package io.github.opencircuit.app

import io.github.opencircuit.app.data.PrefsAppPrefs
import io.github.opencircuit.app.health.HealthAccessState
import io.github.opencircuit.app.health.HealthCardAction
import io.github.opencircuit.app.health.HealthCardSources
import io.github.opencircuit.app.health.HealthFlushState
import io.github.opencircuit.app.health.HealthSwitch
import io.github.opencircuit.app.settings.SettingsAction
import io.github.opencircuit.app.settings.SettingsViewModel
import io.github.opencircuit.health.HealthPermission
import io.github.opencircuit.health.HealthShare
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Settings tab: the "Send to Health Connect" switch and, only while it is ON, the Health
 * Connect card. OFF shows the switch and its line and nothing else — no card content, no Connect
 * button — whatever an earlier version left granted. Turning it ON with a grant already in place
 * shows Connected and Write now, never a Connect button (no second permission screen); without a
 * grant it shows the card's Connect. Write now writes only while ON.
 */
class SettingsViewModelTest {

    private val now = Instant.parse("2026-10-21T10:00:00Z")
    private val values = InMemoryKeyValues()
    private val everything = HealthShare.requested(true)
    private val access = MutableStateFlow(HealthAccessState())
    private val flush = MutableStateFlow(HealthFlushState())
    private var writes = 0

    private fun TestScope.settings(switch: HealthSwitch = HealthSwitch(PrefsAppPrefs(values), log = {})) = SettingsViewModel(
        switch = switch,
        health = HealthCardSources(access, flush, writeNow = { writes++ }),
        scope = backgroundScope,
        wallClock = { now },
        clockTicks = flowOf(Unit),
    )

    @Test
    fun offShowsTheSwitchAndItsLineAndNoCardEvenWithAGrantLeftFromBefore() = runTest {
        access.value = HealthAccessState(available = true, granted = everything, asked = true)
        val vm = settings()
        runCurrent()

        val state = vm.uiState.value
        assertFalse(state.healthOn)
        assertEquals("Off. Your readings stay only in this app. Turn it on to send a copy to Health Connect after each sync.", state.healthLine)
        assertNull(state.healthCard, "no card content while OFF")
        assertFalse(state.saveFailed)

        vm.onAction(SettingsAction.WriteHealthNow)
        assertEquals(0, writes, "Write now does nothing while OFF")
    }

    @Test
    fun turningOnWithAnExistingGrantShowsConnectedAndWriteNowWithoutAConnectButton() = runTest {
        val vm = settings()
        runCurrent()

        vm.onAction(SettingsAction.SetHealthWrite(true))
        access.value = HealthAccessState(available = true, granted = everything, asked = true) // the read the switch's turning on starts
        runCurrent()

        val state = vm.uiState.value
        assertTrue(state.healthOn)
        assertEquals(true, values.raw("health.write.enabled.v1"))
        assertEquals("On. After each sync, a copy of your readings goes to Health Connect so other apps you allow can use it.", state.healthLine)
        val card = assertNotNull(state.healthCard)
        assertEquals("Connected", card.headline)
        assertEquals(HealthCardAction.WRITE_NOW, card.action)
        assertTrue(card.action != HealthCardAction.CONNECT && card.secondAction != HealthCardAction.CONNECT, "no permission screen again")

        vm.onAction(SettingsAction.WriteHealthNow)
        assertEquals(1, writes)
    }

    @Test
    fun turningOnWithNothingGrantedShowsTheCardsConnect() = runTest {
        access.value = HealthAccessState(available = true, granted = emptySet())
        val vm = settings()
        vm.onAction(SettingsAction.SetHealthWrite(true))
        runCurrent()

        val card = assertNotNull(vm.uiState.value.healthCard)
        assertEquals("Not connected", card.headline)
        assertEquals("Connect Health Connect", card.button)
        assertEquals(HealthCardAction.CONNECT, card.action)
    }

    @Test
    fun turningOffHidesTheCardAndIsKept() = runTest {
        values.putRaw("health.write.enabled.v1", true)
        access.value = HealthAccessState(available = true, granted = setOf(HealthPermission.HEART_RATE))
        val vm = settings()
        runCurrent()
        assertNotNull(vm.uiState.value.healthCard)

        vm.onAction(SettingsAction.SetHealthWrite(false))
        runCurrent()

        assertFalse(vm.uiState.value.healthOn)
        assertNull(vm.uiState.value.healthCard)
        assertEquals(false, values.raw("health.write.enabled.v1"))
    }

    @Test
    fun anOnThatCouldNotBeSavedStaysOffAndSaysSo() = runTest {
        val vm = settings()
        values.failWrites = true

        vm.onAction(SettingsAction.SetHealthWrite(true))
        runCurrent()

        assertFalse(vm.uiState.value.healthOn)
        assertNull(vm.uiState.value.healthCard)
        assertTrue(vm.uiState.value.saveFailed)

        values.failWrites = false
        vm.onAction(SettingsAction.SetHealthWrite(true))
        runCurrent()
        assertTrue(vm.uiState.value.healthOn)
        assertFalse(vm.uiState.value.saveFailed, "a later save clears it")
    }
}
