package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.TrendsEngine.DailyPoint
import io.github.opencircuit.ringkit.TrendsEngine.Trend
import io.github.opencircuit.ringkit.TrendsRefreshPolicy.Reason
import java.time.Instant
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kotlin-only hostile-input checks for the trends engine and its refresh policy: what the upstream
 * vectors never feed in. The daily values are derived from wire data and stored, bedtimes are stored
 * minutes, and the windows are caller values, so here windows arrive negative, zero, one or huge,
 * counts arrive at the ends of `Int`, values arrive NaN, infinite, signed-zero or huge, bedtimes
 * arrive outside the day, dates arrive unsorted, and the clock arrives before the last load or at the
 * ends of `Instant`'s range. Kept out of the upstream-port classes so their counts stay exact.
 *
 * Every upstream outcome quoted below was measured on the pinned Swift build (Swift 6.3.2). Where the
 * port deliberately differs the test says so, and `PORTING.md` records why.
 */
class TrendsHazardTest {

    /** Day [i] after the Unix epoch; the dates are labels only, which `daysAreTakenByPositionNotByDate` pins. */
    private fun day(i: Int): Instant = Instant.EPOCH.plusSeconds(86_400L * i)
    private fun steps(vararg xs: Int?): List<DailyPoint> = xs.mapIndexed { i, s -> DailyPoint(date = day(i), steps = s) }
    private fun hr(vararg xs: Double?): List<DailyPoint> = xs.mapIndexed { i, h -> DailyPoint(date = day(i), sleepHRAvg = h) }
    private fun trendOf(xs: List<Double?>, window: Int): Trend? =
        TrendsEngine.trend(hr(*xs.toTypedArray()), window = window) { it.sleepHRAvg }
    private val noAverages = TrendsEngine.rollingAverages(emptyList(), window = 7)

    @Test
    fun aNegativeWindowTakesNoDays() {
        // Upstream traps on every one of these ("Can't take a suffix of negative length from a
        // collection", exit 133) — rollingAverages and sleepRegularity even on an empty list. Here a
        // negative window is the nearest valid one, window 0: no days, so every average is nil, the
        // trend is nil and the regularity is nil.
        val points = steps(1_000, 2_000, 3_000) + hr(60.0, 62.0)
        val bedtimes = listOf(1_320, 1_330, 1_310)
        for (w in (-1_000..-1) + Int.MIN_VALUE) {
            assertEquals(noAverages, TrendsEngine.rollingAverages(points, window = w), "rollingAverages window $w")
            assertEquals(noAverages, TrendsEngine.rollingAverages(emptyList(), window = w), "empty rollingAverages window $w")
            assertNull(TrendsEngine.trend(points, window = w) { it.steps?.toDouble() }, "trend window $w")
            assertNull(TrendsEngine.sleepRegularity(bedtimes, window = w), "sleepRegularity window $w")
            assertNull(TrendsEngine.sleepRegularity(emptyList(), window = w), "empty sleepRegularity window $w")
        }
        // The same answers window 0 gives (measured upstream: every average nil, trend nil).
        assertEquals(noAverages, TrendsEngine.rollingAverages(points, window = 0))
        assertNull(TrendsEngine.trend(points, window = 0) { it.steps?.toDouble() })
        assertNull(TrendsEngine.sleepRegularity(bedtimes, window = 0))
        // A single point never reaches the window, as upstream (its `count >= 2` guard returns nil first).
        assertNull(TrendsEngine.trend(steps(5), window = -1) { it.steps?.toDouble() })
    }

    @Test
    fun zeroOneAndHugeWindowsFollowUpstream() {
        // Measured upstream: window 0 → every average nil; Int.max → the whole list (1.5 for [1, 2]).
        val w0 = TrendsEngine.rollingAverages(steps(1) + hr(70.0), window = 0)
        assertNull(w0.steps)
        assertNull(w0.sleepHRAvg)
        assertEquals(1.5, TrendsEngine.rollingAverages(steps(1, 2), window = Int.MAX_VALUE).steps)
        // Regularity of [1, 2, 3]: window 1 → nil, 2 → 99, Int.max → 98 (measured).
        assertNull(TrendsEngine.sleepRegularity(listOf(1, 2, 3), window = 1))
        assertEquals(99, TrendsEngine.sleepRegularity(listOf(1, 2, 3), window = 2))
        assertEquals(98, TrendsEngine.sleepRegularity(listOf(1, 2, 3), window = Int.MAX_VALUE))
        // Trend: three points against a 7-day or Int.max window leave no prior days → nil (measured).
        assertNull(trendOf(listOf(1.0, 2.0, 3.0), 7))
        assertNull(trendOf(listOf(1.0, 2.0, 3.0), Int.MAX_VALUE))
        // A huge window over a long list costs only the list: 200 000 days at Int.max average every one.
        val many = (0 until 200_000).map { DailyPoint(date = day(it), steps = 1) }
        assertEquals(1.0, TrendsEngine.rollingAverages(many, window = Int.MAX_VALUE).steps)
    }

    @Test
    fun countsAreSummedIn64Bits() {
        // Upstream's Int is 64-bit: [Int32.max, Int32.max, Int32.min] averages 715827882.0 (measured),
        // and only a sum past 64 bits traps there (measured: [Int.max, 1], exit 133). The port's counts
        // are Kotlin Ints, so a 64-bit sum can never overflow; a 32-bit sum would wrap.
        assertEquals(715_827_882.0, TrendsEngine.rollingAverages(steps(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE)).steps)
        val maxes = (0 until 7).map {
            DailyPoint(date = day(it), steps = Int.MAX_VALUE, sleepMinutes = Int.MAX_VALUE, sleepScore = Int.MAX_VALUE, stressScore = Int.MAX_VALUE)
        }
        val avg = TrendsEngine.rollingAverages(maxes)
        assertEquals(2_147_483_647.0, avg.steps)
        assertEquals(2_147_483_647.0, avg.sleepMinutes)
        assertEquals(2_147_483_647.0, avg.sleepScore)
        assertEquals(2_147_483_647.0, avg.stressScore)
        // Negative counts: steps and sleep minutes are averaged as they are (measured: [-5, 3] → -1.0);
        // a negative score is "not computed" and dropped, as a zero is (measured: [-4, 0] → nil).
        assertEquals(-1.0, TrendsEngine.rollingAverages(steps(-5, 3)).steps)
        val scores = listOf(-4, 0).mapIndexed { i, s -> DailyPoint(date = day(i), sleepScore = s) }
        assertNull(TrendsEngine.rollingAverages(scores).sleepScore)
    }

    @Test
    fun unreadableAndExtremeValuesFollowUpstream() {
        // rollingAverages (measured): HR [NaN, +Inf, -Inf, -0.0, 0, 29, 29.000000001, 1e308, 1e308] → +Inf
        // (the > 29 guard drops NaN, -Inf, ±0 and 29); [1e308, 1e308] → +Inf (the sum overflows, as
        // upstream's left fold from 0); [NaN, 60] → 60.
        val hostile = hr(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.0, 0.0, 29.0, 29.000000001, 1e308, 1e308)
        assertEquals(Double.POSITIVE_INFINITY, TrendsEngine.rollingAverages(hostile, window = 20).sleepHRAvg)
        assertEquals(Double.POSITIVE_INFINITY, TrendsEngine.rollingAverages(hr(1e308, 1e308)).sleepHRAvg)
        assertEquals(60.0, TrendsEngine.rollingAverages(hr(Double.NaN, 60.0)).sleepHRAvg)
        // -0.0 and NaN fail every "> 0" filter: a day of only those is no data.
        val zeroes = listOf(-0.0, Double.NaN).mapIndexed { i, v -> DailyPoint(date = day(i), skinTempC = v, sleepHRVAvg = v, distanceM = v) }
        val z = TrendsEngine.rollingAverages(zeroes)
        assertNull(z.skinTempC)
        assertNull(z.sleepHRVAvg)
        assertNull(z.distanceM)

        // trend, window 1 (measured): a NaN mean on either side is flat; +Inf recent over a finite
        // prior is up; +Inf prior, +Inf both and -Inf prior are flat; a zero prior (either sign) is
        // flat; negative priors compare by magnitude ([-10, -5] up, [-10, -20] down); a 1e308 sum that
        // overflows on both sides is flat.
        val nan = Double.NaN
        val inf = Double.POSITIVE_INFINITY
        assertEquals(Trend.FLAT, trendOf(listOf(nan, 5.0), 1))
        assertEquals(Trend.FLAT, trendOf(listOf(5.0, nan), 1))
        assertEquals(Trend.UP, trendOf(listOf(5.0, inf), 1))
        assertEquals(Trend.FLAT, trendOf(listOf(inf, 5.0), 1))
        assertEquals(Trend.FLAT, trendOf(listOf(inf, inf), 1))
        assertEquals(Trend.FLAT, trendOf(listOf(-inf, 5.0), 1))
        assertEquals(Trend.FLAT, trendOf(listOf(-0.0, 5.0), 1))
        assertEquals(Trend.FLAT, trendOf(listOf(0.0, -5.0), 1))
        assertEquals(Trend.UP, trendOf(listOf(-10.0, -5.0), 1))
        assertEquals(Trend.DOWN, trendOf(listOf(-10.0, -20.0), 1))
        assertEquals(Trend.FLAT, trendOf(listOf(1e308, 1e308, -1e308, 1e308), 2))
    }

    @Test
    fun bedtimesOutsideTheDayAndDegenerateSpreadsFollowUpstream() {
        // Stored minutes outside 0…1439 land on the circle where their angle puts them (measured):
        // -60 is 23:00, so [-60, 1380] is perfectly regular; [-1, 1439, 2879] is one minute apart → 99.
        assertEquals(100, TrendsEngine.sleepRegularity(listOf(-60, 1_380)))
        assertEquals(99, TrendsEngine.sleepRegularity(listOf(-1, 1_439, 2_879)))
        assertEquals(100, TrendsEngine.sleepRegularity(listOf(Int.MAX_VALUE, Int.MAX_VALUE)))
        assertEquals(0, TrendsEngine.sleepRegularity(listOf(Int.MIN_VALUE, Int.MAX_VALUE)))
        // Opposite bedtimes cancel: R falls below 1e-9 and the spread is π radians → 0 (measured).
        assertEquals(0, TrendsEngine.sleepRegularity(listOf(0, 720)))
        assertEquals(0, TrendsEngine.sleepRegularity(listOf(0, 360, 720, 1_080)))
        // The score falls with the spread (measured): 30 min → 74, 60 → 49, 90 → 24, 120 → 0.
        assertEquals(listOf(74, 49, 24, 0), listOf(30, 60, 90, 120).map { TrendsEngine.sleepRegularity(listOf(0, it)) })
        assertNull(TrendsEngine.sleepRegularity(emptyList()))
        assertNull(TrendsEngine.sleepRegularity(listOf(5)))

        // Property: whatever the minutes and the window, the answer is nil or a score in 0…100.
        val rng = Random(0x7E3D5L)
        var scored = 0
        repeat(20_000) {
            val n = rng.nextInt(12)
            val minutes = List(n) { if (rng.nextInt(4) == 0) rng.nextInt() else rng.nextInt(1_440) }
            val window = rng.nextInt(16) - 4
            val score = TrendsEngine.sleepRegularity(minutes, window = window)
            if (score != null) {
                assertTrue(score in 0..100, "$minutes window $window → $score")
                scored++
            }
        }
        assertTrue(scored > 5_000, "the property must see real scores, saw $scored")
    }

    @Test
    fun daysAreTakenByPositionNotByDate() {
        // Upstream takes the trailing `window` ELEMENTS (suffix / dropLast); a date is a label. Dates
        // reversed, duplicated or far in the future change nothing.
        val far = Instant.parse("+100000-01-01T00:00:00Z")
        val points = listOf(
            DailyPoint(date = far, steps = 100),
            DailyPoint(date = day(5), steps = 200),
            DailyPoint(date = day(5), steps = 300),
            DailyPoint(date = Instant.EPOCH, steps = 400),
        )
        assertEquals(350.0, TrendsEngine.rollingAverages(points, window = 2).steps)
        assertEquals(Trend.UP, TrendsEngine.trend(points, window = 2) { it.steps?.toDouble() })
        val relabelled = points.mapIndexed { i, p -> p.copy(date = day(1_000 - i)) }
        assertEquals(TrendsEngine.rollingAverages(points, window = 2), TrendsEngine.rollingAverages(relabelled, window = 2))
        assertEquals(TrendsEngine.trend(points, window = 2) { it.steps?.toDouble() }, TrendsEngine.trend(relabelled, window = 2) { it.steps?.toDouble() })
    }

    @Test
    fun theRefreshPolicyHandlesBackwardAndExtremeClocks() {
        val t0 = Instant.ofEpochSecond(1_786_400_000)
        // A clock behind the last load is due (upstream's own rule, and measured with
        // `.distantFuture` as the last load); a load in the far past is due.
        assertTrue(TrendsRefreshPolicy.shouldReload(Reason.APPEARED, lastLoadedAt = t0.plusSeconds(86_400L * 365_000), now = t0))
        assertTrue(TrendsRefreshPolicy.shouldReload(Reason.FOREGROUNDED, lastLoadedAt = t0.minusSeconds(86_400L * 365_000), now = t0))
        // The ends of Instant's range in either place never throw.
        for (reason in Reason.entries) {
            assertTrue(TrendsRefreshPolicy.shouldReload(reason, lastLoadedAt = Instant.MAX, now = Instant.MIN), "$reason")
            assertTrue(TrendsRefreshPolicy.shouldReload(reason, lastLoadedAt = Instant.MIN, now = Instant.MAX), "$reason")
            assertEquals(reason == Reason.SYNC_FINISHED, TrendsRefreshPolicy.shouldReload(reason, lastLoadedAt = Instant.MAX, now = Instant.MAX), "$reason")
        }
    }

    @Test
    fun theRefreshPolicyIsExactToTheNanosecond() {
        // Upstream subtracts two Date doubles, which at 2026 dates resolve about 0.1 µs: measured at
        // t0, `now = t0 - 1e-9 s` is the same Date as t0, so upstream says not due. Here the elapsed
        // time is an exact Instant difference, so one nanosecond before the last load is a backward
        // clock and due (a kept difference, recorded in PORTING.md). 59.9999999 s is not due in both.
        val t0 = Instant.ofEpochSecond(1_786_400_000)
        assertTrue(TrendsRefreshPolicy.shouldReload(Reason.APPEARED, lastLoadedAt = t0, now = t0.minusNanos(1)))
        assertFalse(TrendsRefreshPolicy.shouldReload(Reason.APPEARED, lastLoadedAt = t0, now = t0.plusNanos(59_999_999_900)))
        assertFalse(TrendsRefreshPolicy.shouldReload(Reason.APPEARED, lastLoadedAt = t0, now = t0.plus(TrendsRefreshPolicy.MIN_INTERVAL).minusNanos(1)))
        assertTrue(TrendsRefreshPolicy.shouldReload(Reason.APPEARED, lastLoadedAt = t0, now = t0.plus(TrendsRefreshPolicy.MIN_INTERVAL)))
    }
}
