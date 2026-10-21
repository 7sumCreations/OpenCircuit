package io.github.opencircuit.app

import io.github.opencircuit.app.today.TodaySession
import io.github.opencircuit.app.today.TodaySource
import io.github.opencircuit.app.today.TodayStored
import io.github.opencircuit.app.today.TodayUiState
import io.github.opencircuit.app.today.TodayViewModel
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.store.SyncLogEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Today hero shows what the phone already holds the moment the screen opens: the newest heart
 * rate and blood oxygen with when they were taken, and when the ring last synced. Ages move on with
 * the clock. Once shown, values stay on screen while a reload runs (and when one fails) — the screen
 * never drops back to an empty, loading state.
 */
class TodayViewModelTest {

    /** 2026-10-21 16:02 UTC at virtual time 0. */
    private val epoch: Instant = Instant.parse("2026-10-21T16:02:00Z")
    private val utc: ZoneId = ZoneOffset.UTC

    private fun hr(at: String, bpm: Double) = QuantitySample(MetricKind.HEART_RATE, Instant.parse(at), value = bpm)
    private fun spo2(at: String, fraction: Double) = QuantitySample(MetricKind.SPO2, Instant.parse(at), value = fraction)

    /** A source whose revisions the test drives and whose loads it can hold open. */
    private class ScriptedSource(var stored: TodayStored) : TodaySource {
        val revisions = MutableStateFlow(0L)
        var hold: CompletableDeferred<Unit>? = null
        var fail = false
        var loads = 0
        override fun revisions(): Flow<Long> = revisions
        override suspend fun load(day: LocalDate, zone: ZoneId, windowDays: Int): TodayStored {
            loads++
            hold?.await()
            if (fail) throw IllegalStateException("disk")
            return stored
        }
    }

    private fun TestScope.open(source: TodaySource, session: Flow<TodaySession> = MutableStateFlow(TodaySession())): TodayViewModel {
        val vm = TodayViewModel(
            source = source,
            session = session,
            scope = backgroundScope,
            wallClock = { epoch.plusMillis(testScheduler.currentTime) },
            zone = { utc },
        )
        runCurrent()
        return vm
    }

    private fun entry(finishedAt: Instant) = SyncLogEntry(startedAt = finishedAt.minusSeconds(60), finishedAt = finishedAt, outcome = "COMPLETE")

    @Test
    fun theHeroShowsTheNewestStoredHeartRateAndOxygenWithTheirAge() = runTest {
        val vm = open(ScriptedSource(TodayStored(hr("2026-10-21T13:02:00Z", 62.0), spo2("2026-10-21T15:47:00Z", 0.97))))
        val state = vm.uiState.value
        assertFalse(state.loading)
        assertEquals("62", state.heartRate.value)
        assertEquals("bpm", state.heartRate.unit)
        assertEquals("as of 13:02 · 3 h ago", state.heartRate.note)
        assertEquals("97", state.spo2.value)
        assertEquals("%", state.spo2.unit)
        assertEquals("as of 15:47 · 15 min ago", state.spo2.note)
    }

    @Test
    fun aReadingFromAnEarlierDayNamesTheDay() = runTest {
        val vm = open(ScriptedSource(TodayStored(heartRate = hr("2026-10-19T08:30:00Z", 55.0))))
        assertEquals("as of Mon 19 Oct · 2 days ago", vm.uiState.value.heartRate.note)
    }

    @Test
    fun nothingStoredSaysSoAndHeartRateWithoutOxygenShowsOnlyTheHeartRate() = runTest {
        val empty = open(ScriptedSource(TodayStored()))
        assertNull(empty.uiState.value.heartRate.value)
        assertEquals("Waiting for the first sync", empty.uiState.value.heartRate.note, "never synced, nothing stored")
        assertEquals("Not synced yet", empty.uiState.value.lastSynced)

        val synced = MutableStateFlow(TodaySession(log = listOf(entry(epoch.minusSeconds(7_200)))))
        val hrOnly = open(ScriptedSource(TodayStored(heartRate = hr("2026-10-21T16:00:00Z", 70.0))), synced)
        assertEquals("70", hrOnly.uiState.value.heartRate.value)
        assertNull(hrOnly.uiState.value.spo2.value)
        assertEquals("No reading yet", hrOnly.uiState.value.spo2.note, "synced, but no oxygen reading stored")
    }

    @Test
    fun lastSyncedFollowsTheSessionsLogAndARunningSync() = runTest {
        val session = MutableStateFlow(TodaySession(link = LinkState.Authenticated, ringName = "Demo ring", log = listOf(entry(epoch.minusSeconds(7_200)))))
        val vm = open(ScriptedSource(TodayStored()), session)
        assertEquals("Last synced 2 h ago", vm.uiState.value.lastSynced)
        assertEquals("Connected", vm.uiState.value.ring)

        session.value = session.value.copy(syncing = true)
        runCurrent()
        // The strip says a sync runs; "Last synced" stays on screen beside it.
        assertEquals("Syncing…", vm.uiState.value.strip?.text)
        assertEquals("Last synced 2 h ago", vm.uiState.value.lastSynced)

        session.value = session.value.copy(syncing = false, log = session.value.log + entry(epoch.plusMillis(testScheduler.currentTime)))
        runCurrent()
        assertEquals("Last synced just now", vm.uiState.value.lastSynced)
    }

    @Test
    fun withNoRingTheHeroSaysWhereToPairOne() = runTest {
        val vm = open(ScriptedSource(TodayStored()))
        assertEquals("No ring yet — pair one on the Ring tab", vm.uiState.value.ring)
    }

    @Test
    fun agesMoveOnWithTheClockWhileNothingElseChanges() = runTest {
        val vm = open(ScriptedSource(TodayStored(heartRate = hr("2026-10-21T13:02:00Z", 62.0))))
        assertEquals("as of 13:02 · 3 h ago", vm.uiState.value.heartRate.note)
        advanceTimeBy(60 * 60_000L)
        runCurrent()
        assertEquals("as of 13:02 · 4 h ago", vm.uiState.value.heartRate.note)
    }

    @Test
    fun valuesStayOnScreenWhileAReloadRunsAndWhenOneFails() = runTest {
        val source = ScriptedSource(TodayStored(heartRate = hr("2026-10-21T16:00:00Z", 62.0)))
        val vm = open(source)
        assertEquals("62", vm.uiState.value.heartRate.value)

        val gate = CompletableDeferred<Unit>()
        source.hold = gate
        source.stored = TodayStored(heartRate = hr("2026-10-21T16:01:00Z", 64.0))
        source.revisions.value = 1
        runCurrent()
        assertEquals(2, source.loads, "the reload started")
        val during: TodayUiState = vm.uiState.value
        assertFalse(during.loading, "never back to loading after the first frame")
        assertEquals("62", during.heartRate.value, "the previous value stays while the reload runs")

        gate.complete(Unit)
        runCurrent()
        assertEquals("64", vm.uiState.value.heartRate.value)

        source.hold = null
        source.fail = true
        source.revisions.value = 2
        runCurrent()
        assertEquals("64", vm.uiState.value.heartRate.value, "a failed reload keeps what was shown")
        assertFalse(vm.uiState.value.loading)
    }

    @Test
    fun beforeTheFirstLoadTheStateIsLoading() = runTest {
        val source = ScriptedSource(TodayStored(heartRate = hr("2026-10-21T16:00:00Z", 62.0))).apply { hold = CompletableDeferred() }
        val vm = open(source)
        assertTrue(vm.uiState.value.loading)
        assertNull(vm.uiState.value.heartRate.value)
    }

    @Test
    fun todaysOwnActionsReachTheSessionAndNavigationDoesNot() = runTest {
        val calls = mutableListOf<String>()
        val commands = object : io.github.opencircuit.app.today.TodayCommands {
            override fun refresh() { calls += "refresh" }
            override fun measureSpO2() { calls += "spo2" }
            override fun stopMeasure() { calls += "stop" }
        }
        val vm = TodayViewModel(ScriptedSource(TodayStored()), scope = backgroundScope, wallClock = { epoch }, zone = { utc }, commands = commands)
        vm.onAction(io.github.opencircuit.app.today.TodayAction.Refresh)
        vm.onAction(io.github.opencircuit.app.today.TodayAction.MeasureSpO2)
        vm.onAction(io.github.opencircuit.app.today.TodayAction.StopMeasure)
        vm.onAction(io.github.opencircuit.app.today.TodayAction.OpenRing)
        assertEquals(listOf("refresh", "spo2", "stop"), calls)
    }

    /**
     * A live heart-rate frame or a sync's progress changes the hero and the strip, not the stored
     * window: the dashboard (tiles, readiness, sentence) is the same object, so nothing rebuilds it
     * and no tile redraws for it.
     */
    @Test
    fun aSessionUpdateKeepsTheDashboardItBuiltFromTheStore() = runTest {
        val session = MutableStateFlow(TodaySession(link = LinkState.Authenticated, log = listOf(entry(epoch.minusSeconds(600)))))
        val vm = open(ScriptedSource(TodayStored(heartRate = hr("2026-10-21T16:00:00Z", 70.0))), session)
        val before = vm.uiState.value
        assertEquals(8, before.tiles.size)
        session.value = session.value.copy(live = io.github.opencircuit.app.live.LiveMeasureState(mode = io.github.opencircuit.app.live.LiveMode.HEART_RATE))
        runCurrent()
        val after = vm.uiState.value
        assertTrue(after.heartRateLive, "the session update reached the screen")
        assertTrue(after.tiles === before.tiles, "the tiles are the ones already built")
        assertTrue(after.readiness === before.readiness)
    }
}
