package io.github.opencircuit.app

import android.content.pm.FeatureInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the installed app declares, read back from the package manager (the merged manifest as
 * Android sees it, not the source file).
 *
 * The two Nearby-devices permissions, scan flagged never-for-location, and the twelve Health
 * Connect WRITE permissions; no network, notification, legacy Bluetooth or location permission. The companion-device feature is
 * declared not required, so a phone without it can still install the app (pairing then falls
 * back to the plain bond), and it is still in the package's requested features, which is the
 * list the companion device manager checks before it accepts an association request.
 */
@RunWith(AndroidJUnit4::class)
class ManifestDeclarationsTest {

    private val info: PackageInfo = InstrumentationRegistry.getInstrumentation().targetContext.let { context ->
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of((PackageManager.GET_PERMISSIONS or PackageManager.GET_CONFIGURATIONS).toLong()),
        )
    }

    private val requested: List<String> = info.requestedPermissions?.toList().orEmpty()

    @Test
    fun theTwoNearbyDevicesPermissionsAreRequestedAndScanNeverDerivesLocation() {
        assertTrue(requested.toString(), SCAN in requested)
        assertTrue(requested.toString(), CONNECT in requested)
        val scanFlags = info.requestedPermissionsFlags!![requested.indexOf(SCAN)]
        assertTrue("BLUETOOTH_SCAN must carry neverForLocation", scanFlags and PackageInfo.REQUESTED_PERMISSION_NEVER_FOR_LOCATION != 0)
    }

    @Test
    fun noNetworkNotificationLegacyBluetoothOrLocationPermission() {
        val forbidden = listOf(
            "android.permission.INTERNET",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.BLUETOOTH",
            "android.permission.BLUETOOTH_ADMIN",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            "android.permission.BLUETOOTH_ADVERTISE",
            "android.permission.REQUEST_COMPANION_RUN_IN_BACKGROUND",
            "android.permission.REQUEST_COMPANION_USE_DATA_IN_BACKGROUND",
        )
        for (permission in forbidden) assertFalse("$permission must not be requested: $requested", permission in requested)
    }

    @Test
    fun everyRequestedPermissionIsAccountedFor() {
        // Besides the two above: the twelve Health Connect WRITE permissions (no READ one), and
        // androidx.core's guard for receivers registered not-exported on older releases, named
        // after the package.
        val others = requested - setOf(SCAN, CONNECT) - HEALTH_WRITES.toSet()
        assertEquals(listOf("${info.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"), others)
        assertTrue(requested.toString(), requested.containsAll(HEALTH_WRITES))
    }

    @Test
    fun theCompanionDeviceFeatureIsDeclaredNotRequiredAndStillRequested() {
        val feature: FeatureInfo? = info.reqFeatures?.firstOrNull { it.name == COMPANION_SETUP }
        assertNotNull("reqFeatures must hold $COMPANION_SETUP: ${info.reqFeatures?.map { it.name }}", feature)
        assertEquals("not required, so a phone without it can install", 0, feature!!.flags and FeatureInfo.FLAG_REQUIRED)
    }

    private companion object {
        const val SCAN = "android.permission.BLUETOOTH_SCAN"
        const val CONNECT = "android.permission.BLUETOOTH_CONNECT"
        const val COMPANION_SETUP = "android.software.companion_device_setup"
        val HEALTH_WRITES = listOf(
            "android.permission.health.WRITE_HEART_RATE",
            "android.permission.health.WRITE_RESTING_HEART_RATE",
            "android.permission.health.WRITE_HEART_RATE_VARIABILITY",
            "android.permission.health.WRITE_OXYGEN_SATURATION",
            "android.permission.health.WRITE_RESPIRATORY_RATE",
            "android.permission.health.WRITE_SKIN_TEMPERATURE",
            "android.permission.health.WRITE_STEPS",
            "android.permission.health.WRITE_DISTANCE",
            "android.permission.health.WRITE_ACTIVE_CALORIES_BURNED",
            "android.permission.health.WRITE_BASAL_METABOLIC_RATE",
            "android.permission.health.WRITE_SLEEP",
            "android.permission.health.WRITE_MENSTRUATION",
        )
    }
}
