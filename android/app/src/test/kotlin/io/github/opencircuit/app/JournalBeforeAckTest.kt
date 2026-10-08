package io.github.opencircuit.app

import io.github.opencircuit.app.ring.RingAction
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The ring drops a page the moment it has the acknowledgement, so every page must already be in
 * the store's journal when the ring receives its acknowledgement. Read from the real store INSIDE
 * the ring fake's acknowledgement, at the instant the ring gets it — never "eventually".
 */
class JournalBeforeAckTest {

    @Test
    fun everyPageIsInTheJournalAtTheMomentTheRingReceivesItsAcknowledgement() = runTest {
        val pages = HistoryTestPages.backlog(4)
        val w = syncWorld(pages)
        try {
            val storedWhenAcked = mutableListOf<Boolean>()
            w.ring.beforeAck = { page -> storedWhenAcked += w.journal.read(TEST_RING_ID).entries.any { it.page.contentEquals(page) } }
            advanceTo(1_000)

            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(60_000)

            assertEquals(listOf(true, true, true, true), storedWhenAcked)
            assertEquals(4, w.ring.acknowledgedPages.size)
        } finally {
            w.db.close()
        }
    }
}
