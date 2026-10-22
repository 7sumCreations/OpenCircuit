package io.github.opencircuit.app.today

import io.github.opencircuit.app.InMemoryKeyValues
import io.github.opencircuit.app.data.PrefsAppPrefs
import io.github.opencircuit.app.today.TodayTile.Metric
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Today's order of sections and tiles: read from the app's preferences before the first frame (the
 * very first state already has it — no default flashing first), changed one step at a time in Edit
 * order, saved at once, and the same for a new view model over the same preferences (a restart).
 */
class TodayOrderTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val epoch: Instant = Instant.parse("2026-10-21T09:00:00Z")
    private val values = InMemoryKeyValues()

    /** Loads only when [open] completes, so the first frame is seen before any load. */
    private inner class Source : TodaySource {
        val open = CompletableDeferred<Unit>()
        override fun revisions(): Flow<Long> = MutableStateFlow(0L)
        override suspend fun load(day: LocalDate, zone: ZoneId, windowDays: Int): TodayStored {
            open.await()
            return TodayStored(day = day, windowDays = windowDays)
        }
    }

    private fun TestScope.vm(source: TodaySource = Source().apply { open.complete(Unit) }) = TodayViewModel(
        source = source,
        scope = backgroundScope,
        wallClock = { epoch.plusMillis(testScheduler.currentTime) },
        zone = { zone },
        saved = androidx.lifecycle.SavedStateHandle(),
        prefs = PrefsAppPrefs(values),
    )

    @Test
    fun theFirstStateAlreadyCarriesTheSavedOrder() = runTest {
        values.putString("today.sectionOrder.v1", "goals,metrics")
        values.putString("today.tileOrder.v1", "steps,hrv")

        val state = vm(Source()).uiState.value

        assertTrue(state.loading, "nothing loaded yet")
        assertEquals(listOf(DashboardSection.GOALS, DashboardSection.METRICS, DashboardSection.READINESS, DashboardSection.CALORIES), state.sections)
        assertEquals(listOf(Metric.STEPS, Metric.HRV, Metric.HEART_RATE, Metric.RESTING_HR, Metric.SPO2, Metric.RESPIRATORY_RATE, Metric.SKIN_TEMP, Metric.SLEEP), state.tileOrder)
    }

    @Test
    fun nothingSavedIsTheDefaultOrder() = runTest {
        val state = vm().uiState.value
        assertEquals(DashboardSection.entries.toList(), state.sections)
        assertEquals(Metric.entries.toList(), state.tileOrder)
    }

    @Test
    fun movesAreOneStepSavedAtOnceAndTheTilesFollow() = runTest {
        val vm = vm()
        runCurrent()

        vm.onAction(TodayAction.MoveTile(Metric.HRV, -1))
        vm.onAction(TodayAction.MoveSection(DashboardSection.CALORIES, -1))
        runCurrent()

        val state = vm.uiState.value
        assertEquals(listOf(Metric.HRV, Metric.HEART_RATE), state.tiles.take(2).map { it.metric }, "the tiles on screen follow the order")
        assertEquals(listOf(DashboardSection.READINESS, DashboardSection.CALORIES, DashboardSection.METRICS, DashboardSection.GOALS), state.sections)
        assertEquals("hrv,heart_rate,resting_hr,spo2,respiratory_rate,skin_temp,sleep,steps", values.string("today.tileOrder.v1"))
        assertEquals("readiness,calories,metrics,goals", values.string("today.sectionOrder.v1"))
    }

    @Test
    fun aMovePastEitherEndChangesNothing() = runTest {
        val vm = vm()
        runCurrent()

        vm.onAction(TodayAction.MoveSection(DashboardSection.READINESS, -1))
        vm.onAction(TodayAction.MoveSection(DashboardSection.GOALS, +1))
        vm.onAction(TodayAction.MoveTile(Metric.STEPS, +1))
        runCurrent()

        assertEquals(DashboardSection.entries.toList(), vm.uiState.value.sections)
        assertEquals(Metric.entries.toList(), vm.uiState.value.tileOrder)
    }

    /** A move past an end writes nothing; a move writes only the order it changed (one preferences write per tap). */
    @Test
    fun aMoveWritesOnlyItsOwnOrderAndANoOpWritesNothing() = runTest {
        val vm = vm()
        runCurrent()

        vm.onAction(TodayAction.MoveSection(DashboardSection.READINESS, -1))
        runCurrent()
        assertEquals(null, values.string("today.sectionOrder.v1"), "nothing changed, nothing written")
        assertEquals(null, values.string("today.tileOrder.v1"))

        vm.onAction(TodayAction.MoveTile(Metric.HRV, -1))
        runCurrent()
        assertEquals(null, values.string("today.sectionOrder.v1"), "a tile move leaves the section order unwritten")
        assertEquals("hrv,heart_rate,resting_hr,spo2,respiratory_rate,skin_temp,sleep,steps", values.string("today.tileOrder.v1"))
    }

    /** Edit order names every tile as the tile does, whether or not a load has built the tiles. */
    @Test
    fun everyTileHasItsDisplayNameNeverItsEnumName() {
        val built = TodayTiles.build(emptyList(), emptyList(), io.github.opencircuit.ringkit.TemperatureUnit.CELSIUS, now = epoch, zone = zone)
        for (metric in Metric.entries) {
            assertEquals(built.single { it.metric == metric }.title, TodayTiles.title(metric), metric.name)
            assertFalse('_' in TodayTiles.title(metric), "no enum identifier: ${TodayTiles.title(metric)}")
        }
    }

    @Test
    fun aNewViewModelOverTheSamePreferencesHasTheOrder() = runTest {
        val first = vm()
        runCurrent()
        first.onAction(TodayAction.MoveSection(DashboardSection.GOALS, -1))
        first.onAction(TodayAction.MoveTile(Metric.SLEEP, -1))
        runCurrent()

        val again = vm(Source()).uiState.value

        assertEquals(listOf(DashboardSection.READINESS, DashboardSection.METRICS, DashboardSection.GOALS, DashboardSection.CALORIES), again.sections)
        assertEquals(listOf(Metric.SLEEP, Metric.SKIN_TEMP, Metric.STEPS), again.tileOrder.takeLast(3))
        assertEquals(first.uiState.value.tileOrder, again.tileOrder)
    }

    @Test
    fun editOrderTurnsOnAndOffAndResetGoesBackToTheDefault() = runTest {
        val vm = vm()
        runCurrent()
        vm.onAction(TodayAction.EditOrder(true))
        vm.onAction(TodayAction.MoveSection(DashboardSection.GOALS, -1))
        runCurrent()
        assertTrue(vm.uiState.value.editingOrder)

        vm.onAction(TodayAction.ResetOrder)
        vm.onAction(TodayAction.EditOrder(false))
        runCurrent()

        assertFalse(vm.uiState.value.editingOrder)
        assertEquals(DashboardSection.entries.toList(), vm.uiState.value.sections)
        assertEquals("readiness,metrics,calories,goals", values.string("today.sectionOrder.v1"))
    }
}
