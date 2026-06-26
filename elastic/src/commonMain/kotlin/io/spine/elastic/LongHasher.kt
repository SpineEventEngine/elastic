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
 * Computes a 64-bit hash of a `Long` key.
 *
 * A pluggable hasher lets callers override the default mixing — for example to
 * defend against adversarial key distributions. Per-level salting (used by the
 * multi-level structures) is applied internally by each table on top of the
 * hash this function produces.
 */
public fun interface LongHasher {

    /** Returns the 64-bit hash of [key]. */
    public fun hash(key: Long): Long

    public companion object {

        /**
         * The default hasher: the MurmurHash3 64-bit finalizer ([fmix64]).
         *
         * `Long` keys are frequently sequential (entity ids, counters); the
         * finalizer spreads such low-entropy inputs across the whole 64-bit
         * range, which an open-addressing table relies on to keep probe chains
         * short.
         */
        public val Default: LongHasher = LongHasher(::fmix64)

        /**
         * A faster single-multiply Fibonacci (Knuth multiplicative) hash —
         * `key *` the golden-ratio constant `0x9E3779B97F4A7C15`.
         *
         * It costs one multiply versus [Default]'s two, which measurably speeds
         * lookups and inserts (the hash is a real fraction of the in-cache cost).
         * The trade-off is robustness: a single multiply does **not** avalanche the
         * low bits, so structured key sets — for example keys that are all multiples
         * of a large power of two — collapse onto few groups and lengthen probe
         * chains, exactly the case [Default] guards against. Prefer it only when keys
         * are known to be well distributed (random or sequential ids); keep [Default]
         * when the key source is untrusted.
         */
        public val Fibonacci: LongHasher = LongHasher { key -> key * GOLDEN_RATIO }

        /**
         * The standard Fibonacci-hashing multiplier `0x9E3779B97F4A7C15`
         * (≈ `2^64 / golden ratio`, forced odd), written as a negative literal
         * because the value exceeds [Long.MAX_VALUE].
         */
        @Suppress("MagicNumber")
        private const val GOLDEN_RATIO: Long = -0x61C8864680B583EBL
    }
}

/**
 * The MurmurHash3 64-bit finalizer (`fmix64`) by Austin Appleby — a fast,
 * allocation-free avalanche mix that makes every input bit affect every output
 * bit. The two multiplier constants are the published MurmurHash3 values.
 */
@Suppress("MagicNumber") // The two multipliers and the 33-bit shift are the
// published MurmurHash3 finalizer constants; naming them adds no clarity.
public fun fmix64(key: Long): Long {
    var h = key
    h = h xor (h ushr 33)
    h *= 0xff51afd7ed558ccduL.toLong()
    h = h xor (h ushr 33)
    h *= 0xc4ceb9fe1a85ec53uL.toLong()
    h = h xor (h ushr 33)
    return h
}
