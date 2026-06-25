package io.spine.elastic.benchmarks

import kotlin.test.Test
import kotlin.test.assertNotNull

class BenchmarkSuiteTest {

    @Test
    fun `creates benchmark suite instance`() {
        assertNotNull(BenchmarkSuite())
    }
}
