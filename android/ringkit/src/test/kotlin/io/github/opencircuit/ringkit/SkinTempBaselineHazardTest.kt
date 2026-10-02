package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.SkinTempBaseline.DeviationBand
import io.github.opencircuit.ringkit.SkinTempBaseline.NightlyTemp
import io.github.opencircuit.ringkit.SkinTempBaseline.NightlyVerdict
import java.time.Instant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kotlin-only hostile-input checks for the sleeping skin-temperature baseline: what the upstream
 * vectors never feed in. Readings are decoded from the ring's live descriptor and nightly means are
 * STORED per night, so here they arrive NaN or infinite, unsorted, duplicated, outside the window or
 * exactly on its end; windows arrive empty, sub-second or not a whole number of hours; the baseline
 * window and minimums arrive zero or negative. Kept out of the upstream-port class so its count stays
 * exact.
 *
 * The rule under test: an unreadable (NaN or infinite) reading or night is a missing one — it never
 * publishes a NaN nightly mean, never makes a NaN baseline that bands every night "normal", and never
 * raises a flag — and a night's coverage is a fraction of its hours, never more. Every upstream
 * outcome quoted below was measured on the pinned Swift build (Swift 6.3.2); where Kotlin deliberately
 * differs the test says so, and `PORTING.md` records why.
 */
class SkinTempBaselineHazardTest {

    private val nan = Double.NaN
    private val inf = Double.POSITIVE_INFINITY
    private val t0: Instant = Instant.ofEpochSecond(1_700_000_000)
    private fun at(seconds: Double): Instant = t0.plusMillis(Math.round(seconds * 1000))
    private fun samples(seconds: List<Double>, celsius: Double = 34.0) = seconds.map { TemperatureSample(at(it), celsius) }
    private fun window(seconds: Double) = DateInterval(t0, at(seconds))
    private fun night(k: Int, c: Double) = NightlyTemp(t0.plusSeconds(k * 86_400L), c)
    private val tenHours = window(36_000.0)
    private val wellSpread = samples((0 until 60).map { it * 600.0 }, 35.0)

    @Test
    fun coverageFoldsAReadingOnTheWindowsEndIntoItsLastHour() {
        // Upstream gives the end instant an hour bucket of its own: measured, hourly readings plus one
        // at the end of a 10 h window cover 1.1 of the night, readings at both ends of a 1 h window
        // cover 2.0, and a 3 h window read only in its last hour and at its end covers 2/3 — so that
        // night PUBLISHES. The end belongs to the last hour, as the closed window says.
        assertEquals(1.0, SkinTempBaseline.coverage(samples((0..10).map { it * 3600.0 }), tenHours))
        assertEquals(1.0, SkinTempBaseline.coverage(samples(listOf(0.0, 3600.0)), window(3600.0)))
        val lastHourAndEnd = samples((0 until 9).map { 7200 + it * 400.0 } + 10_800.0)
        assertEquals(1.0 / 3, SkinTempBaseline.coverage(lastHourAndEnd, window(10_800.0)))
        assertEquals(NightlyVerdict.RejectedCoverage(1.0 / 3), SkinTempBaseline.nightlyVerdict(lastHourAndEnd, window(10_800.0)))
        // Unchanged (measured): the end reading alone, a zero-length window, and the end of a window
        // that is not a whole number of hours (its last bucket is partial).
        assertEquals(0.1, SkinTempBaseline.coverage(samples(listOf(36_000.0)), tenHours))
        assertEquals(1.0, SkinTempBaseline.coverage(samples(listOf(0.0)), window(0.0)))
        assertEquals(0.0, SkinTempBaseline.coverage(emptyList(), window(0.0)))
        assertEquals(1.0, SkinTempBaseline.coverage(samples(listOf(0.0, 3600.0, 7200.0, 9000.0)), window(9000.0)))
        assertEquals(1.0, SkinTempBaseline.coverage(samples(listOf(0.0, 600.0, 1200.0, 7200.0)), window(7200.0)))
        // Nothing else changes: over a seeded sweep of windows and readings, coverage stays in 0 … 1 and
        // equals the share of hour buckets holding a reading, the end instant in the last one.
        val rng = Random(0x434f_5652)
        repeat(400) { k ->
            val length = listOf(0.0, 0.5, 3600.0, 36_000.0, rng.nextInt(1, 50_000) * 1.0)[rng.nextInt(5)]
            val w = window(length)
            val readings = List(rng.nextInt(0, 40)) { rng.nextInt(-2000, (length + 2000).toInt() + 1) * 1.0 } + if (rng.nextBoolean()) listOf(length) else emptyList()
            val c = SkinTempBaseline.coverage(samples(readings), w)
            val buckets = maxOf(1.0, Math.ceil(length / 3600))
            val hit = readings.filter { it in 0.0..length }.map { minOf(Math.floor(it / 3600), buckets - 1) }.toSet()
            assertTrue(c in 0.0..1.0, "case $k: $c")
            assertEquals(hit.size / buckets, c, "case $k")
        }
    }

    @Test
    fun coverageOfUnsortedDuplicatedOutOfWindowAndSubSecondReadings() {
        assertEquals(0.1, SkinTempBaseline.coverage(samples(listOf(0.0, 0.0, 0.0, 60.0, 60.0)), tenHours), "duplicates count once")
        assertEquals(0.3, SkinTempBaseline.coverage(samples(listOf(30_000.0, 0.0, 18_000.0)), tenHours), "order does not matter")
        assertEquals(0.0, SkinTempBaseline.coverage(samples(listOf(-1.0, -3600.0, 36_000.25)), tenHours), "outside the closed window")
        assertEquals(1.0, SkinTempBaseline.coverage(samples(listOf(0.25)), window(0.5)), "a sub-second window is one bucket")
        // Coverage reads only the times: a reading's temperature does not enter it (measured 1.0 with a NaN reading).
        assertEquals(1.0, SkinTempBaseline.coverage(samples(listOf(0.0), nan) + samples(listOf(3600.0)), window(7200.0)))
        // A window cannot end before it starts: the shared interval type refuses to exist.
        assertFailsWith<IllegalArgumentException> { DateInterval(t0, t0.minusSeconds(1)) }
    }

    @Test
    fun nightlyMeanCountsOnlyReadableReadings() {
        // Upstream publishes NaN or +Inf as the night's mean (measured: ten NaN -> NaN, nine readings and
        // a NaN -> NaN, nine and a +Inf -> +Inf, +Inf and -Inf among ten -> NaN).
        assertNull(SkinTempBaseline.nightlyMean(List(10) { nan }))
        assertNull(SkinTempBaseline.nightlyMean(List(9) { 34.0 } + nan), "nine readable readings are below the floor")
        assertNull(SkinTempBaseline.nightlyMean(List(9) { 34.0 } + inf))
        assertNull(SkinTempBaseline.nightlyMean(listOf(inf, -inf) + List(8) { 34.0 }))
        assertEquals(34.0, SkinTempBaseline.nightlyMean(List(10) { 34.0 } + listOf(nan, inf)))
        // The count floor's kill-switch values, as upstream (measured).
        assertNull(SkinTempBaseline.nightlyMean(emptyList(), minSamples = 0))
        assertEquals(33.0, SkinTempBaseline.nightlyMean(listOf(33.0), minSamples = -5))
        assertEquals(33.0, SkinTempBaseline.nightlyMean(listOf(33.0), minSamples = Int.MIN_VALUE))
    }

    @Test
    fun theVerdictCountsOnlyReadableReadings() {
        // Upstream publishes a NaN or infinite nightly mean (measured published(nan), published(inf)),
        // and counts a NaN reading toward the count floor (measured: nine readings and a NaN were
        // judged and REJECTED, which tells the store to clear its stored value).
        assertEquals(NightlyVerdict.Published(35.0), SkinTempBaseline.nightlyVerdict(wellSpread + samples(listOf(100.0), nan), tenHours))
        assertEquals(NightlyVerdict.Published(35.0), SkinTempBaseline.nightlyVerdict(wellSpread + samples(listOf(100.0), inf), tenHours))
        assertEquals(NightlyVerdict.NotMeasured, SkinTempBaseline.nightlyVerdict(samples((0 until 60).map { it * 600.0 }, nan), tenHours))
        val nineAndNaN = samples((0 until 9).map { it * 600.0 }) + samples(listOf(5400.0), nan)
        assertEquals(NightlyVerdict.NotMeasured, SkinTempBaseline.nightlyVerdict(nineAndNaN, tenHours))
        assertNull(SkinTempBaseline.nightlyMean(nineAndNaN, tenHours))
        // Policy values outside their range, as upstream (measured): a NaN or over-1 coverage floor
        // rejects even a fully covered night; a negative floor turns the coverage gate off.
        assertEquals(NightlyVerdict.RejectedCoverage(1.0), SkinTempBaseline.nightlyVerdict(wellSpread, tenHours, minCoverage = nan))
        assertEquals(NightlyVerdict.RejectedCoverage(1.0), SkinTempBaseline.nightlyVerdict(wellSpread, tenHours, minCoverage = 1.5))
        assertEquals(NightlyVerdict.Published(34.0), SkinTempBaseline.nightlyVerdict(samples((0 until 10).map { it * 60.0 }), tenHours, minCoverage = -1.0))
        // Exactly the coverage floor (6 of 10 hours) publishes.
        assertEquals(NightlyVerdict.Published(34.0), SkinTempBaseline.nightlyVerdict(samples((0 until 6).map { it * 3600.0 } + List(4) { 0.0 }), tenHours))
    }

    @Test
    fun theBaselineCountsOnlyReadableNights() {
        // Upstream averages an unreadable night in (measured: NaN, +Inf), and a NaN baseline then bands
        // every night "normal" for the next thirty.
        assertNull(SkinTempBaseline.baseline(listOf(night(1, 31.0), night(2, nan), night(3, 32.0))), "two readable nights")
        assertNull(SkinTempBaseline.baseline(listOf(night(1, 31.0), night(2, inf), night(3, 32.0))))
        assertEquals(31.0, SkinTempBaseline.baseline(listOf(night(1, 30.0), night(2, nan), night(3, 31.0), night(4, 32.0))))
        // Unsorted and duplicated nights, as upstream (measured): sorted by night, stable for equal keys.
        assertEquals(31.0, SkinTempBaseline.baseline(listOf(night(3, 30.0), night(1, 20.0), night(5, 32.0), night(4, 31.0), night(2, 20.0)), windowNights = 3))
        assertEquals(32.0, SkinTempBaseline.baseline(listOf(night(1, 30.0), night(1, 32.0), night(2, 31.0), night(3, 33.0)), windowNights = 3))
        assertEquals(31.333333333333332, SkinTempBaseline.baseline(listOf(night(1, 32.0), night(1, 30.0), night(2, 31.0), night(3, 33.0)), windowNights = 3))
    }

    @Test
    fun aBaselineWindowOfZeroOrNegativeNightsHasNoBaseline() {
        val three = listOf(night(1, 31.0), night(2, 31.0), night(3, 32.0))
        // A negative window traps upstream in both baseline and report ("Can't take a suffix of
        // negative length from a collection", measured); an empty trailing window with a minimum of 0
        // averages nothing into NaN (measured). The port answers "no baseline".
        assertNull(SkinTempBaseline.baseline(three, windowNights = -1))
        assertNull(SkinTempBaseline.baseline(three, windowNights = Int.MIN_VALUE))
        assertNull(SkinTempBaseline.report(32.0, three, windowNights = -1).baselineC)
        assertNull(SkinTempBaseline.baseline(three, windowNights = 0))
        assertNull(SkinTempBaseline.baseline(listOf(night(1, 31.0)), windowNights = 0, minNights = 0))
        assertNull(SkinTempBaseline.baseline(emptyList(), minNights = 0))
        // A negative minimum with one night is a baseline of that night, as upstream (measured 31.0).
        assertEquals(31.0, SkinTempBaseline.baseline(listOf(night(1, 31.0)), minNights = -2))
        // Exactly the minimum, and one night short.
        assertEquals(31.333333333333332, SkinTempBaseline.baseline(three))
        assertNull(SkinTempBaseline.baseline(three.drop(1)))
    }

    @Test
    fun theDeviationBandPlacesNoNaNOffset() {
        // Upstream bands a NaN offset "normal" (measured). The port has no band for it; an infinite
        // offset lies beyond the band, as upstream.
        assertNull(SkinTempBaseline.deviationBand(nan))
        assertEquals(DeviationBand.ABNORMAL_RISE, SkinTempBaseline.deviationBand(inf))
        assertEquals(DeviationBand.ABNORMAL_DROP, SkinTempBaseline.deviationBand(-inf))
        assertEquals(DeviationBand.NORMAL, SkinTempBaseline.deviationBand(-0.0))
        assertEquals(DeviationBand.NORMAL, SkinTempBaseline.deviationBand(1.0), "exactly +1 °C is normal")
        assertEquals(DeviationBand.NORMAL, SkinTempBaseline.deviationBand(-1.0), "exactly -1 °C is normal")
        assertEquals(DeviationBand.ABNORMAL_RISE, SkinTempBaseline.deviationBand(Math.nextUp(1.0)))
        assertEquals(DeviationBand.ABNORMAL_DROP, SkinTempBaseline.deviationBand(Math.nextDown(-1.0)))
        // Policy band values, as upstream (measured): a NaN band is never exceeded; a negative one always is.
        assertEquals(DeviationBand.NORMAL, SkinTempBaseline.deviationBand(5.0, normalC = nan))
        assertEquals(DeviationBand.ABNORMAL_RISE, SkinTempBaseline.deviationBand(0.0, normalC = -1.0))
    }

    @Test
    fun anomalyFlagsReadNoUnreadableNight() {
        fun flags(f: SkinTempBaseline.AnomalyFlags) = listOf(f.abnormalRise, f.abnormalDrop, f.fluctuationRise, f.fluctuationDrop)
        val none = listOf(false, false, false, false)
        // An unreadable tonight raises nothing (upstream: +Inf raised an abnormal and a fluctuation rise).
        assertEquals(none, flags(SkinTempBaseline.anomalyFlags(nan, baseline = 34.0, previousNight = 34.0)))
        assertEquals(none, flags(SkinTempBaseline.anomalyFlags(inf, baseline = 34.0, previousNight = 34.0)))
        assertFalse(SkinTempBaseline.anomalyFlags(inf, baseline = 34.0, previousNight = 34.0).any)
        // An unreadable baseline is no baseline: the night-over-night comparison runs ungated, exactly
        // as with none (upstream: a NaN baseline silenced this 4 °C fall; +Inf raised an abnormal drop).
        assertEquals(listOf(false, false, false, true), flags(SkinTempBaseline.anomalyFlags(30.0, baseline = nan, previousNight = 34.0)))
        assertEquals(flags(SkinTempBaseline.anomalyFlags(30.0, baseline = null, previousNight = 34.0)),
            flags(SkinTempBaseline.anomalyFlags(30.0, baseline = nan, previousNight = 34.0)))
        assertEquals(none, flags(SkinTempBaseline.anomalyFlags(34.0, baseline = inf, previousNight = 34.0)))
        // An unreadable previous night is no previous night (upstream: +Inf raised a fluctuation drop).
        assertEquals(none, flags(SkinTempBaseline.anomalyFlags(34.0, baseline = 34.0, previousNight = nan)))
        assertEquals(none, flags(SkinTempBaseline.anomalyFlags(34.0, baseline = null, previousNight = inf)))
        // The thresholds are strict and sit on binary approximations (measured): 34.6 − 34.0 and
        // 34.7 − 34.4 land just above 0.6 and 0.3, so both rise.
        assertEquals(listOf(false, false, true, false), flags(SkinTempBaseline.anomalyFlags(34.6, baseline = null, previousNight = 34.0)))
        assertEquals(listOf(false, false, true, false), flags(SkinTempBaseline.anomalyFlags(34.7, baseline = 34.4, previousNight = 34.0)))
        // Policy values, as upstream (measured): a NaN fluctuation step never fires; a negative one always does.
        assertEquals(none, flags(SkinTempBaseline.anomalyFlags(36.0, baseline = null, previousNight = 34.0, fluctC = nan)))
        assertEquals(listOf(false, false, true, false), flags(SkinTempBaseline.anomalyFlags(34.0, baseline = null, previousNight = 34.0, fluctC = -1.0)))
    }

    @Test
    fun theReportOfAnUnreadableNight() {
        val prior = listOf(night(1, 30.8), night(2, 31.0), night(3, 31.2))
        // Upstream: a NaN tonight gets offset NaN and band "normal"; +Inf an abnormal rise (measured).
        for (tonight in listOf(nan, inf, -inf)) {
            val r = SkinTempBaseline.report(tonight, prior, previousNight = 31.0)
            assertEquals(tonight.toRawBits(), r.nightlyC.toRawBits())
            assertEquals(31.0, assertNotNull(r.baselineC), "tonight $tonight: the baseline still exists")
            assertNull(r.offsetC, "tonight $tonight: no offset")
            assertNull(r.band, "tonight $tonight: no band")
            assertFalse(r.flags.any, "tonight $tonight: no flags")
        }
        // An unreadable prior night is skipped (upstream: NaN baseline, NaN offset, band "normal", no flags).
        val r = SkinTempBaseline.report(32.5, prior + night(4, nan), previousNight = 31.0)
        assertEquals(31.0, r.baselineC)
        assertEquals(1.5, r.offsetC)
        assertEquals(DeviationBand.ABNORMAL_RISE, r.band)
        assertTrue(r.flags.abnormalRise && r.flags.fluctuationRise)
        // No window, no baseline; an unreadable previous night, no flags (measured).
        assertNull(SkinTempBaseline.report(32.5, prior, windowNights = 0).baselineC)
        assertFalse(SkinTempBaseline.report(32.5, emptyList(), previousNight = nan).flags.any)
    }
}
