package io.github.opencircuit.app

import io.github.opencircuit.app.data.PrefsSyncMarks
import io.github.opencircuit.app.live.LiveMode
import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.sync.AutoSyncSkip
import io.github.opencircuit.app.sync.AutoSyncSkipReason
import io.github.opencircuit.app.sync.AutoSyncTrigger
import io.github.opencircuit.ble.LinkState
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every automatic sync that does not run says why, where the phone shows it (Connection details):
 * after a relaunch on the owner's phone the link-up sync ran on some launches and not on others,
 * and logcat is out of reach there. The four reasons — throttle, night quiet, a sync already
 * running, a measure running — are each recorded with the trigger they held back. And the
 * sync-and-disconnect mode never holds an idle link: a link-up the throttle holds back with
 * "Disconnect after syncing" on closes the link; with it off the link is held, as before.
 */
class AutoSyncSkipReasonTest {

    private val wallStart = Instant.parse("2026-10-08T14:00:00Z")
    private val linkUp = wallStart.plusMillis(500)

    private fun SyncWorld.skips(): List<AutoSyncSkip> = session.triggers!!.skips.value

    private fun SyncWorld.skipRow(): String = viewModel.uiState.value.details.rows.single { it.label == "Auto-sync skipped" }.value

    @Test
    fun aThrottledLinkUpWithTheSwitchOnRecordsWhyAndClosesTheLink() = runTest {
        val kv = InMemoryKeyValues()
        PrefsSyncMarks(kv, TEST_RING_ID).setLastCompleteSync(linkUp.minusSeconds(60))
        val w = syncWorld(triggers = true, keyValues = kv, wallStart = wallStart, makeRing = ringWith(HistoryTestPages.backlog(3)))
        try {
            advanceTo(5_000)
            assertEquals(emptyList(), w.sleepOpens())
            assertEquals(listOf(AutoSyncSkip(linkUp, AutoSyncTrigger.LINK_UP, AutoSyncSkipReason.THROTTLED, disconnected = true)), w.skips())
            assertEquals(listOf(500L), w.ring.events.filter { it.what == "disconnect" }.map { it.atMillis }, "the idle link is closed at the link-up")
            assertEquals(LinkState.Idle, w.session.state.value)
            assertEquals("14:00:00 link-up: last complete sync under 5 min ago, link closed", w.skipRow())
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aThrottledLinkUpWithTheSwitchOffRecordsWhyAndHoldsTheLink() = runTest {
        val kv = InMemoryKeyValues()
        PrefsSyncMarks(kv, TEST_RING_ID).setLastCompleteSync(linkUp.minusSeconds(60))
        val w = syncWorld(triggers = true, keyValues = kv, wallStart = wallStart, makeRing = ringWith(HistoryTestPages.backlog(3)))
        try {
            w.prefs.setDisconnectAfterSync(false)
            advanceTo(120_000)
            assertEquals(emptyList(), w.sleepOpens())
            assertEquals(listOf(AutoSyncSkip(linkUp, AutoSyncTrigger.LINK_UP, AutoSyncSkipReason.THROTTLED)), w.skips())
            assertEquals(emptyList(), w.ring.events.filter { it.what == "disconnect" })
            assertEquals(LinkState.Authenticated, w.session.state.value, "held, as before")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aLinkUpInsideTheNightRecordsNightQuiet() = runTest {
        val newYork = ZoneId.of("America/New_York")
        val twoAm = Instant.parse("2026-10-08T06:00:00Z") // 02:00 EDT
        val kv = InMemoryKeyValues()
        PrefsSyncMarks(kv, TEST_RING_ID).setLastCompleteSync(twoAm.minusSeconds(12 * 3600))
        val w = syncWorld(triggers = true, keyValues = kv, zone = newYork, wallStart = twoAm, makeRing = ringWith(HistoryTestPages.backlog(3)))
        try {
            advanceTo(60_000)
            assertEquals(emptyList(), w.sleepOpens())
            // The switch is on: the automatic link-up held back by the night closes the link (PORTING.md D-275).
            assertEquals(listOf(AutoSyncSkip(twoAm.plusMillis(500), AutoSyncTrigger.LINK_UP, AutoSyncSkipReason.NIGHT_QUIET, disconnected = true)), w.skips())
            assertEquals("02:00:00 link-up: night quiet, link closed", w.skipRow(), "in the phone's zone")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aLinkUpWhileSyncNowRunsRecordsAlreadySyncing() = runTest {
        val w = syncWorld(triggers = true, wallStart = wallStart, makeRing = ringWith(HistoryTestPages.backlog(3)))
        try {
            // Sync now is tapped while the link is still coming up (it authenticates at 500 ms).
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(60_000)
            assertEquals(1, w.sleepOpens().size, "Sync now's own sync, once")
            assertEquals(listOf(AutoSyncSkip(linkUp, AutoSyncTrigger.LINK_UP, AutoSyncSkipReason.ALREADY_SYNCING)), w.skips())
        } finally {
            w.db.close()
        }
    }

    @Test
    fun theCadenceDueDuringAMeasureRecordsMeasuring() = runTest {
        val kv = InMemoryKeyValues()
        // Throttled at the link-up; the 1 h cadence from that sync falls due at virtual 3 301 500.
        PrefsSyncMarks(kv, TEST_RING_ID).setLastCompleteSync(linkUp.minusSeconds(299))
        val w = syncWorld(triggers = true, keyValues = kv, wallStart = wallStart, makeRing = ringWith(HistoryTestPages.backlog(3)))
        try {
            w.prefs.setDisconnectAfterSync(false)
            advanceTo(3_296_000)
            w.viewModel.onAction(RingAction.Measure(LiveMode.HEART_RATE))
            runCurrent()
            assertEquals(LiveMode.HEART_RATE, w.session.liveMeasure.state.value.mode)

            advanceTo(3_302_000)
            assertEquals(emptyList(), w.sleepOpens(), "no sync during the measure")
            val due = linkUp.minusSeconds(299).plusSeconds(3_600)
            assertEquals(
                listOf(
                    AutoSyncSkip(linkUp, AutoSyncTrigger.LINK_UP, AutoSyncSkipReason.THROTTLED),
                    AutoSyncSkip(due, AutoSyncTrigger.CADENCE, AutoSyncSkipReason.MEASURING),
                ),
                w.skips(),
                "logs=${w.logs}",
            )
            assertTrue(w.skipRow().startsWith("14:55:01 hourly sync: a measure was running; 14:00:00 link-up"), w.skipRow())
        } finally {
            w.db.close()
        }
    }

    /** A skip repeated on every evaluation is kept once, at its latest time, so it cannot push the others out. */
    @Test
    fun theSameSkipRepeatedIsKeptOnceAtItsLatestTime() = runTest {
        val kv = InMemoryKeyValues()
        PrefsSyncMarks(kv, TEST_RING_ID).setLastCompleteSync(linkUp.minusSeconds(299))
        val w = syncWorld(triggers = true, keyValues = kv, wallStart = wallStart, makeRing = ringWith(HistoryTestPages.backlog(3)))
        try {
            w.prefs.setDisconnectAfterSync(false)
            advanceTo(3_296_000)
            w.viewModel.onAction(RingAction.Measure(LiveMode.HEART_RATE))
            runCurrent()
            // The cadence stays due through the measure: the evaluation a minute later skips again.
            advanceTo(3_362_000)
            val cadence = w.skips().filter { it.trigger == AutoSyncTrigger.CADENCE }
            assertEquals(1, cadence.size, "${w.skips()}")
            assertTrue(cadence.single().at.isAfter(linkUp.minusSeconds(299).plusSeconds(3_600)), "${cadence.single()}")
        } finally {
            w.db.close()
        }
    }
}
