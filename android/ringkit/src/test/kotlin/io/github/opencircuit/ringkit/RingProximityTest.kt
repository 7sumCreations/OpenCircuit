package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.RingProximity.Band
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Checks the RSSI → proximity mapping that drives Find My Ring — port of upstream
 * ios/OpenCircuitKit/Tests/OpenCircuitKitTests/RingProximityTests.swift (@ b1c2fdd), all 5 tests.
 */
class RingProximityTest {

    @Test
    fun bandBucketsAreContiguousAndOrdered() { // RingProximityTests.swift:7-17
        assertEquals(Band.VERY_CLOSE, RingProximity.band(-40))
        assertEquals(Band.VERY_CLOSE, RingProximity.band(-55))
        assertEquals(Band.CLOSE, RingProximity.band(-56))
        assertEquals(Band.CLOSE, RingProximity.band(-68))
        assertEquals(Band.NEARBY, RingProximity.band(-69))
        assertEquals(Band.NEARBY, RingProximity.band(-80))
        assertEquals(Band.FAR, RingProximity.band(-81))
        assertEquals(Band.FAR, RingProximity.band(-95))
        assertEquals(Band.SEARCHING, RingProximity.band(-96))
    }

    @Test
    fun nilAndBogusRSSIReadAsSearching() { // RingProximityTests.swift:19-23
        assertEquals(Band.SEARCHING, RingProximity.band(null))
        // CoreBluetooth's "not available" sentinel is 127 — must not read as very close.
        assertEquals(Band.SEARCHING, RingProximity.band(127))
    }

    @Test
    fun distanceMonotonicallyGrowsAsSignalWeakens() { // RingProximityTests.swift:25-34
        val near = assertNotNull(RingProximity.approximateMeters(-55))
        val mid = assertNotNull(RingProximity.approximateMeters(-70))
        val far = assertNotNull(RingProximity.approximateMeters(-85))
        assertTrue(near < mid)
        assertTrue(mid < far)
        // At the 1 m reference power the model should return ≈ 1 m.
        val atRef = assertNotNull(RingProximity.approximateMeters(-59))
        assertEquals(1.0, atRef, absoluteTolerance = 0.05)
    }

    @Test
    fun distanceTextBucketsEnds() { // RingProximityTests.swift:36-44
        assertNull(RingProximity.distanceText(null))
        assertNull(RingProximity.distanceText(127)) // no signal → no text
        assertEquals("Right here", RingProximity.distanceText(-40))
        assertEquals("≈ 20+ ft", RingProximity.distanceText(-95))
        // A mid value produces a concrete "≈ N ft".
        val mid = RingProximity.distanceText(-68)
        assertTrue(mid != null && mid.startsWith("≈ ") && mid.endsWith(" ft"), "got ${mid ?: "nil"}")
    }

    @Test
    fun signalFractionClampsToUnitRange() { // RingProximityTests.swift:46-51
        assertEquals(0.0, RingProximity.signalFraction(null))
        assertEquals(1.0, RingProximity.signalFraction(-40), absoluteTolerance = 0.001) // clamps at -45
        assertEquals(0.0, RingProximity.signalFraction(-95), absoluteTolerance = 0.001)
        assertEquals(0.5, RingProximity.signalFraction(-70), absoluteTolerance = 0.001)
    }
}
