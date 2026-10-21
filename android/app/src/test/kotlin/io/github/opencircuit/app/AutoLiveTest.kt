package io.github.opencircuit.app

import io.github.opencircuit.app.live.AutoLive
import io.github.opencircuit.app.live.LiveMode
import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.sync.AutoSyncSkip
import io.github.opencircuit.app.sync.AutoSyncSkipReason
import io.github.opencircuit.app.sync.AutoSyncTrigger
import io.github.opencircuit.app.sync.SyncOutcome
import io.github.opencircuit.app.sync.SyncTriggers
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

    /** Outside the unlearned night (21:30–10:00 UTC): the automatic syncs and live heart rate run. */
    private val AFTERNOON = Instant.parse("2026-10-08T14:00:00Z")

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
        /** Whether the commit fails, asked at each commit; [failCommit] for every one by default. */
        failing: () -> Boolean = { failCommit },
        /** The ring sits on its charger: every descriptor it sends says so. */
        charging: Boolean = false,
    ) = WorldArgs(visible, foreground, triggers, failing, worn, wallStart, charging)

    private data class WorldArgs(
        val visible: MutableStateFlow<Boolean>,
        val foreground: MutableStateFlow<Boolean>,
        val triggers: Boolean,
        val failCommit: () -> Boolean,
        val worn: Boolean,
        val wallStart: Instant,
        val charging: Boolean,
    )

    private suspend fun TestScope.build(args: WorldArgs): SyncWorld = syncWorld(
        triggers = args.triggers,
        wallStart = args.wallStart,
        insideChunk = { if (args.failCommit()) error("the commit fails") },
        todayVisible = args.visible,
        appInForeground = args.foreground,
    ) { scope, now ->
        val descriptor = if (args.charging) TestFrames.chargingDescriptor else TestFrames.wornDescriptor
        RingFake(
            scope,
            now,
            mapOf(Command.SYNC_CHANNEL_SLEEP to HistoryTestPages.backlog(3)),
            postAuthReply = descriptor,
            statusReply = descriptor,
        ).also { it.worn = args.worn }
    }

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

    /** A ring on its charger is not on a finger: as the user's Measure, the automatic heart rate does not start. */
    @Test
    fun aSyncEndingWithTheRingOnItsChargerStartsNothing() = runTest {
        val w = build(world(charging = true))
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            val end = untilSyncEnds(w)
            assertEquals(SyncOutcome.COMPLETE, w.session.sync.state.value.last!!.outcome)
            assertTrue(w.session.deviceStatus.state.value.onCharger, "the ring said it is on its charger")
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

    /**
     * The link drops and comes back inside the link-up throttle: no sync is due (the last complete
     * one is under 300 s old), so the throttled link-up arms live heart rate on its own.
     */
    @Test
    fun aReconnectInsideTheThrottleStartsLiveHeartRateWhenTodayComesBack() = runTest {
        val visible = MutableStateFlow(true)
        val w = build(world(visible = visible, triggers = true, wallStart = AFTERNOON))
        try {
            val end = untilSyncEnds(w, before = null)
            assertEquals(SyncOutcome.COMPLETE, w.session.sync.state.value.last!!.outcome)
            advanceTo(end + 10_000)
            visible.value = false
            runCurrent()
            w.session.link.disconnect()
            runCurrent()
            assertEquals(io.github.opencircuit.ble.LinkState.Idle, w.session.state.value)
            w.session.connect()
            untilAuthenticated(w)
            val up = testScheduler.currentTime
            assertTrue(up - end < SyncTriggers.THROTTLE.toMillis(), "the reconnect is inside the throttle")
            assertEquals(AutoSyncSkipReason.THROTTLED, w.session.triggers!!.skips.value.last().reason, "no sync was due at this link-up")
            visible.value = true
            runCurrent()
            advanceTo(up + 1_000)
            val mode = w.timesOf(heartRateMode, up).firstOrNull()
            assertNotNull(mode, "Today came back on a throttled reconnect: live heart rate starts")
            assertTrue(mode - up <= 300, "heart-rate mode written ${mode - up} ms after Today came back")
            assertTrue(w.session.liveMeasure.state.value.auto)
        } finally {
            w.db.close()
        }
    }

    /**
     * The user leaves (the switch closes the link after the sync) and returns inside the throttle:
     * the link comes back with nothing due, and live heart rate starts on Today without a second
     * sync and without waiting for the hourly one.
     */
    @Test
    fun returningInsideTheThrottleStartsLiveHeartRateOnTheReconnectedLink() = runTest {
        val foreground = MutableStateFlow(true)
        val w = build(world(foreground = foreground, triggers = true, wallStart = AFTERNOON))
        try {
            val end = untilSyncEnds(w, before = null)
            advanceTo(end + 10_000)
            foreground.value = false
            w.session.onAppLeft(switchOn = true, changingConfigurations = false)
            runCurrent()
            assertEquals(io.github.opencircuit.ble.LinkState.Idle, w.session.state.value, "the switch closed the link")
            foreground.value = true
            w.session.onAppReturned()
            untilAuthenticated(w)
            val up = testScheduler.currentTime
            advanceTo(up + 15_000)
            val live = w.session.liveMeasure.state.value
            assertEquals(LiveMode.HEART_RATE, live.mode, "back inside the throttle: live heart rate on the reconnected link")
            assertTrue(live.auto)
            assertEquals(1, w.ring.events.count { it.what == "commit returned" }, "no second sync ran")
        } finally {
            w.db.close()
        }
    }

    /**
     * Only a throttled link-up on a held, authenticated link arms live heart rate: a link-up held
     * back for any other reason, a throttled one that closed the link, and a throttled cadence start
     * nothing. Driven straight through the skips an [AutoLive] follows, with the night never quiet,
     * so nothing else could hold the measure back.
     */
    @Test
    fun aLinkUpSkippedForAnyOtherReasonStartsNothing() = runTest {
        val w = build(world())
        val skips = MutableStateFlow<List<AutoSyncSkip>>(emptyList())
        try {
            w.session.connect()
            untilAuthenticated(w)
            val probe = AutoLive(
                measure = w.session.liveMeasure,
                sync = w.session.sync.state,
                todayVisible = MutableStateFlow(true),
                nightQuiet = { false },
                scope = backgroundScope,
                log = {},
                linkState = w.session.state,
                linkUpSkips = skips,
            )
            probe.start()
            runCurrent()
            val from = testScheduler.currentTime
            fun skip(trigger: AutoSyncTrigger, reason: AutoSyncSkipReason, disconnected: Boolean = false) {
                skips.value = skips.value + AutoSyncSkip(SYNC_TEST_EPOCH.plusMillis(testScheduler.currentTime), trigger, reason, disconnected)
                runCurrent()
                advanceTimeBy(1_000)
                runCurrent()
            }
            skip(AutoSyncTrigger.LINK_UP, AutoSyncSkipReason.NIGHT_QUIET)
            skip(AutoSyncTrigger.LINK_UP, AutoSyncSkipReason.MEASURING)
            skip(AutoSyncTrigger.LINK_UP, AutoSyncSkipReason.ALREADY_SYNCING)
            skip(AutoSyncTrigger.LINK_UP, AutoSyncSkipReason.THROTTLED, disconnected = true)
            skip(AutoSyncTrigger.CADENCE, AutoSyncSkipReason.THROTTLED)
            assertEquals(emptyList(), w.timesOf(heartRateMode, from), "no skip but a throttled link-up on a held link arms it")
            // The same probe does start on the one that arms: the cases above were not blind.
            skip(AutoSyncTrigger.LINK_UP, AutoSyncSkipReason.THROTTLED)
            assertTrue(w.timesOf(heartRateMode, from).isNotEmpty(), "a throttled link-up on a held link starts live heart rate")
        } finally {
            w.db.close()
        }
    }

    /**
     * The skips list is a state: a throttled link-up and another skip kept before the collector
     * looks arrive as one update. The throttled one still arms live heart rate.
     */
    @Test
    fun aThrottledLinkUpFollowedAtOnceByAnotherSkipStillArms() = runTest {
        val w = build(world())
        val skips = MutableStateFlow<List<AutoSyncSkip>>(emptyList())
        try {
            w.session.connect()
            untilAuthenticated(w)
            AutoLive(
                measure = w.session.liveMeasure, sync = w.session.sync.state, todayVisible = MutableStateFlow(true),
                nightQuiet = { false }, scope = backgroundScope, log = {}, linkState = w.session.state, linkUpSkips = skips,
            ).start()
            runCurrent()
            val from = testScheduler.currentTime
            val at = SYNC_TEST_EPOCH.plusMillis(from)
            skips.value = listOf(
                AutoSyncSkip(at, AutoSyncTrigger.LINK_UP, AutoSyncSkipReason.THROTTLED),
                AutoSyncSkip(at, AutoSyncTrigger.CADENCE, AutoSyncSkipReason.MEASURING),
            )
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertTrue(w.timesOf(heartRateMode, from).isNotEmpty(), "the throttled link-up under a later skip arms live heart rate")
        } finally {
            w.db.close()
        }
    }

    private fun TestScope.untilAuthenticated(w: SyncWorld) {
        val until = testScheduler.currentTime + 120_000
        while (w.session.state.value != io.github.opencircuit.ble.LinkState.Authenticated) {
            check(testScheduler.currentTime < until) { "the link did not come back" }
            advanceTimeBy(100)
            runCurrent()
        }
    }

    @Test
    fun aPausedSyncStartsNothingAndItsResumedEndStartsLiveHeartRate() = runTest {
        val w = build(world())
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            while (w.session.sync.state.value.pagesThisSync < 1) {
                advanceTimeBy(50)
                runCurrent()
            }
            w.session.sync.pause()
            runCurrent()
            val paused = untilSyncEnds(w, before = null)
            val report = w.session.sync.state.value.last!!
            assertTrue(report.paused, "the pause cut the sync short")
            advanceTo(paused + 20_000)
            assertEquals(emptyList(), w.timesOf(heartRateMode), "a paused sync is not finished: no live heart rate on it")
            w.session.sync.resume()
            runCurrent()
            val done = untilSyncEnds(w, before = report)
            assertTrue(!w.session.sync.state.value.last!!.paused)
            advanceTo(done + 15_000)
            assertEquals(LiveMode.HEART_RATE, w.session.liveMeasure.state.value.mode, "the resumed sync's end starts it")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aFailedSpO2MeasureOfTheUsersStillHandsBackToHeartRate() = runTest {
        val w = build(world())
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            val end = untilSyncEnds(w)
            advanceTo(end + 10_000)
            w.ring.refusedModes = setOf(2)
            w.session.liveMeasure.start(LiveMode.SPO2)
            runCurrent()
            val tapped = testScheduler.currentTime
            advanceTo(tapped + 10_000)
            assertNotNull(w.session.liveMeasure.state.value.spo2.failure, "the ring refused SpO₂ twice")
            assertTrue(w.timesOf(heartRateMode, tapped).isNotEmpty(), "heart rate came back after the user's failed measure")
            assertEquals(LiveMode.HEART_RATE, w.session.liveMeasure.state.value.mode)
        } finally {
            w.db.close()
        }
    }

    @Test
    fun leavingTodayAndComingBackKeepsTheNotWornBackOff() = runTest {
        val visible = MutableStateFlow(true)
        val w = build(world(visible = visible, worn = false))
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            val end = untilSyncEnds(w)
            // Two misses (at about +90 s and +180 s): the next try waits 180 s, to about +360 s.
            advanceTo(end + 200_000)
            visible.value = false
            runCurrent()
            visible.value = true
            runCurrent()
            advanceTo(end + 355_000)
            assertEquals(emptyList(), w.timesOf(heartRateMode, end + 200_000), "coming back does not skip the not-worn wait")
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

    /**
     * Live heart rate runs, a pull to refresh makes it stand down, and that sync's commit fails: the
     * link is still held and this connection already had a committed sync, so heart rate comes back
     * at the failed sync's end — it does not stay off while Today stays on screen.
     */
    @Test
    fun aSyncThatTookLiveHeartRateOverAndFailedToCommitHandsItBack() = runTest {
        var failNow = false
        val w = build(world(failing = { failNow }))
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            val first = untilSyncEnds(w)
            advanceTo(first + 10_000)
            assertEquals(LiveMode.HEART_RATE, w.session.liveMeasure.state.value.mode)
            // The ring has new history for the second sync, and its commit fails.
            w.ring.record(Command.SYNC_CHANNEL_SLEEP, HistoryTestPages.backlog(3))
            failNow = true
            assertTrue(w.session.syncNow(), "the sync starts once the automatic measure stood down")
            runCurrent()
            val second = untilSyncEnds(w)
            assertNull(w.session.sync.state.value.last!!.commit, "the second sync's commit failed")
            advanceTo(second + 15_000)
            assertEquals(LiveMode.HEART_RATE, w.session.liveMeasure.state.value.mode, "live again after the failed sync")
            assertTrue(w.session.liveMeasure.state.value.auto)
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
    fun leavingTheAppAfterTheSyncDecidedToKeepTheLinkStillClosesItAtTheSyncsEnd() = runTest {
        val visible = MutableStateFlow(true)
        val foreground = MutableStateFlow(true)
        val w = build(world(visible = visible, foreground = foreground, wallStart = Instant.parse("2026-10-08T14:00:00Z")))
        try {
            // Home pressed while the sync writes its log: its commit returned and it already kept the
            // link (the app was in front), but it has not ended yet.
            w.store.duringLogAppend = {
                if (foreground.value) {
                    visible.value = false
                    foreground.value = false
                    w.session.sync.onAppStopped(changingConfigurations = false)
                    w.session.onAppLeft(switchOn = w.prefs.disconnectAfterSync, changingConfigurations = false)
                }
            }
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            val end = untilSyncEnds(w)
            advanceTo(end + 10_000)
            assertEquals(io.github.opencircuit.ble.LinkState.Idle, w.session.state.value, "the switch closes the link once the user left")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aSyncWhileTodayIsAwayClearsTheNotWornCount() = runTest {
        val visible = MutableStateFlow(true)
        val w = build(world(visible = visible, worn = false))
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            val first = untilSyncEnds(w)
            advanceTo(first + 200_000) // two misses: a 180 s wait is running
            visible.value = false
            runCurrent()
            w.ring.worn = true
            val before = w.session.sync.state.value.last
            assertTrue(w.session.sync.syncNow())
            runCurrent()
            val second = untilSyncEnds(w, before)
            visible.value = true
            runCurrent()
            advanceTo(second + 1_000)
            assertTrue(w.timesOf(heartRateMode, second).isNotEmpty(), "a fresh sync starts afresh: no back-off carried over")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun comingBackDuringTheBackOffWaitsOnlyWhatIsLeftOfIt() = runTest {
        val visible = MutableStateFlow(true)
        val w = build(world(visible = visible, worn = false))
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            val end = untilSyncEnds(w)
            advanceTo(end + 200_000) // the 180 s wait runs to about +360 s
            visible.value = false
            runCurrent()
            advanceTo(end + 260_000)
            visible.value = true
            runCurrent()
            advanceTo(end + 400_000)
            val next = w.timesOf(heartRateMode, end + 200_000).first()
            assertTrue(next - end in 355_000..365_000, "the wait ends where it would have: ${next - end}")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun theHerosStopEndsOnlyTheUsersMeasureAndTheRingTabsSyncNowStandsTheAutomaticOneDown() = runTest {
        val w = build(world())
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            val end = untilSyncEnds(w)
            advanceTo(end + 10_000)
            val host = object : io.github.opencircuit.app.session.SessionHost {
                override val current: kotlinx.coroutines.flow.StateFlow<io.github.opencircuit.app.session.RingSessionController?> = MutableStateFlow(w.session)
                override fun reconnectRemembered() = Unit
                override fun stopReconnecting() = Unit
            }
            io.github.opencircuit.app.today.todayCommands(host).stopMeasure() // a late tap: the user's SpO₂ already ended
            runCurrent()
            assertEquals(LiveMode.HEART_RATE, w.session.liveMeasure.state.value.mode, "the automatic heart rate is not the user's to Stop here")
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            assertTrue(w.session.sync.state.value.syncing, "the Ring tab's Sync now stands the automatic measure down first")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun comingBackToAPausedSyncConnectsOnceThroughTheResumedSync() = runTest {
        val foreground = MutableStateFlow(true)
        val w = build(world(foreground = foreground, wallStart = Instant.parse("2026-10-08T14:00:00Z")))
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            while (w.session.sync.state.value.pagesThisSync < 1) {
                advanceTimeBy(50)
                runCurrent()
            }
            // Home mid-sync, as the activity's stop does: the sync pauses and its end closes the link.
            foreground.value = false
            w.session.sync.onAppStopped(changingConfigurations = false)
            w.session.onAppLeft(switchOn = true, changingConfigurations = false)
            untilSyncEnds(w, before = null)
            advanceTo(testScheduler.currentTime + 5_000)
            assertEquals(io.github.opencircuit.ble.LinkState.Idle, w.session.state.value)
            val back = testScheduler.currentTime
            // Back, as the activity's start does.
            foreground.value = true
            w.session.sync.onAppStarted()
            w.session.onAppReturned()
            advanceTo(back + 60_000)
            assertEquals(1, w.ring.events.count { it.what == "connect" && it.atMillis >= back }, "the resumed sync connects; nothing else does")
        } finally {
            w.db.close()
        }
    }

    /** The link the switch closed when the user left comes back when they return; one the user disconnected does not. */
    @Test
    fun comingBackToTheAppReconnectsTheLinkTheSwitchClosedButNotOneTheUserClosed() = runTest {
        val foreground = MutableStateFlow(true)
        val w = build(world(foreground = foreground, wallStart = Instant.parse("2026-10-08T14:00:00Z")))
        fun TestScope.until(state: io.github.opencircuit.ble.LinkState) {
            val limit = testScheduler.currentTime + 120_000
            while (w.session.state.value != state) {
                check(testScheduler.currentTime < limit) { "the link did not reach $state" }
                advanceTimeBy(100)
                runCurrent()
            }
        }
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            untilSyncEnds(w)
            foreground.value = false
            w.session.onAppLeft(switchOn = true, changingConfigurations = false)
            runCurrent()
            assertEquals(io.github.opencircuit.ble.LinkState.Idle, w.session.state.value)
            foreground.value = true
            w.session.onAppReturned()
            until(io.github.opencircuit.ble.LinkState.Authenticated)

            // The user's own Disconnect, then leaving and coming back: it stays closed.
            w.session.link.disconnect()
            runCurrent()
            foreground.value = false
            w.session.onAppLeft(switchOn = true, changingConfigurations = false)
            foreground.value = true
            w.session.onAppReturned()
            advanceTo(testScheduler.currentTime + 10_000)
            assertEquals(io.github.opencircuit.ble.LinkState.Idle, w.session.state.value, "the user's own Disconnect holds")
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
