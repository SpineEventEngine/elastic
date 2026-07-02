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

import org.jetbrains.lincheck.datastructures.IntGen
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Options
import org.jetbrains.lincheck.datastructures.Param
import org.jetbrains.lincheck.datastructures.StressOptions
import org.junit.jupiter.api.DisplayName
import kotlin.test.Test

/**
 * Linearizability tests of [SingleWriterSwissLongMap] with Lincheck, in both
 * stress and model-checking modes.
 *
 * The single-writer contract is expressed with `nonParallelGroup = "writer"` on
 * every mutating operation: Lincheck then assigns all writes to one thread of
 * the parallel part (the sequential prefix and suffix run on the main thread
 * with happens-before edges from thread start/join, satisfying the "externally
 * synchronized" form of the contract). Reads stay free and race the writer.
 *
 * `size`/`isEmpty` are deliberately absent from the operations: they are
 * documented as weakly consistent estimates, not linearizable, and the checker
 * would rightly reject them.
 *
 * Three configurations are verified:
 *
 * - [EmptyStartOperations] — the cold paths from a fresh map;
 * - [ResizeCrossingOperations] — a map pre-filled to a spent growth budget
 *   (seven distinct keys at capacity eight), so the first fresh-key `put` of
 *   the parallel part rebuilds the table and publishes the copy mid-race,
 *   while readers concurrently probe the very entries being copied — that the
 *   eighth insert grows the table is pinned by
 *   `SingleWriterSwissLongMapSpec.rebuilds on the first insert past the growth
 *   budget`;
 * - [ConstantHashOperations] — a constant hasher gives every key the same
 *   fingerprint and home group, so a reader compares the key bytes of every
 *   full lane on the shared probe chain. This is the configuration that can
 *   detect a racing reader matching a stale or rewritten key slot — for
 *   example, if `remove` ever zeroed the key the way the single-threaded map
 *   does.
 *
 * Keys are drawn from a small window so readers collide with written and
 * pre-filled entries; iteration counts are bounded to keep CI runtime sane.
 */
@DisplayName("`SingleWriterSwissLongMap` should")
internal class SingleWriterSwissLongMapLincheckSpec {

    @Test
    fun `be linearizable from an empty map, under stress`() =
        StressOptions().bounded().check(EmptyStartOperations::class)

    @Test
    fun `be linearizable from an empty map, under model checking`() =
        ModelCheckingOptions().bounded().check(EmptyStartOperations::class)

    @Test
    fun `be linearizable across a racing rebuild, under stress`() =
        StressOptions().bounded().check(ResizeCrossingOperations::class)

    @Test
    fun `be linearizable across a racing rebuild, under model checking`() =
        ModelCheckingOptions().bounded().check(ResizeCrossingOperations::class)

    @Test
    fun `be linearizable on one shared probe chain, under stress`() =
        StressOptions().bounded().check(ConstantHashOperations::class)

    @Test
    fun `be linearizable on one shared probe chain, under model checking`() =
        ModelCheckingOptions().bounded()
            // The sharpest race the no-key-rewrite rule guards against, pinned
            // as an explicit scenario: a reader probes for key 0 across a lane
            // whose key is being retired. Were `remove` to zero the key bytes
            // (as the single-threaded map does), the reader would false-match
            // the retired lane and return another key's value for key 0.
            .addCustomScenario {
                initial { actor(ConstantHashOperations::put, 3, 42) }
                parallel {
                    thread { actor(ConstantHashOperations::remove, 3) }
                    thread { actor(ConstantHashOperations::get, 0) }
                }
            }
            .check(ConstantHashOperations::class)

    /** Applies the CI-friendly scenario bounds shared by every mode. */
    private fun <O : Options<O, *>> O.bounded(): O =
        iterations(ITERATIONS)
            .invocationsPerIteration(INVOCATIONS)
            .threads(THREADS)
            .actorsPerThread(ACTORS_PER_THREAD)

    private companion object {
        const val ITERATIONS = 25
        const val INVOCATIONS = 2_000
        const val THREADS = 3
        const val ACTORS_PER_THREAD = 3
    }
}

/** The single non-parallel operation group expressing the one-writer contract. */
private const val WRITER = "writer"

/** The shared name of the bounded key parameter. */
private const val KEY = "key"

/** The operations Lincheck exercises over a fresh, empty map. */
@Param(name = KEY, gen = IntGen::class, conf = "1:16")
internal class EmptyStartOperations {

    private val map = SingleWriterSwissLongMap<Int>()

    @Operation(nonParallelGroup = WRITER)
    fun put(@Param(name = KEY) key: Int, value: Int): Int? = map.put(key.toLong(), value)

    @Operation(nonParallelGroup = WRITER)
    fun remove(@Param(name = KEY) key: Int): Int? = map.remove(key.toLong())

    @Operation(nonParallelGroup = WRITER)
    fun clear() = map.clear()

    @Operation
    fun get(@Param(name = KEY) key: Int): Int? = map[key.toLong()]

    @Operation
    fun containsKey(@Param(name = KEY) key: Int): Boolean = map.containsKey(key.toLong())
}

/**
 * The operations over a map whose keys all collide: a constant hasher forces
 * one fingerprint and one home group, so readers walk a single probe chain and
 * confirm the key bytes of every full lane they pass — including the retired
 * lanes a concurrent `remove` leaves behind.
 */
@Param(name = KEY, gen = IntGen::class, conf = "0:8")
internal class ConstantHashOperations {

    private val map = SingleWriterSwissLongMap<Int>(hasher = { 0L })

    @Operation(nonParallelGroup = WRITER)
    fun put(@Param(name = KEY) key: Int, value: Int): Int? = map.put(key.toLong(), value)

    @Operation(nonParallelGroup = WRITER)
    fun remove(@Param(name = KEY) key: Int): Int? = map.remove(key.toLong())

    @Operation(nonParallelGroup = WRITER)
    fun clear() = map.clear()

    @Operation
    fun get(@Param(name = KEY) key: Int): Int? = map[key.toLong()]

    @Operation
    fun containsKey(@Param(name = KEY) key: Int): Boolean = map.containsKey(key.toLong())
}

/**
 * The same operations over a map whose growth budget is already spent: seven
 * distinct keys at capacity eight, so the first fresh-key `put` rebuilds and
 * publishes the copied table while readers race it.
 */
@Param(name = KEY, gen = IntGen::class, conf = "1:16")
internal class ResizeCrossingOperations {

    private val map = SingleWriterSwissLongMap<Int>().apply {
        for (key in 1L..7L) {
            put(key, 0)
        }
    }

    @Operation(nonParallelGroup = WRITER)
    fun put(@Param(name = KEY) key: Int, value: Int): Int? = map.put(key.toLong(), value)

    @Operation(nonParallelGroup = WRITER)
    fun remove(@Param(name = KEY) key: Int): Int? = map.remove(key.toLong())

    @Operation(nonParallelGroup = WRITER)
    fun clear() = map.clear()

    @Operation
    fun get(@Param(name = KEY) key: Int): Int? = map[key.toLong()]

    @Operation
    fun containsKey(@Param(name = KEY) key: Int): Boolean = map.containsKey(key.toLong())
}
