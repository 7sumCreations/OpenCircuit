package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kotlin-only end-to-end check of the sync-policy path: the drain plan → a per-channel trace and
 * epoch session fed real frames → the channel outcome → the commit gate. Each stage has its own
 * ported tests; this one proves they agree at their seams.
 *
 * Frames are real: the `0x82` sync-open ACK (upstream RingKitVerify/main.swift:38), the 2026-06-13
 * overnight `0x4c` page (RingKitVerify/main.swift:307-310) and the no-XOR `0x50` cursor report
 * (RingKitVerify/main.swift:141), all @ b1c2fdd.
 *
 * The loop that routes frames here is test-side scaffolding in the shape upstream's BLE session
 * uses (count from the wire, stamp first/last opcode, apply `0x50`); the BLE layer's own router is
 * built and tested with the BLE module.
 *
 * ON THE MISSING `0x50`. Upstream deliberately classifies "pages, then a quiet exit" as COMPLETE
 * (a clean OPEN, never a claim that the ring is empty). So the invariant "no `0x50`, no finished
 * drain" is held in two other places, and both are asserted below: a drain cut off without the
 * report is PARTIAL and never stages; and a quiet exit without it leaves the epoch session
 * unfinished and the continuation policy asking again.
 */
class ScriptedDrainTest {

    private val t0 = Instant.parse("2026-06-13T07:00:00Z")

    private val syncAck = "82000082"
    private val realPage = "4c00260c22a16b55210a7d120a010101010100000402400400000c22a20155000300" +
        "120a010101010100003c00000d01200c22a297540001005f0a010101010100001101b00f" +
        "00440c22a32d6027077b120a010101010100402501c02235a00c22a3c351260577120b01" +
        "0101010108a01000000401300c22a459502d0378120a01010101010160200000040ff0cc"
    private val cursorReport = bytes(0x50, 0x00, 0x00, 0x12, 0x0c, 0x22, 0xaa, 0xe4, 0x0c, 0x22, 0xac, 0xb5)

    /** The wall-clock time of a record counter: the counter plus the 2019-12-31 12:00:00 UTC sync epoch. */
    private fun at(counter: Long) = Instant.ofEpochSecond(1_577_793_600L + counter)

    private class Drained(val trace: HistoryChannelTrace, val session: EpochSyncSession, val records: List<BulkRecord>)

    /** Route [frames] into a fresh trace + session for [step], the way a drain loop counts them. */
    private fun drain(step: HistoryDrainPlan.Step, frames: List<ByteArray>, exitWithoutEndMarker: HistoryChannelExitReason?): Drained {
        val trace = HistoryChannelTrace(label = step.label, channel = step.channel, startedAt = t0)
        val session = EpochSyncSession()
        val records = mutableListOf<BulkRecord>()
        trace.recordsAtStart = 0
        for (frame in frames) {
            val op = frame[0].toInt() and 0xFF
            if (trace.firstOpcode == null) trace.firstOpcode = op
            trace.lastOpcode = op
            when (op) {
                0x82 -> {
                    trace.sawSyncAck = true
                    trace.syncAckFlag = frame[1].toInt() and 0xFF
                }
                0x4C -> {
                    trace.page4CCount += 1
                    session.appendActivityPage(frame)
                    records += BulkSleep.recordsFromPage(frame)
                }
                0x50 -> if (session.complete(frame) != null) {
                    trace.endMarkerCount += 1
                    trace.exitReason = HistoryChannelExitReason.END_MARKER
                }
            }
        }
        if (trace.exitReason == null) trace.exitReason = exitWithoutEndMarker
        trace.recordsAtEnd = records.size
        trace.finishedAt = t0.plusSeconds(6)
        return Drained(trace, session, records)
    }

    @Test
    fun aPlannedSleepDrainOverARealPageEndsOnTheCursorReportAndCommits() {
        val plan = HistoryDrainPlan.steps(inBackground = false, allDayOnly = false, sportEnabled = false, now = t0, nightWindowEnd = null)
        assertEquals(listOf(HistoryDrainPlan.SLEEP_STEP, HistoryDrainPlan.ALL_DAY_STEP), plan)

        val sleep = drain(plan[0], listOf(hex(syncAck), hex(realPage), cursorReport), exitWithoutEndMarker = null)
        val allDay = drain(plan[1], listOf(hex(syncAck), cursorReport), exitWithoutEndMarker = null)

        // The sleep channel: its own handshake first, six records, finished on the 0x50.
        assertEquals(0x00, sleep.trace.channel)
        assertFalse(sleep.trace.openedOntoLiveStream, "the drain saw its own 0x82 first")
        assertEquals(0x50, sleep.trace.lastOpcode)
        assertTrue(sleep.session.isComplete)
        assertEquals(0x0c22acb5L, sleep.session.endOfHistory?.cursorTo)
        assertEquals(6, sleep.trace.recordsAdded)
        assertEquals(HistoryChannelOutcome.COMPLETE, sleep.trace.outcome)
        assertEquals(Duration.ofSeconds(6), sleep.trace.duration)

        // Every epoch record is dated by its own four-byte counter.
        assertEquals(6, sleep.session.activityRecords.size)
        assertEquals(at(0x0c22a16b), sleep.session.activityRecords.first().timestamp)
        assertEquals(at(0x0c22a459), sleep.session.activityRecords.last().timestamp)

        // The all-day channel ACKed and reported its cursor with no pages: empty, never complete.
        assertEquals(HistoryChannelOutcome.EMPTY, allDay.trace.outcome)

        // The commit gate stages this drain's own slice.
        assertEquals(
            HistoryCommitGate.Decision.STAGE,
            HistoryCommitGate.decide(sleep.trace.outcome, recordsAdded = sleep.trace.recordsAdded, adoptedRecordCount = 0),
        )
    }

    @Test
    fun aDrainCutOffBeforeTheCursorReportIsPartialAndNeverStages() {
        for (cutOff in listOf(
            HistoryChannelExitReason.HARD_TIMEOUT, HistoryChannelExitReason.LINK_UNUSABLE, HistoryChannelExitReason.CANCELLED,
            HistoryChannelExitReason.QUIET_AFTER_PAGES, // D-43: quiet after pages without a 0x50 is not a clean end
        )) {
            val sleep = drain(HistoryDrainPlan.SLEEP_STEP, listOf(hex(syncAck), hex(realPage)), exitWithoutEndMarker = cutOff)

            assertFalse(sleep.session.isComplete, "no 0x50 arrived: the session is not finished ($cutOff)")
            assertNull(sleep.session.endOfHistory)
            assertEquals(0, sleep.trace.endMarkerCount)
            assertEquals(HistoryChannelOutcome.PARTIAL, sleep.trace.outcome, "$cutOff")
            assertFalse(sleep.trace.outcome.allowsSleepCommit)
            assertNotEquals(
                HistoryCommitGate.Decision.STAGE,
                HistoryCommitGate.decide(sleep.trace.outcome, recordsAdded = sleep.trace.recordsAdded, adoptedRecordCount = 0),
                "a drain with no 0x50 must never stage its own slice ($cutOff)",
            )
            // Each record carries its own full counter, so its date is real without the report (D-260).
            assertEquals(at(0x0c22a16b), sleep.session.activityRecords.first().timestamp)
        }
    }

    @Test
    fun aQuietExitWithoutTheCursorReportIsNeverTreatedAsDone() {
        val sleep = drain(HistoryDrainPlan.SLEEP_STEP, listOf(hex(syncAck), hex(realPage)), exitWithoutEndMarker = HistoryChannelExitReason.QUIET_AFTER_PAGES)

        assertFalse(sleep.session.isComplete, "the ring never said it was done")
        assertEquals(0, sleep.trace.endMarkerCount)
        assertTrue(
            DrainContinuation.shouldNudge(
                sawPages = sleep.trace.sawAnyPage, sawEndMarker = sleep.trace.endMarkerCount > 0,
                nudgesWithoutProgress = 0, nudgesThisRound = 0, tick = 10, ceiling = 180,
                inBackground = false, backgroundTimeRemaining = Duration.ZERO,
            ),
            "quiet without 0x50 must ask the ring again",
        )
        assertTrue(
            DrainContinuation.shouldReopen(
                exitReason = sleep.trace.exitReason, recordsAdded = sleep.trace.recordsAdded, round = 0,
                inBackground = false, backgroundTimeRemaining = Duration.ZERO,
            ),
            "a quiet round that added records is reopened",
        )

        // The same drain with the report: nothing more to ask.
        val finished = drain(HistoryDrainPlan.SLEEP_STEP, listOf(hex(syncAck), hex(realPage), cursorReport), exitWithoutEndMarker = null)
        assertFalse(
            DrainContinuation.shouldNudge(
                sawPages = finished.trace.sawAnyPage, sawEndMarker = finished.trace.endMarkerCount > 0,
                nudgesWithoutProgress = 0, nudgesThisRound = 0, tick = 10, ceiling = 180,
                inBackground = false, backgroundTimeRemaining = Duration.ZERO,
            ),
        )
        assertFalse(
            DrainContinuation.shouldReopen(
                exitReason = finished.trace.exitReason, recordsAdded = finished.trace.recordsAdded, round = 0,
                inBackground = false, backgroundTimeRemaining = Duration.ZERO,
            ),
        )
    }

    @Test
    fun aDrainThatOpensOntoALiveStreamIsFlagged() {
        val sleep = drain(HistoryDrainPlan.SLEEP_STEP, listOf(hex(realPage), cursorReport), exitWithoutEndMarker = null)
        assertTrue(sleep.trace.openedOntoLiveStream, "the first frame this trace counted was a 0x4c page")
    }
}
