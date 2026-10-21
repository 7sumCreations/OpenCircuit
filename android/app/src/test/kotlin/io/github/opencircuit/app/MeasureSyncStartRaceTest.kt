package io.github.opencircuit.app

import io.github.opencircuit.app.data.PrefsSyncMarks
import io.github.opencircuit.app.live.LiveMode
import io.github.opencircuit.app.session.RingSessionController
import io.github.opencircuit.app.sync.AutoSyncSkip
import io.github.opencircuit.app.sync.AutoSyncSkipReason
import io.github.opencircuit.app.sync.AutoSyncTrigger
import io.github.opencircuit.app.sync.SyncOutcome
import io.github.opencircuit.ble.FakeRingLink
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ble.RefusalReason
import io.github.opencircuit.ble.SendResult
import io.github.opencircuit.ringkit.Command
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A live measure and a history sync never both hold the link, however their starts interleave.
 * Each side used to check the other's flag and then set its own, under two different locks, so an
 * automatic heart-rate restart and a sync started at the same instant could both proceed: live
 * writes mid-drain, where a `d0` answered with `0x50` reads as the end of history (PROTOCOL.md §4).
 *
 * The first two cases start both on real threads, released together and swept across a range of
 * offsets so every interleaving of the two check-then-set windows is reached; their coroutines
 * never run (a dispatcher that drops them), so only the start decisions are judged. The last two
 * run the real session on virtual time: a link-up whose sync a measure refused is not lost.
 */
class MeasureSyncStartRaceTest {

    /** Accepts every coroutine and never runs one: the starts' synchronous decisions are all that happens. */
    private object NeverRuns : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
    }

    private class Outcome(val first: Boolean, val second: Boolean, val measuring: Boolean, val syncing: Boolean)

    /**
     * Runs [first] and [second] on two threads released together, [ITERATIONS] times, each time on a
     * fresh session; one thread waits a little longer each iteration, the two taking turns.
     */
    private fun race(first: (RingSessionController) -> Boolean, second: (RingSessionController) -> Boolean): List<Outcome> =
        (0 until ITERATIONS).map { i ->
            val scope = CoroutineScope(Job() + NeverRuns)
            try {
                val session = RingSessionController(FakeRingLink(testRing), scope, monotonicMillis = { 0L }, log = {})
                val ready = CountDownLatch(2)
                // Spun on, not awaited: a parked thread wakes tens of microseconds late, far wider than the windows.
                val go = AtomicBoolean(false)
                val results = BooleanArray(2)
                val offset = (i / 2) % SWEEP
                val threads = listOf(first, second).mapIndexed { index, action ->
                    Thread {
                        ready.countDown()
                        while (!go.get()) Thread.onSpinWait()
                        // The two sides take turns being the later one, by 0 to SWEEP spins.
                        if (index == i % 2) repeat(offset) { Thread.onSpinWait() }
                        results[index] = action(session)
                    }.apply { start() }
                }
                ready.await()
                go.set(true)
                threads.forEach { it.join() }
                Outcome(results[0], results[1], session.liveMeasure.isMeasuring.value, session.sync.isSyncing.value)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun anAutomaticHeartRateStartAndASyncStartedTogetherLeaveOnlyTheSyncRunning() {
        val outcomes = race(
            { it.liveMeasure.startAuto(LiveMode.HEART_RATE) },
            { it.syncNow() },
        )
        val both = outcomes.count { it.measuring && it.syncing }
        val syncRefused = outcomes.count { !it.second }
        val measureLeft = outcomes.count { it.measuring }
        // The sync outranks the automatic measure (it stands down for a sync), whichever came first.
        assertEquals(
            "both running 0, sync refused 0, measure left running 0",
            "both running $both, sync refused $syncRefused, measure left running $measureLeft",
            "over $ITERATIONS starts together",
        )
    }

    @Test
    fun aUserMeasureAndASyncStartedTogetherLeaveExactlyOneHoldingTheLink() {
        val outcomes = race(
            { session -> session.liveMeasure.start(LiveMode.HEART_RATE); session.liveMeasure.isMeasuring.value },
            { it.sync.syncNow() },
        )
        val both = outcomes.count { it.measuring && it.syncing }
        val neither = outcomes.count { !it.measuring && !it.syncing }
        val misreported = outcomes.count { it.second != it.syncing }
        assertEquals(
            "both 0, neither 0, sync start misreported 0",
            "both $both, neither $neither, sync start misreported $misreported",
            "over $ITERATIONS starts together",
        )
    }

    /**
     * Two syncs started together: the one refused reads the other as running, never as a measure —
     * the link is claimed a moment before the sync is marked running, and a refusal read in that
     * moment would be kept as a "measure running" skip that never happened.
     */
    @Test
    fun aSyncRefusedByAnotherStartingTogetherSeesThatSyncRunning() {
        val outcomes = race(
            { it.syncNow() },
            { session -> session.sync.syncNow() || session.sync.running },
        )
        val misread = outcomes.count { !it.second }
        assertEquals("refusals misread 0", "refusals misread $misread", "over $ITERATIONS starts together")
    }

    private val wallStart = Instant.parse("2026-10-08T14:00:00Z")
    private val linkUp = wallStart.plusMillis(500)

    /** Live-measure and status writes: none may reach the ring between a sync's first open and its commit. */
    private fun liveWritesIn(w: SyncWorld, from: Int, to: Int): List<String> =
        w.ring.events.subList(from, to).map { it.what }.filter { it.startsWith("write ") }.map { it.removePrefix("write ") }
            .filter { it == "d00000" || it == "950000" || (it.startsWith("06") && it.length == 6) }

    /** The writes from the first sleep-channel open to the first commit. */
    private fun duringFirstDrain(w: SyncWorld): List<String> {
        val events = w.ring.events
        val open = events.indexOfFirst { it.what.startsWith("write 02") }
        val commit = events.indexOfFirst { it.what == "commit returned" }
        assertTrue(open in 0 until commit, "a drain ran: open $open, commit $commit")
        return liveWritesIn(w, open, commit)
    }

    /**
     * The user taps Measure in the instant the link-up evaluates (after its busy check, while it
     * reads the stored nights), so the link-up's sync is refused: the skip still says so, and once
     * the user's measure ends the link-up's sync runs — with "Disconnect after syncing" on there is
     * no hourly sync that would otherwise ever serve it.
     */
    @Test
    fun aLinkUpSyncRefusedByTheUsersMeasureRunsWhenThatMeasureEnds() = runTest {
        val kv = InMemoryKeyValues()
        PrefsSyncMarks(kv, TEST_RING_ID).setLastCompleteSync(linkUp.minusSeconds(2 * 3600))
        val w = syncWorld(triggers = true, keyValues = kv, wallStart = wallStart, makeRing = ringWith(HistoryTestPages.backlog(3)))
        try {
            var tapped = false
            w.duringNightsRead = {
                if (!tapped) {
                    tapped = true
                    w.session.liveMeasure.start(LiveMode.HEART_RATE)
                }
            }
            advanceTo(20_000)
            assertTrue(tapped, "the tap landed in the link-up's evaluation")
            assertTrue(w.session.liveMeasure.isMeasuring.value)
            assertEquals(emptyList(), w.sleepOpens(), "no sync during the user's measure")
            assertEquals(listOf(AutoSyncSkip(linkUp, AutoSyncTrigger.LINK_UP, AutoSyncSkipReason.MEASURING)), w.session.triggers!!.skips.value)

            w.session.liveMeasure.stop()
            advanceTo(25_000)
            assertEquals(1, w.sleepOpens().size, "the link-up's sync runs once the measure has ended")
            advanceTo(120_000)
            assertEquals(SyncOutcome.COMPLETE, w.session.sync.state.value.last?.outcome)
            assertEquals(1, w.sleepOpens().size, "exactly once")
            assertEquals(emptyList(), duringFirstDrain(w))
        } finally {
            w.db.close()
        }
    }

    /**
     * The dashboard's automatic heart rate starts in the same instant the link-up evaluates: the
     * link-up's sync still runs (the automatic measure stands down for it), with no live write in its drain.
     */
    @Test
    fun aLinkUpSyncDuringAnAutomaticMeasureStillRuns() = runTest {
        val kv = InMemoryKeyValues()
        PrefsSyncMarks(kv, TEST_RING_ID).setLastCompleteSync(linkUp.minusSeconds(2 * 3600))
        val w = syncWorld(triggers = true, keyValues = kv, wallStart = wallStart, makeRing = ringWith(HistoryTestPages.backlog(3)))
        try {
            var started = false
            w.duringNightsRead = {
                if (!started) started = w.session.liveMeasure.startAuto(LiveMode.HEART_RATE)
            }
            advanceTo(120_000)
            assertTrue(started, "the automatic measure started in the link-up's evaluation")
            assertEquals(1, w.sleepOpens().size, "the link-up's sync ran")
            assertEquals(SyncOutcome.COMPLETE, w.session.sync.state.value.last?.outcome)
            assertEquals(emptyList(), w.session.triggers!!.skips.value, "nothing held it back")
            assertEquals(emptyList(), duringFirstDrain(w))
        } finally {
            w.db.close()
        }
    }

    /**
     * A link-up the throttle would have held back (the last complete sync a minute old) while the
     * user measures: kept as a "measure running" skip, and nothing is owed — no sync runs when the
     * measure ends, as none would have run without it.
     */
    @Test
    fun aThrottledLinkUpDuringTheUsersMeasureOwesNoSync() = runTest {
        val kv = InMemoryKeyValues()
        PrefsSyncMarks(kv, TEST_RING_ID).setLastCompleteSync(linkUp.minusSeconds(60))
        val w = syncWorld(triggers = true, keyValues = kv, wallStart = wallStart, makeRing = ringWith(HistoryTestPages.backlog(3)))
        try {
            // The tap lands the instant the link authenticates, before the link-up is evaluated.
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                w.session.state.first { it == LinkState.Authenticated }
                w.session.liveMeasure.start(LiveMode.HEART_RATE)
            }
            advanceTo(20_000)
            assertTrue(w.session.liveMeasure.isMeasuring.value)
            assertEquals(listOf(AutoSyncSkip(linkUp, AutoSyncTrigger.LINK_UP, AutoSyncSkipReason.MEASURING)), w.session.triggers!!.skips.value)
            w.session.liveMeasure.stop()
            advanceTo(200_000)
            assertEquals(emptyList(), w.sleepOpens(), "the throttle holds as it would have without the measure")
        } finally {
            w.db.close()
        }
    }

    /**
     * The automatic measure's entry has just written its status query when a sync makes it stand
     * down: the ring's answer is still to come, and a `0x50` answer read after the open could end
     * the history early. The sync's first open waits for that answer, as it does for the keepalive's.
     */
    @Test
    fun aSyncThatMadeTheAutomaticMeasureStandDownWaitsForItsStatusQuery() = runTest {
        // The ring answers a d0 1.5 s after it.
        val w = syncWorld { scope, now ->
            RingFake(scope, now, mapOf(Command.SYNC_CHANNEL_SLEEP to HistoryTestPages.backlog(1)), statusReplyMillis = 1_500)
        }
        try {
            advanceTo(10_000)
            assertTrue(w.session.liveMeasure.startAuto(LiveMode.HEART_RATE))
            runCurrent()
            assertEquals(listOf(10_000L), w.ring.writes.filter { it.hex == "d00000" && it.atMillis >= 10_000 }.map { it.atMillis }, "the entry's d0")
            assertTrue(w.session.syncNow())
            advanceTo(11_499)
            assertEquals(emptyList(), w.ring.writes.filter { it.hex.startsWith("02") }, "no open before the d0's answer")
            advanceTo(11_500)
            assertEquals(listOf(11_500L), w.ring.writes.filter { it.hex.startsWith("02") }.map { it.atMillis })
        } finally {
            w.db.close()
        }
    }

    /**
     * A measure coroutine that passed its cancellation check before the sync claimed the link, and
     * reaches the send only after the sync has made it stand down and opened the drain: its write
     * (a status query above all, whose `0x50` answer the drain would read as the end of history)
     * is refused, never written.
     */
    @Test
    fun aMeasureWriteThatArrivesAfterTheSyncHasOpenedIsRefusedNotWritten() = runTest {
        val w = syncWorld(makeRing = ringWith(HistoryTestPages.backlog(3)))
        try {
            advanceTo(10_000)
            assertTrue(w.session.syncNow())
            var step = 0L
            while (w.sleepOpens().isEmpty() && step < 5_000) advanceTo(10_000 + ++step)
            assertEquals(1, w.sleepOpens().size, "the drain opened")
            assertTrue(w.session.sync.isSyncing.value, "and still holds the link")

            val late = listOf(Command.statusQuery, Command.liveHRMode, Command.fetch, Command.poll)
            val results = late.map { w.session.measureSend(it) }
            runCurrent()
            assertEquals(late.map { SendResult.Refused(RefusalReason.HISTORY_UNSAFE) }, results)
            val open = w.ring.writes.indexOfFirst { it.hex.startsWith("02") }
            assertEquals(
                emptyList(),
                w.ring.writes.drop(open).map { it.hex }.filter { it == "d00000" || it == "070000" || it == "950000" || (it.startsWith("06") && it.length == 6) },
                "no measure write after the drain's open",
            )
        } finally {
            w.db.close()
        }
    }

    private companion object {
        const val ITERATIONS = 4_000
        const val SWEEP = 200
    }
}
