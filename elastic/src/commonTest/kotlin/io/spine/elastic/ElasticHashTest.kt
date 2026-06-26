package io.spine.elastic

import kotlin.test.Test
import kotlin.test.assertNotNull

class ElasticHashTest {

    @Test
    fun `creates elastic hash instance`() {
        assertNotNull(ElasticHash())
    }
}
