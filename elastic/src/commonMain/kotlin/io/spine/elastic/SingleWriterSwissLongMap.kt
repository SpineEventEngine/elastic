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

// The atomics are confined to private storage, so the experimental stdlib API
// never surfaces in the public API of the library.
@file:OptIn(ExperimentalAtomicApi::class)

package io.spine.elastic

import io.spine.elastic.internal.Capacity
import io.spine.elastic.internal.Swar
import kotlin.concurrent.atomics.AtomicArray
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLongArray
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * A SwissTable-style open-addressing map from primitive `Long` keys to values
 * of type [V], safe for **one writer** and any number of **lock-free
 * concurrent readers**.
 *
 * This is the thread-safe variant of [SwissLongMap]. The table layout and the
 * probe are identical — packed control words scanned eight lanes at a time, a
 * group-aligned triangular probe over a power-of-two table, `7/8` load factor —
 * so its single-threaded behavior matches [SwissLongMap], at the price of atomic
 * (volatile) reads on the lookup path and more frequent table rebuilds under
 * insert-remove churn (see below).
 *
 * ### Concurrency contract
 *
 * **All mutating calls — [put], [remove], [clear] — must be issued by one thread
 * at a time**: either a dedicated writer thread, or writers serialized externally
 * (a lock, an actor) so that a happens-before edge orders successive writes.
 * Concurrent unsynchronized writers leave the map in an undefined state.
 *
 * Readers need no coordination: [get], [containsKey], [size], and [isEmpty] may
 * run on any thread, concurrently with the writer and each other. They take no
 * locks, never write shared state, and every read finishes within a bounded
 * number of steps regardless of writer activity.
 *
 * [get], [containsKey], [put], [remove], and [clear] are *linearizable*: a read
 * racing a write returns a value consistent with some ordering of the overlapping
 * operations — never a torn entry, never a value paired with the wrong key, never
 * a miss of a key that was present for the whole read. [size] and [isEmpty] are
 * weaker: moment-in-time estimates that may lag concurrent writes, as with
 * `java.util.concurrent.ConcurrentHashMap`.
 *
 * The map instance itself must reach reader threads through a safe-publication
 * edge (a thread start, an atomic or volatile reference, a message channel);
 * handing it over through a plain shared variable is not enough on all targets.
 * A custom [hasher] must be pure and thread-safe, since readers hash keys
 * concurrently; the stateless [LongHasher.Default] and [LongHasher.Fibonacci]
 * both qualify.
 *
 * ```kotlin
 * val sessions = SingleWriterSwissLongMap<Session>()
 *
 * // One dedicated thread — or externally serialized callers — mutates:
 * sessions.put(user.id, session)
 * sessions.remove(staleId)
 *
 * // Readers on any threads query concurrently, with no locks:
 * val active = sessions[user.id]
 * ```
 *
 * ### How writes publish
 *
 * The storage lives in one immutable-shape holder: atomic control words, a plain
 * key array, and an atomic value array. Within a published table, storage only
 * moves forward — a lane goes empty → full → deleted and never back, and a key
 * slot is written exactly once, *before* the control-word store that makes the
 * slot visible. A reader that observes a full lane therefore also observes the
 * key and value written behind it. Deletion retires the lane with a tombstone and
 * never reuses it; tombstones are reclaimed only when the writer rebuilds the
 * table — off-line, in fresh arrays — and publishes it with a single atomic
 * store of the table reference. Readers that loaded the previous table simply
 * finish their probe on that (now frozen) snapshot.
 *
 * Because a removal never returns capacity to the current table, insert-remove
 * churn rebuilds more often than in [SwissLongMap]: the capacity held by `L` live
 * entries still stays within one doubling of what `L` alone needs, but rebuild
 * work is proportional to the churn rate. Prefer [SwissLongMap] when the map is
 * confined to a single thread.
 *
 * @param V the type of mapped values
 * @param expectedSize a hint for the number of entries, used to pre-size the table
 *        so that holding that many entries needs no resize; defaults to empty
 * @param hasher the hash function applied to keys; must be pure and thread-safe;
 *        defaults to [LongHasher.Default]
 */
public class SingleWriterSwissLongMap<V> public constructor(
    expectedSize: Int = 0,
    private val hasher: LongHasher = LongHasher.Default,
) : OpenAddressingLongMap<V> {

    private val tables: AtomicReference<Tables<V>>
    private val entryCount = AtomicInt(0)

    /**
     * The number of inserts into never-used slots allowed before a rebuild is
     * forced. Writer-only state: guarded by the single-writer contract, so a
     * plain field suffices.
     */
    private var growthLeft: Int

    init {
        val capacity = Capacity.forEntries(expectedSize)
        tables = AtomicReference(Tables(capacity))
        growthLeft = Capacity.maxLoad(capacity)
    }

    public override val size: Int
        get() = entryCount.load()

    /** The slot capacity of the current table; a seam for capacity-bound tests. */
    internal val currentCapacity: Int
        get() = tables.load().capacity

    public override operator fun get(key: Long): V? {
        val current = tables.load()
        val slot = current.findSlot(key, hasher.hash(key))
        return if (slot == SLOT_ABSENT) null else current.valueAt(slot)
    }

    public override fun containsKey(key: Long): Boolean {
        val current = tables.load()
        return current.findSlot(key, hasher.hash(key)) != SLOT_ABSENT
    }

    @Suppress("LoopWithTooManyJumpStatements")
    // A find-or-insert probe has two terminal outcomes — overwrite an existing key,
    // or place a new one at the located slot — so the loop has two exits by nature.
    public override fun put(key: Long, value: V): V? {
        val hash = hasher.hash(key)
        val fingerprint = Swar.fingerprint(hash)
        val current = tables.load()
        var group = Swar.groupIndex(hash, current.groupMask)
        var stride = 0
        while (true) {
            val word = current.control.loadAt(group)
            val existing = current.matchingSlot(word, group, key, fingerprint)
            if (existing != SLOT_ABSENT) {
                val previous = current.valueAt(existing)
                current.values.storeAt(existing, value)
                return previous
            }
            // Unlike the single-threaded map, a tombstone is never reused: a key
            // slot must stay write-once while its table is published (readers
            // reach it with no ordering besides the control-word load).
            val empties = Swar.matchEmpty(word)
            if (empties != 0L) {
                val slot = (group shl Swar.GROUP_SHIFT) + Swar.firstLane(empties)
                return insertAbsent(current, key, value, fingerprint, slot)
            }
            stride++
            group = (group + stride) and current.groupMask
        }
    }

    public override fun remove(key: Long): V? {
        val current = tables.load()
        val slot = current.findSlot(key, hasher.hash(key))
        if (slot == SLOT_ABSENT) {
            return null
        }
        val previous = current.valueAt(slot)
        // Retire the lane first, then null the value so it does not linger until
        // the next rebuild. A reader that saw the lane full and loads the null
        // observes a state after this removal — indistinguishable, through this
        // API, from the key being absent. The key slot is NOT cleared: a racing
        // reader may still compare against it, and it must never change while
        // the table is published.
        current.publishLane(slot, Swar.DELETED)
        current.values.storeAt(slot, null)
        entryCount.decrementAndFetch()
        return previous
    }

    public override fun clear() {
        val capacity = tables.load().capacity
        // Swap the fresh table in first; the count is a lagging estimate (see
        // the class documentation on `size`).
        tables.store(Tables(capacity))
        entryCount.store(0)
        growthLeft = Capacity.maxLoad(capacity)
    }

    /**
     * Places an absent [key] at [slot], an empty lane [put] located on [table].
     * The key and value are stored before the control byte, so a reader that
     * sees the lane full also sees both; the entry count is bumped last and so
     * may lag momentarily. When the growth budget is exhausted, rebuilds first
     * and retries on the fresh table.
     */
    private fun insertAbsent(
        table: Tables<V>,
        key: Long,
        value: V,
        fingerprint: Int,
        slot: Int,
    ): V? {
        if (growthLeft == 0) {
            rehashOrGrow(table)
            return put(key, value)
        }
        table.keys[slot] = key
        table.values.storeAt(slot, value)
        table.publishLane(slot, fingerprint)
        growthLeft--
        entryCount.incrementAndFetch()
        return null
    }

    /**
     * Rebuilds the table, dropping tombstones: doubles the capacity when live
     * entries dominate, otherwise reclaims at the same capacity. The copy is
     * built off-line in plain arrays — no reader can reach them — and becomes
     * visible only through the single atomic store of the table reference.
     * The retired table is never written again, so readers still probing it see
     * a frozen snapshot.
     */
    private fun rehashOrGrow(current: Tables<V>) {
        val live = entryCount.load()
        val capacity = current.capacity
        val newCapacity = if (live.toLong() > Capacity.rehashThreshold(capacity)) {
            Capacity.grown(capacity)
        } else {
            capacity
        }
        val control = LongArray(newCapacity / Swar.GROUP_WIDTH) { Swar.EMPTY_WORD }
        val keys = LongArray(newCapacity)
        val values = arrayOfNulls<Any?>(newCapacity)
        for (group in 0 until current.numGroups) {
            var fulls = Swar.matchFull(current.control.loadAt(group))
            while (fulls != 0L) {
                val slot = (group shl Swar.GROUP_SHIFT) + Swar.firstLane(fulls)
                reinsert(
                    control = control,
                    keys = keys,
                    values = values,
                    key = current.keys[slot],
                    value = current.values.loadAt(slot),
                )
                fulls = Swar.clearLowest(fulls)
            }
        }
        tables.store(Tables(keys = keys, controlWords = control, valueSlots = values))
        growthLeft = Capacity.maxLoad(newCapacity) - live
    }

    /**
     * Inserts a known-unique [key] into the plain rebuild arrays, which hold no
     * tombstones, by placing it at the first empty lane on its probe.
     */
    private fun reinsert(
        control: LongArray,
        keys: LongArray,
        values: Array<Any?>,
        key: Long,
        value: Any?,
    ) {
        val groupMask = control.size - 1
        val hash = hasher.hash(key)
        val fingerprint = Swar.fingerprint(hash)
        var group = Swar.groupIndex(hash, groupMask)
        var stride = 0
        while (true) {
            val empties = Swar.matchEmpty(control[group])
            if (empties != 0L) {
                val lane = Swar.firstLane(empties)
                val slot = (group shl Swar.GROUP_SHIFT) + lane
                keys[slot] = key
                values[slot] = value
                control[group] = Swar.withLane(control[group], lane, fingerprint)
                return
            }
            stride++
            group = (group + stride) and groupMask
        }
    }

    /**
     * The fixed-capacity storage, swapped by a single atomic reference store on
     * rebuild. Control words and value slots are atomic elements; the key array
     * is plain, which is sound because a key slot is written exactly once, before
     * the control store that publishes its lane, and never changes afterwards
     * (tombstoned lanes keep their key).
     *
     * The atomic-array constructors copy the arrays passed to the primary
     * constructor, so the writer's plain rebuild arrays never escape.
     */
    private class Tables<V>(
        val keys: LongArray,
        controlWords: LongArray,
        valueSlots: Array<Any?>,
    ) {
        val capacity: Int = keys.size
        val numGroups: Int = capacity / Swar.GROUP_WIDTH
        val groupMask: Int = numGroups - 1
        val control: AtomicLongArray = AtomicLongArray(controlWords)
        val values: AtomicArray<Any?> = AtomicArray(valueSlots)

        /** Creates an empty table of [capacity] slots. */
        constructor(capacity: Int) : this(
            LongArray(capacity),
            LongArray(capacity / Swar.GROUP_WIDTH) { Swar.EMPTY_WORD },
            arrayOfNulls(capacity),
        )

        /**
         * Returns the slot holding [key], or [SLOT_ABSENT] if it is not present.
         * The probe stops at the first empty lane, which proves absence: within
         * a published table, lanes never return to empty.
         */
        fun findSlot(key: Long, hash: Long): Int {
            val fingerprint = Swar.fingerprint(hash)
            var group = Swar.groupIndex(hash, groupMask)
            var stride = 0
            while (true) {
                val word = control.loadAt(group)
                val slot = matchingSlot(word, group, key, fingerprint)
                if (slot != SLOT_ABSENT || Swar.matchEmpty(word) != 0L) {
                    return slot
                }
                stride++
                group = (group + stride) and groupMask
            }
        }

        /**
         * Scans the control [word] of [group] for lanes whose [fingerprint]
         * matches and confirms the key, returning the slot of the first true
         * match or [SLOT_ABSENT]. Only full lanes can match, so the key read is
         * ordered by the control-word load that produced [word].
         */
        fun matchingSlot(word: Long, group: Int, key: Long, fingerprint: Int): Int {
            var matches = Swar.matchByte(word, fingerprint)
            while (matches != 0L) {
                val slot = (group shl Swar.GROUP_SHIFT) + Swar.firstLane(matches)
                if (keys[slot] == key) {
                    return slot
                }
                matches = Swar.clearLowest(matches)
            }
            return SLOT_ABSENT
        }

        /** The value at a full [slot], cast back from its erased storage. */
        @Suppress("UNCHECKED_CAST") // Only a `V` is ever stored at a full slot.
        fun valueAt(slot: Int): V? = values.loadAt(slot) as V?

        /** Stores [controlByte] — a fingerprint or [Swar.DELETED] — at [slot]'s lane. */
        fun publishLane(slot: Int, controlByte: Int) {
            val group = slot ushr Swar.GROUP_SHIFT
            val updated = Swar.withLane(control.loadAt(group), slot and Swar.LANE_MASK, controlByte)
            control.storeAt(group, updated)
        }
    }
}

/** Sentinel for "no such slot". */
private const val SLOT_ABSENT = -1
