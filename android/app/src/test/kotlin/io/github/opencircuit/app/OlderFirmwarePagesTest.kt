package io.github.opencircuit.app

import io.github.opencircuit.app.details.ConnectionDetailsPresenter
import io.github.opencircuit.app.details.DetailsInput
import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.sync.HistoryDrainController
import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.ringkit.CommitPlanner
import io.github.opencircuit.ringkit.HistoryChannelOutcome
import io.github.opencircuit.store.RawHistoryPages
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Older Gen 2 firmware (FR02.013) sends the night as `0x40` pages, each released only by its `c0`
 * acknowledgement (../docs/PROTOCOL.md §5.3a). A real ring on that firmware gave 0 records: the
 * app had no route for `0x40`, never acknowledged one, and the drain stalled after the first page.
 *
 * The `0x40` layout was never captured, so nothing is decoded from these pages: each is stored in
 * the journal, only then acknowledged, and the commit moves it into the permanent raw-page store
 * so a later version can decode it. The channel is never judged complete from them, the card says
 * what happened in plain words, and the sync log counts them. The page bodies here are arbitrary.
 */
class OlderFirmwarePagesTest {

    private fun page40(n: Int): ByteArray = byteArrayOf(0x40, 0x00, n.toByte(), 0x5A, (0x10 + n).toByte(), 0x33, 0x44)

    @Test
    fun aNightOf40PagesIsAcknowledgedPageByPageKeptRawAndTheCardSaysSo() = runTest {
        val pages = (1..5).map(::page40)
        val w = syncWorld(makeRing = ringWith(pages))
        try {
            advanceTo(1_000)

            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(120_000)

            // Every page acknowledged in turn, so the ring sent the next one and ended with its 0x50.
            assertEquals(pages.map { it.toPlainHex() }, w.ring.acknowledgedPages.map { it.toPlainHex() })
            assertEquals(emptyList(), w.ring.stillHeld(Command.SYNC_CHANNEL_SLEEP))
            assertNull(w.session.dispatcher.counts.value.unhandled[0x40], "0x40 has a route")
            assertEquals(false, w.viewModel.uiState.value.ringData.syncing, "the drain ended")

            // Kept raw, byte for byte, and gone from the journal; nothing decoded or ingested.
            assertEquals(pages.map { it.toPlainHex() }, RawHistoryPages(w.db).read(TEST_RING_ID).map { it.page.toPlainHex() })
            assertEquals(emptyList(), w.journal.read(TEST_RING_ID).entries)
            assertEquals(emptyList(), w.blobs.loadEpochArchive(TEST_RING_ID).records)

            // Never judged complete from pages it could not read: nothing is released past them.
            val entry = w.store.syncLog().last()
            val sleep = entry.channels.first { it.label == "sleep" }
            assertEquals(5, sleep.pages40)
            assertEquals(HistoryChannelOutcome.PARTIAL, sleep.verdict)
            assertEquals("PARTIAL", entry.outcome)
            assertEquals(CommitPlanner.Drained.Nothing, w.store.drained.last())
            assertEquals(5, entry.rawPagesKept)
            assertEquals(0, entry.recordsStored)

            val ui = w.viewModel.uiState.value.ringData
            assertEquals(
                "This ring's firmware sends history this version can't read yet. It's kept on your phone — " +
                    "update the ring's firmware in the RingConn app.",
                ui.firmwareNotice,
            )
            assertEquals("5 pages kept, not readable yet", ui.lastSync)

            val log = ConnectionDetailsPresenter.present(DetailsInput(syncLog = w.store.syncLog())).syncLog
            assertTrue(log.any { "40×5" in it }, "the sync log counts the 0x40 pages: $log")
            assertTrue(log.any { "5 raw pages kept, 0 dropped" in it }, "the sync log counts the raw pages kept: $log")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aChannelThatAlsoSent4cPagesReleasesItsRecordsAndKeepsThe40PagesRaw() = runTest {
        val readable = HistoryTestPages.backlog(2)
        val pages = readable + page40(1)
        val w = syncWorld(makeRing = ringWith(pages))
        try {
            advanceTo(1_000)

            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(120_000)

            assertEquals(pages.map { it.toPlainHex() }, w.ring.acknowledgedPages.map { it.toPlainHex() })
            // Partial, so released through its last decoded record: every 0x4c record, nothing past it.
            val last = (0 until 2).flatMap { HistoryTestPages.counters(it) }
            assertEquals(CommitPlanner.Drained.Through(last.max()), w.store.drained.last())
            assertEquals(last, w.blobs.loadEpochArchive(TEST_RING_ID).records.map { it.counter })
            assertEquals(listOf(page40(1).toPlainHex()), RawHistoryPages(w.db).read(TEST_RING_ID).map { it.page.toPlainHex() })
            assertEquals(HistoryChannelOutcome.PARTIAL, w.store.syncLog().last().channels.first { it.label == "sleep" }.verdict)
        } finally {
            w.db.close()
        }
    }

    @Test
    fun anyVerdictBecomesPartialOnceA40PageCameAndNoneChangesWithout() {
        for (verdict in HistoryChannelOutcome.entries + listOf(null)) {
            assertEquals(HistoryChannelOutcome.PARTIAL, HistoryDrainController.verdictWith40(verdict, pages40 = 1), "$verdict with a 0x40 page")
            assertEquals(verdict, HistoryDrainController.verdictWith40(verdict, pages40 = 0), "$verdict with none")
        }
    }

    @Test
    fun a40PageThatCannotBeStoredIsNeverAcknowledged() = runTest {
        val pages = (1..3).map(::page40)
        val w = syncWorld(makeRing = ringWith(pages))
        try {
            w.store.failAppendsOf = { it.contentEquals(pages[1]) }
            advanceTo(1_000)

            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(120_000)

            assertEquals(listOf(pages[0].toPlainHex()), w.ring.acknowledgedPages.map { it.toPlainHex() }, "only the stored page")
            assertEquals(pages.drop(1).map { it.toPlainHex() }, w.ring.stillHeld(Command.SYNC_CHANNEL_SLEEP).map { it.toPlainHex() })
            assertEquals(1, w.session.historyPages.counts.value.saveFailures)
            assertEquals(listOf(pages[0].toPlainHex()), RawHistoryPages(w.db).read(TEST_RING_ID).map { it.page.toPlainHex() })
        } finally {
            w.db.close()
        }
    }

    @Test
    fun overTheCapTheCommitDropsTheOldestRawPagesAndTheLogSaysHowMany() = runTest {
        val pages = (1..4).map(::page40)
        val w = syncWorld(rawPagesKept = 3, makeRing = ringWith(pages))
        try {
            advanceTo(1_000)

            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(120_000)

            assertEquals(pages.drop(1).map { it.toPlainHex() }, RawHistoryPages(w.db).read(TEST_RING_ID).map { it.page.toPlainHex() })
            val entry = w.store.syncLog().last()
            assertEquals(4, entry.rawPagesKept)
            assertEquals(1, entry.rawPagesDropped)
            val log = ConnectionDetailsPresenter.present(DetailsInput(syncLog = w.store.syncLog())).syncLog
            assertTrue(log.any { "4 raw pages kept, 1 dropped" in it }, "the drop is shown: $log")
        } finally {
            w.db.close()
        }
    }
}
