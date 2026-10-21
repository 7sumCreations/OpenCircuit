package io.github.opencircuit.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.opencircuit.app.today.BrowsableDays
import io.github.opencircuit.app.today.TODAY_PAST_DAY_TAG
import io.github.opencircuit.app.today.TodayAction
import io.github.opencircuit.app.today.TodayRepository
import io.github.opencircuit.app.today.TodayScreen
import io.github.opencircuit.app.today.TodayViewModel
import io.github.opencircuit.app.today.todayDayTag
import io.github.opencircuit.app.ui.OpenCircuitTheme
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.store.LocalStore
import io.github.opencircuit.store.StoreDatabase
import io.github.opencircuit.store.StoreFactory
import io.github.opencircuit.store.openInMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Days added in front of the first stored day while the user browses never move them off their
 * day. (a) What the pager itself does: a pager keyed by epoch day, settled on a page, then given
 * nine days in front — measured, whichever way it goes. (b) The app: Today over the real view model,
 * repository and an in-memory store; the user taps a past date, then older readings are written to
 * the store (a drain's first pages do this) — the picked day, its page and its content stay put,
 * and the user's pick is never replaced by the page index the new days moved.
 */
@RunWith(AndroidJUnit4::class)
class DayBrowserPrependTest {

    @get:Rule
    val compose = createComposeRule()

    private val zone: ZoneId = ZoneId.of("UTC")
    private val today: LocalDate = LocalDate.now(zone)
    private var db: StoreDatabase? = null
    private var scope: CoroutineScope? = null

    @After
    fun close() {
        scope?.cancel()
        db?.close()
    }

    private fun hr(day: LocalDate, hour: Int, bpm: Double) =
        QuantitySample(kind = MetricKind.HEART_RATE, start = day.atStartOfDay(zone).plusHours(hour.toLong()).toInstant(), value = bpm)

    @Test
    fun aPagerKeyedByEpochDayFollowsItsPageWhenDaysArePrepended() {
        var days by mutableStateOf(BrowsableDays(today.minusDays(5), today))
        val picked = today.minusDays(2)
        lateinit var pager: PagerState
        compose.setContent {
            pager = rememberPagerState(initialPage = days.indexOf(picked)) { days.size }
            HorizontalPager(state = pager, key = { days.dateAt(it).toEpochDay() }, modifier = Modifier.fillMaxSize()) { i ->
                Box(Modifier.fillMaxSize()) { Text("page ${days.dateAt(i)}") }
            }
        }
        compose.onNodeWithText("page $picked").assertIsDisplayed()
        assertEquals(3, pager.currentPage)

        days = BrowsableDays(today.minusDays(14), today)
        compose.waitForIdle()

        // The measured behaviour the hazard row records: the page follows its key.
        assertEquals("the pager kept the picked day's page by its key", days.indexOf(picked), pager.currentPage)
        compose.onNodeWithText("page $picked").assertIsDisplayed()
    }

    @Test
    fun olderReadingsWrittenWhileBrowsingKeepThePickedDay() {
        val store = runBlocking { StoreFactory.openInMemory().also { db = it } }
        val local = LocalStore(store)
        val now = Instant.now()
        // Past days only, so no reading is dated after the moment the test runs.
        runBlocking { local.insertLiveSamples((1..5).map { hr(today.minusDays(it.toLong()), 9, 60.0 + it) }, now) }
        val vmScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        val vm = TodayViewModel(
            source = TodayRepository({ store }),
            scope = vmScope,
            zone = { zone },
            clockTicks = flowOf(Unit),
            saved = SavedStateHandle(),
        )
        val actions = mutableListOf<TodayAction>()
        compose.setContent {
            OpenCircuitTheme {
                val state by vm.uiState.collectAsStateWithLifecycle()
                TodayScreen(state, onAction = { actions += it; vm.onAction(it) })
            }
        }
        val picked = today.minusDays(3)
        compose.waitUntil(10_000) { vm.uiState.value.browser.days?.first == today.minusDays(5) }
        compose.onNodeWithTag(todayDayTag(picked)).performClick()
        compose.waitUntil(10_000) { vm.uiState.value.day == picked }
        compose.onNodeWithTag(todayDayTag(picked)).assertIsSelected()
        compose.onAllNodesWithTag(TODAY_PAST_DAY_TAG).filterToOne(descriptionStartsWith("Heart rate on ${LONG(picked)}: 63 to 63")).assertIsDisplayed()

        // A drain's older pages: nine days in front of the first.
        runBlocking { local.insertLiveSamples((6..14).map { hr(today.minusDays(it.toLong()), 9, 50.0) }, now) }
        compose.waitUntil(10_000) { vm.uiState.value.browser.days?.first == today.minusDays(14) }
        compose.waitForIdle()

        val state = vm.uiState.value
        assertEquals(picked, state.browser.selected)
        assertEquals(picked, state.day)
        compose.onNodeWithTag(todayDayTag(picked)).assertIsSelected()
        // The page on screen is the picked day's (the pager also composes a neighbour off screen,
        // which holds placeholders: its day's load is not the one in the state).
        compose.onAllNodesWithTag(TODAY_PAST_DAY_TAG).filterToOne(descriptionStartsWith("Heart rate on ${LONG(picked)}: 63 to 63")).assertIsDisplayed()
        assertEquals("only the user's own tap picked a day", listOf<TodayAction>(TodayAction.SelectDay(picked)), actions)
    }

    private fun descriptionStartsWith(prefix: String) =
        SemanticsMatcher("content description starts with \"$prefix\"") { node ->
            node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.any { it.startsWith(prefix) }
        }

    private fun LONG(day: LocalDate): String =
        java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", java.util.Locale.ENGLISH).format(day)
}
