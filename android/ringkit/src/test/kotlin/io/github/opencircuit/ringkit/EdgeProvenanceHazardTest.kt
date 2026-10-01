package io.github.opencircuit.ringkit

import java.time.Instant
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kotlin-only hostile-input checks for the two edge classifiers (bedtime and wake provenance): the
 * evidence window and tolerance boundaries, measurements on the wrong side of the edge, instants at
 * the ends of `Instant`'s range, unusable walk limits and thresholds, and a week-long run. Kept out of
 * the upstream-port classes so their counts stay exact.
 *
 * Every expected value was measured on upstream's pinned Swift build. Where a case needs an instant
 * Swift's `Date` cannot hold exactly (it is a `Double`), the case says so.
 */
class EdgeProvenanceHazardTest {

    private fun s(epoch: Long): Instant = Instant.ofEpochSecond(epoch)

    // MARK: bedtime

    private val bed = s(1_786_602_978)

    @Test
    fun bedtimeEvidenceWindowAndToleranceBoundariesAsUpstream() {
        fun v(last: Instant?, earliest: Instant?) =
            BedtimeProvenance.classify(inBedStart = bed, lastMeasurementBefore = last, earliestRetainedMeasurement = earliest)
        assertEquals(BedtimeProvenance.Verdict.NoPriorMeasurement, v(null, bed.minusSeconds(1800)), "retention reaching exactly 30 min back is evidence")
        assertEquals(BedtimeProvenance.Verdict.Unknown, v(null, bed), "retention starting at the edge proves nothing")
        assertEquals(BedtimeProvenance.Verdict.Unknown, v(null, bed.plusSeconds(60)), "retention starting after the edge proves nothing")
        assertEquals(BedtimeProvenance.Verdict.Witnessed, v(bed.minusMillis(500), null), "half a second before the edge is continuous")
        assertEquals(BedtimeProvenance.Verdict.Unknown, v(s(10_000_000_000_000_000), s(-10_000_000_000_000_000)), "a far-future 'before' is no evidence")
        // A predecessor older than the oldest retained row is still a gap (the store never checks this).
        assertEquals(BedtimeProvenance.Verdict.ResumedAfterGap(1000.0), v(bed.minusSeconds(1000), bed.minusSeconds(10)))
        // One millisecond past the tolerance is a gap. Upstream measured 300.00099992752075 (its Date
        // is a Double, |Δ| 7e-8 s); an Instant holds the millisecond exactly.
        assertEquals(BedtimeProvenance.Verdict.ResumedAfterGap(300.001), v(bed.minusMillis(300_001), null))
    }

    @Test
    fun bedtimeAtTheEndsOfTimeNeverThrows() {
        // Upstream measured with ±1e16 s: a 2e16 s gap; an edge 100 s after the oldest row is unknown,
        // 1800 s after it is evidence.
        assertEquals(
            BedtimeProvenance.Verdict.ResumedAfterGap(2e16),
            BedtimeProvenance.classify(s(10_000_000_000_000_000), s(-10_000_000_000_000_000), s(-10_000_000_000_000_000)),
        )
        assertEquals(BedtimeProvenance.Verdict.Unknown, BedtimeProvenance.classify(s(-10_000_000_000_000_000 + 100), null, s(-10_000_000_000_000_000)))
        assertEquals(BedtimeProvenance.Verdict.NoPriorMeasurement, BedtimeProvenance.classify(s(-10_000_000_000_000_000 + 1800), null, s(-10_000_000_000_000_000)))
        // No upstream counterpart (a Date never overflows): "the edge minus 30 minutes" would leave
        // Instant's range here; the window is compared as a gap instead.
        assertEquals(BedtimeProvenance.Verdict.Unknown, BedtimeProvenance.classify(Instant.MIN.plusSeconds(100), null, Instant.MIN))
        assertEquals(BedtimeProvenance.Verdict.NoPriorMeasurement, BedtimeProvenance.classify(Instant.MAX, null, Instant.MIN))
        assertEquals(BedtimeProvenance.Verdict.Unknown, BedtimeProvenance.classify(Instant.MIN, null, Instant.MIN))
    }

    // MARK: wake

    private val wake = s(1_787_013_422)
    private val deep = wake.minusSeconds(30L * 86_400)
    private fun t(offset: Long): Instant = wake.plusSeconds(offset)

    @Test
    fun measurementsAtOrBeforeTheEdgeAreDroppedFromTheWalk() {
        assertEquals(
            WakeProvenance.Verdict.Unknown,
            WakeProvenance.classify(inBedEnd = wake, measurementsAfter = listOf(t(-60), t(0), wake.minusMillis(1)), earliestRetainedMeasurement = deep),
        )
        assertEquals(
            WakeProvenance.Stoppage(WakeProvenance.Verdict.StoppedThenResumed(14_616.0), wake),
            WakeProvenance.stoppage(inBedEnd = wake, measurementsAfter = listOf(t(-60), t(0), t(14_616)), earliestRetainedMeasurement = deep),
        )
    }

    @Test
    fun unusableWalkLimitsSwitchTheWalkOffAndAnInfiniteOneWalksToTheFirstHole() {
        val series = listOf(t(150), t(300), t(450), t(600), t(750), t(900), t(900 + 14_400))
        fun st(limit: Double) = WakeProvenance.stoppage(inBedEnd = wake, measurementsAfter = series, earliestRetainedMeasurement = deep, resumeRunLimit = limit)
        val witnessed = WakeProvenance.Stoppage(WakeProvenance.Verdict.Witnessed, null)
        for (limit in listOf(Double.NaN, -1.0, 0.0, 300.0, 600.0)) {
            assertEquals(witnessed, st(limit), "limit $limit")
        }
        assertEquals(WakeProvenance.Stoppage(WakeProvenance.Verdict.StoppedThenResumed(14_400.0), t(900)), st(Double.POSITIVE_INFINITY))
    }

    @Test
    fun walkBoundariesAsUpstream() {
        fun st(vararg offsets: Long) = WakeProvenance.stoppage(inBedEnd = wake, measurementsAfter = offsets.map { t(it) }, earliestRetainedMeasurement = deep)
        // A step of exactly the tolerance is continuous; the run then passes the bound and is witnessed.
        assertEquals(WakeProvenance.Stoppage(WakeProvenance.Verdict.Witnessed, null), st(300, 600, 900 + 14_400))
        // One second over the tolerance on the second step is a hole that began at the first record.
        assertEquals(WakeProvenance.Stoppage(WakeProvenance.Verdict.StoppedThenResumed(301.0), t(150)), st(150, 451))
        // Duplicates of the first record are zero steps.
        assertEquals(WakeProvenance.Stoppage(WakeProvenance.Verdict.StoppedThenResumed(4850.0), t(150)), st(150, 150, 150, 5000))
        // A run reaching EXACTLY the bound is still this night's; one second further is daytime.
        assertEquals(WakeProvenance.Stoppage(WakeProvenance.Verdict.StoppedThenResumed(4700.0), t(300)), st(150, 300, 5000))
        assertEquals(WakeProvenance.Stoppage(WakeProvenance.Verdict.Witnessed, null), st(150, 301, 5000))
    }

    @Test
    fun retentionAndFarFutureEdgesAsUpstream() {
        assertEquals(
            WakeProvenance.Stoppage(WakeProvenance.Verdict.Unknown, null),
            WakeProvenance.stoppage(inBedEnd = wake, measurementsAfter = listOf(t(30), t(14_616)), earliestRetainedMeasurement = wake.plusSeconds(1)),
        )
        assertEquals(
            WakeProvenance.Stoppage(WakeProvenance.Verdict.StoppedThenResumed(4970.0), t(30)),
            WakeProvenance.stoppage(inBedEnd = wake, measurementsAfter = listOf(t(30), t(5000)), earliestRetainedMeasurement = null),
        )
        val far = s(10_000_000_000_000_000)
        assertEquals(
            WakeProvenance.Stoppage(WakeProvenance.Verdict.StoppedThenResumed(9.999998212986578e15), wake),
            WakeProvenance.stoppage(inBedEnd = wake, measurementsAfter = listOf(far), earliestRetainedMeasurement = deep),
        )
        assertEquals(
            WakeProvenance.Stoppage(WakeProvenance.Verdict.StoppedThenResumed(9.999998212986548e15), t(30)),
            WakeProvenance.stoppage(inBedEnd = wake, measurementsAfter = listOf(t(30), far), earliestRetainedMeasurement = deep),
        )
        // No upstream counterpart: the ends of Instant's range never throw.
        assertEquals(
            WakeProvenance.Verdict.StoppedThenResumed(SleepStaging.seconds(java.time.Duration.between(Instant.MIN, Instant.MAX))),
            WakeProvenance.classify(inBedEnd = Instant.MIN, firstMeasurementAfter = Instant.MAX, earliestRetainedMeasurement = Instant.MIN),
        )
    }

    @Test
    fun materialThresholdEdgesAsUpstream() {
        assertFalse(WakeProvenance.isMaterial(WakeProvenance.Verdict.StoppedThenResumed(14_515.0), threshold = Double.NaN), "a NaN threshold never fires")
        assertTrue(WakeProvenance.isMaterial(WakeProvenance.Verdict.StoppedThenResumed(301.0), threshold = -1.0))
        assertFalse(WakeProvenance.isMaterial(WakeProvenance.Verdict.StoppedThenResumed(3600.0)), "strictly longer than an hour")
        assertTrue(WakeProvenance.isMaterial(WakeProvenance.Verdict.StoppedThenResumed(3600.001)))
        assertFalse(WakeProvenance.isMaterial(WakeProvenance.Verdict.Witnessed, threshold = Double.NEGATIVE_INFINITY))
    }

    @Test
    @Timeout(20)
    fun aWeekLongRunFinishesAndIsOrderInsensitive() {
        val week = ArrayList<Instant>()
        var off = 150L
        while (off <= 7L * 86_400) { week += t(off); off += 150 }
        week += t(7L * 86_400 + 3 * 3600)
        assertEquals(4033, week.size)
        val reversed = week.reversed()
        // Upstream measured: with no bound the walk reaches the daytime disconnect a week later; with the
        // shipped bound the night is witnessed.
        assertEquals(
            WakeProvenance.Stoppage(WakeProvenance.Verdict.StoppedThenResumed(10_800.0), t(7L * 86_400)),
            WakeProvenance.stoppage(inBedEnd = wake, measurementsAfter = reversed, earliestRetainedMeasurement = deep, resumeRunLimit = Double.POSITIVE_INFINITY),
        )
        val shipped = WakeProvenance.stoppage(inBedEnd = wake, measurementsAfter = reversed, earliestRetainedMeasurement = deep)
        assertEquals(WakeProvenance.Verdict.Witnessed, shipped.verdict)
        assertNull(shipped.silenceBegan)
    }
}
