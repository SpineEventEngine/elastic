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

package io.spine.elastic.internal

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.test.Test

/**
 * The expected values below are the exact outputs of the `sternma/optopenhash`
 * reference for the same inputs, so this suite is a cross-language cross-check
 * of the ported sizing — not just an internal-consistency test.
 */
internal class ElasticSizingSpec {

    @Test
    fun `partitions capacity into geometric levels matching the reference`() {
        ElasticSizing.levelSizes(16).toList() shouldBe listOf(1, 1, 3, 11)
        ElasticSizing.levelSizes(1000).toList() shouldBe
            listOf(1, 3, 7, 15, 30, 59, 110, 193, 582)
        ElasticSizing.levelSizes(1024).toList() shouldBe
            listOf(1, 1, 3, 7, 15, 31, 60, 113, 198, 595)
    }

    @Test
    fun `level sizes always sum to capacity`() {
        for (capacity in listOf(2, 16, 1000, 1024, 65_536)) {
            ElasticSizing.levelSizes(capacity).sum() shouldBe capacity
        }
    }

    @Test
    fun `derives the level count from log2 of capacity`() {
        ElasticSizing.levelCount(1) shouldBe 1
        ElasticSizing.levelCount(16) shouldBe 4
        ElasticSizing.levelCount(1000) shouldBe 9
        ElasticSizing.levelCount(1024) shouldBe 10
    }

    @Test
    fun `reserves enough empty slots to honor the one-minus-delta load cap`() {
        ElasticSizing.maxInserts(1000, 0.1) shouldBe 900
        // 0.1 * 1024 = 102.4 -> reserve ceil = 103 empty slots (not 102), so the
        // load stays <= 1 - delta. Truncation would give 922 (load 0.9004 > 0.9).
        ElasticSizing.maxInserts(1024, 0.1) shouldBe 921
        for (capacity in listOf(1000, 1024, 4096, 65_536)) {
            val reserved = capacity - ElasticSizing.maxInserts(capacity, 0.1)
            reserved shouldBe ceil(0.1 * capacity).toInt()
        }
    }

    @Test
    fun `splits a power-of-two capacity into largest-first power-of-two levels`() {
        ElasticSizing.binaryLevelSizes(8).toList() shouldBe listOf(4, 2, 1, 1)
        ElasticSizing.binaryLevelSizes(16).toList() shouldBe listOf(8, 4, 2, 1, 1)
        ElasticSizing.binaryLevelSizes(2).toList() shouldBe listOf(1, 1)
    }

    @Test
    fun `binary level sizes sum to capacity and are non-increasing powers of two`() {
        var capacity = ElasticCapacity.MIN
        while (capacity <= 1 shl 20) {
            val sizes = ElasticSizing.binaryLevelSizes(capacity)
            withClue("capacity=$capacity sizes=${sizes.toList()}") {
                sizes.sum() shouldBe capacity
                for (size in sizes) {
                    (size and (size - 1)) shouldBe 0 // a power of two
                }
                for (i in 1 until sizes.size) {
                    (sizes[i] <= sizes[i - 1]) shouldBe true
                }
                // The two trailing size-1 levels make the binary split one longer than
                // the paper/reference-faithful `levelSizes` count — pinned so a future
                // reader does not "align" the two.
                sizes.size shouldBe ElasticSizing.levelCount(capacity) + 1
            }
            capacity *= 2
        }
    }

    @Test
    fun `rejects a non-power-of-two binary level capacity`() {
        shouldThrow<IllegalArgumentException> { ElasticSizing.binaryLevelSizes(1000) }
        shouldThrow<IllegalArgumentException> { ElasticSizing.binaryLevelSizes(1) }
    }

    @Test
    fun `bounds the per-level probe budget by log2 of one over delta`() {
        ElasticSizing.probeLimit(freeFraction = 0.0001, delta = 0.1) shouldBe
            (4 * log2(1.0 / 0.1)).toInt()
    }

    @Test
    fun `treats the budget argument as the free fraction rather than the load`() {
        // A near-empty level (free fraction ~1) needs the single home probe; a near-full
        // level (free fraction <= delta) gets the full c*log2(1/delta) budget. Passing
        // the load (1 - free) instead would invert this, so the boundary numbers lock the
        // semantics: budget 1 when nearly empty, 13 = floor(4*log2(10)) when nearly full.
        ElasticSizing.probeLimit(freeFraction = 0.99, delta = 0.1) shouldBe 1
        ElasticSizing.probeLimit(freeFraction = 0.05, delta = 0.1) shouldBe
            (4 * log2(1.0 / 0.1)).toInt()
    }

    @Test
    fun `keeps the probe budget at least one`() {
        ElasticSizing.probeLimit(freeFraction = 0.9999, delta = 0.1) shouldBe 1
    }

    @Test
    fun `rejects invalid capacity and delta`() {
        shouldThrow<IllegalArgumentException> { ElasticSizing.levelCount(0) }
        shouldThrow<IllegalArgumentException> { ElasticSizing.levelSizes(-1) }
        shouldThrow<IllegalArgumentException> { ElasticSizing.maxInserts(0, 0.1) }
        shouldThrow<IllegalArgumentException> { ElasticSizing.maxInserts(100, 0.0) }
        shouldThrow<IllegalArgumentException> {
            ElasticSizing.probeLimit(freeFraction = 0.5, delta = 1.0)
        }
    }
}
