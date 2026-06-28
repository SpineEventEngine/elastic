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

internal class FunnelCapacitySpec {

    @Test
    fun `starts at the minimum for an empty or tiny map`() {
        FunnelCapacity.forEntries(0, 0.1) shouldBe FunnelCapacity.MIN
        FunnelCapacity.forEntries(1, 0.1) shouldBe FunnelCapacity.MIN
    }

    @Test
    fun `picks the smallest capacity that holds the expected entries`() {
        for (expected in listOf(100, 1_000, 10_000, 100_000)) {
            val capacity = FunnelCapacity.forEntries(expected, 0.1)
            withClue("expected=$expected capacity=$capacity") {
                (FunnelSizing(capacity, 0.1).maxInserts >= expected) shouldBe true
                // The choice is the smallest in the doubling sequence: the prior step
                // (if any) would not have held the entries.
                if (capacity > FunnelCapacity.MIN) {
                    (FunnelSizing(capacity / 2, 0.1).maxInserts < expected) shouldBe true
                }
            }
        }
    }

    @Test
    fun `doubles the capacity on grow`() {
        FunnelCapacity.grown(FunnelCapacity.MIN) shouldBe FunnelCapacity.MIN * 2
    }

    @Test
    fun `rejects growing beyond the maximum capacity`() {
        shouldThrow<IllegalArgumentException> {
            FunnelCapacity.grown(FunnelCapacity.MAX)
        }
    }

    @Test
    fun `rejects a negative expected size`() {
        shouldThrow<IllegalArgumentException> {
            FunnelCapacity.forEntries(-1, 0.1)
        }
    }

    @Test
    fun `rejects an expected size beyond the maximum capacity`() {
        shouldThrow<IllegalArgumentException> {
            FunnelCapacity.forEntries(Int.MAX_VALUE, 0.1)
        }
    }

    @Test
    fun `rejects a delta outside the open unit interval`() {
        shouldThrow<IllegalArgumentException> {
            FunnelCapacity.forEntries(10, 0.0)
        }
    }
}
