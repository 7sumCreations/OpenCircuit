package io.github.opencircuit.app

import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.sync.SyncOutcome
import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.ringkit.HistoryChannelExitReason
import io.github.opencircuit.ringkit.HistoryChannelOutcome
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Android-tuned drain budget on virtual time (PORTING.md D-265), each edge at t − 1 ms and t:
 * quiet 6 s after the last page → one `07 00 00` nudge, quiet 6 s more → the round ends and the
 * channel is reopened; an answered channel with no page and no end report is cut 20 s after its
 * open; an `82 ff 00 7d` channel ends 6 s after that answer; a round that keeps receiving pages
 * extends 45 s at a time up to 3,600 s; the whole sync stops at 90 min. Every number is a literal
 * typed from the approved values, never read from `AndroidDrainTiming`.
 */
class DrainBudgetAndroidConstantsTest {

    private fun opens(w: SyncWorld) = w.ring.writes.filter { it.hex.startsWith("02") }.map { it.atMillis }

    private fun fetches(w: SyncWorld) = w.ring.writes.filter { it.hex == "070000" }.map { it.atMillis }

    @Test
    fun quietSixSecondsNudgesThenEndsTheRoundAndAnAnsweredSilentReopenIsCutAtTwentySeconds() = runTest {
        // A ring that never sends 0x50 on the sleep channel: 2 pages (2 700, 5 300), then quiet.
        val w = syncWorld { scope, now ->
            RingFake(scope, now, mapOf(Command.SYNC_CHANNEL_SLEEP to HistoryTestPages.backlog(2)), endOfHistoryBy = mapOf(0x00 to null))
        }
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)

            advanceTo(11_299)
            assertEquals(listOf(1_300L), fetches(w), "no nudge 5 999 ms after the last page")
            advanceTo(11_300)
            assertEquals(listOf(1_300L, 11_300L), fetches(w), "the nudge at 6 000 ms")

            advanceTo(17_299)
            assertEquals(listOf(1_000L), opens(w), "the round is still open 5 999 ms after the nudge")
            advanceTo(17_300)
            assertEquals(listOf(1_000L, 17_300L), opens(w), "reopened at 6 000 ms after the nudge")
            assertEquals("02000cbc2361000100", w.ring.writes.last { it.hex.startsWith("02") }.hex)

            // The reopen is answered 82 00 00 82 and then nothing: cut 20 s after its open.
            advanceTo(37_299)
            assertEquals(listOf(1_000L, 17_300L), opens(w))
            advanceTo(37_300)
            assertEquals(listOf(1_000L, 17_300L, 37_300L), opens(w), "the all-day channel opens at the cut")
            advanceTo(60_000)

            val sleep = w.session.sync.state.value.last!!.channels.first()
            assertEquals(listOf(HistoryChannelExitReason.QUIET_AFTER_PAGES, HistoryChannelExitReason.QUIET_NO_PAGES), sleep.rounds.map { it.exitReason })
            assertEquals(1, sleep.rounds.first().fetchNudges)
            assertEquals(HistoryChannelOutcome.PARTIAL, sleep.verdict)
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aChannelAnsweredEmptyEndsSixSecondsAfterThatAnswer() = runTest {
        val w = syncWorld { scope, now ->
            RingFake(
                scope, now, mapOf(Command.SYNC_CHANNEL_SLEEP to HistoryTestPages.backlog(1)),
                emptyAck = RingFake.SYNC_ACK_EMPTY, endOfHistoryBy = mapOf(0x03 to null),
            )
        }
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            // Sleep: 0x82 1 500, page 2 700, 0x50 5 300. All-day: open 5 300, fetch 5 600, 82 ff 00 7d at 5 800.
            advanceTo(11_799)
            assertEquals(emptyList(), w.ring.events.filter { it.what == "commit returned" })
            advanceTo(11_800)
            assertEquals(listOf(11_800L), w.ring.events.filter { it.what == "commit returned" }.map { it.atMillis })

            val allDay = w.session.sync.state.value.last!!.channels[1]
            assertEquals(HistoryChannelOutcome.EMPTY, allDay.verdict)
            assertEquals(true, allDay.rounds.single().sawEmptyHistorySignal)
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aRoundThatKeepsStreamingRunsToTheHourCeilingAndTheWholeSyncStopsAtNinetyMinutes() = runTest {
        // Pages every 5 s — never 6 s of quiet — on both channels, far more than either can drain.
        val w = syncWorld { scope, now ->
            RingFake(
                scope, now,
                mapOf(
                    Command.SYNC_CHANNEL_SLEEP to (0 until 800).map { HistoryTestPages.page(it, (799 - it) * 6, firstEpoch = 0) },
                    Command.SYNC_CHANNEL_ALL_DAY to (0 until 800).map { HistoryTestPages.page(it, (799 - it) * 6, firstEpoch = 10_000) },
                ),
                pageGapsMillis = longArrayOf(5_000),
            )
        }
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()

            // The sleep round opened at 1 000 extends 45 s at a time while pages flow, to 3 600 s.
            advanceTo(3_600_999)
            assertEquals(listOf(1_000L), opens(w), "still the sleep round 1 ms before its ceiling")
            advanceTo(3_601_000)
            assertEquals(listOf(1_000L, 3_601_000L), opens(w), "the all-day channel opens at the ceiling")

            // The whole sync began at 1 000: it stops at 1 000 + 90 min = 5 401 000.
            advanceTo(5_400_999)
            assertEquals(emptyList(), w.ring.events.filter { it.what == "commit returned" })
            advanceTo(5_401_000)
            assertEquals(listOf(5_401_000L), w.ring.events.filter { it.what == "commit returned" }.map { it.atMillis })

            val report = w.session.sync.state.value.last!!
            assertEquals(listOf(HistoryChannelExitReason.HARD_TIMEOUT), report.channels[0].rounds.map { it.exitReason }, "no reopen after the ceiling")
            assertEquals(listOf(HistoryChannelExitReason.HARD_TIMEOUT), report.channels[1].rounds.map { it.exitReason })
            assertEquals(SyncOutcome.PARTIAL, report.outcome)
            // Sleep pages 6 500 … 3 596 500 (719), all-day 3 606 500 … 5 396 500 (359): every one stored and committed.
            assertEquals(1_078, w.ring.acknowledgedPages.size)
            assertEquals(listOf(719 * 6, 359 * 6), report.channels.map { it.records })
            assertEquals(emptyList(), w.journal.read(TEST_RING_ID).entries)
        } finally {
            w.db.close()
        }
    }
}
