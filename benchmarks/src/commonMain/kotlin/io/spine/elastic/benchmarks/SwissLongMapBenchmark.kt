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

package io.spine.elastic.benchmarks

import io.spine.elastic.SwissLongMap
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
 * Measurements of the primitive [SwissLongMap] with `Long` keys and values, laid
 * out to be read directly against [StdlibHashMapBenchmark] — the same sizes and
 * key set, so the Phase 1 "faster than stdlib" claim is a like-for-like comparison
 * on each platform (JVM via JMH, and Kotlin/Native).
 *
 * It runs the baseline's operations — `lookupHit`; `insertAllPresized`, the fair
 * steady-state insert with the map pre-sized in its own units ([SwissLongMap]'s
 * `expectedSize`); and `insertAllGrowing`, the same inserts into a default-capacity
 * map, folding in resize/rehash cost — plus `lookupMiss` (keys absent from the map,
 * exercising the stop-on-empty probe). Each result is consumed through a [Blackhole]
 * to defeat dead-code elimination.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
class SwissLongMapBenchmark {

    @Param("10000", "1000000")
    var size: Int = 0

    private var keys: LongArray = LongArray(0)
    private var shuffledKeys: LongArray = LongArray(0)
    private var map: SwissLongMap<Long> = SwissLongMap()

    @Setup
    fun setup() {
        val n = size
        keys = LongArray(n) { it.toLong() }
        shuffledKeys = shuffle(keys)
        val prepared = SwissLongMap<Long>(expectedSize = n)
        for (key in keys) {
            prepared.put(key, key)
        }
        map = prepared
    }

    @Benchmark
    fun lookupHit(blackhole: Blackhole) {
        val m = map
        for (key in keys) {
            blackhole.consume(m[key])
        }
    }

    @Benchmark
    fun lookupHitShuffled(blackhole: Blackhole) {
        val m = map
        for (key in shuffledKeys) {
            blackhole.consume(m[key])
        }
    }

    @Benchmark
    fun lookupMiss(blackhole: Blackhole) {
        val m = map
        val absentOffset = size.toLong()
        for (key in keys) {
            blackhole.consume(m[key + absentOffset])
        }
    }

    @Benchmark
    fun insertAllPresized(blackhole: Blackhole) {
        val fresh = SwissLongMap<Long>(expectedSize = size)
        for (key in keys) {
            fresh.put(key, key)
        }
        blackhole.consume(fresh)
    }

    @Benchmark
    fun insertAllGrowing(blackhole: Blackhole) {
        val fresh = SwissLongMap<Long>()
        for (key in keys) {
            fresh.put(key, key)
        }
        blackhole.consume(fresh)
    }
}
