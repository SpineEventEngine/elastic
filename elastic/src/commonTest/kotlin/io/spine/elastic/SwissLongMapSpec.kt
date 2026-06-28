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
import kotlin.test.Test

internal class SwissLongMapSpec {

    @Test
    fun `starts empty`() {
        val map = SwissLongMap<Long>()
        map.size shouldBe 0
        map.isEmpty() shouldBe true
        map[42L].shouldBeNull()
        map.containsKey(42L) shouldBe false
        map.remove(42L).shouldBeNull()
    }

    @Test
    fun `inserts and reads back a value`() {
        val map = SwissLongMap<String>()
        map.put(1L, "one").shouldBeNull()
        map[1L] shouldBe "one"
        map.containsKey(1L) shouldBe true
        map.size shouldBe 1
        map.isEmpty() shouldBe false
    }

    @Test
    fun `overwrites an existing key and returns the previous value`() {
        val map = SwissLongMap<String>()
        map.put(1L, "one")
        map.put(1L, "uno") shouldBe "one"
        map[1L] shouldBe "uno"
        map.size shouldBe 1
    }

    @Test
    fun `removes a key and returns the previous value`() {
        val map = SwissLongMap<String>()
        map.put(7L, "seven")
        map.remove(7L) shouldBe "seven"
        map.containsKey(7L) shouldBe false
        map[7L].shouldBeNull()
        map.size shouldBe 0
        map.remove(7L).shouldBeNull()
    }

    @Test
    fun `distinguishes a null value from an absent key`() {
        val map = SwissLongMap<String?>()
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
        val map = SwissLongMap<Long>()
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
        val map = SwissLongMap<Long>()
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
        val map = SwissLongMap<Int>(expectedSize = count)
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
        val map = SwissLongMap<Long>()
        val rounds = 2_000L
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
    fun `survives worst-case probing when every key shares one bucket`() {
        // A constant hash forces one fingerprint and one home group for all keys,
        // so the whole table becomes a single probe chain.
        val map = SwissLongMap<Long>(hasher = { 0L })
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
    fun `clears all entries and is reusable`() {
        val map = SwissLongMap<Long>()
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
            SwissLongMap<Long>(expectedSize = -1)
        }
    }

    @Test
    fun `rejects an expected size beyond the maximum capacity`() {
        shouldThrow<IllegalArgumentException> {
            SwissLongMap<Long>(expectedSize = Int.MAX_VALUE)
        }
    }
}
