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

// Tests may use the experimental atomics for cross-thread flags; the production
// map confines its own use the same way.
@file:OptIn(ExperimentalAtomicApi::class)

package io.spine.elastic

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * Concurrent stress tests of [SingleWriterSwissLongMap]: one writer coroutine and
 * several reader coroutines on real threads. This is the only suite that
 * exercises the map's memory-model claims on Kotlin/Native as well as the JVM
 * (the Lincheck suite is JVM-only).
 *
 * The work runs on [Dispatchers.Default] deliberately — the virtual time of
 * `runTest` does not apply there, so writer and readers race on actual threads.
 * Each test carries an explicit generous timeout, which doubles as the livelock
 * guard: a reader that never terminates its probe fails the test by timeout.
 *
 * Values are multi-field [Payload] objects whose fields both encode the key, so
 * a reader that observes a value constructed by the writer also proves the
 * *contents* of that object were safely published, not only the reference. Any
 * non-null read must decode back to its key; a mismatch means a torn read or a
 * value paired with the wrong key.
 *
 * The iteration constants are deliberately modest so the suite stays CI-friendly
 * on Native; scale them up locally when hunting for races.
 */
internal class SingleWriterSwissLongMapStressSpec {

    /** A two-field value; both fields must always decode to the key. */
    private class Payload(val a: Long, val b: Long)

    private fun payloadFor(key: Long) = Payload(key, key xor PAYLOAD_SALT)

    private fun checkIntegrity(key: Long, value: Payload?) {
        if (value != null) {
            value.a shouldBe key
            value.b shouldBe (key xor PAYLOAD_SALT)
        }
    }

    /** Launches [readerCount] readers that run [body] until [done] is set. */
    private fun CoroutineScope.launchReaders(
        readerCount: Int,
        done: AtomicBoolean,
        body: (Random) -> Unit,
    ): List<Job> = (1..readerCount).map { reader ->
        launch {
            val random = Random(reader * 7_919L)
            while (!done.load()) {
                body(random)
            }
        }
    }

    @Test
    fun `readers observe ascending inserts without ever losing an entry`() =
        runTest(timeout = 3.minutes) {
            withContext(Dispatchers.Default) {
                repeat(GROWTH_REPEATS) {
                    val map = SingleWriterSwissLongMap<Payload>()
                    val done = AtomicBoolean(false)
                    val readers = launchReaders(READERS, done) { random ->
                        var maxSeen = -1L
                        // A key observed present stays present: inserts are
                        // ascending and nothing is removed, so every key below
                        // the largest observed one must be found. This is what
                        // catches an entry lost while a rebuild swaps tables.
                        repeat(READ_BATCH) {
                            val key = random.nextLong(0L, INSERT_COUNT)
                            val value = map[key]
                            checkIntegrity(key, value)
                            if (value != null && key > maxSeen) {
                                maxSeen = key
                            }
                            if (maxSeen >= 0L) {
                                val below = random.nextLong(0L, maxSeen + 1L)
                                val belowValue = map[below]
                                checkIntegrity(below, belowValue)
                                belowValue.shouldNotBeNull()
                            }
                        }
                    }
                    try {
                        for (key in 0L until INSERT_COUNT) {
                            map.put(key, payloadFor(key))
                        }
                    } finally {
                        done.store(true)
                    }
                    readers.forEach { it.join() }
                    map.size shouldBe INSERT_COUNT.toInt()
                    for (key in 0L until INSERT_COUNT) {
                        checkIntegrity(key, map[key])
                        map[key].shouldNotBeNull()
                    }
                }
            }
        }

    @Test
    fun `readers stay consistent under insert-remove churn across rebuilds`() =
        runTest(timeout = 3.minutes) {
            withContext(Dispatchers.Default) {
                // A small pre-size keeps the growth budget tight, so the churn
                // crosses many rebuilds while readers probe the sliding window.
                val map = SingleWriterSwissLongMap<Payload>()
                val done = AtomicBoolean(false)
                val readers = launchReaders(READERS, done) { random ->
                    repeat(READ_BATCH) {
                        val key = random.nextLong(0L, CHURN_ROUNDS + WINDOW)
                        checkIntegrity(key, map[key])
                    }
                }
                var next = 0L
                try {
                    while (next < CHURN_ROUNDS) {
                        map.put(next, payloadFor(next))
                        if (next >= WINDOW) {
                            map.remove(next - WINDOW)
                        }
                        next++
                    }
                } finally {
                    done.store(true)
                }
                readers.forEach { it.join() }
                // The writer's operations are deterministic, so the quiescent
                // state must equal the model: exactly the last WINDOW keys.
                map.size shouldBe WINDOW.toInt()
                for (key in (next - WINDOW) until next) {
                    map[key].shouldNotBeNull()
                    checkIntegrity(key, map[key])
                }
                for (gone in 0L until (next - WINDOW) step 97L) {
                    map.containsKey(gone) shouldBe false
                }
            }
        }

    @Test
    fun `readers survive repeated clears while probing`() =
        runTest(timeout = 3.minutes) {
            withContext(Dispatchers.Default) {
                // clear() publishes a fresh empty table with no copy — the same
                // swap the Lincheck suite covers on the JVM, exercised here on
                // Native as well.
                val map = SingleWriterSwissLongMap<Payload>()
                val done = AtomicBoolean(false)
                val readers = launchReaders(READERS, done) { random ->
                    repeat(READ_BATCH) {
                        val key = random.nextLong(0L, CLEAR_KEYS)
                        checkIntegrity(key, map[key])
                    }
                }
                try {
                    repeat(CLEAR_ROUNDS) {
                        for (key in 0L until CLEAR_KEYS) {
                            map.put(key, payloadFor(key))
                        }
                        map.clear()
                    }
                } finally {
                    done.store(true)
                }
                readers.forEach { it.join() }
                map.size shouldBe 0
                map[0L].shouldBeNull()
            }
        }

    private companion object {

        /** Readers racing the single writer in every phase. */
        const val READERS = 3

        /** Reads per reader between checks of the writer-done flag. */
        const val READ_BATCH = 256

        /** Ascending inserts per growth run; crosses ~14 rebuilds from capacity 8. */
        const val INSERT_COUNT = 100_000L

        /** Fresh maps per growth run, multiplying the resize crossings observed. */
        const val GROWTH_REPEATS = 4

        /** Churn length; with [WINDOW] live entries the table rebuilds many times. */
        const val CHURN_ROUNDS = 300_000L

        /** Live keys maintained by the churn writer. */
        const val WINDOW = 256L

        /** Keys inserted before each clear. */
        const val CLEAR_KEYS = 512L

        /** Insert-then-clear cycles under concurrent readers. */
        const val CLEAR_ROUNDS = 400

        /** Distinguishes the two payload fields, defeating an accidental swap. */
        const val PAYLOAD_SALT = 0x5DEECE66DL
    }
}
