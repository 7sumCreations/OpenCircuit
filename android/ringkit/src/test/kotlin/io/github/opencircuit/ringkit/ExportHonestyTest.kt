package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.ExportEngine.SleepEdgeProvenanceRow
import io.github.opencircuit.ringkit.ExportEngine.SleepRow
import io.github.opencircuit.ringkit.ExportEngine.SleepSessionRow
import io.github.opencircuit.ringkit.ExportEngineTest.Companion.parseCSV
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/ExportHonestyTests.swift (@ b1c2fdd),
 * all 19 tests: the three export claims upstream could not support, and the assertions that keep them
 * supported.
 *   1. `coverageFraction` is measured over the night's REPORTED in-bed window — on a night nobody
 *      corrected, that window ends at the last record, so it cannot fall when the recording stops at
 *      the wake. The same records score 1.0 against that window and well under 1 against a wake the
 *      recording did not define.
 *   2. `edgeProvenance` names which night's totals fed its duration verdict (`durationBasis`).
 *   3. `measuredAwakeSec` no longer counts a wearer's own awake label over recorded ground.
 *
 * The inputs are synthetic on purpose: what is tested is arithmetic and serialization. Upstream's
 * session JSON is printed in the machine's zone; here the zone is passed (Europe/Amsterdam) — nothing
 * these tests read depends on it.
 */
class ExportHonestyTest {

    private val t0 = FoundationDate.unix(1_700_000_000.0)
    private val zone: ZoneId = ZoneId.of("Europe/Amsterdam")

    /** `t0.addingTimeInterval(minutes * 60)`: Swift `Date` arithmetic on the seconds-since-2001 double. */
    private fun at(minutes: Double): Instant = FoundationDate.reference(FoundationDate.referenceSeconds(t0) + minutes * 60)

    /** One heart-rate instant every 150 s (the ring's own epoch step) across `[from, to)`. */
    private fun epochs(from: Instant, to: Instant): List<Instant> {
        val out = mutableListOf<Instant>()
        var t = from
        while (t < to) {
            out += t
            t = FoundationDate.reference(FoundationDate.referenceSeconds(t) + 150)
        }
        return out
    }

    // MARK: - 1. The detected window cannot falsify itself

    @Test
    fun detectedWindowCoverageIsPerfectOnANightWhoseRecordingStoppedAtTheWake() {
        // Six hours of unbroken epochs, and then nothing. The detected window ENDS where they end.
        val detectedStart = at(0.0)
        val detectedEnd = at(360.0)
        val witness = epochs(detectedStart, detectedEnd)

        val detected = ExportCoverage.assess(witness, detectedStart, detectedEnd)
        assertEquals(1.0, detected.coverageFraction, 1e-9, "the window is defined by the records, so it is always full of them")
        assertTrue(detected.gaps.isEmpty(), "and the four missing hours are OUTSIDE it, so there is nothing to report")

        // The wearer's schedule says she gets up at +600 min — nothing about that instant came from the recording.
        val reference = assertNotNull(
            ExportReferenceCoverage.assess(witness, detectedStart, detectedEnd, at(600.0), ExportReferenceCoverage.Reference.MANUAL_SCHEDULE_WAKE),
        )
        assertEquals(360.0 / 600.0, reference.assessment.coverageFraction, 0.01, "six recorded hours out of the ten she says she was in bed")
        assertEquals(1, reference.assessment.gaps.size)
        assertEquals(240.0 * 60, reference.assessment.gaps.firstOrNull()?.seconds ?: 0.0, 150.0, "the hole the detected window could not see, now inside the window")
        assertEquals(240.0 * 60, reference.beyondReportedEndSeconds, 1e-9)
        assertTrue(reference.assessment.coverageFraction < detected.coverageFraction, "the whole point: one of these two numbers can be wrong")
    }

    @Test
    fun aFullyCoveredNightScoresTheSameAgainstBothWindows() {
        val start = at(0.0)
        val wake = at(480.0)
        val reference = assertNotNull(
            ExportReferenceCoverage.assess(epochs(start, wake), start, wake, wake, ExportReferenceCoverage.Reference.MANUAL_SCHEDULE_WAKE),
        )
        assertEquals(1.0, reference.assessment.coverageFraction, 1e-9)
        assertTrue(reference.assessment.gaps.isEmpty())
        assertEquals(0.0, reference.beyondReportedEndSeconds, 1e-9)
    }

    @Test
    fun aReferenceEarlierThanTheDetectedEndIsStillPublishedWithANegativeDelta() {
        // A wearer who slept past their schedule: published with the sign that says so, not dropped.
        val start = at(0.0)
        val detectedEnd = at(480.0)
        val reference = assertNotNull(
            ExportReferenceCoverage.assess(epochs(start, detectedEnd), start, detectedEnd, at(400.0), ExportReferenceCoverage.Reference.MANUAL_SCHEDULE_WAKE),
        )
        assertEquals(-80.0 * 60, reference.beyondReportedEndSeconds, 1e-9)
    }

    @Test
    fun aReferenceAtOrBeforeTheBedtimeIsRefusedRatherThanMeasuredAsZero() {
        // A non-positive window has no denominator, and 0 would read as a total outage.
        assertNull(
            ExportReferenceCoverage.assess(epochs(at(0.0), at(60.0)), at(0.0), at(60.0), at(0.0), ExportReferenceCoverage.Reference.MANUAL_SCHEDULE_WAKE),
        )
    }

    // MARK: - 1a. The reference never reaches past the present

    @Test
    fun aScheduleWakeThatHasNotArrivedIsClampedToNowAndSaysSo() {
        // Export at 05:00 against an 06:30 schedule: measuring to 06:30 would count 90 minutes no
        // recording could exist for as missing.
        val now = at(300.0)
        val bounded = ExportReferenceCoverage.reference(forScheduledWake = at(390.0), asOf = now)
        assertEquals(now, bounded?.end, "the window closes at the export instant, not the wake")
        assertEquals(ExportReferenceCoverage.Reference.MANUAL_SCHEDULE_WAKE_SO_FAR, bounded?.reference, "and the token says which of the two it is")
    }

    @Test
    fun aScheduleWakeAlreadyPastIsUsedUnchanged() {
        val bounded = ExportReferenceCoverage.reference(forScheduledWake = at(390.0), asOf = at(600.0))
        assertEquals(at(390.0), bounded?.end)
        assertEquals(ExportReferenceCoverage.Reference.MANUAL_SCHEDULE_WAKE, bounded?.reference)
    }

    @Test
    fun noScheduleYieldsNoReferenceRatherThanNow() {
        assertNull(
            ExportReferenceCoverage.reference(forScheduledWake = null, asOf = at(600.0)),
            "`now` is not a wake anybody named — it must not become the denominator",
        )
    }

    @Test
    fun theClampedReferenceStillSeesAHoleThatHasAlreadyOpened() {
        // The recording stopped at +120, the export runs at +300, the schedule says +600.
        val start = at(0.0)
        val witness = epochs(start, at(120.0))
        val bounded = assertNotNull(ExportReferenceCoverage.reference(forScheduledWake = at(600.0), asOf = at(300.0)))
        val reference = assertNotNull(ExportReferenceCoverage.assess(witness, start, at(120.0), bounded.end, bounded.reference))
        assertEquals(at(300.0), reference.assessment.windowEnd, "and never a minute past it")
        assertEquals(120.0 / 300.0, reference.assessment.coverageFraction, 0.01)
        assertEquals(180.0 * 60, reference.beyondReportedEndSeconds, 1e-9)
        val toTheUnclampedWake = ExportCoverage.assess(witness, start, at(600.0))
        assertTrue(
            reference.assessment.coverageFraction > toTheUnclampedWake.coverageFraction,
            "measuring to the un-arrived wake would have understated it further, and every second of that extra shortfall is time that has not happened",
        )
    }

    // MARK: - 1b. Both names, and the explicit "could not check"

    @Test
    fun coverageEmitsTheHonestNameBesideTheShippedOne() {
        val coverage = ExportCoverage.assess(epochs(at(0.0), at(360.0)), at(0.0), at(360.0))
        val block = json(session(coverage = coverage))["coverage"]?.asObject() ?: fail("coverage block missing")
        assertEquals(block.double("coverageFraction"), block.double("coverageWithinReportedWindow"), "same number, and the second name is the one that states its frame")
        assertNotNull(block["coverageFraction"], "the shipped key stays: the schema version is unchanged and these files are already in third-party hands")
    }

    @Test
    fun anUnavailableReferenceIsSaidOutLoudRatherThanOmitted() {
        // "The check found nothing wrong" and "the check could not run" must not serialize identically.
        val coverage = ExportCoverage.assess(epochs(at(0.0), at(360.0)), at(0.0), at(360.0))
        val obj = json(
            session(coverage = coverage, referenceCoverage = ExportReferenceCoverage.Outcome.Unavailable(ExportReferenceCoverage.Outcome.NO_MANUAL_SLEEP_SCHEDULE)),
        )
        val block = obj["referenceCoverage"]?.asObject() ?: fail("referenceCoverage must be emitted even when there is no reference")
        assertEquals(ReplayJson.Null, block["reference"], "null, not a fabricated denominator")
        assertEquals("noManualSleepSchedule", block.string("unavailableReason"))
        assertNull(block["coverageToReference"], "nothing was measured, so nothing is reported")
    }

    @Test
    fun theCSVAlwaysSaysWhetherTheReferenceCheckCouldRun() {
        val coverage = ExportCoverage.assess(epochs(at(0.0), at(360.0)), at(0.0), at(360.0))
        fun sourceColumn(row: SleepSessionRow): String = parseCSV(ExportEngine.sleepSessionsCSV(listOf(row), zone))[1][31]
        assertEquals("none", sourceColumn(session(coverage = coverage, referenceCoverage = ExportReferenceCoverage.Outcome.Unavailable("x"))))
        val measured = assertNotNull(
            ExportReferenceCoverage.assess(epochs(at(0.0), at(360.0)), at(0.0), at(360.0), at(600.0), ExportReferenceCoverage.Reference.MANUAL_SCHEDULE_WAKE),
        )
        assertEquals("manualScheduleWake", sourceColumn(session(coverage = coverage, referenceCoverage = ExportReferenceCoverage.Outcome.Measured(measured))))
        assertEquals("", sourceColumn(session()), "a night with no coverage window at all has nothing to say either way")
    }

    // MARK: - 2. One frame of reference per block, and it is named

    @Test
    fun edgeProvenanceStatesWhichNightsTotalsFedTheDurationVerdict() {
        val assessment = SleepConfidence.assess(asleep = 7.0 * 3600, inBed = 8.0 * 3600, coverage = null)
        val recorded = SleepEdgeProvenanceRow(at(0.0), at(480.0), assessment)
        assertEquals("recorded", recorded.durationBasis, "the default is the frame the EDGES are measured in")
        val edited = SleepEdgeProvenanceRow(at(0.0), at(480.0), assessment, SleepEdgeProvenanceRow.DURATION_BASIS_EDITED)
        val block = json(session(edgeProvenance = edited))["edgeProvenance"]?.asObject()
        assertEquals("edited", block?.string("durationBasis"))
    }

    // MARK: - 3. An assertion is not a measurement

    @Test
    fun aWearersAwakePaintOverRecordedGroundIsNoLongerCalledMeasured() {
        // The only awake block on the night is her own label, on ground the ring DID record.
        val segments = listOf(
            SleepSegment(at(0.0), at(480.0), SleepStage.IN_BED, SleepProvenance.ASSERTED_OVER_MEASURED),
            SleepSegment(at(0.0), at(35.0), SleepStage.AWAKE, SleepProvenance.ASSERTED_OVER_MEASURED),
            SleepSegment(at(35.0), at(480.0), SleepStage.ASLEEP_CORE),
        )
        val b = SleepProvenanceBreakdown(segments)
        assertEquals(0.0, b.measuredAwake, "the ring's own staging called none of this awake")
        assertEquals(35.0 * 60, b.assertedOverMeasuredAwake, "her 35 minutes to fall asleep, named as hers")
        assertEquals(35.0 * 60, b.displayedAwake, "clause 1 is untouched — the card still shows every minute of it")
    }

    @Test
    fun theAwakeBucketsStillCloseOnTheDisplayedTotal() {
        val segments = listOf(
            SleepSegment(at(0.0), at(400.0), SleepStage.IN_BED),
            SleepSegment(at(0.0), at(10.0), SleepStage.AWAKE),
            SleepSegment(at(10.0), at(30.0), SleepStage.AWAKE, SleepProvenance.ASSERTED_OVER_MEASURED),
            SleepSegment(at(30.0), at(70.0), SleepStage.AWAKE, SleepProvenance.ASSERTED),
            SleepSegment(at(70.0), at(150.0), SleepStage.AWAKE, SleepProvenance.ASSERTED_COVERAGE_UNKNOWN),
            SleepSegment(at(150.0), at(400.0), SleepStage.ASLEEP_CORE),
        )
        val b = SleepProvenanceBreakdown(segments)
        assertEquals(b.displayedAwake, b.measuredAwake + b.assertedOverMeasuredAwake + b.assertedAwake + b.unknownAwake, 1e-9)
        assertEquals(10.0 * 60, b.measuredAwake)
        assertEquals(20.0 * 60, b.assertedOverMeasuredAwake)
        assertEquals(40.0 * 60, b.assertedAwake)
        assertEquals(80.0 * 60, b.unknownAwake)
    }

    @Test
    fun theAsleepSubTotalIsASubsetAndMovesNoPublishedNumber() {
        // `measuredAsleep` stays the efficiency numerator; the relabelled part is STATED instead.
        val segments = listOf(
            SleepSegment(at(0.0), at(600.0), SleepStage.IN_BED),
            SleepSegment(at(0.0), at(300.0), SleepStage.ASLEEP_CORE),
            SleepSegment(at(300.0), at(600.0), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED_OVER_MEASURED),
        )
        val b = SleepProvenanceBreakdown(segments)
        assertEquals(600.0 * 60, b.measuredAsleep, "unchanged: both halves sit on recorded ground")
        assertEquals(300.0 * 60, b.assertedOverMeasuredAsleep, "and half of it carries her label")
        assertEquals(b.measuredAsleep + b.assertedAsleep + b.unknownAsleep, b.displayedAsleep, "the subset must not appear in the sum")
        assertEquals(1.0, assertNotNull(b.efficiency), 1e-12)
    }

    @Test
    fun perStageAssertedTotalsCountOnlyProvableHoles() {
        // What the Sleep card hatches; assertedCoverageUnknown is excluded on purpose.
        val segments = listOf(
            SleepSegment(at(0.0), at(300.0), SleepStage.IN_BED),
            SleepSegment(at(0.0), at(100.0), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED),
            SleepSegment(at(100.0), at(140.0), SleepStage.ASLEEP_DEEP, SleepProvenance.ASSERTED),
            SleepSegment(at(140.0), at(170.0), SleepStage.ASLEEP_REM, SleepProvenance.ASSERTED),
            SleepSegment(at(170.0), at(200.0), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED_COVERAGE_UNKNOWN),
            SleepSegment(at(200.0), at(300.0), SleepStage.ASLEEP_CORE),
        )
        val b = SleepProvenanceBreakdown(segments)
        assertEquals(100.0 * 60, b.assertedLight)
        assertEquals(40.0 * 60, b.assertedDeep)
        assertEquals(30.0 * 60, b.assertedREM)
        assertEquals(0.0, b.assertedAwake)
    }

    @Test
    fun anUneditedNightHatchesNothingAndPublishesNoProvenanceSummary() {
        // Staging emits only measured segments, so nothing changes for a night nobody corrected.
        val segments = listOf(
            SleepSegment(at(0.0), at(480.0), SleepStage.IN_BED),
            SleepSegment(at(0.0), at(20.0), SleepStage.AWAKE),
            SleepSegment(at(20.0), at(480.0), SleepStage.ASLEEP_CORE),
        )
        val b = SleepProvenanceBreakdown(segments)
        assertFalse(b.hasAssertedTime)
        assertEquals(0.0, b.assertedLight + b.assertedDeep + b.assertedREM + b.assertedAwake)
        assertEquals(0.0, b.assertedOverMeasuredAwake)
        assertEquals(0.0, b.assertedOverMeasuredAsleep)
        assertEquals(20.0 * 60, b.measuredAwake)
        assertNull(json(session(hypnogram = segments))["provenanceSummary"], "no summary is emitted for a night with nothing to qualify")
    }

    @Test
    fun theProvenanceSummaryPublishesBothSidesOfTheSplit() {
        val segments = listOf(
            SleepSegment(at(0.0), at(400.0), SleepStage.IN_BED),
            SleepSegment(at(0.0), at(30.0), SleepStage.AWAKE, SleepProvenance.ASSERTED_OVER_MEASURED),
            SleepSegment(at(30.0), at(200.0), SleepStage.ASLEEP_CORE),
            SleepSegment(at(200.0), at(400.0), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED),
        )
        val summary = json(session(hypnogram = segments))["provenanceSummary"]?.asObject() ?: fail("an asserted night must publish its split")
        assertEquals(0.0, summary.double("measuredAwakeSec"))
        assertEquals(30.0 * 60, summary.double("assertedOverMeasuredAwakeSec"))
        assertEquals(0.0, summary.double("assertedOverMeasuredAsleepSec"))
        assertEquals(170.0 * 60, summary.double("measuredAsleepSec"))
        assertEquals(200.0 * 60, summary.double("assertedAsleepSec"))
    }

    @Test
    fun theNotesNameEveryClaimThisFilePins() {
        val notes = json(session())["notes"]?.asObject()
        assertTrue(notes?.string("coverage")?.contains("coverageWithinReportedWindow") == true)
        assertTrue(notes?.string("referenceCoverage")?.contains("manualScheduleWake") == true)
        assertTrue(notes?.string("referenceCoverage")?.contains("REFERENCE and not a") == true, "it must not be presented as ground truth")
        assertTrue(notes?.string("provenanceSummary")?.contains("OVER GROUND THE RING RECORDED") == true)
        assertTrue(notes?.string("edgeProvenance")?.contains("durationBasis") == true)
    }

    // MARK: - Fixtures

    private fun session(
        hypnogram: List<SleepSegment> = emptyList(),
        coverage: ExportCoverage.Assessment? = null,
        referenceCoverage: ExportReferenceCoverage.Outcome? = null,
        edgeProvenance: SleepEdgeProvenanceRow? = null,
    ) = SleepSessionRow(
        sessionID = "night-test", night = t0, inBedStart = at(0.0), inBedEnd = at(480.0), hypnogram = hypnogram,
        summary = SleepRow(
            night = t0, asleepMin = 420, deepMin = 60, lightMin = 300, remMin = 60, awakeMin = 60, efficiency = 0.875,
            skinTempC = 34.0, sleepScore = 80, stressScore = 30,
        ),
        coverage = coverage, referenceCoverage = referenceCoverage, edgeProvenance = edgeProvenance,
    )

    /** The whole JSON bundle for one session, parsed: `sleepSessions[0]`'s keys, then the root's keys it lacks. */
    private fun json(row: SleepSessionRow): Map<String, ReplayJson.Value> {
        val text = ExportEngine.toJSON(emptyList(), emptyList(), emptyList(), zone = zone, now = t0, sleepSessions = listOf(row)) ?: fail("the bundle must serialize")
        val root = ExportJsonReader.root(text)
        val merged = linkedMapOf<String, ReplayJson.Value>()
        root["sleepSessions"]?.asObjectList()?.firstOrNull()?.let { s -> for (k in s.keys) merged[k] = s[k]!! }
        for (k in root.keys) if (k !in merged) merged[k] = root[k]!!
        return merged
    }
}
