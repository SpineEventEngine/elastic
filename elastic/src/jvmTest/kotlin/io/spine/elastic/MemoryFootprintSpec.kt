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

package io.spine.elastic

import io.kotest.matchers.shouldBe
import org.openjdk.jol.info.GraphLayout
import kotlin.test.Test

/**
 * Measures the **retained heap footprint** of the maps to demonstrate the memory
 * advantage of primitive storage — the Phase 1 win that the time benchmarks do not
 * surface.
 *
 * Footprint is the exact retained size of each map's whole object graph, computed by
 * JOL ([GraphLayout.totalSize]) on the JVM — deterministic, not a GC estimate. Each
 * map is pre-sized for `n` in its own units (the per-map pre-sizing fairness rule),
 * and `n` is the `7/8` max load of a `2^18`-slot open-addressing table, so the
 * comparison is at a realistic, equal-occupancy point. Numbers are written to the
 * scratchpad for reporting; the asserted ordering is what the test guards.
 */
internal class MemoryFootprintSpec {

    @Test
    fun `primitive storage retains far less heap than HashMap`() {
        // n = the 7/8 max load of a 2^18-slot table, so SwissLongMap/LongLongMap sit
        // at exactly capacity 2^18 with no slack from power-of-two rounding.
        val n = LOAD_NUMERATOR * (1 shl LOG2_CAPACITY) / LOAD_DENOMINATOR

        val hashMapBpe = retainedBytesPerEntry(n) {
            // Pre-size for n at the JDK's 0.75 load, in HashMap's own units.
            val map = HashMap<Long, Long>((n / JDK_LOAD_FACTOR).toInt() + 1)
            for (i in 0 until n) {
                map[i.toLong()] = i.toLong()
            }
            map
        }
        val swissBpe = retainedBytesPerEntry(n) {
            val map = SwissLongMap<Long>(expectedSize = n)
            for (i in 0 until n) {
                map.put(i.toLong(), i.toLong())
            }
            map
        }
        val longLongBpe = retainedBytesPerEntry(n) {
            val map = LongLongMap(expectedSize = n)
            for (i in 0 until n) {
                map.put(i.toLong(), i.toLong())
            }
            map
        }

        val report = "Retained bytes/entry @ n=$n: " +
            "HashMap<Long,Long>=$hashMapBpe  SwissLongMap<Long>=$swissBpe  LongLongMap=$longLongBpe"
        println(report)
        runCatching {
            java.io.File(System.getenv("FOOTPRINT_OUT") ?: "build/footprint.txt").writeText(report)
        }

        // Both primitive-storage maps retain less than HashMap's node-and-box-per-entry
        // layout; dropping the value box makes LongLongMap the smallest.
        (hashMapBpe > swissBpe) shouldBe true
        (swissBpe > longLongBpe) shouldBe true
    }

    private inline fun retainedBytesPerEntry(entries: Int, build: () -> Any): Long {
        val map = build()
        return GraphLayout.parseInstance(map).totalSize() / entries
    }

    private companion object {
        const val LOG2_CAPACITY = 18
        const val LOAD_NUMERATOR = 7
        const val LOAD_DENOMINATOR = 8
        const val JDK_LOAD_FACTOR = 0.75
    }
}
