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

import io.spine.elastic.LongHasher
import io.spine.elastic.LongLongMap
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
 * Measurements of the fully primitive [LongLongMap] (`Long` keys *and* `Long`
 * values, neither boxed), the true primitive-versus-primitive counterpart of
 * `java.util.HashMap<Long, Long>` in [StdlibHashMapBenchmark].
 *
 * Same sizes and key set as the other benchmarks. Lookups sum the returned `long`
 * into a sink consumed once through the [Blackhole], so the hot path stays free of
 * the boxing a per-result `consume` would introduce — the whole point of this map.
 * `lookupHitShuffled` is the fair random-access gate (see [SwissLongMapBenchmark]).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
class LongLongMapBenchmark {

    @Param("10000", "1000000")
    var size: Int = 0

    private var keys: LongArray = LongArray(0)
    private var shuffledKeys: LongArray = LongArray(0)
    private var map: LongLongMap = LongLongMap()
    private var fastHashMap: LongLongMap = LongLongMap()

    @Setup
    fun setup() {
        val n = size
        keys = LongArray(n) { it.toLong() }
        shuffledKeys = shuffle(keys)
        val prepared = LongLongMap(expectedSize = n)
        for (key in keys) {
            prepared.put(key, key)
        }
        map = prepared
        // Same map but with the single-multiply Fibonacci hasher instead of the
        // default fmix64 (two multiplies) — to quantify the hash's in-cache cost.
        val fast = LongLongMap(expectedSize = n, hasher = LongHasher.Fibonacci)
        for (key in keys) {
            fast.put(key, key)
        }
        fastHashMap = fast
    }

    @Benchmark
    fun lookupHit(blackhole: Blackhole) {
        val m = map
        var sink = 0L
        for (key in keys) {
            sink += m[key]
        }
        blackhole.consume(sink)
    }

    @Benchmark
    fun lookupHitShuffled(blackhole: Blackhole) {
        val m = map
        var sink = 0L
        for (key in shuffledKeys) {
            sink += m[key]
        }
        blackhole.consume(sink)
    }

    @Benchmark
    fun lookupHitShuffledFastHash(blackhole: Blackhole) {
        val m = fastHashMap
        var sink = 0L
        for (key in shuffledKeys) {
            sink += m[key]
        }
        blackhole.consume(sink)
    }

    @Benchmark
    fun insertAllPresized(blackhole: Blackhole) {
        val fresh = LongLongMap(expectedSize = size)
        for (key in keys) {
            fresh.put(key, key)
        }
        blackhole.consume(fresh)
    }

    @Benchmark
    fun insertAllGrowing(blackhole: Blackhole) {
        val fresh = LongLongMap()
        for (key in keys) {
            fresh.put(key, key)
        }
        blackhole.consume(fresh)
    }
}
