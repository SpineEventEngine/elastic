/*
 * Copyright 2026, TeamDev. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Redistribution and use in source and/or binary forms, with or without
 * modification, must retain the above copyright notice and the following
 * disclaimer.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.spine.elastic.benchmarks.jmh

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Group
import org.openjdk.jmh.annotations.GroupThreads
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup

/**
 * Asymmetric mixed load — one writer thread and three reader threads on the same map —
 * expressed with JMH thread groups.
 *
 * Two groups measure two writer workloads against the same reader traffic:
 *
 * - `overwriteMix` — the writer overwrites existing keys round-robin. This is the
 *   steady state: no fresh slot is ever consumed, so the single-writer map never
 *   rebuilds, and the group isolates the cost of concurrent value stores under reads.
 * - `churnMix` — the writer inserts fresh keys beyond the pre-filled range and removes
 *   the oldest previously churned key, maintaining a sliding window. Churn is the
 *   honest stress case for the no-tombstone-reuse design: every insert consumes growth
 *   budget and removals return none, so the writer periodically rebuilds and publishes
 *   whole tables while the readers run. Readers look up the stable pre-filled range
 *   only, so hits stay hits throughout.
 *
 * Single-writer contract: only the `@GroupThreads(1)` writer methods mutate the map,
 * so the `SINGLE_WRITER` implementation runs within its contract. Run with the default
 * thread count — one group instance; scaling to several groups would put several
 * writers on the shared map.
 *
 * Baselines and fairness caveats match `ReadScalingBenchmark`: report CPU topology
 * alongside published numbers; throughput mode makes no latency claims.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
open class MixedLoadBenchmark {

    @Param(SINGLE_WRITER, CONCURRENT_HASH_MAP, SYNCHRONIZED_SWISS)
    lateinit var impl: String

    @Param("1000000")
    var size: Int = 0

    private var shuffledKeys: LongArray = LongArray(0)
    private lateinit var map: LongToLongMap

    @Setup(Level.Trial)
    fun setUp() {
        val keys = LongArray(size) { it.toLong() }
        shuffledKeys = shuffled(keys)
        val prepared = createMap(impl, size)
        for (key in keys) {
            prepared.put(key, key)
        }
        map = prepared
    }

    /** A per-thread position on the shuffled key set, for the reader methods. */
    @State(Scope.Thread)
    open class Cursor {

        /** The index of the next key to look up. */
        var index: Int = 0

        /** Starts each thread at its own random position, so threads do not scan in lockstep. */
        @Setup(Level.Iteration)
        fun randomizeStart(benchmark: MixedLoadBenchmark) {
            index = ThreadLocalRandom.current().nextInt(benchmark.size)
        }
    }

    /**
     * The overwrite writer's position on the shuffled key set. Thread-scoped state is
     * writer-private because `@GroupThreads(1)` admits exactly one writer thread.
     */
    @State(Scope.Thread)
    open class OverwriteCursor {

        /** The index of the next key to overwrite. */
        var index: Int = 0

        @Setup(Level.Trial)
        fun randomizeStart(benchmark: MixedLoadBenchmark) {
            index = ThreadLocalRandom.current().nextInt(benchmark.size)
        }
    }

    /**
     * The churn writer's sliding window over fresh keys. Thread-scoped state is
     * writer-private because `@GroupThreads(1)` admits exactly one writer thread.
     */
    @State(Scope.Thread)
    open class ChurnCursor {

        /** The next fresh key, strictly beyond the pre-filled range. */
        var nextFresh: Long = 0

        /** The oldest churned key still present in the map. */
        var oldest: Long = 0

        @Setup(Level.Trial)
        fun startBeyondPrefill(benchmark: MixedLoadBenchmark) {
            nextFresh = benchmark.size.toLong()
            oldest = nextFresh
        }
    }

    /** Overwrites an existing key; never consumes a fresh slot, so no rebuilds. */
    @Benchmark
    @Group("overwriteMix")
    @GroupThreads(1)
    fun overwriteWriter(cursor: OverwriteCursor) {
        val keys = shuffledKeys
        val index = cursor.index
        val key = keys[index]
        map.put(key, key)
        val next = index + 1
        cursor.index = if (next == keys.size) 0 else next
    }

    @Benchmark
    @Group("overwriteMix")
    @GroupThreads(3)
    fun overwriteReader(cursor: Cursor): Long = lookup(cursor)

    /**
     * Inserts a fresh key and retires the oldest churned one, keeping a sliding
     * window of churned entries; the pre-filled range is never touched.
     */
    @Benchmark
    @Group("churnMix")
    @GroupThreads(1)
    fun churnWriter(cursor: ChurnCursor) {
        val fresh = cursor.nextFresh
        map.put(fresh, fresh)
        cursor.nextFresh = fresh + 1
        val oldest = cursor.oldest
        if (fresh - oldest >= CHURN_WINDOW) {
            map.remove(oldest)
            cursor.oldest = oldest + 1
        }
    }

    @Benchmark
    @Group("churnMix")
    @GroupThreads(3)
    fun churnReader(cursor: Cursor): Long = lookup(cursor)

    /** One lookup per invocation; the value is returned so JMH blackholes it. */
    private fun lookup(cursor: Cursor): Long {
        val keys = shuffledKeys
        val index = cursor.index
        val value = map.get(keys[index])
        val next = index + 1
        cursor.index = if (next == keys.size) 0 else next
        return value
    }
}

/** The number of churned entries the writer keeps live beyond the pre-filled range. */
private const val CHURN_WINDOW = 1024L
