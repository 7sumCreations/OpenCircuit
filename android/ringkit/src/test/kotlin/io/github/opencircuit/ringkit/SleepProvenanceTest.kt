package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.MeasuredCoverage.Ground
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// PROVENANCE — the unit-level contract: coverage, the retention guard, the label-recovered coverage,
// the edit path's kill switch, the measured-versus-asserted arithmetic and the stored hypnogram's
// provenance codes.
//
// Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepProvenanceTests.swift (@ b1c2fdd).
// The file's classes keep their names with `Tests` → `Test`, except upstream's `MeasuredCoverageTests`,
// which is `MeasuredCoverageVectorTest` here (a Kotlin-only `MeasuredCoverageTest` already exists).
// `TimeInterval` totals are `Double` seconds; `Duration`-valued results are converted where upstream
// compares seconds.

private fun sec(d: Duration): Double = SleepStaging.seconds(d)

class MeasuredCoverageVectorTest {

    private val t0: Instant = Instant.ofEpochSecond(1_700_000_000)
    private fun at(s: Long): Instant = t0.plusSeconds(s)
    private val epoch150: Duration = Duration.ofSeconds(150)

    @Test
    fun consecutiveEpochsMergeIntoOneInterval() {
        // Three touching 150 s epochs must coalesce. If they did not, `partition` would emit
        // zero-length "gaps" between every pair and a continuous night would look shredded.
        val c = MeasuredCoverage.ofRecordDates(listOf(at(0), at(150), at(300)), epoch150)
        assertEquals(1, c.intervals.size)
        assertEquals(at(0), c.intervals.first().start)
        assertEquals(at(450), c.intervals.first().end)
    }

    @Test
    fun gapIsReportedAsUnmeasured() {
        val c = MeasuredCoverage.ofRecordDates(listOf(at(0), at(600)), epoch150)
        val range = DateInterval(at(0), at(750))
        assertEquals(300.0, sec(c.measuredDuration(range)))
        assertEquals(1, c.unmeasuredPortions(range).size)
        assertEquals(450.0, sec(c.longestGap(range)))
        assertEquals(300.0 / 750.0, c.fraction(range), 1e-12)
    }

    @Test
    fun partitionTilesTheRangeExactly() {
        // A hole in the tiling would silently drop time off a night, so pin the property.
        val c = MeasuredCoverage.ofRecordDates(listOf(at(100), at(900)), epoch150)
        val range = DateInterval(at(0), at(1200))
        val parts = c.partition(range)
        assertEquals(range.start, parts.first().range.start)
        assertEquals(range.end, parts.last().range.end)
        for ((a, b) in parts.zipWithNext()) {
            assertEquals(a.range.end, b.range.start, "partition left a hole")
        }
        val total = parts.fold(0.0) { acc, p -> acc + sec(p.range.duration) }
        assertEquals(1200.0, total, 1e-9)
    }

    @Test
    fun emptyCoverageMakesEverythingUnmeasured() {
        val range = DateInterval(at(0), at(1000))
        assertEquals(0.0, sec(MeasuredCoverage.EMPTY.measuredDuration(range)))
        assertEquals(1, MeasuredCoverage.EMPTY.partition(range).size)
        assertEquals(Ground.UNMEASURED, MeasuredCoverage.EMPTY.partition(range).first().ground)
    }
}

// The retention guard (M2). RETENTION MUST NEVER READ AS ABSENCE: coverage comes from a rolling ~30 h
// archive; a night older than that holds no records for a reason that has nothing to do with the ring.

class MeasuredCoverageTrustTest {

    private val t0: Instant = Instant.ofEpochSecond(1_700_000_000)
    private fun at(m: Double): Instant = t0.plusNanos((m * 60e9).roundToLong())
    private fun iv(a: Double, b: Double) = DateInterval(at(a), at(b))
    private val epoch150: Duration = Duration.ofSeconds(150)

    @Test
    fun aWindowHoldingNoRecordAtAllIsUNKNOWNRatherThanEmpty() {
        // The retention case in one line: every record we still hold is from AFTER the night.
        val night = iv(0.0, 480.0)
        val archive = MeasuredCoverage.ofRecordDates(listOf(at(3000.0), at(3150.0)), epoch150)
        assertEquals(0.0, archive.fraction(night), "the raw read really does say zero coverage")
        assertNull(archive.trusted(night), "…and zero coverage over a window we hold nothing for is UNKNOWN, not empty")
    }

    @Test
    fun oneRecordInsideTheWindowIsEnoughToTrustIt() {
        val night = iv(0.0, 480.0)
        val archive = MeasuredCoverage.ofRecordDates(listOf(at(-600.0), at(200.0)), epoch150)
        val trusted = archive.trusted(night)
        assertEquals(archive.intervals, trusted?.intervals, "trusting must not change the ground")
        assertEquals(at(-600.0), trusted?.provenFrom)
    }

    @Test
    fun groundOlderThanOurOldestRecordIsUnknownAndTheRestIsStillProven() {
        // One record at minute 100, covering one 150 s epoch. A window opening at minute -60 reaches
        // back past everything we hold.
        val archive = MeasuredCoverage.ofRecordDates(listOf(at(100.0)), epoch150)
        val night = iv(-60.0, 480.0)
        val trusted = assertNotNull(archive.trusted(night))
        val parts = trusted.partition(night)
        assertEquals(listOf(Ground.UNKNOWN, Ground.MEASURED, Ground.UNMEASURED), parts.map { it.ground }, "before the oldest record = unknown; after it, silence is evidence")
        assertEquals(iv(-60.0, 100.0), parts[0].range)
        assertEquals(iv(102.5, 480.0), parts[2].range)
    }

    @Test
    fun aGapStraddlingTheHorizonIsSplitAtIt() {
        // Two record islands; the window opens inside the earlier one's past.
        val archive = MeasuredCoverage(listOf(iv(0.0, 60.0), iv(200.0, 260.0)))
        val trusted = assertNotNull(archive.trusted(iv(-100.0, 300.0)))
        val parts = trusted.partition(iv(-100.0, 300.0))
        assertEquals(listOf(Ground.UNKNOWN, Ground.MEASURED, Ground.UNMEASURED, Ground.MEASURED, Ground.UNMEASURED), parts.map { it.ground })
        assertEquals(iv(-100.0, 0.0), parts[0].range, "split exactly at the oldest record")
    }

    @Test
    fun trustIsRefusedForAReversedOrEmptyWindow() {
        val archive = MeasuredCoverage.ofRecordDates(listOf(at(0.0)), epoch150)
        assertNull(archive.trusted(iv(10.0, 10.0)))
        assertNull(MeasuredCoverage.EMPTY.trusted(iv(0.0, 100.0)))
    }
}

// The SECOND health-store construction reads LABELS, and must not be re-guarded: running label-recovered
// coverage through `trusted` substitutes "the first non-hole LABEL" for "our oldest RECORD", which always
// favours publishing. `ProvenanceLabelCoverage` has no `trusted` — the call does not compile.

class ProvenanceLabelCoverageTest {

    private val t0: Instant = Instant.ofEpochSecond(1_700_000_000)
    private fun at(m: Long): Instant = t0.plusSeconds(m * 60)
    private fun iv(a: Long, b: Long) = DateInterval(at(a), at(b))

    /** A stored hypnogram whose FIRST label is a proven hole, exactly as the primary path writes it. */
    private val labels: List<SleepSegment>
        get() = listOf(
            SleepSegment(at(0), at(60), SleepStage.IN_BED, SleepProvenance.ASSERTED),
            SleepSegment(at(0), at(60), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED),
            SleepSegment(at(60), at(480), SleepStage.IN_BED),
            SleepSegment(at(60), at(480), SleepStage.ASLEEP_CORE),
        )

    /**
     * Re-baselined upstream 2026-08-24: a hole the records PROVED empty must still come back
     * unmeasured, and its fill is now published tagged as the wearer's own entry.
     */
    @Test
    fun aLeadingPROVENHoleIsStillAHoleAndItsFillIsTaggedUserEntered() {
        val cov = assertNotNull(MeasuredCoverage.fromProvenanceLabels(labels))
        val parts = cov.partition(iv(0, 480))
        assertEquals(listOf(Ground.UNMEASURED, Ground.MEASURED), parts.map { it.ground }, "the labels proved this hour empty; recovering them must not soften it")

        // What the store's pending health writes do with those pieces, verbatim.
        val filled = parts.map { SleepSegment(it.range.start, it.range.end, SleepStage.ASLEEP_CORE, SleepEdit.provenance(it.ground)) }
        assertEquals(480.0 * 60, sec(SleepStaging.totalAsleep(filled.healthPublishable)), 1.0, "the whole window reaches Health — the wearer's account included")
        assertEquals(60.0 * 60, sec(SleepStaging.totalAsleep(filled.healthUserEntered)), 1.0, "…and exactly the 60-minute proven hole is tagged as entered by her")
        assertTrue(filled.withheldSpans.isEmpty(), "nothing is withheld ⇒ nothing may be deleted")
    }

    @Test
    fun groundLabelledCoverageUNKNOWNCountsAsCoveredAndPublishes() {
        val withUnknown = listOf(
            SleepSegment(at(0), at(60), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED_COVERAGE_UNKNOWN),
            SleepSegment(at(60), at(120), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED),
            SleepSegment(at(120), at(480), SleepStage.ASLEEP_CORE),
        )
        val cov = assertNotNull(MeasuredCoverage.fromProvenanceLabels(withUnknown))
        assertEquals(listOf(Ground.MEASURED, Ground.UNMEASURED, Ground.MEASURED), cov.partition(iv(0, 480)).map { it.ground })
    }

    @Test
    fun aHypnogramWithNoPROVENHoleReturnsNilSoTheCallerKeepsItsOldBehaviour() {
        val allMeasured = listOf(SleepSegment(at(0), at(480), SleepStage.ASLEEP_CORE))
        assertNull(MeasuredCoverage.fromProvenanceLabels(allMeasured), "fully covered and pre-provenance are indistinguishable — do not guess")
        val unknownOnly = listOf(SleepSegment(at(0), at(480), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED_COVERAGE_UNKNOWN))
        assertNull(MeasuredCoverage.fromProvenanceLabels(unknownOnly))
    }
}

class SleepEditRetentionGuardTest {

    private val t0: Instant = Instant.ofEpochSecond(1_700_000_000)
    private fun at(m: Long): Instant = t0.plusSeconds(m * 60)
    private fun iv(a: Long, b: Long) = DateInterval(at(a), at(b))

    /** A fully-recorded night, and the user nudges wake by 10 minutes — only the archive's age varies. */
    private val base: List<SleepSegment>
        get() = listOf(SleepSegment(at(0), at(480), SleepStage.IN_BED), SleepSegment(at(0), at(480), SleepStage.ASLEEP_CORE))
    private val times: SleepEdit.Times get() = SleepEdit.Times(inBedStart = at(0), sleepOnset = at(0), sleepWake = at(490))

    @Test
    fun anArchiveThatRolledPastTheNightCannotShrinkIt() {
        // Two days later: the archive holds only recent records, none from this night.
        val rolled = MeasuredCoverage.ofRecordDates(listOf(at(4000), at(4150)), Duration.ofSeconds(150))
        val out = SleepEdit.recompute(base, times, coverage = rolled)
        val master = SleepEdit.recompute(base, times, coverage = null)

        assertEquals(master, out, "a night the archive cannot speak about must behave as master")
        assertFalse(out.containsAssertedTime)
        assertEquals(
            SleepStaging.totalAsleep(master.healthPublishable),
            SleepStaging.totalAsleep(out.healthPublishable),
            "…and the health store must receive exactly what it received before",
        )
        assertTrue(out.withheldSpans.isEmpty(), "nothing withheld ⇒ nothing can be deleted")
    }

    @Test
    fun theSameNightSTILLSHRINKSWhileTheArchiveHoldsIt() {
        // With the records still in hand, the 10-minute extension past the last record is invented.
        val held = MeasuredCoverage(listOf(iv(0, 480)))
        val out = SleepEdit.recompute(base, times, coverage = held)
        assertTrue(out.containsAssertedTime)
        assertEquals(600.0, SleepProvenanceBreakdown(out).assertedAsleep, 1.0, "the 10 minutes past the last record are the user's claim")
    }

    @Test
    fun aHalfRetainedNightPublishesTheUnREACHABLEHalfAndWithholdsTheProvenHole() {
        // Retention cut this night in two, and it cut the staging with it. The user's window still runs 0→490:
        //   0   → 240  no records AND we cannot reach back that far → UNKNOWN → published
        //   240 → 480  records, staged                              → measured
        //   480 → 490  past our newest record                      → proven hole
        val retainedBase = listOf(SleepSegment(at(240), at(480), SleepStage.IN_BED), SleepSegment(at(240), at(480), SleepStage.ASLEEP_CORE))
        val half = MeasuredCoverage(listOf(iv(240, 480)))
        val out = SleepEdit.recompute(retainedBase, times, coverage = half)
        val b = SleepProvenanceBreakdown(out)

        assertEquals(240.0 * 60, b.unknownAsleep, 1.0, "the pre-archive half is UNKNOWN, not a hole")
        assertEquals(600.0, b.assertedAsleep, 1.0, "only the tail is proven unmeasured")
        assertEquals(240.0 * 60, b.measuredAsleep, 1.0)
        assertEquals(490.0 * 60, b.displayedAsleep, 1.0, "the card total is untouched")
        assertEquals(240.0 * 60, b.unknownInBed, 1.0)

        // Re-baselined upstream 2026-08-24: the unknown half is published unlabelled, the 10 proven
        // minutes are published tagged as hers, and nothing is withheld.
        val published = out.healthPublishable
        assertEquals(490.0 * 60, sec(SleepStaging.totalAsleep(published)), 1.0, "the whole 490-minute window she asserted now reaches Health")
        assertEquals(600.0, sec(SleepStaging.totalAsleep(out.healthUserEntered)), 1.0, "only the 10 PROVEN minutes are tagged; the unknown half is not hers to own")
        assertTrue(out.withheldSpans.isEmpty(), "nothing withheld ⇒ nothing may be deleted")
    }
}

class SleepEditProvenanceKillSwitchTest {

    private val t0: Instant = Instant.ofEpochSecond(1_700_000_000)
    private fun at(m: Long): Instant = t0.plusSeconds(m * 60)

    /** A base recording, an extension past it, and a bedtime-to-onset gap — every fill site at once. */
    private val base: List<SleepSegment>
        get() = listOf(
            SleepSegment(at(0), at(120), SleepStage.IN_BED),
            SleepSegment(at(10), at(60), SleepStage.ASLEEP_CORE),
            SleepSegment(at(60), at(90), SleepStage.ASLEEP_DEEP),
            SleepSegment(at(90), at(120), SleepStage.ASLEEP_REM),
        )
    private val times: SleepEdit.Times get() = SleepEdit.Times(inBedStart = at(-30), sleepOnset = at(5), sleepWake = at(300))

    @Test
    fun nilCoverageEmitsOnlyMeasuredSegments() {
        val out = SleepEdit.recompute(base, times, coverage = null)
        assertFalse(out.isEmpty())
        assertTrue(out.all { it.provenance == SleepProvenance.MEASURED }, "the kill switch must leave every segment measured")
        assertFalse(out.containsAssertedTime)
    }

    @Test
    fun nilCoverageIsIdenticalToTheDefaultParameter() {
        // The default argument IS the kill switch.
        assertEquals(SleepEdit.recompute(base, times, coverage = null), SleepEdit.recompute(base, times))
    }

    @Test
    fun fullCoverageProducesTheSameSPANSAsTheKillSwitch() {
        // Coverage that spans everything must not change the SHAPE of the night, only its labels.
        // (Adjacent same-stage pieces are not re-merged, so compare the union of spans per stage.)
        val full = MeasuredCoverage(listOf(DateInterval(at(-1000), at(1000))))
        val off = SleepEdit.recompute(base, times, coverage = null)
        val on = SleepEdit.recompute(base, times, coverage = full)
        fun spanByStage(segs: List<SleepSegment>): Map<SleepStage, Duration> =
            segs.groupBy { it.stage }.mapValues { (_, v) -> v.fold(Duration.ZERO) { acc, s -> acc.plus(s.duration) } }
        assertEquals(spanByStage(off), spanByStage(on))
        assertFalse(on.containsAssertedTime, "fully-covered ground can never be asserted")
    }

    @Test
    fun windowOverloadKillSwitchIsAlsoInert() {
        val w = SleepEdit.Window(inBedStart = at(-30), inBedEnd = at(300))
        val out = SleepEdit.recompute(base, w, coverage = null)
        assertEquals(SleepEdit.recompute(base, w), out)
        assertTrue(out.all { it.provenance == SleepProvenance.MEASURED })
    }
}

class SleepProvenanceBreakdownTest {

    private val t0: Instant = Instant.ofEpochSecond(1_700_000_000)
    private fun at(m: Long): Instant = t0.plusSeconds(m * 60)

    @Test
    fun assertedSleepIsExcludedFromDerivedNumbersButNotFromTheHeadline() {
        val segs = listOf(
            SleepSegment(at(0), at(100), SleepStage.IN_BED),
            SleepSegment(at(100), at(400), SleepStage.IN_BED, SleepProvenance.ASSERTED),
            SleepSegment(at(0), at(100), SleepStage.ASLEEP_CORE),
            SleepSegment(at(100), at(400), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED),
        )
        val b = SleepProvenanceBreakdown(segs)
        assertEquals(100.0 * 60, b.measuredAsleep)
        assertEquals(300.0 * 60, b.assertedAsleep)
        assertEquals(400.0 * 60, b.displayedAsleep, "clause 1: the assertion wins for display")
        assertEquals(400.0 * 60, b.totalInBed)
        assertEquals(100.0 * 60, b.coveredInBed)
        assertEquals(0.25, b.coverageFraction, 1e-12)
        assertEquals(300.0 * 60, b.longestUnmeasuredGap)
        assertNull(b.efficiency, "100 min of covered in-bed is below the ratio floor — withhold")
        assertFalse(b.isScorable)
    }

    @Test
    fun assertedOverMeasuredCountsNormally() {
        // The user relabelled ground the ring DID record: numerator and denominator both.
        val segs = listOf(
            SleepSegment(at(0), at(600), SleepStage.IN_BED, SleepProvenance.ASSERTED_OVER_MEASURED),
            SleepSegment(at(0), at(480), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED_OVER_MEASURED),
            SleepSegment(at(480), at(600), SleepStage.AWAKE, SleepProvenance.ASSERTED_OVER_MEASURED),
        )
        val b = SleepProvenanceBreakdown(segs)
        assertEquals(0.0, b.assertedAsleep)
        assertEquals(480.0 * 60, b.measuredAsleep)
        assertEquals(1.0, b.coverageFraction, 1e-12)
        assertEquals(0.8, assertNotNull(b.efficiency), 1e-12)
        assertTrue(b.isScorable)
    }

    @Test
    fun unstagedNightIsUnaffected() {
        // Every segment staging emits is measured, so an unedited night publishes exactly what it did.
        val segs = listOf(
            SleepSegment(at(0), at(480), SleepStage.IN_BED),
            SleepSegment(at(0), at(60), SleepStage.AWAKE),
            SleepSegment(at(60), at(480), SleepStage.ASLEEP_CORE),
        )
        val b = SleepProvenanceBreakdown(segs)
        assertFalse(b.hasAssertedTime)
        assertNull(b.withheldReason)
        assertEquals(1.0, b.coverageFraction, 1e-12)
        assertEquals(420.0 / 480.0, assertNotNull(b.efficiency), 1e-12)
        assertTrue(b.isScorable)
    }

    @Test
    fun withholdingCanBeTurnedOff() {
        val segs = listOf(
            SleepSegment(at(0), at(400), SleepStage.IN_BED, SleepProvenance.ASSERTED),
            SleepSegment(at(0), at(400), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED),
        )
        val b = SleepProvenanceBreakdown(segs, SleepProvenanceBreakdown.Tuning.NEVER_WITHHOLD)
        assertTrue(b.isScorable)
        assertNull(b.efficiency, "no covered ground at all -> still nil; nil is not a threshold verdict")
        assertNull(b.withheldReason)
    }

    @Test
    fun efficiencyIsNeverZeroAsAWithholdSignal() {
        // A stored 0 is a live sentinel upstream's store reads as "reconstruct in-bed otherwise".
        // Withholding MUST be null, never 0.
        val segs = listOf(
            SleepSegment(at(0), at(400), SleepStage.IN_BED, SleepProvenance.ASSERTED),
            SleepSegment(at(0), at(400), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED),
        )
        val b = SleepProvenanceBreakdown(segs)
        assertNull(b.efficiency)
        b.efficiency?.let { assertTrue(it != 0.0, "0 is the store's sentinel") }
    }
}

class SleepHypnogramCodecProvenanceTest {

    private fun s(epoch: Long): Instant = Instant.ofEpochSecond(epoch)
    private fun utf8(b: ByteArray) = String(b, Charsets.UTF_8)

    @Test
    fun allMeasuredNightEncodesToTheHISTORICALBYTES() {
        // An unedited night's stored bytes must not change at all, so no install re-writes its history.
        val segs = listOf(
            SleepSegment(s(1_700_000_000), s(1_700_000_150), SleepStage.ASLEEP_DEEP),
            SleepSegment(s(1_700_000_150), s(1_700_000_300), SleepStage.ASLEEP_CORE),
        )
        assertEquals("[[1700000000,1700000150,3],[1700000150,1700000300,2]]", utf8(SleepHypnogramCodec.encode(segs)))
    }

    @Test
    fun provenanceRoundTrips() {
        val segs = listOf(
            SleepSegment(s(1_700_000_000), s(1_700_000_150), SleepStage.ASLEEP_CORE),
            SleepSegment(s(1_700_000_150), s(1_700_000_300), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED),
            SleepSegment(s(1_700_000_300), s(1_700_000_450), SleepStage.AWAKE, SleepProvenance.ASSERTED_OVER_MEASURED),
        )
        val data = SleepHypnogramCodec.encode(segs)
        assertEquals("[[1700000000,1700000150,2],[1700000150,1700000300,2,1],[1700000300,1700000450,1,2]]", utf8(data))
        assertEquals(segs, SleepHypnogramCodec.decode(data))
    }

    @Test
    fun legacyThreeElementRowsDecodeAsMeasured() {
        val data = "[[1700000000,1700000150,3],[1700000150,1700000300,2]]".toByteArray(Charsets.UTF_8)
        assertTrue(SleepHypnogramCodec.decode(data).all { it.provenance == SleepProvenance.MEASURED })
    }

    @Test
    fun unknownProvenanceCodeKeepsTheSegment() {
        // Losing a minute of real sleep is worse than losing its label.
        val out = SleepHypnogramCodec.decode("[[1700000000,1700000150,3,99]]".toByteArray(Charsets.UTF_8))
        assertEquals(1, out.size)
        assertEquals(SleepProvenance.MEASURED, out.first().provenance)
        assertEquals(SleepStage.ASLEEP_DEEP, out.first().stage)
    }

    @Test
    fun fiveOrTwoElementRowsAreStillRefused() {
        assertEquals(emptyList(), SleepHypnogramCodec.decode("[[1,2,3,1,9]]".toByteArray(Charsets.UTF_8)))
        assertEquals(emptyList(), SleepHypnogramCodec.decode("[[1700000000,1700000150]]".toByteArray(Charsets.UTF_8)))
    }
}
