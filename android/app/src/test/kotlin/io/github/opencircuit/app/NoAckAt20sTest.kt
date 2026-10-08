package io.github.opencircuit.app

import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.sync.OpenFallback
import io.github.opencircuit.app.sync.SyncOutcome
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.ringkit.HistoryChannelOutcome
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A ring that answers no open, re-auth or not: each channel ends NO_ACK 20 s after its first open,
 * the one re-auth of the sync is spent on the first channel, nothing loops, and the card says
 * "The ring didn't answer the sync request". The link is not torn down to recover; the
 * "Disconnect after syncing" switch decides, as after any sync.
 */
class NoAckAt20sTest {

    private suspend fun TestScope.silentRing() = syncWorld { scope, now ->
        RingFake(scope, now, mapOf(Command.SYNC_CHANNEL_SLEEP to HistoryTestPages.backlog(2)), ignoreAllOpens = true)
    }

    @Test
    fun eachChannelEndsNoAckTwentySecondsAfterItsOpenWithOneReauthInTheWholeSync() = runTest {
        val w = silentRing()
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SetDisconnectAfterSync(false))
            w.viewModel.onAction(RingAction.SyncNow)

            advanceTo(20_999)
            assertEquals(listOf(1_000L, 6_000L), w.ring.writes.filter { it.hex.startsWith("02") }.map { it.atMillis })
            advanceTo(21_000)
            assertEquals(listOf(1_000L, 6_000L, 21_000L), w.ring.writes.filter { it.hex.startsWith("02") }.map { it.atMillis }, "the all-day open at 20 s")
            advanceTo(40_999)
            assertEquals(emptyList(), w.ring.events.filter { it.what == "commit returned" })
            advanceTo(41_000)
            assertEquals(listOf(41_000L), w.ring.events.filter { it.what == "commit returned" }.map { it.atMillis })
            advanceTo(300_000)

            assertEquals(3, w.ring.writes.count { it.hex.startsWith("02") }, "never loops")
            assertEquals(listOf(6_000L), w.ring.reauths, "one re-auth in the whole sync")
            val report = w.session.sync.state.value.last!!
            assertEquals(listOf(HistoryChannelOutcome.NO_ACK, HistoryChannelOutcome.NO_ACK), report.channels.map { it.verdict })
            assertEquals(OpenFallback.REAUTH, report.channels[0].openFallback)
            assertEquals(false, report.channels[0].fallbackHelped)
            assertEquals(SyncOutcome.NO_ACK, report.outcome)
            assertEquals("The ring didn't answer the sync request", w.viewModel.uiState.value.ringData.problem)
            assertEquals(LinkState.Authenticated, w.session.state.value, "link kept")
            assertEquals(0, w.ring.events.count { it.what == "disconnect" })
        } finally {
            w.db.close()
        }
    }

    @Test
    fun withTheSwitchOnTheLinkIsClosedAfterTheCommitAsAfterAnySync() = runTest {
        val w = silentRing()
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(60_000)

            assertEquals(SyncOutcome.NO_ACK, w.session.sync.state.value.last!!.outcome)
            assertEquals(listOf("commit returned", "disconnect"), w.ring.events.map { it.what }.filter { it == "commit returned" || it == "disconnect" })
        } finally {
            w.db.close()
        }
    }
}
