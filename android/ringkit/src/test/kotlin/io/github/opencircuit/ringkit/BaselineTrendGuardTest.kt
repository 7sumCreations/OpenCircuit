package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.BaselineTrend.Direction
import io.github.opencircuit.ringkit.BaselineTrend.Point
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Guards for what the Kotlin baseline-trend port adds or could lose relative to Swift: points on the
 * same date keep the order they were given (Swift's sort gives them none, Kotlin's `sortedBy` is
 * stable); the prior-day boundaries 0 / 3 / 4; a zero or negative minimum still needs one prior day;
 * the defaults typed as literals; non-finite values never reach the mean; values compare their
 * doubles by IEEE `==`; and the caller's list is never reordered.
 */
class BaselineTrendGuardTest {

    private val day0: Instant = Instant.ofEpochSecond(1_780_000_000)
    private fun day(i: Int): Instant = day0.plusSeconds(i * 86_400L)
    private fun series(vararg values: Double): List<Point> = values.mapIndexed { i, v -> Point(day(i), v) }

    @Test
    fun twoPointsOnTheSameDateTheOneListedLastIsTheLatest() {
        val flat = series(50.0, 50.0, 50.0, 50.0)
        val r = assertNotNull(BaselineTrend.evaluate(flat + Point(day(4), 70.0) + Point(day(4), 40.0), minAbsoluteDelta = 3.0))
        assertEquals(40.0, r.latest.value, "the later-listed point of a tied date is the latest")
        assertEquals(5, r.baselineDays, "the other tied point joins the baseline")
        assertEquals((50.0 * 4 + 70.0) / 5, r.baselineMean)
    }

    @Test
    fun noPriorDayHasNoMeanThreeHaveAMeanOnlyFourHaveADirection() {
        val zero = assertNotNull(BaselineTrend.evaluate(series(60.0), minAbsoluteDelta = 1.0))
        assertEquals(0, zero.baselineDays)
        assertNull(zero.baselineMean)
        val three = assertNotNull(BaselineTrend.evaluate(series(50.0, 50.0, 50.0, 60.0), minAbsoluteDelta = 1.0))
        assertEquals(50.0, three.baselineMean)
        assertNull(three.direction)
        assertNull(three.bandHalfWidth)
        val four = assertNotNull(BaselineTrend.evaluate(series(50.0, 50.0, 50.0, 50.0, 60.0), minAbsoluteDelta = 1.0))
        assertEquals(Direction.ABOVE, four.direction)
        assertEquals(4, BaselineTrend.DEFAULT_MIN_BASELINE_DAYS)
    }

    @Test
    fun aZeroOrNegativeMinimumStillNeedsOnePriorDay() {
        assertNull(BaselineTrend.evaluate(series(60.0), minAbsoluteDelta = 1.0, minBaselineDays = 0)?.direction)
        assertEquals(Direction.ABOVE, BaselineTrend.evaluate(series(50.0, 60.0), minAbsoluteDelta = 1.0, minBaselineDays = 0)?.direction)
        assertEquals(Direction.ABOVE, BaselineTrend.evaluate(series(50.0, 60.0), minAbsoluteDelta = 1.0, minBaselineDays = -3)?.direction)
    }

    @Test
    fun theDefaultBandIsHalfThePopulationSdAndANegativeFloorCountsByItsSize() {
        // Prior 40, 60, 40, 60: mean 50, population SD 10 → half is 5.
        assertEquals(5.0, BaselineTrend.evaluate(series(40.0, 60.0, 40.0, 60.0, 50.0), minAbsoluteDelta = 0.0)?.bandHalfWidth)
        assertEquals(3.0, BaselineTrend.evaluate(series(50.0, 50.0, 50.0, 50.0, 52.0), minAbsoluteDelta = -3.0)?.bandHalfWidth)
    }

    @Test
    fun nonFiniteValuesNeverReachTheMeanAndAnAllNonFiniteSeriesIsNull() {
        val r = assertNotNull(
            BaselineTrend.evaluate(
                series(50.0, Double.POSITIVE_INFINITY, 50.0, Double.NEGATIVE_INFINITY, 50.0, 50.0, Double.NaN, 51.0),
                minAbsoluteDelta = 2.0,
            ),
        )
        assertEquals(4, r.baselineDays)
        assertEquals(50.0, r.baselineMean)
        assertEquals(51.0, r.latest.value)
        assertNull(BaselineTrend.evaluate(series(Double.NaN, Double.POSITIVE_INFINITY), minAbsoluteDelta = 1.0))
    }

    @Test
    fun valuesCompareTheirDoublesByIeeeEquality() {
        assertEquals(Point(day0, 0.0), Point(day0, -0.0))
        assertEquals(Point(day0, 0.0).hashCode(), Point(day0, -0.0).hashCode())
        val a = BaselineTrend.Result(Point(day0, 1.0), baselineMean = 0.0, baselineDays = 1, direction = Direction.ABOVE, bandHalfWidth = 0.5)
        val b = BaselineTrend.Result(Point(day0, 1.0), baselineMean = -0.0, baselineDays = 1, direction = Direction.ABOVE, bandHalfWidth = 0.5)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun theCallersListIsNeverReordered() {
        val given = series(50.0, 50.0, 50.0, 50.0, 60.0).reversed().toMutableList()
        val before = given.toList()
        BaselineTrend.evaluate(given, minAbsoluteDelta = 1.0)
        assertEquals(before, given)
    }
}
