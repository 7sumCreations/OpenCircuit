package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The wire names `sleepSessions[].edgeProvenance` and the diagnostics bundle emit.
 *
 * These are the only part of the parked coverage-card work that ships now, and they are the part a
 * future analysis keys on, so they are pinned by value rather than round-tripped: a round-trip test
 * stays green through a rename and every bundle already collected does not.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepConfidenceExportNamesTests.swift
 * (@ b1c2fdd) — both tests.
 */
class SleepConfidenceExportNamesTest {

    private val t0: Instant = Instant.ofEpochSecond(1_787_013_422) // 2026-08-18 02:37:02 +02:00

    /**
     * A rename here silently invalidates every tester bundle already collected — the reader is a
     * script, so nothing fails to compile on the other side.
     */
    @Test
    fun exportNamesArePinned() {
        assertEquals("durationLikelyHigh", SleepConfidence.exportName(SleepConfidence.Reason.DurationLikelyHigh))
        assertEquals("noRecordingAfterWake", SleepConfidence.exportName(SleepConfidence.Reason.NoRecordingAfterWake(from = t0, silentFor = 1.0)))
        assertEquals("noRecordingBeforeBedtime", SleepConfidence.exportName(SleepConfidence.Reason.NoRecordingBeforeBedtime(until = t0, silentFor = 1.0)))
        assertEquals("witnessed", SleepConfidence.exportName(BedtimeProvenance.Verdict.Witnessed))
        assertEquals("resumedAfterGap", SleepConfidence.exportName(BedtimeProvenance.Verdict.ResumedAfterGap(1.0)))
        assertEquals("noPriorMeasurement", SleepConfidence.exportName(BedtimeProvenance.Verdict.NoPriorMeasurement))
        assertEquals("unknown", SleepConfidence.exportName(BedtimeProvenance.Verdict.Unknown))
        assertEquals("witnessed", SleepConfidence.exportName(WakeProvenance.Verdict.Witnessed))
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
        assertNull(SleepConfidence.gapSeconds(BedtimeProvenance.Verdict.NoPriorMeasurement))
        assertNull(SleepConfidence.gapSeconds(WakeProvenance.Verdict.Witnessed))
        assertNull(SleepConfidence.gapSeconds(WakeProvenance.Verdict.Unknown))
        assertEquals(42.0, SleepConfidence.gapSeconds(WakeProvenance.Verdict.StoppedThenResumed(42.0)))
        assertEquals(42.0, SleepConfidence.gapSeconds(BedtimeProvenance.Verdict.ResumedAfterGap(42.0)))
    }
}
