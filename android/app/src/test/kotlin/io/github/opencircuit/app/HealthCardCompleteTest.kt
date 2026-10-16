package io.github.opencircuit.app

import io.github.opencircuit.app.health.HealthAccessState
import io.github.opencircuit.app.health.HealthCardAction
import io.github.opencircuit.app.health.HealthCardInput
import io.github.opencircuit.app.health.HealthCardPresenter
import io.github.opencircuit.app.health.HealthFlushState
import io.github.opencircuit.health.FlushResult
import io.github.opencircuit.health.HealthPermission
import io.github.opencircuit.health.HealthShare
import io.github.opencircuit.health.RecordType
import io.github.opencircuit.health.ShareState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The card's remaining states and lines (copy from the Ring screen mockup and the research's
 * permission and rate-limit answers): the "Not connected" variant after the permission screen was
 * answered with nothing allowed (a second request may be refused silently, so the card opens
 * Health Connect's settings instead), the live-only line, the "catching up" line after a write
 * that stopped at Health Connect's limit, and every input landing on one of the five states.
 */
class HealthCardCompleteTest {

    private val now = Instant.parse("2026-10-17T12:00:00Z")
    private val all = HealthPermission.entries.toSet()

    private fun card(access: HealthAccessState, flush: HealthFlushState = HealthFlushState()) =
        HealthCardPresenter.present(HealthCardInput(access, flush, now))

    @Test
    fun afterThePermissionScreenWasAnsweredWithNothingAllowedTheCardOpensHealthConnectSettings() {
        val ui = card(HealthAccessState(available = true, granted = emptySet(), skinTemperatureSupported = true, asked = true))

        assertEquals("Not connected", ui.headline)
        assertEquals("Nothing is allowed yet. Choose what OpenCircuit may write in Health Connect's settings.", ui.body)
        assertEquals("Open Health Connect settings", ui.button)
        assertEquals(HealthCardAction.MANAGE, ui.action)
        assertTrue(ui.buttonEnabled)
        assertNull(ui.secondButton)
        assertEquals(emptyList(), ui.notes)
    }

    @Test
    fun beforeThePermissionScreenWasShownTheCardStillAsksToConnect() {
        val ui = card(HealthAccessState(available = true, granted = emptySet(), skinTemperatureSupported = true, asked = false))

        assertEquals("Connect Health Connect", ui.button)
        assertEquals(HealthCardAction.CONNECT, ui.action)
    }

    @Test
    fun theLiveOnlyLineShowsOnlyWhileHealthConnectIsConnected() {
        val connected = card(HealthAccessState(available = true, granted = all, skinTemperatureSupported = true))
        val partial = card(HealthAccessState(available = true, granted = all - HealthPermission.SLEEP, skinTemperatureSupported = true))
        val notConnected = card(HealthAccessState(available = true, granted = emptySet(), asked = true))
        val unavailable = card(HealthAccessState(available = false))

        assertEquals(listOf(LIVE_ONLY), connected.notes)
        assertEquals(listOf(LIVE_ONLY), partial.notes)
        assertEquals(emptyList(), notConnected.notes)
        assertEquals(emptyList(), unavailable.notes)
    }

    @Test
    fun aWriteThatStoppedAtHealthConnectsLimitSaysCatchingUpAsAPlainLineNotAmber() {
        val last = FlushResult(finishedAt = now.minusSeconds(20), written = mapOf(RecordType.HEART_RATE to 1_000), catchingUp = true)
        val ui = card(HealthAccessState(available = true, granted = all, skinTemperatureSupported = true), HealthFlushState(last = last))

        assertEquals(listOf(CATCHING_UP, LIVE_ONLY), ui.notes)
        assertNull(ui.warning)
        assertEquals(listOf("Last written" to "just now", "Written this sync" to "1,000 records"), ui.rows)
        assertEquals("Write to Health Connect now", ui.button)
        assertTrue(ui.buttonEnabled)
    }

    @Test
    fun aWriteThatFinishedSaysNothingAboutCatchingUp() {
        val last = FlushResult(finishedAt = now.minusSeconds(20), written = mapOf(RecordType.HEART_RATE to 3))
        val ui = card(HealthAccessState(available = true, granted = all, skinTemperatureSupported = true), HealthFlushState(last = last))

        assertFalse(CATCHING_UP in ui.notes)
    }

    /**
     * The five states, from the share state `HealthShare.resolve` gives (the source the card's
     * list must cover): not available; not connected (Connect, or Open Health Connect settings
     * once asked); connected; partial (amber "Not allowed"); write failed (amber "Could not write").
     */
    @Test
    fun everyInputLandsOnOneOfTheFiveStatesWithItsButton() {
        val grants = listOf(emptySet(), setOf(HealthPermission.HEART_RATE), all, setOf(HealthPermission.SKIN_TEMPERATURE), all - HealthPermission.SKIN_TEMPERATURE)
        var cases = 0
        val headlines = mutableSetOf<String>()
        for (available in listOf(null, true, false)) for (granted in grants) for (asked in listOf(false, true))
            for (failed in listOf(emptyMap(), mapOf(RecordType.SLEEP_SESSION to now))) for (skin in listOf(true, false, null))
                for (catching in listOf(false, true)) {
                    cases++
                    val access = HealthAccessState(available, granted, skin, asked, failed)
                    // The last write's failure map is the one the card shows (HealthCardAmberTest).
                    val last = FlushResult(finishedAt = now, catchingUp = catching, failureMap = failed)
                    val ui = card(access, HealthFlushState(last = last))
                    val label = "available=$available granted=$granted asked=$asked failed=$failed skin=$skin catching=$catching"
                    headlines += ui.headline
                    if (available == false) {
                        assertEquals("Health Connect is not available", ui.headline, label)
                        assertNull(ui.action, label)
                        assertFalse(ui.buttonEnabled, label)
                        assertEquals(emptyList(), ui.notes, label)
                        continue
                    }
                    val share = HealthShare.resolve(granted, HealthShare.requested(skin != false))
                    if (share == ShareState.Unauthorized) {
                        assertEquals("Not connected", ui.headline, label)
                        assertEquals(if (asked) HealthCardAction.MANAGE else HealthCardAction.CONNECT, ui.action, label)
                        assertEquals(if (asked) "Open Health Connect settings" else "Connect Health Connect", ui.button, label)
                        assertNull(ui.warning, label)
                        assertEquals(emptyList(), ui.notes, label)
                        continue
                    }
                    assertEquals("Connected", ui.headline, label)
                    assertEquals(HealthCardAction.WRITE_NOW, ui.action, label)
                    val warning = ui.warning.orEmpty()
                    assertEquals(share is ShareState.Partial, "Not allowed:" in warning, label)
                    assertEquals(share is ShareState.Partial, ui.secondAction == HealthCardAction.MANAGE, label)
                    assertEquals(failed.isNotEmpty(), "Could not write" in warning, label)
                    assertEquals(if (catching) listOf(CATCHING_UP, LIVE_ONLY) else listOf(LIVE_ONLY), ui.notes, label)
                    assertFalse("Catching up" in warning, label)
                }
        assertEquals(360, cases)
        assertEquals(setOf("Health Connect is not available", "Not connected", "Connected"), headlines)
    }

    /**
     * "Not available" covers work profiles, Private Space and a missing Health Connect service:
     * Jetpack's getSdkStatus answers SDK_UNAVAILABLE for all of them on Android 14 and later, so the
     * adapter must take anything but SDK_AVAILABLE as unavailable (source scan; the adapter never
     * runs on the JVM).
     */
    @Test
    fun anythingButSdkAvailableIsUnavailableInTheAdapter() {
        val adapter = java.io.File("../health/src/androidMain/kotlin/io/github/opencircuit/health/JetpackHealthConnect.kt").readText()
        assertTrue(
            Regex("""override suspend fun sdkStatus\(\): HealthSdkStatus =\s*if \(HealthConnectClient\.getSdkStatus\(appContext\) == HealthConnectClient\.SDK_AVAILABLE\) HealthSdkStatus\.AVAILABLE else HealthSdkStatus\.UNAVAILABLE""")
                .containsMatchIn(adapter),
        )
    }

    private companion object {
        const val LIVE_ONLY = "Steps and skin temperature are recorded only while the ring is connected."
        const val CATCHING_UP = "Catching up: Health Connect paused the writes. The rest is written on the next sync."
    }
}
