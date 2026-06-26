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
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The expected values below are the exact outputs of the `sternma/optopenhash`
 * reference for the same inputs — a cross-language cross-check of the ported
 * funnel sizing.
 */
internal class FunnelSizingSpec {

    @Test
    fun `sizes the table for delta 0_1 and capacity 1000`() {
        val sizing = FunnelSizing(1000, 0.1)
        sizing.alpha shouldBe 24
        sizing.beta shouldBe 7
        sizing.specialSize shouldBe 75
        sizing.primarySize shouldBe 925
        sizing.totalBuckets shouldBe 132
        sizing.maxInserts shouldBe 900
        sizing.levelBucketCounts.toList() shouldBe
            listOf(33, 25, 19, 14, 10, 8, 6, 4, 3, 2, 2, 1, 1, 1, 1, 1, 1)
    }

    @Test
    fun `sizes the table for delta 0_1 and capacity 10000`() {
        val sizing = FunnelSizing(10_000, 0.1)
        sizing.alpha shouldBe 24
        sizing.beta shouldBe 7
        sizing.specialSize shouldBe 750
        sizing.primarySize shouldBe 9250
        sizing.totalBuckets shouldBe 1321
        sizing.levelBucketCounts.toList() shouldBe
            listOf(
                331, 248, 186, 139, 105, 78, 59, 44, 33, 25, 19, 14,
                10, 8, 6, 4, 3, 2, 2, 1, 1, 1, 1, 1
            )
    }

    @Test
    fun `sizes the table for delta 0_25 and capacity 1000`() {
        val sizing = FunnelSizing(1000, 0.25)
        sizing.alpha shouldBe 18
        sizing.beta shouldBe 4
        sizing.specialSize shouldBe 187
        sizing.primarySize shouldBe 813
        sizing.totalBuckets shouldBe 203
        sizing.levelBucketCounts.toList() shouldBe
            listOf(51, 38, 29, 22, 16, 12, 9, 7, 5, 4, 3, 2, 2, 1, 1, 1)
    }

    @Test
    fun `keeps bucket counts summing to the total bucket budget`() {
        val cases = listOf(1000 to 0.1, 10_000 to 0.1, 1000 to 0.25, 50_000 to 0.05)
        for ((capacity, delta) in cases) {
            val sizing = FunnelSizing(capacity, delta)
            sizing.levelBucketCounts.sum() shouldBe sizing.totalBuckets
        }
    }

    @Test
    fun `rejects non-positive capacity`() {
        shouldThrow<IllegalArgumentException> { FunnelSizing(0, 0.1) }
        shouldThrow<IllegalArgumentException> { FunnelSizing(-5, 0.1) }
    }

    @Test
    fun `rejects delta outside the open unit interval`() {
        shouldThrow<IllegalArgumentException> { FunnelSizing(100, 0.0) }
        shouldThrow<IllegalArgumentException> { FunnelSizing(100, 1.0) }
    }
}
