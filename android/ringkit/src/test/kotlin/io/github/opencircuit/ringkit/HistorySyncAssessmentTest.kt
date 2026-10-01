package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The per-channel drain verdict: which traces may commit sleep, and which "nothing arrived" shapes
 * stay distinguishable (never asked / asked and silent / our own teardown / sport only).
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/HistorySyncAssessmentTests.swift
 * (@ b1c2fdd): 22 of 24. `testLegacyTraceJSONWithoutTheNewFieldStillDecodes` (`:137`) and
 * `testLegacyTraceJSONWithoutTheSportCountersStillDecodes` (`:152`) decode stored JSON; the stored
 * form of a trace is decided with the storage epic (E6).
 *
 * Upstream's trace defaults `startedAt` to the wall clock; here every trace is started at a fixed
 * instant, because the Kotlin constructor takes no hidden clock read.
 */
class HistorySyncAssessmentTest {

    private val t0 = Instant.parse("2026-08-04T04:15:00Z")

    private fun trace(label: String, channel: Int) = HistoryChannelTrace(label = label, channel = channel, startedAt = t0)

    // :6
    @Test
    fun sleepPagesAndEndMarkerAreComplete() {
        val trace = trace("sleep", 0x00)
        trace.sawSyncAck = true
        trace.page4CCount = 2
        trace.endMarkerCount = 1
        trace.exitReason = HistoryChannelExitReason.END_MARKER
        assertEquals(HistoryChannelOutcome.COMPLETE, trace.outcome)
        assertTrue(trace.outcome.allowsSleepCommit)
    }

    // :16 — REPLACED (D-43): upstream asserts COMPLETE here; the port requires the 0x50 end report.
    @Test
    fun quietAfterSleepPagesWithoutEndMarkerIsPartial() {
        val trace = trace("sleep", 0x00)
        trace.sawSyncAck = true
        trace.page4CCount = 1
        trace.exitReason = HistoryChannelExitReason.QUIET_AFTER_PAGES
        assertEquals(HistoryChannelOutcome.PARTIAL, trace.outcome)
        assertFalse(trace.outcome.allowsSleepCommit)
    }

    // :24
    @Test
    fun sleepPagesWithoutCleanExitArePartial() {
        val trace = trace("sleep", 0x00)
        trace.sawSyncAck = true
        trace.page4CCount = 1
        trace.exitReason = HistoryChannelExitReason.HARD_TIMEOUT
        assertEquals(HistoryChannelOutcome.PARTIAL, trace.outcome)
        assertFalse(trace.outcome.allowsSleepCommit)
    }

    // :33
    @Test
    fun ppgOnlyDrainIsNotSleepSuccess() {
        val trace = trace("sleep", 0x00)
        trace.sawSyncAck = true
        trace.page47Count = 3
        trace.exitReason = HistoryChannelExitReason.QUIET_AFTER_PAGES
        assertEquals(HistoryChannelOutcome.PPG_ONLY, trace.outcome)
    }

    // :41
    @Test
    fun ackPlusEndMarkerWithoutPagesIsEmpty() {
        val trace = trace("sleep", 0x00)
        trace.sawSyncAck = true
        trace.endMarkerCount = 1
        trace.exitReason = HistoryChannelExitReason.END_MARKER
        assertEquals(HistoryChannelOutcome.EMPTY, trace.outcome)
    }

    // :49
    @Test
    fun noAckIsNoAck() {
        val trace = trace("sleep", 0x00)
        trace.exitReason = HistoryChannelExitReason.HARD_TIMEOUT
        assertEquals(HistoryChannelOutcome.NO_ACK, trace.outcome)
    }

    // :57 — sawEmptyHistorySignal (0x82 byte[1]=0xff)
    @Test
    fun sawEmptyHistorySignalDefaultsFalse() {
        assertFalse(trace("all-day", 0x03).sawEmptyHistorySignal)
    }

    // :62 — the observed `82 ff 00 7d` ACK: ACK, no pages, signal set → still .empty.
    @Test
    fun sawEmptyHistorySignalOutcomeIsEmptyWhenAckAndNoPages() {
        val trace = trace("all-day", 0x03)
        trace.sawSyncAck = true
        trace.sawEmptyHistorySignal = true
        trace.exitReason = HistoryChannelExitReason.QUIET_NO_PAGES
        assertEquals(HistoryChannelOutcome.EMPTY, trace.outcome)
        assertFalse(trace.outcome.allowsSleepCommit)
    }

    // :74 — the signal is a hint to exit early; it must not poison a real drain.
    @Test
    fun sawEmptyHistorySignalDoesNotDegradeCompleteOutcome() {
        val trace = trace("sleep", 0x00)
        trace.sawSyncAck = true
        trace.sawEmptyHistorySignal = true
        trace.page4CCount = 3
        trace.endMarkerCount = 1
        trace.exitReason = HistoryChannelExitReason.END_MARKER
        assertEquals(HistoryChannelOutcome.COMPLETE, trace.outcome)
        assertTrue(trace.outcome.allowsSleepCommit)
    }

    // :87
    @Test
    fun sawEmptyHistorySignalNoAckWithSignalStaysNoAck() {
        val trace = trace("sleep", 0x00)
        trace.sawEmptyHistorySignal = true
        trace.exitReason = HistoryChannelExitReason.QUIET_NO_PAGES
        assertEquals(HistoryChannelOutcome.NO_ACK, trace.outcome)
    }

    // :97 — .linkDown: "we never asked" vs .noAck "the ring stayed silent".
    @Test
    fun openWriteFailureIsLinkDownNotNoAck() {
        val trace = trace("all-day", 0x03)
        trace.openWriteFailed = true
        trace.exitReason = HistoryChannelExitReason.LINK_UNUSABLE
        assertEquals(HistoryChannelOutcome.LINK_DOWN, trace.outcome)
        assertFalse(trace.outcome.allowsSleepCommit)
    }

    // :107 — if the ring answered, the link plainly worked; the flag must not override that.
    @Test
    fun realEvidenceOutranksAStaleWriteFailureFlag() {
        val acked = trace("all-day", 0x03)
        acked.openWriteFailed = true
        acked.sawSyncAck = true
        assertEquals(HistoryChannelOutcome.EMPTY, acked.outcome)

        val paged = trace("sleep", 0x00)
        paged.openWriteFailed = true
        paged.page4CCount = 4
        paged.endMarkerCount = 1
        paged.exitReason = HistoryChannelExitReason.END_MARKER
        assertEquals(HistoryChannelOutcome.COMPLETE, paged.outcome)
        assertTrue(paged.outcome.allowsSleepCommit)

        val ppg = trace("sleep", 0x00)
        ppg.openWriteFailed = true
        ppg.page47Count = 1
        assertEquals(HistoryChannelOutcome.PPG_ONLY, ppg.outcome)
    }

    // :128 — an unset flag must behave exactly as the pre-flag code did.
    @Test
    fun tracesWrittenBeforeTheFlagExistedStillClassifyAsNoAck() {
        val trace = trace("all-day", 0x03)
        trace.exitReason = HistoryChannelExitReason.QUIET_NO_PAGES
        assertNull(trace.openWriteFailed)
        assertEquals(HistoryChannelOutcome.NO_ACK, trace.outcome)
    }

    // :171 — "we counted and it was zero" is a fresh trace's measured 0, never null.
    @Test
    fun nilCountersMeanPreUpgradeWhileAFreshTraceMeansMeasuredZero() {
        val fresh = trace("sport", 0x02)
        assertEquals(0, fresh.page4DCount)
        assertEquals(0, fresh.sportSampleCount)
    }

    // :180 — a sport drain full of workout history is not "empty".
    @Test
    fun sportPagesClassifyAsSportOnlyNotEmpty() {
        val trace = trace("sport", 0x02)
        trace.sawSyncAck = true
        trace.page4DCount = 7
        trace.sportSampleCount = 210
        trace.exitReason = HistoryChannelExitReason.END_MARKER
        assertEquals(HistoryChannelOutcome.SPORT_ONLY, trace.outcome)
        assertTrue(trace.sawAnyPage)
    }

    // :193 — the mirror case must stay reachable.
    @Test
    fun sportChannelThatTrulyReturnedNothingIsStillEmpty() {
        val trace = trace("sport", 0x02)
        trace.sawSyncAck = true
        trace.exitReason = HistoryChannelExitReason.END_MARKER
        assertEquals(HistoryChannelOutcome.EMPTY, trace.outcome)
        assertFalse(trace.sawAnyPage)
    }

    // :203 — pages with no samples (our decode dropped them) vs a channel that returned nothing.
    @Test
    fun pagesWithoutSamplesIsDistinguishableFromAnEmptyChannel() {
        val decodeBroken = trace("sport", 0x02)
        decodeBroken.sawSyncAck = true
        decodeBroken.page4DCount = 5
        decodeBroken.sportSampleCount = 0
        assertEquals(HistoryChannelOutcome.SPORT_ONLY, decodeBroken.outcome)

        val ringEmpty = trace("sport", 0x02)
        ringEmpty.sawSyncAck = true
        assertEquals(HistoryChannelOutcome.EMPTY, ringEmpty.outcome)
        assertNotEquals(decodeBroken.outcome, ringEmpty.outcome)
    }

    // :219 — sport records carry no sleep epochs.
    @Test
    fun sportOnlyNeverCommitsSleep() {
        assertFalse(HistoryChannelOutcome.SPORT_ONLY.allowsSleepCommit)
        assertEquals(
            HistoryCommitGate.Decision.SKIP,
            HistoryCommitGate.decide(outcome = HistoryChannelOutcome.SPORT_ONLY, recordsAdded = 5, adoptedRecordCount = 0),
        )
    }

    // :228 — ordering lock: a stray 0x4d never degrades an epoch or PPG channel.
    @Test
    fun sportCountersNeverDegradeAnEpochOrPPGChannel() {
        val sleep = trace("sleep", 0x00)
        sleep.sawSyncAck = true
        sleep.page4CCount = 3
        sleep.page4DCount = 2
        sleep.endMarkerCount = 1
        sleep.exitReason = HistoryChannelExitReason.END_MARKER
        assertEquals(HistoryChannelOutcome.COMPLETE, sleep.outcome)
        assertTrue(sleep.outcome.allowsSleepCommit)

        val ppg = trace("sleep", 0x00)
        ppg.sawSyncAck = true
        ppg.page47Count = 1
        ppg.page4DCount = 2
        assertEquals(HistoryChannelOutcome.PPG_ONLY, ppg.outcome)
    }

    // :249 — both are persisted as strings; the raw values are typed from upstream.
    @Test
    fun outcomeAndExitReasonRawValuesAreStable() {
        assertEquals("linkDown", HistoryChannelOutcome.LINK_DOWN.rawValue)
        assertEquals("sportOnly", HistoryChannelOutcome.SPORT_ONLY.rawValue)
        assertEquals("linkUnusable", HistoryChannelExitReason.LINK_UNUSABLE.rawValue)
    }

    // :258 — cut off by OUR OWN session teardown is not "the ring stayed silent".
    @Test
    fun cancelledMidWaitIsNotMisreportedAsNoAck() {
        val trace = trace("all-day", 0x03)
        trace.exitReason = HistoryChannelExitReason.CANCELLED
        assertEquals(HistoryChannelOutcome.CANCELLED, trace.outcome)
        assertFalse(trace.outcome.allowsSleepCommit)
    }

    // :271 — pages or an ACK before the cancel are real evidence the cancel must not erase.
    @Test
    fun realEvidenceOutranksACancelledExitReason() {
        val acked = trace("all-day", 0x03)
        acked.sawSyncAck = true
        acked.exitReason = HistoryChannelExitReason.CANCELLED
        assertEquals(HistoryChannelOutcome.EMPTY, acked.outcome)

        val paged = trace("sleep", 0x00)
        paged.page4CCount = 2
        paged.endMarkerCount = 1
        paged.exitReason = HistoryChannelExitReason.CANCELLED
        assertEquals(HistoryChannelOutcome.COMPLETE, paged.outcome)
        assertTrue(paged.outcome.allowsSleepCommit)
    }
}
