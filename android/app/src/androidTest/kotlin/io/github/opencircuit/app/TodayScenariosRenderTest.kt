package io.github.opencircuit.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.opencircuit.app.ring.LinkStatePresenter
import io.github.opencircuit.app.today.BrowsableDays
import io.github.opencircuit.app.today.DayBrowser
import io.github.opencircuit.app.today.MeasureButtonUi
import io.github.opencircuit.app.today.PastDayHeroUi
import io.github.opencircuit.app.today.ReadingUi
import io.github.opencircuit.app.today.SyncStripUi
import io.github.opencircuit.app.today.TODAY_CHIP_TAG
import io.github.opencircuit.app.today.TODAY_HERO_TAG
import io.github.opencircuit.app.today.TODAY_OVERDUE_TAG
import io.github.opencircuit.app.today.TODAY_PAST_DAY_TAG
import io.github.opencircuit.app.today.TODAY_STRIP_TAG
import io.github.opencircuit.app.today.TODAY_TOP_BAR_TAG
import io.github.opencircuit.app.today.TodayAction
import io.github.opencircuit.app.today.TodayAlertLevel
import io.github.opencircuit.app.today.TodayInput
import io.github.opencircuit.app.today.TodayOverdueUi
import io.github.opencircuit.app.today.TodayPresenter
import io.github.opencircuit.app.today.TodayScreen
import io.github.opencircuit.app.today.TodaySession
import io.github.opencircuit.app.today.TodayStored
import io.github.opencircuit.app.today.TodayUiState
import io.github.opencircuit.app.today.todayDayTag
import io.github.opencircuit.app.ui.OpenCircuitTheme
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

/**
 * The Today scenarios the design mock-up names that no other render test draws, each from a
 * hand-built state and read back by its text: the live hero, the amber overdue chip, a ring that is
 * not connected, and a past day in the day browser. (Syncing, a fresh install and red overdue are
 * in [TodayScreenRenderTest]; the upgrade notice in [HealthSwitchRenderTest]; a tile's trend in
 * [TileDetailRenderTest]; Edit order in [CaloriesGoalsRenderTest].)
 */
@RunWith(AndroidJUnit4::class)
class TodayScenariosRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val stored = TodayUiState(
        loading = false,
        heartRate = ReadingUi("Heart rate", "62", "bpm", "as of 13:02 · 3 h ago"),
        spo2 = ReadingUi("SpO₂", "97", "%", "as of 07:10 · 9 h ago"),
        lastSynced = "Last synced 2 min ago",
        ring = "Connected",
        spo2Measure = MeasureButtonUi(enabled = true),
    )

    private fun show(state: TodayUiState): MutableList<TodayAction> {
        val actions = mutableListOf<TodayAction>()
        compose.setContent { OpenCircuitTheme { TodayScreen(state, onAction = { actions += it }) } }
        return actions
    }

    @Test
    fun liveTheHeroShowsTheLiveNumberAsOneNodeAndAnnouncesOnlyItsStateChanges() {
        val live = stored.copy(heartRate = stored.heartRate.copy(value = "71", note = "Live now", status = "Live · warming up"), heartRateLive = true)
        show(live)
        compose.onNodeWithText("71").assertIsDisplayed()
        compose.onNodeWithText("Live now").assertIsDisplayed()
        compose.onNodeWithText("Last synced 2 min ago").assertIsDisplayed()
        compose.onNodeWithText("Measure").assertIsEnabled()
        // The label, the number, the unit and "Live now" are one node, read on demand: a number that
        // changes every 2 s is never announced by itself.
        compose.onNode(hasText("Heart rate") and hasText("71") and hasText("Live now"))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        // The live state line changes only when the state does: that is what is announced, politely.
        compose.onNodeWithText("Live · warming up").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    /** The sync strip's line moves with every page and "Last synced" with every minute: neither is announced by itself. */
    @Test
    fun theSyncStripIsReadOnDemandNeverAnnouncedOnEachChange() {
        show(stored.copy(strip = SyncStripUi("Syncing · Sleep channel 120 records so far", null)))
        compose.onNode(hasText("Syncing · Sleep channel 120 records so far") and hasText("Last synced 2 min ago"))
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
    }

    /** At twice the font the past day's card grows with it, as the tiles do, so its bar and scale are not cut off. */
    @Test
    fun atTwiceTheFontThePastDayCardGrowsWithIt() {
        val today = LocalDate.parse("2026-10-21")
        val past = today.minusDays(2)
        val browser = DayBrowser.present(BrowsableDays(today.minusDays(9), today), past, today, emptySet())
        val range = PastDayHeroUi(
            title = "Heart rate on Mon 19 Oct", range = "52–112", detail = "average 72 · resting 55",
            low = 0.1f, average = 0.27f, high = 0.6f, description = "Heart rate on Monday 19 October.",
        )
        val s = stored.copy(browser = browser, day = past, pastDay = range)
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 2f)) { OpenCircuitTheme { TodayScreen(s, onAction = {}) } }
        }
        val card = compose.onNodeWithTag(TODAY_PAST_DAY_TAG).getBoundsInRoot()
        assertEquals("the card grew with the font", 392f, (card.bottom - card.top).value, 1f)
        // The bar grows too: the day's name and its date line both fit inside it.
        val bar = compose.onNodeWithTag(TODAY_TOP_BAR_TAG).getBoundsInRoot()
        val heading = compose.onNode(isHeading() and hasText("Monday")).getBoundsInRoot()
        assertTrue("the heading (${heading.top}–${heading.bottom}) fits in the bar (${bar.top}–${bar.bottom})", heading.bottom <= bar.bottom && heading.top >= bar.top)
    }

    @Test
    fun overdueAmberShowsTheWarningBesideLastSyncedAndSyncsNow() {
        val amber = stored.copy(
            lastSynced = "Last synced 4 days ago",
            ring = LinkStatePresenter.present(LinkState.WaitingForRing, "RingConn").headline,
            spo2Measure = MeasureButtonUi(reason = "Connect the ring to measure"),
            overdue = TodayOverdueUi("Your ring keeps about 7 days of data. Sync soon so nothing is lost.", TodayAlertLevel.AMBER, canSync = true),
        )
        val actions = show(amber)
        compose.onNodeWithTag(TODAY_OVERDUE_TAG).assertIsDisplayed()
        compose.onNodeWithText("Your ring keeps about 7 days of data. Sync soon so nothing is lost.").assertIsDisplayed()
        compose.onNodeWithText("Last synced 4 days ago").assertIsDisplayed()
        compose.onNodeWithText("62").assertIsDisplayed()
        compose.onNodeWithText("Sync now").performClick()
        assertEquals(listOf<TodayAction>(TodayAction.Refresh), actions)
    }

    @Test
    fun disconnectedKeepsTheStoredReadingsAndSaysWhyMeasureIsOff() {
        // Built by the presenter from a paired ring that is out of reach, so the ring line, Measure's
        // reason and the absent Open Ring / overdue chip are the app's own decisions, not the fixture's.
        val now = Instant.parse("2026-10-21T16:00:00Z")
        val readings = TodayStored(
            heartRate = QuantitySample(MetricKind.HEART_RATE, now.minusSeconds(3 * 3_600L), value = 62.0),
            spo2 = QuantitySample(MetricKind.SPO2, now.minusSeconds(9 * 3_600L), value = 0.97),
        )
        val log = listOf(SyncLogEntry(startedAt = now.minusSeconds(3 * 3_600L + 60), finishedAt = now.minusSeconds(3 * 3_600L), outcome = "COMPLETE"))
        val disconnected = TodayPresenter.present(
            TodayInput(readings, TodaySession(link = LinkState.WaitingForRing, ringName = "RingConn", log = log), now, ZoneOffset.UTC),
        )
        show(disconnected)
        compose.onNodeWithText("Ring unreachable — reconnecting automatically").assertIsDisplayed()
        compose.onNodeWithText("Last synced 3 h ago").assertIsDisplayed()
        compose.onNodeWithText("62").assertIsDisplayed()
        compose.onNodeWithText("97").assertIsDisplayed()
        compose.onNodeWithText("Measure").assertIsNotEnabled()
        compose.onNodeWithText("Connect the ring to measure").assertIsDisplayed()
        compose.onNodeWithTag(TODAY_STRIP_TAG).assertDoesNotExist()
        compose.onNodeWithTag(TODAY_OVERDUE_TAG).assertDoesNotExist()
        compose.onNodeWithText("Open Ring").assertDoesNotExist()
    }

    @Test
    fun aPastDayShowsItsHeartRateRangeInPlaceOfTheHeroAndTheTodayChipReturns() {
        val today = LocalDate.parse("2026-10-21")
        val past = today.minusDays(2)
        val marked = today.minusDays(5)
        val browser = DayBrowser.present(BrowsableDays(today.minusDays(9), today), past, today, setOf(marked))
        val range = PastDayHeroUi(
            title = "Heart rate on Mon 19 Oct",
            range = "52–112",
            detail = "average 72 · resting 55",
            low = 0.1f,
            average = 0.27f,
            high = 0.6f,
            description = "Heart rate on Monday 19 October: 52 to 112 beats per minute, average 72, resting 55.",
        )
        val actions = show(stored.copy(browser = browser, day = past, pastDay = range))

        compose.onNode(isHeading() and hasText("Monday")).assertIsDisplayed()
        compose.onNodeWithTag(TODAY_HERO_TAG).assertDoesNotExist()
        compose.onNodeWithTag(TODAY_PAST_DAY_TAG).assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(range.description)))
        compose.onNodeWithTag(todayDayTag(past)).assertIsSelected()
        val spoken = compose.onNodeWithTag(todayDayTag(marked)).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        assertTrue(spoken, spoken.endsWith(", new data just arrived"))
        compose.onNodeWithTag(TODAY_CHIP_TAG).assertIsDisplayed().performClick()
        assertEquals(listOf<TodayAction>(TodayAction.ShowToday), actions)
    }
}
