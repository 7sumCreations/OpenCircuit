package io.github.opencircuit.app

import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencircuit.app.data.PrefsAppPrefs
import io.github.opencircuit.app.data.SharedPreferencesKeyValues
import io.github.opencircuit.app.demo.DemoRingLink
import io.github.opencircuit.app.ring.RingDataCard
import io.github.opencircuit.app.ring.RingDataInput
import io.github.opencircuit.app.ring.RingDataPresenter
import io.github.opencircuit.app.session.RingSessionController
import io.github.opencircuit.app.sync.SessionHistory
import io.github.opencircuit.app.sync.StoreHistory
import io.github.opencircuit.app.sync.SyncOutcome
import io.github.opencircuit.app.ui.OpenCircuitTheme
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.store.StoreDatabase
import io.github.opencircuit.store.StoreFactory
import io.github.opencircuit.store.open
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

/**
 * What the Ring data card shows survives a relaunch, on the device's own files: one session syncs
 * the demo ring into a database file and the user turns "Disconnect after syncing" off; everything
 * is closed; a second session over the same database file and preferences file — nothing carried
 * in memory — shows when the ring last synced, the result, what is stored, the nights and the
 * switch, before any new sync. The test's own database and preferences are deleted before and after.
 */
@RunWith(AndroidJUnit4::class)
class RelaunchPersistenceOnDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun prefs() = PrefsAppPrefs(SharedPreferencesKeyValues(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)))

    private fun history(db: StoreDatabase) = SessionHistory(
        store = StoreHistory({ db }, RING_ID, { ZoneId.systemDefault() }),
        wallClock = Instant::now,
        disconnectAfterSync = { prefs().disconnectAfterSync },
        zone = { ZoneId.systemDefault() },
    )

    @Before
    fun clean() {
        context.deleteDatabase(DB)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun cleanUp() = clean()

    @Test
    fun theLastSyncWhatIsStoredAndTheSwitchSurviveANewSessionOverTheSameFiles() = runBlocking<Unit> {
        check(prefs().setDisconnectAfterSync(false)) { "could not turn the switch off" }

        // The first run: sync the demo ring into the database file, then close everything.
        val first = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val db1 = StoreFactory.open(context, DB)
        val link1 = DemoRingLink(first)
        try {
            val session = RingSessionController(link1, first, SystemClock::elapsedRealtime, {}, history(db1))
            session.connect()
            withTimeout(10_000) { link1.state.first { it == LinkState.Authenticated } }
            check(session.sync.syncNow()) { "the sync did not start" }
            val report = withTimeout(90_000) { session.sync.state.first { it.last != null } }.last
            assertEquals(SyncOutcome.COMPLETE, report?.outcome)
        } finally {
            link1.close()
            first.cancel()
            db1.close()
        }

        // The relaunch: a new session over the same files.
        val second = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val db2 = StoreFactory.open(context, DB)
        val link2 = DemoRingLink(second)
        try {
            val session = RingSessionController(link2, second, SystemClock::elapsedRealtime, {}, history(db2))
            session.start()
            val log = withTimeout(10_000) { session.syncRecords.entries.first { it.isNotEmpty() } }
            val stored = withTimeout(10_000) { session.syncRecords.stored.first { it != null } }
            val ui = RingDataPresenter.present(
                RingDataInput(
                    sync = session.sync.state.value,
                    log = log,
                    stored = stored,
                    disconnectAfterSync = prefs().disconnectAfterSync,
                    now = Instant.now(),
                    zone = ZoneId.systemDefault(),
                ),
            )
            assertNotNull("last night is stored", ui.lastNight)
            compose.setContent { OpenCircuitTheme { RingDataCard(ui, onSyncNow = {}, onDisconnectAfterSync = {}) } }

            compose.onNodeWithText("Last synced just now").assertIsDisplayed()
            compose.onNodeWithText(" records · complete", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Stored on this phone").assertIsDisplayed()
            compose.onNodeWithText("Nights").assertIsDisplayed()
            compose.onNodeWithText("3").assertIsDisplayed()
            compose.onNodeWithText("Disconnect after syncing").assertIsOff()
        } finally {
            link2.close()
            second.cancel()
            db2.close()
        }
    }

    private companion object {
        const val DB = "relaunch-persistence-test.db"
        const val PREFS = "relaunch-persistence-test"
        const val RING_ID = "AA:BB:CC:DD:EE:00"
    }
}
