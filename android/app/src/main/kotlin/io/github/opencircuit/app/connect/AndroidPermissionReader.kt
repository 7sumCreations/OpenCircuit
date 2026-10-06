package io.github.opencircuit.app.connect

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.UserManager

/** Reads the Nearby-devices permission as it stands now. */
fun interface PermissionReader {
    fun read(): PermissionSnapshot
}

/**
 * [PermissionReader] for an activity: both runtime permissions, the rationale flag (only an
 * activity can read it) and the device policy that forbids Bluetooth. Read on every resume, every
 * tap and every dialog answer; nothing is cached.
 */
class AndroidPermissionReader(private val activity: Activity) : PermissionReader {
    override fun read(): PermissionSnapshot = PermissionSnapshot(
        scanGranted = granted(Manifest.permission.BLUETOOTH_SCAN),
        connectGranted = granted(Manifest.permission.BLUETOOTH_CONNECT),
        rationale = NEARBY_DEVICES.any(activity::shouldShowRequestPermissionRationale),
        restricted = activity.getSystemService(UserManager::class.java)?.hasUserRestriction(UserManager.DISALLOW_BLUETOOTH) == true,
    )

    private fun granted(permission: String) = activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** Both are one "Nearby devices" dialog; the app needs both. */
        val NEARBY_DEVICES: Array<String> = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    }
}
