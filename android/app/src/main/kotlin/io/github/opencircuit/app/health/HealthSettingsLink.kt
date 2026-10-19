package io.github.opencircuit.app.health

import android.content.Intent
import android.health.connect.HealthConnectManager

/**
 * Health Connect's own permission page for one app: where the user changes what OpenCircuit may
 * write, and the way back after refusing everything (asking again may be dropped without
 * anything shown). Platform Health Connect, Android 14 and later.
 */
object HealthSettingsLink {
    fun intent(packageName: String): Intent =
        Intent(HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS)
            .putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
}
