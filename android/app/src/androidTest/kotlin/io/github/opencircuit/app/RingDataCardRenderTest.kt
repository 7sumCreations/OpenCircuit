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
import io.github.opencircuit.app.ring.RingDataUi
import io.github.opencircuit.app.ui.OpenCircuitTheme
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
}
