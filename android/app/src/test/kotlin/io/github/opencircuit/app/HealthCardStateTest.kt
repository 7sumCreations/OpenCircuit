package io.github.opencircuit.app

import io.github.opencircuit.app.health.HealthAccess
import io.github.opencircuit.app.health.HealthAccessState
import io.github.opencircuit.app.health.HealthCardAction
import io.github.opencircuit.app.health.HealthCardInput
import io.github.opencircuit.app.health.HealthCardPresenter
import io.github.opencircuit.app.health.HealthCardSources
import io.github.opencircuit.app.health.HealthFlushState
import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.ring.RingViewModel
import io.github.opencircuit.health.FakeHealthConnect
import io.github.opencircuit.health.FlushResult
import io.github.opencircuit.health.HealthConnectPort
import io.github.opencircuit.health.HealthPermission
import io.github.opencircuit.health.HealthSdkStatus
import io.github.opencircuit.health.RecordType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Ring screen's Health Connect card, from what Health Connect says (available, which WRITE
 * permissions are granted) and what the writes did. Copy as in the mockup. The partial (amber)
 * and write-failed (amber) states arrive with the other record types.
 */
class HealthCardStateTest {

    private val now = Instant.parse("2026-10-16T12:00:00Z")
    private val all = HealthPermission.entries.toSet()

    private fun card(access: HealthAccessState, flush: HealthFlushState = HealthFlushState()) =
        HealthCardPresenter.present(HealthCardInput(access, flush, now))

    @Test
    fun nothingGrantedIsNotConnectedWithTheConnectButton() {
        val ui = card(HealthAccessState(available = true, granted = emptySet()))

        assertEquals("Not connected", ui.headline)
        assertEquals("Writes your ring data to Health Connect on this phone. Nothing leaves the phone.", ui.body)
        assertEquals("Connect Health Connect", ui.button)
        assertEquals(HealthCardAction.CONNECT, ui.action)
        assertTrue(ui.buttonEnabled)
        assertEquals(emptyList(), ui.rows)
    }

    @Test
    fun beforeHealthConnectWasAskedTheCardOffersToConnect() {
        val ui = card(HealthAccessState(available = null))

        assertEquals("Not connected", ui.headline)
        assertEquals(HealthCardAction.CONNECT, ui.action)
    }

    @Test
    fun anUnavailableHealthConnectSaysSoAndTheButtonIsOff() {
        val ui = card(HealthAccessState(available = false, granted = all))

        assertEquals("Health Connect is not available", ui.headline)
        assertEquals("Install or enable Health Connect in system settings, then come back.", ui.body)
        assertEquals("Connect Health Connect", ui.button)
        assertFalse(ui.buttonEnabled)
        assertNull(ui.action)
    }

    @Test
    fun grantedIsConnectedWithTheLastWriteAndWriteNow() {
        val last = FlushResult(finishedAt = now.minusSeconds(20), written = mapOf(RecordType.HEART_RATE to 4_312))
        val ui = card(HealthAccessState(available = true, granted = all), HealthFlushState(last = last))

        assertEquals("Connected", ui.headline)
        assertEquals(listOf("Last written" to "just now", "Written this sync" to "4,312 records"), ui.rows)
        assertEquals("Write to Health Connect now", ui.button)
        assertEquals(HealthCardAction.WRITE_NOW, ui.action)
        assertTrue(ui.buttonEnabled)
    }

    @Test
    fun connectedBeforeAnyWriteAndWhileOneRuns() {
        val before = card(HealthAccessState(available = true, granted = setOf(HealthPermission.HEART_RATE)))
        assertEquals("Connected", before.headline)
        assertEquals(listOf("Last written" to "Not yet"), before.rows)

        val running = card(
            HealthAccessState(available = true, granted = all),
            HealthFlushState(running = true, last = FlushResult(finishedAt = now.minusSeconds(3 * 3600), written = mapOf(RecordType.HEART_RATE to 1))),
        )
        assertEquals(listOf("Last written" to "3 h ago", "Written this sync" to "1 record"), running.rows)
        assertEquals("Writing…", running.button)
        assertFalse(running.buttonEnabled)
    }

    @Test
    fun theAccessIsReadFromHealthConnectAndAFailedReadKeepsWhatWasKnown() = runTest {
        val hc = FakeHealthConnect({ now })
        val logs = mutableListOf<String>()
        val access = HealthAccess(hc, backgroundScope, log = { logs += it })
        assertEquals(HealthAccessState(available = null, granted = emptySet()), access.state.value)

        access.refresh()
        runCurrent()
        assertEquals(HealthAccessState(available = true, granted = emptySet()), access.state.value)

        hc.grant(HealthPermission.HEART_RATE, HealthPermission.SLEEP)
        access.refresh()
        runCurrent()
        assertEquals(setOf(HealthPermission.HEART_RATE, HealthPermission.SLEEP), access.state.value.granted)

        hc.status = HealthSdkStatus.UNAVAILABLE
        access.refresh()
        runCurrent()
        assertEquals(HealthAccessState(available = false, granted = emptySet()), access.state.value)

        val broken = object : HealthConnectPort by hc {
            override suspend fun grantedWrites(): Set<HealthPermission> = throw IllegalStateException("binder died")
        }
        hc.status = HealthSdkStatus.AVAILABLE
        val failing = HealthAccess(broken, backgroundScope, log = { logs += it })
        failing.refresh()
        runCurrent()
        assertEquals(HealthAccessState(available = null, granted = emptySet()), failing.state.value)
        assertTrue(logs.any { "IllegalStateException" in it })
    }

    @Test
    fun theRingScreenShowsTheCardAndWriteNowAsksForAWrite() = runTest {
        val access = MutableStateFlow(HealthAccessState(available = true, granted = all))
        val flush = MutableStateFlow(HealthFlushState())
        var writes = 0
        val vm = RingViewModel(
            sessions = null,
            title = "Ring",
            scope = backgroundScope,
            health = HealthCardSources(access, flush, writeNow = { writes++ }),
            wallClock = { now },
            clockTicks = flowOf(Unit),
        )
        runCurrent()
        assertEquals("Connected", vm.uiState.value.health?.headline)

        vm.onAction(RingAction.WriteHealthNow)
        assertEquals(1, writes)

        flush.value = HealthFlushState(last = FlushResult(finishedAt = now, written = mapOf(RecordType.HEART_RATE to 2)))
        access.value = HealthAccessState(available = true, granted = emptySet())
        runCurrent()
        assertEquals("Not connected", vm.uiState.value.health?.headline)

        // Without the Health Connect sources (the earlier screens' tests) there is no card.
        val bare = RingViewModel(sessions = null, title = "Ring", scope = backgroundScope, wallClock = { now }, clockTicks = flowOf(Unit))
        runCurrent()
        assertNull(bare.uiState.value.health)
    }
}
