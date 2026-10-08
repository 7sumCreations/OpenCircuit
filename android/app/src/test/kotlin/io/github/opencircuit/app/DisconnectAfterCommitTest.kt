package io.github.opencircuit.app

import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.sync.SyncOutcome
import io.github.opencircuit.ble.LinkState
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * "Disconnect after syncing" ON closes the link only once the commit's write transaction has
 * returned — the history is durable in the store before the connection goes — and after the last
 * acknowledgement went out; a failed commit leaves the link up (the pages stay on the phone,
 * nothing is cut). OFF holds the link, and the held link is drained again on the cadence. Ordered
 * on the ring fake's one event log: what the ring saw and the store's own hooks, at the same
 * virtual instants.
 */
class DisconnectAfterCommitTest {

    @Test
    fun switchOnDisconnectsOnlyAfterTheCommitTransactionReturnedAndTheLastAckWentOut() = runTest {
        lateinit var ring: RingFake
        val makeRing = ringWith(HistoryTestPages.backlog(3))
        val w = syncWorld(insideChunk = { ring.note("inside the commit transaction") }) { scope, now -> makeRing(scope, now).also { ring = it } }
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(60_000)

            val order = w.ring.events.map { it.what }.filter {
                it == "inside the commit transaction" || it == "commit returned" || it == "disconnect" || it.startsWith("ack ")
            }
            assertEquals(listOf("inside the commit transaction", "commit returned", "disconnect"), order.filterNot { it.startsWith("ack ") })
            assertTrue(order.indexOfLast { it.startsWith("ack ") } < order.indexOf("disconnect"), "the last acknowledgement before the disconnect: $order")
            assertEquals(LinkState.Idle, w.session.state.value)
            assertEquals(SyncOutcome.COMPLETE, w.session.sync.state.value.last?.outcome)
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aFailedCommitDoesNotCloseTheLink() = runTest {
        val w = syncWorld(insideChunk = { throw IllegalStateException("disk full") }, makeRing = ringWith(HistoryTestPages.backlog(3)))
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(60_000)

            assertEquals(SyncOutcome.COMMIT_FAILED, w.session.sync.state.value.last?.outcome)
            assertEquals(emptyList(), w.ring.events.filter { it.what == "disconnect" }, "never disconnected")
            assertEquals(LinkState.Authenticated, w.session.state.value)
            assertEquals(3, w.journal.read(TEST_RING_ID).entries.size, "the pages stay on the phone")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun switchOffHoldsTheLinkAndTheCadenceDrainsItAgain() = runTest {
        val w = syncWorld(triggers = true, wallStart = Instant.parse("2026-10-08T14:00:00Z"), makeRing = ringWith(HistoryTestPages.backlog(3)))
        try {
            w.prefs.setDisconnectAfterSync(false)
            advanceTo(60_000)
            assertEquals(1, w.sleepOpens().size, "the link-up synced")
            assertNotNull(w.session.sync.state.value.last)
            assertEquals(emptyList(), w.ring.events.filter { it.what == "disconnect" })
            assertEquals(LinkState.Authenticated, w.session.state.value)

            advanceTo(2 * 3_600_000L)
            assertEquals(LinkState.Authenticated, w.session.state.value, "still held two hours on")
            assertEquals(emptyList(), w.ring.events.filter { it.what == "disconnect" })
            assertTrue(w.sleepOpens().size >= 2, "drained again on the cadence: ${w.sleepOpens()}")
        } finally {
            w.db.close()
        }
    }
}
