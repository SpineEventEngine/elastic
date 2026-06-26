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

import io.kotest.matchers.shouldBe
import kotlin.test.Test

internal class LongHasherSpec {

    @Test
    fun `maps zero to zero`() {
        fmix64(0L) shouldBe 0L
    }

    @Test
    fun `matches the reference MurmurHash3 64-bit finalizer`() {
        fmix64(1L) shouldBe -5451962507482445012L
        fmix64(2L) shouldBe 4233148493373801447L
        fmix64(-1L) shouldBe 7256831767414464289L
    }

    @Test
    fun `exposes the finalizer through the default hasher`() {
        val keys = listOf(0L, 1L, 2L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 12_345L)
        for (key in keys) {
            LongHasher.Default.hash(key) shouldBe fmix64(key)
        }
    }

    @Test
    fun `avalanches sequential keys to distinct hashes`() {
        val hashes = HashSet<Long>()
        for (key in 0L until 10_000L) {
            hashes.add(fmix64(key))
        }
        hashes.size shouldBe 10_000
    }

    @Test
    fun `multiplies by the golden ratio in the Fibonacci hasher`() {
        LongHasher.Fibonacci.hash(0L) shouldBe 0L
        LongHasher.Fibonacci.hash(1L) shouldBe -7046029254386353131L
    }

    @Test
    fun `spreads sequential keys distinctly through the Fibonacci hasher`() {
        val hashes = HashSet<Long>()
        for (key in 0L until 10_000L) {
            hashes.add(LongHasher.Fibonacci.hash(key))
        }
        hashes.size shouldBe 10_000
    }
}
