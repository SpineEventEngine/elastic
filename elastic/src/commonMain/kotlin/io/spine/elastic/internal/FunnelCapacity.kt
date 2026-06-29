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
 * Capacity arithmetic for the funnel-hashing table.
 *
 * Unlike the SwissTable maps — whose capacity is a power of two so that
 * `index and (capacity - 1)` is an exact modulo — a funnel table's capacity is
 * governed by [FunnelSizing]: the only requirement is that
 * `FunnelSizing(capacity, delta)` admits enough insertions for the workload.
 * Capacities are therefore drawn from a simple doubling sequence starting at [MIN]
 * and chosen by how many insertions they must hold at a given `delta` — never by
 * the `7/8` power-of-two rule of [Capacity], which would size the table wrongly for
 * funnel hashing. (The doubled values happen to be powers of two, but nothing in
 * the funnel map relies on that; bucket indices use a real modulo.)
 */
internal object FunnelCapacity {

    /** The smallest funnel capacity: a small but non-degenerate base table. */
    const val MIN: Int = 64

    /** The largest capacity that keeps slot arithmetic within `Int`. */
    const val MAX: Int = 1 shl 30

    /** The next (doubled) capacity for a grow, failing if already at [MAX]. */
    fun grown(capacity: Int): Int {
        require(capacity < MAX) {
            "Funnel map exceeded the maximum capacity of $MAX slots."
        }
        return capacity * 2
    }

    /**
     * The smallest capacity in the doubling sequence from [MIN] that can actually hold
     * [expectedSize] entries at [delta], so a map pre-sized for that many entries needs
     * no rebuild.
     *
     * Sizing is against [holdableEntries] — the lesser of the load budget and the
     * addressable slot count — not [FunnelSizing.maxInserts] alone, which for small
     * `delta` overstates capacity. The value is monotonic in capacity, so the linear
     * scan terminates at the first sufficient capacity.
     */
    fun forEntries(expectedSize: Int, delta: Double): Int {
        require(expectedSize >= 0) {
            "expectedSize must be non-negative: $expectedSize."
        }
        requireValidDelta(delta)
        var capacity = MIN
        while (holdableEntries(capacity, delta) < expectedSize) {
            require(capacity < MAX) {
                "Cannot size a funnel map for $expectedSize entries: " +
                    "exceeds the maximum of $MAX slots."
            }
            capacity *= 2
        }
        return capacity
    }

    /**
     * The most entries a table of [capacity] at [delta] can actually hold: the lesser of
     * its load budget ([FunnelSizing.maxInserts]) and its **addressable** slot count
     * (`totalBuckets * beta + specialSize`).
     *
     * For small `delta` the trailing `primarySize % beta` primary slots are unaddressable
     * (see [FunnelSizing]), so `maxInserts` alone overstates how many entries fit — e.g.
     * `FunnelSizing(64, 0.01).maxInserts` is 63 but only 57 slots are addressable. Capping
     * by the addressable count keeps [forEntries] from reporting a capacity that cannot
     * physically hold the requested entries (which would force a structural rebuild despite
     * the map being "pre-sized"). The result is non-decreasing across the doubling
     * sequence [forEntries] scans (`maxInserts` grows, and the addressable count grows
     * on net even though `totalBuckets*beta` can dip momentarily when `specialSize`
     * jumps), so the scan still stops at the smallest sufficient capacity.
     */
    private fun holdableEntries(capacity: Int, delta: Double): Int {
        val sizing = FunnelSizing(capacity, delta)
        val addressable = sizing.totalBuckets * sizing.beta + sizing.specialSize
        return minOf(sizing.maxInserts, addressable)
    }
}
