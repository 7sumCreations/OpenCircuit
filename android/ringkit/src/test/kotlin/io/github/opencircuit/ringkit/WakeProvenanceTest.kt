package io.github.opencircuit.ringkit

import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The nights this exists for: two Gen 2 Air nights of one tester. Staging closed both nights at
 * 02:39:14 / 02:37:02, the record stream resumed 243.6 / 241.9 min later, and each night's duration
 * read 246 min LOW against the user's own edit — with the ~4 h hole starting exactly AT the in-bed end,
 * where no internal-hole test can see it. The instants below are the measured ones, as absolute time.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/WakeProvenanceTests.swift (@ b1c2fdd)
 * — all 28 tests. The two calendar checks read the components in a named offset (+02:00 and UTC), as
 * upstream does.
 */
class WakeProvenanceTest {

    /** 2026-08-18 02:37:02 +02:00 — the night's detected in-bed end (stored to the second). */
    private val stopped: Instant = Instant.ofEpochSecond(1_787_013_422)
    private fun t(offsetFromStopped: Long): Instant = stopped.plusSeconds(offsetFromStopped)
    private val tolerance: Long = WakeProvenance.CONTINUOUS_TOLERANCE_SECONDS.toLong()

    /**
     * The classifier with retention deep enough (30 days before the edge) that the retention guard
     * never fires. `earliestRetainedMeasurement` has NO DEFAULT in the real signature, on purpose —
     * do not add one to make these call sites shorter.
     */
    private fun edgeVerdict(after: Instant?): WakeProvenance.Verdict =
        WakeProvenance.classify(inBedEnd = stopped, firstMeasurementAfter = after, earliestRetainedMeasurement = t(-30L * 86_400))

    private fun deepRetention(before: Instant): Instant = before.minusSeconds(30L * 86_400)

    /** The fixture's own provenance: the epoch really is the instant the comment names, in the ring's zone. */
    @Test
    fun fixtureInstantIsTheNightItClaimsToBe() {
        val c = stopped.atOffset(ZoneOffset.ofHours(2))
        assertEquals(listOf(2026, 8, 18, 2, 37, 2), listOf(c.year, c.monthValue, c.dayOfMonth, c.hour, c.minute, c.second))
    }

    // MARK: the nights it exists for

    @Test
    fun testerBNightIsStoppedThenResumed() {
        // Records resume 06:38:57, i.e. 241.9 min = 14_515 s later.
        val verdict = edgeVerdict(t(14_515))
        assertEquals(WakeProvenance.Verdict.StoppedThenResumed(14_515.0), verdict)
        assertTrue(WakeProvenance.isMaterial(verdict))
    }

    @Test
    fun theGapIsReportedNotTheMissingSleep() {
        // The verdict must carry the GAP and nothing derived from it.
        val verdict = edgeVerdict(t(14_515)) as? WakeProvenance.Verdict.StoppedThenResumed ?: fail("expected a gap verdict")
        assertEquals(14_515.0, verdict.seconds, 0.001, "the associated value is the measured silence, never an inferred sleep total")
    }

    // MARK: continuity — the nights that must stay silent

    @Test
    fun streamContinuingPastWakeIsWitnessed() {
        // One 150 s epoch later: the stager chose this edge while data kept arriving.
        val verdict = edgeVerdict(t(150))
        assertEquals(WakeProvenance.Verdict.Witnessed, verdict)
        assertFalse(WakeProvenance.isMaterial(verdict))
    }

    @Test
    fun oneDroppedEpochStillCountsAsContinuous() {
        assertEquals(WakeProvenance.Verdict.Witnessed, edgeVerdict(t(tolerance)))
    }

    @Test
    fun justBeyondToleranceIsAGapButNotYetMaterial() {
        val verdict = edgeVerdict(t(tolerance + 1))
        assertEquals(WakeProvenance.Verdict.StoppedThenResumed(WakeProvenance.CONTINUOUS_TOLERANCE_SECONDS + 1), verdict)
        assertFalse(WakeProvenance.isMaterial(verdict), "a 5-minute gap is a measurement, not something to tell a user about")
    }

    // MARK: the two constants

    @Test
    fun toleranceIsTheSameConstantAsTheLeadingEdge() {
        // A front/back asymmetry would be unexplainable to anyone reading the two hints together.
        assertEquals(BedtimeProvenance.CONTINUOUS_TOLERANCE_SECONDS, WakeProvenance.CONTINUOUS_TOLERANCE_SECONDS)
        assertEquals(300.0, WakeProvenance.CONTINUOUS_TOLERANCE_SECONDS)
    }

    @Test
    fun materialCutSitsInTheEmptyIntervalTheCorpusMeasured() {
        // Sorted corpus gaps after the in-bed end (minutes): … 2.5, 7.5, 33.0, 241.9, 243.6. Every value
        // in (33.0, 241.9] separates the clusters identically — assert the PROPERTY, not the number.
        val cut = WakeProvenance.MATERIAL_GAP_SECONDS / 60
        assertTrue(cut > 33.0, "would newly flag an unlabelled Gen 3 night")
        assertTrue(cut <= 241.9, "would stop flagging a 246-min error")
    }

    @Test
    fun thresholdIsCallerOverridableInBothDirections() {
        val fiveMinuteGap = edgeVerdict(t(450))
        assertFalse(WakeProvenance.isMaterial(fiveMinuteGap))
        assertTrue(WakeProvenance.isMaterial(fiveMinuteGap, threshold = 0.0), "0 = maximally loud, for a sweep")
        val bigGap = edgeVerdict(t(14_515))
        assertFalse(WakeProvenance.isMaterial(bigGap, threshold = Double.POSITIVE_INFINITY), "infinity is the kill switch")
    }

    // MARK: absence — the nights that CANNOT be adjudicated

    @Test
    fun noLaterMeasurementIsUnknownNotStopped() {
        // "The ring stopped recording" and "you synced the moment you woke" are the same picture.
        val verdict = edgeVerdict(null)
        assertEquals(WakeProvenance.Verdict.Unknown, verdict)
        assertFalse(WakeProvenance.isMaterial(verdict), "an unadjudicable night must never produce user copy")
    }

    @Test
    fun unknownStaysUnknownAtEveryThreshold() {
        assertFalse(WakeProvenance.isMaterial(edgeVerdict(null), threshold = 0.0), "even maximally loud, 'we could not tell' must not become a claim")
    }

    // MARK: retention — the guard that used to live in the caller

    /**
     * Raw samples are pruned at 30 days while the night's summary is kept, so an aged-out night's
     * "next" sample is the OLDEST SURVIVING ROW, days later. Unguarded, that reads as a resume.
     */
    @Test
    fun retentionThatStopsShortOfTheEdgeIsUnknownNotAThreeWeekSilence() {
        val boundary = t(21L * 86_400)
        assertEquals(
            WakeProvenance.Verdict.Unknown,
            WakeProvenance.classify(inBedEnd = stopped, firstMeasurementAfter = boundary, earliestRetainedMeasurement = boundary),
            "the oldest row we hold is NEWER than this night's end, so everything at and after that edge was pruned and the 'next' measurement is a boundary, not evidence",
        )
    }

    /** The guard must not swallow the real case: retention deep enough to vouch for the silence. */
    @Test
    fun theSameGapIsStillReportedWhenRetentionReachesPastTheEdge() {
        val verdict = WakeProvenance.classify(inBedEnd = stopped, firstMeasurementAfter = t(21L * 86_400), earliestRetainedMeasurement = t(-30L * 86_400))
        assertEquals(WakeProvenance.Verdict.StoppedThenResumed(21.0 * 86_400), verdict)
        assertTrue(WakeProvenance.isMaterial(verdict))
    }

    /** Null means "the caller has no retention information", NOT "retention stops here". */
    @Test
    fun missingRetentionInformationLeavesTheRawVerdictStanding() {
        assertEquals(
            WakeProvenance.Verdict.StoppedThenResumed(14_616.0),
            WakeProvenance.classify(inBedEnd = stopped, firstMeasurementAfter = t(14_616), earliestRetainedMeasurement = null),
        )
    }

    /** Retention reaching EXACTLY the edge is not "short of" it (`>` not `>=`). */
    @Test
    fun retentionExactlyAtTheEdgeStillJudges() {
        assertEquals(
            WakeProvenance.Verdict.StoppedThenResumed(14_515.0),
            WakeProvenance.classify(inBedEnd = stopped, firstMeasurementAfter = t(14_515), earliestRetainedMeasurement = stopped),
        )
    }

    /** Both edges answer the retention question themselves, from the same field. */
    @Test
    fun bothEdgesGuardRetentionTheSameWay() {
        val boundary = t(21L * 86_400)
        assertEquals(
            WakeProvenance.Verdict.Unknown,
            WakeProvenance.classify(inBedEnd = stopped, firstMeasurementAfter = boundary, earliestRetainedMeasurement = boundary),
        )
        assertEquals(
            BedtimeProvenance.Verdict.Unknown,
            BedtimeProvenance.classify(inBedStart = stopped, lastMeasurementBefore = null, earliestRetainedMeasurement = boundary),
        )
    }

    // MARK: caller hazards

    @Test
    fun measurementAtOrBeforeTheEdgeIsUnknownNotANegativeGap() {
        // The newest record INSIDE the window is always ≤ inBedEnd, so a caller that queries `>= end`
        // instead of `> end` trips this on every night.
        for (offset in listOf(-60L, 0L)) {
            assertEquals(WakeProvenance.Verdict.Unknown, edgeVerdict(t(offset)), "offset $offset must not produce a negative gap")
        }
    }

    // MARK: the run walk (the one-epoch defeat)

    /**
     * THE REGRESSION THIS WALK EXISTS FOR — a Gen 2 Air tester night (2026-09-01) read from her own
     * diagnostics export: in-bed end 01:32:21Z, the archive's LAST record 30 s later, then nothing
     * until a live heart rate at 05:35:20.951Z. The single-step rule answered "witnessed".
     */
    @Test
    fun oneEpochOfDataDoesNotBuyAWitnessedVerdict() {
        val end = Instant.ofEpochSecond(1_788_226_341) // 2026-09-01T01:32:21Z
        val resumed = end.plusSeconds(30) // 01:32:51Z, the archive's last record
        val morning = end.plusMillis(30_000 + 14_549_951) // 05:35:20.951Z, a live heart rate

        // What shipped before the walk, from exactly these instants.
        assertEquals(
            WakeProvenance.Verdict.Witnessed,
            WakeProvenance.classify(inBedEnd = end, firstMeasurementAfter = resumed, earliestRetainedMeasurement = deepRetention(end)),
            "precondition: the single-step rule is defeated by one epoch",
        )

        val walked = WakeProvenance.classify(inBedEnd = end, measurementsAfter = listOf(resumed, morning), earliestRetainedMeasurement = deepRetention(end))
        val gap = walked as? WakeProvenance.Verdict.StoppedThenResumed ?: fail("expected a stop, got $walked")
        assertEquals(14_549.951, gap.seconds, 0.001)
        assertTrue(WakeProvenance.isMaterial(walked))
    }

    /** A ring worn through the morning emits every 150 s; the first real break 3 h later is daytime. */
    @Test
    fun aRunThatCarriesOnPastTheBoundStaysWitnessed() {
        val series = ArrayList<Instant>()
        var offset = 150L
        while (offset <= 3 * 3600) { series += t(offset); offset += 150 }
        series += t(3 * 3600 + 4 * 3600) // a 4 h daytime disconnect, hours past the edge
        assertEquals(
            WakeProvenance.Verdict.Witnessed,
            WakeProvenance.classify(inBedEnd = stopped, measurementsAfter = series, earliestRetainedMeasurement = deepRetention(stopped)),
        )
    }

    /** A hole that begins just INSIDE the bound is still this night's; just outside it is not. */
    @Test
    fun theBoundIsTheHoleSTARTNotItsSize() {
        fun verdict(runLength: Long): WakeProvenance.Verdict {
            val series = ArrayList<Instant>()
            var offset = 150L
            while (offset <= runLength) { series += t(offset); offset += 150 }
            series += t(runLength + 4 * 3600)
            return WakeProvenance.classify(inBedEnd = stopped, measurementsAfter = series, earliestRetainedMeasurement = deepRetention(stopped), resumeRunLimit = 600.0)
        }
        assertEquals(WakeProvenance.Verdict.StoppedThenResumed(4.0 * 3600), verdict(600))
        assertEquals(WakeProvenance.Verdict.Witnessed, verdict(750))
    }

    /** The kill switch must reproduce the single-step rule EXACTLY. */
    @Test
    fun walkKillSwitchReproducesSingleStep() {
        val series = listOf(t(150), t(150 + 14_429), t(150 + 14_429 + 150))
        assertEquals(
            WakeProvenance.classify(inBedEnd = stopped, firstMeasurementAfter = series.first(), earliestRetainedMeasurement = deepRetention(stopped)),
            WakeProvenance.classify(inBedEnd = stopped, measurementsAfter = series, earliestRetainedMeasurement = deepRetention(stopped), resumeRunLimit = 0.0),
        )
    }

    /** Additive only: the walk may upgrade a witnessed verdict, never touch the other two. */
    @Test
    fun walkNeverSilencesAStopAndNeverInventsOne() {
        // Already a stop at the first step — returned untouched, not re-measured.
        assertEquals(
            WakeProvenance.Verdict.StoppedThenResumed(14_616.0),
            WakeProvenance.classify(inBedEnd = stopped, measurementsAfter = listOf(t(14_616), t(14_766)), earliestRetainedMeasurement = deepRetention(stopped)),
        )
        // Nothing after the edge stays unknown.
        assertEquals(
            WakeProvenance.Verdict.Unknown,
            WakeProvenance.classify(inBedEnd = stopped, measurementsAfter = emptyList(), earliestRetainedMeasurement = deepRetention(stopped)),
        )
        // Retention no longer reaches the night — unknown regardless of what the series holds.
        assertEquals(
            WakeProvenance.Verdict.Unknown,
            WakeProvenance.classify(inBedEnd = stopped, measurementsAfter = listOf(t(150), t(150 + 14_429)), earliestRetainedMeasurement = t(60)),
        )
        // An unbroken run to the end of what we hold is not evidence of a stop.
        assertEquals(
            WakeProvenance.Verdict.Witnessed,
            WakeProvenance.classify(inBedEnd = stopped, measurementsAfter = listOf(t(150), t(300), t(450)), earliestRetainedMeasurement = deepRetention(stopped)),
        )
    }

    /** Unsorted and duplicated input must not change the answer — the real caller reads a store. */
    @Test
    fun walkIsOrderAndDuplicateInsensitive() {
        val jumbled = listOf(t(150 + 14_429), t(150), t(150), t(150 + 14_429))
        assertEquals(
            WakeProvenance.Verdict.StoppedThenResumed(14_429.0),
            WakeProvenance.classify(inBedEnd = stopped, measurementsAfter = jumbled, earliestRetainedMeasurement = deepRetention(stopped)),
        )
    }

    /** The tester-night fixture literal must BE the night it claims to be. */
    @Test
    fun testerNightFixtureLiteralIsTheInstantItClaims() {
        val c = Instant.ofEpochSecond(1_788_226_341).atOffset(ZoneOffset.UTC)
        assertEquals(listOf(2026, 9, 1, 1, 32, 21), listOf(c.year, c.monthValue, c.dayOfMonth, c.hour, c.minute, c.second))
    }

    /**
     * The walk must report WHERE the silence began, not just how long it lasted: heart rates at
     * +60/+210 s, then the stream resumes 3h 30m after that last record.
     */
    @Test
    fun theReportedSilenceStartsAtTheLastRecordNotTheEdge() {
        val run = listOf(t(60), t(210))
        val resume = t(210 + 12_600)
        val s = WakeProvenance.stoppage(inBedEnd = stopped, measurementsAfter = run + resume, earliestRetainedMeasurement = deepRetention(stopped))
        assertEquals(WakeProvenance.Verdict.StoppedThenResumed(12_600.0), s.verdict)
        assertEquals(t(210), s.silenceBegan, "the silence began at the LAST record, not inBedEnd")
        // The invariant the copy depends on.
        val gap = s.verdict as? WakeProvenance.Verdict.StoppedThenResumed ?: fail("expected a stop")
        val from = s.silenceBegan ?: fail("expected a stop")
        assertEquals(resume, from.plusMillis((gap.seconds * 1000).toLong()), "from + silentFor must be the instant recording resumed")
    }

    /** A ring worn well past the staged wake and THEN removed is witnessed and silent, as shipped. */
    @Test
    fun aRingWornSeveralMinutesPastWakeThenRemovedStaysSilent() {
        val run = listOf(t(60), t(210), t(360), t(510)) // still recording 8.5 min past the edge
        assertEquals(
            WakeProvenance.Verdict.Witnessed,
            WakeProvenance.classify(inBedEnd = stopped, measurementsAfter = run + t(510 + 12_600), earliestRetainedMeasurement = deepRetention(stopped)),
        )
    }

    /** A hole beginning exactly at the edge still reports the edge — the single-step behaviour. */
    @Test
    fun aSilenceAtTheEdgeStillReportsTheEdge() {
        val s = WakeProvenance.stoppage(inBedEnd = stopped, measurementsAfter = listOf(t(14_616)), earliestRetainedMeasurement = deepRetention(stopped))
        assertEquals(WakeProvenance.Verdict.StoppedThenResumed(14_616.0), s.verdict)
        assertEquals(stopped, s.silenceBegan)
    }

    /** Pins a deliberate non-monotonicity: adding a real record can OPEN a hole. */
    @Test
    fun addingARecordCanOpenAHoleAndThatIsIntended() {
        val retention = deepRetention(stopped)
        assertEquals(
            WakeProvenance.Verdict.Witnessed,
            WakeProvenance.classify(inBedEnd = stopped, measurementsAfter = listOf(t(30)), earliestRetainedMeasurement = retention),
        )
        assertEquals(
            WakeProvenance.Verdict.StoppedThenResumed(370.0),
            WakeProvenance.classify(inBedEnd = stopped, measurementsAfter = listOf(t(30), t(400)), earliestRetainedMeasurement = retention),
        )
    }
}
