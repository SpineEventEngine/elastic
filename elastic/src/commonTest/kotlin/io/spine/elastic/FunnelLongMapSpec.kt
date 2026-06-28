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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.spine.elastic.internal.FunnelCapacity
import io.spine.elastic.internal.FunnelSizing
import kotlin.test.Test

internal class FunnelLongMapSpec {

    @Test
    fun `starts empty`() {
        val map = FunnelLongMap<Long>()
        map.size shouldBe 0
        map.isEmpty() shouldBe true
        map[42L].shouldBeNull()
        map.containsKey(42L) shouldBe false
        map.remove(42L).shouldBeNull()
    }

    @Test
    fun `inserts and reads back a value`() {
        val map = FunnelLongMap<String>()
        map.put(1L, "one").shouldBeNull()
        map[1L] shouldBe "one"
        map.containsKey(1L) shouldBe true
        map.size shouldBe 1
        map.isEmpty() shouldBe false
    }

    @Test
    fun `overwrites an existing key and returns the previous value`() {
        val map = FunnelLongMap<String>()
        map.put(1L, "one")
        map.put(1L, "uno") shouldBe "one"
        map[1L] shouldBe "uno"
        map.size shouldBe 1
    }

    @Test
    fun `removes a key and returns the previous value`() {
        val map = FunnelLongMap<String>()
        map.put(7L, "seven")
        map.remove(7L) shouldBe "seven"
        map.containsKey(7L) shouldBe false
        map[7L].shouldBeNull()
        map.size shouldBe 0
        map.remove(7L).shouldBeNull()
    }

    @Test
    fun `distinguishes a null value from an absent key`() {
        val map = FunnelLongMap<String?>()
        map.put(1L, null).shouldBeNull()
        map.containsKey(1L) shouldBe true
        map[1L].shouldBeNull()
        map.size shouldBe 1

        map.put(2L, "two")
        map.put(2L, null) shouldBe "two"
        map.containsKey(2L) shouldBe true
        map[2L].shouldBeNull()
    }

    @Test
    fun `handles boundary key values`() {
        val map = FunnelLongMap<Long>()
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
        val map = FunnelLongMap<Long>()
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
    fun `holds the entries it was pre-sized for`() {
        val count = 4_000
        val map = FunnelLongMap<Int>(expectedSize = count)
        for (key in 0 until count) {
            map.put(key.toLong(), key)
        }
        map.size shouldBe count
        for (key in 0 until count) {
            map[key.toLong()] shouldBe key
        }
    }

    @Test
    fun `reclaims tombstones across insert-remove churn`() {
        val map = FunnelLongMap<Long>()
        val rounds = 5_000L
        for (round in 0L until rounds) {
            map.put(round, round)
            if (round >= 2L) {
                map.remove(round - 2L) shouldBe (round - 2L)
            }
        }
        // Only the last two keys remain live throughout the churn.
        map.size shouldBe 2
        map[rounds - 1L] shouldBe (rounds - 1L)
        map[rounds - 2L] shouldBe (rounds - 2L)
        map.containsKey(0L) shouldBe false
    }

    @Test
    fun `survives heavy collisions when every key shares one bucket per level`() {
        // A constant hash sends every key to the same bucket on every level, so the
        // table becomes one deep descent chain — the funnel's degenerate case, which
        // must still place and find each key (a modest count that fits below the
        // alpha*beta collision ceiling).
        val map = FunnelLongMap<Long>(hasher = { 0L })
        val count = 60L
        for (key in 0L until count) {
            map.put(key, key)
        }
        map.size shouldBe count.toInt()
        for (key in 0L until count) {
            map[key] shouldBe key
        }
        // Remove the evens, then confirm odds survive and evens are gone (tombstones
        // in the descent chain must not hide the keys placed past them).
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
    fun `does not duplicate a descended key when a front slot is tombstoned`() {
        // The regression guard for the cross-level duplicate bug: with a constant
        // hasher, keys fill level 0's bucket and then descend. Deleting a front
        // occupant leaves a tombstone; re-putting a descended key must overwrite the
        // live copy, not place a second copy at the freed slot.
        val delta = 0.1
        val beta = FunnelSizing(FunnelCapacity.forEntries(0, delta), delta).beta
        val map = FunnelLongMap<Long>(delta = delta, hasher = { 0L })
        val count = (3 * beta).toLong() // spans at least three levels
        for (key in 0L until count) {
            map.put(key, key)
        }
        map.size shouldBe count.toInt()

        map.remove(0L) shouldBe 0L
        val descended = count - 1L
        // Re-put returns the prior value (an overwrite), proving it was found, not re-added.
        map.put(descended, descended * 10L) shouldBe descended
        map.size shouldBe (count - 1L).toInt()
        map[descended] shouldBe descended * 10L
        // A second descended key behaves identically — no duplicate at the freed slot.
        val alsoDescended = count - 2L
        map.put(alsoDescended, alsoDescended * 10L) shouldBe alsoDescended
        map.size shouldBe (count - 1L).toInt()
        map[alsoDescended] shouldBe alsoDescended * 10L
        // A single live copy: one removal clears it.
        map.remove(descended) shouldBe descended * 10L
        map[descended].shouldBeNull()
        map.size shouldBe (count - 2L).toInt()
    }

    @Test
    fun `recovers when a rebuild itself overflows and must grow further`() {
        // The re-double path: a constant hasher forces the degenerate descent, and at
        // delta 0.25 a count of 39 drives a rebuild whose greedy re-insertion overflows
        // once and then succeeds after a further doubling — a branch ordinary,
        // well-distributed workloads (which drain on the first try) never reach.
        val map = FunnelLongMap<Long>(delta = 0.25, hasher = { 0L })
        val count = 39L
        for (key in 0L until count) {
            map.put(key, key)
        }
        map.size shouldBe count.toInt()
        for (key in 0L until count) {
            map[key] shouldBe key
        }
    }

    @Test
    fun `stays correct and bounded under heavy insert-delete churn`() {
        // Repeated insert-then-mostly-delete rounds exhaust the never-used-slot budget
        // at low live occupancy, forcing same-capacity tombstone-reclaim rebuilds
        // rather than unbounded growth, while growth itself fires for the fresh keys.
        // Verified against a `LinkedHashMap` model throughout.
        val map = FunnelLongMap<Long>()
        val model = LinkedHashMap<Long, Long>()
        var nextKey = 0L
        repeat(30) {
            repeat(400) {
                val key = nextKey++
                map.put(key, key * 7L) shouldBe model.put(key, key * 7L)
            }
            // Delete the oldest ~85 %, keeping the live set small.
            val doomed = model.keys.sorted().take(model.size * 85 / 100)
            for (key in doomed) {
                map.remove(key) shouldBe model.remove(key)
            }
            map.size shouldBe model.size
        }
        map.size shouldBe model.size
        for ((key, value) in model) {
            map[key] shouldBe value
        }
    }

    @Test
    fun `places into and finds keys in the special overflow array`() {
        // A constant hash maps every key to one bucket per level plus a single special
        // home, so the chain holds exactly `levelCount*beta + specialProbeLimit` keys —
        // filling it forces the last `specialProbeLimit` keys into the special array.
        val delta = 0.5
        val sizing = FunnelSizing(FunnelCapacity.forEntries(0, delta), delta)
        val chainCapacity = sizing.levelCount * sizing.beta + sizing.specialProbeLimit()
        val map = FunnelLongMap<Long>(delta = delta, hasher = { 0L })
        for (key in 0L until chainCapacity.toLong()) {
            map.put(key, key * 11L)
        }
        map.size shouldBe chainCapacity
        // Every key — including those that spilled into the special array — is found.
        for (key in 0L until chainCapacity.toLong()) {
            map[key] shouldBe key * 11L
        }
        // Delete a special-resident key (the last inserted), leaving a tombstone in the
        // special array; a fresh key then reuses that slot rather than overflowing.
        val specialKey = chainCapacity - 1L
        map.remove(specialKey) shouldBe specialKey * 11L
        map.containsKey(specialKey) shouldBe false
        val reused = chainCapacity.toLong()
        map.put(reused, reused * 11L).shouldBeNull()
        map[reused] shouldBe reused * 11L
        map.size shouldBe chainCapacity
    }

    @Test
    fun `fails loudly when a degenerate hasher defeats the per-level salts`() {
        // Funnel hashing cannot accommodate keys that collide identically at every
        // level regardless of capacity; it must throw rather than loop or OOM. A
        // large delta keeps the failing capacity (and so the test) small.
        val map = FunnelLongMap<Long>(delta = 0.5, hasher = { 0L })
        val failure = shouldThrow<IllegalStateException> {
            for (key in 0L until 100_000L) {
                map.put(key, key)
            }
        }
        // The message must point at the cause, so an unrelated future `check` failure
        // cannot keep this test green by accident.
        failure.message shouldContain "per-level salts"
    }

    @Test
    fun `clears all entries and is reusable`() {
        val map = FunnelLongMap<Long>()
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
            FunnelLongMap<Long>(expectedSize = -1)
        }
    }

    @Test
    fun `rejects a delta outside the open unit interval`() {
        shouldThrow<IllegalArgumentException> {
            FunnelLongMap<Long>(delta = 0.0)
        }
        shouldThrow<IllegalArgumentException> {
            FunnelLongMap<Long>(delta = 1.0)
        }
    }
}
