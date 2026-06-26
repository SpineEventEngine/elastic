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
 * Capacity and load-factor arithmetic shared by the open-addressing maps.
 *
 * A table's capacity is always a power of two and at least [MIN] (one group), so
 * `index and (capacity - 1)` is an exact modulo. The target load factor is `7/8`:
 * [maxLoad] is the most entries a capacity holds before a rebuild, and
 * [rehashThreshold] is the live-entry count below which a rebuild rehashes in
 * place (to reclaim tombstones) instead of doubling.
 *
 * These functions are value-type-independent, so every primitive specialization
 * (`Long → V`, `Long → Long`, …) sizes its table the same way.
 */
internal object Capacity {

    /** The smallest table: a single group. */
    const val MIN: Int = 8

    /** The largest power-of-two capacity that keeps slot arithmetic within `Int`. */
    const val MAX: Int = 1 shl 30

    /** Numerator of the `7/8` target load factor. */
    private const val LOAD_NUM: Int = 7

    /** Denominator of the `7/8` target load factor (a power of two). */
    private const val LOAD_DEN: Int = 8

    /** Numerator of the `7/16` rehash-in-place threshold (half of max load). */
    private const val REHASH_NUM: Int = 7

    /** Denominator of the `7/16` rehash-in-place threshold. */
    private const val REHASH_DEN: Int = 16

    /** The maximum number of entries a table of [capacity] slots holds. */
    fun maxLoad(capacity: Int): Int = capacity - capacity / LOAD_DEN

    /** Above this many live entries, a rebuild grows instead of rehashing in place. */
    fun rehashThreshold(capacity: Int): Long =
        capacity.toLong() * REHASH_NUM / REHASH_DEN

    /** The smallest power-of-two capacity that holds [expectedSize] entries. */
    fun forEntries(expectedSize: Int): Int {
        require(expectedSize >= 0) {
            "expectedSize must be non-negative: $expectedSize."
        }
        val needed = (expectedSize.toLong() * LOAD_DEN + (LOAD_NUM - 1)) / LOAD_NUM
        val target = nextPowerOfTwo(needed.coerceAtLeast(MIN.toLong()))
        require(target <= MAX) {
            "Cannot size a map for $expectedSize entries: exceeds the maximum of $MAX slots."
        }
        return target.toInt()
    }

    /** Rounds [value] up to the nearest power of two. */
    private fun nextPowerOfTwo(value: Long): Long {
        val highest = value.takeHighestOneBit()
        return if (highest == value) value else highest shl 1
    }
}
