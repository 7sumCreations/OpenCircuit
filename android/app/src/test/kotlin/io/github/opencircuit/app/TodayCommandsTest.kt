package io.github.opencircuit.app

import io.github.opencircuit.app.live.LiveMode
import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.session.RingSessionController
import io.github.opencircuit.app.session.SessionHost
import io.github.opencircuit.app.today.todayCommands
import io.github.opencircuit.ringkit.Command
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What Today's own actions do on the real session (real drain, real store, the ring fake): pull to
 * refresh makes the automatic heart-rate measure stand down first — a sync never starts while a
 * measure runs — then syncs; a measure the user started is left alone and no sync starts. The hero's
 * SpO₂ Measure takes the automatic heart rate over; Stop ends it.
 */
class TodayCommandsTest {

    private suspend fun TestScope.world() = syncWorld(todayVisible = MutableStateFlow(true), appInForeground = MutableStateFlow(true)) { scope, now ->
        RingFake(scope, now, mapOf(Command.SYNC_CHANNEL_SLEEP to HistoryTestPages.backlog(3)))
    }

    private fun TestScope.untilSyncEnds(w: SyncWorld, before: Any? = w.session.sync.state.value.last): Long {
        val until = testScheduler.currentTime + 300_000
        while (w.session.sync.state.value.last === before) {
            check(testScheduler.currentTime < until) { "the sync did not end" }
            advanceTimeBy(50)
            runCurrent()
        }
        return testScheduler.currentTime
    }

    /** The app's host with this world's session as the current one. */
    private fun hostOf(w: SyncWorld): SessionHost = object : SessionHost {
        override val current: StateFlow<RingSessionController?> = MutableStateFlow(w.session)
        override fun reconnectRemembered() = Unit
        override fun stopReconnecting() = Unit
    }

    @Test
    fun pullToRefreshStopsTheAutomaticMeasureThenSyncs() = runTest {
        val w = world()
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            val first = untilSyncEnds(w)
            advanceTo(first + 10_000)
            assertEquals(LiveMode.HEART_RATE, w.session.liveMeasure.state.value.mode)
            val before = w.session.sync.state.value.last
            todayCommands(hostOf(w)).refresh()
            runCurrent()
            assertTrue(w.session.sync.state.value.syncing, "the refresh started a sync")
            assertNull(w.session.liveMeasure.state.value.mode, "the automatic measure stood down first")
            val second = untilSyncEnds(w, before)
            assertEquals(emptyList(), w.ring.writes.filter { it.hex == "950000" && it.atMillis in (first + 10_001)..second }.map { it.atMillis }, "no live poll during the sync")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun pullToRefreshLeavesTheUsersOwnMeasureAlone() = runTest {
        val w = world()
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            val first = untilSyncEnds(w)
            advanceTo(first + 10_000)
            val commands = todayCommands(hostOf(w))
            commands.measureSpO2()
            runCurrent()
            assertEquals(LiveMode.SPO2, w.session.liveMeasure.state.value.mode)
            assertFalse(w.session.liveMeasure.state.value.auto, "the user owns it")
            commands.refresh()
            runCurrent()
            assertFalse(w.session.sync.state.value.syncing, "no sync while the user's measure runs")
            assertEquals(LiveMode.SPO2, w.session.liveMeasure.state.value.mode)
            commands.stopMeasure()
            runCurrent()
            assertTrue(w.session.liveMeasure.state.value.mode != LiveMode.SPO2, "Stop ended the SpO₂ measure")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun withNoRingTheCommandsDoNothing() = runTest {
        val commands = todayCommands(null)
        commands.refresh()
        commands.measureSpO2()
        commands.stopMeasure()
    }
}
