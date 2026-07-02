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
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The single-threaded contract of [SingleWriterSwissLongMap]: a correct concurrent
 * map is first a correct map. Mirrors [SwissLongMapSpec], then pins the behaviors
 * where the concurrent variant deliberately diverges from [SwissLongMap] — no
 * tombstone reuse, keys never zeroed on removal, and rebuild-based (rather than
 * in-place) tombstone reclaim — because those are the load-bearing invariants of
 * its publication protocol.
 */
internal class SingleWriterSwissLongMapSpec {

    @Test
    fun `starts empty`() {
        val map = SingleWriterSwissLongMap<Long>()
        map.size shouldBe 0
        map.isEmpty() shouldBe true
        map[42L].shouldBeNull()
        map.containsKey(42L) shouldBe false
        map.remove(42L).shouldBeNull()
    }

    @Test
    fun `inserts and reads back a value`() {
        val map = SingleWriterSwissLongMap<String>()
        map.put(1L, "one").shouldBeNull()
        map[1L] shouldBe "one"
        map.containsKey(1L) shouldBe true
        map.size shouldBe 1
        map.isEmpty() shouldBe false
    }

    @Test
    fun `overwrites an existing key and returns the previous value`() {
        val map = SingleWriterSwissLongMap<String>()
        map.put(1L, "one")
        map.put(1L, "uno") shouldBe "one"
        map[1L] shouldBe "uno"
        map.size shouldBe 1
    }

    @Test
    fun `removes a key and returns the previous value`() {
        val map = SingleWriterSwissLongMap<String>()
        map.put(7L, "seven")
        map.remove(7L) shouldBe "seven"
        map.containsKey(7L) shouldBe false
        map[7L].shouldBeNull()
        map.size shouldBe 0
        map.remove(7L).shouldBeNull()
    }

    @Test
    fun `distinguishes a null value from an absent key`() {
        val map = SingleWriterSwissLongMap<String?>()
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
        val map = SingleWriterSwissLongMap<Long>()
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
        val map = SingleWriterSwissLongMap<Long>()
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
    fun `does not resize when pre-sized for the inserted entries`() {
        val count = 4_096
        val map = SingleWriterSwissLongMap<Int>(expectedSize = count)
        val presized = map.currentCapacity
        for (key in 0 until count) {
            map.put(key.toLong(), key)
        }
        map.currentCapacity shouldBe presized
        map.size shouldBe count
        for (key in 0 until count) {
            map[key.toLong()] shouldBe key
        }
    }

    @Test
    fun `never resurrects a stale entry when a removed key is re-inserted`() {
        // The concurrent variant leaves the key bytes of a tombstoned slot in
        // place (readers may still compare against them), so a re-insert of the
        // same key must find a fresh lane and must not revive the retired one.
        for (hasher in listOf(LongHasher.Default, LongHasher { 0L })) {
            val map = SingleWriterSwissLongMap<String>(hasher = hasher)
            map.put(5L, "first")
            map.remove(5L) shouldBe "first"
            map.put(5L, "second").shouldBeNull()
            map[5L] shouldBe "second"
            map.size shouldBe 1
            map.remove(5L) shouldBe "second"
            map[5L].shouldBeNull()
        }
    }

    @Test
    fun `keeps the default capacity across single-key churn`() {
        // Every insert consumes growth budget (tombstones are never reused), so
        // churn forces periodic rebuilds; with one live entry they must reclaim
        // at the same capacity, never grow.
        val map = SingleWriterSwissLongMap<Long>()
        val initial = map.currentCapacity
        initial shouldBe 8
        repeat(50) { round ->
            val key = round.toLong()
            map.put(key, key)
            map.remove(key) shouldBe key
            map.currentCapacity shouldBe initial
        }
        map.size shouldBe 0
    }

    @Test
    fun `rebuilds on the first insert past the growth budget`() {
        // Capacity 8 holds 7 entries; the 8th fresh-slot insert must publish a
        // doubled table. Pins the geometry the Lincheck resize-crossing
        // configuration relies on.
        val map = SingleWriterSwissLongMap<Long>()
        for (key in 1L..7L) {
            map.put(key, key)
        }
        map.currentCapacity shouldBe 8
        map.put(8L, 8L)
        map.currentCapacity shouldBe 16
        for (key in 1L..8L) {
            map[key] shouldBe key
        }
    }

    @Test
    fun `stabilizes within one doubling under churn at a steady live count`() {
        // Churn at 600 live entries in a map pre-sized for 600 (capacity 1024):
        // the growth budget erodes with every insert, so the first rebuild grows
        // to 2048 — the intended one-time doubling — after which 600 live is
        // below the rehash threshold and every rebuild reclaims in place.
        val live = 600
        val map = SingleWriterSwissLongMap<Long>(expectedSize = live)
        map.currentCapacity shouldBe 1_024
        var next = 0L
        while (next < live) {
            map.put(next, next)
            next++
        }
        val rounds = 5_000
        repeat(rounds) {
            map.put(next, next)
            map.remove(next - live) shouldBe (next - live)
            next++
            map.currentCapacity shouldBeLessThanOrEqual 2_048
        }
        map.currentCapacity shouldBe 2_048
        map.size shouldBe live
        for (key in (next - live) until next) {
            map[key] shouldBe key
        }
        map.containsKey(next - live - 1L) shouldBe false
    }

    @Test
    fun `survives worst-case probing when every key shares one bucket`() {
        // A constant hash forces one fingerprint and one home group for all keys,
        // so the whole table becomes a single probe chain.
        val map = SingleWriterSwissLongMap<Long>(hasher = { 0L })
        val count = 300L
        for (key in 0L until count) {
            map.put(key, key)
        }
        map.size shouldBe count.toInt()
        for (key in 0L until count) {
            map[key] shouldBe key
        }
        // Remove the evens, then confirm odds survive and evens are gone.
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
    fun `survives constant-hash churn across forced rebuilds`() {
        // Remove + re-insert cycles under a constant hash walk the same probe
        // chain past stale (never-zeroed) keys and cross several rebuilds; live
        // keys must always resolve to their latest values and removed keys must
        // stay gone after every table swap.
        val map = SingleWriterSwissLongMap<Long>(hasher = { 0L })
        val window = 8L
        var next = 0L
        repeat(200) {
            map.put(next, next * 7L)
            if (next >= window) {
                map.remove(next - window) shouldBe ((next - window) * 7L)
            }
            next++
        }
        map.size shouldBe window.toInt()
        for (key in (next - window) until next) {
            map[key] shouldBe key * 7L
        }
        for (gone in 0L until (next - window)) {
            map.containsKey(gone) shouldBe false
        }
    }

    @Test
    fun `clears all entries and restores the growth budget for reuse`() {
        val map = SingleWriterSwissLongMap<Long>()
        for (key in 0L until 100L) {
            map.put(key, key)
        }
        val grown = map.currentCapacity
        map.clear()
        map.size shouldBe 0
        map.isEmpty() shouldBe true
        map[0L].shouldBeNull()
        map.currentCapacity shouldBe grown

        // A fresh budget: the same insert volume must not need a further grow.
        for (key in 0L until 100L) {
            map.put(key, key)
        }
        map.currentCapacity shouldBe grown
        map.size shouldBe 100
    }

    @Test
    fun `rejects a negative expected size`() {
        shouldThrow<IllegalArgumentException> {
            SingleWriterSwissLongMap<Long>(expectedSize = -1)
        }
    }

    @Test
    fun `rejects an expected size beyond the maximum capacity`() {
        shouldThrow<IllegalArgumentException> {
            SingleWriterSwissLongMap<Long>(expectedSize = Int.MAX_VALUE)
        }
    }
}
