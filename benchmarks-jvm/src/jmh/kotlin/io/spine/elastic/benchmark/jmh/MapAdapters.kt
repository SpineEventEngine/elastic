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

package io.spine.elastic.benchmark.jmh

import io.spine.elastic.SingleWriterSwissLongMap
import io.spine.elastic.SwissLongMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** Selects [SingleWriterSwissLongMap], the map under test. */
internal const val SINGLE_WRITER = "SINGLE_WRITER"

/** Selects the boxed `ConcurrentHashMap` baseline. */
internal const val CONCURRENT_HASH_MAP = "CONCURRENT_HASH_MAP"

/** Selects the lock-guarded [SwissLongMap] baseline. */
internal const val SYNCHRONIZED_SWISS = "SYNCHRONIZED_SWISS"

/** The value reported for a missed lookup; never seen when all keys hit. */
private const val MISSING = Long.MIN_VALUE

/** The map under test, seen through `get`/`put`/`remove` of `Long` to `Long`. */
internal interface LongToLongMap {
    fun get(key: Long): Long
    fun put(key: Long, value: Long)
    fun remove(key: Long)
}

/** [SingleWriterSwissLongMap] used as intended: lock-free readers, one writer. */
private class SingleWriterAdapter(expectedSize: Int) : LongToLongMap {
    private val map = SingleWriterSwissLongMap<Long>(expectedSize)
    override fun get(key: Long): Long = map[key] ?: MISSING
    override fun put(key: Long, value: Long) { map.put(key, value) }
    override fun remove(key: Long) { map.remove(key) }
}

/** The standard-library concurrent baseline: boxed keys and values. */
private class ConcurrentHashMapAdapter(expectedSize: Int) : LongToLongMap {
    private val map = ConcurrentHashMap<Long, Long>(expectedSize)
    override fun get(key: Long): Long = map[key] ?: MISSING
    override fun put(key: Long, value: Long) { map[key] = value }
    override fun remove(key: Long) { map.remove(key) }
}

/**
 * The "just lock the fast single-threaded map" baseline: a [SwissLongMap] with
 * every operation wrapped in `synchronized` on one shared lock.
 */
private class SynchronizedSwissAdapter(expectedSize: Int) : LongToLongMap {
    private val lock = Any()
    private val map = SwissLongMap<Long>(expectedSize)
    override fun get(key: Long): Long = synchronized(lock) { map[key] ?: MISSING }
    override fun put(key: Long, value: Long) { synchronized(lock) { map.put(key, value) } }
    override fun remove(key: Long) { synchronized(lock) { map.remove(key) } }
}

/** Creates the adapter selected by [impl], one of the `impl` parameter constants above. */
internal fun createMap(impl: String, expectedSize: Int): LongToLongMap = when (impl) {
    SINGLE_WRITER -> SingleWriterAdapter(expectedSize)
    CONCURRENT_HASH_MAP -> ConcurrentHashMapAdapter(expectedSize)
    SYNCHRONIZED_SWISS -> SynchronizedSwissAdapter(expectedSize)
    else -> throw IllegalArgumentException("Unknown map implementation: `$impl`.")
}

/**
 * Returns a deterministically shuffled copy of [keys] (Fisher–Yates, fixed seed).
 *
 * Lookup benchmarks scan keys in this random order rather than insertion order, so the
 * measurement does not flatter insertion-order locality; the fixed seed keeps the order
 * identical across implementations and runs. This duplicates the portable benchmark
 * module's internal shuffle, which cannot be referenced across modules.
 */
internal fun shuffled(keys: LongArray): LongArray {
    val out = keys.copyOf()
    val random = Random(42)
    for (i in out.indices.reversed()) {
        val j = random.nextInt(i + 1)
        val tmp = out[i]
        out[i] = out[j]
        out[j] = tmp
    }
    return out
}
