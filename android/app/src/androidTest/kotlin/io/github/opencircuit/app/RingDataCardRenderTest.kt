package io.github.opencircuit.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.opencircuit.app.ring.RingDataCard
import io.github.opencircuit.app.ring.RingDataInput
import io.github.opencircuit.app.ring.RingDataPresenter
import io.github.opencircuit.app.ring.RingDataUi
import io.github.opencircuit.app.sync.ChannelProgress
import io.github.opencircuit.app.sync.StoredData
import io.github.opencircuit.app.sync.StoredLastNight
import io.github.opencircuit.app.sync.SyncState
import io.github.opencircuit.app.ui.OpenCircuitTheme
import io.github.opencircuit.ringkit.HistoryChannelOutcome
import io.github.opencircuit.store.SyncLogChannel
import io.github.opencircuit.store.SyncLogEntry
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Ring data card as drawn on a device: the text of each state, Sync now enabled or not, and
 * the switch — its tap reported with the new value, the whole row a switch for TalkBack.
 */
@RunWith(AndroidJUnit4::class)
class RingDataCardRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val actions = mutableListOf<String>()

    private fun show(ui: RingDataUi) {
        compose.setContent {
            OpenCircuitTheme {
                RingDataCard(ui, onSyncNow = { actions += "sync" }, onDisconnectAfterSync = { actions += "switch $it" })
            }
        }
    }

    @Test
    fun aRingNeverSyncedOffersSyncNowAndTheSwitchOn() {
        show(RingDataUi(headline = "Not synced yet", syncEnabled = true, disconnectAfterSync = true))

        compose.onNodeWithText("Ring data").assertIsDisplayed()
        compose.onNodeWithText("Not synced yet").assertIsDisplayed()
        compose.onNodeWithText("Steps and skin temperature are only recorded while the ring is connected.").assertIsDisplayed()
        compose.onNodeWithText("Disconnect after syncing").assertIsOn()
        compose.onNodeWithText("Sync now").assertIsEnabled().performClick()
        compose.onNodeWithText("Disconnect after syncing").performClick()

        assertEquals(listOf("sync", "switch false"), actions)
    }

    @Test
    fun whileSyncingTheButtonIsOffAndTheHelpSaysToKeepTheAppOpen() {
        show(
            RingDataUi(
                headline = "Syncing…", syncing = true, syncEnabled = false, syncLabel = "Sync in progress",
                help = "Keep the app open until the sync finishes. It disconnects when your data is saved.",
            ),
        )

        compose.onNodeWithText("Syncing…").assertIsDisplayed()
        compose.onNodeWithText("Keep the app open until the sync finishes. It disconnects when your data is saved.").assertIsDisplayed()
        compose.onNodeWithText("Sync in progress").assertIsNotEnabled().performClick()
        assertEquals(emptyList<String>(), actions)
    }

    @Test
    fun afterASyncTheCardShowsWhenAndWhatWasStored() {
        show(RingDataUi(headline = "Last synced just now", lastSync = "18 records · complete", syncEnabled = true, disconnectAfterSync = false))

        compose.onNodeWithText("Last synced just now").assertIsDisplayed()
        compose.onNodeWithText("Last sync").assertIsDisplayed()
        compose.onNodeWithText("18 records · complete").assertIsDisplayed()
        compose.onNodeWithText("Disconnect after syncing").assertIsOff()
    }

    @Test
    fun aSyncThatCouldNotSaveSaysSo() {
        show(
            RingDataUi(
                headline = "Last synced just now", lastSync = "6 records · partial — data kept, will retry",
                problem = "Couldn't save — will retry", syncEnabled = true,
            ),
        )

        compose.onNodeWithText("Couldn't save — will retry").assertIsDisplayed()
        compose.onNodeWithText("6 records · partial — data kept, will retry").assertIsDisplayed()
    }

    // ---- Each state as the presenter builds it (the same input before and after a relaunch) ----

    private val now: Instant = Instant.parse("2026-10-08T09:00:00Z")

    private fun complete(label: String, at: Instant) =
        SyncLogChannel(label = label, channel = if (label == "sleep") 0 else 3, verdict = HistoryChannelOutcome.COMPLETE, drainedThrough = at)

    private fun entry(outcome: String, stored: Int, finishedAt: Instant = now.minus(Duration.ofDays(2))) = SyncLogEntry(
        startedAt = finishedAt.minusSeconds(60),
        finishedAt = finishedAt,
        outcome = outcome,
        recordsStored = stored,
        firmware = "FR02.018",
        channels = listOf(complete("sleep", finishedAt), complete("all-day", finishedAt)),
    )

    private fun present(log: List<SyncLogEntry>, stored: StoredData? = null, sync: SyncState = SyncState()) = RingDataPresenter.present(
        RingDataInput(sync = sync, log = log, stored = stored, now = now, zone = ZoneId.of("Europe/London")),
    )

    @Test
    fun syncingShowsEachChannelsProgressAndTheButtonOff() {
        show(
            present(
                emptyList(),
                sync = SyncState(syncing = true, channels = listOf(ChannelProgress("sleep", 1_204, null, null, true), ChannelProgress("all-day", 612, null, null, false))),
            ),
        )

        compose.onNodeWithText("Sleep channel done · 1,204 records").assertIsDisplayed()
        compose.onNodeWithText("All-day channel 612 records so far").assertIsDisplayed()
        compose.onNodeWithText("Keep the app open until the sync finishes. It disconnects when your data is saved.").assertIsDisplayed()
    }

    @Test
    fun aCompleteSyncShowsWhatIsStoredTheNightsAndLastNight() {
        val stored = StoredData(
            oldest = Instant.parse("2026-09-12T06:00:00Z"),
            newest = Instant.parse("2026-10-08T07:30:00Z"),
            nights = 21,
            lastNight = StoredLastNight(Instant.parse("2026-10-07T22:41:00Z"), Instant.parse("2026-10-08T06:02:00Z"), asleepMinutes = 441),
        )
        show(present(listOf(entry("COMPLETE", 4_312, finishedAt = now.minus(Duration.ofHours(3)))), stored))

        compose.onNodeWithText("Last synced 3 h ago").assertIsDisplayed()
        compose.onNodeWithText("Stored on this phone").assertIsDisplayed()
        compose.onNodeWithText("12 Sep – 8 Oct").assertIsDisplayed()
        compose.onNodeWithText("Nights").assertIsDisplayed()
        compose.onNodeWithText("21").assertIsDisplayed()
        compose.onNodeWithText("23:41 → 07:02 · 7 h 21 m").assertIsDisplayed()
        compose.onNodeWithText("4,312 records · complete").assertIsDisplayed()
    }

    @Test
    fun upToDatePartialAndNoDataReadAsSuch() {
        show(present(listOf(entry("COMPLETE", 0, finishedAt = now))))
        compose.onNodeWithText("Up to date").assertIsDisplayed()
    }

    @Test
    fun aPartialSyncSaysDataKept() {
        show(present(listOf(entry("PARTIAL", 12, finishedAt = now))))
        compose.onNodeWithText("12 records · partial — data kept, will retry").assertIsDisplayed()
    }

    @Test
    fun aSyncThatGotNothingSaysNoDataAndWhy() {
        show(present(listOf(entry("NO_ACK", 0, finishedAt = now))))
        compose.onNodeWithText("No data received").assertIsDisplayed()
        compose.onNodeWithText("The ring didn't answer the sync request").assertIsDisplayed()
    }

    @Test
    fun lostFramesHeldBackRecordsAndNightsWaitingAreSaid() {
        val held = entry("PARTIAL", 0, finishedAt = now).copy(
            undeliveredFrames = 3,
            heldBack = 12,
            heldBackBy = listOf("all-day"),
            nightsWaiting = 2,
            channels = listOf(complete("sleep", now), SyncLogChannel(label = "all-day", channel = 3, verdict = HistoryChannelOutcome.NO_ACK)),
        )
        show(present(listOf(held)))

        compose.onNodeWithText("3 frames from the ring weren't read — sync again").assertIsDisplayed()
        compose.onNodeWithText("12 records waiting — the all-day channel didn't answer").assertIsDisplayed()
        compose.onNodeWithText("2 nights waiting — saved by a later sync").assertIsDisplayed()
    }

    @Test
    fun anOverdueRingShowsTheWarningAmberThenRed() {
        show(present(listOf(entry("COMPLETE", 10, finishedAt = now.minus(Duration.ofDays(4))))))
        compose.onNodeWithText("Your ring keeps about 7 days of data. Sync soon so nothing is lost.").assertIsDisplayed()
    }

    @Test
    fun aRingFiveDaysOverdueSaysSyncNow() {
        show(present(listOf(entry("COMPLETE", 10, finishedAt = now.minus(Duration.ofDays(5))))))
        compose.onNodeWithText("Your ring keeps about 7 days of data. Sync now so nothing is lost.").assertIsDisplayed()
    }
}
