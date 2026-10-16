package io.github.opencircuit.app

import io.github.opencircuit.app.health.HealthAccess
import io.github.opencircuit.app.health.HealthAccessMemory
import io.github.opencircuit.app.health.HealthAccessState
import io.github.opencircuit.app.health.HealthCardAction
import io.github.opencircuit.app.health.HealthCardInput
import io.github.opencircuit.app.health.HealthCardPresenter
import io.github.opencircuit.app.health.HealthFlushState
import io.github.opencircuit.health.FakeHealthConnect
import io.github.opencircuit.health.FlushResult
import io.github.opencircuit.health.HealthFeature
import io.github.opencircuit.health.HealthPermission
import io.github.opencircuit.health.RecordType
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The card's amber states (copy from the Ring screen mockup): partial — some types not allowed,
 * named, the rest still written, with "Change in Health Connect"; write failed — a type whose
 * write failed, named, retried on the next sync, with "Try again". The failure map comes from the
 * last write, or from the store before this launch's first write. Skin temperature on a phone
 * without it is a plain line, never amber. And what the access state reads besides the grants:
 * the skin-temperature feature, whether the permission screen was shown, the failure map.
 */
class HealthCardAmberTest {

    private val now = Instant.parse("2026-10-16T12:00:00Z")
    private val all = HealthPermission.entries.toSet()

    private fun card(access: HealthAccessState, flush: HealthFlushState = HealthFlushState()) =
        HealthCardPresenter.present(HealthCardInput(access, flush, now))

    private fun connected(granted: Set<HealthPermission> = all, skin: Boolean? = true, failures: Map<RecordType, Instant> = emptyMap()) =
        HealthAccessState(available = true, granted = granted, skinTemperatureSupported = skin, failures = failures)

    // ---- partial ----

    @Test
    fun somethingNotAllowedIsAmberNamingItWithTheRestWrittenAndChangeInHealthConnect() {
        val ui = card(connected(granted = all - setOf(HealthPermission.SKIN_TEMPERATURE, HealthPermission.RESPIRATORY_RATE)))

        assertEquals("Connected", ui.headline)
        assertEquals("Not allowed: Respiratory rate, Skin temperature. The rest is being written.", ui.warning)
        assertEquals("Write to Health Connect now", ui.button)
        assertEquals(HealthCardAction.WRITE_NOW, ui.action)
        assertEquals("Change in Health Connect", ui.secondButton)
        assertEquals(HealthCardAction.MANAGE, ui.secondAction)
    }

    @Test
    fun everythingAllowedIsNotAmber() {
        val ui = card(connected())

        assertNull(ui.warning)
        assertNull(ui.secondButton)
    }

    // ---- write failed ----

    @Test
    fun aFailedWriteIsAmberNamingTheTypeWithTryAgain() {
        val last = FlushResult(finishedAt = now, failureMap = mapOf(RecordType.SLEEP_SESSION to now))
        val ui = card(connected(), HealthFlushState(last = last))

        assertEquals("Could not write Sleep. It will be retried on the next sync.", ui.warning)
        assertEquals("Try again", ui.button)
        assertEquals(HealthCardAction.WRITE_NOW, ui.action)
        assertTrue(ui.buttonEnabled)
    }

    @Test
    fun aFailureStoredBeforeThisLaunchShowsBeforeTheFirstWrite() {
        val ui = card(connected(failures = mapOf(RecordType.HEART_RATE to now.minusSeconds(3600), RecordType.SLEEP_SESSION to now)))

        assertEquals("Could not write Heart rate, Sleep. It will be retried on the next sync.", ui.warning)
    }

    @Test
    fun theLastWritesMapReplacesTheStoredOne() {
        // Stored as failing at launch; this launch's write healed it.
        val healed = FlushResult(finishedAt = now, written = mapOf(RecordType.SLEEP_SESSION to 1))
        val ui = card(connected(failures = mapOf(RecordType.SLEEP_SESSION to now.minusSeconds(3600))), HealthFlushState(last = healed))

        assertNull(ui.warning)
        assertEquals("Write to Health Connect now", ui.button)
    }

    @Test
    fun notAllowedAndFailedTogetherShowBothWithTryAgainAndChange() {
        val last = FlushResult(finishedAt = now, failureMap = mapOf(RecordType.HEART_RATE to now))
        val ui = card(connected(granted = all - HealthPermission.SLEEP), HealthFlushState(last = last))

        assertEquals("Could not write Heart rate. It will be retried on the next sync. Not allowed: Sleep. The rest is being written.", ui.warning)
        assertEquals("Try again", ui.button)
        assertEquals("Change in Health Connect", ui.secondButton)
    }

    @Test
    fun whileAWriteRunsTryAgainWaits() {
        val last = FlushResult(finishedAt = now, failureMap = mapOf(RecordType.SLEEP_SESSION to now))
        val ui = card(connected(), HealthFlushState(running = true, last = last))

        assertEquals("Writing…", ui.button)
        assertFalse(ui.buttonEnabled)
    }

    // ---- skin temperature not on this phone ----

    @Test
    fun skinTemperatureOnAPhoneWithoutItIsAPlainLineNotAmber() {
        val ui = card(connected(granted = all - HealthPermission.SKIN_TEMPERATURE, skin = false))

        assertNull(ui.warning)
        assertTrue("Skin temperature" to "Not supported on this phone" in ui.rows)
    }

    @Test
    fun aGrantOfOnlyWhatIsNotAskedForIsNotConnected() {
        val ui = card(connected(granted = setOf(HealthPermission.SKIN_TEMPERATURE), skin = false))

        assertEquals("Not connected", ui.headline)
        assertEquals(HealthCardAction.CONNECT, ui.action)
    }

    // ---- what the access state reads ----

    private class Memory(var asked: Boolean = false, var failures: Map<RecordType, Instant> = emptyMap()) : HealthAccessMemory {
        var marks = 0
        override suspend fun asked(): Boolean = asked
        override suspend fun markAsked() {
            asked = true
            marks++
        }
        override suspend fun failures(): Map<RecordType, Instant> = failures
    }

    @Test
    fun theAccessReadsTheFeatureTheShownFlagAndTheStoredFailures() = runTest {
        val hc = FakeHealthConnect({ now }).apply { grantAll() }
        val memory = Memory(failures = mapOf(RecordType.SLEEP_SESSION to now))
        val access = HealthAccess(hc, memory, backgroundScope, log = {})

        access.refresh()
        runCurrent()
        assertEquals(HealthAccessState(available = true, granted = all, skinTemperatureSupported = true, asked = false, failures = memory.failures), access.state.value)

        hc.features = emptySet<HealthFeature>()
        access.refresh()
        runCurrent()
        assertEquals(false, access.state.value.skinTemperatureSupported)
        assertEquals(all - HealthPermission.SKIN_TEMPERATURE, access.requested())
    }

    @Test
    fun theAnsweredPermissionScreenIsRememberedAndTheGrantsReadAgain() = runTest {
        val hc = FakeHealthConnect({ now })
        val memory = Memory()
        val access = HealthAccess(hc, memory, backgroundScope, log = {})
        access.refresh()
        runCurrent()
        assertFalse(access.state.value.asked)

        hc.grant(HealthPermission.HEART_RATE)
        access.permissionScreenAnswered()
        runCurrent()

        assertEquals(1, memory.marks)
        assertTrue(access.state.value.asked)
        assertEquals(setOf(HealthPermission.HEART_RATE), access.state.value.granted)
    }

    @Test
    fun beforeTheFeatureIsKnownEverythingIsAskedFor() {
        assertEquals(all, HealthAccessState(available = null).requested)
    }
}
