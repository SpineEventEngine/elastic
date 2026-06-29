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

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.spine.elastic.internal.ElasticCapacity
import io.spine.elastic.internal.ElasticSizing
import kotlin.test.Test

internal class ElasticLongMapSpec {

    @Test
    fun `starts empty`() {
        val map = ElasticLongMap<Long>()
        map.size shouldBe 0
        map.isEmpty() shouldBe true
        map[42L].shouldBeNull()
        map.containsKey(42L) shouldBe false
        map.remove(42L).shouldBeNull()
    }

    @Test
    fun `inserts and reads back a value`() {
        val map = ElasticLongMap<String>()
        map.put(1L, "one").shouldBeNull()
        map[1L] shouldBe "one"
        map.containsKey(1L) shouldBe true
        map.size shouldBe 1
        map.isEmpty() shouldBe false
    }

    @Test
    fun `overwrites an existing key and returns the previous value`() {
        val map = ElasticLongMap<String>()
        map.put(1L, "one")
        map.put(1L, "uno") shouldBe "one"
        map[1L] shouldBe "uno"
        map.size shouldBe 1
    }

    @Test
    fun `removes a key and returns the previous value`() {
        val map = ElasticLongMap<String>()
        map.put(7L, "seven")
        map.remove(7L) shouldBe "seven"
        map.containsKey(7L) shouldBe false
        map[7L].shouldBeNull()
        map.size shouldBe 0
        map.remove(7L).shouldBeNull()
    }

    @Test
    fun `distinguishes a null value from an absent key`() {
        val map = ElasticLongMap<String?>()
        map.put(1L, null).shouldBeNull()
        map.containsKey(1L) shouldBe true
        map[1L].shouldBeNull()
        map.size shouldBe 1

        map.put(2L, "two")
        map.put(2L, null) shouldBe "two"
        map.containsKey(2L) shouldBe true
        map[2L].shouldBeNull()
        // Removing a key whose value is null returns that null as the previous value,
        // and the key really goes away (presence is tracked by the slot, not the value).
        map.remove(1L).shouldBeNull()
        map.containsKey(1L) shouldBe false
        map.size shouldBe 1
    }

    @Test
    fun `handles boundary key values`() {
        val map = ElasticLongMap<Long>()
        val keys = listOf(0L, -1L, 1L, Long.MIN_VALUE, Long.MAX_VALUE)
        for (key in keys) {
            map.put(key, key)
        }
        map.size shouldBe keys.size
        for (key in keys) {
            map[key] shouldBe key
        }
    }

    @Test
    fun `grows across many insertions from the default capacity`() {
        val map = ElasticLongMap<Long>()
        val count = 5_000L
        for (key in 0L until count) {
            map.put(key, key * 3L)
        }
        map.size shouldBe count.toInt()
        for (key in 0L until count) {
            map[key] shouldBe key * 3L
        }
        map.containsKey(count) shouldBe false
    }

    @Test
    fun `holds the entries it was pre-sized for without growing`() {
        val count = 4_000
        val delta = 0.1
        val expectedCapacity = ElasticCapacity.forEntries(count, delta)
        val map = ElasticLongMap<Int>(expectedSize = count, delta = delta)
        for (key in 0 until count) {
            map.put(key.toLong(), key)
        }
        map.size shouldBe count
        // Pre-sizing means zero rebuilds: the capacity is exactly the pre-sized one.
        map.tableCapacity shouldBe expectedCapacity
        for (key in 0 until count) {
            map[key.toLong()] shouldBe key
        }
    }

    @Test
    fun `fills a pre-sized table to its load budget without premature growth`() {
        // The stranding guard: under the used-based eps, Case 2 can skip a level that
        // still has a free slot. The table-wide greedy fallback must place the key
        // anyway, so the table reaches maxInserts with NO growth — even with a
        // degenerate hasher that funnels every key down one probe path.
        for (delta in listOf(0.1, 0.25)) {
            val capacity = ElasticCapacity.forEntries(2_000, delta)
            val fill = ElasticSizing.maxInserts(capacity, delta)
            val map = ElasticLongMap<Long>(expectedSize = fill, delta = delta, hasher = { 0L })
            shouldNotThrowAny {
                for (key in 0L until fill.toLong()) {
                    map.put(key, key)
                }
            }
            withClue("delta=$delta: filled $fill into capacity $capacity, no growth expected") {
                map.size shouldBe fill
                map.tableCapacity shouldBe capacity
            }
            for (key in 0L until fill.toLong()) {
                map[key] shouldBe key
            }
        }
    }

    @Test
    fun `grows exactly once at the entry past the load budget`() {
        val delta = 0.1
        val capacity = ElasticCapacity.forEntries(1_000, delta)
        val maxInserts = ElasticSizing.maxInserts(capacity, delta)
        val map = ElasticLongMap<Long>(expectedSize = maxInserts, delta = delta)
        for (key in 0L until maxInserts.toLong()) {
            map.put(key, key)
        }
        // Exactly the load budget fits with no growth (no off-by-one).
        map.tableCapacity shouldBe capacity
        // One more entry forces a single doubling.
        map.put(maxInserts.toLong(), maxInserts.toLong())
        map.tableCapacity shouldBe capacity * 2
        map.size shouldBe maxInserts + 1
    }

    @Test
    fun `degrades gracefully on a constant hasher instead of throwing`() {
        // Unlike FunnelLongMap (which throws), ElasticLongMap's full-coverage levels
        // absorb a degenerate hasher by filling completely and growing — every key stays
        // findable and NO exception is thrown. But it is not free: search probes blow up
        // to ~Theta(capacity), the regression this test makes visible.
        val map = ElasticLongMap<Long>(hasher = { 0L })
        val count = 1_000L
        shouldNotThrowAny {
            for (key in 0L until count) {
                map.put(key, key)
            }
        }
        map.size shouldBe count.toInt()
        for (key in 0L until count) {
            map[key] shouldBe key
        }
        // It grew (graceful growth, budget-driven), not threw.
        map.tableCapacity shouldBeGreaterThan ElasticCapacity.MIN
        // The blow-up is real: a miss scans whole full levels rather than a handful of
        // slots. A healthy map probes single digits here.
        map.containsKey(count * 10L) shouldBe false
        withClue("constant-hasher search must visibly blow up: ${map.lastProbes} probes") {
            map.lastProbes shouldBeGreaterThan 50
        }
    }

    @Test
    fun `does not duplicate a deferred key when a front slot is tombstoned`() {
        // The cross-level-duplicate regression: a constant hasher funnels keys across
        // levels; deleting a front occupant leaves a tombstone, and re-putting a deferred
        // key must overwrite the live copy (found by the all-levels lookup), not place a
        // second copy at the freed slot.
        val map = ElasticLongMap<Long>(hasher = { 0L })
        val count = 40L
        for (key in 0L until count) {
            map.put(key, key)
        }
        map.size shouldBe count.toInt()

        map.remove(0L) shouldBe 0L
        val deferred = count - 1L
        map.put(deferred, deferred * 10L) shouldBe deferred
        map.size shouldBe (count - 1L).toInt()
        map[deferred] shouldBe deferred * 10L
        // A single live copy: one removal clears it.
        map.remove(deferred) shouldBe deferred * 10L
        map[deferred].shouldBeNull()
        map.size shouldBe (count - 2L).toInt()
    }

    @Test
    fun `survives heavy collisions across the levels`() {
        // Constant hash sends every key down one probe path per level, exercising the
        // descent, Case-2 skips, the greedy fallback, and the size-1 tail.
        val map = ElasticLongMap<Long>(hasher = { 0L })
        val count = 100L
        for (key in 0L until count) {
            map.put(key, key)
        }
        map.size shouldBe count.toInt()
        // Remove the evens, then confirm odds survive and evens are gone (tombstones in
        // the probe chains must not hide keys placed past them).
        var key = 0L
        while (key < count) {
            map.remove(key) shouldBe key
            key += 2L
        }
        map.size shouldBe (count / 2L).toInt()
        for (odd in 1L until count step 2L) {
            map[odd] shouldBe odd
        }
        for (even in 0L until count step 2L) {
            map.containsKey(even) shouldBe false
        }
    }

    @Test
    fun `stays correct and bounded under heavy insert-delete churn`() {
        // Repeated insert-then-mostly-delete rounds keep the live set small while
        // tombstones accumulate, forcing tombstone-reclaiming rebuilds rather than
        // unbounded growth. Verified against a LinkedHashMap model throughout, and the
        // capacity must stay bounded for a bounded live size.
        val map = ElasticLongMap<Long>()
        val model = LinkedHashMap<Long, Long>()
        var nextKey = 0L
        var maxCapacitySeen = map.tableCapacity
        repeat(30) {
            repeat(400) {
                val key = nextKey++
                map.put(key, key * 7L) shouldBe model.put(key, key * 7L)
            }
            val doomed = model.keys.sorted().take(model.size * 85 / 100)
            for (key in doomed) {
                map.remove(key) shouldBe model.remove(key)
            }
            map.size shouldBe model.size
            if (map.tableCapacity > maxCapacitySeen) {
                maxCapacitySeen = map.tableCapacity
            }
        }
        map.size shouldBe model.size
        for ((key, value) in model) {
            map[key] shouldBe value
        }
        // The live set never exceeded ~460, so the capacity must not have run away to the
        // ~12k a non-reclaiming map would need for the 12k total inserts.
        withClue("capacity $maxCapacitySeen must stay bounded for a small live set") {
            (maxCapacitySeen <= 2_048) shouldBe true
        }
    }

    @Test
    fun `reclaims tombstones in place without growing when occupancy is low`() {
        // Fill to the budget, delete almost everything, then insert: the rebuild that the
        // exhausted growth budget forces must reclaim tombstones IN PLACE (capacity
        // unchanged), because live occupancy is well under half the budget.
        val delta = 0.1
        val capacity = ElasticCapacity.forEntries(800, delta)
        val maxInserts = ElasticSizing.maxInserts(capacity, delta)
        val map = ElasticLongMap<Long>(expectedSize = maxInserts, delta = delta)
        for (key in 0L until maxInserts.toLong()) {
            map.put(key, key)
        }
        map.tableCapacity shouldBe capacity
        // Delete down to a handful of live entries (far below maxInserts/2).
        for (key in 0L until (maxInserts - 5).toLong()) {
            map.remove(key)
        }
        map.size shouldBe 5
        // Keep inserting fresh keys; growth budget is exhausted so a rebuild fires, and
        // with low live occupancy it reclaims in place — capacity stays put.
        for (key in maxInserts.toLong() until (maxInserts + 5).toLong()) {
            map.put(key, key)
        }
        map.tableCapacity shouldBe capacity
        map.size shouldBe 10
        for (key in (maxInserts - 5).toLong() until (maxInserts + 5).toLong()) {
            map[key] shouldBe key
        }
    }

    @Test
    fun `clears all entries and is reusable`() {
        val map = ElasticLongMap<Long>()
        for (key in 0L until 100L) {
            map.put(key, key)
        }
        map.clear()
        map.size shouldBe 0
        map.isEmpty() shouldBe true
        map[0L].shouldBeNull()

        map.put(1L, 1L)
        map[1L] shouldBe 1L
        map.size shouldBe 1
    }

    @Test
    fun `rejects a negative expected size`() {
        shouldThrow<IllegalArgumentException> {
            ElasticLongMap<Long>(expectedSize = -1)
        }
    }

    @Test
    fun `rejects a delta outside the open unit interval`() {
        shouldThrow<IllegalArgumentException> {
            ElasticLongMap<Long>(delta = 0.0)
        }
        shouldThrow<IllegalArgumentException> {
            ElasticLongMap<Long>(delta = 1.0)
        }
    }
}
