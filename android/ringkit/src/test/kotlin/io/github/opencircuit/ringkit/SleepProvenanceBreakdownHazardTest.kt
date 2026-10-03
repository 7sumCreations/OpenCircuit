package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kotlin-only hostile-input checks for the provenance breakdown and the health-store partition: empty,
 * reversed, zero-length, duplicated, overlapping, touching and unsorted segment lists; non-finite and
 * negative tuning; exact withholding thresholds; half-minute rounding; and totals too large for
 * upstream's whole-minute conversion. Kept out of the upstream-port classes so their counts stay exact.
 *
 * Every expected value was measured on upstream's pinned Swift build, except where a Kotlin bound
 * replaces an upstream trap; that case says so.
 */
class SleepProvenanceBreakdownHazardTest {

    private val t0: Instant = Instant.ofEpochSecond(1_700_000_000)
    private fun at(m: Double): Instant = t0.plusNanos((m * 60e9).roundToLong())
    private fun seg(a: Double, b: Double, st: SleepStage, p: SleepProvenance = SleepProvenance.MEASURED) = SleepSegment(at(a), at(b), st, p)

    private val core = SleepStage.ASLEEP_CORE
    private val inBed = SleepStage.IN_BED
    private val asserted = SleepProvenance.ASSERTED

    /** Every total, in upstream's print order, so one comparison pins the whole breakdown. */
    private fun totals(b: SleepProvenanceBreakdown): List<Double> = listOf(
        b.totalInBed, b.coveredInBed, b.unknownInBed,
        b.measuredAsleep, b.assertedOverMeasuredAsleep, b.assertedAsleep, b.unknownAsleep,
        b.measuredAwake, b.assertedOverMeasuredAwake, b.assertedAwake, b.unknownAwake,
        b.measuredLight, b.measuredDeep, b.measuredREM, b.assertedLight, b.assertedDeep, b.assertedREM,
        b.longestUnmeasuredGap,
    )

    private fun minutes(vararg m: Long) = SleepProvenanceBreakdown.Minutes(m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8], m[9])

    @Test
    fun anEmptyNightHasNothingToPublishAndIsNotScorable() {
        val b = SleepProvenanceBreakdown(emptyList())
        assertEquals(List(18) { 0.0 }, totals(b))
        assertEquals(0.0, b.coverageFraction)
        assertNull(b.efficiency)
        assertFalse(b.isScorable)
        assertNull(b.withheldReason)
        assertFalse(b.hasAssertedTime)
        assertEquals(minutes(0, 0, 0, 0, 0, 0, 0, 0, 0, 0), b.minutes)
    }

    @Test
    fun reversedAndZeroLengthSegmentsCountAsNothing() {
        val reversed = SleepProvenanceBreakdown(listOf(seg(100.0, 0.0, inBed), seg(100.0, 0.0, core, asserted), seg(0.0, 50.0, core)))
        assertEquals(
            listOf(0.0, 0.0, 0.0, 3000.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 3000.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            totals(reversed),
            "a reversed in-bed layer still counts as the layer: 0 in bed, so nothing is scorable",
        )
        assertNull(reversed.efficiency)
        assertFalse(reversed.isScorable)
        assertFalse(reversed.hasAssertedTime)
        assertEquals(minutes(0, 0, 50, 0, 0, 0, 0, 50, 0, 0), reversed.minutes)

        val zero = SleepProvenanceBreakdown(listOf(seg(10.0, 10.0, inBed), seg(10.0, 10.0, core, asserted)))
        assertEquals(List(18) { 0.0 }, totals(zero))
        assertFalse(zero.hasAssertedTime)
        assertNull(zero.withheldReason)
    }

    @Test
    fun duplicatedOverlappingTouchingAndUnsortedSegmentsAsUpstream() {
        // A doubled in-bed layer is summed twice — upstream's arithmetic, kept.
        val dup = SleepProvenanceBreakdown(listOf(seg(0.0, 400.0, inBed), seg(0.0, 400.0, inBed), seg(0.0, 300.0, core), seg(300.0, 400.0, core, asserted)))
        assertEquals(
            listOf(48000.0, 48000.0, 0.0, 18000.0, 0.0, 6000.0, 0.0, 0.0, 0.0, 0.0, 0.0, 18000.0, 0.0, 0.0, 6000.0, 0.0, 0.0, 6000.0),
            totals(dup),
        )
        assertEquals(1.0, dup.coverageFraction)
        assertEquals(0.375, dup.efficiency)
        assertTrue(dup.isScorable)
        assertEquals("0 min of this night's 800 min in-bed window holds no ring data", dup.withheldReason)

        // Overlapping asserted spans merge for the longest gap; with no in-bed layer the staged total is the denominator.
        val overlap = SleepProvenanceBreakdown(
            listOf(
                seg(0.0, 100.0, core, asserted), seg(50.0, 200.0, SleepStage.AWAKE, asserted),
                seg(300.0, 310.0, SleepStage.ASLEEP_DEEP, asserted), seg(200.0, 300.0, SleepStage.ASLEEP_REM),
            ),
        )
        assertEquals(
            listOf(21600.0, 6000.0, 0.0, 6000.0, 0.0, 6600.0, 0.0, 0.0, 0.0, 9000.0, 0.0, 0.0, 0.0, 6000.0, 6000.0, 600.0, 0.0, 12000.0),
            totals(overlap),
        )
        assertEquals(0.2777777777777778, overlap.coverageFraction)
        assertNull(overlap.efficiency)
        assertFalse(overlap.isScorable)
        assertEquals("260 min of this night's 360 min in-bed window holds no ring data", overlap.withheldReason)
        assertEquals(minutes(360, 100, 100, 110, 0, 0, 150, 0, 0, 100), overlap.minutes)

        // Touching asserted spans are one hole.
        val touching = SleepProvenanceBreakdown(listOf(seg(0.0, 100.0, core, asserted), seg(100.0, 200.0, SleepStage.ASLEEP_REM, asserted)))
        assertEquals(12000.0, touching.longestUnmeasuredGap)
        assertEquals("200 min of this night's 200 min in-bed window holds no ring data", touching.withheldReason)

        // Order does not matter.
        val unsorted = SleepProvenanceBreakdown(listOf(seg(300.0, 400.0, core, asserted), seg(0.0, 400.0, inBed), seg(0.0, 300.0, core)))
        assertEquals(0.75, unsorted.efficiency)
        assertEquals(6000.0, unsorted.longestUnmeasuredGap)
        assertEquals("0 min of this night's 400 min in-bed window holds no ring data", unsorted.withheldReason)
    }

    @Test
    fun unknownGroundIsNeverAHoleAndAnInBedOnlyNightHasZeroEfficiency() {
        val unknown = SleepProvenanceBreakdown(
            listOf(seg(0.0, 400.0, inBed, SleepProvenance.ASSERTED_COVERAGE_UNKNOWN), seg(0.0, 400.0, core, SleepProvenance.ASSERTED_COVERAGE_UNKNOWN)),
        )
        assertEquals(
            listOf(24000.0, 0.0, 24000.0, 0.0, 0.0, 0.0, 24000.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            totals(unknown),
        )
        assertEquals(0.0, unknown.provenUnmeasuredInBed)
        assertNull(unknown.withheldReason)
        assertFalse(unknown.isScorable)

        val inBedOnly = SleepProvenanceBreakdown(listOf(seg(0.0, 400.0, inBed)))
        assertEquals(0.0, inBedOnly.efficiency, "measured asleep 0 over covered ground is a real 0, not a withhold")
        assertTrue(inBedOnly.isScorable)
    }

    @Test
    fun nonFiniteAndNegativeTuningAsUpstream() {
        val full = listOf(seg(0.0, 400.0, inBed), seg(0.0, 400.0, core))
        val nan = SleepProvenanceBreakdown(full, SleepProvenanceBreakdown.Tuning(Double.NaN, Double.NaN, true))
        assertEquals(1.0, nan.efficiency, "a NaN floor never withholds efficiency")
        assertFalse(nan.isScorable, "a NaN coverage bar is never met")
        val inf = SleepProvenanceBreakdown(full, SleepProvenanceBreakdown.Tuning(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, true))
        assertNull(inf.efficiency)
        assertFalse(inf.isScorable)
        val neg = SleepProvenanceBreakdown(
            listOf(seg(0.0, 100.0, inBed), seg(100.0, 400.0, inBed, asserted), seg(0.0, 100.0, core)),
            SleepProvenanceBreakdown.Tuning(-1.0, -1.0, true),
        )
        assertEquals(1.0, neg.efficiency)
        assertTrue(neg.isScorable)
        assertEquals(18000.0, neg.provenUnmeasuredInBed)
        assertNull(neg.withheldReason, "asserted in-bed with no asserted stage is not asserted TIME")
    }

    @Test
    fun withholdingThresholdsAreInclusiveAsUpstream() {
        // Exactly three covered hours keeps efficiency; exactly 0.75 coverage is scorable.
        val floor = SleepProvenanceBreakdown(listOf(seg(0.0, 180.0, inBed), seg(0.0, 180.0, core), seg(180.0, 240.0, inBed, asserted)))
        assertEquals(1.0, floor.efficiency)
        assertEquals(0.75, floor.coverageFraction)
        assertTrue(floor.isScorable)
        val bar = SleepProvenanceBreakdown(listOf(seg(0.0, 300.0, inBed), seg(0.0, 300.0, core), seg(300.0, 400.0, inBed, asserted)))
        assertEquals(0.75, bar.coverageFraction)
        assertTrue(bar.isScorable)
        assertEquals(6000.0, bar.provenUnmeasuredInBed)
    }

    @Test
    fun halfMinutesRoundAwayFromZeroAsUpstream() {
        val b = SleepProvenanceBreakdown(listOf(seg(0.0, 0.5, inBed, asserted), seg(0.0, 1.5, core, asserted), seg(1.5, 2.5, SleepStage.ASLEEP_REM)))
        assertEquals(minutes(1, 0, 1, 2, 0, 0, 0, 0, 0, 1), b.minutes)
        assertEquals("1 min of this night's 1 min in-bed window holds no ring data", b.withheldReason)
        assertEquals(90.0, b.longestUnmeasuredGap)
    }

    @Test
    fun totalsTooLargeForWholeMinutesSaturateInsteadOfTrapping() {
        val far = Instant.ofEpochSecond(10_000_000_000_000_000)
        val near = Instant.ofEpochSecond(-10_000_000_000_000_000)
        // Upstream measured with three ±1e16 s asserted segments: 1e15 minutes, printed whole.
        val few = SleepProvenanceBreakdown(List(3) { SleepSegment(near, far, core, asserted) })
        assertEquals(minutes(1_000_000_000_000_000, 0, 0, 1_000_000_000_000_000, 0, 0, 0, 0, 0, 0), few.minutes)
        assertEquals("1000000000000000 min of this night's 1000000000000000 min in-bed window holds no ring data", few.withheldReason)
        assertEquals(2e16, few.longestUnmeasuredGap)
        // Upstream TRAPS on 30 000 of them ("Double value cannot be converted to Int", measured) — both
        // in `minutes` and in `withheldReason`. Here the whole-minute count saturates at 64 bits.
        val many = SleepProvenanceBreakdown(List(30_000) { SleepSegment(near, far, core, asserted) })
        assertEquals(6e20, many.assertedAsleep)
        assertEquals(Long.MAX_VALUE, many.minutes.assertedAsleep)
        assertEquals(Long.MAX_VALUE, many.minutes.inBed)
        assertEquals("9223372036854775807 min of this night's 9223372036854775807 min in-bed window holds no ring data", many.withheldReason)
        val measured = SleepProvenanceBreakdown(List(10_000) { SleepSegment(near, far, core) })
        assertEquals(2e20, measured.measuredAsleep)
        assertEquals(1.0, measured.coverageFraction)
        assertEquals(1.0, measured.efficiency)
    }

    // MARK: the health-store partition

    @Test
    fun thePartitionKeepsEverySegmentInOrderWhateverItsShape() {
        val mixed = listOf(
            seg(0.0, 100.0, core, asserted),
            seg(100.0, 50.0, core, asserted),
            seg(0.0, 200.0, inBed, SleepProvenance.ASSERTED_COVERAGE_UNKNOWN),
            seg(100.0, 200.0, SleepStage.AWAKE, SleepProvenance.ASSERTED_OVER_MEASURED),
            seg(0.0, 200.0, inBed, asserted),
        )
        val pub = mixed.healthPublication
        assertEquals(listOf(mixed[2], mixed[3]), pub.measured)
        assertEquals(listOf(mixed[0], mixed[1], mixed[4]), pub.userEntered, "a reversed asserted segment is still hers")
        assertTrue(pub.withheld.isEmpty())
        assertEquals(mixed, pub.published)
        assertEquals(6000.0, mixed.unmeasuredAsleepSeconds, "the reversed one counts as nothing")
        assertTrue(mixed.withheldSpans.isEmpty())
        assertTrue(mixed.containsAssertedTime)
        assertEquals(listOf(mixed[3]), mixed.measuredOnly)

        val empty = emptyList<SleepSegment>().healthPublication
        assertEquals(SleepHealthPublication(emptyList(), emptyList(), emptyList(), emptyList()), empty)
    }

    /**
     * Upstream sums each segment's `duration` (`end.timeIntervalSince(start)`, the difference of the two
     * dates' doubles) and measures the longest proven hole the same way; the export prints these totals
     * with 17 significant digits. Measured at the pin on a millisecond-stamped night.
     */
    @Test
    fun totalsSumTheDifferencesOfTheDateDoubles() {
        val a = FoundationDate.unix(1_755_000_000.580)
        val b = FoundationDate.unix(1_755_003_000.913)
        val c = FoundationDate.unix(1_755_020_000.207)
        val night = listOf(
            SleepSegment(a, b, inBed), SleepSegment(b, c, inBed, asserted),
            SleepSegment(a, b, core), SleepSegment(b, c, core, asserted),
        )
        val br = SleepProvenanceBreakdown(night)
        assertEquals(4_671_226_669_565_280_256L, br.totalInBed.toRawBits(), "totalInBed ${br.totalInBed}")
        assertEquals(19_999.62700009346, br.totalInBed)
        assertEquals(4_658_816_217_115_525_120L, br.coveredInBed.toRawBits(), "coveredInBed ${br.coveredInBed}")
        assertEquals(4_658_816_217_115_525_120L, br.measuredAsleep.toRawBits(), "measuredAsleep ${br.measuredAsleep}")
        assertEquals(4_670_401_944_310_054_912L, br.assertedAsleep.toRawBits(), "assertedAsleep ${br.assertedAsleep}")
        assertEquals(4_670_401_944_310_054_912L, br.longestUnmeasuredGap.toRawBits(), "longest ${br.longestUnmeasuredGap}")
        assertEquals(4_594_573_040_526_782_387L, br.coverageFraction.toRawBits(), "coverageFraction ${br.coverageFraction}")
        assertNull(br.efficiency, "under three covered hours the ratio is withheld")
    }
}
