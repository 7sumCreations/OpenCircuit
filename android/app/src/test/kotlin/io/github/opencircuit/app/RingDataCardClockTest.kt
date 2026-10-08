package io.github.opencircuit.app

import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.ble.LinkState
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The Ring data card's "Last synced N ago" follows the clock even when nothing else changes: with
 * "Disconnect after syncing" on, the link is idle after the sync and no other input moves, yet the
 * age (and the overdue warning measured from it) must not freeze at "just now".
 */
class RingDataCardClockTest {

    @Test
    fun theLastSyncedAgeMovesOnWhileTheLinkIsIdle() = runTest {
        val w = syncWorld(HistoryTestPages.backlog(3))
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(60_000)
            val finished = assertNotNull(w.session.sync.state.value.last).finishedAt
            assertEquals(LinkState.Idle, w.session.state.value, "the switch is on: disconnected after the sync")
            assertEquals("Last synced just now", w.viewModel.uiState.value.ringData.headline)

            // Three days away from the ring: the card must say so (and the overdue warning reads from the same clock).
            advanceTo(60_000 + 3 * 86_400_000L + 120_000)
            val wall = SYNC_TEST_EPOCH.plusMillis(testScheduler.currentTime)
            val days = Duration.between(finished, wall).toDays()
            assertEquals("Last synced $days days ago", w.viewModel.uiState.value.ringData.headline)
        } finally {
            w.db.close()
        }
    }
}
