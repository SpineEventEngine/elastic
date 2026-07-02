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

package io.spine.elastic.benchmark.jmh

import io.spine.elastic.SingleWriterSwissLongMap
import io.spine.elastic.SwissLongMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Threads
import org.openjdk.jmh.annotations.Warmup

/**
 * Read-only lookup throughput at 1, 2, 4, and 8 threads over a pre-populated map.
 *
 * [SingleWriterSwissLongMap]'s readers take no locks and write no shared state, so
 * their throughput should scale near-linearly with thread count until memory bandwidth
 * saturates. Two baselines run at the same thread counts: `ConcurrentHashMap` — the
 * standard-library concurrent baseline, with boxed keys and values — and [SwissLongMap]
 * guarded by a single shared lock, which is what a caller gets by simply locking the
 * fast single-threaded map; it serializes every lookup and is expected to flatten or
 * degrade as threads are added.
 *
 * Fairness caveats: scaling numbers depend on CPU topology (core count, SMT, shared
 * caches), so always report the hardware alongside published results; and these runs
 * measure throughput only — they make no latency claims.
 *
 * Each thread walks the shuffled key set from its own random starting position, one
 * lookup per invocation, and returns the value so JMH blackholes it. JMH runs
 * separate forks per `impl` value, so each fork's call sites stay monomorphic
 * through the adapter.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
open class ReadScalingBenchmark {

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

    /** A per-thread position on the shuffled key set. */
    @State(Scope.Thread)
    open class Cursor {

        /** The index of the next key to look up. */
        var index: Int = 0

        /** Starts each thread at its own random position, so threads do not scan in lockstep. */
        @Setup(Level.Iteration)
        fun randomizeStart(benchmark: ReadScalingBenchmark) {
            index = ThreadLocalRandom.current().nextInt(benchmark.size)
        }
    }

    @Benchmark
    @Threads(1)
    fun readT1(cursor: Cursor): Long = lookup(cursor)

    @Benchmark
    @Threads(2)
    fun readT2(cursor: Cursor): Long = lookup(cursor)

    @Benchmark
    @Threads(4)
    fun readT4(cursor: Cursor): Long = lookup(cursor)

    @Benchmark
    @Threads(8)
    fun readT8(cursor: Cursor): Long = lookup(cursor)

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
