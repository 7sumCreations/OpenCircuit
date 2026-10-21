package io.github.opencircuit.app.today

import io.github.opencircuit.ringkit.TemperatureUnit
import io.github.opencircuit.ringkit.TrendsEngine.DailyPoint
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the Kotlin port of the Today tiles adds beyond upstream's tests: number text that ignores
 * the default locale and rounds as Swift does, day keys in a DST zone, degraded inputs, and the two
 * Android tiles (heart rate, sleep) and the steps rules the dashboard adds.
 */
class TodayTilesGuardTest {

    private val saved = Locale.getDefault()

    @AfterTest
    fun restoreLocale() = Locale.setDefault(saved)

    private val zone: ZoneId = ZoneOffset.UTC
    private val now: Instant = Instant.parse("2026-09-30T10:00:00Z")

    private fun day(offset: Int, z: ZoneId = zone, at: Instant = now): Instant =
        LocalDate.ofInstant(at, z).plusDays(offset.toLong()).atStartOfDay(z).toInstant()

    private fun tile(metric: TodayTile.Metric, points: List<DailyPoint>, stepGoal: Int? = null, ranges: List<DayRange> = emptyList()): TodayTile =
        TodayTiles.build(metric, points, emptyList(), TemperatureUnit.CELSIUS, now = now, zone = zone, heartRateRanges = ranges, stepGoal = stepGoal)

    private fun nightly(values: List<Double>, pick: (Instant, Double) -> DailyPoint): List<DailyPoint> =
        values.mapIndexed { i, v -> pick(day(i - values.size + 1), v) }

    @Test
    fun numbersStayAsciiWithPointAndCommaWhateverTheDefaultLocale() {
        for (tag in listOf("ar-EG", "de-DE", "hi-IN-u-nu-deva")) {
            Locale.setDefault(Locale.forLanguageTag(tag))
            val steps = tile(TodayTile.Metric.STEPS, (0 until 8).map { i -> DailyPoint(date = day(i - 7), steps = if (i == 7) 1_200 else if (i == 6) 6_760 else 8_000) })
            assertEquals("1,200", steps.valueText, tag)
            assertEquals("Yesterday −1,240 vs usual", steps.deltaText, tag)
            val rr = tile(TodayTile.Metric.RESPIRATORY_RATE, nightly(listOf(14.0, 14.0, 14.0, 14.0, 15.25)) { d, v -> DailyPoint(date = d, sleepRRAvg = v) })
            assertEquals("15.2", rr.valueText, "Swift's %.1f of 15.25 (ties to even on the binary value), $tag")
            assertEquals("+1.3 brpm vs usual", rr.deltaText, "(1.25 × 10).rounded() = 13: ties away from zero, $tag")
        }
    }

    @Test
    fun oneDecimalTiesFollowSwiftNotJava() {
        val t = tile(TodayTile.Metric.SKIN_TEMP, nightly(listOf(36.0, 36.55)) { d, v -> DailyPoint(date = d, skinTempC = v) })
        assertEquals("36.5", t.valueText, "Java's String.format would print 36.6")
        assertEquals("36.6", String.format(Locale.ROOT, "%.1f", 36.55), "the vector separates the two rules")
    }

    @Test
    fun wholeNumbersRoundTiesAwayFromZeroLikeSwift() {
        val t = tile(TodayTile.Metric.HRV, nightly(listOf(56.5)) { d, v -> DailyPoint(date = d, sleepHRVAvg = v) })
        assertEquals("57", t.valueText, "kotlin.math.round(56.5) is 56 (ties to even); Swift's rounded() is 57")
    }

    @Test
    fun aDeltaThatRoundsToZeroReadsAsPlusMinusZero() {
        assertEquals("±0.0", TileNumber.ONE_DECIMAL.formatDelta(-0.04))
        assertEquals("±0", TileNumber.WHOLE.formatDelta(-0.4))
        assertEquals("−1", TileNumber.WHOLE.formatDelta(-0.5))
    }

    @Test
    fun theWindowsDaysAreLocalMidnightsAcrossADstChange() {
        val berlin = ZoneId.of("Europe/Berlin")
        val dstEnd = Instant.parse("2026-10-25T10:00:00Z") // 25 Oct 2026: clocks go back
        val pts = (0 until 14).map { DailyPoint(date = day(-it, berlin, dstEnd).plusSeconds(3_600), sleepHRVAvg = 50.0) }
        val t = TodayTiles.build(TodayTile.Metric.HRV, pts, emptyList(), TemperatureUnit.CELSIUS, now = dstEnd, zone = berlin)
        assertEquals(14, t.days.toSet().size)
        assertTrue(t.days.all { it.atZone(berlin).toLocalTime().toSecondOfDay() == 0 }, "every day starts at local midnight")
        assertEquals(14, t.values.count { it != null }, "the DST day's night lands in its own slot")
        assertEquals("last night", t.freshnessText(dstEnd, berlin))
    }

    @Test
    fun zeroNegativeAndNonFiniteValuesAreGapsNotReadings() {
        val pts = listOf(0.0, -3.0, Double.NaN, Double.POSITIVE_INFINITY).mapIndexed { i, v -> DailyPoint(date = day(i - 3), sleepHRVAvg = v) }
        val t = tile(TodayTile.Metric.HRV, pts)
        assertNull(t.valueText)
        assertTrue(t.values.all { it == null })
    }

    @Test
    fun theHeartRateTileIsTheDaysAverageWithItsRange() {
        val pts = nightly(listOf(70.0, 71.0, 69.0, 70.0, 76.4)) { d, v -> DailyPoint(date = d, dayHRAvg = v) }
        val t = tile(TodayTile.Metric.HEART_RATE, pts, ranges = listOf(DayRange(day(0), 48.0, 131.0)))
        assertEquals("76", t.valueText)
        assertEquals("today avg", t.qualifier)
        assertEquals("today", t.freshnessText(now, zone))
        assertEquals("Range 48–131 bpm", t.extra)
        assertEquals("+6 bpm vs usual", t.deltaText)
        assertTrue(t.accessibilityLabel(now, zone).contains("Range 48–131 bpm"))
        // Yesterday's average when today has none: a day average, no range invented.
        val older = tile(TodayTile.Metric.HEART_RATE, pts.dropLast(1))
        assertEquals("day avg", older.qualifier)
        assertNull(older.extra)
    }

    @Test
    fun theSleepTileIsLastNightsAsleepTimeWithItsScore() {
        val pts = (listOf(420, 420, 420, 420, 480)).mapIndexed { i, m ->
            DailyPoint(date = day(i - 4), sleepMinutes = m, sleepScore = if (i == 4) 82 else 70)
        }
        val t = tile(TodayTile.Metric.SLEEP, pts)
        assertEquals("8 h 00 m", t.valueText)
        assertEquals("last night", t.freshnessText(now, zone))
        assertEquals("Sleep score 82", t.extra)
        assertEquals("+1 h 00 m vs usual", t.deltaText)
        assertEquals("Usual 6 h 30 m–7 h 30 m", t.rangeText)
        assertEquals("45 m", TileNumber.DURATION.format(45.0))
        // A tile with no spoken unit adds no stray space before a comma or at a sentence's end.
        val label = t.accessibilityLabel(now, zone)
        assertFalse(label.contains(" ,") || label.contains(" .") || label.contains("  "), label)
        assertTrue(label.contains("of 6 h 30 m to 7 h 30 m, +1 h 00 m versus your average"), label)
    }

    @Test
    fun stepsNeverReadZeroWhenNothingWasRecorded() {
        val none = tile(TodayTile.Metric.STEPS, listOf(DailyPoint(date = day(0), steps = 0)))
        assertNull(none.valueText)
        assertEquals("Steps not recorded", none.rangeText)
        val label = none.accessibilityLabel(now, zone)
        assertTrue(label.contains("steps not recorded"), label)
        assertFalse(label.contains("0 steps"), label)
    }

    @Test
    fun stepsOnTodayShowTheGoalAndTheConnectedTimeCaveat() {
        val pts = listOf(DailyPoint(date = day(-1), steps = 9_000), DailyPoint(date = day(0), steps = 3_400))
        assertEquals("Goal 8,000 · connected time only", tile(TodayTile.Metric.STEPS, pts, stepGoal = 8_000).extra)
        // No total today: no goal against yesterday's number.
        assertEquals("Connected time only", tile(TodayTile.Metric.STEPS, pts.dropLast(1), stepGoal = 8_000).extra)
    }

    /**
     * The port holds every upstream test once, under its upstream name. The list is the `func test`
     * lines of ios/OpenCircuitTests/TodayTilesTests.swift @ 2d07e550, in file order; the Kotlin side
     * is read off the compiled test class, not typed.
     */
    @Test
    fun thePortHoldsEveryUpstreamTileTestOnce() {
        val upstream = listOf(
            "testNoDataSaysSo", "testLastNightIsFreshAndBandMatchesVerdict", "testANightThatEndedYesterdayIsStale",
            "testThinBaselineIsLearning", "testGapsStayGaps", "testStepsHeadlineIsTodayButTrendIsYesterday",
            "testStepsStateNoVerdictWhenYesterdayIsMissing", "testRestingHRUsesTheDerivedSeries",
            "testTheRestingHRCopyDescribesTheSparseReadingFallback", "testThirtyDayWindow",
            "testSynthesisIgnoresStaleTiles", "testSynthesisTakesFeverSignsFromVitalsStatusOnly",
            "testSynthesisNeverClaimsNoDataAfterASync",
        )
        assertEquals(13, upstream.size)
        val expected = upstream.map { it.removePrefix("test").replaceFirstChar(Char::lowercaseChar) }.sorted()
        val ported = TodayTilesTest::class.java.declaredMethods.filter { it.isAnnotationPresent(Test::class.java) }.map { it.name }.sorted()
        assertEquals(expected, ported)
    }

    /** The tiles never read the phone's clock, zone or locale: every one is passed in. */
    @Test
    fun theTileSourcesReadNoAmbientClockZoneOrLocale() {
        val ambient = Regex("""\b[A-Z]\w*\.now\(|systemDefault\(|Locale\.getDefault\(|currentTimeMillis\(|String\.format\("%""")
        val defaultedClock = Regex("""\b(now|zone)\s*:\s*(Instant|ZoneId)\??\s*=""")
        val hits = listOf("TodayTiles.kt", "TodaySynthesisInputs.kt").flatMap { name ->
            val file = java.io.File("src/main/kotlin/io/github/opencircuit/app/today/$name")
            assertTrue(file.isFile, "missing $name")
            file.readLines().withIndex().filter { (_, l) -> ambient.containsMatchIn(l) || defaultedClock.containsMatchIn(l) }.map { "$name:${it.index + 1}" }
        }
        assertEquals(emptyList(), hits)
    }

    @Test
    fun everyTileInTheDashboardsDefaultOrder() {
        val tiles = TodayTiles.build(emptyList(), emptyList(), TemperatureUnit.CELSIUS, now = now, zone = zone)
        assertEquals(
            listOf("Heart rate", "HRV", "Resting HR", "SpO₂", "Resp. rate", "Skin temp", "Sleep", "Steps"),
            tiles.map { it.title },
        )
    }
}
