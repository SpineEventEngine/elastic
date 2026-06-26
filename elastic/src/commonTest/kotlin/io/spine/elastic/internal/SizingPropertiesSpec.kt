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

import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Property-based checks of the sizing invariants over a wide range of inputs.
 *
 * This both guards the invariants the structures rely on and exercises the
 * `kotest-property` scaffolding on every target (JVM and Native).
 */
internal class SizingPropertiesSpec {

    @Test
    fun elasticLevelSizesAlwaysSumToCapacity() = runTest {
        checkAll(Arb.int(2..50_000_000)) { capacity ->
            ElasticSizing.levelSizes(capacity).sum() shouldBe capacity
        }
    }

    @Test
    fun elasticLevelCountMatchesTheSizesLength() = runTest {
        checkAll(Arb.int(2..50_000_000)) { capacity ->
            ElasticSizing.levelSizes(capacity).size shouldBe
                ElasticSizing.levelCount(capacity)
        }
    }

    @Test
    fun funnelBucketCountsAlwaysSumToTheTotalBudget() = runTest {
        checkAll(Arb.int(200..10_000_000), deltas()) { capacity, delta ->
            val sizing = FunnelSizing(capacity, delta)
            sizing.levelBucketCounts.sum() shouldBe sizing.totalBuckets
        }
    }

    @Test
    fun funnelMaxInsertsStaysBelowCapacity() = runTest {
        checkAll(Arb.int(200..10_000_000), deltas()) { capacity, delta ->
            val sizing = FunnelSizing(capacity, delta)
            sizing.maxInserts shouldBeGreaterThan 0
            (capacity - sizing.maxInserts) shouldBeGreaterThan 0
        }
    }

    /** Valid target empty-fractions in `(0, 1)`: 0.01 … 0.49, no edge cases. */
    private fun deltas(): Arb<Double> = Arb.int(1..49).map { it / 100.0 }
}
