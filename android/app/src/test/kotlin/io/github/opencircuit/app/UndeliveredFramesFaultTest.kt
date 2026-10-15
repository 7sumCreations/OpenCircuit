package io.github.opencircuit.app

import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.sync.SyncFault
import io.github.opencircuit.app.sync.SyncOutcome
import io.github.opencircuit.ringkit.Command
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Frames the link received but the app never took (`LinkTeardown.undeliveredFrames`) are dropped
 * at the teardown: a page among them was never stored, so never acknowledged, and the ring keeps
 * it — but the app fell behind, and that must be seen. The sync's own teardown (after the commit,
 * with "Disconnect after syncing" on) reports them; the sync's report carries the count as a
 * fault, and the Ring data card says so. Without any, nothing is shown.
 */
class UndeliveredFramesFaultTest {

    private suspend fun TestScope.syncWith(undelivered: Int): SyncWorld {
        val w = syncWorld { scope, now ->
            RingFake(scope, now, mapOf(Command.SYNC_CHANNEL_SLEEP to HistoryTestPages.backlog(2)), undeliveredOnTeardown = undelivered)
        }
        advanceTo(1_000)
        w.viewModel.onAction(RingAction.SyncNow)
        advanceTo(60_000)
        return w
    }

    @Test
    fun undeliveredFramesAtTheSyncsTeardownAreASyncFaultOnTheCard() = runTest {
        val w = syncWith(undelivered = 2)
        try {
            val report = w.session.sync.state.value.last!!
            assertEquals(SyncOutcome.COMPLETE, report.outcome, "what came is stored: the outcome is unchanged")
            assertEquals(2, report.undeliveredFrames)
            assertEquals(setOf(SyncFault.UNDELIVERED_FRAMES), report.faults)
            assertEquals("2 frames from the ring weren't read — sync again", w.viewModel.uiState.value.ringData.problem)
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aCleanTeardownIsNoFault() = runTest {
        val w = syncWith(undelivered = 0)
        try {
            val report = w.session.sync.state.value.last!!
            assertEquals(0, report.undeliveredFrames)
            assertEquals(0, report.pagesUnacknowledged)
            assertEquals(emptySet(), report.faults)
            assertNull(w.viewModel.uiState.value.ringData.problem)
            assertEquals(1, w.ring.events.count { it.what == "disconnect" })
        } finally {
            w.db.close()
        }
    }
}
