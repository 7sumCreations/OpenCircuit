package io.github.opencircuit.app

import io.github.opencircuit.app.live.LiveMode
import io.github.opencircuit.app.ring.LinkAction
import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.sync.AutoSyncSkip
import io.github.opencircuit.app.sync.AutoSyncSkipReason
import io.github.opencircuit.app.sync.AutoSyncTrigger
import io.github.opencircuit.app.sync.SyncOutcome
import io.github.opencircuit.ble.LinkState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * With "Disconnect after syncing" on, a link-up the 300 s throttle holds back closes the link —
 * but only an AUTOMATIC link-up (the remembered ring's reconnect at launch, the link's own
 * reconnect after a drop). A link-up the user asked for (Scan & connect, Try again) is kept, so a
 * live measure can start on it. Daytime: the night's quiet plays no part. Each test first runs the
 * launch's link-up sync to COMPLETE (the switch closes the link after it, at about 10 s), then
 * brings the link up again at 60 s, inside the throttle.
 */
class UserLinkUpKeptTest {

    private val wallStart = Instant.parse("2026-10-08T14:00:00Z")

    private suspend fun TestScope.syncedAndClosed(): SyncWorld {
        val w = syncWorld(triggers = true, wallStart = wallStart, makeRing = ringWith(HistoryTestPages.backlog(3)))
        advanceTo(60_000)
        assertEquals(SyncOutcome.COMPLETE, w.session.sync.state.value.last?.outcome, "the launch's link-up sync completed")
        assertEquals(LinkState.Idle, w.session.state.value, "the switch is on: disconnected after the sync")
        return w
    }

    private fun SyncWorld.disconnects(): List<Long> = ring.events.filter { it.what == "disconnect" }.map { it.atMillis }

    private fun SyncWorld.skips(): List<AutoSyncSkip> = session.triggers!!.skips.value

    @Test
    fun aScanAndConnectInsideTheThrottleKeepsTheLinkAndAMeasureStarts() = runTest {
        val w = syncedAndClosed()
        try {
            val closedAfterSync = w.disconnects()
            assertEquals(1, closedAfterSync.size)
            // The connect flow's end: the ring was picked and is connected (RingConnector.connect).
            sessionsOf(w.session).connect(w.session.link.ring)
            advanceTo(70_000)
            assertEquals(LinkState.Authenticated, w.session.state.value, "the user's own link-up is not closed by the throttle")
            assertEquals(closedAfterSync, w.disconnects(), "no disconnect after the user's link-up")
            assertEquals(1, w.sleepOpens().size, "still no second sync inside the throttle")
            assertEquals(
                listOf(AutoSyncSkip(wallStart.plusMillis(60_500), AutoSyncTrigger.LINK_UP, AutoSyncSkipReason.THROTTLED, disconnected = false)),
                w.skips(),
            )

            w.viewModel.onAction(RingAction.Measure(LiveMode.HEART_RATE))
            advanceTo(73_000)
            assertTrue(w.session.liveMeasure.isMeasuring.value, "the measure runs")
            assertTrue(w.ring.writes.any { it.atMillis >= 70_000 && it.hex == "060100" }, "the measure entered heart-rate mode: ${w.ring.writes.filter { it.atMillis >= 70_000 }}")
            assertEquals(LinkState.Authenticated, w.session.state.value)
            assertEquals(closedAfterSync, w.disconnects())
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aTryAgainInsideTheThrottleKeepsTheLink() = runTest {
        val w = syncedAndClosed()
        try {
            val closedAfterSync = w.disconnects()
            w.viewModel.onAction(RingAction.Link(LinkAction.TRY_AGAIN))
            advanceTo(70_000)
            assertEquals(LinkState.Authenticated, w.session.state.value, "Try again is the user's: its link-up is kept")
            assertEquals(closedAfterSync, w.disconnects())
        } finally {
            w.db.close()
        }
    }

    @Test
    fun theLinksOwnReconnectInsideTheThrottleStillClosesTheLink() = runTest {
        val w = syncedAndClosed()
        try {
            w.ring.reconnectOnItsOwn()
            advanceTo(70_000)
            assertEquals(LinkState.Idle, w.session.state.value, "an automatic link-up inside the throttle is closed")
            assertEquals(listOf(60_500L), w.disconnects().drop(1), "closed at its link-up")
            assertEquals(
                listOf(AutoSyncSkip(wallStart.plusMillis(60_500), AutoSyncTrigger.LINK_UP, AutoSyncSkipReason.THROTTLED, disconnected = true)),
                w.skips(),
            )
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aUserLinkUpThatDropsAndComesBackOnItsOwnIsAutomaticAgain() = runTest {
        // The user's mark is taken by the link-up it was for: the link's later reconnect is not the user's.
        val w = syncedAndClosed()
        try {
            sessionsOf(w.session).connect(w.session.link.ring)
            advanceTo(70_000)
            assertEquals(LinkState.Authenticated, w.session.state.value)
            w.ring.dropLink()
            advanceTo(80_000)
            w.ring.reconnectOnItsOwn()
            advanceTo(90_000)
            assertEquals(LinkState.Idle, w.session.state.value, "the link's own reconnect inside the throttle is closed")
            assertEquals(80_500L, w.disconnects().last())
        } finally {
            w.db.close()
        }
    }
}
