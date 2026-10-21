package io.github.opencircuit.app

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.opencircuit.app.connect.ConnectFlowPresenter
import io.github.opencircuit.app.connect.ConnectFlowState
import io.github.opencircuit.app.ring.DeviceStatusState
import io.github.opencircuit.app.ring.RingScreen
import io.github.opencircuit.app.ring.RingUiState
import io.github.opencircuit.app.ring.connectionCardUi
import io.github.opencircuit.app.settings.SettingsScreen
import io.github.opencircuit.app.settings.SettingsUiState
import io.github.opencircuit.app.today.DashboardSection
import io.github.opencircuit.app.today.TODAY_CHIP_TAG
import io.github.opencircuit.app.today.TODAY_DAY_STRIP_TAG
import io.github.opencircuit.app.today.TODAY_EDIT_ORDER_TAG
import io.github.opencircuit.app.today.TODAY_GOALS_TAG
import io.github.opencircuit.app.today.TODAY_LIST_TAG
import io.github.opencircuit.app.today.TODAY_NEXT_DAY_TAG
import io.github.opencircuit.app.today.TODAY_PICK_DAY_TAG
import io.github.opencircuit.app.today.TODAY_PREVIOUS_DAY_TAG
import io.github.opencircuit.app.today.TileDetailPresenter
import io.github.opencircuit.app.today.TodayAlertLevel
import io.github.opencircuit.app.today.TodayInput
import io.github.opencircuit.app.today.TodayNight
import io.github.opencircuit.app.today.TodayOverdueUi
import io.github.opencircuit.app.today.TodayPresenter
import io.github.opencircuit.app.today.TodayScreen
import io.github.opencircuit.app.today.TodaySession
import io.github.opencircuit.app.today.TodayStored
import io.github.opencircuit.app.today.TodayTile
import io.github.opencircuit.app.today.TodayTrends
import io.github.opencircuit.app.today.TodayUiState
import io.github.opencircuit.app.today.TodayWindowInputs
import io.github.opencircuit.app.today.todayDayTag
import io.github.opencircuit.app.today.todayOrderRowTag
import io.github.opencircuit.app.today.todayTileTag
import io.github.opencircuit.app.ui.OpenCircuitTheme
import io.github.opencircuit.ble.AdapterState
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.store.SyncLogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/**
 * What a screen reader and a finger need from every screen: each screen's title is a heading
 * (Today, a past day, a tile's trend, Ring, Settings), and every control Today draws on today, on a
 * loaded past day, in Edit order and with no ring paired can be touched over at least 48 × 48 dp.
 * Each sweep scrolls down its page and also names the controls it must have seen whole — the hero's
 * Measure, Open Ring, the date strip and its arrows, the "Today" chip, Pick a day, Sync now, the
 * notice's buttons, Edit order, its move buttons and Reset, every tile — so none can slip past
 * unmeasured.
 */
@RunWith(AndroidJUnit4::class)
class ScreenAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val zone = ZoneOffset.UTC
    private val today = LocalDate.parse("2026-09-30")
    private val now = Instant.parse("2026-09-30T10:00:00Z")
    private fun day(o: Int) = today.plusDays(o.toLong()).atStartOfDay(zone).toInstant()

    private fun trends(): TodayTrends {
        val nights = (-5..0).map { o ->
            val wake = day(o).plusSeconds(6 * 3_600L)
            TodayNight(day(o), 420, 75, 38, 33.5, wake.minusSeconds(8 * 3_600L), wake)
        }
        val hrv = (-5..0).map { o -> QuantitySample(kind = MetricKind.HRV_SDNN, start = day(o).plusSeconds(7_200), value = 50.0 + o) }
        val hr = (-5..0).flatMap { o -> (8..20).map { h -> QuantitySample(kind = MetricKind.HEART_RATE, start = day(o).plusSeconds(h * 3_600L), value = 60.0 + h) } }
            .filter { it.start <= now }
        return TodayTrends.of(TodayWindowInputs(nights = nights, hrv = hrv, hr = hr), zone)
    }

    /**
     * Today with everything it can draw at once, its values loaded for [selected]: the date strip,
     * the hero (or a past day's card), readiness, tiles, calories and goals — plus, added by hand
     * so their buttons are measured too, the overdue chip and the one-time notice.
     */
    private fun full(selected: LocalDate = today): TodayUiState {
        val log = listOf(SyncLogEntry(startedAt = now.minusSeconds(660), finishedAt = now.minusSeconds(600), outcome = "COMPLETE"))
        val stored = TodayStored(trends = trends(), day = selected, earliest = day(-20))
        val base = TodayPresenter.present(
            TodayInput(stored, TodaySession(link = LinkState.Authenticated, log = log), now, zone, picked = selected, marked = setOf(today.minusDays(1))),
        )
        check(base.day == selected && base.browser.selected == selected) { "the page must be loaded for $selected" }
        return base.copy(
            overdue = TodayOverdueUi("Your ring keeps about 7 days of data. Sync soon so nothing is lost.", TodayAlertLevel.AMBER, canSync = true),
            healthNotice = true,
        )
    }

    /** The screen's title, exactly (a card heading that merely contains the word is not it). */
    private fun heading(title: String) = compose.onNode(isHeading() and hasText(title, substring = false)).assertIsDisplayed()

    /** The click targets checked so far, by what they say, and those under 48 dp. */
    private val checked = mutableSetOf<String>()
    private val small = mutableListOf<String>()

    /**
     * Every node with a click action on screen now: its touch area — what Android's accessibility
     * services are told, a 40 dp button grown to the minimum touch target — must be at least 48 dp
     * each way. A node cut by the edge of a scrolling list (Today's list, the date strip), or
     * scrolled wholly out of sight, is left for a scroll that shows it whole: its touch bounds are
     * clipped there, which says nothing about its size.
     */
    private fun checkTouchTargets() {
        val min = with(compose.density) { 48.dp.toPx() } - 0.5f
        val viewports = listOf(TODAY_LIST_TAG, TODAY_DAY_STRIP_TAG)
            .flatMap { tag -> compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes() }
            .map { it.boundsInRoot }
        for (node in compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()) {
            val laidOut = Rect(node.positionInRoot, node.size.toSize())
            // Wholly scrolled out of sight (bounds empty), or cut by a list's edge.
            val cutByScroll = node.boundsInRoot.isEmpty || viewports.any { it.overlaps(laidOut) && !it.encloses(laidOut) }
            if (cutByScroll) continue
            checked += name(node)
            val touch = node.touchBoundsInRoot
            if (touch.width < min || touch.height < min) small += "${name(node)}: touch area $touch"
        }
    }

    /** [inner] lies wholly inside this rect (half a pixel of rounding allowed). */
    private fun Rect.encloses(inner: Rect): Boolean =
        inner.left >= left - 0.5f && inner.top >= top - 0.5f && inner.right <= right + 0.5f && inner.bottom <= bottom + 0.5f

    private fun name(node: SemanticsNode): String =
        node.config.getOrNull(SemanticsProperties.TestTag)
            ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString()
            ?: node.config.toString()

    /** Scrolls Today's list to each of [stops] in turn, checking at the top and at every stop. */
    private fun checkTodayDownTo(stops: List<SemanticsMatcher>) {
        checkTouchTargets()
        for (stop in stops) {
            compose.onNodeWithTag(TODAY_LIST_TAG).performScrollToNode(stop)
            checkTouchTargets()
        }
        assertTrue(small.joinToString("\n"), small.isEmpty())
    }

    private val everyTileThenGoals = TodayTile.Metric.entries.map { hasTestTag(todayTileTag(it)) } + hasTestTag(TODAY_GOALS_TAG)

    @Test
    fun todaysTitleIsAHeading() {
        compose.setContent { OpenCircuitTheme { TodayScreen(full(), onAction = {}) } }
        heading("Today")
    }

    @Test
    fun aPastDaysTitleIsAHeading() {
        val state = full(today.minusDays(2))
        compose.setContent { OpenCircuitTheme { TodayScreen(state, onAction = {}) } }
        heading(state.browser.title)
    }

    @Test
    fun aTrendsTitleIsAHeading() {
        val detail = TileDetailPresenter.present(trends(), TodayTile.Metric.HRV, 14, today, now, zone)
        compose.setContent { OpenCircuitTheme { TodayScreen(full().copy(detail = detail), onAction = {}) } }
        heading(detail.title)
    }

    @Test
    fun theRingTabsTitleIsAHeading() {
        val state = RingUiState(
            title = "Ring",
            card = connectionCardUi(
                LinkState.Idle, null, DeviceStatusState(), measuring = false, keepaliveProblem = null,
                flowCard = ConnectFlowPresenter.availability(ConnectFlowState(), AdapterState.ON),
            ),
        )
        compose.setContent { OpenCircuitTheme { RingScreen(state = state, onAction = {}, pulse = false) } }
        heading("Ring")
    }

    @Test
    fun theSettingsTabsTitleIsAHeading() {
        compose.setContent { OpenCircuitTheme { SettingsScreen(SettingsUiState(), onAction = {}) } }
        heading("Settings")
    }

    @Test
    fun everyControlOnTodayIsAtLeast48dpFromTheTopToTheGoals() {
        compose.setContent { OpenCircuitTheme { TodayScreen(full(), onAction = {}) } }
        checkTodayDownTo(everyTileThenGoals)
        // The hero's Measure, the overdue chip's and the notice's buttons, the strip's arrows, Pick a day, Edit order and all eight tiles were seen whole.
        val expected = listOf("Measure", "Sync now", "Dismiss", "Open Settings", TODAY_PREVIOUS_DAY_TAG, TODAY_NEXT_DAY_TAG, TODAY_PICK_DAY_TAG, TODAY_EDIT_ORDER_TAG) +
            TodayTile.Metric.entries.map(::todayTileTag)
        assertEquals(emptyList<String>(), expected - checked)
    }

    /** The day strip's arrows say only "Previous day" / "Next day": the arrow characters are not read out too. */
    @Test
    fun theDayArrowsSayOnlyTheirLabels() {
        compose.setContent { OpenCircuitTheme { TodayScreen(full(today.minusDays(2)), onAction = {}) } }
        for (tag in listOf(TODAY_PREVIOUS_DAY_TAG, TODAY_NEXT_DAY_TAG)) {
            compose.onNodeWithTag(tag).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
        }
    }

    @Test
    fun everyControlOnALoadedPastDayIsAtLeast48dp() {
        val past = today.minusDays(2)
        compose.setContent { OpenCircuitTheme { TodayScreen(full(past), onAction = {}) } }
        checkTodayDownTo(everyTileThenGoals)
        val expected = listOf(TODAY_CHIP_TAG, todayDayTag(past), TODAY_EDIT_ORDER_TAG) + TodayTile.Metric.entries.map(::todayTileTag)
        assertEquals(emptyList<String>(), expected - checked)
    }

    @Test
    fun everyControlInEditOrderIsAtLeast48dpDownToReset() {
        compose.setContent { OpenCircuitTheme { TodayScreen(full().copy(editingOrder = true), onAction = {}) } }
        // Down every tile's row and the last section's, to "Reset to default order".
        checkTodayDownTo(
            TodayTile.Metric.entries.map { hasTestTag(todayOrderRowTag(it.name.lowercase(Locale.ROOT))) } +
                hasTestTag(todayOrderRowTag(DashboardSection.GOALS.rawValue)) + hasText("Reset to default order"),
        )
        val expected = listOf("Move Readiness down", "Move Heart rate down", "Move Steps up", "Move Goals up", "Reset to default order", TODAY_EDIT_ORDER_TAG)
        assertEquals(emptyList<String>(), expected - checked)
    }

    @Test
    fun withNoRingPairedOpenRingIsAtLeast48dp() {
        compose.setContent { OpenCircuitTheme { TodayScreen(TodayUiState(loading = false), onAction = {}) } }
        checkTodayDownTo(emptyList())
        assertEquals(emptyList<String>(), listOf("Open Ring") - checked)
    }
}
