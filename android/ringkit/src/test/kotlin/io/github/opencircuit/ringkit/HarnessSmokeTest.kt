package io.github.opencircuit.ringkit

import kotlin.test.Test
import kotlin.test.assertEquals

class HarnessSmokeTest {
    @Test
    fun upstreamShaIsPinned() {
        assertEquals(40, RingKit.UPSTREAM_SHA.length)
    }
}
