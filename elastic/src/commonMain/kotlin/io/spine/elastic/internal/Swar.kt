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

/**
 * SWAR ("SIMD Within A Register") primitives for scanning a *control word* — eight
 * control bytes packed into a single [Long], one per slot of a group.
 *
 * An open-addressing table in the SwissTable / `hashbrown` style keeps a control
 * byte per slot. Here those bytes live eight-to-a-`Long` in a `LongArray`, so a
 * whole group is read with a single array load and tested in a few integer
 * operations — no platform SIMD, and no per-byte assembly — which makes the same
 * code authoritative and fast on every Kotlin target (the JDK Vector API is
 * unreachable from common code anyway).
 *
 * ### Control-byte encoding
 *
 * A control byte is either empty, deleted, or *full*. A full byte holds a 7-bit
 * hash fingerprint in its low bits with the high bit clear (`0x00..0x7F`), while
 * both the empty (`0x80`) and the [DELETED] (`0xFE`) sentinels have the high bit
 * set. The Kotlin `Byte` is **signed**, so a byte is masked with `and 0xFF` before
 * it is compared or packed — otherwise `0x80`/`0xFE` would sign-extend and corrupt
 * the register. A fresh control array is filled with [EMPTY_WORD] (every lane
 * `0x80`).
 *
 * ### Why the match is exact
 *
 * [matchByte] broadcasts the target byte into all eight lanes, XORs it with the
 * word (so a matching lane becomes `0x00`), and locates the zero lanes with a
 * **carry-free** zero-byte test: `((x and 0x7F…7F) + 0x7F…7F) or x or 0x7F…7F` has
 * its per-lane high bit set exactly where a lane is *non*-zero, so inverting and
 * masking the high bits yields the zero (matching) lanes. The addition cannot
 * carry out of a lane — each lane adds at most `0x7F + 0x7F = 0xFE` — so, unlike
 * the borrow-prone `(x - 0x01…01) & ~x` trick, a match never lights up its
 * neighbour as a false positive. It is exact for *any* target byte `0x00..0xFF`,
 * which is why the same routine serves [matchByte] (a fingerprint), [matchEmpty],
 * and [matchDeleted] without special-casing the high-bit sentinels.
 *
 * A match result is a mask with `0x80` set in the high bit of each matching lane;
 * iterate it with [firstLane] and [clearLowest]. Besides scanning, this object
 * owns the rest of the control-byte layout: it derives a hash's [fingerprint] and
 * [groupIndex], writes a lane with [withLane], and finds the full lanes of a word
 * with [matchFull].
 */
internal object Swar {

    /** The number of control-byte lanes in one word (one group). */
    const val GROUP_WIDTH: Int = 8

    /** `log2(GROUP_WIDTH)`: converts a slot index to its group index and back. */
    const val GROUP_SHIFT: Int = 3

    /** `GROUP_WIDTH - 1`: the lane of a slot within its group. */
    const val LANE_MASK: Int = 7

    /** A control word with every lane empty (`0x80`); fills a fresh control array. */
    const val EMPTY_WORD: Long = -0x7F7F7F7F7F7F7F80L

    /** The control byte of a removed slot (tombstone): `0xFE`. */
    const val DELETED: Int = 0xFE

    /** `0x01` in every lane — the unit added by the carry-free zero test. */
    private const val LANES_OF_ONE: Long = 0x0101010101010101L

    /**
     * `0x80` in every lane — the high-bit mask of the zero test, written as a
     * negative literal because `0x8080808080808080` exceeds [Long.MAX_VALUE].
     */
    private const val LANES_OF_HIGH_BIT: Long = -0x7F7F7F7F7F7F7F80L

    /** `0x7F` in every lane — the low-seven-bit mask of the carry-free zero test. */
    private const val LANES_OF_LOW_BITS: Long = 0x7F7F7F7F7F7F7F7FL

    /** Masks a packed byte to its low eight bits, defeating sign extension. */
    private const val BYTE_MASK: Long = 0xFF

    /** Bits per lane; lane `i` occupies bits `[8i, 8i+7]`. */
    private const val LANE_BITS: Int = 8

    /** `log2(GROUP_WIDTH)`: shifts a trailing-zero bit index down to a lane index. */
    private const val LANE_SHIFT: Int = 3

    /** The empty sentinel as an unsigned `Int` target for [matchByte]. */
    private const val EMPTY_TARGET: Int = 0x80

    /** Bits of a hash consumed by the fingerprint, kept disjoint from the index. */
    private const val FINGERPRINT_BITS: Int = 7

    /** Low-bit mask selecting the 7-bit fingerprint from a hash. */
    private const val FINGERPRINT_MASK: Long = 0x7FL

    /**
     * Returns a mask with `0x80` set in the high bit of each lane of [group] whose
     * byte equals the low eight bits of [target], and `0x00` in every other lane.
     */
    fun matchByte(group: Long, target: Int): Long {
        val zeroedAtMatches = group xor ((target.toLong() and BYTE_MASK) * LANES_OF_ONE)
        val lowBitsNonZero = (zeroedAtMatches and LANES_OF_LOW_BITS) + LANES_OF_LOW_BITS
        val nonZeroLanes = lowBitsNonZero or zeroedAtMatches or LANES_OF_LOW_BITS
        return nonZeroLanes.inv() and LANES_OF_HIGH_BIT
    }

    /** Returns the match mask of the empty lanes in [group]. */
    fun matchEmpty(group: Long): Long = matchByte(group, EMPTY_TARGET)

    /** Returns the match mask of the [DELETED] lanes in [group]. */
    fun matchDeleted(group: Long): Long = matchByte(group, DELETED)

    /**
     * Returns the match mask of the *full* lanes of [group] — those whose high bit
     * is clear. Exact and borrow-free, being a single mask of the high bits.
     */
    fun matchFull(group: Long): Long = group.inv() and LANES_OF_HIGH_BIT

    /**
     * Returns the index (`0..7`) of the lowest matching lane in a non-zero match
     * [mask]. Callers must guard with `mask != 0L`, since a zero mask has no lane.
     */
    fun firstLane(mask: Long): Int = mask.countTrailingZeroBits() ushr LANE_SHIFT

    /** Clears the lowest set lane of a match [mask], for iterating the matches. */
    fun clearLowest(mask: Long): Long = mask and (mask - 1L)

    /** Returns [word] with its [lane]'s control byte replaced by [value]'s low byte. */
    fun withLane(word: Long, lane: Int, value: Int): Long {
        val shift = LANE_BITS * lane
        return (word and (BYTE_MASK shl shift).inv()) or ((value.toLong() and BYTE_MASK) shl shift)
    }

    /** The 7-bit fingerprint of [hash], stored in a full control byte. */
    fun fingerprint(hash: Long): Int = (hash and FINGERPRINT_MASK).toInt()

    /**
     * The index of the home group of [hash] among `groupMask + 1` groups. Disjoint
     * hash bits feed the fingerprint and the group, so the two are uncorrelated.
     */
    fun groupIndex(hash: Long, groupMask: Int): Int =
        ((hash ushr FINGERPRINT_BITS) and groupMask.toLong()).toInt()
}
