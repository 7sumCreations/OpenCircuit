package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `SleepConfidence.assess` adds the acquisition question — did the recording cover the night? — to
 * the duration question, without moving the duration answer.
 *
 * The numbers below are the corpus's own, replayed on upstream master `f042639`:
 *
 *     night           in-bed   eff      worst edge err   what shipped says today
 *     R2_2026-08-18   253 min  0.9873   −246 min         nothing (under the 5 h gate)
 *     R2_2026-08-17   102 min  1.0000   −246 min         nothing (under the 5 h gate)
 *     R3_2026-08-15   558 min  0.9534   −8 min           `.durationLikelyHigh`  ← the ONLY labelled
 *                                                          night it fires on, and it is the GOOD one
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepConfidenceCoverageTests.swift
 * (@ b1c2fdd) — all 22 tests.
 */
class SleepConfidenceCoverageTest {

    private fun mins(m: Double): Double = m * 60

    /** Swift's `addingTimeInterval`, to the nanosecond. */
    private fun Instant.adding(seconds: Double): Instant = plusNanos((seconds * 1e9).roundToLong())

    /** 2026-08-18 02:37:02 +02:00 — `R2_2026-08-18`'s detected in-bed end. */
    private val end: Instant = Instant.ofEpochSecond(1_787_013_422)

    /** 2026-08-17 22:24:25 +02:00 — the same night's detected in-bed start (253 min earlier). */
    private val start: Instant get() = end.adding(-mins(252.6167))

    private fun coverage(
        before: Double?,
        after: Double?,
        earliestDaysBack: Double = 14.0,
        afterSeries: List<Double> = emptyList(),
    ): SleepConfidence.Coverage =
        SleepConfidence.Coverage(
            inBedStart = start,
            inBedEnd = end,
            lastMeasurementBeforeStart = before?.let { start.adding(-it) },
            firstMeasurementAfterEnd = after?.let { end.adding(it) },
            // Empty by default: every test below this line predates the run walk and is about the
            // single-step rule, which an empty series reproduces exactly.
            measurementsAfterEnd = afterSeries.map { end.adding(it) },
            earliestRetainedMeasurement = start.adding(-earliestDaysBack * 86_400),
        )

    private fun afterWake(from: Instant, silentFor: Double): SleepConfidence.Reason = SleepConfidence.Reason.NoRecordingAfterWake(from, silentFor)

    // MARK: - The nights this exists for

    @Test
    fun truncatedNightReportsTheGapNotAnOverCount() {
        // R2_2026-08-18 exactly: 253 min in bed at 0.9873, recording resumes 241.9 min later.
        val a = SleepConfidence.assess(asleep = mins(249.0), inBed = mins(253.0), coverage = coverage(before = 150.0, after = 14_515.0))
        assertEquals(listOf(afterWake(end, 14_515.0)), a.reasons)
        assertTrue(a.hasAcquisitionReason)
        // And the legacy verdict is untouched — .normal, because 253 min is under the 5 h gate.
        assertEquals(SleepConfidence.Level.NORMAL, a.level)
        assertEquals(WakeProvenance.Verdict.StoppedThenResumed(14_515.0), a.wake)
    }

    @Test
    fun shortTruncatedNightStillFlagsBecauseTheGateIsDurationOnly() {
        // R2_2026-08-17: 102 min in bed at efficiency 1.0000 — three times below minNightForFlag.
        // "Nothing was recorded for 4 hours after this" is exactly as true of a 102-minute night as
        // of a 9-hour one, so the acquisition test must NOT inherit the duration test's length gate.
        val a = SleepConfidence.assess(asleep = mins(102.0), inBed = mins(102.0), coverage = coverage(before = null, after = 14_616.0))
        assertTrue(a.flags)
        assertTrue(mins(102.0) < SleepConfidence.MIN_NIGHT_FOR_FLAG)
    }

    @Test
    fun theGoodNightStaysSilentOnAcquisition() {
        // R3_2026-08-15, worst edge error 8 min: the corpus's ONE accurate labelled night. Its
        // trailing edge is a data edge like every other night's (all 21 are), but the stream is
        // dense on both sides — so no acquisition reason may fire. A flag here is worse than none.
        val a = SleepConfidence.assess(asleep = mins(532.0), inBed = mins(558.0), coverage = coverage(before = 90.0, after = 90.0))
        assertFalse(a.hasAcquisitionReason)
        assertEquals(WakeProvenance.Verdict.Witnessed, a.wake)
        assertEquals(BedtimeProvenance.Verdict.Witnessed, a.bedtime)
    }

    @Test
    fun stagingErrorNightIsADesignedFalseNegative() {
        // R3_2026-08-19: −119 min at the FRONT with dense data on both edges (0.5 min before,
        // 1.5 min after). That is a STAGING miss, not an acquisition one, and an acquisition flag
        // must be silent there. Anyone quoting this rule's hit rate has to say so.
        val a = SleepConfidence.assess(asleep = mins(713.0), inBed = mins(768.0), coverage = coverage(before = 30.0, after = 90.0))
        assertFalse(a.flags, "no acquisition reason, and eff 0.9277 is under implausibleEfficiency")
    }

    // MARK: - The duration verdict is preserved, and yields

    @Test
    fun levelIsAlwaysExactlyTheLegacyClassification() {
        // Every combination of totals × coverage must leave `level` equal to the shipped primitive.
        val totals = listOf(572.0 to 579.0, 553.0 to 629.0, 68.0 to 70.0, 249.0 to 253.0, 0.0 to 0.0)
        val covers = listOf(
            null,
            coverage(before = 150.0, after = 150.0),
            coverage(before = 14_515.0, after = 14_515.0),
            coverage(before = null, after = null),
        )
        for ((asleep, inBed) in totals) {
            for (c in covers) {
                assertEquals(
                    SleepConfidence.classify(asleep = mins(asleep), inBed = mins(inBed)),
                    SleepConfidence.assess(asleep = mins(asleep), inBed = mins(inBed), coverage = c).level,
                    "coverage must never move the duration verdict ($asleep/$inBed)",
                )
            }
        }
    }

    @Test
    fun noCoverageReducesToTheLegacyVerdict() {
        val flagged = SleepConfidence.assess(asleep = mins(572.0), inBed = mins(579.0), coverage = null)
        assertEquals(listOf<SleepConfidence.Reason>(SleepConfidence.Reason.DurationLikelyHigh), flagged.reasons)
        assertEquals(BedtimeProvenance.Verdict.Unknown, flagged.bedtime)
        assertEquals(WakeProvenance.Verdict.Unknown, flagged.wake)

        val quiet = SleepConfidence.assess(asleep = mins(553.0), inBed = mins(629.0), coverage = null)
        assertFalse(quiet.flags)
    }

    @Test
    fun acquisitionSuppressesTheOppositeClaim() {
        // A 9.5 h night at 98.8 % efficiency whose recording ALSO stopped for 4 h. Both tests fire;
        // only one may be shown, because "your duration reads high" and "4 hours are missing" are
        // contradictory statements about the same night.
        val a = SleepConfidence.assess(asleep = mins(572.0), inBed = mins(579.0), coverage = coverage(before = 150.0, after = 14_515.0))
        assertEquals(SleepConfidence.Level.DURATION_LIKELY_HIGH, a.level, "the legacy verdict is still reported")
        assertFalse(a.reasons.contains(SleepConfidence.Reason.DurationLikelyHigh), "but it is not offered as copy")
        assertEquals(afterWake(end, 14_515.0), a.primary)
    }

    /**
     * THE 2026-08-26 TESTER NIGHT — an UNWITNESSED back edge must suppress the duration claim just as
     * a resumed one does. This combination (coverage present, `wake == .unknown`, level
     * `.durationLikelyHigh`) was never pinned, and it is exactly the shape that reached her.
     *
     * Her stream ended at 02:47:30 and she got up at 06:46, so the night read ~4 h LOW — and the
     * only caption the card offered said it "may read a little high". Nothing follows the wake, so
     * `WakeProvenance` cannot distinguish "the ring stopped" from "you synced the moment you woke"
     * and returns `.unknown`; with only the mutual-exclusion rule, no acquisition reason fires and
     * `.durationLikelyHigh` was emitted by default. Her export carries the pair verbatim:
     * `wakeVerdict: "unknown"` beside `reasons: ["durationLikelyHigh"]`.
     */
    @Test
    fun anUnwitnessedWakeSuppressesTheDurationClaim() {
        val a = SleepConfidence.assess(asleep = mins(572.0), inBed = mins(579.0), coverage = coverage(before = 150.0, after = null))
        assertEquals(WakeProvenance.Verdict.Unknown, a.wake)
        assertEquals(SleepConfidence.Level.DURATION_LIKELY_HIGH, a.level, "the legacy verdict is still reported")
        assertFalse(a.reasons.contains(SleepConfidence.Reason.DurationLikelyHigh), "a night we did not watch end must not be told it ran long")
        assertTrue(a.reasons.isEmpty(), "and nothing else may be invented in its place")
    }

    /**
     * The other side of the same rule: the hint is SUPPRESSED, not deleted. A night whose stream
     * runs continuously past the wake is genuinely witnessed, so the legacy claim still ships —
     * otherwise the change would silently retire the signal instead of scoping it.
     */
    @Test
    fun aWitnessedWakeStillEmitsTheDurationClaim() {
        val a = SleepConfidence.assess(asleep = mins(572.0), inBed = mins(579.0), coverage = coverage(before = 150.0, after = 150.0))
        assertEquals(WakeProvenance.Verdict.Witnessed, a.wake)
        assertEquals(listOf<SleepConfidence.Reason>(SleepConfidence.Reason.DurationLikelyHigh), a.reasons)
    }

    // MARK: - Precedence and multiplicity

    @Test
    fun bothEdgesHoleyReportsBothBackEdgeFirst() {
        val a = SleepConfidence.assess(asleep = mins(249.0), inBed = mins(253.0), coverage = coverage(before = 14_478.0, after = 14_515.0))
        assertEquals(
            listOf(afterWake(end, 14_515.0), SleepConfidence.Reason.NoRecordingBeforeBedtime(until = start, silentFor = 14_478.0)),
            a.reasons,
            "the back edge leads: it is where both 246-min corpus errors are and it has no other surface",
        )
    }

    @Test
    fun reasonsCarryTheInstantTheCopyWillPrint() {
        // The point of the whole exercise: "no recording after 02:37 for about 4 hours", not "this
        // night may be incomplete". The instant is the detected edge, i.e. the wake the card already
        // prints — so the caveat and the headline cannot disagree.
        val primary = SleepConfidence.assess(asleep = mins(249.0), inBed = mins(253.0), coverage = coverage(before = 150.0, after = 14_515.0)).primary
        if (primary !is SleepConfidence.Reason.NoRecordingAfterWake) fail("expected a back-edge gap reason")
        assertEquals(end, primary.from)
        assertEquals(14_515.0, primary.silentFor, 0.001)
    }

    // MARK: - Threshold behaviour

    @Test
    fun subMaterialGapsProduceNothing() {
        // 7.5 min (R2_2026-08-02) and 33.0 min (R5_2026-08-11) are real gaps that the corpus cannot
        // adjudicate — neither is labelled. They must not reach the user at the default cut.
        for (gapMinutes in listOf(7.5, 33.0)) {
            val a = SleepConfidence.assess(asleep = mins(249.0), inBed = mins(253.0), coverage = coverage(before = null, after = gapMinutes * 60))
            assertFalse(a.flags, "$gapMinutes min must stay under the default material cut")
        }
    }

    @Test
    fun killSwitchSilencesAcquisitionEntirely() {
        val a = SleepConfidence.assess(
            asleep = mins(249.0), inBed = mins(253.0),
            coverage = coverage(before = 14_478.0, after = 14_515.0),
            materialGapSeconds = Double.POSITIVE_INFINITY,
        )
        assertFalse(a.flags)
        assertEquals(WakeProvenance.Verdict.StoppedThenResumed(14_515.0), a.wake, "the measurement is still reported")
    }

    @Test
    fun zeroThresholdIsMaximallyLoudForSweeps() {
        val a = SleepConfidence.assess(asleep = mins(249.0), inBed = mins(253.0), coverage = coverage(before = 301.0, after = 301.0), materialGapSeconds = 0.0)
        assertEquals(2, a.reasons.size)
    }

    // MARK: - The threshold travels WITH the verdict

    /**
     * A bundle must never misstate the cut behind its own reasons. The threshold used to be a
     * separate defaulted argument on the export row, so a sweep could assess at one value and
     * export another beside those reasons with nothing able to notice.
     */
    @Test
    fun theAssessmentCarriesTheCutItsReasonsWereProducedAt() {
        for (cut in listOf(0.0, 900.0, WakeProvenance.MATERIAL_GAP_SECONDS, Double.POSITIVE_INFINITY)) {
            val a = SleepConfidence.assess(asleep = mins(249.0), inBed = mins(253.0), coverage = coverage(before = 150.0, after = 14_515.0), materialGapSeconds = cut)
            assertEquals(cut, a.materialGapSeconds, "cut $cut")
        }
        assertEquals(
            WakeProvenance.MATERIAL_GAP_SECONDS,
            SleepConfidence.assess(asleep = mins(249.0), inBed = mins(253.0), coverage = coverage(before = 150.0, after = 14_515.0)).materialGapSeconds,
            "the default must be the constant, not a second copy of the number",
        )
    }

    /**
     * …including on the no-coverage path, which returns early. That branch produced the legacy
     * verdict and would otherwise have had to invent a threshold for a row it never measured.
     */
    @Test
    fun theCutIsCarriedEvenWithNoCoverageAtAll() {
        assertEquals(42.0, SleepConfidence.assess(asleep = mins(572.0), inBed = mins(579.0), coverage = null, materialGapSeconds = 42.0).materialGapSeconds)
    }

    /**
     * And it reaches the file: the export row reads the cut off the assessment rather than
     * defaulting, so a swept run cannot label its reasons with the shipped default.
     */
    @Test
    fun theExportRowTakesTheCutFromTheAssessmentNotADefault() {
        val a = SleepConfidence.assess(asleep = mins(249.0), inBed = mins(253.0), coverage = coverage(before = 150.0, after = 14_515.0), materialGapSeconds = 900.0)
        val row = ExportEngine.SleepEdgeProvenanceRow(windowStart = start, windowEnd = end, assessment = a)
        assertEquals(900.0, row.materialGapSeconds)
        assertNotEquals(WakeProvenance.MATERIAL_GAP_SECONDS, row.materialGapSeconds, "fixture must differ from the default or this cannot fail")
        assertEquals(listOf("noRecordingAfterWake"), row.reasons, "…and the reasons it labels really were produced at that cut")
    }

    @Test
    fun unknownEdgesNeverFlagAtAnyThreshold() {
        // 6 of 21 corpus nights have nothing after the in-bed end at all. "The ring stopped" and
        // "you synced at wake" are the same picture, so this branch ships silent — even at 0.
        for (threshold in listOf(0.0, WakeProvenance.MATERIAL_GAP_SECONDS)) {
            val a = SleepConfidence.assess(
                asleep = mins(249.0), inBed = mins(253.0),
                coverage = coverage(before = null, after = null, earliestDaysBack = 0.0),
                materialGapSeconds = threshold,
            )
            assertFalse(a.hasAcquisitionReason, "threshold $threshold")
            assertEquals(WakeProvenance.Verdict.Unknown, a.wake)
        }
    }

    // MARK: - Summary overload

    // MARK: - Retention (found integrating the sleep card)
    //
    // The guard itself now lives in `WakeProvenance.classify`, mirroring the leading edge, and is
    // asserted there directly (`WakeProvenanceTest`, "Retention"). It moved because leaving it
    // here made the OBVIOUS API — the public classifier — the unsafe one: any second caller had to
    // remember to reapply it. These three tests stay, unchanged in expectation, as the end-to-end
    // check that `assess` still routes both edges through the guarded path.

    /**
     * A night older than the store's sample retention keeps its summary row and loses every raw
     * sample around it. `earliestSample(after:)` then returns the OLDEST SURVIVING ROW — the
     * retention boundary, days later — and an unguarded classifier reads that as a resume, telling
     * the wearer "nothing was recorded between 02:37 and <three weeks later>". That is local
     * housekeeping reported as a hole in the night.
     *
     * The tell: the oldest row we still hold is NEWER than the night's trailing edge.
     */
    @Test
    fun anAgedOutNightDoesNotReportRetentionPruningAsAHole() {
        val pruned = SleepConfidence.Coverage(
            inBedStart = start,
            inBedEnd = end,
            // Everything before the night was pruned too, which is why the FRONT edge is already
            // safe: BedtimeProvenance answers .unknown with no predecessor and no deep retention.
            lastMeasurementBeforeStart = null,
            // …but the oldest surviving row sits 21 days AFTER this night ended.
            firstMeasurementAfterEnd = end.adding(21 * 86_400.0),
            measurementsAfterEnd = emptyList(),
            earliestRetainedMeasurement = end.adding(21 * 86_400.0),
        )
        val a = SleepConfidence.assess(asleep = mins(249.0), inBed = mins(253.0), coverage = pruned)
        assertEquals(
            WakeProvenance.Verdict.Unknown, a.wake,
            "retention no longer reaches this night's end — we cannot judge, so we must not claim a 21-day silence",
        )
        assertEquals(BedtimeProvenance.Verdict.Unknown, a.bedtime)
        assertTrue(a.reasons.isEmpty(), "an unjudgeable night must say nothing: ${a.reasons}")
        // No reasons ⇒ nothing to render and nothing to export. The card's own rendering of this is
        // asserted on the parked coverage-card branch; here the reason list IS the surface, because
        // it is what `sleepSessions[].edgeProvenance.reasons` and the diagnostics line carry.
        assertEquals(emptyList(), a.reasons.map { SleepConfidence.exportName(it) })
    }

    /**
     * The guard must not swallow the real case: when retention DOES reach past the edge, the same
     * gap is evidence and must still be reported. (Identical inputs to the test above except that
     * the store also holds rows from before the night.)
     */
    @Test
    fun theGuardOnlyFiresWhenRetentionStopsShortOfTheEdge() {
        val a = SleepConfidence.assess(asleep = mins(249.0), inBed = mins(253.0), coverage = coverage(before = 150.0, after = 21 * 86_400.0))
        assertEquals(WakeProvenance.Verdict.StoppedThenResumed(21 * 86_400.0), a.wake)
        assertTrue(a.hasAcquisitionReason)
    }

    /**
     * A nil `earliestRetainedMeasurement` means the caller supplied no retention information, not
     * "retention stops here" — and the corpus harness withholds it on `R2_2026-08-17`, one of the
     * two 246-minute nights this whole change exists for. The guard must not silence it.
     */
    @Test
    fun missingRetentionInformationDoesNotSilenceTheWakeVerdict() {
        val a = SleepConfidence.assess(
            asleep = mins(102.0), inBed = mins(102.0),
            coverage = SleepConfidence.Coverage(
                inBedStart = start, inBedEnd = end,
                lastMeasurementBeforeStart = null,
                firstMeasurementAfterEnd = end.adding(14_616.0),
                measurementsAfterEnd = emptyList(),
                earliestRetainedMeasurement = null,
            ),
        )
        assertEquals(WakeProvenance.Verdict.StoppedThenResumed(14_616.0), a.wake)
        assertEquals(listOf(afterWake(end, 14_616.0)), a.reasons)
    }

    @Test
    fun summaryOverloadMatchesThePrimitive() {
        val s = SleepStaging.Summary(
            inBed = Duration.ofMinutes(253), awake = Duration.ofMinutes(4),
            light = Duration.ofMinutes(160), deep = Duration.ofMinutes(40), rem = Duration.ofMinutes(49),
        )
        val c = coverage(before = 150.0, after = 14_515.0)
        assertEquals(
            SleepConfidence.assess(asleep = s.totalAsleep.seconds.toDouble(), inBed = s.inBed.seconds.toDouble(), coverage = c),
            SleepConfidence.assess(s, coverage = c),
        )
    }
}
