package io.github.opencircuit.app

import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.ringkit.Command
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A page that cannot be saved is never acknowledged: the ring keeps it, the failure is counted
 * and shown ("Couldn't save — will retry"), the drain ends, what was saved is committed, and the
 * next sync — offered the same page again by the ring — stores it.
 */
class AppendFailureNoAckTest {

    @Test
    fun aPageThatCannotBeSavedIsNotAcknowledgedAndTheNextSyncStoresIt() = runTest {
        val pages = HistoryTestPages.backlog(3)
        val w = syncWorld(pages)
        try {
            w.store.failAppendsOf = { it.contentEquals(pages[1]) }
            advanceTo(1_000)

            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(60_000)

            assertEquals(listOf(pages[0].toPlainHex()), w.ring.acknowledgedPages.map { it.toPlainHex() }, "only the saved page")
            assertEquals(pages.drop(1).map { it.toPlainHex() }, w.ring.stillHeld(Command.SYNC_CHANNEL_SLEEP).map { it.toPlainHex() })
            assertEquals(1, w.session.historyPages.counts.value.saveFailures)
            assertEquals(HistoryTestPages.counters(0), w.blobs.loadEpochArchive(TEST_RING_ID).records.map { it.counter })
            assertEquals("Couldn't save — will retry", w.viewModel.uiState.value.ringData.problem)

            // The disk has room again; the ring offers the page it kept.
            w.store.failAppendsOf = { false }
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(120_000)

            assertEquals(pages.map { it.toPlainHex() }, w.ring.acknowledgedPages.map { it.toPlainHex() })
            assertEquals((0 until 3).flatMap { HistoryTestPages.counters(it) }, w.blobs.loadEpochArchive(TEST_RING_ID).records.map { it.counter })
            assertEquals(null, w.viewModel.uiState.value.ringData.problem)
        } finally {
            w.db.close()
        }
    }
}
