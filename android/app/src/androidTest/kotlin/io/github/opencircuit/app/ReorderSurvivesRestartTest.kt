package io.github.opencircuit.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencircuit.app.data.PrefsAppPrefs
import io.github.opencircuit.app.data.SharedPreferencesKeyValues
import io.github.opencircuit.app.today.DashboardSection
import io.github.opencircuit.app.today.TODAY_EDIT_ORDER_TAG
import io.github.opencircuit.app.today.TODAY_LIST_TAG
import io.github.opencircuit.app.today.TodayRepository
import io.github.opencircuit.app.today.TodayScreen
import io.github.opencircuit.app.today.TodayTile
import io.github.opencircuit.app.today.TodayViewModel
import io.github.opencircuit.app.today.todayOrderRowTag
import io.github.opencircuit.app.today.todayTileTag
import io.github.opencircuit.app.ui.OpenCircuitTheme
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

/**
 * The order the user sets with Edit order survives a restart: Calories is moved above the tiles
 * and HRV to the first tile with the buttons, then everything is let go; a new view model over a
 * new preferences object on the same preferences file — nothing carried in memory — draws the
 * same order on its first frame. The test's own preferences file is cleared before and after.
 */
@RunWith(AndroidJUnit4::class)
class ReorderSurvivesRestartTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private var db: StoreDatabase? = null
    private val scopes = mutableListOf<CoroutineScope>()

    private fun prefs() = PrefsAppPrefs(SharedPreferencesKeyValues(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)))

    @Before
    fun clean() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun cleanUp() {
        scopes.forEach { it.cancel() }
        db?.close()
        clean()
    }

    private fun viewModel(store: StoreDatabase): TodayViewModel {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scopes += it }
        return TodayViewModel(
            source = TodayRepository({ store }),
            scope = scope,
            zone = { ZoneId.of("UTC") },
            clockTicks = flowOf(Unit),
            saved = SavedStateHandle(),
            prefs = prefs(),
        )
    }

    /** Edit order / Done: scrolled into view first (the list may have moved it off screen). */
    private fun tapOrderBar() {
        compose.onNodeWithTag(TODAY_LIST_TAG).performScrollToNode(hasTestTag(TODAY_EDIT_ORDER_TAG))
        compose.onNodeWithTag(TODAY_EDIT_ORDER_TAG).performClick()
    }

    private fun top(tag: String): Float = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().top.value

    @Test
    fun anOrderSetWithTheButtonsIsTheOrderAfterARestart() {
        val store = runBlocking { StoreFactory.openInMemory().also { db = it } }
        var current by mutableStateOf(viewModel(store))
        compose.setContent {
            OpenCircuitTheme {
                val vm = current
                val state by vm.uiState.collectAsStateWithLifecycle()
                TodayScreen(state, onAction = vm::onAction)
            }
        }
        compose.waitUntil(10_000) { !current.uiState.value.loading }

        tapOrderBar()
        compose.onNodeWithTag(TODAY_LIST_TAG).performScrollToNode(hasTestTag(todayOrderRowTag("calories")))
        compose.onNodeWithContentDescription("Move Calories up").performClick()
        compose.onNodeWithTag(TODAY_LIST_TAG).performScrollToNode(hasTestTag(todayOrderRowTag("hrv")))
        compose.onNodeWithContentDescription("Move HRV up").performClick()
        tapOrderBar()
        compose.waitForIdle()

        // The restart: a new view model over a new preferences object on the same file.
        current = viewModel(store)
        compose.waitForIdle()
        val first = current.uiState.value
        assertEquals(listOf(DashboardSection.READINESS, DashboardSection.CALORIES, DashboardSection.METRICS, DashboardSection.GOALS), first.sections)
        assertEquals(listOf(TodayTile.Metric.HRV, TodayTile.Metric.HEART_RATE), first.tileOrder.take(2))

        compose.waitUntil(10_000) { !current.uiState.value.loading }
        compose.onNodeWithTag(TODAY_LIST_TAG).performScrollToNode(hasTestTag(todayTileTag(TodayTile.Metric.HRV)))
        val hrv = compose.onNodeWithTag(todayTileTag(TodayTile.Metric.HRV)).getUnclippedBoundsInRoot()
        val heartRate = compose.onNodeWithTag(todayTileTag(TodayTile.Metric.HEART_RATE)).getUnclippedBoundsInRoot()
        assertTrue("HRV is drawn before heart rate", hrv.top < heartRate.top || (hrv.top == heartRate.top && hrv.left < heartRate.left))
        // Edit order lists the saved section order: Calories above the tiles.
        tapOrderBar()
        compose.onNodeWithTag(TODAY_LIST_TAG).performScrollToNode(hasTestTag(todayOrderRowTag("metrics")))
        compose.onNodeWithTag(TODAY_LIST_TAG).performScrollToNode(hasTestTag(todayOrderRowTag("calories")))
        assertTrue("Calories is listed above the tiles", top(todayOrderRowTag("calories")) < top(todayOrderRowTag("metrics")))
    }

    private companion object {
        const val PREFS = "reorder-restart-test"
    }
}
