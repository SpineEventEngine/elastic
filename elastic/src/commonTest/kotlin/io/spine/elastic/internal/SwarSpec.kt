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

import io.kotest.matchers.shouldBe
import kotlin.test.Test

internal class SwarSpec {

    /** Packs unsigned byte values (`0x00..0xFF`) into a control word, lane by lane. */
    private fun wordOf(vararg bytes: Int): Long {
        var word = 0L
        for (lane in bytes.indices) {
            word = word or ((bytes[lane].toLong() and 0xFF) shl (8 * lane))
        }
        return word
    }

    /** Enumerates the matching lane indices of a mask, lowest first. */
    private fun lanesOf(mask: Long): List<Int> {
        val lanes = mutableListOf<Int>()
        var bits = mask
        while (bits != 0L) {
            lanes.add(Swar.firstLane(bits))
            bits = Swar.clearLowest(bits)
        }
        return lanes
    }

    @Test
    fun `matches a fingerprint byte only in the lanes that hold it`() {
        val word = wordOf(0x80, 0x42, 0xFE, 0x07, 0x80, 0x42, 0x13, 0xFE)
        lanesOf(Swar.matchByte(word, 0x42)) shouldBe listOf(1, 5)
    }

    @Test
    fun `never matches the empty or deleted sentinels as a fingerprint`() {
        val word = wordOf(0x80, 0xFE, 0x80, 0xFE, 0x80, 0xFE, 0x80, 0xFE)
        Swar.matchByte(word, 0x00) shouldBe 0L
        Swar.matchByte(word, 0x7F) shouldBe 0L
    }

    @Test
    fun `matches the zero fingerprint without confusing the high-bit sentinels`() {
        val word = wordOf(0x00, 0x80, 0xFE, 0x00, 0x01, 0x00, 0x80, 0xFE)
        lanesOf(Swar.matchByte(word, 0x00)) shouldBe listOf(0, 3, 5)
    }

    @Test
    fun `matchEmpty finds exactly the empty lanes`() {
        val word = wordOf(0x80, 0x00, 0xFE, 0x80, 0x7F, 0x80, 0xFE, 0x33)
        lanesOf(Swar.matchEmpty(word)) shouldBe listOf(0, 3, 5)
    }

    @Test
    fun `matchDeleted finds exactly the deleted lanes`() {
        val word = wordOf(0x80, 0x00, 0xFE, 0x80, 0x7F, 0x80, 0xFE, 0x33)
        lanesOf(Swar.matchDeleted(word)) shouldBe listOf(2, 6)
    }

    @Test
    fun `matchFull finds exactly the full lanes`() {
        val word = wordOf(0x80, 0x00, 0xFE, 0x80, 0x7F, 0x80, 0xFE, 0x33)
        lanesOf(Swar.matchFull(word)) shouldBe listOf(1, 4, 7)
    }

    @Test
    fun `treats the empty word as eight empty lanes`() {
        lanesOf(Swar.matchEmpty(Swar.EMPTY_WORD)) shouldBe listOf(0, 1, 2, 3, 4, 5, 6, 7)
        Swar.matchFull(Swar.EMPTY_WORD) shouldBe 0L
        Swar.matchDeleted(Swar.EMPTY_WORD) shouldBe 0L
    }

    @Test
    fun `iterates every matching lane lowest first`() {
        val word = wordOf(0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42)
        lanesOf(Swar.matchByte(word, 0x42)) shouldBe listOf(0, 1, 2, 3, 4, 5, 6, 7)
    }

    @Test
    fun `writes a single lane leaving the others intact`() {
        val updated = Swar.withLane(Swar.EMPTY_WORD, 3, 0x2A)
        lanesOf(Swar.matchByte(updated, 0x2A)) shouldBe listOf(3)
        lanesOf(Swar.matchEmpty(updated)) shouldBe listOf(0, 1, 2, 4, 5, 6, 7)
    }

    @Test
    fun `writes the deleted sentinel into a lane`() {
        val full = wordOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x10)
        val updated = Swar.withLane(full, 4, Swar.DELETED)
        lanesOf(Swar.matchDeleted(updated)) shouldBe listOf(4)
        lanesOf(Swar.matchFull(updated)) shouldBe listOf(0, 1, 2, 3, 5, 6, 7)
    }

    @Test
    fun `extracts the seven-bit fingerprint from a hash`() {
        Swar.fingerprint(0L) shouldBe 0
        Swar.fingerprint(0x7FL) shouldBe 0x7F
        Swar.fingerprint(0xFFL) shouldBe 0x7F
        Swar.fingerprint(-1L) shouldBe 0x7F
    }

    @Test
    fun `derives the home group from disjoint hash bits`() {
        val groupMask = 7 // eight groups
        val hashes = longArrayOf(0L, 1L, 123L, -1L, Long.MAX_VALUE, Long.MIN_VALUE)
        for (hash in hashes) {
            Swar.groupIndex(hash, groupMask) shouldBe ((hash ushr 7) and 7L).toInt()
        }
    }
}
