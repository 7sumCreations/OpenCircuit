package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.TrendsEngine.DailyPoint
import io.github.opencircuit.ringkit.TrendsEngine.Trend
import io.github.opencircuit.ringkit.TrendsRefreshPolicy.Reason
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice-end tracer for the trends engine: two weeks of a wearer's ring data run through the vitals
 * and energy code into daily points, then into rolling averages and trend directions — and the
 * refresh policy decides when that recomputation happens.
 *
 * The days are built the way upstream's trends screen builds them (its `TrendsData.computePoints`),
 * from the ported E4 functions, in New York, across the 2026 spring-forward day (8 March, 23 hours).
 * Which daily-point field each value fills:
 *
 * | value                                   | from                               | daily-point field          |
 * |-----------------------------------------|------------------------------------|----------------------------|
 * | the day's step count                    | (the decoded daily steps)          | `steps`                    |
 * | distance                                | `DistanceEstimate.meters(steps)`   | `distanceM`                |
 * | active energy, elevated minutes         | `Calories.dailyEstimate`           | `activeEnergyKcal`, `exerciseMin` |
 * | mean HR over the night / the whole day  | the HR samples, `> MIN_VALID_HR`   | `sleepHRAvg`, `dayHRAvg`   |
 * | the night's HRV                         | `HRV.rmssd` of the night's RR      | `sleepHRVAvg`              |
 * | resting HR                              | `RestingHR.dailyValues` (in zone)  | **none** — upstream carries resting HR outside the point; a caller trends it through `trend`'s `extract`, keyed by the point's `date` |
 *
 * The last row is the seam this test exists for: `RestingHR` keys each day by its local midnight in
 * the zone, and the point's `date` must be that same instant or the lookup finds nothing.
 */
class TrendsTracerTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val firstDay: LocalDate = LocalDate.of(2026, 3, 1) // the 8th is the spring-forward day
    private val profile = UserProfile(age = 40, weightKg = 70.0, heightCm = 175.0, sex = BiologicalSex.FEMALE)

    /** One synthetic day: its resting (overnight) HR, step count, night RR swing (= its RMSSD) and workout HR. */
    private class Spec(val restingBpm: Int, val steps: Int, val rrSwingMs: Int, val workoutBpm: Int)

    // Week 1: resting 60 bpm, 6 000 steps, HRV 50 ms, a 140 bpm workout. Week 2: resting 66 (+10 %),
    // 9 000 steps (+50 %), HRV 40 ms (−20 %), a 150 bpm workout.
    private val specs = List(14) { i -> if (i < 7) Spec(60, 6_000, 50, 140) else Spec(66, 9_000, 40, 150) }

    private fun at(day: LocalDate, h: Int, m: Int): Instant = day.atTime(LocalTime.of(h, m)).atZone(zone).toInstant()

    private class Day(val start: Instant, val hr: List<HRSample>, val sleep: List<SleepSegment>, val nightRR: List<Int>, val spec: Spec, val night: DateInterval)

    private fun day(i: Int): Day {
        val date = firstDay.plusDays(i.toLong())
        val spec = specs[i]
        val start = date.atStartOfDay(zone).toInstant()
        val night = DateInterval(at(date, 0, 30), at(date, 6, 30))
        // One reading per 150 s epoch, the ring's own cadence (an elevated point reading counts an
        // epoch only when another elevated reading is within one epoch of it).
        val epoch = BulkRecord.EPOCH_SECONDS.toLong()
        val hr = mutableListOf<HRSample>()
        var t = at(date, 0, 30)
        while (t < night.end) { hr += HRSample(spec.restingBpm, t); t = t.plusSeconds(epoch) }
        t = at(date, 7, 0)
        val evening = at(date, 23, 0)
        while (t < evening) {
            val workout = t >= at(date, 18, 0) && t < at(date, 18, 50)
            hr += HRSample(if (workout) spec.workoutBpm else spec.restingBpm + 25, t)
            t = t.plusSeconds(epoch)
        }
        val sleep = listOf(SleepSegment(night.start, night.end, SleepStage.ASLEEP_CORE))
        val rr = List(60) { k -> if (k % 2 == 0) 1_000 else 1_000 + spec.rrSwingMs }
        return Day(start, hr, sleep, rr, spec, night)
    }

    private fun mean(xs: List<Int>): Double? = if (xs.isEmpty()) null else xs.sumOf { it.toDouble() } / xs.size

    /** A daily point as upstream's trends screen assembles it, from the E4 functions. */
    private fun point(d: Day): DailyPoint {
        val dayKey = assertNotNull(CalendarDay.startOfDay(d.start, zone))
        val estimate = Calories.dailyEstimate(d.hr, steps = d.spec.steps, profile = profile, sleepWindow = d.night, dayStart = dayKey)
        return DailyPoint(
            date = dayKey,
            steps = d.spec.steps,
            sleepHRAvg = mean(d.hr.filter { d.night.containsClosed(it.start) && it.bpm > TrendsEngine.MIN_VALID_HR }.map { it.bpm }),
            sleepHRVAvg = HRV.rmssd(d.nightRR)?.toDouble(),
            dayHRAvg = mean(d.hr.filter { it.bpm > TrendsEngine.MIN_VALID_HR }.map { it.bpm }),
            activeEnergyKcal = estimate.activeKcal,
            distanceM = DistanceEstimate.meters(d.spec.steps),
            exerciseMin = estimate.elevatedMinutes,
        )
    }

    private val days = List(14) { day(it) }

    @Test
    fun twoWeeksOfRingDataBecomeRollingAveragesAndTrends() {
        val points = days.map(::point)

        // The days are fourteen distinct local midnights, and the spring-forward day is 23 hours long —
        // the points are keyed in the zone, not by 24-hour steps.
        assertEquals(14, points.map { it.date }.toSet().size)
        assertEquals(Duration.ofHours(23), Duration.between(points[7].date, points[8].date))

        // The last seven days, averaged (week 2's values, typed from the construction above).
        val avg = TrendsEngine.rollingAverages(points)
        assertEquals(9_000.0, avg.steps)
        assertEquals(66.0, avg.sleepHRAvg)
        assertEquals(40.0, avg.sleepHRVAvg)
        assertTrue(abs(assertNotNull(avg.distanceM) - 2_232.0) < 1e-9, "9 000 steps × 0.248 m, got ${avg.distanceM}")
        assertTrue(assertNotNull(avg.exerciseMin) > 0, "the workout's elevated minutes reach the average")
        // A harder week with more steps burns more: the second week's average exceeds the first's.
        val week1 = TrendsEngine.rollingAverages(points.take(7))
        assertTrue(assertNotNull(avg.activeEnergyKcal) > assertNotNull(week1.activeEnergyKcal), "week 2 ${avg.activeEnergyKcal} vs week 1 ${week1.activeEnergyKcal}")

        // Directions, week 2 against week 1.
        assertEquals(Trend.UP, TrendsEngine.trend(points) { it.steps?.toDouble() }) // +50 %
        assertEquals(Trend.DOWN, TrendsEngine.trend(points) { it.sleepHRVAvg }) // −20 %
        assertEquals(Trend.UP, TrendsEngine.trend(points) { it.sleepHRAvg }) // +10 %
        assertEquals(Trend.UP, TrendsEngine.trend(points) { it.activeEnergyKcal })
    }

    @Test
    fun restingHRTrendsThroughThePointsOwnDays() {
        val points = days.map(::point)
        val resting = RestingHR.dailyValues(days.flatMap { it.hr }, days.flatMap { it.sleep }, zone = zone)
        // One resting value per day, from the night's asleep samples — 60 then 66 bpm.
        assertEquals(List(7) { 60.0 } + List(7) { 66.0 }, resting.map { it.bpm })
        val byDay = resting.associate { it.day to it.bpm }
        // The seam: every point's date is a key RestingHR produced, so the lookup finds all fourteen.
        assertEquals(points.map { it.date }, resting.map { it.day })
        assertEquals(Trend.UP, TrendsEngine.trend(points) { byDay[it.date] }) // +10 %
        // Keyed by UTC midnight instead, the lookup finds nothing and there is no trend: the seam bites.
        val utcKeyed = points.map { it.copy(date = assertNotNull(CalendarDay.startOfDay(it.date, ZoneId.of("UTC")))) }
        assertNull(TrendsEngine.trend(utcKeyed) { byDay[it.date] })
    }

    @Test
    fun aFinishedSyncRecomputesTheTrendsAndNavigationDoesNot() {
        // The screen first loads with the first week only: no prior week, so no direction yet.
        val loadedAt = at(firstDay.plusDays(7), 8, 0)
        var shown = days.take(7).map(::point)
        assertNull(TrendsEngine.trend(shown) { it.steps?.toDouble() })
        // A drain lands the second week. The app comes to the foreground 30 s after the load: a
        // navigation event inside the minute, so the stale snapshot stays.
        assertFalse(TrendsRefreshPolicy.shouldReload(Reason.FOREGROUNDED, lastLoadedAt = loadedAt, now = loadedAt.plusSeconds(30)))
        // The sync's own completion, a second later, always reloads — and the trend appears.
        val syncDone = loadedAt.plusSeconds(31)
        if (TrendsRefreshPolicy.shouldReload(Reason.SYNC_FINISHED, lastLoadedAt = loadedAt, now = syncDone)) {
            shown = days.map(::point)
        }
        assertEquals(Trend.UP, TrendsEngine.trend(shown) { it.steps?.toDouble() })
        assertEquals(9_000.0, TrendsEngine.rollingAverages(shown).steps)
    }
}
