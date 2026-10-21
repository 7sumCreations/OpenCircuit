package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.BaselineTrend.Direction
import io.github.opencircuit.ringkit.BaselineTrend.Point
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Synthetic-only tests for the Today tiles' baseline comparison: the latest daily value vs the mean
 * of the prior days, with a deadband, and an honest null direction while the baseline is thin.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/BaselineTrendTests.swift
 * (@ 2d07e550, the last MIT commit to touch it): all 7 tests. The `// :n` lines cite each test.
 */
class BaselineTrendTest {

    private val day0: Instant = Instant.ofEpochSecond(1_780_000_000)

    private fun series(values: List<Double>): List<Point> =
        values.mapIndexed { i, v -> Point(date = day0.plusSeconds(i * 86_400L), value = v) }

    // :14
    @Test
    fun emptySeriesIsNull() {
        assertNull(BaselineTrend.evaluate(emptyList(), minAbsoluteDelta = 1.0))
    }

    // :18
    @Test
    fun singlePointHasNoBaseline() {
        val r = BaselineTrend.evaluate(series(listOf(50.0)), minAbsoluteDelta = 1.0)
        assertEquals(50.0, r?.latest?.value)
        assertNull(r?.baselineMean)
        assertEquals(0, r?.baselineDays)
        assertNull(r?.direction)
        assertNull(r?.delta)
    }

    // :27 — 3 prior days < default 4: the mean is shown, the direction is withheld.
    @Test
    fun thinBaselineReportsMeanButNoDirection() {
        val r = BaselineTrend.evaluate(series(listOf(40.0, 42.0, 44.0, 80.0)), minAbsoluteDelta = 1.0)
        assertEquals(3, r?.baselineDays)
        assertEquals(42.0, r?.baselineMean ?: 0.0, 1e-9)
        assertNull(r?.direction)
    }

    // :35
    @Test
    fun aboveBelowWithin() {
        val flat = listOf(50.0, 50.0, 50.0, 50.0, 50.0)
        assertEquals(Direction.ABOVE, BaselineTrend.evaluate(series(flat + 55.0), minAbsoluteDelta = 3.0)?.direction)
        assertEquals(Direction.BELOW, BaselineTrend.evaluate(series(flat + 45.0), minAbsoluteDelta = 3.0)?.direction)
        assertEquals(Direction.WITHIN, BaselineTrend.evaluate(series(flat + 52.0), minAbsoluteDelta = 3.0)?.direction)
        // Exactly on the band edge is still within.
        assertEquals(Direction.WITHIN, BaselineTrend.evaluate(series(flat + 53.0), minAbsoluteDelta = 3.0)?.direction)
    }

    // :44
    @Test
    fun usualRangeIsTheDeadband() {
        val r = BaselineTrend.evaluate(series(listOf(50.0, 50.0, 50.0, 50.0, 52.0)), minAbsoluteDelta = 3.0)
        assertEquals(3.0, r?.bandHalfWidth)
        assertEquals(47.0..53.0, r?.usualRange)
        assertTrue(r?.usualRange?.contains(52.0) == true)
        assertEquals(Direction.WITHIN, r?.direction)
        // Thin baseline: no band claimed.
        assertNull(BaselineTrend.evaluate(series(listOf(50.0, 50.0, 52.0)), minAbsoluteDelta = 3.0)?.usualRange)
    }

    // :54 — mean 50, population SD 10 → band = max(1, 5) = 5. A +4 move is within; +6 is above.
    @Test
    fun noisyBaselineWidensTheBand() {
        val noisy = listOf(40.0, 60.0, 40.0, 60.0)
        assertEquals(Direction.WITHIN, BaselineTrend.evaluate(series(noisy + 54.0), minAbsoluteDelta = 1.0)?.direction)
        assertEquals(Direction.ABOVE, BaselineTrend.evaluate(series(noisy + 56.0), minAbsoluteDelta = 1.0)?.direction)
        assertEquals(5.0, BaselineTrend.evaluate(series(noisy + 56.0), minAbsoluteDelta = 1.0)?.bandHalfWidth ?: 0.0, 1e-9)
    }

    // :62
    @Test
    fun orderIndependentAndNonFiniteDropped() {
        val pts = series(listOf(50.0, 50.0, 50.0, 50.0, 60.0)).reversed() + Point(date = day0.plusSeconds(10 * 86_400L), value = Double.NaN)
        val r = BaselineTrend.evaluate(pts, minAbsoluteDelta = 2.0)
        assertEquals(60.0, r?.latest?.value)
        assertEquals(Direction.ABOVE, r?.direction)
        assertEquals(10.0, r?.delta ?: 0.0, 1e-9)
    }
}
