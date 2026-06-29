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
 * Capacity arithmetic for the elastic-hashing table.
 *
 * An elastic table's capacity is a **power of two** drawn from a doubling sequence
 * starting at [MIN], so [ElasticSizing.binaryLevelSizes] splits it into power-of-two
 * levels that triangular probing fully covers.
 *
 * Neither [Capacity] nor [FunnelCapacity] can be reused: [Capacity] sizes to a `7/8`
 * load (elastic's load cap is `1 - delta`), and [FunnelCapacity] caps by an
 * *addressable-slot* count that is meaningless here. With full triangular coverage of
 * power-of-two levels summing to exactly the capacity, **every slot is addressable**,
 * so the capacity that holds a given number of entries is governed purely by
 * [ElasticSizing.maxInserts] — no addressable-count indirection is needed.
 */
internal object ElasticCapacity {

    /**
     * The smallest elastic capacity: a power of two large enough that
     * [ElasticSizing.binaryLevelSizes] is well-formed and the geometric levels are
     * non-degenerate.
     */
    const val MIN: Int = 16

    /** The largest power-of-two capacity that keeps slot arithmetic within `Int`. */
    const val MAX: Int = 1 shl 30

    /** The next (doubled) capacity for a grow, failing if already at [MAX]. */
    fun grown(capacity: Int): Int {
        require(capacity < MAX) {
            "Elastic map exceeded the maximum capacity of $MAX slots."
        }
        return capacity * 2
    }

    /**
     * The smallest capacity in the doubling sequence from [MIN] whose
     * [ElasticSizing.maxInserts] at [delta] is at least [expectedSize], so a map
     * pre-sized for that many entries needs no rebuild.
     *
     * `maxInserts` is non-decreasing across the doubling sequence, so the linear scan
     * stops at the first sufficient capacity.
     */
    fun forEntries(expectedSize: Int, delta: Double): Int {
        require(expectedSize >= 0) {
            "expectedSize must be non-negative: $expectedSize."
        }
        requireValidDelta(delta)
        var capacity = MIN
        while (ElasticSizing.maxInserts(capacity, delta) < expectedSize) {
            require(capacity < MAX) {
                "Cannot size an elastic map for $expectedSize entries: " +
                    "exceeds the maximum of $MAX slots."
            }
            capacity *= 2
        }
        return capacity
    }
}
