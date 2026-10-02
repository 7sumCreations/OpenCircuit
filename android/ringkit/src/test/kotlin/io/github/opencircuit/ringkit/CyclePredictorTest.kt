package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.CyclePredictor.PeriodEntry
import io.github.opencircuit.ringkit.CyclePredictor.SkinTempNight
import io.github.opencircuit.ringkit.FoundationDate.referenceSeconds
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/CyclePredictorTests.swift (@ b1c2fdd),
 * 27 of 27, each test named after upstream's with its line. SYNTHETIC-ONLY: no real health values.
 *
 * ZONE AND CLOCK. Upstream builds every date from the machine's calendar and clock (`Calendar.current`,
 * `Date()`), and 26 of its 27 tests read them. Here both are fixed and named: the zone [cal] is
 * Pacific/Auckland and the clock [clock] is 2026-08-05 08:00 there (2026-08-04 20:00 UTC). The zone is
 * twelve hours from UTC and the hour is one where its date and UTC's differ, so a helper that read UTC
 * days would see a different date than the one the test builds; and the tests' whole span (118 days back
 * to 28 days ahead) lies between Auckland's 2026 clock changes (5 April, 27 September), as it must: a
 * 28-day interval across a change is 27.96 or 28.04 days, outside upstream's own 0.01-day tolerances,
 * so upstream fails on a machine whose zone changes clock inside that span. Clock changes themselves are
 * covered by `CyclePredictorClockChangeTest`. Every function upstream defaulted to `.current` takes the
 * zone; `predict` takes the clock where upstream defaulted to `Date()`.
 */
class CyclePredictorTest {

    // MARK: Helpers

    private val cal: ZoneId = ZoneId.of("Pacific/Auckland")

    /** Upstream's `Date()`, fixed: 2026-08-05 08:00 in [cal]. */
    private val clock: Instant = Instant.parse("2026-08-04T20:00:00Z")

    private fun startOfDay(t: Instant): Instant = t.atZone(cal).toLocalDate().atStartOfDay(cal).toInstant()

    private fun daysAgo(n: Int): Instant = startOfDay(clock).atZone(cal).plusDays(-n.toLong()).toInstant()

    private fun daysFromNow(n: Int): Instant = startOfDay(clock).atZone(cal).plusDays(n.toLong()).toInstant()

    private fun period(startDaysAgo: Int, endDaysAgo: Int? = null): PeriodEntry =
        PeriodEntry(start = daysAgo(startDaysAgo), end = endDaysAgo?.let { daysAgo(it) })

    /** `b.timeIntervalSince(a)`, in days. */
    private fun daysBetween(a: Instant, b: Instant): Double = Duration.between(a, b).toNanos() / 1e9 / 86_400

    // MARK: cycleStats — minimum history guard

    @Test
    fun testCycleStatsNilWhenEmpty() { // :32
        assertNull(CyclePredictor.cycleStats(emptyList()))
    }

    @Test
    fun testCycleStatsNilWhenOnePeriod() { // :36
        assertNull(CyclePredictor.cycleStats(listOf(period(startDaysAgo = 30))))
    }

    // MARK: cycleStats — interval math

    @Test
    fun testCycleStats_SingleInterval_28Days() { // :42
        val periods = listOf(period(startDaysAgo = 28), period(startDaysAgo = 0))
        val stats = assertNotNull(CyclePredictor.cycleStats(periods))
        assertEquals(28.0, stats.avgCycleLengthDays, 0.01)
        assertEquals(1, stats.sampleCount)
    }

    @Test
    fun testCycleStats_MultipleIntervals_Average() { // :49
        // 56 days ago, 28 days ago, today → two 28-day cycles
        val periods = listOf(period(startDaysAgo = 56), period(startDaysAgo = 28), period(startDaysAgo = 0))
        val stats = CyclePredictor.cycleStats(periods)!!
        assertEquals(28.0, stats.avgCycleLengthDays, 0.1)
        assertEquals(2, stats.sampleCount)
    }

    @Test
    fun testCycleStats_UnequalIntervals_TrueAverage() { // :61
        // 56 + 28 → intervals: 28, 28; but vary: 62 + 28 → intervals 34, 28 → avg 31
        val periods = listOf(period(startDaysAgo = 62), period(startDaysAgo = 28), period(startDaysAgo = 0))
        val stats = CyclePredictor.cycleStats(periods)!!
        assertEquals(31.0, stats.avgCycleLengthDays, 0.1)
    }

    // MARK: cycleStats — out-of-range interval exclusion

    @Test
    fun testCycleStats_TooShortInterval_Excluded() { // :74
        // 15 days → below minCycleLengthDays (21) → no valid interval → nil
        val periods = listOf(period(startDaysAgo = 15), period(startDaysAgo = 0))
        assertNull(CyclePredictor.cycleStats(periods))
    }

    @Test
    fun testCycleStats_TooLongInterval_Excluded() { // :80
        // 50 days → above maxCycleLengthDays (45) → excluded → nil
        val periods = listOf(period(startDaysAgo = 50), period(startDaysAgo = 0))
        assertNull(CyclePredictor.cycleStats(periods))
    }

    @Test
    fun testCycleStats_MixedValidity_OnlyValidIncluded() { // :86
        // Three periods: interval 1 = 15 days (invalid), interval 2 = 28 days (valid)
        val periods = listOf(
            period(startDaysAgo = 43),
            period(startDaysAgo = 28), // 15 days after prev — invalid
            period(startDaysAgo = 0), // 28 days after prev — valid
        )
        val stats = CyclePredictor.cycleStats(periods)!!
        assertEquals(28.0, stats.avgCycleLengthDays, 0.1)
        assertEquals(1, stats.sampleCount)
    }

    // MARK: cycleStats — period duration

    @Test
    fun testCycleStats_AvgDurationFromCompletedPeriods() { // :100
        // One completed period (5 days), one ongoing
        val p1 = PeriodEntry(start = daysAgo(30), end = daysAgo(25))
        val p2 = PeriodEntry(start = daysAgo(0))
        val stats = assertNotNull(CyclePredictor.cycleStats(listOf(p1, p2)))
        val avgDuration = assertNotNull(stats.avgPeriodDurationDays)
        assertEquals(5.0, avgDuration, 0.1)
    }

    @Test
    fun testCycleStats_NilDurationWhenNoneCompleted() { // :109
        val p1 = PeriodEntry(start = daysAgo(28))
        val p2 = PeriodEntry(start = daysAgo(0))
        val stats = CyclePredictor.cycleStats(listOf(p1, p2))!!
        assertNull(stats.avgPeriodDurationDays)
    }

    // MARK: predict — nil guard

    @Test
    fun testPredictNilBelowMinimum() { // :118
        assertNull(CyclePredictor.predict(listOf(period(startDaysAgo = 30)), now = clock))
        assertNull(CyclePredictor.predict(emptyList(), now = clock))
    }

    // MARK: predict — date math

    @Test
    fun testPredict_28DayCycle_NextPeriodDate() { // :125
        val p1 = PeriodEntry(start = daysAgo(28))
        val p2 = PeriodEntry(start = daysAgo(0))
        val pred = CyclePredictor.predict(listOf(p1, p2), now = clock)!!

        // Next period ≈ 28 days from now (from start-of-today)
        val expected = daysFromNow(28)
        assertEquals(expected, startOfDay(pred.nextPeriodStart))
        assertEquals(28.0, pred.avgCycleLengthDays, 0.01)
    }

    @Test
    fun testPredict_OvulationIs14DaysBeforeNextPeriod() { // :136
        val p1 = PeriodEntry(start = daysAgo(28))
        val p2 = PeriodEntry(start = daysAgo(0))
        val pred = CyclePredictor.predict(listOf(p1, p2), now = clock)!!

        val expectedOvulation = startOfDay(pred.nextPeriodStart).minusSeconds(CyclePredictor.LUTEAL_PHASE_DAYS * 86_400L)
        assertEquals(expectedOvulation, startOfDay(pred.ovulationEstimate))
        assertEquals(expectedOvulation, startOfDay(pred.fertileWindowEnd), "fertileWindowEnd == ovulation day")
    }

    @Test
    fun testPredict_FertileWindowStartIs5DaysBeforeOvulation() { // :148
        val p1 = PeriodEntry(start = daysAgo(28))
        val p2 = PeriodEntry(start = daysAgo(0))
        val pred = CyclePredictor.predict(listOf(p1, p2), now = clock)!!

        val expectedStart = startOfDay(pred.ovulationEstimate).minusSeconds(CyclePredictor.FERTILE_WINDOW_DAYS_BEFORE_OVULATION * 86_400L)
        assertEquals(expectedStart, startOfDay(pred.fertileWindowStart))
    }

    @Test
    fun testPredict_DefaultPeriodDuration_5Days() { // :158
        // No completed periods → default 5-day duration
        val p1 = PeriodEntry(start = daysAgo(28))
        val p2 = PeriodEntry(start = daysAgo(0))
        val pred = CyclePredictor.predict(listOf(p1, p2), now = clock)!!

        val durDays = daysBetween(pred.nextPeriodStart, pred.nextPeriodEnd)
        assertEquals(5.0, durDays, 0.01)
    }

    @Test
    fun testPredict_RollsForwardWhenLoggingWentStale() { // :168
        // User logged two cycles long ago then stopped (last period 90 days ago, 28-day cycle).
        // The naive next-period (last + 28d) is in the PAST; predict() must roll it forward so
        // the "next" period and its ovulation/fertile window are all in the FUTURE.
        val now = startOfDay(clock)
        val p1 = PeriodEntry(start = daysAgo(118))
        val p2 = PeriodEntry(start = daysAgo(90))
        val pred = CyclePredictor.predict(listOf(p1, p2), now = now)!!

        assertTrue(pred.nextPeriodStart > now, "next period must be in the future")
        assertTrue(pred.ovulationEstimate > now, "ovulation must be in the future")
        assertTrue(pred.fertileWindowStart > now, "fertile window must be in the future")
        // It must be the FIRST future occurrence: rolling back one whole cycle lands at/before now.
        val oneCycle = pred.avgCycleLengthDays * 86_400
        assertTrue(referenceSeconds(pred.nextPeriodStart) - oneCycle < referenceSeconds(now), "must not over-roll past the first future cycle")
    }

    @Test
    fun testPredict_DoesNotRollForwardWhenAlreadyFuture() { // :186
        // Last period today → naive next (today + 28d) is already future → no roll-forward.
        val now = startOfDay(clock)
        val p1 = PeriodEntry(start = daysAgo(28))
        val p2 = PeriodEntry(start = daysAgo(0))
        val pred = CyclePredictor.predict(listOf(p1, p2), now = now)!!
        assertEquals(daysFromNow(28), startOfDay(pred.nextPeriodStart))
    }

    @Test
    fun testPredict_UsesLoggedPeriodDuration() { // :195
        val p1 = PeriodEntry(start = daysAgo(28), end = daysAgo(24)) // 4 days
        val p2 = PeriodEntry(start = daysAgo(0))
        val pred = CyclePredictor.predict(listOf(p1, p2), now = clock)!!

        val durDays = daysBetween(pred.nextPeriodStart, pred.nextPeriodEnd)
        assertEquals(4.0, durDays, 0.1)
    }

    // MARK: predict — skin-temp corroboration

    @Test
    fun testTempCorroborated_WithSufficientRise() { // :206
        val p1 = PeriodEntry(start = daysAgo(28))
        val p2 = PeriodEntry(start = daysAgo(0))
        val pred0 = CyclePredictor.predict(listOf(p1, p2), now = clock)!!

        // Place two rising nights within ±3 days of the predicted ovulation
        val ov = pred0.ovulationEstimate
        val deviations = listOf(
            SkinTempNight(night = ov.minusSeconds(1 * 86_400L), offsetC = 0.3),
            SkinTempNight(night = ov.plusSeconds(1 * 86_400L), offsetC = 0.4),
        )
        val pred = CyclePredictor.predict(listOf(p1, p2), skinTempDeviations = deviations, now = clock)!!
        assertTrue(pred.tempCorroborated)
    }

    @Test
    fun testTempNotCorroborated_OnlyOneRisingNight() { // :221
        val p1 = PeriodEntry(start = daysAgo(28))
        val p2 = PeriodEntry(start = daysAgo(0))
        val pred0 = CyclePredictor.predict(listOf(p1, p2), now = clock)!!

        val ov = pred0.ovulationEstimate
        val deviations = listOf(
            SkinTempNight(night = ov, offsetC = 0.5), // only one night
        )
        val pred = CyclePredictor.predict(listOf(p1, p2), skinTempDeviations = deviations, now = clock)!!
        assertFalse(pred.tempCorroborated, "need ≥ 2 nights to corroborate")
    }

    @Test
    fun testTempNotCorroborated_RiseBelowThreshold() { // :234
        val p1 = PeriodEntry(start = daysAgo(28))
        val p2 = PeriodEntry(start = daysAgo(0))
        val pred0 = CyclePredictor.predict(listOf(p1, p2), now = clock)!!

        val ov = pred0.ovulationEstimate
        // Offsets present but below threshold (0.2 °C)
        val deviations = listOf(
            SkinTempNight(night = ov.minusSeconds(86_400L), offsetC = 0.1),
            SkinTempNight(night = ov, offsetC = 0.05),
        )
        val pred = CyclePredictor.predict(listOf(p1, p2), skinTempDeviations = deviations, now = clock)!!
        assertFalse(pred.tempCorroborated)
    }

    @Test
    fun testTempNotCorroborated_RiseOutsideWindow() { // :249
        val p1 = PeriodEntry(start = daysAgo(28))
        val p2 = PeriodEntry(start = daysAgo(0))
        val pred0 = CyclePredictor.predict(listOf(p1, p2), now = clock)!!

        val ov = pred0.ovulationEstimate
        // Rising nights are 7 days away — outside the ±3-day window
        val deviations = listOf(
            SkinTempNight(night = ov.minusSeconds(7 * 86_400L), offsetC = 0.5),
            SkinTempNight(night = ov.plusSeconds(7 * 86_400L), offsetC = 0.5),
        )
        val pred = CyclePredictor.predict(listOf(p1, p2), skinTempDeviations = deviations, now = clock)!!
        assertFalse(pred.tempCorroborated)
    }

    // MARK: day-classification helpers

    @Test
    fun testIsLoggedPeriodDay() { // :266
        val p = PeriodEntry(start = daysAgo(5), end = daysAgo(2))
        assertTrue(CyclePredictor.isLoggedPeriodDay(daysAgo(5), entries = listOf(p), zone = cal))
        assertTrue(CyclePredictor.isLoggedPeriodDay(daysAgo(3), entries = listOf(p), zone = cal))
        assertTrue(CyclePredictor.isLoggedPeriodDay(daysAgo(2), entries = listOf(p), zone = cal))
        assertFalse(CyclePredictor.isLoggedPeriodDay(daysAgo(1), entries = listOf(p), zone = cal))
        assertFalse(CyclePredictor.isLoggedPeriodDay(daysAgo(6), entries = listOf(p), zone = cal))
    }

    @Test
    fun testIsLoggedPeriodDay_NoEnd_OnlyStartDay() { // :275
        val p = PeriodEntry(start = daysAgo(3))
        assertTrue(CyclePredictor.isLoggedPeriodDay(daysAgo(3), entries = listOf(p), zone = cal))
        assertFalse(CyclePredictor.isLoggedPeriodDay(daysAgo(2), entries = listOf(p), zone = cal))
    }

    @Test
    fun testIsInPredictedPeriod() { // :281
        val p1 = PeriodEntry(start = daysAgo(28))
        val p2 = PeriodEntry(start = daysAgo(0))
        val pred = CyclePredictor.predict(listOf(p1, p2), now = clock)!!

        val duringPred = startOfDay(pred.nextPeriodStart)
        assertTrue(CyclePredictor.isInPredictedPeriod(duringPred, prediction = pred, zone = cal))
        assertFalse(CyclePredictor.isInPredictedPeriod(daysAgo(100), prediction = pred, zone = cal))
    }

    @Test
    fun testIsInFertileWindow() { // :291
        val p1 = PeriodEntry(start = daysAgo(28))
        val p2 = PeriodEntry(start = daysAgo(0))
        val pred = CyclePredictor.predict(listOf(p1, p2), now = clock)!!

        val duringFertile = pred.fertileWindowStart.plusSeconds(86_400L)
        assertTrue(CyclePredictor.isInFertileWindow(duringFertile, prediction = pred, zone = cal))
        assertFalse(CyclePredictor.isInFertileWindow(daysAgo(100), prediction = pred, zone = cal))
    }

    @Test
    fun testIsOvulationDay() { // :301
        val p1 = PeriodEntry(start = daysAgo(28))
        val p2 = PeriodEntry(start = daysAgo(0))
        val pred = CyclePredictor.predict(listOf(p1, p2), now = clock)!!

        assertTrue(CyclePredictor.isOvulationDay(pred.ovulationEstimate, prediction = pred, zone = cal))
        assertFalse(CyclePredictor.isOvulationDay(daysAgo(100), prediction = pred, zone = cal))
    }
}
