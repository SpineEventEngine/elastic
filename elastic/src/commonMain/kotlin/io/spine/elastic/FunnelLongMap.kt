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

import io.spine.elastic.internal.FunnelCapacity
import io.spine.elastic.internal.FunnelSizing
import io.spine.elastic.internal.requireValidDelta

/** Occupancy of a slot whose key/value have never been written. */
private const val EMPTY: Byte = 0

/** Occupancy of a slot that currently holds a live entry. */
private const val FULL: Byte = 1

/** Occupancy of a slot whose entry has been removed (a tombstone). */
private const val TOMBSTONE: Byte = 2

/** Sentinel for "no such slot" — key absent, or no free slot. */
private const val SLOT_ABSENT: Int = -1

/** Sentinel telling a bucket scan that the key may yet live on a deeper level. */
private const val DESCEND: Int = -2

/** The default target empty-fraction (`1 - load`); a 90 % load table. */
private const val DEFAULT_DELTA: Double = 0.1

/**
 * The golden-ratio multiplier `0x9E3779B97F4A7C15`, written negative because it
 * exceeds [Long.MAX_VALUE]. Used to derive deterministic per-level salts.
 */
private const val GOLDEN: Long = -0x61C8864680B583EBL

/**
 * The most successively-doubled capacities a single rebuild tries before giving up —
 * the first target plus that many minus one re-doublings.
 *
 * A rebuild re-inserts every live entry by the same greedy descent, which can itself
 * overflow; a single re-doubling resolves this for well-distributed keys, so four
 * attempts is generous headroom. Exhausting them signals a hasher that defeats the
 * per-level salts (e.g. a constant hash), which funnel hashing cannot accommodate.
 */
private const val MAX_REBUILD_ATTEMPTS: Int = 4

/**
 * A clean-room implementation of **funnel hashing** from Farach-Colton, Krapivin
 * and Kuszmaul, *Optimal Bounds for Open Addressing Without Reordering*
 * (arXiv:2501.02305, 2025), mapping primitive `Long` keys to values of type [V].
 *
 * Funnel hashing is a **greedy, no-reordering** open-addressing scheme tuned for
 * *bounded behaviour at very high load*. It is the namesake research contribution of
 * this library, **not** a general-purpose fast map: it optimises worst-case *probe
 * counts*, not wall-clock time, and is expected to lose to [SwissLongMap] and the
 * standard-library `HashMap` on ordinary workloads. Reach for it when predictable
 * worst-case probing at load factors approaching `1` matters more than raw speed.
 *
 * ### Layout
 *
 * The geometry comes entirely from [FunnelSizing] (`capacity`, [delta]): a *primary*
 * region of `levelCount` levels — level `i` is `levelBucketCounts[i]` buckets of
 * `beta` slots, laid out as one contiguous run — followed by a single *special*
 * overflow array of `specialSize` slots. Keys live in a `LongArray`, values in an
 * `Array<Any?>`, and a parallel `ByteArray` of occupancy bytes
 * ([EMPTY]/[FULL]/[TOMBSTONE]) marks each slot. Unlike the SwissTable maps, no
 * packed SWAR control words are used: a funnel bucket is small — `beta` slots, where
 * `beta = ceil(2*log2(1/delta))` (7 at the default `delta`, growing as `delta`
 * shrinks) — and a whole bucket fits within a cache line, so a plain per-slot scan is
 * simpler than SWAR's eight-wide group machinery and just as fast.
 *
 * Each level salts the key hash deterministically
 * (`fmix64(hash xor levelSalt[i])`) so probe paths decorrelate across levels, and a
 * key maps to exactly **one** bucket per level (a real modulo of the salted hash by
 * the level's bucket count, which is not a power of two).
 *
 * ### Insertion (greedy descent)
 *
 * A key is sought across all levels and the special array first; if present, its
 * value is overwritten. Only once the key is proven absent does placement descend the
 * levels, placing into the first level whose bucket has a free slot and descending
 * past any bucket entirely full of other keys; if every level's bucket is full, the
 * key spills into the special array (a short linear probe).
 * Searching *before* placing is essential: deciding placement from a single bucket
 * alone could place a key shallow while a live copy sits deeper, creating cross-level
 * duplicates once tombstones exist.
 *
 * ### Search termination
 *
 * A search descends past a bucket only when it is entirely full; the first [EMPTY]
 * slot it meets proves the key absent and stops the whole search. This is sound
 * because a key placed at level `j` was placed only because every earlier level's
 * bucket was *entirely* full at the time, and a full slot never reverts to empty
 * (deletes write [TOMBSTONE], never [EMPTY]) — so a present key is never preceded by
 * an empty slot on its probe path. A [TOMBSTONE] therefore never ends a search; only
 * an [EMPTY] does.
 *
 * ### Deletion and growth
 *
 * Deletion writes a [TOMBSTONE] (an extension beyond the insertion-only paper): the
 * structure never reorders entries, so a tombstone preserves the descend/probe chain
 * that a deleted occupant participated in. Tombstones are reclaimed only on rebuild.
 * The table grows by rebuilding into a larger one and re-inserting every live entry
 * (`O(n)`, transient roughly `2×` memory): a never-used slot is consumed up to a
 * [FunnelSizing.maxInserts] budget, after which a rebuild either reclaims tombstones
 * in place (when occupancy is low) or doubles the capacity. A *structural* overflow —
 * placement finding no slot even below the budget — always grows, since the
 * capacity-invariant salts would reproduce the same collision at the same size.
 *
 * ### Hashing caveat
 *
 * Funnel hashing assumes the hash distributes keys randomly. A hasher that defeats
 * the per-level salts (for example a constant hash) can make placement impossible at
 * any capacity; such a workload fails loudly with [IllegalStateException] rather than
 * looping or silently degrading. Use [SwissLongMap] for adversarial keys.
 *
 * ### Concurrency
 *
 * Single-threaded, like [SwissLongMap]: no synchronization, concurrent mutation
 * undefined, but structured (single-field-swap rebuild; key and value written before
 * the occupancy byte) so a future lock-free-read variant is derivable.
 *
 * @param V the type of mapped values
 * @param expectedSize a hint for the number of entries, used to pre-size the table so
 *        that holding that many entries needs no rebuild; defaults to empty
 * @param delta the target empty-fraction (`1 - load`), in the open interval `(0, 1)`;
 *        fixed for the map's lifetime (preserved across rebuilds). Smaller values
 *        pack more tightly at the cost of more levels. Defaults to `0.1` (90 % load)
 * @param hasher the hash function applied to keys; defaults to [LongHasher.Default]
 */
public class FunnelLongMap<V> public constructor(
    expectedSize: Int = 0,
    public val delta: Double = DEFAULT_DELTA,
    private val hasher: LongHasher = LongHasher.Default,
) : OpenAddressingLongMap<V> {

    init {
        // `expectedSize` is validated by `FunnelCapacity.forEntries` below, as in the
        // Phase-1 maps; `delta` is this map's own contract, so it is checked here.
        requireValidDelta(delta)
    }

    private var tables: Tables<V> =
        Tables(FunnelSizing(FunnelCapacity.forEntries(expectedSize, delta), delta), hasher)

    private var entryCount: Int = 0

    /** The number of inserts into never-used slots allowed before a rebuild is forced. */
    private var growthLeft: Int = tables.maxInserts

    /**
     * The number of slots examined by the most recent search (any of [get],
     * [containsKey], the lookup phase of [put], or [remove]). Internal
     * instrumentation that lets tests assert the paper's probe-count bound; not part
     * of the public surface.
     */
    internal val lastProbes: Int
        get() = tables.probes

    /**
     * The structural upper bound on the slots a single operation may examine on the
     * current table: `levelCount*beta + specialProbeLimit`. Internal instrumentation
     * paired with [lastProbes] for the probe-count bound check; not part of the public
     * surface, and it tracks the current table across rebuilds.
     */
    internal val maxProbesPerOp: Int
        get() {
            val current = tables
            return current.levelCount * current.beta + current.specialProbeLimit
        }

    public override val size: Int
        get() = entryCount

    public override operator fun get(key: Long): V? {
        val current = tables
        val slot = current.find(key, hasher.hash(key))
        return if (slot == SLOT_ABSENT) null else current.valueAt(slot)
    }

    public override fun containsKey(key: Long): Boolean {
        val current = tables
        return current.find(key, hasher.hash(key)) != SLOT_ABSENT
    }

    public override fun put(key: Long, value: V): V? {
        val baseHash = hasher.hash(key)
        val current = tables
        val existing = current.find(key, baseHash)
        if (existing != SLOT_ABSENT) {
            val previous = current.valueAt(existing)
            current.values[existing] = value
            return previous
        }
        insertAbsent(key, value, baseHash)
        return null
    }

    public override fun remove(key: Long): V? {
        val current = tables
        val slot = current.find(key, hasher.hash(key))
        if (slot == SLOT_ABSENT) {
            return null
        }
        val previous = current.valueAt(slot)
        // A tombstone keeps the descend/probe chain intact; occupancy is unchanged,
        // so `growthLeft` is not credited back until a rebuild reclaims the tombstone.
        current.ctrl[slot] = TOMBSTONE
        current.keys[slot] = 0L
        current.values[slot] = null
        entryCount--
        return previous
    }

    public override fun clear() {
        val sizing = tables.sizing
        tables = Tables(sizing, hasher)
        entryCount = 0
        growthLeft = sizing.maxInserts
    }

    /**
     * Places a [key] proven absent from [tables], first rebuilding when growth is
     * exhausted or no slot exists. The key and value are written before the occupancy
     * byte, so the slot is never observed full holding a stale entry.
     */
    private fun insertAbsent(key: Long, value: V, baseHash: Long) {
        val current = tables
        val slot = current.placement(baseHash)
        val fromEmpty = slot != SLOT_ABSENT && current.ctrl[slot] == EMPTY
        if (slot == SLOT_ABSENT || (fromEmpty && growthLeft == 0)) {
            rebuildOrGrow(key, value, structural = slot == SLOT_ABSENT)
            return
        }
        current.keys[slot] = key
        current.values[slot] = value
        current.ctrl[slot] = FULL
        entryCount++
        if (fromEmpty) {
            growthLeft--
        }
    }

    /**
     * Rebuilds into a fresh table that also holds the pending (absent) [pendingKey],
     * dropping tombstones, then publishes it with a single field write.
     *
     * A [structural] overflow always grows the capacity (at the same capacity the
     * salts and per-level moduli are unchanged, so a same-size rebuild would reproduce
     * the very collision that overflowed); otherwise a low-occupancy table rebuilds in
     * place to reclaim tombstones, and a fuller one doubles. Because the greedy
     * re-insertion can itself overflow, the drain is retried at successively doubled
     * capacities, up to [MAX_REBUILD_ATTEMPTS] in all, before failing loudly.
     */
    @Suppress("LoopWithTooManyJumpStatements") // The drain exits early on the first
    // entry that does not fit, so the caller can grow the target and retry.
    private fun rebuildOrGrow(pendingKey: Long, pendingValue: V, structural: Boolean) {
        val current = tables
        val reclaimInPlace = !structural && entryCount <= current.maxInserts / 2
        var capacity =
            if (reclaimInPlace) current.capacity else FunnelCapacity.grown(current.capacity)
        var attempt = 0
        while (true) {
            val rebuilt = Tables<V>(FunnelSizing(capacity, delta), hasher)
            var drained = true
            val sourceCtrl = current.ctrl
            for (slot in 0 until current.totalSlots) {
                if (sourceCtrl[slot] == FULL &&
                    !rebuilt.reinsert(current.keys[slot], current.valueAt(slot))
                ) {
                    drained = false
                    break
                }
            }
            if (drained && rebuilt.reinsert(pendingKey, pendingValue)) {
                tables = rebuilt
                entryCount++
                growthLeft = rebuilt.maxInserts - entryCount
                return
            }
            attempt++
            check(attempt < MAX_REBUILD_ATTEMPTS) {
                "Funnel rebuild could not place all entries after trying $attempt " +
                    "capacities; the hash function may be defeating the per-level salts."
            }
            capacity = FunnelCapacity.grown(capacity)
        }
    }

    /**
     * The fixed-geometry storage for one [FunnelSizing], swapped by a single field
     * write on rebuild. The primary levels occupy slots `[0, specialBase)` and the
     * special array occupies the tail `[specialBase, totalSlots)`, so a single triple
     * of `keys`/`values`/`ctrl` arrays backs both regions. The search and placement
     * probes live here, against this one table's geometry.
     */
    private class Tables<V>(val sizing: FunnelSizing, private val hasher: LongHasher) {

        val beta: Int = sizing.beta
        val levelCount: Int = sizing.levelCount
        val specialSize: Int = sizing.specialSize
        val specialProbeLimit: Int = sizing.specialProbeLimit()

        private val levelBucketCounts: IntArray = sizing.levelBucketCounts

        /** The flat start offset of each primary level within the slot arrays. */
        private val levelOffset: IntArray = IntArray(levelCount).also { offsets ->
            var accumulated = 0
            for (level in 0 until levelCount) {
                offsets[level] = accumulated
                accumulated += levelBucketCounts[level] * beta
            }
        }

        /** The first slot of the special array (just past the primary region). */
        private val specialBase: Int = sizing.totalBuckets * beta

        val totalSlots: Int = specialBase + specialSize

        private val levelSalt: LongArray = LongArray(levelCount) { GOLDEN * (it + 1) }
        private val specialSalt: Long = GOLDEN * (levelCount + 1)

        val keys: LongArray = LongArray(totalSlots)
        val values: Array<Any?> = arrayOfNulls(totalSlots)
        val ctrl: ByteArray = ByteArray(totalSlots) // every lane EMPTY (0) by default

        /** Slots examined by the most recent [find], for probe-bound instrumentation. */
        var probes: Int = 0
            private set

        val capacity: Int get() = sizing.capacity
        val maxInserts: Int get() = sizing.maxInserts

        /**
         * Returns the slot holding [key], or [SLOT_ABSENT] if absent. Scans each
         * level's single bucket, descends past a full bucket, and stops at the first
         * empty slot; if every level is full, searches the special array.
         */
        fun find(key: Long, baseHash: Long): Int {
            probes = 0
            var result = DESCEND
            for (level in 0 until levelCount) {
                result = scanBucket(key, levelOffset[level] + bucketIndex(baseHash, level) * beta)
                if (result != DESCEND) {
                    break
                }
            }
            return if (result == DESCEND) findInSpecial(key, baseHash) else result
        }

        /**
         * Scans one `beta`-slot bucket from [start] for [key], returning the matching
         * slot, [SLOT_ABSENT] if an empty slot proves the key absent, or [DESCEND] if
         * the bucket is full and the key may live deeper.
         */
        @Suppress("LoopWithTooManyJumpStatements") // A match and an empty lane are the
        // two natural exits of a bucket scan.
        private fun scanBucket(key: Long, start: Int): Int {
            var result = DESCEND
            for (lane in 0 until beta) {
                val slot = start + lane
                probes++
                val occupancy = ctrl[slot]
                if (occupancy == EMPTY) {
                    result = SLOT_ABSENT
                    break
                }
                if (occupancy == FULL && keys[slot] == key) {
                    result = slot
                    break
                }
            }
            return result
        }

        /**
         * Searches the special array for [key] with a linear probe of up to
         * [FunnelSizing.specialProbeLimit] slots from the home index, stopping at the
         * first empty slot (which proves the key absent).
         */
        @Suppress("LoopWithTooManyJumpStatements") // Same two exits as a bucket scan.
        private fun findInSpecial(key: Long, baseHash: Long): Int {
            val home = specialBucketBase(baseHash)
            var result = SLOT_ABSENT
            for (step in 0 until specialProbeLimit) {
                val slot = specialBase + (home + step) % specialSize
                probes++
                val occupancy = ctrl[slot]
                if (occupancy == EMPTY) {
                    break
                }
                if (occupancy == FULL && keys[slot] == key) {
                    result = slot
                    break
                }
            }
            return result
        }

        /**
         * Returns the slot at which an absent key with hash [baseHash] should be
         * placed: the first non-full lane (a [TOMBSTONE] preferred over a never-used
         * [EMPTY], so deletions are reclaimed eagerly) of the first level whose bucket
         * is not entirely full, then the special array, or [SLOT_ABSENT] when nothing
         * is free.
         */
        fun placement(baseHash: Long): Int {
            for (level in 0 until levelCount) {
                val slot = bucketFreeSlot(levelOffset[level] + bucketIndex(baseHash, level) * beta)
                if (slot != SLOT_ABSENT) {
                    return slot
                }
            }
            return placeInSpecial(baseHash)
        }

        /**
         * Returns the slot to place an absent key into the `beta`-slot bucket from
         * [start] (a [TOMBSTONE] preferred over the first [EMPTY]), or [SLOT_ABSENT]
         * if the bucket is entirely full.
         */
        @Suppress("LoopWithTooManyJumpStatements") // Stops at the first empty lane.
        private fun bucketFreeSlot(start: Int): Int {
            var firstTomb = SLOT_ABSENT
            var result = SLOT_ABSENT
            for (lane in 0 until beta) {
                val slot = start + lane
                val occupancy = ctrl[slot]
                if (occupancy == EMPTY) {
                    result = slot
                    break
                }
                if (occupancy == TOMBSTONE && firstTomb == SLOT_ABSENT) {
                    firstTomb = slot
                }
            }
            return if (firstTomb != SLOT_ABSENT) firstTomb else result
        }

        /**
         * Returns the special-array slot for an absent key: the first non-full slot of
         * the linear probe (a [TOMBSTONE] preferred over a never-used [EMPTY], so
         * deletions are reclaimed eagerly), or [SLOT_ABSENT] when the probe window is
         * entirely full.
         */
        @Suppress("LoopWithTooManyJumpStatements") // Stops at the first empty slot.
        private fun placeInSpecial(baseHash: Long): Int {
            val home = specialBucketBase(baseHash)
            var firstTomb = SLOT_ABSENT
            var firstEmpty = SLOT_ABSENT
            for (step in 0 until specialProbeLimit) {
                val slot = specialBase + (home + step) % specialSize
                val occupancy = ctrl[slot]
                if (occupancy == EMPTY) {
                    firstEmpty = slot
                    break
                }
                if (occupancy == TOMBSTONE && firstTomb == SLOT_ABSENT) {
                    firstTomb = slot
                }
            }
            return if (firstTomb != SLOT_ABSENT) firstTomb else firstEmpty
        }

        /**
         * Inserts a [key] known to be unique and absent into this freshly built table
         * (which holds no tombstones), returning `false` if it structurally overflows.
         * Used to drain entries during a rebuild.
         */
        fun reinsert(key: Long, value: V?): Boolean {
            val slot = placement(hasher.hash(key))
            if (slot == SLOT_ABSENT) {
                return false
            }
            keys[slot] = key
            values[slot] = value
            ctrl[slot] = FULL
            return true
        }

        /** The index of a key's single bucket within primary [level]. */
        fun bucketIndex(baseHash: Long, level: Int): Int =
            fmix64(baseHash xor levelSalt[level]).mod(levelBucketCounts[level].toLong()).toInt()

        /** The home index of a key within the special array. */
        fun specialBucketBase(baseHash: Long): Int =
            fmix64(baseHash xor specialSalt).mod(specialSize.toLong()).toInt()

        /** The value at a full [slot], cast back from its erased storage. */
        @Suppress("UNCHECKED_CAST") // Only a `V` is ever stored at a full slot.
        fun valueAt(slot: Int): V? = values[slot] as V?
    }
}
