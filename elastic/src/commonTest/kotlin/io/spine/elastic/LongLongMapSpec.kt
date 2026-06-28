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
import io.kotest.matchers.shouldBe
import kotlin.test.Test

internal class LongLongMapSpec {

    @Test
    fun `starts empty and reports the default absent value`() {
        val map = LongLongMap()
        map.size shouldBe 0
        map.isEmpty shouldBe true
        map.absentValue shouldBe 0L
        map[42L] shouldBe 0L
        map.containsKey(42L) shouldBe false
        map.remove(42L) shouldBe 0L
    }

    @Test
    fun `honors a custom absent value`() {
        val map = LongLongMap(absentValue = -1L)
        map.absentValue shouldBe -1L
        map[42L] shouldBe -1L
        map.remove(42L) shouldBe -1L
        map.put(1L, 100L) shouldBe -1L
        map[1L] shouldBe 100L
    }

    @Test
    fun `inserts and reads back a value`() {
        val map = LongLongMap()
        map.put(1L, 10L) shouldBe 0L
        map[1L] shouldBe 10L
        map.containsKey(1L) shouldBe true
        map.size shouldBe 1
    }

    @Test
    fun `overwrites an existing key and returns the previous value`() {
        val map = LongLongMap()
        map.put(1L, 10L)
        map.put(1L, 20L) shouldBe 10L
        map[1L] shouldBe 20L
        map.size shouldBe 1
    }

    @Test
    fun `removes a key and returns the previous value`() {
        val map = LongLongMap()
        map.put(7L, 70L)
        map.remove(7L) shouldBe 70L
        map.containsKey(7L) shouldBe false
        map[7L] shouldBe 0L
        map.size shouldBe 0
        map.remove(7L) shouldBe 0L
    }

    @Test
    fun `distinguishes a stored sentinel value from an absent key`() {
        val map = LongLongMap(absentValue = -1L)
        map.put(1L, -1L)
        map.containsKey(1L) shouldBe true
        map[1L] shouldBe -1L
        map.containsKey(2L) shouldBe false
        map[2L] shouldBe -1L
    }

    @Test
    fun `handles boundary keys and values`() {
        val map = LongLongMap(absentValue = Long.MIN_VALUE)
        val pairs = listOf(0L to 1L, -1L to 2L, 1L to -2L, Long.MAX_VALUE to 3L)
        for ((key, value) in pairs) {
            map.put(key, value)
        }
        map.size shouldBe pairs.size
        for ((key, value) in pairs) {
            map[key] shouldBe value
        }
    }

    @Test
    fun `grows across many insertions from the default capacity`() {
        val map = LongLongMap()
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
        val map = LongLongMap(expectedSize = count)
        for (key in 0 until count) {
            map.put(key.toLong(), key.toLong())
        }
        map.size shouldBe count
        for (key in 0 until count) {
            map[key.toLong()] shouldBe key.toLong()
        }
    }

    @Test
    fun `reclaims tombstones across insert-remove churn`() {
        val map = LongLongMap()
        val rounds = 2_000L
        for (round in 0L until rounds) {
            map.put(round, round)
            if (round >= 2L) {
                map.remove(round - 2L) shouldBe (round - 2L)
            }
        }
        map.size shouldBe 2
        map[rounds - 1L] shouldBe (rounds - 1L)
        map[rounds - 2L] shouldBe (rounds - 2L)
        map.containsKey(0L) shouldBe false
    }

    @Test
    fun `survives worst-case probing when every key shares one bucket`() {
        val map = LongLongMap(hasher = { 0L })
        val count = 300L
        for (key in 0L until count) {
            map.put(key, key)
        }
        map.size shouldBe count.toInt()
        for (key in 0L until count) {
            map[key] shouldBe key
        }
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
        val map = LongLongMap()
        for (key in 0L until 100L) {
            map.put(key, key)
        }
        map.isEmpty shouldBe false
        map.clear()
        map.size shouldBe 0
        map.isEmpty shouldBe true
        map[0L] shouldBe 0L

        map.put(1L, 1L)
        map[1L] shouldBe 1L
        map.size shouldBe 1
    }

    @Test
    fun `rejects a negative expected size`() {
        shouldThrow<IllegalArgumentException> {
            LongLongMap(expectedSize = -1)
        }
    }

    @Test
    fun `rejects an expected size beyond the maximum capacity`() {
        shouldThrow<IllegalArgumentException> {
            LongLongMap(expectedSize = Int.MAX_VALUE)
        }
    }
}
