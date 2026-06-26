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

import io.kotest.matchers.shouldBe
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
    fun `computes max insertions from the target empty-fraction`() {
        ElasticSizing.maxInserts(1000, 0.1) shouldBe 900
        ElasticSizing.maxInserts(1024, 0.1) shouldBe 922
    }

    @Test
    fun `bounds the per-level probe budget by log2 of one over delta`() {
        ElasticSizing.probeLimit(load = 0.0001, delta = 0.1) shouldBe
            (4 * log2(1.0 / 0.1)).toInt()
    }

    @Test
    fun `keeps the probe budget at least one`() {
        ElasticSizing.probeLimit(load = 0.9999, delta = 0.1) shouldBe 1
    }
}
