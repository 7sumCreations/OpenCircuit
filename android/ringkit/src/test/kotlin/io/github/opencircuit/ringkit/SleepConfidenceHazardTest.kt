package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Kotlin-only hostile-input checks for the sleep-confidence classifier (`classify` and `assess`):
 * non-finite and degenerate totals, non-finite and signed-zero thresholds, duplicated, unsorted and
 * partial measurement series, a reversed in-bed window, and instants at the ends of `Instant`'s range.
 * Kept out of the upstream-port classes so their counts stay exact.
 *
 * Every expected value was measured on upstream's pinned Swift build. Where a case needs an instant
 * Swift's `Date` cannot hold exactly (it is a `Double`), the case says so.
 */
class SleepConfidenceHazardTest {

    private val end: Instant = Instant.ofEpochSecond(1_787_013_422)
    private val start: Instant = end.minusMillis(15_157_002)
    private val dlh = SleepConfidence.Level.DURATION_LIKELY_HIGH
    private val normal = SleepConfidence.Level.NORMAL

    private fun coverage(before: Long?, after: Long?, series: List<Long> = emptyList(), earliestDaysBack: Long? = 14) =
        SleepConfidence.Coverage(
            inBedStart = start,
            inBedEnd = end,
            lastMeasurementBeforeStart = before?.let { start.minusSeconds(it) },
            firstMeasurementAfterEnd = after?.let { end.plusSeconds(it) },
            measurementsAfterEnd = series.map { end.plusSeconds(it) },
            earliestRetainedMeasurement = earliestDaysBack?.let { start.minusSeconds(it * 86_400) },
        )

    /** A 579-minute night at 98.8 % — `classify` alone says "duration likely high". */
    private fun assess(coverage: SleepConfidence.Coverage?, cut: Double = WakeProvenance.MATERIAL_GAP_SECONDS) =
        SleepConfidence.assess(asleep = 34_320.0, inBed = 34_740.0, coverage = coverage, materialGapSeconds = cut)

    private fun after(from: Instant, s: Double) = SleepConfidence.Reason.NoRecordingAfterWake(from, s)
    private fun before(until: Instant, s: Double) = SleepConfidence.Reason.NoRecordingBeforeBedtime(until, s)

    @Test
    fun classifyOnNonFiniteAndDegenerateTotalsAsUpstream() {
        val inf = Double.POSITIVE_INFINITY
        val cases = listOf(
            Triple(Double.NaN, 20_000.0, normal), Triple(20_000.0, Double.NaN, normal),
            Triple(inf, 20_000.0, dlh), Triple(20_000.0, inf, normal), Triple(inf, inf, normal),
            Triple(-inf, 20_000.0, normal), Triple(19_001.0, 20_000.0, dlh), Triple(19_000.0, 20_000.0, normal),
            Triple(18_000.0, 18_000.0, dlh), Triple(17_999.0, 17_999.0, normal), Triple(-1.0, 18_000.0, normal),
            Triple(36_000.0, 18_000.0, dlh), Triple(0.0, 0.0, normal), Triple(-5.0, -1.0, normal),
            Triple(1e308, 18_000.0, dlh), Triple(Double.MIN_VALUE, 18_000.0, normal), Triple(18_000 * 0.95, 18_000.0, normal),
        )
        for ((asleep, inBed, expected) in cases) {
            assertEquals(expected, SleepConfidence.classify(asleep, inBed), "classify($asleep, $inBed)")
        }
    }

    @Test
    fun nonFiniteAndSignedZeroThresholdsAsUpstream() {
        val both = coverage(before = 14_478, after = 14_515)
        val back = after(end, 14_515.0)
        val front = before(start, 14_478.0)
        val cases = listOf(
            Double.NaN to emptyList(), // a NaN cut is never exceeded — and the stop still silences the duration claim
            Double.NEGATIVE_INFINITY to listOf(back, front),
            -1.0 to listOf(back, front),
            0.0 to listOf(back, front),
            -0.0 to listOf(back, front),
            14_515.0 to emptyList(), // strictly greater than the cut
            14_514.999 to listOf<SleepConfidence.Reason>(back),
            Double.POSITIVE_INFINITY to emptyList(),
        )
        for ((cut, reasons) in cases) {
            val a = assess(both, cut)
            assertEquals(reasons, a.reasons, "cut $cut")
            assertEquals(dlh, a.level, "cut $cut: the legacy verdict never moves")
            assertEquals(BedtimeProvenance.Verdict.ResumedAfterGap(14_478.0), a.bedtime)
            assertEquals(WakeProvenance.Verdict.StoppedThenResumed(14_515.0), a.wake)
            assertEquals(cut.toRawBits(), a.materialGapSeconds.toRawBits(), "the cut travels with the verdict, bit for bit")
        }
    }

    @Test
    fun theAfterSeriesIsUnionedDeduplicatedAndOrderedAsUpstream() {
        // Two copies of one record a minute after the edge: witnessed, so the duration claim ships.
        val dup = SleepConfidence.Coverage(start, end, null, null, listOf(end.plusSeconds(60), end.plusSeconds(60)), null)
        val d = assess(dup)
        assertEquals(listOf<SleepConfidence.Reason>(SleepConfidence.Reason.DurationLikelyHigh), d.reasons)
        assertEquals(BedtimeProvenance.Verdict.Unknown, d.bedtime)
        assertEquals(WakeProvenance.Verdict.Witnessed, d.wake)

        // A series missing the first record: the union puts it back, and the walk finds the hole after it.
        assertEquals(listOf<SleepConfidence.Reason>(after(end.plusSeconds(60), 4_940.0)), assess(coverage(150, 60, listOf(5_000))).reasons)
        // A series that stops early: the single instant extends it, so the hole is still reported.
        assertEquals(listOf<SleepConfidence.Reason>(after(end.plusSeconds(60), 14_455.0)), assess(coverage(150, 14_515, listOf(60))).reasons)
        // The walk consumes a short run before the hole; order and entries at or before the edge do not matter.
        val walked = listOf<SleepConfidence.Reason>(after(end.plusSeconds(300), 14_400.0))
        assertEquals(walked, assess(coverage(150, null, listOf(150, 300, 14_700))).reasons)
        assertEquals(walked, assess(coverage(150, null, listOf(14_700, -60, 0, 150, 300))).reasons)
    }

    @Test
    fun aReversedInBedWindowAsUpstream() {
        val reversed = SleepConfidence.Coverage(
            inBedStart = end, inBedEnd = start,
            lastMeasurementBeforeStart = end.minusSeconds(7_200), firstMeasurementAfterEnd = start.plusSeconds(7_200),
            measurementsAfterEnd = emptyList(), earliestRetainedMeasurement = null,
        )
        val a = SleepConfidence.assess(asleep = 100.0, inBed = -15_157.0, coverage = reversed)
        assertEquals(normal, a.level)
        assertEquals(listOf(after(start, 7_200.0), before(end, 7_200.0)), a.reasons)
    }

    @Test
    fun instantsAtTheEndsOfTimeNeverThrow() {
        // Measured upstream at ±3e16 s.
        val farPast = Instant.ofEpochSecond(-30_000_000_000_000_000)
        val farFuture = Instant.ofEpochSecond(30_000_000_000_000_000)
        val far = SleepConfidence.Coverage(farPast, farPast.plusSeconds(3_600), null, farFuture, emptyList(), null)
        val a = SleepConfidence.assess(asleep = 3_600.0, inBed = 3_600.0, coverage = far)
        assertEquals(normal, a.level)
        assertEquals(listOf<SleepConfidence.Reason>(after(farPast.plusSeconds(3_600), 5.99999999999964e16)), a.reasons)
        assertEquals(BedtimeProvenance.Verdict.Unknown, a.bedtime)

        // The whole range of `Instant` (a `Date` cannot hold these exactly): still one finite gap, no throw.
        val whole = SleepConfidence.Coverage(Instant.MIN, Instant.MIN, null, Instant.MAX, listOf(Instant.MAX, Instant.MIN), Instant.MIN)
        val w = SleepConfidence.assess(asleep = 0.0, inBed = 0.0, coverage = whole, materialGapSeconds = 0.0)
        assertEquals(listOf<SleepConfidence.Reason>(after(Instant.MIN, 6.31139040316224e16)), w.reasons)
        assertTrue(w.hasAcquisitionReason)
    }
}
