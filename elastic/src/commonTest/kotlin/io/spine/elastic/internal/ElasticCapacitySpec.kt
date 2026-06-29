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
import kotlin.test.Test

internal class ElasticCapacitySpec {

    @Test
    fun `starts at the minimum for an empty or tiny map`() {
        ElasticCapacity.forEntries(0, 0.1) shouldBe ElasticCapacity.MIN
        ElasticCapacity.forEntries(1, 0.1) shouldBe ElasticCapacity.MIN
    }

    @Test
    fun `picks the smallest power-of-two capacity that holds the expected entries`() {
        for (expected in listOf(100, 1_000, 10_000, 100_000)) {
            val capacity = ElasticCapacity.forEntries(expected, 0.1)
            withClue("expected=$expected capacity=$capacity") {
                (capacity and (capacity - 1)) shouldBe 0 // a power of two
                (ElasticSizing.maxInserts(capacity, 0.1) >= expected) shouldBe true
                // The choice is the smallest in the doubling sequence: the prior step
                // could not have held the entries.
                if (capacity > ElasticCapacity.MIN) {
                    (ElasticSizing.maxInserts(capacity / 2, 0.1) < expected) shouldBe true
                }
            }
        }
    }

    @Test
    fun `sizes purely by the load budget since every slot is addressable`() {
        // Unlike funnel, elastic's power-of-two levels sum to exactly the capacity and
        // are fully covered by triangular probing, so maxInserts alone is the bound.
        for (delta in listOf(0.01, 0.1, 0.25)) {
            for (expected in listOf(50, 500, 5_000)) {
                val capacity = ElasticCapacity.forEntries(expected, delta)
                withClue("delta=$delta expected=$expected capacity=$capacity") {
                    (ElasticSizing.maxInserts(capacity, delta) >= expected) shouldBe true
                }
            }
        }
    }

    @Test
    fun `doubles the capacity on grow`() {
        ElasticCapacity.grown(ElasticCapacity.MIN) shouldBe ElasticCapacity.MIN * 2
    }

    @Test
    fun `rejects growing beyond the maximum capacity`() {
        shouldThrow<IllegalArgumentException> {
            ElasticCapacity.grown(ElasticCapacity.MAX)
        }
    }

    @Test
    fun `rejects a negative expected size`() {
        shouldThrow<IllegalArgumentException> {
            ElasticCapacity.forEntries(-1, 0.1)
        }
    }

    @Test
    fun `rejects an expected size beyond the maximum capacity`() {
        shouldThrow<IllegalArgumentException> {
            ElasticCapacity.forEntries(Int.MAX_VALUE, 0.1)
        }
    }

    @Test
    fun `rejects a delta outside the open unit interval`() {
        shouldThrow<IllegalArgumentException> {
            ElasticCapacity.forEntries(10, 0.0)
        }
    }
}
