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

import io.spine.elastic.internal.ElasticCapacity
import io.spine.elastic.internal.ElasticSizing
import io.spine.elastic.internal.requireValidDelta

/** Occupancy of a slot whose key/value have never been written. */
private const val EMPTY: Byte = 0

/** Occupancy of a slot that currently holds a live entry. */
private const val FULL: Byte = 1

/** Occupancy of a slot whose entry has been removed (a tombstone). */
private const val TOMBSTONE: Byte = 2

/** Sentinel for "no such slot" — key absent, or no free slot. */
private const val SLOT_ABSENT: Int = -1

/** The default target empty-fraction (`1 - load`); a 90 % load table. */
private const val DEFAULT_DELTA: Double = 0.1

/**
 * The next-level free-fraction threshold from the paper's three-case insertion: a
 * level defers placement into the current level only while the *next* level still has
 * more than this fraction free.
 */
private const val NEXT_LEVEL_FREE_THRESHOLD: Double = 0.25

/**
 * The golden-ratio multiplier `0x9E3779B97F4A7C15`, written negative because it
 * exceeds [Long.MAX_VALUE]. Used to derive deterministic per-level salts.
 */
private const val GOLDEN: Long = -0x61C8864680B583EBL

/**
 * A clean-room implementation of **elastic hashing** from Farach-Colton, Krapivin and
 * Kuszmaul, *Optimal Bounds for Open Addressing Without Reordering* (arXiv:2501.02305,
 * 2025, §4), mapping primitive `Long` keys to values of type [V].
 *
 * Elastic hashing is the **non-greedy** open-addressing scheme that breaks the
 * amortized-`log` barrier: `O(1)` amortized and `O(log 1/δ)` worst-case *expected*
 * probes. It is the namesake research contribution of this library and, like
 * [FunnelLongMap], a **bounded-worst-case specialist for very high load**, *not* a
 * general-purpose fast map: it optimises probe counts, not wall-clock time, and is
 * expected to lose to [SwissLongMap] and the standard-library `HashMap` on ordinary
 * workloads. The lookup-at-scale cost in particular can exceed an ordinary map's (the
 * level-by-level probe ordering trades search locality for the insertion bound).
 *
 * ### Layout
 *
 * The slot array is partitioned into geometric **levels** `A₀, A₁, …` of decreasing
 * size — `A₀` the largest (`capacity/2`), each subsequent level half the previous, down
 * to a size-1 tail of two unit levels ([ElasticSizing.binaryLevelSizes]). Levels are
 * **largest-first**,
 * matching the paper's `A₁`-largest cascade; this deliberately *reverses* the
 * `sternma/optopenhash` reference's smallest-first iteration (the reference walks the
 * geometric series the wrong way), so do not "align" it back. Each level is a power of
 * two so triangular probing `(h + j(j+1)/2) mod size` covers its slots exactly once —
 * full coverage the reference's quadratic probing lacks, and what lets the table fill to
 * load `1 − δ`. Keys live in a `LongArray`, values in an `Array<Any?>`, and a parallel
 * `ByteArray` of occupancy bytes ([EMPTY]/[FULL]/[TOMBSTONE]) marks each slot. Each
 * level salts the key hash deterministically (`fmix64(hash xor levelSalt[i])`) so probe
 * paths decorrelate across levels.
 *
 * ### Insertion (non-greedy, three-case)
 *
 * A key is sought across all levels first; if present, its value is overwritten.
 * Searching *before* placing is essential — the non-greedy rule can defer a key to a
 * deeper level even while a shallower level has room, so deciding placement from one
 * level alone could leave a live copy stranded elsewhere and create cross-level
 * duplicates once tombstones exist.
 *
 * A proven-absent key descends the levels largest-first. With `ε₁` the free fraction of
 * the current level `Aᵢ` and `ε₂` that of the next level `Aᵢ₊₁`, and the per-level probe
 * budget `f(ε) = ` [ElasticSizing.probeLimit]:
 *
 *  - **`ε₁ > δ/2` and `ε₂ > 0.25`:** probe up to `f(ε₁)` slots in `Aᵢ`; place in the
 *    first free one, else **defer** to `Aᵢ₊₁`.
 *  - **`ε₁ ≤ δ/2`** (this level nearly full): **skip** `Aᵢ`, descend.
 *  - **`ε₂ ≤ 0.25`** (the next level nearly full): fill `Aᵢ` **greedily** (scan it all).
 *
 * The last (size-1) level is always greedy. Because `ε` is the *used*-based free
 * fraction (see *Deletion and growth*), Case 2 can skip a level that still has a free
 * slot, so the descent ends with a **table-wide greedy fallback**: if no level admitted
 * the key, a greedy sweep largest-first places it in the first free slot anywhere. This
 * guarantees placement never reports a structural overflow while any slot is free.
 *
 * ### Search termination
 *
 * Because the non-greedy rule defers and skips, a key can sit in a level deeper than the
 * first with free space, so a search **cannot** stop at the first empty slot across the
 * whole table the way [FunnelLongMap] does. Search is **level-by-level**: per level it
 * probes the triangular sequence, stopping at the first [EMPTY] slot *within that level*
 * (a [TOMBSTONE] never stops it), then continues to the next level; the first key match
 * wins. This is sound because a key is placed at the first non-[FULL] slot in its level's
 * triangular order, every earlier slot was [FULL] at placement time, and a [FULL] slot
 * never reverts to [EMPTY] (deletes write [TOMBSTONE]) — so search always reaches it.
 *
 * This level-by-level search is a deliberate, documented simplification of the paper's
 * `φ` injection, which interleaves probes across adjacent levels to tighten the
 * worst-case *search* bound; the `O(1)`-amortized *insertion* win (where the budget and
 * deferral act, touching only two adjacent levels) does not depend on `φ` and is fully
 * realized here.
 *
 * ### Deletion and growth
 *
 * Deletion writes a [TOMBSTONE] (an extension beyond the insertion-only paper): no
 * reordering, so a tombstone preserves the probe chain a deleted occupant participated
 * in. A per-level `used` count (slots ever written — [FULL] plus [TOMBSTONE]) drives the
 * free fraction `ε`; it is deliberately pessimistic under tombstones. A tombstone passed
 * inside an entered probe window is reused before a never-used [EMPTY] (best-effort
 * reclaim); tombstone-heavy near-full levels are reclaimed only at the next rebuild. The
 * table grows by rebuilding into a larger one and re-inserting every live entry (`O(n)`,
 * transient roughly `2×` memory): a never-used slot is consumed up to a
 * [ElasticSizing.maxInserts] budget, after which a rebuild either reclaims tombstones in
 * place (when occupancy is low) or doubles the capacity. **Pre-sizable to `(n, δ)`**.
 *
 * ### Hashing caveat
 *
 * Unlike [FunnelLongMap], a degenerate hasher does *not* make this map throw: full
 * coverage lets each level fill completely and the table simply grows, every key
 * remaining findable. This is a *trade-off*, not a free advantage — a constant hasher
 * makes operations degrade to `Θ(capacity)` probes and memory grow without bound, where
 * funnel fails loudly but cheaply. Prefer [LongHasher.Default]; for untrusted keys
 * consider [SwissLongMap].
 *
 * ### Concurrency
 *
 * Single-threaded, like the sibling maps: no synchronization, concurrent mutation
 * undefined, but structured (single-field-swap rebuild; key and value written before the
 * occupancy byte) so a future lock-free-read variant is derivable.
 *
 * @param V the type of mapped values
 * @param expectedSize a hint for the number of entries, used to pre-size the table so
 *        that holding that many entries needs no rebuild; defaults to empty
 * @param delta the target empty-fraction (`1 - load`), in the open interval `(0, 1)`;
 *        fixed for the map's lifetime (preserved across rebuilds). Smaller values pack
 *        more tightly at the cost of more levels. Defaults to `0.1` (90 % load)
 * @param hasher the hash function applied to keys; defaults to [LongHasher.Default]
 */
public class ElasticLongMap<V> public constructor(
    expectedSize: Int = 0,
    public val delta: Double = DEFAULT_DELTA,
    private val hasher: LongHasher = LongHasher.Default,
) : OpenAddressingLongMap<V> {

    init {
        // `expectedSize` is validated by `ElasticCapacity.forEntries` below, as in the
        // sibling maps; `delta` is this map's own contract, so it is checked here.
        requireValidDelta(delta)
    }

    private var tables: Tables<V> =
        Tables(ElasticCapacity.forEntries(expectedSize, delta), delta, hasher)

    private var entryCount: Int = 0

    /** The number of inserts into never-used slots allowed before a rebuild is forced. */
    private var growthLeft: Int = tables.maxInserts

    /**
     * The number of slots examined by the most recent search (any of [get],
     * [containsKey], the lookup phase of [put], or [remove]). Internal instrumentation
     * for the probe-count tests; not part of the public surface.
     */
    internal val lastProbes: Int
        get() = tables.probes

    /**
     * The number of slots examined by the most recent [put]'s placement descent.
     * Internal instrumentation: the amortized mean of this over a fill is the elastic
     * `O(1)`-amortized insertion metric the probe-bound test gates on. Not public.
     */
    internal val lastPlacementProbes: Int
        get() = tables.placementProbes

    /** The current table's capacity. Internal instrumentation for growth tests. */
    internal val tableCapacity: Int
        get() = tables.capacity

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
        // A tombstone keeps the probe chain intact; `used` is unchanged, so `growthLeft`
        // is not credited back until a rebuild reclaims the tombstone.
        current.ctrl[slot] = TOMBSTONE
        current.keys[slot] = 0L
        current.values[slot] = null
        entryCount--
        return previous
    }

    public override fun clear() {
        val capacity = tables.capacity
        tables = Tables(capacity, delta, hasher)
        entryCount = 0
        growthLeft = tables.maxInserts
    }

    /**
     * Places a [key] proven absent from [tables], first rebuilding when growth is
     * exhausted or (only when the whole table is full) no slot exists. The key and value
     * are written before the occupancy byte, so the slot is never observed full holding
     * a stale entry.
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
            current.used[current.levelOf(slot)]++
            growthLeft--
        }
    }

    /**
     * Rebuilds into a fresh table holding all live entries plus the pending (absent)
     * [pendingKey], dropping tombstones, then publishes it with a single field write.
     *
     * The fresh table must have room for every live entry **plus** the pending one
     * within its `1 - delta` budget, so the target capacity is grown until
     * [ElasticSizing.maxInserts] is at least that count. A high `delta` reserves close
     * to a whole slot per entry, so this can take several doublings — a single doubling
     * (or worse, the same capacity) could leave `maxInserts` below the live count, which
     * would drive `growthLeft` negative and silently break the load cap. Low occupancy
     * (`entryCount ≤ maxInserts/2`) with a capacity that already fits rebuilds in place
     * to reclaim tombstones rather than growing. The drain re-inserts by a pure greedy
     * descent, which over full-coverage levels always fits a table sized for its
     * entries — so, unlike a funnel rebuild, an elastic rebuild can never itself
     * overflow and a single pass suffices ([Tables.reinsert] fails loudly rather than
     * dropping an entry if that invariant is ever violated).
     */
    private fun rebuildOrGrow(pendingKey: Long, pendingValue: V, structural: Boolean) {
        val current = tables
        val needed = entryCount + 1
        val reclaimInPlace =
            !structural && entryCount <= current.maxInserts / 2 && current.maxInserts >= needed
        val capacity =
            if (reclaimInPlace) current.capacity else grownToHold(current.capacity, needed)
        val rebuilt = Tables<V>(capacity, delta, hasher)
        val sourceCtrl = current.ctrl
        for (slot in sourceCtrl.indices) {
            if (sourceCtrl[slot] == FULL) {
                rebuilt.reinsert(current.keys[slot], current.valueAt(slot))
            }
        }
        rebuilt.reinsert(pendingKey, pendingValue)
        tables = rebuilt
        entryCount++
        growthLeft = rebuilt.maxInserts - entryCount
    }

    /**
     * The next capacity strictly larger than [from] whose [ElasticSizing.maxInserts] at
     * [delta] is at least [needed]. Always grows by at least one doubling; a high `delta`
     * (little free budget per slot) may require several before the budget admits [needed]
     * entries.
     */
    private fun grownToHold(from: Int, needed: Int): Int {
        var capacity = ElasticCapacity.grown(from)
        while (ElasticSizing.maxInserts(capacity, delta) < needed) {
            capacity = ElasticCapacity.grown(capacity)
        }
        return capacity
    }

    /**
     * The fixed-geometry storage for one capacity, swapped by a single field write on
     * rebuild. Levels occupy contiguous ranges `[levelOffset[i], levelOffset[i] + size)`
     * within one triple of `keys`/`values`/`ctrl` arrays; the search and placement
     * probes live here, against this one table's geometry.
     */
    private class Tables<V>(
        val capacity: Int,
        private val delta: Double,
        private val hasher: LongHasher,
    ) {

        private val levelSizes: IntArray = ElasticSizing.binaryLevelSizes(capacity)

        /** The single source of the map's level count (`= levelCount(capacity) + 1`). */
        val levelCount: Int = levelSizes.size

        /** Per-level `size - 1`, an exact modulo mask for the power-of-two level. */
        private val levelMask: IntArray = IntArray(levelCount) { levelSizes[it] - 1 }

        /** The flat start offset of each level within the slot arrays. */
        private val levelOffset: IntArray = IntArray(levelCount).also { offsets ->
            var accumulated = 0
            for (level in 0 until levelCount) {
                offsets[level] = accumulated
                accumulated += levelSizes[level]
            }
        }

        private val levelSalt: LongArray = LongArray(levelCount) { GOLDEN * (it + 1) }

        /** `δ/2`, the Case 2 threshold, precomputed off the hot path. */
        private val deltaHalf: Double = delta / 2

        /** Per-level count of ever-written slots ([FULL] + [TOMBSTONE]); drives `ε`. */
        val used: IntArray = IntArray(levelCount)

        val keys: LongArray = LongArray(capacity)
        val values: Array<Any?> = arrayOfNulls(capacity)
        val ctrl: ByteArray = ByteArray(capacity) // every slot EMPTY (0) by default

        val maxInserts: Int = ElasticSizing.maxInserts(capacity, delta)

        /** Slots examined by the most recent [find], for probe-bound instrumentation. */
        var probes: Int = 0
            private set

        /** Slots examined by the most recent [placement], for the amortized-insert metric. */
        var placementProbes: Int = 0
            private set

        /**
         * Returns the slot holding [key], or [SLOT_ABSENT] if absent. Scans each level's
         * triangular sequence, stopping within a level at the first empty slot, and
         * continues to the next level (a key may be deferred deeper than a shallow free
         * slot), so all levels are scanned until a match is found.
         */
        fun find(key: Long, baseHash: Long): Int {
            probes = 0
            var result = SLOT_ABSENT
            var level = 0
            while (level < levelCount && result == SLOT_ABSENT) {
                result = scanLevel(key, level, baseHash)
                level++
            }
            return result
        }

        /**
         * Scans one [level]'s full triangular sequence for [key], returning its slot, or
         * [SLOT_ABSENT] if the first empty slot proves it absent here (it may live
         * deeper) or the level is exhausted with no match.
         */
        @Suppress("LoopWithTooManyJumpStatements") // A match and an empty lane are the
        // two natural early exits of a level scan.
        private fun scanLevel(key: Long, level: Int, baseHash: Long): Int {
            val size = levelSizes[level]
            val mask = levelMask[level]
            val offset = levelOffset[level]
            var pos = (levelHash(baseHash, level) and mask.toLong()).toInt()
            // The triangular sequence visits all `size` slots over `size` steps, so
            // `stride` (incremented each step) both advances the probe and bounds it.
            var stride = 0
            var result = SLOT_ABSENT
            while (stride < size) {
                val slot = offset + pos
                probes++
                val occupancy = ctrl[slot]
                if (occupancy == EMPTY) {
                    break
                }
                if (occupancy == FULL && keys[slot] == key) {
                    result = slot
                    break
                }
                stride++
                pos = (pos + stride) and mask
            }
            return result
        }

        /**
         * Returns the slot at which an absent key with hash [baseHash] should be placed
         * by the non-greedy three-case descent, falling back to a table-wide greedy
         * sweep so a free slot is never missed; or [SLOT_ABSENT] only when the whole
         * table is full.
         */
        fun placement(baseHash: Long): Int {
            placementProbes = 0
            val last = levelCount - 1
            var result = SLOT_ABSENT
            var level = 0
            while (level <= last && result == SLOT_ABSENT) {
                result = placeInLevel(level, last, baseHash)
                level++
            }
            return if (result != SLOT_ABSENT) result else greedyPlacement(baseHash)
        }

        /**
         * The three-case placement decision for one [level]: Case 1 (budgeted probe with
         * deferral), Case 2 ([SLOT_ABSENT] = skip), or Case 3 / the greedy last level.
         */
        private fun placeInLevel(level: Int, last: Int, baseHash: Long): Int =
            if (level == last) {
                freeSlotInLevel(level, baseHash, levelSizes[level])
            } else {
                val size = levelSizes[level]
                val eps1 = (size - used[level]).toDouble() / size
                val nextSize = levelSizes[level + 1]
                val eps2 = (nextSize - used[level + 1]).toDouble() / nextSize
                when {
                    eps1 > deltaHalf && eps2 > NEXT_LEVEL_FREE_THRESHOLD ->
                        freeSlotInLevel(level, baseHash, ElasticSizing.probeLimit(eps1, delta))
                    eps1 <= deltaHalf -> SLOT_ABSENT
                    else -> freeSlotInLevel(level, baseHash, size)
                }
            }

        /**
         * Places greedily largest-first: the first non-full slot of the first level that
         * has one, scanning each level's full triangular sequence. Used as [placement]'s
         * fallback and as the rebuild drain. Returns [SLOT_ABSENT] only when every slot
         * is full.
         */
        fun greedyPlacement(baseHash: Long): Int {
            var result = SLOT_ABSENT
            var level = 0
            while (level < levelCount && result == SLOT_ABSENT) {
                result = freeSlotInLevel(level, baseHash, levelSizes[level])
                level++
            }
            return result
        }

        /**
         * Returns the slot to place an absent key into [level] within [limit] triangular
         * probes: a [TOMBSTONE] passed before the first [EMPTY] is preferred (reclaim),
         * else the first [EMPTY]; [SLOT_ABSENT] if neither appears within the window.
         */
        @Suppress("LoopWithTooManyJumpStatements") // Stops at the first empty lane.
        private fun freeSlotInLevel(level: Int, baseHash: Long, limit: Int): Int {
            val mask = levelMask[level]
            val offset = levelOffset[level]
            val steps = minOf(limit, levelSizes[level])
            var pos = (levelHash(baseHash, level) and mask.toLong()).toInt()
            // `stride` advances the triangular probe and bounds it (see `scanLevel`).
            var stride = 0
            var firstTomb = SLOT_ABSENT
            var firstEmpty = SLOT_ABSENT
            while (stride < steps) {
                val slot = offset + pos
                placementProbes++
                val occupancy = ctrl[slot]
                if (occupancy == EMPTY) {
                    firstEmpty = slot
                    break
                }
                if (occupancy == TOMBSTONE && firstTomb == SLOT_ABSENT) {
                    firstTomb = slot
                }
                stride++
                pos = (pos + stride) and mask
            }
            return if (firstTomb != SLOT_ABSENT) firstTomb else firstEmpty
        }

        /**
         * Inserts a [key] known unique and absent into this freshly built table (which
         * holds no tombstones) by a pure greedy descent. Used to drain entries during a
         * rebuild; a table sized for its entries always has room under full coverage, so
         * a failure to place signals a sizing-logic error and fails loudly rather than
         * silently dropping the entry.
         */
        fun reinsert(key: Long, value: V?) {
            val slot = greedyPlacement(hasher.hash(key))
            check(slot != SLOT_ABSENT) {
                "Elastic rebuild overflowed a table sized for its entries; " +
                    "this is unreachable with full-coverage levels."
            }
            keys[slot] = key
            values[slot] = value
            ctrl[slot] = FULL
            used[levelOf(slot)]++
        }

        /** The salted per-level hash of a key whose base hash is [baseHash]. */
        private fun levelHash(baseHash: Long, level: Int): Long =
            fmix64(baseHash xor levelSalt[level])

        /** The level owning a flat [slot], found from the contiguous [levelOffset]s. */
        fun levelOf(slot: Int): Int {
            var level = levelCount - 1
            while (level > 0 && slot < levelOffset[level]) {
                level--
            }
            return level
        }

        /** The value at a full [slot], cast back from its erased storage. */
        @Suppress("UNCHECKED_CAST") // Only a `V` is ever stored at a full slot.
        fun valueAt(slot: Int): V? = values[slot] as V?
    }
}
