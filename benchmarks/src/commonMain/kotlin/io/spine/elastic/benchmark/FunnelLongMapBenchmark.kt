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

package io.spine.elastic.benchmark

import io.spine.elastic.FunnelLongMap
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Measurement
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.Warmup

/**
 * Measurements of the [FunnelLongMap] with `Long` keys and values, laid out against
 * [StdlibHashMapBenchmark] and [SwissLongMapBenchmark] — same sizes and key set — so
 * the funnel structure's behaviour can be read directly against the standard library
 * and the Phase-1 fast map on each platform (JVM via JMH, and Kotlin/Native).
 *
 * The `delta` parameter sweeps the target empty-fraction: `0.1` (90 % load, the
 * ordinary regime where funnel hashing is *expected to trail* `HashMap` and
 * [io.spine.elastic.SwissLongMap] — it optimises worst-case probe counts, not
 * throughput) and `0.01` (99 % load, the high-load regime the structure is built for).
 * Numbers are framed honestly per `docs/performance-goals.md`: the interest is the
 * high-load insert behaviour and the lookup-at-scale cost, not beating the fast map on
 * general lookups.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
class FunnelLongMapBenchmark {

    @Param("10000", "1000000")
    var size: Int = 0

    @Param("0.1", "0.01")
    var delta: Double = 0.1

    private var keys: LongArray = LongArray(0)
    private var shuffledKeys: LongArray = LongArray(0)
    private var map: FunnelLongMap<Long> = FunnelLongMap()

    @Setup
    fun setup() {
        val n = size
        keys = LongArray(n) { it.toLong() }
        shuffledKeys = shuffle(keys)
        val prepared = FunnelLongMap<Long>(expectedSize = n, delta = delta)
        for (key in keys) {
            prepared.put(key, key)
        }
        map = prepared
    }

    @Benchmark
    fun lookupHit(blackhole: Blackhole) {
        val m = map
        var sink = 0L
        for (key in keys) {
            sink += m[key] ?: 0L
        }
        blackhole.consume(sink)
    }

    @Benchmark
    fun lookupHitShuffled(blackhole: Blackhole) {
        val m = map
        var sink = 0L
        for (key in shuffledKeys) {
            sink += m[key] ?: 0L
        }
        blackhole.consume(sink)
    }

    @Benchmark
    fun lookupMiss(blackhole: Blackhole) {
        val m = map
        val absentOffset = size.toLong()
        var sink = 0L
        for (key in keys) {
            sink += m[key + absentOffset] ?: 0L
        }
        blackhole.consume(sink)
    }

    @Benchmark
    fun insertAllPresized(blackhole: Blackhole) {
        val fresh = FunnelLongMap<Long>(expectedSize = size, delta = delta)
        for (key in keys) {
            fresh.put(key, key)
        }
        blackhole.consume(fresh)
    }

    @Benchmark
    fun insertAllGrowing(blackhole: Blackhole) {
        val fresh = FunnelLongMap<Long>(delta = delta)
        for (key in keys) {
            fresh.put(key, key)
        }
        blackhole.consume(fresh)
    }
}
