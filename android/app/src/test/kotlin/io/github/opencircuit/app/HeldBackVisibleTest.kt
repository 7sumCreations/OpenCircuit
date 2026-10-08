package io.github.opencircuit.app

import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.store.SyncLog
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A sync whose commit holds records back says so, and names the channel that held them (PORTING.md
 * D-267, D-273): the store moves its cursor only past what every channel is drained through, so a
 * channel that never finishes would keep everything newer on the phone, uncommitted, sync after
 * sync. The card shows "N records waiting — the all-day channel …" and the sync log keeps the
 * count and the channel, so a stalled ring is diagnosed from the screen.
 */
class HeldBackVisibleTest {

    @Test
    fun anAllDayChannelCutMidwayHoldsTheSleepRecordsBackAndTheCardSaysWhichChannel() = runTest {
        // The all-day channel holds OLDER records than the sleep channel; the link drops after two
        // of its four pages (pages at 8 800 and 11 400; the third is due at 13 200).
        val sleep = (0 until 2).map { HistoryTestPages.page(it, queuedAfter = (1 - it) * 6, firstEpoch = 600) }
        val allDay = (0 until 4).map { HistoryTestPages.page(it, queuedAfter = (3 - it) * 6, firstEpoch = 0) }
        val w = syncWorld { scope, now -> RingFake(scope, now, mapOf(Command.SYNC_CHANNEL_SLEEP to sleep, Command.SYNC_CHANNEL_ALL_DAY to allDay)) }
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(12_000)
            w.ring.dropLink()
            advanceTo(20_000)

            val held = w.session.sync.state.value.last!!.commit!!.heldBack
            assertEquals(12, held, "the sleep channel's 12 records are newer than the all-day channel's bound")
            assertEquals(listOf("12 records waiting — the all-day channel didn't finish"), w.viewModel.uiState.value.ringData.notices)

            val entry = SyncLog(w.db).read(TEST_RING_ID).entries.single()
            assertEquals(12, entry.heldBack)
            assertEquals(listOf("all-day"), entry.heldBackBy)
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aSyncCutOnTheSleepChannelHoldsItsRecordsForTheChannelItNeverReached() = runTest {
        val w = syncWorld { scope, now ->
            RingFake(scope, now, mapOf(Command.SYNC_CHANNEL_SLEEP to HistoryTestPages.backlog(3), Command.SYNC_CHANNEL_ALL_DAY to HistoryTestPages.allDayBacklog(1)))
        }
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(4_000) // the first sleep page is in; the second is due at 5 300
            w.ring.dropLink()
            runCurrent()
            advanceTo(20_000)

            assertEquals(listOf("6 records waiting — the all-day channel wasn't reached"), w.viewModel.uiState.value.ringData.notices)
            val entry = SyncLog(w.db).read(TEST_RING_ID).entries.single()
            assertEquals(6, entry.heldBack)
            assertEquals(listOf("all-day"), entry.heldBackBy)
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aSyncThatHoldsNothingBackSaysNothing() = runTest {
        val w = syncWorld { scope, now ->
            RingFake(scope, now, mapOf(Command.SYNC_CHANNEL_SLEEP to HistoryTestPages.backlog(2), Command.SYNC_CHANNEL_ALL_DAY to HistoryTestPages.allDayBacklog(1)))
        }
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            advanceTo(60_000)

            assertEquals(emptyList(), w.viewModel.uiState.value.ringData.notices)
            assertEquals(emptyList(), SyncLog(w.db).read(TEST_RING_ID).entries.single().heldBackBy)
        } finally {
            w.db.close()
        }
    }
}
