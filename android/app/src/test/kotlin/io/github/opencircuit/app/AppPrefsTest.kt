package io.github.opencircuit.app

import io.github.opencircuit.app.data.PrefsAppPrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two flags: `onboarding.completed.v1` and `bt.permission.asked.v1`. Each starts false,
 * stays true once set (also for a new instance over the same file, as after a relaunch), and a
 * damaged value reads as false: onboarding shows again, and the Nearby-devices dialog is asked
 * for again (Android answers at once if the user had refused for good).
 */
class AppPrefsTest {

    private val values = InMemoryKeyValues()
    private val prefs = PrefsAppPrefs(values)

    @Test
    fun bothFlagsStartFalse() {
        assertFalse(prefs.onboardingCompleted)
        assertFalse(prefs.permissionAsked)
    }

    @Test
    fun onboardingCompletedIsKeptAcrossARelaunch() {
        assertTrue(prefs.setOnboardingCompleted())

        assertTrue(prefs.onboardingCompleted)
        assertTrue(PrefsAppPrefs(values).onboardingCompleted)
        assertEquals(true, values.raw(ONBOARDING))
        assertFalse(prefs.permissionAsked, "one flag does not set the other")
    }

    @Test
    fun permissionAskedIsKeptAcrossARelaunch() {
        assertTrue(prefs.setPermissionAsked())

        assertTrue(PrefsAppPrefs(values).permissionAsked)
        assertEquals(true, values.raw(ASKED))
        assertFalse(prefs.onboardingCompleted)
    }

    @Test
    fun aDamagedFlagReadsAsNotSet() {
        values.putRaw(ONBOARDING, "true")
        values.putRaw(ASKED, 1)

        assertFalse(prefs.onboardingCompleted)
        assertFalse(prefs.permissionAsked)
    }

    @Test
    fun aFlagSavedFalseReadsFalse() {
        values.putRaw(ONBOARDING, false)

        assertFalse(prefs.onboardingCompleted)
    }

    @Test
    fun aFailedWriteIsReportedAndTheFlagStaysUnset() {
        values.failWrites = true

        assertFalse(prefs.setOnboardingCompleted())
        assertFalse(prefs.setPermissionAsked())
        assertFalse(prefs.onboardingCompleted)
        assertFalse(prefs.permissionAsked)
    }

    private companion object {
        /** The keys, typed here rather than read from the code. */
        const val ONBOARDING = "onboarding.completed.v1"
        const val ASKED = "bt.permission.asked.v1"
    }
}
