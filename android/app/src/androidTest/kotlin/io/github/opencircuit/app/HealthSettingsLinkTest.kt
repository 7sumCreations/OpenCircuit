package io.github.opencircuit.app

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencircuit.app.health.HealthSettingsLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The card's "Open Health Connect settings" / "Change in Health Connect" link resolves on the
 * platform: an activity of the phone takes the exact intent the app starts (Health Connect's
 * page for one app), with this app's package named.
 */
@RunWith(AndroidJUnit4::class)
class HealthSettingsLinkTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theSettingsLinkOpensAnActivityOnThePlatform() {
        val intent = HealthSettingsLink.intent(context.packageName)

        assertEquals("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS", intent.action)
        assertEquals(context.packageName, intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME))
        assertNotNull(
            "nothing on the platform takes the link",
            context.packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())),
        )
    }
}
