package io.github.opencircuit.app

import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.sync.ChannelProgress
import io.github.opencircuit.ringkit.Command
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Progress while a sync runs, per channel, from the 16-bit countdown each `0x4c` page carries
 * (bytes 1–2): "N of M records", M = the first page's countdown plus that page's records (then the
 * records so far plus the latest countdown), and an ETA from the record rate since the first page.
 * Read off the session's state and the Ring data card while the drain runs, on virtual time.
 */
class CountdownProgressTest {

    @Test
    fun theFirstPageGivesTheTotalAndTheSecondTheTimeLeft() = runTest {
        // 717 still queued after the first page (02 cd), 711 after the second.
        val pages = listOf(HistoryTestPages.page(0, queuedAfter = 717, firstEpoch = 0), HistoryTestPages.page(1, queuedAfter = 711, firstEpoch = 0))
        val w = syncWorld { scope, now -> RingFake(scope, now, mapOf(Command.SYNC_CHANNEL_SLEEP to pages)) }
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            assertEquals(listOf(ChannelProgress("sleep", records = 0, expected = null, etaSeconds = null, done = false)), w.session.sync.state.value.channels)
            assertEquals(listOf("Sleep channel 0 records so far"), w.viewModel.uiState.value.ringData.progress)

            advanceTo(2_700) // the first page
            assertEquals(ChannelProgress("sleep", records = 6, expected = 723, etaSeconds = null, done = false), w.session.sync.state.value.channels.single())
            assertEquals(listOf("Sleep channel 6 of 723 records"), w.viewModel.uiState.value.ringData.progress)

            advanceTo(5_300) // the second, 2.6 s later: 6 records / 2.6 s → 711 left in 309 s (rounded up)
            assertEquals(ChannelProgress("sleep", records = 12, expected = 723, etaSeconds = 309, done = false), w.session.sync.state.value.channels.single())
            assertEquals(listOf("Sleep channel 12 of 723 records · about 6 min left"), w.viewModel.uiState.value.ringData.progress)

            advanceTo(7_100) // 0x50: the sleep channel is done; the all-day channel opens
            runCurrent()
            assertEquals(
                listOf("Sleep channel done · 12 records", "All-day channel 0 records so far"),
                w.viewModel.uiState.value.ringData.progress,
            )

            advanceTo(60_000)
            assertEquals(emptyList(), w.viewModel.uiState.value.ringData.progress, "no progress lines once the sync is over")
            val sleep = w.session.sync.state.value.last!!.channels.first()
            assertEquals(717, sleep.firstCountdown)
            assertEquals(711, sleep.lastCountdown)
        } finally {
            w.db.close()
        }
    }

    @Test
    fun lessThanAMinuteLeftIsSaidSo() = runTest {
        // 18 left after 2 pages that came 2.6 s apart: 18 / (6 / 2.6 s) → 8 s.
        val pages = (0 until 5).map { HistoryTestPages.page(it, queuedAfter = (4 - it) * 6, firstEpoch = 0) }
        val w = syncWorld { scope, now -> RingFake(scope, now, mapOf(Command.SYNC_CHANNEL_SLEEP to pages)) }
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(5_300)
            assertEquals(listOf("Sleep channel 12 of 30 records · less than a minute left"), w.viewModel.uiState.value.ringData.progress)
        } finally {
            w.db.close()
        }
    }
}
