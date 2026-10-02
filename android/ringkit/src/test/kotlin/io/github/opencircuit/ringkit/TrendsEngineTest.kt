package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.TrendsEngine.DailyPoint
import io.github.opencircuit.ringkit.TrendsEngine.Trend
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/TrendsEngineTests.swift (@ b1c2fdd),
 * all 14 tests. Expected values are typed from upstream's test file, never from the Kotlin constants.
 *
 * Upstream's `day(_:)` adds days to the Unix epoch in a Gregorian calendar in the machine's time
 * zone. Here the zone is named (New York). The dates are labels only — the engine takes the trailing
 * points by position and never reads a date (`TrendsHazardTest.daysAreTakenByPositionNotByDate`) —
 * so no zone could change a result; naming one keeps the test off the machine's zone.
 */
class TrendsEngineTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")

    private fun day(offset: Int): Instant = Instant.EPOCH.atZone(zone).plusDays(offset.toLong()).toInstant()

    private fun assertClose(expected: Double, actual: Double, accuracy: Double) =
        assertTrue(abs(expected - actual) <= accuracy, "expected $expected ± $accuracy, was $actual")

    // MARK: Rolling averages — basic

    @Test
    fun rollingAveragesSteps() { // TrendsEngineTests.swift:15
        val points = (0 until 7).map { i -> DailyPoint(date = day(i), steps = (i + 1) * 1000) }
        val avgs = TrendsEngine.rollingAverages(points, window = 7)
        // steps: 1000+2000+...+7000 = 28000 / 7 = 4000
        assertClose(4000.0, avgs.steps ?: 0.0, 0.01)
    }

    @Test
    fun rollingAveragesSleepScore() { // TrendsEngineTests.swift:24
        val scores = listOf(80, 85, 90, 75, 0, 88, 82) // 0 = not computed → excluded
        val points = scores.mapIndexed { i, s -> DailyPoint(date = day(i), sleepScore = s) }
        val avgs = TrendsEngine.rollingAverages(points, window = 7)
        // Excluding 0: (80+85+90+75+88+82)/6 = 500/6 ≈ 83.33
        assertClose(500.0 / 6.0, avgs.sleepScore ?: 0.0, 0.01)
    }

    @Test
    fun rollingAveragesHRGuard() { // TrendsEngineTests.swift:34
        // Values ≤ 29 bpm are excluded (the APK SQL guard)
        val hrs: List<Double?> = listOf(0.0, 29.0, 65.0, 72.0, null, 68.0, 70.0)
        val points = hrs.mapIndexed { i, hr -> DailyPoint(date = day(i), sleepHRAvg = hr) }
        val avgs = TrendsEngine.rollingAverages(points, window = 7)
        // Valid: 65, 72, 68, 70 → mean = 275/4 = 68.75
        assertClose(68.75, avgs.sleepHRAvg ?: 0.0, 0.01)
    }

    @Test
    fun rollingAveragesAllNilReturnsNil() { // TrendsEngineTests.swift:45
        val points = (0 until 7).map { i -> DailyPoint(date = day(i)) }
        val avgs = TrendsEngine.rollingAverages(points, window = 7)
        assertNull(avgs.steps)
        assertNull(avgs.sleepHRAvg)
    }

    @Test
    fun rollingWindowLimitedByAvailablePoints() { // TrendsEngineTests.swift:52
        // Only 3 points available for a 7-day window → averages just those 3
        val points = listOf(1000, 2000, 3000).mapIndexed { i, s -> DailyPoint(date = day(i), steps = s) }
        val avgs = TrendsEngine.rollingAverages(points, window = 7)
        assertClose(2000.0, avgs.steps ?: 0.0, 0.01)
    }

    // MARK: Trend direction

    @Test
    fun trendUp() { // TrendsEngineTests.swift:63
        // Recent 7 days much higher than prior 7 days
        val points = (0 until 7).map { i -> DailyPoint(date = day(i), steps = 3000) } +
            (7 until 14).map { i -> DailyPoint(date = day(i), steps = 7000) }
        val t = TrendsEngine.trend(points, window = 7) { it.steps?.toDouble() }
        assertEquals(Trend.UP, t)
    }

    @Test
    fun trendDown() { // TrendsEngineTests.swift:71
        val points = (0 until 7).map { i -> DailyPoint(date = day(i), steps = 8000) } +
            (7 until 14).map { i -> DailyPoint(date = day(i), steps = 3000) }
        val t = TrendsEngine.trend(points, window = 7) { it.steps?.toDouble() }
        assertEquals(Trend.DOWN, t)
    }

    @Test
    fun trendFlat() { // TrendsEngineTests.swift:78
        // 14 days all equal → flat
        val points = (0 until 14).map { i -> DailyPoint(date = day(i), steps = 8000) }
        val t = TrendsEngine.trend(points, window = 7) { it.steps?.toDouble() }
        assertEquals(Trend.FLAT, t)
    }

    @Test
    fun trendNilWithInsufficientData() { // TrendsEngineTests.swift:85
        val points = listOf(DailyPoint(date = day(0), steps = 5000))
        val t = TrendsEngine.trend(points, window = 7) { it.steps?.toDouble() }
        assertNull(t)
    }

    // MARK: Sleep regularity

    @Test
    fun sleepRegularityPerfect() { // TrendsEngineTests.swift:93
        // Same bedtime every night → 0 variance → score = 100
        val same = List(7) { 22 * 60 } // 22:00 every night
        assertEquals(100, TrendsEngine.sleepRegularity(bedtimeMinutes = same))
    }

    @Test
    fun sleepRegularityHighVariance() { // TrendsEngineTests.swift:99
        // Very different bedtimes → low score
        val varied = listOf(22 * 60, 0 * 60, 23 * 60, 1 * 60, 22 * 60, 2 * 60, 23 * 60)
        val score = TrendsEngine.sleepRegularity(bedtimeMinutes = varied) ?: 100
        assertTrue(score < 60, "score $score")
    }

    @Test
    fun sleepRegularityInsufficientData() { // TrendsEngineTests.swift:106
        assertNull(TrendsEngine.sleepRegularity(bedtimeMinutes = listOf(22 * 60)))
    }

    @Test
    fun sleepRegularityTightClusterAroundMidnight() { // TrendsEngineTests.swift:110
        // A very consistent near-midnight sleeper: [23:58, 00:02, 23:55, 00:05, 00:00, 23:57, 00:03]
        // → true spread ~10 min. Linear variance would treat these as ~1440 min apart and score 0;
        // circular statistics must score this near-perfect (≥ 90).
        val minutes = listOf(1438, 2, 1435, 5, 0, 1437, 3)
        val score = TrendsEngine.sleepRegularity(bedtimeMinutes = minutes) ?: 0
        assertTrue(score >= 90, "tight midnight-wrap cluster should be highly regular (score $score)")
    }

    @Test
    fun sleepRegularityWrapEquivalentToShifted() { // TrendsEngineTests.swift:119
        // Same spread, once straddling midnight and once shifted to noon → identical score
        // (rotation-invariance is the whole point of circular statistics).
        val aroundMidnight = listOf(1438, 2, 1435, 5, 0, 1437, 3)
        val aroundNoon = aroundMidnight.map { (it + 720) % 1440 }
        assertEquals(
            TrendsEngine.sleepRegularity(bedtimeMinutes = aroundMidnight),
            TrendsEngine.sleepRegularity(bedtimeMinutes = aroundNoon),
        )
    }
}
