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
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

/**
 * Differential tests of [LongLongMap] against a `HashMap<Long, Long>` oracle over
 * randomized operation sequences. The map must agree with the model on every return
 * value (mapping the model's `null` to [LongLongMap.absentValue]), on membership,
 * and on size, after every operation — including with a non-zero absent value, so
 * the absent-key protocol is exercised.
 */
internal class LongLongMapPropertiesSpec {

    private sealed interface Op {
        data class Put(val key: Long, val value: Long) : Op
        data class Remove(val key: Long) : Op
        data class Get(val key: Long) : Op
    }

    private fun operations(keys: Arb<Long>): Arb<Op> = Arb.choice(
        Arb.bind(keys, Arb.long()) { key, value -> Op.Put(key, value) },
        keys.map { Op.Remove(it) },
        keys.map { Op.Get(it) },
    )

    @Test
    fun `agrees with the model on a small clustered key domain`() = runTest {
        val absent = -7L
        val domain = 0L..63L
        checkAll(200, Arb.list(operations(Arb.long(domain)), 0..400)) { ops ->
            val map = LongLongMap(absentValue = absent)
            val model = HashMap<Long, Long>()
            for (op in ops) {
                applyAndCompare(op, map, model, absent)
            }
            map.size shouldBe model.size
            for (key in domain) {
                map.containsKey(key) shouldBe model.containsKey(key)
                map[key] shouldBe (model[key] ?: absent)
            }
        }
    }

    @Test
    fun `agrees with the model on a wide key domain`() = runTest {
        val absent = 0L
        checkAll(120, Arb.list(operations(Arb.long()), 0..600)) { ops ->
            val map = LongLongMap(absentValue = absent)
            val model = HashMap<Long, Long>()
            for (op in ops) {
                applyAndCompare(op, map, model, absent)
            }
            map.size shouldBe model.size
            for ((key, value) in model) {
                map.containsKey(key) shouldBe true
                map[key] shouldBe value
            }
        }
    }

    private fun applyAndCompare(
        op: Op,
        map: LongLongMap,
        model: MutableMap<Long, Long>,
        absent: Long,
    ) {
        when (op) {
            is Op.Put -> map.put(op.key, op.value) shouldBe (model.put(op.key, op.value) ?: absent)
            is Op.Remove -> map.remove(op.key) shouldBe (model.remove(op.key) ?: absent)
            is Op.Get -> map[op.key] shouldBe (model[op.key] ?: absent)
        }
        map.size shouldBe model.size
    }
}
