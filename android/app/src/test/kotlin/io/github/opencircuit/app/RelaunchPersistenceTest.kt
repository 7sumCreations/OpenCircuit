package io.github.opencircuit.app

import io.github.opencircuit.app.ring.RingAction
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * What the Ring data card shows survives a relaunch: a second session and view model, built over
 * the same store and the same preferences file as the first (what a new process finds on disk),
 * show when the ring last synced, the last sync's result, what is stored and the switch — before
 * any new sync.
 */
class RelaunchPersistenceTest {

    @Test
    fun lastSyncedTheResultTheStoredRangeAndTheSwitchSurviveARelaunch() = runTest {
        val keyValues = InMemoryKeyValues()
        val first = syncWorld(keyValues = keyValues, makeRing = ringWith(HistoryTestPages.backlog(2)))
        try {
            first.viewModel.onAction(RingAction.SetDisconnectAfterSync(false))
            advanceTo(1_000)
            first.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            advanceTo(60_000)
            assertEquals("12 records · complete", first.viewModel.uiState.value.ringData.lastSync)

            // About an hour later, a new process: nothing carried over in memory.
            advanceTo(4_000_000)
            val second = syncWorld(keyValues = keyValues, reuseDb = first.db, makeRing = ringWith(emptyList()))
            runCurrent()

            val card = second.viewModel.uiState.value.ringData
            assertEquals("Last synced 1 h ago", card.headline)
            assertEquals("12 records · complete", card.lastSync)
            // The real page's twelve records: 2026-06-13 22:28 to 22:56 UTC.
            assertEquals("13 Jun", card.storedRange)
            assertEquals("0", card.nights)
            assertFalse(card.disconnectAfterSync, "the switch as the user left it")
            assertEquals(1, second.session.syncRecords.entries.value.size)
        } finally {
            first.db.close()
        }
    }
}
