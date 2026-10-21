package io.github.opencircuit.app

import io.github.opencircuit.app.data.PrefsAppPrefs
import io.github.opencircuit.app.health.HealthSwitch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Settings' "Send to Health Connect" switch over the saved preference: it starts from what is
 * saved (nothing saved is OFF), an ON that could not be saved stays OFF (it would be lost at the
 * next launch, and the user must see that), and an OFF applies at once even when it could not be
 * saved — stopping is never held back by a full disk.
 */
class HealthSwitchTest {

    private val values = InMemoryKeyValues()
    private val changes = mutableListOf<Boolean>()

    private fun switch() = HealthSwitch(PrefsAppPrefs(values), log = {}, onChanged = { changes += it })

    @Test
    fun nothingSavedStartsOffAndASavedOnStartsOn() {
        assertFalse(switch().isOn())
        assertFalse(switch().enabled.value)

        values.putRaw(KEY, true)
        assertTrue(switch().isOn(), "a relaunch keeps the user's ON")

        values.putRaw(KEY, "on")
        assertFalse(switch().isOn(), "a damaged value starts OFF")
    }

    @Test
    fun turningOnIsSavedAndReported() {
        val s = switch()

        assertTrue(s.set(true))

        assertTrue(s.isOn())
        assertTrue(s.enabled.value)
        assertEquals(true, values.raw(KEY))
        assertEquals(listOf(true), changes)

        assertTrue(s.set(true), "setting the same value again succeeds")
        assertEquals(listOf(true), changes, "and reports no change")
    }

    @Test
    fun anOnThatCouldNotBeSavedStaysOff() {
        val s = switch()
        values.failWrites = true

        assertFalse(s.set(true))

        assertFalse(s.isOn())
        assertFalse(s.enabled.value)
        assertEquals(emptyList(), changes)
    }

    @Test
    fun anOffAppliesAtOnceEvenWhenItCouldNotBeSaved() {
        values.putRaw(KEY, true)
        val s = switch()
        values.failWrites = true

        assertFalse(s.set(false), "the failed save is reported")

        assertFalse(s.isOn(), "but writing stops now")
        assertFalse(s.enabled.value)
        assertEquals(listOf(false), changes)
    }

    private companion object {
        const val KEY = "health.write.enabled.v1"
    }
}
