package io.github.opencircuit.app.today

import androidx.lifecycle.SavedStateHandle
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Browsing days on Today: the selected day is a date kept in the saved state, so nothing but the
 * user moves it — not a reload, not days added before the first, not a new local day while a past
 * day is open, not data landing on another day (that day's date is marked instead). On Today the
 * screen follows the clock's today. A newly picked day never shows the previous day's values as its own.
 */
class TodayDayBrowserTest {

    private val zone: ZoneId = ZoneId.of("Europe/London")

    /** 2026-10-21 10:00 in London (summer time) at virtual time 0. */
    private val epoch: Instant = Instant.parse("2026-10-21T09:00:00Z")
    private val today: LocalDate = LocalDate.of(2026, 10, 21)

    private fun hr(day: LocalDate, hour: Int, bpm: Double) =
        QuantitySample(kind = MetricKind.HEART_RATE, start = day.atStartOfDay(zone).plusHours(hour.toLong()).toInstant(), value = bpm)

    /** A source whose stored days the test sets; it answers each load for the day and window asked. */
    private inner class DaySource : TodaySource {
        val revisions = MutableStateFlow(0L)
        val loads = mutableListOf<Pair<LocalDate, Int>>()
        var hold: CompletableDeferred<Unit>? = null
        var earliest: Instant? = hr(today.minusDays(5), 9, 60.0).start
        val hrByDay = mutableMapOf<LocalDate, List<QuantitySample>>()

        override fun revisions(): Flow<Long> = revisions

        override suspend fun load(day: LocalDate, zone: ZoneId, windowDays: Int): TodayStored {
            loads += day to windowDays
            hold?.await()
            val first = day.minusDays(windowDays - 1L)
            val hr = hrByDay.filterKeys { it in first..day }.values.flatten().sortedBy { it.start }
            return TodayStored(
                heartRate = hrByDay.values.flatten().maxByOrNull { it.start },
                trends = TodayTrends.of(TodayWindowInputs(hr = hr), zone),
                day = day,
                windowDays = windowDays,
                earliest = earliest,
            )
        }
    }

    private fun TestScope.open(source: TodaySource, saved: SavedStateHandle = SavedStateHandle()): TodayViewModel {
        val vm = TodayViewModel(
            source = source,
            scope = backgroundScope,
            wallClock = { epoch.plusMillis(testScheduler.currentTime) },
            zone = { zone },
            saved = saved,
        )
        runCurrent()
        return vm
    }

    @Test
    fun todayIsShownFirstAndEveryStoredDayCanBePicked() = runTest {
        val source = DaySource()
        val vm = open(source)
        val browser = vm.uiState.value.browser
        assertTrue(browser.isToday)
        assertEquals(today, browser.selected)
        assertEquals(today.minusDays(5), browser.days?.first)
        assertEquals(today, browser.days?.last)
        assertEquals("Today", browser.title)
        assertEquals(today, vm.uiState.value.day)

        vm.onAction(TodayAction.SelectDay(today.minusDays(2)))
        runCurrent()
        val past = vm.uiState.value
        assertFalse(past.browser.isToday)
        assertEquals(today.minusDays(2), past.browser.selected)
        assertEquals("Monday", past.browser.title)
        assertEquals("19 October · 2 days ago", past.browser.subtitle)
        assertEquals(today.minusDays(2), past.day)
        assertEquals(today.minusDays(2) to TodayTiles.WINDOW_DAYS, source.loads.last())

        vm.onAction(TodayAction.ShowToday)
        runCurrent()
        assertTrue(vm.uiState.value.browser.isToday)
        assertEquals(today, source.loads.last().first)
    }

    @Test
    fun thePickedDaySurvivesRecreationThroughTheSavedState() = runTest {
        val saved = SavedStateHandle()
        val first = open(DaySource(), saved)
        first.onAction(TodayAction.SelectDay(today.minusDays(3)))
        runCurrent()

        val source = DaySource()
        val again = open(source, saved)
        assertEquals(today.minusDays(3), again.uiState.value.browser.selected)
        assertEquals(today.minusDays(3), source.loads.single().first, "the recreated screen loads the picked day, not today")
    }

    @Test
    fun aReloadAndDaysAddedBeforeTheFirstKeepThePickedDay() = runTest {
        val source = DaySource()
        val vm = open(source)
        val picked = today.minusDays(2)
        vm.onAction(TodayAction.SelectDay(picked))
        runCurrent()

        // A drain stores nine older days: they come in front of the first.
        source.earliest = hr(today.minusDays(14), 9, 58.0).start
        source.revisions.value = 1
        runCurrent()
        val state = vm.uiState.value
        assertEquals(picked, state.browser.selected)
        assertEquals(picked, state.day)
        assertEquals(today.minusDays(14), state.browser.days?.first)
        assertEquals(12, state.browser.days?.indexOf(picked), "its page moved, the day did not")
        assertEquals(picked, source.loads.last().first)
    }

    @Test
    fun aNewLocalDayLeavesAPastDayWhereItIsButTodayFollowsTheClock() = runTest {
        val onPast = DaySource()
        val past = open(onPast)
        past.onAction(TodayAction.SelectDay(today.minusDays(1)))
        runCurrent()

        val onToday = DaySource()
        val current = open(onToday)

        // Past local midnight in London: 23:00 UTC.
        advanceTimeBy(14 * 3_600_000L + 60_000L)
        runCurrent()
        assertEquals(today.minusDays(1), past.uiState.value.browser.selected, "the open past day stays put")
        assertEquals(today.plusDays(1), past.uiState.value.browser.days?.last, "the new day is there to go to")
        assertFalse(past.uiState.value.browser.isToday)

        assertEquals(today.plusDays(1), current.uiState.value.browser.selected)
        assertTrue(current.uiState.value.browser.isToday)
        assertEquals(today.plusDays(1), onToday.loads.last().first, "Today reloads for the new day")
    }

    @Test
    fun aDayStillLoadingNeverShowsTheDayBeforesValuesAsItsOwn() = runTest {
        val source = DaySource()
        source.hrByDay[today] = listOf(hr(today, 8, 71.0))
        val vm = open(source)
        assertEquals(today, vm.uiState.value.day)
        assertEquals("71", vm.uiState.value.tiles.single { it.metric == TodayTile.Metric.HEART_RATE }.value)

        source.hold = CompletableDeferred()
        vm.onAction(TodayAction.SelectDay(today.minusDays(4)))
        runCurrent()
        val loading = vm.uiState.value
        assertEquals(today.minusDays(4), loading.browser.selected, "the strip moves at once")
        assertEquals(today, loading.day, "the values on screen are still said to be today's")

        source.hold!!.complete(Unit)
        runCurrent()
        assertEquals(today.minusDays(4), vm.uiState.value.day)
        assertNull(vm.uiState.value.tiles.single { it.metric == TodayTile.Metric.HEART_RATE }.value, "nothing stored that day")
    }

    @Test
    fun noStateEverCallsOneDaysValuesAnotherDays() = runTest {
        val source = DaySource()
        source.hrByDay[today] = listOf(hr(today, 8, 71.0))
        source.hrByDay[today.minusDays(3)] = listOf(hr(today.minusDays(3), 8, 55.0))
        val vm = open(source)
        val seen = mutableListOf<TodayUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { seen += it } }
        vm.onAction(TodayAction.SelectDay(today.minusDays(3)))
        runCurrent()
        vm.onAction(TodayAction.ShowToday)
        runCurrent()
        fun hrOf(s: TodayUiState) = s.tiles.singleOrNull { it.metric == TodayTile.Metric.HEART_RATE }?.value
        val wrong = seen.filter { (it.day == today.minusDays(3) && hrOf(it) == "71") || (it.day == today && hrOf(it) == "55") }
        assertEquals(emptyList(), wrong.map { it.day to hrOf(it) }, "a state whose day and values disagree")
        assertTrue(seen.any { it.day == today.minusDays(3) && hrOf(it) == "55" })
    }

    @Test
    fun aTileDetailOpensInTheSavedStateAndThirtyDaysWaitsForAThirtyDayLoad() = runTest {
        val source = DaySource()
        source.hrByDay[today.minusDays(20)] = listOf(hr(today.minusDays(20), 9, 58.0))
        source.hrByDay[today] = listOf(hr(today, 9, 64.0))
        val saved = SavedStateHandle()
        val vm = open(source, saved)
        assertNull(vm.uiState.value.detail)

        vm.onAction(TodayAction.OpenDetail(TodayTile.Metric.HEART_RATE))
        runCurrent()
        val fourteen = vm.uiState.value.detail!!
        assertEquals(14, fourteen.days)
        assertEquals("1 of 14 days", fourteen.count)
        assertFalse(fourteen.loading)

        source.hold = CompletableDeferred()
        vm.onAction(TodayAction.DetailRange(30))
        runCurrent()
        assertEquals(today to 30, source.loads.last(), "one wider load, not a second reader")
        assertTrue(vm.uiState.value.detail!!.loading, "the 14-day load is not shown as thirty days")
        source.hold!!.complete(Unit)
        source.hold = null
        runCurrent()
        val thirty = vm.uiState.value.detail!!
        assertFalse(thirty.loading)
        assertEquals("2 of 30 days", thirty.count)

        // Recreated: the open detail and its range come back.
        val again = open(DaySource().also { it.hrByDay.putAll(source.hrByDay) }, saved)
        assertEquals(TodayTile.Metric.HEART_RATE to 30, again.uiState.value.detail?.let { it.metric to it.days })

        vm.onAction(TodayAction.CloseDetail)
        runCurrent()
        assertNull(vm.uiState.value.detail)
        vm.onAction(TodayAction.DetailRange(7))
        vm.onAction(TodayAction.OpenDetail(TodayTile.Metric.SPO2))
        runCurrent()
        assertEquals(30, vm.uiState.value.detail?.days, "only 14 or 30 are taken")
    }

    @Test
    fun dataLandingOnAnotherDayMarksThatDayOnlyAndVisitingItClearsTheMark() = runTest {
        val source = DaySource()
        source.hrByDay[today.minusDays(3)] = listOf(hr(today.minusDays(3), 9, 60.0))
        val vm = open(source)
        val picked = today.minusDays(2)
        vm.onAction(TodayAction.SelectDay(picked))
        runCurrent()
        assertEquals(emptySet(), vm.uiState.value.browser.marked)

        // Readings land on the day before the picked one, and on the picked day itself.
        source.hrByDay[today.minusDays(3)] = source.hrByDay.getValue(today.minusDays(3)) + hr(today.minusDays(3), 10, 64.0)
        source.hrByDay[picked] = listOf(hr(picked, 9, 66.0))
        source.revisions.value = 1
        runCurrent()
        val state = vm.uiState.value
        assertEquals(picked, state.browser.selected, "the user stays where they are")
        assertEquals(setOf(today.minusDays(3)), state.browser.marked, "the picked day folds in place, unmarked")
        assertEquals("66", state.tiles.single { it.metric == TodayTile.Metric.HEART_RATE }.value)

        vm.onAction(TodayAction.SelectDay(today.minusDays(3)))
        runCurrent()
        assertEquals(emptySet(), vm.uiState.value.browser.marked)
    }
}
