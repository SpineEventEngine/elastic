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

import io.spine.elastic.internal.Capacity
import io.spine.elastic.internal.Swar

/**
 * A SwissTable-style open-addressing map from primitive `Long` keys to values of
 * type [V].
 *
 * This is the lead specialization of the library (decision DP-6) and the Phase 1
 * proof that primitive specialization beats the boxing standard-library `HashMap`:
 * keys live in a `LongArray`, so neither lookups nor insertions box a key.
 *
 * ### Layout
 *
 * Slots are grouped eight to a *control word*. The table keeps a [LongArray] of
 * these words — eight 1-byte slot descriptors packed per `Long` — so a whole group
 * is scanned with a single array load and a few arithmetic operations through
 * [Swar], with no platform SIMD. A control byte holds either a 7-bit hash
 * fingerprint (a full slot) or an empty/deleted sentinel. The keys and values sit
 * in parallel arrays indexed by slot.
 *
 * The probe is group-aligned: it starts at the home group and advances by a
 * triangular, group-strided sequence that visits every group of a power-of-two
 * table exactly once, so it always reaches an empty slot and terminates. The load
 * factor is kept at `7/8`. Deletion writes a tombstone (decision DP-8); tombstones
 * are reclaimed only when the table is rebuilt, either in place at the same
 * capacity (when most occupancy is tombstones) or by doubling (decision DP-9).
 *
 * ### Concurrency
 *
 * This implementation is **single-threaded** (decision DP-13): there is no
 * synchronization, and concurrent mutation is undefined. The storage is kept in one
 * immutable storage holder swapped by a single field write on resize, and inserts
 * publish a slot's key and value before its control byte — structural seams that let
 * the single-writer / multiple-reader, lock-free-read variant be *derived* in a
 * later phase rather than retrofitted. No thread-safety is claimed here.
 *
 * @param V the type of mapped values
 * @param expectedSize a hint for the number of entries, used to pre-size the table
 *        so that holding that many entries needs no resize; defaults to empty
 * @param hasher the hash function applied to keys; defaults to [LongHasher.Default]
 */
public class SwissLongMap<V> public constructor(
    expectedSize: Int = 0,
    private val hasher: LongHasher = LongHasher.Default,
) : OpenAddressingLongMap<V> {

    private var tables: Tables<V> = Tables(Capacity.forEntries(expectedSize))
    private var entryCount: Int = 0

    /** The number of inserts into never-used slots allowed before a rebuild is forced. */
    private var growthLeft: Int = Capacity.maxLoad(tables.capacity)

    public override val size: Int
        get() = entryCount

    public override operator fun get(key: Long): V? {
        val current = tables
        val slot = findSlot(current, key, hasher.hash(key))
        return if (slot == SLOT_ABSENT) null else current.valueAt(slot)
    }

    public override fun containsKey(key: Long): Boolean {
        val current = tables
        return findSlot(current, key, hasher.hash(key)) != SLOT_ABSENT
    }

    @Suppress("LoopWithTooManyJumpStatements")
    // A find-or-insert probe has two terminal outcomes — overwrite an existing key,
    // or place a new one at the located slot — so the loop has two exits by nature.
    public override fun put(key: Long, value: V): V? {
        val hash = hasher.hash(key)
        val fingerprint = Swar.fingerprint(hash)
        val current = tables
        val control = current.control
        val groupMask = current.groupMask
        var firstDeleted = SLOT_ABSENT
        var group = Swar.groupIndex(hash, groupMask)
        var stride = 0
        while (true) {
            val word = control[group]
            val existing = matchingSlot(current, word, group, key, fingerprint)
            if (existing != SLOT_ABSENT) {
                val previous = current.valueAt(existing)
                current.values[existing] = value
                return previous
            }
            if (firstDeleted == SLOT_ABSENT) {
                val deleted = Swar.matchDeleted(word)
                if (deleted != 0L) {
                    firstDeleted = (group shl Swar.GROUP_SHIFT) + Swar.firstLane(deleted)
                }
            }
            val empties = Swar.matchEmpty(word)
            if (empties != 0L) {
                val reusing = firstDeleted != SLOT_ABSENT
                val slot = if (reusing) {
                    firstDeleted
                } else {
                    (group shl Swar.GROUP_SHIFT) + Swar.firstLane(empties)
                }
                return insertAbsent(key, value, fingerprint, slot, fromEmpty = !reusing)
            }
            stride++
            group = (group + stride) and groupMask
        }
    }

    public override fun remove(key: Long): V? {
        val current = tables
        val slot = findSlot(current, key, hasher.hash(key))
        if (slot == SLOT_ABSENT) {
            return null
        }
        val previous = current.valueAt(slot)
        // A tombstone keeps the probe chain intact; occupancy is unchanged, so
        // `growthLeft` is not credited back until a rebuild reclaims it (DP-8).
        val group = slot ushr Swar.GROUP_SHIFT
        current.control[group] =
            Swar.withLane(current.control[group], slot and Swar.LANE_MASK, Swar.DELETED)
        current.values[slot] = null
        current.keys[slot] = 0L
        entryCount--
        return previous
    }

    public override fun clear() {
        val capacity = tables.capacity
        tables = Tables<V>(capacity)
        entryCount = 0
        growthLeft = Capacity.maxLoad(capacity)
    }

    /**
     * Returns the slot holding [key] in [table], or [SLOT_ABSENT] if it is not
     * present. The probe stops at the first empty lane, which proves absence.
     */
    private fun findSlot(table: Tables<V>, key: Long, hash: Long): Int {
        val control = table.control
        val groupMask = table.groupMask
        val fingerprint = Swar.fingerprint(hash)
        var group = Swar.groupIndex(hash, groupMask)
        var stride = 0
        while (true) {
            val word = control[group]
            val slot = matchingSlot(table, word, group, key, fingerprint)
            if (slot != SLOT_ABSENT || Swar.matchEmpty(word) != 0L) {
                return slot
            }
            stride++
            group = (group + stride) and groupMask
        }
    }

    /**
     * Scans the control [word] of [group] for lanes whose [fingerprint] matches
     * and confirms the key, returning the slot of the first true match or
     * [SLOT_ABSENT].
     */
    private fun matchingSlot(
        table: Tables<V>,
        word: Long,
        group: Int,
        key: Long,
        fingerprint: Int,
    ): Int {
        var matches = Swar.matchByte(word, fingerprint)
        while (matches != 0L) {
            val slot = (group shl Swar.GROUP_SHIFT) + Swar.firstLane(matches)
            if (table.keys[slot] == key) {
                return slot
            }
            matches = Swar.clearLowest(matches)
        }
        return SLOT_ABSENT
    }

    /**
     * Places an absent [key] at [slot], which [put] located on the current table.
     * When [fromEmpty] is `true` the slot was never used, so the insert consumes
     * growth capacity and first rebuilds and then retries when it is exhausted;
     * otherwise [slot] reuses a tombstone, leaving occupancy — and therefore
     * `growthLeft` — unchanged. The key and value are written before the control
     * byte, so the slot is never observed full holding a stale entry.
     */
    private fun insertAbsent(
        key: Long,
        value: V,
        fingerprint: Int,
        slot: Int,
        fromEmpty: Boolean,
    ): V? {
        if (fromEmpty && growthLeft == 0) {
            rehashOrGrow()
            return put(key, value)
        }
        val table = tables
        table.keys[slot] = key
        table.values[slot] = value
        val group = slot ushr Swar.GROUP_SHIFT
        table.control[group] =
            Swar.withLane(table.control[group], slot and Swar.LANE_MASK, fingerprint)
        entryCount++
        if (fromEmpty) {
            growthLeft--
        }
        return null
    }

    /**
     * Rebuilds the table, dropping tombstones. Doubles the capacity when live
     * entries dominate; otherwise rehashes in place at the same capacity to reclaim
     * tombstones. Either way the fresh table is published by a single field write.
     */
    private fun rehashOrGrow() {
        val current = tables
        val capacity = current.capacity
        val newCapacity = if (entryCount.toLong() > Capacity.rehashThreshold(capacity)) {
            require(capacity < Capacity.MAX) {
                "Map exceeded the maximum capacity of ${Capacity.MAX} slots."
            }
            capacity * 2
        } else {
            capacity
        }
        val rebuilt = Tables<V>(newCapacity)
        val control = current.control
        val keys = current.keys
        for (group in control.indices) {
            var fulls = Swar.matchFull(control[group])
            while (fulls != 0L) {
                val slot = (group shl Swar.GROUP_SHIFT) + Swar.firstLane(fulls)
                reinsert(rebuilt, keys[slot], current.valueAt(slot))
                fulls = Swar.clearLowest(fulls)
            }
        }
        tables = rebuilt
        growthLeft = Capacity.maxLoad(newCapacity) - entryCount
    }

    /**
     * Inserts a known-unique [key] into [table], which holds no tombstones, by
     * placing it at the first empty lane on its probe.
     */
    private fun reinsert(table: Tables<V>, key: Long, value: V?) {
        val control = table.control
        val groupMask = table.groupMask
        val hash = hasher.hash(key)
        val fingerprint = Swar.fingerprint(hash)
        var group = Swar.groupIndex(hash, groupMask)
        var stride = 0
        while (true) {
            val empties = Swar.matchEmpty(control[group])
            if (empties != 0L) {
                val lane = Swar.firstLane(empties)
                val slot = (group shl Swar.GROUP_SHIFT) + lane
                table.keys[slot] = key
                table.values[slot] = value
                control[group] = Swar.withLane(control[group], lane, fingerprint)
                return
            }
            stride++
            group = (group + stride) and groupMask
        }
    }

    /** The fixed-capacity storage, swapped by a single field write on resize. */
    private class Tables<V>(val capacity: Int) {
        val numGroups: Int = capacity / Swar.GROUP_WIDTH
        val groupMask: Int = numGroups - 1
        val control: LongArray = LongArray(numGroups).apply { fill(Swar.EMPTY_WORD) }
        val keys: LongArray = LongArray(capacity)
        val values: Array<Any?> = arrayOfNulls(capacity)

        /** The value at a full [slot], cast back from its erased storage. */
        @Suppress("UNCHECKED_CAST") // Only a `V` is ever stored at a full slot.
        fun valueAt(slot: Int): V? = values[slot] as V?
    }

    private companion object {

        /** Sentinel for "no such slot". */
        private const val SLOT_ABSENT = -1
    }
}
