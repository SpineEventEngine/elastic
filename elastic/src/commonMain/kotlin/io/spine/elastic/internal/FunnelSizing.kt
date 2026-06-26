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

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Structural sizing for the funnel-hashing table, derived from `capacity` and
 * the target empty-fraction [delta].
 *
 * Funnel hashing splits the table into a *primary* region of `alpha` levels —
 * each a number of fixed-size `beta` buckets, with bucket counts decreasing
 * geometrically by a factor of `0.75` — plus a small *special* overflow array.
 * These formulas are ported from the paper "Optimal Bounds for Open Addressing
 * Without Reordering" (Farach-Colton, Krapivin, Kuszmaul, arXiv:2501.02305) and
 * cross-checked against the `sternma/optopenhash` reference; the test fixtures
 * assert byte-for-byte agreement with it.
 *
 * Rounding follows Kotlin's [roundToInt] (half away from zero), which can differ
 * from Python's banker's rounding only at exact `.5` boundaries — none of which
 * the geometric series hits for the verified fixtures.
 *
 * @param capacity total number of slots; must be positive
 * @param delta target empty-fraction; must be in `(0, 1)`
 */
@Suppress("MagicNumber") // The factors (4, 2, 3/4, 0.75, +10) are the funnel
// sizing constants transcribed from the paper; naming them adds no clarity.
internal class FunnelSizing(val capacity: Int, val delta: Double) {

    init {
        requirePositiveCapacity(capacity)
        requireValidDelta(delta)
    }

    /**
     * Maximum insertions allowed, reserving `ceil(delta * capacity)` empty slots
     * so the table never exceeds load `1 - delta`. (The `sternma` reference
     * truncates here, which can admit one insertion too many when
     * `delta * capacity` is fractional; the level/bucket sizes still match it.)
     */
    val maxInserts: Int = capacity - ceil(delta * capacity).toInt()

    /** Number of primary levels, `ceil(4 * log2(1/delta) + 10)`. */
    val alpha: Int = ceil(4 * log2(1.0 / delta) + 10).toInt()

    /** Bucket size, `ceil(2 * log2(1/delta))`. */
    val beta: Int = ceil(2 * log2(1.0 / delta)).toInt()

    /** Size of the special overflow array, `floor(3 * delta * capacity / 4)`. */
    val specialSize: Int = max(1, floor(3 * delta * capacity / 4).toInt())

    /**
     * Number of slots in the primary region (everything outside the special
     * array). Note that the bucket grid covers only `totalBuckets * beta` of
     * these; the trailing `primarySize % beta` slots are not addressable by any
     * bucket — an intentional consequence of fixed-size buckets. Table-allocation
     * logic should therefore size the primary arrays from [totalBuckets] and
     * [beta], not from this value.
     */
    val primarySize: Int = capacity - specialSize

    /** Total number of `beta`-sized buckets across all primary levels. */
    val totalBuckets: Int = primarySize / beta

    /**
     * Bucket count per primary level: a geometric series with ratio `0.75`,
     * clamped so the counts never exceed the remaining budget, with any leftover
     * folded into the last level so the counts sum to [totalBuckets].
     */
    val levelBucketCounts: IntArray = computeLevelBucketCounts()

    /** Number of populated primary levels (may be fewer than [alpha]). */
    val levelCount: Int get() = levelBucketCounts.size

    private fun computeLevelBucketCounts(): IntArray {
        if (totalBuckets <= 0 || alpha <= 0) {
            return IntArray(0)
        }
        val a1 = totalBuckets / (4 * (1 - 0.75.pow(alpha)))
        val counts = ArrayList<Int>(alpha)
        var remaining = totalBuckets
        for (i in 0 until alpha) {
            if (remaining <= 0) {
                break
            }
            val ai = minOf(max(1, (a1 * 0.75.pow(i)).roundToInt()), remaining)
            counts.add(ai)
            remaining -= ai
        }
        if (remaining > 0 && counts.isNotEmpty()) {
            counts[counts.lastIndex] += remaining
        }
        return counts.toIntArray()
    }

    /**
     * The linear-probe budget for the special overflow array,
     * `ceil(ln(ln(capacity + 1) + 1))`.
     */
    fun specialProbeLimit(): Int =
        max(1, ceil(ln(ln(capacity + 1.0) + 1.0)).toInt())
}
