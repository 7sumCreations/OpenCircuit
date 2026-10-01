package io.github.opencircuit.ringkit

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The card's SENTENCES, asserted as claims rather than as strings.
 *
 * Most of these do not check wording — they check that a sentence does not make a claim the
 * measurement cannot support. The three rules from the file header, as tests:
 *
 *   1. no cause is named ("the ring stopped", "charging", "you took it off"),
 *   2. the measured gap appears and no sleep TOTAL is inferred from it,
 *   3. the wearer is pointed at Edit, the only lever they have (and the supervised label we lack).
 *
 * Plus the structural one that a golden-string test would miss entirely: the duration note and an
 * acquisition note are OPPOSITE claims about the same night and must never both render.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepConfidenceCopyTests.swift
 * (@ b1c2fdd) — all 13 tests.
 */
class SleepConfidenceCopyTest {

    private val t0: Instant = Instant.ofEpochSecond(1_723_000_000) // arbitrary, fixed

    /** A fixed 24 h clock so the assertions never depend on the runner's locale. */
    private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC)
    private fun clock(d: Instant): String = formatter.format(d)

    /** Swift's `addingTimeInterval`, to the nanosecond. */
    private fun Instant.adding(seconds: Double): Instant = plusNanos((seconds * 1e9).roundToLong())

    private fun hints(asleep: Double, inBed: Double, before: Instant?, after: Instant?, start: Instant, end: Instant): List<SleepConfidence.Hint> =
        SleepConfidence.hints(
            SleepConfidence.assess(
                asleep = asleep, inBed = inBed,
                coverage = SleepConfidence.Coverage(
                    inBedStart = start, inBedEnd = end,
                    lastMeasurementBeforeStart = before,
                    firstMeasurementAfterEnd = after,
                    // Empty: these cases predate the run walk and exercise the single-step rule,
                    // which an empty series reproduces exactly (`WakeProvenance.classify`).
                    measurementsAfterEnd = emptyList(),
                    earliestRetainedMeasurement = start.adding(-7 * 86_400.0),
                ),
            ),
            ::clock,
        )

    // MARK: - The back edge — the R2_2026-08-18 shape

    /**
     * 253 min in bed, recording stops at the wake and resumes 241.9 min later. Nothing shipped
     * before this change said a word about this night; it is understated by 246 minutes.
     */
    @Test
    fun stoppedAtWakeNamesBothInstantsAndTheGap() {
        val start = t0
        val end = t0.adding(253 * 60.0)
        val list = hints(asleep = 250 * 60.0, inBed = 253 * 60.0, before = start.adding(-60.0), after = end.adding(241.9 * 60), start = start, end = end)
        assertEquals(1, list.size)
        val text = list[0].text
        assertTrue(text.contains(clock(end)), "must name where the data ends")
        assertTrue(text.contains(clock(end.adding(241.9 * 60))), "must name where it resumes — that is what makes the gap checkable")
        assertTrue(text.contains("4h 2m"), "must state the MEASURED gap: $text")
        assertTrue(text.contains("Edit"), "must point at the only lever the wearer has")
    }

    /**
     * The gap BOUNDS the error, it does not estimate it (n = 3; on `R1_2026-08-16` a 241.3 min hole
     * sits against a 120.0 min error, because she went to bed INSIDE the hole). So the ONLY
     * duration a sentence may contain is the measured gap itself — any second figure would be an
     * inferred sleep total dressed as a measurement.
     *
     * Asserted structurally, on the numbers, rather than as a blocklist of phrasings: a blocklist
     * also rejects the CONDITIONAL "if you slept longer", which asserts nothing and is the whole
     * point of the sentence. What must never appear is a second quantity.
     */
    @Test
    fun theOnlyDurationInTheCopyIsTheMeasuredGap() {
        val start = t0
        val end = t0.adding(253 * 60.0)
        val backGap = 241.9 * 60
        val frontGap = 149.3 * 60
        val list = hints(asleep = 250 * 60.0, inBed = 253 * 60.0, before = start.adding(-frontGap), after = end.adding(backGap), start = start, end = end)
        assertEquals(2, list.size, "fixture must produce both edges")

        // Every "<n>h <n>m" / "<n> hours" / "<n> minutes" token in a sentence, in order.
        val pattern = Regex("""\d+h \d+m|\d+ hours?|\d+ minutes?""")
        for (hint in list) {
            val text = hint.text
            val found = pattern.findAll(text).map { it.value }.toList()
            val expected = when (val reason = hint.reason) {
                is SleepConfidence.Reason.NoRecordingAfterWake -> SleepConfidence.approximateDuration(reason.silentFor)
                is SleepConfidence.Reason.NoRecordingBeforeBedtime -> SleepConfidence.approximateDuration(reason.silentFor)
                SleepConfidence.Reason.DurationLikelyHigh -> ""
            }
            assertEquals(listOf(expected), found, "the only duration in this sentence must be the measured gap: $text")
        }
    }

    /**
     * A stopped ring, a dead battery, a contended resume pointer (#188) and the wearer taking the
     * ring off are indistinguishable from the persisted stream — and when the cause is our own
     * sync, blaming the device is wrong, not merely unsupported.
     */
    @Test
    fun noCopyNamesACause() {
        val start = t0
        val end = t0.adding(253 * 60.0)
        val all = hints(asleep = 250 * 60.0, inBed = 253 * 60.0, before = start.adding(-60.0), after = end.adding(241.9 * 60), start = start, end = end).map { it.text } +
            hints(asleep = 250 * 60.0, inBed = 253 * 60.0, before = start.adding(-241.3 * 60), after = null, start = start, end = end).map { it.text }
        for (text in all) {
            for (cause in listOf("ring stopped", "stopped recording", "charging", "battery", "took it off", "didn’t transfer", "didn't transfer", "RingConn")) {
                assertFalse(text.lowercase(Locale.ROOT).contains(cause.lowercase(Locale.ROOT)), "'$cause' is a cause we cannot observe: $text")
            }
        }
    }

    // MARK: - Mutual exclusion

    /**
     * "Your duration may read a little HIGH" on a night whose recording stopped for four hours is
     * the INVERSE of the truth — the measured error on both such corpus nights is −246 min.
     */
    @Test
    fun acquisitionSilencesTheDurationNote() {
        val start = t0
        val end = t0.adding(6 * 3600.0)
        // Efficiency 0.99 over a 6 h night ⇒ classify() alone says .durationLikelyHigh.
        val asleep = 6 * 3600 * 0.99
        assertEquals(SleepConfidence.Level.DURATION_LIKELY_HIGH, SleepConfidence.classify(asleep = asleep, inBed = 6 * 3600.0), "fixture must trip the legacy rule")
        val list = hints(asleep = asleep, inBed = 6 * 3600.0, before = start.adding(-60.0), after = end.adding(4 * 3600.0), start = start, end = end)
        assertEquals(listOf<SleepConfidence.Reason>(SleepConfidence.Reason.NoRecordingAfterWake(from = end, silentFor = 4 * 3600.0)), list.map { it.reason })
        assertFalse(list.any { it.text.contains("read a little high") })
    }

    /**
     * The same night with a WITNESSED wake keeps the shipped duration note, word for word — the
     * legacy signal is superseded in place, not deleted.
     */
    @Test
    fun witnessedNightStillGetsTheShippedDurationSentence() {
        val start = t0
        val end = t0.adding(6 * 3600.0)
        val list = hints(asleep = 6 * 3600 * 0.99, inBed = 6 * 3600.0, before = start.adding(-60.0), after = end.adding(60.0), start = start, end = end)
        assertEquals(listOf<SleepConfidence.Reason>(SleepConfidence.Reason.DurationLikelyHigh), list.map { it.reason })
        assertEquals(
            "Very still night — duration may read a little high. The ring can't sense " +
                "motionless wakefulness (no movement, near-sleep heart rate), so quiet " +
                "time awake in bed is counted as light sleep.",
            list[0].text,
        )
    }

    /**
     * The front edge keeps the #198 sentence VERBATIM. Changing wording already on testers' phones
     * would add a variable to a change whose purpose is to measure the new signal.
     */
    @Test
    fun frontEdgeSentenceIsTheShippedOne() {
        val start = t0
        val end = t0.adding(329 * 60.0)
        val list = hints(asleep = 246 * 60.0, inBed = 329 * 60.0, before = start.adding(-241.3 * 60), after = end.adding(60.0), start = start, end = end)
        assertEquals(1, list.size)
        assertEquals(
            "${clock(start)} is when the ring started recording again, not when you " +
                "settled — it recorded nothing for 4h 1m before that. If you were already " +
                "in bed, tap Edit to correct it.",
            list[0].text,
        )
    }

    // MARK: - Silence

    @Test
    fun witnessedOnBothEdgesAndPlausibleEfficiencySaysNothing() {
        val start = t0
        val end = t0.adding(8 * 3600.0)
        assertTrue(hints(asleep = 7 * 3600.0, inBed = 8 * 3600.0, before = start.adding(-60.0), after = end.adding(60.0), start = start, end = end).isEmpty())
    }

    /**
     * `.unknown` must SHIP SILENT: 6 of 21 corpus nights land there and the corpus cannot adjudicate
     * one of them. Nothing after the edge is equally consistent with "the ring stopped" and with
     * "the drain has not reached that far yet".
     */
    @Test
    fun nothingAfterTheEdgeSaysNothing() {
        val start = t0
        val end = t0.adding(8 * 3600.0)
        val list = hints(asleep = 7 * 3600.0, inBed = 8 * 3600.0, before = start.adding(-60.0), after = null, start = start, end = end)
        assertTrue(list.isEmpty(), "an unknown trailing edge must not produce copy")
    }

    /**
     * Ordering IS the precedence: the back edge first, because it is where both 246-minute corpus
     * errors are and the only edge with no other surface in the app.
     */
    @Test
    fun bothEdgesRenderBackEdgeFirst() {
        val start = t0
        val end = t0.adding(698 * 60.0)
        val list = hints(asleep = 587 * 60.0, inBed = 698 * 60.0, before = start.adding(-149.3 * 60), after = end.adding(243.2 * 60), start = start, end = end)
        assertEquals(2, list.size)
        if (list[0].reason !is SleepConfidence.Reason.NoRecordingAfterWake) fail("back edge must come first, got ${list[0].reason}")
        if (list[1].reason !is SleepConfidence.Reason.NoRecordingBeforeBedtime) fail("front edge must come second, got ${list[1].reason}")
    }

    /** The kill switch reaches the copy, not just the classifier. */
    @Test
    fun infiniteThresholdRendersNoAcquisitionCopy() {
        val start = t0
        val end = t0.adding(253 * 60.0)
        val a = SleepConfidence.assess(
            asleep = 250 * 60.0, inBed = 253 * 60.0,
            coverage = SleepConfidence.Coverage(
                inBedStart = start, inBedEnd = end,
                lastMeasurementBeforeStart = start.adding(-241.3 * 60),
                firstMeasurementAfterEnd = end.adding(241.9 * 60),
                measurementsAfterEnd = emptyList(),
                earliestRetainedMeasurement = start.adding(-7 * 86_400.0),
            ),
            materialGapSeconds = Double.POSITIVE_INFINITY,
        )
        assertTrue(SleepConfidence.hints(a, ::clock).isEmpty())
    }

    // MARK: - Gap rendering

    @Test
    fun approximateDurationNeverPrintsSecondsAndRoundsToTheEpoch() {
        assertEquals("1 minute", SleepConfidence.approximateDuration(0.0), "a sub-minute gap still reads as a minute — the cadence is 150 s")
        assertEquals("2 minutes", SleepConfidence.approximateDuration(90.0))
        assertEquals("1 hour", SleepConfidence.approximateDuration(3_600.0))
        assertEquals("2 hours", SleepConfidence.approximateDuration(7_200.0))
        assertEquals("4h 2m", SleepConfidence.approximateDuration(241.9 * 60))
    }

    // MARK: - Wire names

    /**
     * These strings are a wire format for the diagnostics bundle and the data export: a rename
     * silently invalidates every bundle already collected.
     */
    @Test
    fun exportNamesArePinned() {
        assertEquals("durationLikelyHigh", SleepConfidence.exportName(SleepConfidence.Reason.DurationLikelyHigh))
        assertEquals("noRecordingAfterWake", SleepConfidence.exportName(SleepConfidence.Reason.NoRecordingAfterWake(from = t0, silentFor = 1.0)))
        assertEquals("noRecordingBeforeBedtime", SleepConfidence.exportName(SleepConfidence.Reason.NoRecordingBeforeBedtime(until = t0, silentFor = 1.0)))
        assertEquals("resumedAfterGap", SleepConfidence.exportName(BedtimeProvenance.Verdict.ResumedAfterGap(1.0)))
        assertEquals("stoppedThenResumed", SleepConfidence.exportName(WakeProvenance.Verdict.StoppedThenResumed(1.0)))
        assertEquals("unknown", SleepConfidence.exportName(WakeProvenance.Verdict.Unknown))
    }

    /**
     * A verdict that measured NO silence must report nil, not 0 — 0 would claim a continuous
     * stream, which is precisely what `.unknown` cannot claim.
     */
    @Test
    fun gapSecondsIsNilRatherThanZeroWhenNothingWasMeasured() {
        assertNull(SleepConfidence.gapSeconds(BedtimeProvenance.Verdict.Witnessed))
        assertNull(SleepConfidence.gapSeconds(BedtimeProvenance.Verdict.Unknown))
        assertNull(SleepConfidence.gapSeconds(WakeProvenance.Verdict.Witnessed))
        assertNull(SleepConfidence.gapSeconds(WakeProvenance.Verdict.Unknown))
        assertEquals(42.0, SleepConfidence.gapSeconds(WakeProvenance.Verdict.StoppedThenResumed(42.0)))
        assertEquals(42.0, SleepConfidence.gapSeconds(BedtimeProvenance.Verdict.ResumedAfterGap(42.0)))
    }
}
