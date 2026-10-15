package io.github.opencircuit.app

import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.sync.ChannelProgress
import io.github.opencircuit.ringkit.Command
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Progress while a sync runs, per channel, from the 16-bit countdown each `0x4c` page carries
 * (bytes 1–2, the records still queued after the page plus one): "N of M records", M = the
 * records so far plus the latest countdown less one, and an ETA from the record rate since the
 * first page.
 * Read off the session's state and the Ring data card while the drain runs, on virtual time.
 */
class CountdownProgressTest {

    @Test
    fun theFirstPageGivesTheTotalAndTheSecondTheTimeLeft() = runTest {
        // 717 still queued after the first page (countdown 718, 02 ce), 711 after the second (712).
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
            assertEquals(718, sleep.firstCountdown, "the raw countdown: 717 queued after + 1")
            assertEquals(712, sleep.lastCountdown)
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aRealRingShapedDrainOf652RecordsReadsOf652AllTheWayToItsLastPage() = runTest {
        // The real FR02.022 drain: 652 records, a 4-record first page (countdown 649), then 108
        // pages of six, the last one counting 1. The card once read "of 653" with 652 stored.
        val pages = HistoryTestPages.ringShapedBacklog(total = 652, firstPageRecords = 4)
        assertEquals(109, pages.size)
        assertEquals("4c0289", pages.first().toPlainHex().take(6), "first page countdown 649")
        assertEquals("4c0001", pages.last().toPlainHex().take(6), "last page countdown 1")
        val w = syncWorld(makeRing = ringWith(pages))
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            val sleepLines = mutableListOf<String>()
            var at = 1_000L
            while (at < 600_000L && w.session.sync.state.value.syncing) {
                at += 100
                advanceTo(at)
                w.viewModel.uiState.value.ringData.progress.firstOrNull()?.takeIf { it.startsWith("Sleep") }
                    ?.let { if (sleepLines.lastOrNull() != it) sleepLines += it }
            }
            val counted = sleepLines.filter { " of " in it }
            assertEquals("Sleep channel 4 of 652 records", counted.first())
            assertEquals("Sleep channel 652 of 652 records · less than a minute left", counted.last())
            assertTrue(counted.all { it.contains(" of 652 records") }, "every line says of 652: ${counted.filterNot { it.contains(" of 652 ") }}")
            assertEquals(652, w.session.sync.state.value.last!!.channels.first().records)
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
