package io.github.opencircuit.app

import android.content.Context
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencircuit.app.data.RingAddress
import io.github.opencircuit.app.data.SharedPreferencesKeyValues
import io.github.opencircuit.app.demo.DemoRingLink
import io.github.opencircuit.app.health.HEALTH_CONNECT_CARD_TAG
import io.github.opencircuit.app.onboarding.Destination
import io.github.opencircuit.app.settings.SETTINGS_HEALTH_SWITCH_TAG
import io.github.opencircuit.health.HealthRetryLaterException
import io.github.opencircuit.health.RecordType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant

/**
 * The whole Health Connect path in one run on a device, read back FROM THE PLATFORM (the app's own
 * records through the Health Connect client, never the writer's result): a fresh-install state
 * (switch OFF, no ring), the Settings switch turned ON through the UI, the WRITE permissions
 * granted from the shell (never Google's screen), the demo ring connected and synced, and then the
 * records the sync's commit sent are there in Health Connect. The mirror case leaves the switch
 * OFF, with the same grants and the same sync, and nothing arrives.
 *
 * Before each test the app's own records in the window are deleted by id (never another app's), so
 * whatever is read back was written during the test; the same ids are deleted afterwards, and the
 * switch and the other preferences it touched are put back.
 */
@RunWith(AndroidJUnit4::class)
class HealthSwitchSyncReadBackOnDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: OpenCircuitApp
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OpenCircuitApp

    private val hc get() = app.container.healthConnect

    private var noticeDismissed = false
    private var disconnectAfterSyncWas = true

    private fun window(): Pair<Instant, Instant> = Instant.now().minus(Duration.ofDays(60)) to Instant.now().plus(Duration.ofDays(2))

    /** The app's own record ids in the window, per type, as Health Connect holds them now. */
    private suspend fun ownIds(): Map<RecordType, List<String>> {
        val (from, until) = window()
        return RecordType.entries.associateWith { type -> hc.readOwn(type, from, until).map { it.meta.clientRecordId } }
            .filterValues { it.isNotEmpty() }
    }

    /** [ownIds], or nothing when Health Connect asks to be called later (the poll tries again). */
    private suspend fun ownIdsOrNothing(): Map<RecordType, List<String>> = try {
        ownIds()
    } catch (_: HealthRetryLaterException) {
        emptyMap()
    }

    private suspend fun deleteOwn() {
        for ((type, ids) in ownIds()) hc.deleteByClientIds(type, ids)
    }

    @Before
    fun freshInstallState() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { app.container.ringSessions.stopReconnecting() }
        check(app.container.appPrefs.setOnboardingCompleted()) { "could not record that onboarding was done" }
        noticeDismissed = app.container.appPrefs.healthUpgradeNoticeDismissed
        disconnectAfterSyncWas = app.container.appPrefs.disconnectAfterSync
        check(app.container.appPrefs.setDisconnectAfterSync(true)) { "could not turn the switch on" }
        app.container.healthSwitch.set(false)
        // No complete sync recorded for the demo ring: its link-up syncs (a recent one would be skipped).
        val prefs = SharedPreferencesKeyValues(app.getSharedPreferences("opencircuit", Context.MODE_PRIVATE))
        check(prefs.remove("sync.lastComplete.v1/${RingAddress.normalized(DemoRingLink.RING.address)}")) { "could not clear the last sync" }
        runBlocking {
            grantHealthWrites(hc, app.packageName, RecordType.entries.map { it.permission }.distinct())
            deleteOwn()
        }
    }

    @After
    fun putEverythingBack() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { app.container.ringSessions.stopReconnecting() }
        app.container.healthSwitch.set(false)
        // Only this app's own records, found by their ids (the grant stays: other tests rely on it).
        runBlocking { deleteOwn() }
        restoreNoticeFlag(app, noticeDismissed)
        check(app.container.appPrefs.setDisconnectAfterSync(disconnectAfterSyncWas)) { "could not restore the switch" }
    }

    /** Connects the demo ring as pairing would and returns once its sync has committed. */
    private fun syncTheDemoRing() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { app.container.ringSessions.connect(DemoRingLink.RING) }
        compose.waitUntil(timeoutMillis = 90_000) {
            app.container.ringSessions.current.value?.sync?.state?.value?.last?.commit != null
        }
    }

    @Test
    fun switchOnThenAGrantedDemoSyncLandsTheAppsRecordsInHealthConnect() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.openTab(Destination.Settings)
            compose.onNodeWithTag(SETTINGS_HEALTH_SWITCH_TAG).performClick()
            compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithTag(HEALTH_CONNECT_CARD_TAG).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(SETTINGS_HEALTH_SWITCH_TAG).assertIsOn()
            assertTrue("ON is saved", app.container.appPrefs.healthWriteEnabled)
            assertTrue("nothing of ours is in Health Connect before the sync", runBlocking { ownIds() }.isEmpty())

            syncTheDemoRing()

            // The commit's flush runs on the process scope: wait for the platform to hold records, then for the flush to end.
            // Polled slowly: Health Connect rate-limits a caller that reads every few milliseconds.
            val until = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < until && runBlocking { ownIdsOrNothing() }.isEmpty()) Thread.sleep(2_000)
            compose.waitUntil(timeoutMillis = 60_000) { !app.container.healthFlush.state.value.running }
            val back = runBlocking { ownIds() }
            assertTrue("the sync's records are read back from Health Connect", back.isNotEmpty())
            assertTrue(
                "heart rate, steps or sleep among them: ${back.keys}",
                back.keys.any { it in setOf(RecordType.HEART_RATE, RecordType.STEPS, RecordType.SLEEP_SESSION) },
            )
        }
    }

    @Test
    fun switchOffTheSameGrantedDemoSyncWritesNothing() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.openTab(Destination.Settings)
            assertFalse("the switch is OFF", app.container.appPrefs.healthWriteEnabled)

            syncTheDemoRing()
            // Longer than the flush takes when it is on (it runs within a second or two of the commit).
            Thread.sleep(8_000)

            assertEquals(
                "nothing of ours reaches Health Connect while the switch is OFF",
                emptyMap<RecordType, List<String>>(),
                runBlocking { ownIds() },
            )
        }
    }
}
