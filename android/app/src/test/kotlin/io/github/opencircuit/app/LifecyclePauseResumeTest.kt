package io.github.opencircuit.app

import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.sync.SyncOutcome
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.store.LocalStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Leaving the app during a sync (PORTING.md D-268; no foreground service in this version): the
 * sync is paused — no further open, nudge or reopen; what is stored is committed, the commit
 * starting no new transaction 5 s after the pause; then, with "Disconnect after syncing" on, the
 * link closes once the ACK lane is idle (at most 2 s); the card says "Sync paused — open
 * OpenCircuit to finish". Coming back resumes it, and the resumed sync finishes the backlog with
 * every record stored once. A pause is asked through [io.github.opencircuit.app.sync.HistoryDrainController.pause],
 * which is what the activity's stop leads to ([io.github.opencircuit.app.sync.SyncLifecycle]).
 *
 * Sleep pages arrive at 2 700, 5 300, 7 100 and 8 300 (gaps 1.2 / 2.6 / 1.8 s), each sent only
 * once the one before is acknowledged.
 */
class LifecyclePauseResumeTest {

    private val pages = HistoryTestPages.backlog(4)

    private suspend fun TestScope.world(
        chunkRecords: Int = 5_000,
        insideChunk: suspend (Int) -> Unit = {},
    ) = syncWorld(chunkRecords, insideChunk) { scope, now ->
        RingFake(scope, now, mapOf(Command.SYNC_CHANNEL_SLEEP to pages, Command.SYNC_CHANNEL_ALL_DAY to emptyList()))
    }

    private fun opensAndFetches(w: SyncWorld) = w.ring.writes.filter { it.hex.startsWith("02") || it.hex == "070000" }

    private suspend fun heartRateCount(w: SyncWorld) =
        LocalStore(w.db).samples(MetricKind.HEART_RATE, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z")).size

    @Test
    fun aPausedSyncStopsCommitsDisconnectsAndSaysSoThenResumesAndFinishes() = runTest {
        val w = world()
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(6_000) // pages 1 and 2 stored and acknowledged
            val writesBefore = opensAndFetches(w)
            w.session.sync.pause()
            runCurrent()

            assertEquals(listOf(6_000L), w.ring.events.filter { it.what == "commit returned" }.map { it.atMillis }, "committed at once")
            assertEquals(listOf(6_000L), w.ring.events.filter { it.what == "disconnect" }.map { it.atMillis }, "then disconnected")
            advanceTo(29_000)
            assertEquals(writesBefore, opensAndFetches(w), "no open, nudge or reopen after the pause")
            val paused = w.session.sync.state.value
            assertFalse(paused.syncing)
            assertTrue(paused.last!!.paused)
            assertEquals(SyncOutcome.PARTIAL, paused.last!!.outcome)
            assertEquals("Sync paused — open OpenCircuit to finish", w.viewModel.uiState.value.ringData.headline)
            assertEquals(LinkState.Idle, w.session.state.value)

            w.session.sync.resume()
            runCurrent()
            advanceTo(90_000)

            val done = w.session.sync.state.value.last!!
            assertEquals(SyncOutcome.COMPLETE, done.outcome)
            assertFalse(done.paused)
            assertEquals(pages.map { it.toPlainHex() }, w.ring.acknowledgedPages.map { it.toPlainHex() }, "each page acknowledged once")
            assertEquals(24, heartRateCount(w))
            assertEquals(emptyList(), w.journal.read(TEST_RING_ID).entries)
            assertTrue(w.viewModel.uiState.value.ringData.headline.startsWith("Last synced"))
        } finally {
            w.db.close()
        }
    }

    @Test
    fun theActivitysStopPausesAndItsStartResumesButARotationDoesNothing() = runTest {
        val w = world()
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(4_000)
            w.session.sync.onAppStopped(changingConfigurations = true) // a rotation
            runCurrent()
            assertTrue(w.session.sync.state.value.syncing, "a rotation does not pause")
            assertEquals(emptyList(), w.ring.events.filter { it.what == "commit returned" })

            advanceTo(6_000)
            w.session.sync.onAppStopped(changingConfigurations = false) // Home
            runCurrent()
            assertTrue(w.session.sync.state.value.last!!.paused)

            advanceTo(10_000)
            w.session.sync.onAppStarted()
            runCurrent()
            assertTrue(w.session.sync.state.value.syncing, "coming back resumes")
            advanceTo(90_000)
            assertEquals(SyncOutcome.COMPLETE, w.session.sync.state.value.last!!.outcome)

            w.session.sync.onAppStarted() // nothing unfinished: no new sync
            runCurrent()
            assertFalse(w.session.sync.state.value.syncing)
        } finally {
            w.db.close()
        }
    }

    @Test
    fun comingBackWhileThePauseIsStillFinishingResumesAsSoonAsItHas() = runTest {
        val w = world()
        try {
            w.store.duringAppend = { page -> if (page.contentEquals(pages[1])) delay(1_500) }
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(5_500)
            w.session.sync.onAppStopped(changingConfigurations = false)
            advanceTo(6_000) // the disconnect waits for page 2 until 6 800
            w.session.sync.onAppStarted()
            runCurrent()
            advanceTo(90_000)

            val last = w.session.sync.state.value.last!!
            assertEquals(SyncOutcome.COMPLETE, last.outcome)
            assertFalse(last.paused)
            assertEquals(24, heartRateCount(w))
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aPauseWhileAPageIsBeingStoredDisconnectsOnlyOnceItIsAcknowledged() = runTest {
        val w = world()
        try {
            w.store.duringAppend = { page -> if (page.contentEquals(pages[1])) delay(1_500) }
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(5_500) // page 2 arrived at 5 300; its store takes until 6 800
            w.session.sync.pause()
            advanceTo(6_799)
            assertEquals(emptyList(), w.ring.events.filter { it.what == "disconnect" }, "not while the page is in flight")
            advanceTo(6_800)

            val ackAt = w.ring.events.indexOfFirst { it.what == "ack ${pages[1].toPlainHex().take(6)}" }
            val disconnectAt = w.ring.events.indexOfFirst { it.what == "disconnect" }
            assertTrue(ackAt in 0 until disconnectAt, "acknowledged, then disconnected")
            assertEquals(6_800L, w.ring.events[disconnectAt].atMillis)
            assertEquals(0, w.session.sync.state.value.last!!.pagesUnacknowledged)
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aPageStillInFlightTwoSecondsAfterThePauseIsCutAndOfferedAgain() = runTest {
        val w = world()
        try {
            var slow = true
            w.store.duringAppend = { page -> if (slow && page.contentEquals(pages[1])) { slow = false; delay(3_000) } }
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(5_500)
            w.session.sync.pause()
            advanceTo(7_499)
            assertEquals(emptyList(), w.ring.events.filter { it.what == "disconnect" })
            advanceTo(7_500)
            assertEquals(listOf(7_500L), w.ring.events.filter { it.what == "disconnect" }.map { it.atMillis }, "2 s after the pause")
            advanceTo(20_000)
            assertEquals(1, w.session.sync.state.value.last!!.pagesUnacknowledged, "the cut page is traced")

            w.session.sync.resume()
            runCurrent()
            advanceTo(90_000)
            assertEquals(SyncOutcome.COMPLETE, w.session.sync.state.value.last!!.outcome)
            assertEquals(24, heartRateCount(w), "the page offered again is stored once")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun withTheSwitchOffAPauseKeepsTheLinkAndThePagesTheRingStillSendsAreKept() = runTest {
        val w = world()
        try {
            w.prefs.setDisconnectAfterSync(false)
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(6_000)
            w.session.sync.pause()
            advanceTo(29_000)

            assertEquals(emptyList(), w.ring.events.filter { it.what == "disconnect" })
            assertEquals(LinkState.Authenticated, w.session.state.value)
            // The ring went on with pages 3 and 4: stored outside the sync, then acknowledged.
            assertEquals(2, w.session.historyPages.counts.value.outsideDrain)
            assertEquals(pages.map { it.toPlainHex() }, w.ring.acknowledgedPages.map { it.toPlainHex() })

            w.session.sync.resume()
            runCurrent()
            advanceTo(90_000)
            assertEquals(SyncOutcome.COMPLETE, w.session.sync.state.value.last!!.outcome)
            assertEquals(24, heartRateCount(w))
            assertEquals(emptyList(), w.journal.read(TEST_RING_ID).entries)
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aPauseDuringTheCommitStartsNoTransactionFiveSecondsAfterIt() = runTest {
        lateinit var w: SyncWorld
        val chunkStarts = mutableListOf<Long>()
        w = world(chunkRecords = 6, insideChunk = { index ->
            chunkStarts += testScheduler.currentTime
            if (index == 0) w.session.sync.pause() // the user leaves while the commit runs
            delay(2_000) // each transaction takes 2 s
        })
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(60_000)

            val commitAt = chunkStarts.first()
            // Paused at the first transaction: the ones starting 2 s and 4 s later run; one 6 s later does not.
            assertEquals(listOf(commitAt, commitAt + 2_000, commitAt + 4_000), chunkStarts)
            assertEquals(18, heartRateCount(w))
            assertEquals(listOf(pages[3].toPlainHex()), w.journal.read(TEST_RING_ID).entries.map { it.page.toPlainHex() }, "the rest waits")
            val report = w.session.sync.state.value.last!!
            assertTrue(report.paused)
            assertEquals(1, report.commit!!.chunksLeft)

            w.session.sync.resume()
            runCurrent()
            advanceTo(120_000)
            assertEquals(24, heartRateCount(w))
            assertEquals(emptyList(), w.journal.read(TEST_RING_ID).entries)
        } finally {
            w.db.close()
        }
    }
}
