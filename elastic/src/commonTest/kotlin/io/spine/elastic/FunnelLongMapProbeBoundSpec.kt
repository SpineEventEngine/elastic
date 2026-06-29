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

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.spine.elastic.internal.FunnelCapacity
import io.spine.elastic.internal.FunnelSizing
import kotlin.test.Test

/**
 * Turns the paper's worst-case bound into a CI regression metric (decision DP-11).
 *
 * Funnel hashing inspects at most one `beta`-slot bucket per level over `levelCount`
 * levels, plus the special array's linear probe of `specialProbeLimit` slots. A single
 * operation therefore never examines more than `levelCount*beta + specialProbeLimit`
 * slots — `O(alpha*beta + log log n) = O(log² 1/delta)`. The map exposes that
 * ceiling as [FunnelLongMap.maxProbesPerOp] and the slots examined by the last search
 * as [FunnelLongMap.lastProbes]; this verifies the ceiling is never exceeded for a
 * large sample of present and absent keys at high load, and that the typical search is
 * far cheaper (the amortized `O(log 1/delta)`).
 */
internal class FunnelLongMapProbeBoundSpec {

    @Test
    fun `keeps every operation within the structural probe ceiling at high load`() {
        for (delta in listOf(0.1, 0.25)) {
            // Fill to the table's `maxInserts` so the load sits at ~1 - delta, where
            // the deepest descents and special-array probes actually occur — that is
            // where the worst-case bound must hold.
            val capacity = FunnelCapacity.forEntries(4_000, delta)
            val fill = FunnelSizing(capacity, delta).maxInserts
            val map = FunnelLongMap<Long>(expectedSize = fill, delta = delta)
            for (key in 0 until fill) {
                map.put(key.toLong(), key.toLong())
            }
            val ceiling = map.maxProbesPerOp
            var totalProbes = 0L
            var maxProbes = 0
            // Half present (0 until fill), half absent (fill until 2*fill); absent
            // keys drive the full descent plus the special-array probe path.
            val sampleSize = 2 * fill
            for (i in 0 until sampleSize) {
                map.containsKey(i.toLong())
                val probes = map.lastProbes
                totalProbes += probes
                if (probes > maxProbes) {
                    maxProbes = probes
                }
            }
            withClue("delta=$delta: max probes $maxProbes must not exceed ceiling $ceiling") {
                (maxProbes <= ceiling) shouldBe true
            }
            val meanProbes = totalProbes.toDouble() / sampleSize
            withClue("delta=$delta: mean probes $meanProbes should stay below ceiling $ceiling") {
                (meanProbes < ceiling.toDouble()) shouldBe true
            }
        }
    }
}
