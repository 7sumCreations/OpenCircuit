package io.github.opencircuit.app

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencircuit.app.data.PrefsAppPrefs
import io.github.opencircuit.app.data.PrefsRememberedRingStore
import io.github.opencircuit.app.data.SharedPreferencesKeyValues
import io.github.opencircuit.ble.AddressType
import io.github.opencircuit.ble.RememberedRing
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real SharedPreferences adapter on a device: values round trip through a real file, and a
 * value saved under one of the app's keys with another type reads as absent instead of throwing
 * (`getString` on a boolean throws `ClassCastException`). Uses its own file, never the app's.
 */
@RunWith(AndroidJUnit4::class)
class SharedPreferencesKeyValuesTest {

    private lateinit var prefs: SharedPreferences

    @Before
    fun openFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @After
    fun deleteFile() {
        prefs.edit().clear().commit()
        InstrumentationRegistry.getInstrumentation().targetContext.deleteSharedPreferences(FILE)
    }

    @Test
    fun theRingAndBothFlagsRoundTripThroughARealFile() {
        val values = SharedPreferencesKeyValues(prefs)
        val ring = RememberedRing("AA:BB:CC:DD:EE:FF", AddressType.RANDOM, "RingConn Gen2 TEST")

        assertTrue(PrefsRememberedRingStore(values).save(ring))
        assertTrue(PrefsAppPrefs(values).setOnboardingCompleted())
        assertTrue(PrefsAppPrefs(values).setPermissionAsked())

        val reopened = SharedPreferencesKeyValues(prefs)
        assertEquals(ring, PrefsRememberedRingStore(reopened).load())
        assertTrue(PrefsAppPrefs(reopened).onboardingCompleted)
        assertTrue(PrefsAppPrefs(reopened).permissionAsked)
    }

    @Test
    fun aValueOfTheWrongTypeReadsAsAbsentInsteadOfThrowing() {
        prefs.edit()
            .putBoolean("ring.remembered.v1", true)
            .putString("onboarding.completed.v1", "true")
            .putInt("bt.permission.asked.v1", 1)
            .commit()
        val values = SharedPreferencesKeyValues(prefs)

        assertNull(PrefsRememberedRingStore(values).load())
        assertFalse(PrefsAppPrefs(values).onboardingCompleted)
        assertFalse(PrefsAppPrefs(values).permissionAsked)
    }

    private companion object {
        const val FILE = "shared-preferences-key-values-test"
    }
}
