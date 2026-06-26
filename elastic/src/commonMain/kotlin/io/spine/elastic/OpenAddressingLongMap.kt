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

/**
 * A map from primitive `Long` keys to values of type [V], backed by an
 * open-addressing table that never reorders existing entries once placed.
 *
 * The `Long` key type is primitive on purpose: the hot path stores keys in a
 * `LongArray`, so neither lookups nor insertions box the key. This is the lead
 * specialization of the library (decision DP-6); a boxed
 * [kotlin.collections.MutableMap] view over it is a later, opt-in addition
 * (decision DP-10), and a generated matrix of further specializations follows
 * via code generation (decision DP-7).
 *
 * Implementations in the initial phase are **single-threaded**. A
 * single-writer / multiple-reader, lock-free-read variant is a separate phase
 * (decision DP-13); the single-threaded cores are nevertheless designed so that
 * variant can be derived rather than retrofitted.
 *
 * @param V the type of mapped values
 */
public interface OpenAddressingLongMap<V> {

    /** The number of key-value pairs currently stored. */
    public val size: Int

    /** Tells whether the map contains no entries. */
    public fun isEmpty(): Boolean = size == 0

    /**
     * Associates [value] with [key].
     *
     * Existing entries are never relocated by this call.
     *
     * @return the value previously associated with [key], or `null` if the key
     *         was not present
     */
    public fun put(key: Long, value: V): V?

    /**
     * Returns the value associated with [key], or `null` if the key is absent.
     */
    public operator fun get(key: Long): V?

    /** Tells whether [key] is present in the map. */
    public fun containsKey(key: Long): Boolean

    /**
     * Removes the entry for [key], if present.
     *
     * @return the value that was associated with [key], or `null` if the key
     *         was not present
     */
    public fun remove(key: Long): V?

    /** Removes all entries from the map. */
    public fun clear()
}
