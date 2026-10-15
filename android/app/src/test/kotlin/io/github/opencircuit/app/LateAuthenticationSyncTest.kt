package io.github.opencircuit.app

import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.sync.SyncOutcome
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ringkit.Command
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A ring whose link authenticates late. On the owner's Gen 2 (FR02.022, release 0.2.0), with
 * "Disconnect after syncing" on, a second Sync now in the same process ended NOT_CONNECTED after
 * exactly 30.0 s, four times in a row: the link authenticates only on the ring's first frame, which
 * came 38.8 s after connecting (the ring's own timer can take ~110 s, `docs/PROTOCOL.md` §5.8), and
 * the NOT_CONNECTED sync then disconnected the link that was about to authenticate. Only a relaunch
 * (whose connect waits with no deadline) recovered.
 *
 * The sync's connect wait covers the ring's own timer, and a sync that ends NOT_CONNECTED never
 * closes a link that is still coming up: once it authenticates, the link-up syncs.
 */
class LateAuthenticationSyncTest {

    private val wallStart = Instant.parse("2026-10-08T14:00:00Z")

    private fun lateRing(): (CoroutineScope, () -> Long) -> RingFake = { scope, now ->
        RingFake(
            scope,
            now,
            mapOf(Command.SYNC_CHANNEL_SLEEP to HistoryTestPages.backlog(3), Command.SYNC_CHANNEL_ALL_DAY to emptyList()),
            notStreamingAfterMillis = 11_400,
        )
    }

    private fun SyncWorld.disconnectsAfter(at: Long): List<Long> =
        ring.events.filter { it.what == "disconnect" && it.atMillis > at }.map { it.atMillis }

    @Test
    fun aSecondSyncNowAfterSyncAndDisconnectWaitsForALateAuthenticationAndRuns() = runTest {
        val w = syncWorld(wallStart = wallStart, makeRing = lateRing())
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(60_000)
            assertEquals(SyncOutcome.COMPLETE, w.session.sync.state.value.last?.outcome)
            assertEquals(LinkState.Idle, w.session.state.value, "the switch is on: disconnected after the first sync")

            // The real trace: the next connection authenticates only 38.8 s after connecting.
            w.ring.connectMillis = 38_800
            val tap = testScheduler.currentTime
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(tap + 30_001)
            assertEquals(emptyList(), w.disconnectsAfter(tap), "nothing closes the link coming up at 30 s")
            advanceTo(tap + 100_000)

            val second = assertNotNull(w.session.sync.state.value.last)
            assertNotEquals(SyncOutcome.NOT_CONNECTED, second.outcome, "the second sync ran")
            assertEquals(SyncOutcome.COMPLETE, second.outcome)
            assertEquals(2, w.sleepOpens().size, "both syncs opened the sleep channel")
            assertTrue(w.sleepOpens().last() >= tap + 38_800, "not before the late authentication: ${w.sleepOpens()}")
            assertEquals(1, w.ring.events.count { it.what == "connect" && it.atMillis >= tap }, "the tap connected afresh, once")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun theConnectWaitCoversTheRingsOwnTimerOfAbout110Seconds() = runTest {
        val w = syncWorld(wallStart = wallStart, makeRing = lateRing())
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(60_000)
            w.ring.connectMillis = 115_000
            val tap = testScheduler.currentTime
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(tap + 200_000)

            assertEquals(SyncOutcome.COMPLETE, w.session.sync.state.value.last?.outcome)
            assertEquals(2, w.sleepOpens().size)
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aNotConnectedSyncLeavesALinkStillComingUpAlone() = runTest {
        val w = syncWorld(wallStart = wallStart, makeRing = lateRing())
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(60_000)
            // A ring that does not authenticate within the sync's whole connect wait.
            w.ring.connectMillis = 600_000
            val tap = testScheduler.currentTime
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(tap + 200_000)

            assertEquals(SyncOutcome.NOT_CONNECTED, w.session.sync.state.value.last?.outcome)
            assertEquals(emptyList(), w.disconnectsAfter(tap), "the link still coming up is not closed")
            assertEquals(LinkState.NotStreaming, w.session.state.value)

            advanceTo(tap + 600_000)
            assertEquals(LinkState.Authenticated, w.session.state.value, "it came up in the end")
        } finally {
            w.db.close()
        }
    }

    /** The link that came up after a NOT_CONNECTED sync syncs at once, though a complete sync is minutes old. */
    @Test
    fun theLinkThatAuthenticatesAfterANotConnectedSyncSyncsAtLinkUp() = runTest {
        val w = syncWorld(triggers = true, wallStart = wallStart, makeRing = lateRing())
        try {
            advanceTo(60_000)
            assertEquals(SyncOutcome.COMPLETE, w.session.sync.state.value.last?.outcome, "the link-up synced")
            assertEquals(LinkState.Idle, w.session.state.value)

            w.ring.connectMillis = 150_000
            val tap = testScheduler.currentTime
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(tap + 125_000)
            assertEquals(SyncOutcome.NOT_CONNECTED, w.session.sync.state.value.last?.outcome)
            assertEquals(1, w.sleepOpens().size)

            advanceTo(tap + 200_000)
            assertEquals(2, w.sleepOpens().size, "the late link-up synced, within 300 s of the complete sync")
            assertTrue(w.sleepOpens().last() >= tap + 150_000)
            assertEquals(SyncOutcome.COMPLETE, w.session.sync.state.value.last?.outcome)
        } finally {
            w.db.close()
        }
    }
}
