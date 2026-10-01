package io.github.opencircuit.ringkit

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The overnight stress mapping: the RMSSD→score curve is monotonic decreasing, the app's band
 * thresholds, the median-based overnight score and the per-band state durations.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepStressTests.swift (@ b1c2fdd)
 * — all 6 tests. State durations are `Duration`s (upstream `TimeInterval`).
 */
class SleepStressTest {

    @Test
    fun bandThresholds() { // :9-18
        assertEquals(SleepStress.Band.RELAXED, SleepStress.Band.of(1))
        assertEquals(SleepStress.Band.RELAXED, SleepStress.Band.of(29))
        assertEquals(SleepStress.Band.NORMAL, SleepStress.Band.of(30))
        assertEquals(SleepStress.Band.NORMAL, SleepStress.Band.of(59))
        assertEquals(SleepStress.Band.MEDIUM, SleepStress.Band.of(60))
        assertEquals(SleepStress.Band.MEDIUM, SleepStress.Band.of(79))
        assertEquals(SleepStress.Band.HIGH, SleepStress.Band.of(80))
        assertEquals(SleepStress.Band.HIGH, SleepStress.Band.of(100))
    }

    @Test
    fun scoreIsMonotonicDecreasingInRMSSD() { // :20-27
        val lowHRV = SleepStress.score(15.0)
        val midHRV = SleepStress.score(40.0)
        val highHRV = SleepStress.score(70.0)
        assertTrue(lowHRV > midHRV, "$lowHRV vs $midHRV")
        assertTrue(midHRV > highHRV, "$midHRV vs $highHRV")
    }

    @Test
    fun scoreClampedToReferenceWindow() { // :29-37
        val veryHigh = SleepStress.score(200.0)
        val veryLow = SleepStress.score(2.0)
        assertEquals(SleepStress.LOW_SCORE.toInt(), veryHigh)
        assertEquals(SleepStress.HIGH_SCORE.toInt(), veryLow)
        assertTrue(veryHigh >= 0)
        assertTrue(veryLow <= 100)
    }

    @Test
    fun restedBoundIsRelaxedAndStressedBoundIsHigh() { // :39-42
        assertEquals(SleepStress.Band.RELAXED, SleepStress.Band.of(SleepStress.score(SleepStress.RESTED_RMSSD)))
        assertEquals(SleepStress.Band.HIGH, SleepStress.Band.of(SleepStress.score(SleepStress.STRESSED_RMSSD)))
    }

    @Test
    fun overnightScoreUsesMedian() { // :44-50
        // Median of [10,40,40,40,200] is 40; the lone outliers don't drag it.
        val s = SleepStress.overnightScore(rmssd = listOf(10, 40, 40, 40, 200))
        assertEquals(SleepStress.score(40.0), s)
        assertNull(SleepStress.overnightScore(rmssd = emptyList()))
        assertNull(SleepStress.overnightScore(rmssd = listOf(0, 0)), "non-positive RMSSD dropped")
    }

    @Test
    fun stateDurations() { // :52-58
        // Two relaxed (high HRV) + one high-stress (low HRV) epoch.
        val durations = SleepStress.stateDurations(rmssd = listOf(70, 70, 15), epochSeconds = 150)
        assertEquals(Duration.ofSeconds(300), durations[SleepStress.Band.RELAXED] ?: Duration.ZERO)
        assertEquals(Duration.ofSeconds(150), durations[SleepStress.Band.HIGH] ?: Duration.ZERO)
        assertNull(durations[SleepStress.Band.MEDIUM])
    }
}
