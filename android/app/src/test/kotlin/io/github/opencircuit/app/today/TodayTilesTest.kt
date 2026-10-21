package io.github.opencircuit.app.today

import io.github.opencircuit.ringkit.BaselineTrend.Direction
import io.github.opencircuit.ringkit.HRSample
import io.github.opencircuit.ringkit.RestingHR
import io.github.opencircuit.ringkit.TemperatureUnit
import io.github.opencircuit.ringkit.TodaySynthesis
import io.github.opencircuit.ringkit.TrendsEngine.DailyPoint
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Synthetic-only tests for the Today metric tiles: which day's value a tile shows, how it labels
 * freshness, that steps compare the last COMPLETE day, that the labelled usual range is the same
 * band the verdict uses, and that missing data reads as missing. No real health values.
 *
 * Port of upstream ios/OpenCircuitTests/TodayTilesTests.swift (@ 2d07e550, the last MIT commit to
 * touch it): all 13 tests. The `// :n` lines cite each test. The calendar is a fixed UTC zone
 * passed in (upstream's `cal`), never the phone's; `1_200.formatted(.number)` is the en grouping
 * "1,200" the tiles print with an explicit locale.
 */
class TodayTilesTest {

    private val zone: ZoneId = ZoneOffset.UTC

    /** 2026-09-30 10:00 UTC. */
    private val now: Instant = Instant.ofEpochSecond(1_790_762_400)

    private fun day(offset: Int): Instant = LocalDate.ofInstant(now, zone).plusDays(offset.toLong()).atStartOfDay(zone).toInstant()

    private fun tile(metric: TodayTile.Metric, points: List<DailyPoint>, restingHR: List<RestingHR.DailyValue> = emptyList(), window: Int = 14): TodayTile =
        TodayTiles.build(metric, points = points, restingHR = restingHR, tempUnit = TemperatureUnit.CELSIUS, windowDays = window, now = now, zone = zone)

    /** Nights keyed on their WAKE day (`SleepNightKey`), last night = today's key. */
    private fun nights(hrv: List<Double>, endingOffset: Int = 0): List<DailyPoint> =
        hrv.mapIndexed { i, v -> DailyPoint(date = day(endingOffset - (hrv.size - 1 - i)), sleepHRVAvg = v) }

    // :34
    @Test
    fun noDataSaysSo() {
        val t = tile(TodayTile.Metric.HRV, emptyList())
        assertNull(t.valueText)
        assertEquals("No data yet", t.rangeText)
        assertNull(t.deltaText)
        assertNull(t.freshnessText(now, zone))
        assertEquals(14, t.values.size)
        assertTrue(t.values.all { it == null })
    }

    // :44
    @Test
    fun lastNightIsFreshAndBandMatchesVerdict() {
        val t = tile(TodayTile.Metric.HRV, nights(listOf(50.0, 50.0, 50.0, 50.0, 50.0, 58.0)))
        assertEquals("58", t.valueText)
        assertEquals("last night", t.freshnessText(now, zone))
        assertFalse(t.isStale(now, zone))
        assertEquals(Direction.ABOVE, t.trend?.direction)
        assertEquals("+8 ms vs usual", t.deltaText)
        // Floor 3 ms, flat baseline → the band is exactly 47–53, the range the label names.
        assertEquals("Usual 47–53 ms", t.rangeText)
        assertEquals(58.0, t.values.last())
    }

    // :56
    @Test
    fun aNightThatEndedYesterdayIsStale() {
        val t = tile(TodayTile.Metric.HRV, nights(listOf(50.0, 50.0, 50.0, 50.0, 50.0), endingOffset = -1))
        assertTrue(t.isStale(now, zone))
        assertTrue(t.freshnessText(now, zone)?.startsWith("night to") == true)
        assertNull(t.values.last(), "no value for today's key")
    }

    // :63
    @Test
    fun thinBaselineIsLearning() {
        val t = tile(TodayTile.Metric.HRV, nights(listOf(50.0, 52.0, 58.0)))
        assertNull(t.trend?.direction)
        assertNull(t.deltaText)
        assertEquals("Learning your usual · 2/4 days", t.rangeText)
    }

    // :70
    @Test
    fun gapsStayGaps() {
        val pts = nights(listOf(50.0, 50.0, 50.0, 50.0, 50.0, 50.0)).toMutableList()
        pts.removeAt(2)
        val t = tile(TodayTile.Metric.HRV, pts)
        assertEquals(1, t.values.takeLast(6).count { it == null })
    }

    // :77
    @Test
    fun stepsHeadlineIsTodayButTrendIsYesterday() {
        val pts = (0 until 8).map { i ->
            val offset = i - 7 // −7 … 0 (today)
            DailyPoint(date = day(offset), steps = if (offset == 0) 1_200 else if (offset == -1) 12_000 else 8_000)
        }
        val t = tile(TodayTile.Metric.STEPS, pts)
        assertEquals("1,200", t.valueText)
        assertEquals("today", t.freshnessText(now, zone))
        assertTrue(t.trendIsYesterday)
        assertEquals(Direction.ABOVE, t.trend?.direction)
        assertEquals("Yesterday +4,000 vs usual", t.deltaText)
        assertNull(t.values.last(), "today's partial total is not plotted")
        assertEquals(12_000.0, t.values[t.values.size - 2])
    }

    // :97 — yesterday has no step total (the ring was charging), so the last complete day the trend
    // judges is the day BEFORE yesterday: no delta, no arrow, no verdict; the usual range still shows.
    @Test
    fun stepsStateNoVerdictWhenYesterdayIsMissing() {
        val pts = (-8..-3).map { DailyPoint(date = day(it), steps = 8_000) } +
            DailyPoint(date = day(-2), steps = 12_000) + DailyPoint(date = day(0), steps = 1_200)
        val t = tile(TodayTile.Metric.STEPS, pts)
        assertEquals("1,200", t.valueText)
        assertEquals("so far today", t.qualifier)
        assertEquals(day(-2), t.trend?.latest?.date)
        assertFalse(t.trendIsYesterday)
        assertNull(t.statedTrend)
        assertNull(t.deltaText, "no delta, and so no arrow, under today's number")
        assertTrue(t.rangeText.startsWith("Usual "), "the band and its label still show")
        val label = t.accessibilityLabel(now, zone)
        assertFalse(label.contains("above"), label)
        assertFalse(label.contains("That's"), label)
        assertTrue(label.contains("Your usual range is"), label)
        assertFalse(label.contains("still learning"), label)

        // The same with no step total today either: the headline is the day before yesterday's, and
        // the rule is the same — a steps verdict is stated about yesterday or not at all.
        val stale = tile(TodayTile.Metric.STEPS, pts.dropLast(1))
        assertEquals("latest day", stale.qualifier)
        assertNull(stale.deltaText)
    }

    // :121
    @Test
    fun restingHRUsesTheDerivedSeries() {
        val rhr = (0 until 6).map { RestingHR.DailyValue(day = day(it - 5), bpm = if (it == 5) 58.0 else 52.0) }
        val t = tile(TodayTile.Metric.RESTING_HR, emptyList(), restingHR = rhr)
        assertEquals("58", t.valueText)
        assertEquals("today", t.freshnessText(now, zone))
        assertEquals(Direction.ABOVE, t.trend?.direction)
    }

    // :132 — on a day of spot reads 10 minutes apart no 5-minute window holds two readings, so the
    // value is the single lowest READING; the copy says exactly that.
    @Test
    fun theRestingHRCopyDescribesTheSparseReadingFallback() {
        val bpms = listOf(64, 61, 48, 63, 62, 60)
        val hr = bpms.mapIndexed { i, b -> HRSample(bpm = b, start = now.plusSeconds(i * 600L), end = now.plusSeconds(i * 600L + 60)) }
        assertEquals(listOf(48.0), RestingHR.dailyValues(hr = hr, zone = zone).map { it.bpm })
        val copy = TodayTiles.what(TodayTile.Metric.RESTING_HR)
        assertTrue(copy.contains("lowest reading when readings are sparse"), copy)
        assertFalse(copy.contains("5-minute average"), copy)
    }

    // :144
    @Test
    fun thirtyDayWindow() {
        val t = tile(TodayTile.Metric.HRV, nights(List(30) { 50.0 }), window = 30)
        assertEquals(30, t.values.size)
        assertEquals(30, t.values.filterNotNull().size)
        assertEquals(29, t.trend?.baselineDays)
    }

    // :151
    @Test
    fun synthesisIgnoresStaleTiles() {
        val tiles = listOf(tile(TodayTile.Metric.HRV, nights(listOf(50.0, 50.0, 50.0, 50.0, 50.0, 40.0), endingOffset = -2)))
        val trends = TodayTrends(newestSampleAt = now.minusSeconds(3600))
        val input = TodaySynthesisInputs.input(trends, tiles, readiness = null, lastSyncAt = null, now = now, zone = zone)
        assertNull(input.hrv, "a two-day-old night must not be reported as today's HRV")
    }

    // :161 — the fever pairing is Vitals Status's verdict, passed through untouched, never derived
    // from the tiles. Two tiles above their usual, no Vitals Status fever: no pairing.
    @Test
    fun synthesisTakesFeverSignsFromVitalsStatusOnly() {
        val hot = listOf(33.5, 33.5, 33.5, 33.5, 33.5, 33.9).mapIndexed { i, v -> DailyPoint(date = day(i - 5), skinTempC = v) }
        val rhr = (0 until 6).map { RestingHR.DailyValue(day = day(it - 5), bpm = if (it == 5) 61.0 else 58.0) }
        val tiles = listOf(tile(TodayTile.Metric.SKIN_TEMP, hot), tile(TodayTile.Metric.RESTING_HR, emptyList(), restingHR = rhr))
        assertEquals(listOf(Direction.ABOVE, Direction.ABOVE), tiles.map { it.trend?.direction })
        val trends = TodayTrends(newestSampleAt = now.minusSeconds(3600))

        val quiet = TodaySynthesisInputs.input(trends, tiles, readiness = null, lastSyncAt = now, now = now, zone = zone)
        assertFalse(quiet.feverSuspected)
        assertFalse(TodaySynthesis.sentence(quiet).contains("gently"))

        val flagged = TodaySynthesisInputs.input(trends, tiles, readiness = null, feverSuspected = true, lastSyncAt = now, now = now, zone = zone)
        assertTrue(flagged.feverSuspected)
        assertEquals(
            "Skin temperature and resting heart rate are both above your usual, so take today gently.",
            TodaySynthesis.sentence(flagged),
        )
    }

    // :182
    @Test
    fun synthesisNeverClaimsNoDataAfterASync() {
        val input = TodaySynthesisInputs.input(TodayTrends(), emptyList(), readiness = null, lastSyncAt = now.minusSeconds(86_400L * 20), now = now, zone = zone)
        assertEquals(
            "Your newest data is more than two weeks old, so sync your device to bring today's summary up to date.",
            TodaySynthesis.sentence(input),
        )
    }
}
