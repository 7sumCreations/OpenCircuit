package io.github.opencircuit.app.today

import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.store.DailySteps
import io.github.opencircuit.store.DaytimeTemperature
import io.github.opencircuit.store.StepSample
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The per-day rollup the tiles read (upstream `TrendsData.computePoints`,
 * ios/OpenCircuit/Trends/TrendsData.swift:228-300 @ 2d07e550): a night's vitals are averaged over
 * its in-bed window and land on its wake day; the day's heart rate keeps only plausible readings;
 * zero means "not measured". Synthetic values only; SpO₂ is stored as a fraction, as the ring's is.
 */
class TodayTrendsTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private fun at(s: String): Instant = Instant.parse(s)
    private fun day(d: String): Instant = LocalDate.parse(d).atStartOfDay(zone).toInstant()
    private fun sample(kind: MetricKind, t: String, v: Double) = QuantitySample(kind = kind, start = at(t), value = v)

    /** The night that ended on 2026-09-30: in bed 22:40 → 06:50. */
    private val night = TodayNight(
        night = day("2026-09-30"), asleepMin = 431, sleepScore = 82, stressScore = 34, skinTempC = 33.6,
        inBedStart = at("2026-09-29T22:40:00Z"), inBedEnd = at("2026-09-30T06:50:00Z"),
    )

    @Test
    fun aNightsVitalsAreItsInBedAveragesOnItsWakeDay() {
        val inputs = TodayWindowInputs(
            nights = listOf(night),
            hrv = listOf(sample(MetricKind.HRV_SDNN, "2026-09-29T23:00:00Z", 40.0), sample(MetricKind.HRV_SDNN, "2026-09-30T05:00:00Z", 50.0),
                // After waking: a daytime reading, not the night's.
                sample(MetricKind.HRV_SDNN, "2026-09-30T12:00:00Z", 90.0)),
            spo2 = listOf(sample(MetricKind.SPO2, "2026-09-30T01:00:00Z", 0.96), sample(MetricKind.SPO2, "2026-09-30T03:00:00Z", 0.98)),
            rr = listOf(sample(MetricKind.RESPIRATORY_RATE, "2026-09-30T02:00:00Z", 14.5)),
        )
        val p = TodayTrends.of(inputs, zone).points.single { it.date == day("2026-09-30") }
        assertEquals(45.0, p.sleepHRVAvg)
        assertEquals(0.97, p.sleepSpO2Avg!!, 1e-12)
        assertEquals(14.5, p.sleepRRAvg)
        assertEquals(431, p.sleepMinutes)
        assertEquals(82, p.sleepScore)
        assertEquals(34, p.stressScore)
        assertEquals(33.6, p.skinTempC)
        // The evening the night began holds no sleep vitals of its own.
        assertNull(TodayTrends.of(inputs, zone).points.single { it.date == day("2026-09-29") }.sleepHRVAvg)
    }

    @Test
    fun zeroOnTheSummaryMeansNotMeasured() {
        val blank = night.copy(asleepMin = 0, sleepScore = 0, stressScore = 0, skinTempC = 0.0)
        val p = TodayTrends.of(TodayWindowInputs(nights = listOf(blank)), zone).points.single()
        assertNull(p.sleepMinutes)
        assertNull(p.sleepScore)
        assertNull(p.stressScore)
        assertNull(p.skinTempC)
    }

    @Test
    fun aNightWithNoInBedWindowAveragesNothing() {
        val noWindow = night.copy(inBedStart = Instant.parse("0001-01-01T00:00:00Z"), inBedEnd = Instant.parse("0001-01-01T00:00:00Z"))
        val inputs = TodayWindowInputs(nights = listOf(noWindow), hrv = listOf(sample(MetricKind.HRV_SDNN, "2026-09-30T02:00:00Z", 44.0)))
        assertNull(TodayTrends.of(inputs, zone).points.single { it.date == day("2026-09-30") }.sleepHRVAvg)
    }

    @Test
    fun theDaysHeartRateKeepsOnlyPlausibleReadingsWithItsRange() {
        val hr = listOf(
            sample(MetricKind.HEART_RATE, "2026-09-30T08:00:00Z", 58.0),
            sample(MetricKind.HEART_RATE, "2026-09-30T12:00:00Z", 121.0),
            sample(MetricKind.HEART_RATE, "2026-09-30T13:00:00Z", 29.0), // at the guard: not a reading
            sample(MetricKind.HEART_RATE, "2026-09-30T18:00:00Z", 64.0),
        )
        val trends = TodayTrends.of(TodayWindowInputs(hr = hr), zone)
        assertEquals(81.0, trends.points.single().dayHRAvg)
        assertEquals(listOf(DayRange(day("2026-09-30"), 58.0, 121.0)), trends.heartRateRanges)
        assertEquals(listOf(day("2026-09-30")), trends.restingHR.map { it.day })
    }

    /** A reading at exactly the next midnight belongs to the next day only: its average and its range agree. */
    @Test
    fun aReadingAtMidnightCountsForOneDayOnly() {
        val hr = listOf(sample(MetricKind.HEART_RATE, "2026-09-30T08:00:00Z", 60.0), sample(MetricKind.HEART_RATE, "2026-10-01T00:00:00Z", 100.0))
        val trends = TodayTrends.of(TodayWindowInputs(hr = hr), zone)
        assertEquals(listOf(60.0, 100.0), trends.points.map { it.dayHRAvg })
        assertEquals(listOf(60.0, 100.0), trends.heartRateRanges.map { it.high })
    }

    @Test
    fun stepsComeFromTheDailyTotals() {
        val trends = TodayTrends.of(
            TodayWindowInputs(dailies = listOf(DailySteps(day("2026-09-29"), 9_512, at("2026-09-29T23:59:00Z")), DailySteps(day("2026-09-30"), 0, at("2026-09-30T07:00:00Z")))),
            zone,
        )
        assertEquals(listOf(9_512, 0), trends.points.map { it.steps })
    }

    @Test
    fun theNewestReadingOfAnyKindIsKept() {
        val inputs = TodayWindowInputs(
            hr = listOf(sample(MetricKind.HEART_RATE, "2026-09-30T08:00:00Z", 60.0)),
            dayTemps = listOf(DaytimeTemperature(at("2026-09-30T09:30:00Z"), 34.1)),
            stepDeltas = listOf(StepSample(at("2026-09-30T09:00:00Z"), at("2026-09-30T09:45:00Z"), 120, healthWritten = false)),
        )
        assertEquals(at("2026-09-30T09:45:00Z"), TodayTrends.of(inputs, zone).newestSampleAt)
        assertNull(TodayTrends.of(TodayWindowInputs(), zone).newestSampleAt)
    }

    @Test
    fun daysGroupByTheLocalCalendarDayAcrossADstChange() {
        val berlin = ZoneId.of("Europe/Berlin")
        // 25 Oct 2026, the day clocks go back: 23:30 local is still the 25th.
        val hr = listOf(QuantitySample(kind = MetricKind.HEART_RATE, start = Instant.parse("2026-10-25T22:30:00Z"), value = 70.0))
        val p = TodayTrends.of(TodayWindowInputs(hr = hr), berlin).points.single()
        assertEquals(LocalDate.parse("2026-10-25").atStartOfDay(berlin).toInstant(), p.date)
        assertTrue(p.date.atZone(berlin).toLocalTime().toSecondOfDay() == 0)
    }

    @Test
    fun pointsAreOldestFirst() {
        val hr = listOf("2026-09-30T08:00:00Z", "2026-09-28T08:00:00Z", "2026-09-29T08:00:00Z").map { sample(MetricKind.HEART_RATE, it, 60.0) }
        assertEquals(listOf(day("2026-09-28"), day("2026-09-29"), day("2026-09-30")), TodayTrends.of(TodayWindowInputs(hr = hr), zone).points.map { it.date })
    }
}
