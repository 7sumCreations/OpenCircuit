package io.github.opencircuit.app

import io.github.opencircuit.app.debug.SyncCutArming
import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.sync.SyncOutcome
import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.SyncMeasurement
import io.github.opencircuit.store.LocalStore
import io.github.opencircuit.store.StoreDatabase
import io.github.opencircuit.store.SyncLog
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The debug build's "Cut the next sync after N pages" test action, end to end on the ring-faithful
 * fake and the real store: armed with 3 on a ring holding 10 sleep pages, the next sync drops the
 * link right after the 3rd page is stored and acknowledged — never between the journal write and
 * the acknowledgement — and ends as any lost link does (committed, PARTIAL), its log entry marked
 * as cut by the test action. The next normal sync then drains the other 7 from where the ring's
 * pointer was left and its log says the history joins (CONTIGUOUS): the evidence the owner needs
 * from a real ring that an interrupted sync loses nothing.
 */
class InterruptedSyncCutTest {

    private suspend fun heartRates(db: StoreDatabase): List<Instant> =
        LocalStore(db).samples(MetricKind.HEART_RATE, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z")).map { it.start }

    private fun dates(counters: List<Long>) = counters.map { Instant.ofEpochSecond(it + Command.SYNC_EPOCH) }

    @Test
    fun aSyncArmedToCutAfter3PagesLosesNothingAndTheNextSyncJoinsIt() = runTest {
        val arming = SyncCutArming()
        val w = syncWorld(syncFaults = arming, makeRing = ringWith(HistoryTestPages.backlog(10)))
        try {
            arming.arm(3)
            assertEquals(3, arming.armed.value)
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            advanceTo(60_000)

            // Exactly 3 pages acknowledged; the link dropped right after the 3rd acknowledgement.
            assertEquals(3, w.ring.acknowledgedPages.size, "${w.ring.events}")
            val events = w.ring.events
            val acks = events.withIndex().filter { it.value.what.startsWith("ack ") }
            val cut = events.withIndex().first { it.value.what == "disconnect" }
            assertTrue(cut.index > acks[2].index, "the cut follows the 3rd acknowledgement: $events")
            assertEquals(acks[2].value.atMillis, cut.value.atMillis, "at once, not on a later timer")
            assertTrue(events.subList(acks[2].index, cut.index).none { it.what.startsWith("page ") }, "no page sent between them: $events")
            assertNull(arming.armed.value, "taken by the sync: disarmed")

            val first = w.session.sync.state.value.last!!
            assertEquals(SyncOutcome.PARTIAL, first.outcome, "ended as a lost link")
            assertTrue(first.cutByTest)
            // The 3 acknowledged pages are stored on the phone. The all-day channel was never
            // reached, so the commit holds all 18 records back for now (PORTING.md D-267): kept, not lost.
            assertEquals(3, w.journal.read(TEST_RING_ID).entries.size)
            assertEquals(0, first.commit?.records)
            assertEquals(18, first.commit?.heldBack)
            assertEquals(emptyList(), heartRates(w.db))

            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            advanceTo(200_000)

            assertEquals(10, w.ring.acknowledgedPages.size, "the next sync drains the other 7")
            assertEquals(emptyList(), w.ring.stillHeld(Command.SYNC_CHANNEL_SLEEP))
            val second = w.session.sync.state.value.last!!
            assertEquals(SyncOutcome.COMPLETE, second.outcome)
            assertFalse(second.cutByTest, "not cut: the action was taken by the first sync")

            val entries = SyncLog(w.db).read(TEST_RING_ID).entries
            assertEquals(listOf(true, false), entries.map { it.cutByTest })
            val sleep = entries[1].channels.single { it.label == "sleep" }
            assertEquals(HistoryTestPages.counters(3).first(), sleep.firstCounter, "resumed at the 4th page")
            assertEquals(SyncMeasurement.Continuity(SyncMeasurement.ContinuityKind.CONTIGUOUS, 0), sleep.continuity)

            // Every record stored, each once.
            val all = dates((0 until 10).flatMap { HistoryTestPages.counters(it) })
            assertEquals(all, heartRates(w.db))
            assertEquals(all.size, heartRates(w.db).distinct().size, "no record stored twice")

            // Connection details tells the cut sync apart.
            val log = w.viewModel.uiState.value.details.syncLog
            val headers = log.filterNot { it.startsWith("  ") }
            assertEquals(2, headers.size, "$log")
            assertTrue(headers[1].endsWith(" · cut by the test action"), headers[1])
            assertFalse(headers[0].contains("cut by the test action"), headers[0])
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aCutArmedPastTheBacklogIsTakenByTheNextSyncAndCutsNothing() = runTest {
        val arming = SyncCutArming()
        val w = syncWorld(syncFaults = arming, makeRing = ringWith(HistoryTestPages.backlog(2)))
        try {
            arming.arm(5)
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            advanceTo(60_000)
            assertEquals(2, w.ring.acknowledgedPages.size)
            assertEquals(SyncOutcome.COMPLETE, w.session.sync.state.value.last?.outcome)
            assertFalse(w.session.sync.state.value.last!!.cutByTest)
            assertNull(arming.armed.value, "\"the next sync\": disarmed by it even though it never reached 5 pages")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aSyncThatNeverReachesTheRingLeavesTheCutArmed() = runTest {
        val arming = SyncCutArming()
        val w = syncWorld(syncFaults = arming) { scope, now ->
            RingFake(scope, now, mapOf(Command.SYNC_CHANNEL_SLEEP to HistoryTestPages.backlog(2)), connectMillis = 500_000)
        }
        try {
            arming.arm(3)
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            advanceTo(200_000)
            assertEquals(SyncOutcome.NOT_CONNECTED, w.session.sync.state.value.last?.outcome)
            assertEquals(3, arming.armed.value, "taken only at a drain's start: still armed for the sync that reaches the ring")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aSessionWithNoInjectorNeverCuts() = runTest {
        val w = syncWorld(makeRing = ringWith(HistoryTestPages.backlog(10)))
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            advanceTo(120_000)
            assertEquals(10, w.ring.acknowledgedPages.size)
            assertEquals(SyncOutcome.COMPLETE, w.session.sync.state.value.last?.outcome)
            assertFalse(w.session.sync.state.value.last!!.cutByTest)
        } finally {
            w.db.close()
        }
    }
}
