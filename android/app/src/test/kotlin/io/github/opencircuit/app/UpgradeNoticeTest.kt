package io.github.opencircuit.app

import io.github.opencircuit.app.data.PrefsAppPrefs
import io.github.opencircuit.app.health.HealthSwitch
import io.github.opencircuit.app.health.HealthUpgradeNotice
import io.github.opencircuit.app.health.StoreHealthAccessMemory
import io.github.opencircuit.app.health.StoreHealthFailures
import io.github.opencircuit.app.today.TodayAction
import io.github.opencircuit.app.today.TodayViewModel
import io.github.opencircuit.store.StoreDatabase
import io.github.opencircuit.store.StoreFactory
import io.github.opencircuit.store.openInMemory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Today's one-time "Health Connect export is now off" notice, for installs that used Health Connect
 * under 0.3.0 — told apart by the store's "permission screen shown" flag (`hc.permissionsRequested`,
 * which 0.3.0 set when its permission screen answered; read from the store, never from Health
 * Connect). A fresh install never sees it: the flag can only be set after the switch is ON, and
 * using the switch dismisses the notice. Dismissed is kept in the preferences and read
 * synchronously, so a dismissed notice never flashes on a later launch.
 */
class UpgradeNoticeTest {

    private val epoch = Instant.parse("2026-10-21T08:00:00Z")
    private val values = InMemoryKeyValues()

    private suspend fun TestScope.store(): StoreDatabase = StoreFactory.openInMemory(StandardTestDispatcher(testScheduler))

    private fun TestScope.memory(db: StoreDatabase) =
        StoreHealthAccessMemory({ db }, { epoch }, StoreHealthFailures({ db }, { epoch }, log = {}))

    private fun TestScope.notice(db: StoreDatabase, usedBefore: suspend () -> Boolean = { memory(db).asked() }) =
        HealthUpgradeNotice(PrefsAppPrefs(values), usedBefore, backgroundScope, log = {})

    @Test
    fun aFreshInstallNeverSeesIt() = runTest {
        val db = store()
        try {
            val n = notice(db)
            runCurrent()
            assertFalse(n.visible.value)
        } finally {
            db.close()
        }
    }

    @Test
    fun anInstallThatUsedHealthConnectSeesItUntilDismissedAndNeverAgain() = runTest {
        val db = store()
        try {
            memory(db).markAsked() // what 0.3.0 left: its permission screen was shown and answered
            val n = notice(db)
            runCurrent()
            assertTrue(n.visible.value)

            n.dismiss()
            assertFalse(n.visible.value)
            assertEquals(true, values.raw("health.upgradeNotice.dismissed.v1"))

            // A relaunch: dismissed is read before anything else, so the store is not even asked.
            var asked = 0
            val again = notice(db, usedBefore = { asked++; true })
            assertFalse(again.visible.value)
            runCurrent()
            assertFalse(again.visible.value)
            assertEquals(0, asked)
        } finally {
            db.close()
        }
    }

    /** Dismissed while the store read is still out: the late answer does not bring it back. */
    @Test
    fun aDismissalDuringTheStoreReadWins() = runTest {
        val answer = CompletableDeferred<Boolean>()
        val n = HealthUpgradeNotice(PrefsAppPrefs(values), { answer.await() }, backgroundScope, log = {})
        runCurrent()
        n.dismiss()
        answer.complete(true)
        runCurrent()
        assertFalse(n.visible.value)
    }

    /**
     * With writing ON the notice ("export is now off") would be false: never shown, even when an
     * earlier dismissal could not be saved and the store flag is set.
     */
    @Test
    fun withTheSwitchOnItIsNeverShown() = runTest {
        values.putRaw("health.write.enabled.v1", true)
        val n = HealthUpgradeNotice(PrefsAppPrefs(values), { true }, backgroundScope, log = {})
        runCurrent()
        assertFalse(n.visible.value)
    }

    /** A dismissal that could not be saved still hides it for this run. */
    @Test
    fun aDismissalThatCouldNotBeSavedStillHidesIt() = runTest {
        val n = HealthUpgradeNotice(PrefsAppPrefs(values), { true }, backgroundScope, log = {})
        runCurrent()
        values.failWrites = true
        n.dismiss()
        assertFalse(n.visible.value)
    }

    /** A store that cannot be read shows no notice (it is a courtesy, never a blocker). */
    @Test
    fun anUnreadableStoreShowsNothing() = runTest {
        val logs = mutableListOf<String>()
        val n = HealthUpgradeNotice(PrefsAppPrefs(values), { throw IllegalStateException("disk") }, backgroundScope, log = { logs += it })
        runCurrent()
        assertFalse(n.visible.value)
        assertTrue(logs.any { "IllegalStateException" in it })
    }

    /** Using the switch, either way, is the user having seen the change: the notice goes. */
    @Test
    fun usingTheSwitchDismissesIt() = runTest {
        val n = HealthUpgradeNotice(PrefsAppPrefs(values), { true }, backgroundScope, log = {})
        val switch = HealthSwitch(PrefsAppPrefs(values), log = {}, onChanged = { n.dismiss() })
        runCurrent()
        assertTrue(n.visible.value)

        switch.set(true)
        assertFalse(n.visible.value)
        assertTrue(PrefsAppPrefs(values).healthUpgradeNoticeDismissed)
    }

    /** On Today: shown while visible; Dismiss hides it; Open Settings hides it too (the host opens the tab). */
    @Test
    fun todayShowsItAndBothButtonsDismissIt() = runTest {
        val n = HealthUpgradeNotice(PrefsAppPrefs(values), { true }, backgroundScope, log = {})
        fun TestScope.today() = TodayViewModel(
            source = null,
            scope = backgroundScope,
            wallClock = { epoch },
            zone = { ZoneOffset.UTC },
            clockTicks = flowOf(Unit),
            saved = androidx.lifecycle.SavedStateHandle(),
            healthNotice = n,
        )
        val vm = today()
        runCurrent()
        assertTrue(vm.uiState.value.healthNotice)

        vm.onAction(TodayAction.DismissHealthNotice)
        runCurrent()
        assertFalse(vm.uiState.value.healthNotice)

        values.putRaw("health.upgradeNotice.dismissed.v1", false)
        val n2 = HealthUpgradeNotice(PrefsAppPrefs(values), { true }, backgroundScope, log = {})
        val vm2 = TodayViewModel(source = null, scope = backgroundScope, wallClock = { epoch }, zone = { ZoneOffset.UTC }, clockTicks = flowOf(Unit), saved = androidx.lifecycle.SavedStateHandle(), healthNotice = n2)
        runCurrent()
        assertTrue(vm2.uiState.value.healthNotice)
        vm2.onAction(TodayAction.OpenSettings)
        runCurrent()
        assertFalse(vm2.uiState.value.healthNotice)
        assertTrue(PrefsAppPrefs(values).healthUpgradeNoticeDismissed)

        // Without a notice (every earlier test's Today) there is none.
        val bare = TodayViewModel(source = null, scope = backgroundScope, wallClock = { epoch }, zone = { ZoneOffset.UTC }, clockTicks = flowOf(Unit), saved = androidx.lifecycle.SavedStateHandle())
        runCurrent()
        assertFalse(bare.uiState.value.healthNotice)
    }
}
