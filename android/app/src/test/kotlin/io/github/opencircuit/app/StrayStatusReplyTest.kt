package io.github.opencircuit.app

import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.sync.SyncOutcome
import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.ringkit.HistoryChannelOutcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The ring answers `d0 00 00` with a `0x10` descriptor or with a `0x50` frame (PROTOCOL.md §4),
 * and a `0x50` carries nothing that tells "status reply" from "end of history" — only order does.
 * A `0x50` that arrives after a channel's open but before that channel's `0x82` is a status reply:
 * the drain goes on. Frame: the real `50 00 00 12 0c 22 aa e4 0c 22 ac b5`
 * (`EpochSyncTests.swift:58-60` @ b1c2fdd).
 */
class StrayStatusReplyTest {

    @Test
    fun aStatusReplyBetweenTheOpenAndTheSyncAnswerIsNotTheEndOfHistory() = runTest {
        // The keepalive's d0 at 500 is answered with 0x50 only 2 100 ms later, at 2 600: after the
        // open (2 500, the 2 s limit) and before the 0x82 (fetch 2 800 + 200 = 3 000).
        val pages = HistoryTestPages.backlog(2)
        val w = syncWorld { scope, now ->
            RingFake(
                scope, now, mapOf(Command.SYNC_CHANNEL_SLEEP to pages),
                postAuthReply = null, statusReply = RingFake.END_OF_HISTORY, statusReplyMillis = 2_100,
            )
        }
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(60_000)

            val events = w.ring.events.map { it.atMillis to it.what }
            assertEquals(2_500L, w.ring.writes.first { it.hex.startsWith("02") }.atMillis)
            assertEquals(true, events.any { it.first == 2_600L && it.second.startsWith("frame 500000120c22aae40c22acb5") })
            // Both pages were still taken, stored and committed: the drain did not stop at 2 600.
            assertEquals(pages.map { it.toPlainHex() }, w.ring.acknowledgedPages.map { it.toPlainHex() })
            assertEquals(12, w.blobs.loadEpochArchive(TEST_RING_ID).records.size)
            val sleep = w.session.sync.state.value.last!!.channels.first()
            assertEquals(1, sleep.statusReplies)
            assertEquals(1, sleep.rounds.single().endMarkerCount, "only the real end report counts")
            assertEquals(HistoryChannelOutcome.COMPLETE, sleep.verdict)
            assertEquals(SyncOutcome.COMPLETE, w.session.sync.state.value.last!!.outcome)
        } finally {
            w.db.close()
        }
    }
}
