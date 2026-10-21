package io.github.opencircuit.app

import io.github.opencircuit.app.data.PrefsAppPrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    /** Today's two saved orders: unset at first, kept across a relaunch under their own keys, a non-string read as unset. */
    @Test
    fun todaysOrdersAreKeptAcrossARelaunchAndADamagedOneReadsAsUnset() {
        assertNull(prefs.todaySectionOrder)
        assertNull(prefs.todayTileOrder)

        assertTrue(prefs.setTodaySectionOrder("goals,readiness"))
        assertTrue(prefs.setTodayTileOrder("steps,hrv"))

        assertEquals("goals,readiness", PrefsAppPrefs(values).todaySectionOrder)
        assertEquals("steps,hrv", PrefsAppPrefs(values).todayTileOrder)
        assertEquals("goals,readiness", values.raw(SECTION_ORDER))
        assertEquals("steps,hrv", values.raw(TILE_ORDER))

        values.putRaw(SECTION_ORDER, 7)
        values.putRaw(TILE_ORDER, true)
        assertNull(prefs.todaySectionOrder)
        assertNull(prefs.todayTileOrder)
    }

    /**
     * Sending to Health Connect: OFF when the key is absent — a fresh install and an install upgraded
     * from a version without the switch alike — and OFF when the stored value is damaged, so nothing
     * leaves the app unless the user turned it on.
     */
    @Test
    fun healthWritingIsOffUntilTurnedOnAndADamagedValueReadsOff() {
        assertFalse(prefs.healthWriteEnabled, "absent reads OFF")

        assertTrue(prefs.setHealthWriteEnabled(true))
        assertTrue(PrefsAppPrefs(values).healthWriteEnabled, "kept across a relaunch")
        assertEquals(true, values.raw(HEALTH_WRITE))

        assertTrue(prefs.setHealthWriteEnabled(false))
        assertFalse(PrefsAppPrefs(values).healthWriteEnabled)
        assertEquals(false, values.raw(HEALTH_WRITE))

        values.putRaw(HEALTH_WRITE, "true")
        assertFalse(prefs.healthWriteEnabled, "a string is damaged: OFF")
        values.putRaw(HEALTH_WRITE, 1)
        assertFalse(prefs.healthWriteEnabled, "a number is damaged: OFF")
    }

    @Test
    fun aFailedSwitchWriteIsReportedAndWritingStaysOff() {
        values.failWrites = true

        assertFalse(prefs.setHealthWriteEnabled(true))
        assertFalse(prefs.healthWriteEnabled)
    }

    /** The upgrade notice's "dismissed" flag: unset at first, kept once set, damaged reads unset; independent of the switch. */
    @Test
    fun theUpgradeNoticeDismissalIsKeptAcrossARelaunch() {
        assertFalse(prefs.healthUpgradeNoticeDismissed)

        assertTrue(prefs.setHealthUpgradeNoticeDismissed())
        assertTrue(PrefsAppPrefs(values).healthUpgradeNoticeDismissed)
        assertEquals(true, values.raw(NOTICE_DISMISSED))
        assertFalse(prefs.healthWriteEnabled, "dismissing the notice does not turn writing on")

        values.putRaw(NOTICE_DISMISSED, "yes")
        assertFalse(prefs.healthUpgradeNoticeDismissed)

        values.failWrites = true
        values.putRaw(NOTICE_DISMISSED, false)
        assertFalse(prefs.setHealthUpgradeNoticeDismissed())
    }

    private companion object {
        /** The keys, typed here rather than read from the code. */
        const val ONBOARDING = "onboarding.completed.v1"
        const val ASKED = "bt.permission.asked.v1"
        const val SECTION_ORDER = "today.sectionOrder.v1"
        const val TILE_ORDER = "today.tileOrder.v1"
        const val HEALTH_WRITE = "health.write.enabled.v1"
        const val NOTICE_DISMISSED = "health.upgradeNotice.dismissed.v1"
    }
}
