package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The single source of truth for a night's mean vital: both the Sleep card and the Vitals
 * dashboard resolve a night's HRV to the same overnight MEAN, never the single newest epoch.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/OvernightAveragesTests.swift
 * (@ b1c2fdd) — all 4 tests.
 */
class OvernightAveragesTest {

    private val t0: Instant = Instant.ofEpochSecond(0)

    private fun point(value: Double, offsetSeconds: Long) = OvernightAverages.Point(value, t0.plusSeconds(offsetSeconds))

    @Test
    fun meanIsOvernightMeanNotLastEpoch() { // :10-23
        val win = DateInterval(t0, t0.plus(Duration.ofHours(8)))
        val points = listOf(
            point(60.0, 600),
            point(62.0, 3600),
            point(48.0, 5 * 3600L),
            OvernightAverages.Point(86.0, win.end.minusSeconds(300)), // last epoch
        )
        val mean = assertNotNull(OvernightAverages.mean(points, window = win))
        assertEquals(64, mean.roundToInt(), "(60+62+48+86)/4 = 64 — the canonical overnight mean")
        val lastEpoch = points.maxBy { it.start }.value
        assertEquals(86, lastEpoch.toInt(), "what the Vitals row used to show")
        assertNotEquals(mean.roundToInt(), lastEpoch.toInt(), "the regression class this fix closes")
    }

    @Test
    fun meanExcludesOutOfWindow() { // :25-32
        val win = DateInterval(t0, t0.plusSeconds(3600))
        val points = listOf(point(50.0, 60), point(70.0, 7200)) // the second is outside the window
        assertEquals(50.0, OvernightAverages.mean(points, window = win))
    }

    @Test
    fun meanInclusiveOfWindowEndpoints() { // :34-41
        val win = DateInterval(t0, t0.plusSeconds(3600))
        val points = listOf(point(40.0, 0), point(80.0, 3600)) // == start, == end
        assertEquals(60.0, OvernightAverages.mean(points, window = win))
    }

    @Test
    fun meanNilWhenEmptyOrNoneInWindow() { // :43-48
        val win = DateInterval(t0, t0.plusSeconds(3600))
        assertNull(OvernightAverages.mean(emptyList(), window = win))
        assertNull(OvernightAverages.mean(listOf(point(99.0, 99_999)), window = win))
    }
}
