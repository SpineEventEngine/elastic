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
import io.spine.elastic.internal.ElasticCapacity
import io.spine.elastic.internal.ElasticSizing
import kotlin.test.Test

/**
 * Turns the paper's bounds into CI regression metrics (decision DP-11).
 *
 * For elastic hashing the contribution that this level-by-level realization actually
 * delivers is the **`O(1)`-amortized insertion**: the non-greedy budget-plus-deferral
 * touches only a couple of adjacent levels, so the mean number of slots a placement
 * examines is small **and independent of the table size** `n` — that is what this suite
 * gates on (the design review measured ≈3.4 placement probes/insert across capacities
 * 256…65536 at delta 0.1). The worst-case *search* bound is the part the dropped `φ`
 * injection would tighten, so search is only **measured and reported** here, not gated:
 * at very high load a level-by-level search can scan more than the `φ`-interleaved ideal.
 */
internal class ElasticLongMapProbeBoundSpec {

    @Test
    fun `keeps amortized insertion probes small and independent of table size`() {
        val delta = 0.1
        // A generous, n-independent ceiling: the observed mean is ≈3.4. If this held only
        // by growing with n, the largest table would breach it — so passing at every size
        // is the n-independence assertion.
        val ceiling = 8.0
        for (entries in listOf(500, 4_000, 32_000)) {
            val capacity = ElasticCapacity.forEntries(entries, delta)
            val fill = ElasticSizing.maxInserts(capacity, delta)
            // Pre-sized so the whole fill happens with no rebuild: every placement is a
            // steady-state non-greedy descent, which is what the metric measures.
            val map = ElasticLongMap<Long>(expectedSize = fill, delta = delta)
            var totalPlacementProbes = 0L
            for (key in 0L until fill.toLong()) {
                map.put(key, key)
                totalPlacementProbes += map.lastPlacementProbes
            }
            map.tableCapacity shouldBe capacity // no rebuild happened
            val mean = totalPlacementProbes.toDouble() / fill
            withClue("entries=$entries capacity=$capacity: mean placement probes $mean") {
                (mean < ceiling) shouldBe true
            }
        }
    }

    @Test
    fun `lookups at high load stay finite and are reported`() {
        // Search is not gated (phi would tighten its worst case), but it must remain
        // finite and reasonable for a well-distributed hasher at high load. This both
        // guards against an unbounded probe and documents the lookup-at-scale cost.
        val delta = 0.1
        val capacity = ElasticCapacity.forEntries(16_000, delta)
        val fill = ElasticSizing.maxInserts(capacity, delta)
        val map = ElasticLongMap<Long>(expectedSize = fill, delta = delta)
        for (key in 0L until fill.toLong()) {
            map.put(key, key)
        }
        var maxProbes = 0
        var totalProbes = 0L
        for (key in 0L until fill.toLong()) {
            map.containsKey(key) shouldBe true
            val probes = map.lastProbes
            totalProbes += probes
            if (probes > maxProbes) {
                maxProbes = probes
            }
        }
        val mean = totalProbes.toDouble() / fill
        // No single lookup may exceed the trivial structural ceiling (every slot once).
        withClue("max present-key probes $maxProbes must not exceed capacity $capacity") {
            (maxProbes <= capacity) shouldBe true
        }
        // For a good hasher at ~90% load the mean stays modest; a generous bound guards a
        // regression without being brittle.
        withClue("mean present-key probes $mean should stay modest at high load") {
            (mean < 50.0) shouldBe true
        }
    }
}
