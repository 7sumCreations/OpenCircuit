package io.github.opencircuit.app

import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.store.LocalStore
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tracer: the user taps Sync now on the Ring data card, and the history crosses every seam
 * to the store — the view model → the drain → the link's open and `07 00 00` → a ring-faithful
 * fake's pages → the session's one collector → the frame dispatcher → the history routes → the
 * journal (real store) → the acknowledgement → the next page … → `0x50` → the commit (archive +
 * samples + journal cleared, one transaction) → disconnect → "Last synced just now".
 *
 * Asserted on what the ring saw (writes, acknowledgements, events in order) and on the real
 * store's rows; never on the controller's own state alone.
 */
class SyncTracerEndToEndTest {

    @Test
    fun syncNowDrainsTheSleepChannelStoresEveryRecordOfEveryAcknowledgedPageThenDisconnects() = runTest {
        val pages = HistoryTestPages.backlog(3)
        val w = syncWorld(pages)
        try {
            advanceTo(1_000)
            assertEquals(LinkState.Authenticated, w.session.state.value, "opening the screen connected the remembered ring")

            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()

            // The open at 2026-10-08T09:00:01Z: cursor 0x0cbc2351, sleep channel 00. No 07 00 00 until 300 ms later.
            assertEquals(listOf(TimedWrite(1_000, "02000cbc2351000100")), w.ring.writes.filter { it.hex.startsWith("02") })
            advanceTo(1_299)
            assertEquals(emptyList(), w.ring.writes.filter { it.hex == "070000" }.map { it.atMillis })
            advanceTo(1_300)
            assertEquals(listOf(1_300L), w.ring.writes.filter { it.hex == "070000" }.map { it.atMillis })
            assertEquals("Syncing…", w.viewModel.uiState.value.ringData.headline)

            advanceTo(60_000)

            // The ring got an acknowledgement for every page, in order, and holds none any more.
            assertEquals(pages.map { it.toPlainHex() }, w.ring.acknowledgedPages.map { it.toPlainHex() })
            assertEquals(emptyList(), w.ring.stillHeld(Command.SYNC_CHANNEL_SLEEP))
            // Every record of every acknowledged page is in the store's archive …
            assertEquals(
                (0 until 3).flatMap { HistoryTestPages.counters(it) },
                w.blobs.loadEpochArchive(TEST_RING_ID).records.map { it.counter },
            )
            // … its heart-rate samples are stored at those records' times …
            val recordTimes = (0 until 3).flatMap { HistoryTestPages.counters(it) }.map { Instant.ofEpochSecond(Command.SYNC_EPOCH + it) }.toSet()
            val heartRates = LocalStore(w.db).samples(MetricKind.HEART_RATE, Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-07-01T00:00:00Z"))
            assertTrue(heartRates.isNotEmpty(), "heart-rate samples stored")
            assertTrue(heartRates.all { it.start in recordTimes }, "every stored sample is one of the drained records'")
            // … and the journal is empty: the commit consumed every page.
            assertEquals(emptyList(), w.journal.read(TEST_RING_ID).entries)

            // Disconnected once, and only after the commit returned.
            val order = w.ring.events.map { it.what }.filter { it == "commit returned" || it == "disconnect" }
            assertEquals(listOf("commit returned", "disconnect"), order)
            assertEquals(LinkState.Idle, w.session.state.value)
            // The app never wrote the ring's auth command.
            assertTrue(w.ring.writes.none { it.hex == "010000" || it.hex.startsWith("0101") })

            val card = w.viewModel.uiState.value.ringData
            assertEquals("Last synced just now", card.headline)
            assertEquals("18 records · complete", card.lastSync)
            assertEquals(null, card.problem)
        } finally {
            w.db.close()
        }
    }

    @Test
    fun withTheSwitchOffTheLinkIsHeldAfterTheSync() = runTest {
        val w = syncWorld(HistoryTestPages.backlog(2))
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SetDisconnectAfterSync(false))
            assertEquals(false, w.prefs.disconnectAfterSync, "the switch is saved")

            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(60_000)

            assertEquals(2, w.ring.acknowledgedPages.size)
            assertEquals(12, w.blobs.loadEpochArchive(TEST_RING_ID).records.size)
            assertTrue(w.ring.events.none { it.what == "disconnect" })
            assertEquals(LinkState.Authenticated, w.session.state.value)
            assertEquals(false, w.viewModel.uiState.value.ringData.disconnectAfterSync)
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aSecondSyncOnTheSameSessionStoresNoRecordTwice() = runTest {
        val w = syncWorld(HistoryTestPages.backlog(2))
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(60_000)
            val local = LocalStore(w.db)
            val from = Instant.parse("2026-06-01T00:00:00Z")
            val to = Instant.parse("2026-07-01T00:00:00Z")
            val samplesAfterFirst = local.samples(MetricKind.HEART_RATE, from, to)

            // The link was closed after the first sync; the second reconnects, and the ring has nothing new.
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(120_000)

            assertEquals(4, w.ring.writes.count { it.hex.startsWith("02") }, "two opens per sync: sleep, then all-day")
            assertEquals(2, w.ring.events.count { it.what == "commit returned" })
            assertEquals(12, w.blobs.loadEpochArchive(TEST_RING_ID).records.size)
            assertEquals(samplesAfterFirst, local.samples(MetricKind.HEART_RATE, from, to))
            assertEquals("0 records · complete", w.viewModel.uiState.value.ringData.lastSync)
        } finally {
            w.db.close()
        }
    }
}
