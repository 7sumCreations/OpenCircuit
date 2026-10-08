package io.github.opencircuit.app

import io.github.opencircuit.app.ring.RingAction
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A page that arrives with no sync running (the ring can stream after a live measure's
 * `07 00 00`) is stored, then acknowledged, with no drain id — and the next sync's commit puts
 * its records in the store. Version 0.1.1 acknowledged such a page and dropped it.
 */
class OutOfDrainPageJournaledTest {

    @Test
    fun aPageOutsideASyncIsStoredBeforeItsAckAndCommittedByTheNextSync() = runTest {
        val w = syncWorld(sleepPages = emptyList())
        try {
            advanceTo(1_000)
            var storedWhenAcked = false
            w.ring.beforeAck = { page -> storedWhenAcked = w.journal.read(TEST_RING_ID).entries.any { it.page.contentEquals(page) } }

            w.ring.sendStray(HistoryTestPages.REAL_PAGE)
            advanceTo(2_000)

            val journaled = w.journal.read(TEST_RING_ID).entries.single()
            assertNull(journaled.drainId, "stored with no drain id")
            assertEquals(true, storedWhenAcked)
            assertEquals(listOf(HistoryTestPages.REAL_PAGE.toPlainHex()), w.ring.acknowledgedPages.map { it.toPlainHex() })
            assertEquals(1, w.session.historyPages.counts.value.outsideDrain)

            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(60_000)

            assertEquals(HistoryTestPages.REAL_COUNTERS, w.blobs.loadEpochArchive(TEST_RING_ID).records.map { it.counter })
            assertEquals(emptyList(), w.journal.read(TEST_RING_ID).entries)
        } finally {
            w.db.close()
        }
    }
}
