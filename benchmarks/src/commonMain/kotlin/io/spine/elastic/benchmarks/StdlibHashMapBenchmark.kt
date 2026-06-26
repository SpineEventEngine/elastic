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
 * Baseline measurements of the standard-library [HashMap] with boxed `Long`
 * keys and values.
 *
 * This is the bar the Phase 1 primitive `Long → V` map must beat (and the proof
 * that the cross-platform harness works end-to-end). It runs on JVM (via JMH)
 * and on Kotlin/Native, so the per-platform baselines are captured from the
 * outset. `lookupHit` and `insertAll` are deliberately separate operations
 * (decision DP-4 / §1.4 of the plan); each result is consumed through a
 * [Blackhole] to defeat dead-code elimination.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
class StdlibHashMapBenchmark {

    @Param("10000", "1000000")
    var size: Int = 0

    private var keys: LongArray = LongArray(0)
    private var map: HashMap<Long, Long> = HashMap()

    @Setup
    fun setup() {
        val n = size
        keys = LongArray(n) { it.toLong() }
        val prepared = HashMap<Long, Long>(n * 2)
        for (key in keys) {
            prepared[key] = key
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
    fun insertAll(blackhole: Blackhole) {
        val fresh = HashMap<Long, Long>()
        for (key in keys) {
            fresh[key] = key
        }
        blackhole.consume(fresh)
    }
}
