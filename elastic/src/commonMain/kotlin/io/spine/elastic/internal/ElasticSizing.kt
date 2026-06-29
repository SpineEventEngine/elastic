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
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min

/**
 * Geometric level sizing for the elastic-hashing table.
 *
 * The array of `capacity` slots is partitioned into levels of geometrically
 * decreasing size (each roughly half of the next), with the final level
 * absorbing the remainder. These formulas are ported from the paper "Optimal
 * Bounds for Open Addressing Without Reordering" (Farach-Colton, Krapivin,
 * Kuszmaul, arXiv:2501.02305) and cross-checked against the `sternma/optopenhash`
 * reference; the test fixtures assert byte-for-byte agreement with it.
 */
internal object ElasticSizing {

    /**
     * The probe-budget constant `c`, ported from the paper (the reference uses `4`)
     * and to be tuned empirically against the oracles and probe-count metrics.
     */
    const val PROBE_BUDGET: Int = 4

    /**
     * The maximum number of insertions allowed into a table of [capacity] slots
     * at target empty-fraction [delta], so the table never exceeds load
     * `1 - delta`.
     *
     * Reserves `ceil(delta * capacity)` empty slots. This intentionally diverges
     * from the `sternma` reference, which truncates (`int(delta * capacity)`) and
     * so would admit one insertion too many when `delta * capacity` is fractional
     * (e.g. capacity 1024, delta 0.1 reaches load 0.9004 > 0.9). The level sizes
     * still match the reference exactly.
     */
    fun maxInserts(capacity: Int, delta: Double): Int {
        requirePositiveCapacity(capacity)
        requireValidDelta(delta)
        return capacity - ceil(delta * capacity).toInt()
    }

    /** The number of geometric levels for a table of [capacity] slots. */
    fun levelCount(capacity: Int): Int {
        requirePositiveCapacity(capacity)
        return max(1, floor(log2(capacity.toDouble())).toInt())
    }

    /**
     * The size of each level, summing to exactly [capacity]. Every level but the
     * last holds `remaining / 2^(levels - i)` slots; the last takes whatever
     * remains.
     *
     * This is the **paper / `sternma`-faithful** geometric split (smallest level
     * first, sizes not powers of two — e.g. `levelSizes(16) = [1, 1, 3, 11]`),
     * retained only as the cross-language sizing cross-check. The `ElasticLongMap`
     * does **not** allocate from this; it uses [binaryLevelSizes] so each level
     * admits full-coverage triangular probing. The two functions differ in
     * ordering, in level sizes, **and** in count — see [binaryLevelSizes].
     */
    fun levelSizes(capacity: Int): IntArray {
        val levels = levelCount(capacity)
        val sizes = IntArray(levels)
        var remaining = capacity
        for (i in 0 until levels - 1) {
            val size = max(1, remaining / (1 shl (levels - i)))
            sizes[i] = size
            remaining -= size
        }
        sizes[levels - 1] = remaining
        return sizes
    }

    /**
     * The level split the `ElasticLongMap` actually allocates from: **largest-first
     * power-of-two sizes** `[capacity/2, capacity/4, …, 2, 1, 1]`, summing to
     * exactly [capacity].
     *
     * Unlike [levelSizes] (the oracle-faithful, smallest-first, non-power-of-two
     * arithmetic reference), every level here is a power of two so that triangular
     * probing `(h + j(j+1)/2) mod size` visits the level's slots exactly once —
     * full coverage, which the reference's quadratic probing on prime-ish sizes
     * lacks (it reaches only ≈half the slots and so cannot fill to high load). The
     * two trailing size-1 levels make the geometric series (which sums to
     * `capacity - 2`) reach exactly [capacity], so this returns **one more** level
     * than [levelCount]: `binaryLevelSizes(capacity).size == levelCount(capacity) + 1`.
     * The map must size every per-level array from this length, never from
     * [levelCount].
     *
     * @param capacity a power of two, at least 2
     */
    fun binaryLevelSizes(capacity: Int): IntArray {
        requirePositiveCapacity(capacity)
        require(capacity >= 2 && capacity and (capacity - 1) == 0) {
            "binaryLevelSizes requires a power-of-two capacity of at least 2: $capacity."
        }
        val sizes = ArrayList<Int>()
        var size = capacity / 2
        while (size >= 1) {
            sizes.add(size)
            size /= 2
        }
        // The descending powers [capacity/2 .. 1] sum to capacity - 1; one more
        // size-1 level brings the total to exactly capacity.
        sizes.add(1)
        return sizes.toIntArray()
    }

    /**
     * The per-level probe budget `f(ε)` for a level whose **free-slot fraction** is
     * [freeFraction] (`ε = 1 − fill`, *not* the load factor), given target [delta].
     * Mirrors the paper / reference: `max(1, c * min(log2(1/ε), log2(1/delta)))`.
     *
     * Passing the *load* (`1 − ε`) here would invert the budget — a near-empty level
     * would get the largest budget instead of the smallest — and silently destroy the
     * `O(1)`-amortized insertion property, so the parameter is the free fraction.
     */
    fun probeLimit(freeFraction: Double, delta: Double, c: Int = PROBE_BUDGET): Int {
        requireValidDelta(delta)
        val byFree = if (freeFraction > 0.0) log2(1.0 / freeFraction) else 0.0
        val byDelta = log2(1.0 / delta)
        return max(1.0, c * min(byFree, byDelta)).toInt()
    }
}
