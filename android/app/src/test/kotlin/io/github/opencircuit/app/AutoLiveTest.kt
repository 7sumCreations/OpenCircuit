package io.github.opencircuit.app

import io.github.opencircuit.app.live.AutoLive
import io.github.opencircuit.app.live.LiveMode
import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.sync.SyncOutcome
import io.github.opencircuit.ringkit.Command
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.time.Instant

/**
 * Live heart rate on Today after a sync, end to end on virtual time: the real session (the real
 * history drain over the real store, the real live measure) on a fake ring that answers the live
 * entry and polls as the real one does. The trigger is the sync's END — one state update carrying a
 * new report whose commit returned — so the link-up sync always runs first. Each case goes red when
 * its rule is removed from [AutoLive] or the controller.
 */
class AutoLiveTest {

    private val heartRateMode = "060100"
    private val spo2Mode = "060200"

    /** Today on screen and the app in front by default; the switch stays at its default (ON). */
    private fun world(
        visible: MutableStateFlow<Boolean> = MutableStateFlow(true),
        foreground: MutableStateFlow<Boolean> = MutableStateFlow(true),
        triggers: Boolean = false,
        failCommit: Boolean = false,
        worn: Boolean = true,
        wallStart: Instant = SYNC_TEST_EPOCH,
    ) = WorldArgs(visible, foreground, triggers, failCommit, worn, wallStart)

    private data class WorldArgs(
        val visible: MutableStateFlow<Boolean>,
        val foreground: MutableStateFlow<Boolean>,
        val triggers: Boolean,
        val failCommit: Boolean,
        val worn: Boolean,
        val wallStart: Instant,
    )

    private suspend fun TestScope.build(args: WorldArgs): SyncWorld = syncWorld(
        triggers = args.triggers,
        wallStart = args.wallStart,
        insideChunk = { if (args.failCommit) error("the commit fails") },
        todayVisible = args.visible,
        appInForeground = args.foreground,
    ) { scope, now -> RingFake(scope, now, mapOf(Command.SYNC_CHANNEL_SLEEP to HistoryTestPages.backlog(3))).also { it.worn = args.worn } }

    /** Moves virtual time on until the sync publishes a new report; returns the virtual time it did. */
    private fun TestScope.untilSyncEnds(w: SyncWorld, before: Any? = w.session.sync.state.value.last, limitMillis: Long = 300_000): Long {
        val until = testScheduler.currentTime + limitMillis
        while (w.session.sync.state.value.last === before) {
            check(testScheduler.currentTime < until) { "the sync did not end" }
            advanceTimeBy(50)
            runCurrent()
        }
        return testScheduler.currentTime
    }

    private fun SyncWorld.timesOf(hex: String, from: Long = 0) = ring.writes.filter { it.hex == hex && it.atMillis >= from }.map { it.atMillis }

    @Test
    fun aSyncEndingWithACommitStartsLiveHeartRateWithNoExtraDelay() = runTest {
        val w = build(world())
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            val end = untilSyncEnds(w)
            assertEquals(SyncOutcome.COMPLETE, w.session.sync.state.value.last!!.outcome)
            // The entry: d0 at once, the mode 250 ms on, the fetch 250 ms after (the controller's spacing).
            advanceTo(end + 1_000)
            val mode = w.timesOf(heartRateMode, end - 50).first()
            assertTrue(mode - end <= 300, "heart-rate mode written ${mode - end} ms after the sync's end")
            advanceTo(end + 15_000)
            val live = w.session.liveMeasure.state.value
            assertEquals(LiveMode.HEART_RATE, live.mode, "within 15 s of the drain's end the hero is live")
            assertTrue(live.auto)
            advanceTo(end + 30_000)
            assertNotNull(w.session.liveMeasure.state.value.settledHeartRate, "the number appears on lock")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aSyncWhoseCommitFailedStartsNothing() = runTest {
        val w = build(world(failCommit = true))
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            val end = untilSyncEnds(w)
            assertNull(w.session.sync.state.value.last!!.commit)
            advanceTo(end + 20_000)
            assertEquals(emptyList(), w.timesOf(heartRateMode))
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aSyncEndingWhileTodayIsNotOnScreenStartsNothing() = runTest {
        val w = build(world(visible = MutableStateFlow(false)))
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            val end = untilSyncEnds(w)
            advanceTo(end + 20_000)
            assertEquals(emptyList(), w.timesOf(heartRateMode))
            assertTrue(w.logs.any { it.startsWith("auto-live: a sync ended while Today was not on screen") })
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aSyncEndingInsideTheNightStartsNothing() = runTest {
        // 09:00 UTC is inside the unlearned night (21:30–10:00): the first link-up sync still runs (none
        // complete yet), live heart rate does not.
        val w = build(world(triggers = true))
        try {
            val end = untilSyncEnds(w, before = null)
            assertEquals(SyncOutcome.COMPLETE, w.session.sync.state.value.last!!.outcome)
            advanceTo(end + 20_000)
            assertEquals(emptyList(), w.timesOf(heartRateMode))
            assertTrue(w.logs.any { it == "auto-live: stopped until the next sync (the night is quiet)" })
        } finally {
            w.db.close()
        }
    }

    @Test
    fun leavingTodayStopsTheAutomaticMeasureAndNothingRestartsIt() = runTest {
        val visible = MutableStateFlow(true)
        val w = build(world(visible = visible))
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            val end = untilSyncEnds(w)
            advanceTo(end + 10_000)
            assertEquals(LiveMode.HEART_RATE, w.session.liveMeasure.state.value.mode)
            visible.value = false
            runCurrent()
            assertNull(w.session.liveMeasure.state.value.mode, "Today left the screen: the automatic measure stopped")
            val left = testScheduler.currentTime
            advanceTo(left + 300_000)
            assertEquals(emptyList(), w.timesOf(heartRateMode, left), "no restart while Today is away")
            assertEquals(emptyList(), w.timesOf("950000", left + 1), "no poll after it stopped")
        } finally {
            w.db.close()
        }
    }

    /** Today → Ring → Today: the screen comes back on the held link, after this connection's committed sync. */
    @Test
    fun todayComingBackAfterACommittedSyncStartsLiveHeartRateAgain() = runTest {
        val visible = MutableStateFlow(true)
        val w = build(world(visible = visible))
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            val end = untilSyncEnds(w)
            advanceTo(end + 10_000)
            visible.value = false
            runCurrent()
            assertNull(w.session.liveMeasure.state.value.mode)
            advanceTo(end + 70_000)
            visible.value = true
            runCurrent()
            val back = testScheduler.currentTime
            advanceTo(back + 1_000)
            val mode = w.timesOf(heartRateMode, back).firstOrNull()
            assertNotNull(mode, "Today came back: live heart rate starts again")
            assertTrue(mode - back <= 300, "heart-rate mode written ${mode - back} ms after Today came back")
            advanceTo(back + 15_000)
            val live = w.session.liveMeasure.state.value
            assertEquals(LiveMode.HEART_RATE, live.mode)
            assertTrue(live.auto)
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aSyncThatEndedWhileTodayWasAwayStartsLiveHeartRateWhenTodayComesOnScreen() = runTest {
        val visible = MutableStateFlow(false)
        val w = build(world(visible = visible))
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            val end = untilSyncEnds(w)
            advanceTo(end + 20_000)
            assertEquals(emptyList(), w.timesOf(heartRateMode))
            visible.value = true
            runCurrent()
            advanceTo(end + 21_000)
            assertEquals(LiveMode.HEART_RATE, w.session.liveMeasure.state.value.mode, "Today on screen after a committed sync: live")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun afterTheUserStoppedItTodayComingBackStartsNothing() = runTest {
        val visible = MutableStateFlow(true)
        val w = build(world(visible = visible))
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            val end = untilSyncEnds(w)
            advanceTo(end + 10_000)
            w.session.liveMeasure.stop()
            runCurrent()
            visible.value = false
            runCurrent()
            visible.value = true
            runCurrent()
            val back = testScheduler.currentTime
            advanceTo(back + 200_000)
            assertEquals(emptyList(), w.timesOf(heartRateMode, back), "the user's Stop holds until the next sync")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aSyncWithNoCommitLeavesTodayComingBackWithNothingToStart() = runTest {
        val visible = MutableStateFlow(true)
        val w = build(world(visible = visible, failCommit = true))
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            val end = untilSyncEnds(w)
            advanceTo(end + 5_000)
            visible.value = false
            runCurrent()
            visible.value = true
            runCurrent()
            advanceTo(end + 30_000)
            assertEquals(emptyList(), w.timesOf(heartRateMode))
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aNewConnectionWithoutItsOwnSyncStartsNothingWhenTodayComesBack() = runTest {
        val visible = MutableStateFlow(true)
        val w = build(world(visible = visible))
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            val end = untilSyncEnds(w)
            advanceTo(end + 10_000)
            visible.value = false
            runCurrent()
            // The link drops and comes back; this connection has had no sync yet.
            w.session.link.disconnect()
            runCurrent()
            assertEquals(io.github.opencircuit.ble.LinkState.Idle, w.session.state.value)
            w.session.connect()
            val until = testScheduler.currentTime + 120_000
            while (w.session.state.value != io.github.opencircuit.ble.LinkState.Authenticated) {
                check(testScheduler.currentTime < until) { "the link did not come back" }
                advanceTimeBy(100)
                runCurrent()
            }
            val up = testScheduler.currentTime
            visible.value = true
            runCurrent()
            advanceTo(up + 20_000)
            assertEquals(emptyList(), w.timesOf(heartRateMode, end + 10_001), "no live heart rate before this connection's own sync")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun whileTodayStaysVisibleEachBudgetEndStartsTheMeasureAgain() = runTest {
        val w = build(world())
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            val end = untilSyncEnds(w)
            advanceTo(end + 200_000)
            val modes = w.timesOf(heartRateMode, end - 50)
            assertTrue(modes.size >= 3, "re-armed at each 90 s budget end: $modes")
            // Locked each time: no back-off, the next entry at the budget's end.
            assertTrue(modes.zipWithNext { a, b -> b - a }.all { it in 90_000..91_500 }, "gaps ${modes.zipWithNext { a, b -> b - a }}")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aRingOffAFingerBacksOffWithTheNotWornRule() = runTest {
        val w = build(world(worn = false))
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            val end = untilSyncEnds(w)
            advanceTo(end + 900_000)
            val gaps = w.timesOf(heartRateMode, end - 50).zipWithNext { a, b -> b - a }
            // First miss: one more try at once (no temperature, two misses needed). Second miss: not
            // worn, wait 90 s × 2. Third: × 4 = 360 s.
            assertTrue(gaps.size >= 3, "gaps $gaps")
            assertTrue(gaps[0] in 90_000..91_500, "after one miss the next try is at once: ${gaps[0]}")
            assertTrue(gaps[1] in 90_000L + 180_000..91_500L + 180_000, "after two misses it waits 180 s: ${gaps[1]}")
            assertTrue(gaps[2] in 90_000L + 360_000..91_500L + 360_000, "after three misses it waits 360 s: ${gaps[2]}")
            assertTrue(AutoLive.BACKOFF_CAP.seconds == 600L)
        } finally {
            w.db.close()
        }
    }

    @Test
    fun anSpO2TapTakesOverAndHeartRateComesBackAfterIt() = runTest {
        val w = build(world())
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            val end = untilSyncEnds(w)
            advanceTo(end + 10_000)
            w.session.liveMeasure.start(LiveMode.SPO2)
            runCurrent()
            val tapped = testScheduler.currentTime
            val state = w.session.liveMeasure.state.value
            assertEquals(LiveMode.SPO2, state.mode)
            assertEquals(false, state.auto, "the user owns the SpO₂ measure")
            advanceTo(tapped + 30_000)
            assertEquals(emptyList(), w.timesOf(heartRateMode, tapped), "no heart rate during the user's SpO₂")
            assertNotNull(w.session.liveMeasure.state.value.newest, "SpO₂ measured")
            advanceTo(tapped + 50_000)
            // The SpO₂ budget is 45 s; heart rate starts again at its end.
            val back = w.timesOf(heartRateMode, tapped).first()
            assertTrue(back - tapped in 45_000..46_500, "heart rate back ${back - tapped} ms after the tap")
            assertEquals(LiveMode.HEART_RATE, w.session.liveMeasure.state.value.mode)
            assertTrue(w.session.liveMeasure.state.value.auto)
            assertTrue(w.timesOf(spo2Mode).isNotEmpty())
        } finally {
            w.db.close()
        }
    }

    @Test
    fun stoppingTheAutomaticMeasureEndsItUntilTheNextSync() = runTest {
        val w = build(world())
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            val end = untilSyncEnds(w)
            advanceTo(end + 10_000)
            w.session.liveMeasure.stop()
            runCurrent()
            val stopped = testScheduler.currentTime
            advanceTo(stopped + 200_000)
            assertEquals(emptyList(), w.timesOf(heartRateMode, stopped))
        } finally {
            w.db.close()
        }
    }

    @Test
    fun twoSyncsThroughOneAutoLiveEachStartLiveHeartRateAfterTheirEnd() = runTest {
        val w = build(world())
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            val first = untilSyncEnds(w)
            advanceTo(first + 10_000)
            assertEquals(LiveMode.HEART_RATE, w.session.liveMeasure.state.value.mode)
            // A second sync (pull to refresh): the automatic measure stands down first.
            assertTrue(w.session.liveMeasure.yieldAuto())
            assertTrue(w.session.sync.syncNow(), "the sync starts once the automatic measure stood down")
            runCurrent()
            val second = untilSyncEnds(w)
            val polls = w.timesOf("950000", first + 10_001).filter { it < second }
            assertEquals(emptyList(), polls, "no live poll during the second sync")
            advanceTo(second + 15_000)
            assertEquals(LiveMode.HEART_RATE, w.session.liveMeasure.state.value.mode, "live again after the second sync")
            assertTrue(w.timesOf(heartRateMode, second - 50).isNotEmpty())
        } finally {
            w.db.close()
        }
    }

    @Test
    fun withTheAppInFrontTheSwitchHoldsTheLinkAndTheHourlySyncStillRunsThroughLiveHeartRate() = runTest {
        // 14:00 UTC, outside the night. "Disconnect after syncing" is at its default, ON; the app is
        // in front, so the link is held after the link-up sync, live heart rate runs on it, and the
        // hourly cadence sync makes the automatic measure stand down and runs.
        val w = build(world(triggers = true, wallStart = Instant.parse("2026-10-08T14:00:00Z")))
        try {
            assertTrue(w.prefs.disconnectAfterSync, "the switch is ON")
            val first = untilSyncEnds(w, before = null)
            advanceTo(first + 10_000)
            assertEquals(io.github.opencircuit.ble.LinkState.Authenticated, w.session.state.value, "the link is held while the app is in front")
            assertEquals(LiveMode.HEART_RATE, w.session.liveMeasure.state.value.mode)
            val second = untilSyncEnds(w, limitMillis = 3_700_000)
            assertTrue(second - first in 3_600_000..3_700_000, "the hourly sync ran ${second - first} ms after the first")
            advanceTo(second + 15_000)
            assertEquals(LiveMode.HEART_RATE, w.session.liveMeasure.state.value.mode, "live again after the hourly sync")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun leavingTheAppWithTheSwitchOnClosesTheHeldLinkAfterStoppingLiveHeartRate() = runTest {
        val visible = MutableStateFlow(true)
        val foreground = MutableStateFlow(true)
        val w = build(world(visible = visible, foreground = foreground, wallStart = Instant.parse("2026-10-08T14:00:00Z")))
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            val end = untilSyncEnds(w)
            advanceTo(end + 10_000)
            assertEquals(LiveMode.HEART_RATE, w.session.liveMeasure.state.value.mode)
            // As the activity's stop does: Today pauses, the app leaves the front, the session is told.
            visible.value = false
            foreground.value = false
            w.session.onAppLeft(switchOn = w.prefs.disconnectAfterSync, changingConfigurations = false)
            runCurrent()
            assertEquals(io.github.opencircuit.ble.LinkState.Idle, w.session.state.value, "the held link closed")
            assertNull(w.session.liveMeasure.state.value.heartRate.failure, "stopped, not lost")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun withTheAppNotInFrontTheSwitchClosesTheLinkAfterTheSync() = runTest {
        val w = build(world(visible = MutableStateFlow(false), foreground = MutableStateFlow(false), wallStart = Instant.parse("2026-10-08T14:00:00Z")))
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            val end = untilSyncEnds(w)
            advanceTo(end + 10_000)
            assertEquals(io.github.opencircuit.ble.LinkState.Idle, w.session.state.value, "the switch closed the link")
            assertEquals(emptyList(), w.timesOf(heartRateMode))
        } finally {
            w.db.close()
        }
    }
}
