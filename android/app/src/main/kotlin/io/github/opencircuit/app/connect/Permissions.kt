package io.github.opencircuit.app.connect

import io.github.opencircuit.ble.AdapterState
import io.github.opencircuit.ble.BTAvailability
import io.github.opencircuit.ble.BluetoothPermission

/**
 * What Android says about the Nearby-devices permission right now, read by the activity
 * ([AndroidPermissionReader]) on every resume and after every request.
 */
data class PermissionSnapshot(
    /** `BLUETOOTH_SCAN` is granted. */
    val scanGranted: Boolean,
    /** `BLUETOOTH_CONNECT` is granted. */
    val connectGranted: Boolean,
    /**
     * `shouldShowRequestPermissionRationale` for either permission: true after one refusal,
     * false before the first request and after a refusal for good.
     */
    val rationale: Boolean,
    /** A device policy forbids Bluetooth (`UserManager.DISALLOW_BLUETOOTH`). */
    val restricted: Boolean,
)

/**
 * Android's runtime-permission state mapped into `:ble`'s [BluetoothPermission] (upstream's
 * `CBManagerAuthorization`, `ios/OpenCircuit/BLE/RingScanner.swift:176-197` @ b1c2fdd).
 *
 * Android reports "never asked" and "refused for good" the same way (no grant, no rationale), so
 * the app's own "asked" flag tells them apart. The two permissions are one "Nearby devices"
 * dialog and both are needed, so one without the other is not a grant.
 */
object PermissionMapper {
    fun permission(snapshot: PermissionSnapshot, everAsked: Boolean): BluetoothPermission = when {
        snapshot.restricted -> BluetoothPermission.RESTRICTED
        snapshot.scanGranted && snapshot.connectGranted -> BluetoothPermission.GRANTED
        !everAsked -> BluetoothPermission.NOT_DETERMINED
        else -> BluetoothPermission.DENIED
    }

    /**
     * Whether the system dialog can still appear: never asked, or refused once (Android then
     * shows a rationale). After a refusal for good only Settings can grant it.
     */
    fun canAskAgain(snapshot: PermissionSnapshot, everAsked: Boolean): Boolean = when (permission(snapshot, everAsked)) {
        BluetoothPermission.NOT_DETERMINED -> true
        BluetoothPermission.DENIED -> snapshot.rationale
        BluetoothPermission.GRANTED, BluetoothPermission.RESTRICTED -> false
    }
}

/** What a Scan & connect tap does. */
enum class ScanStep {
    /** A device policy forbids Bluetooth: say so; nothing to tap. */
    BLOCKED,

    /** Show the system's Nearby-devices dialog. */
    ASK_PERMISSION,

    /** Refused for good: only Settings can grant it. */
    NOT_ALLOWED,

    /** Granted, but Bluetooth is off: offer to turn it on. */
    BLUETOOTH_OFF,

    /** Scan for rings. */
    SCAN,
}

/** The tap's step from the permission and the adapter, through `:ble`'s [BTAvailability.of]. */
object ScanGate {
    fun step(permission: BluetoothPermission, canAskAgain: Boolean, adapter: AdapterState?): ScanStep {
        // BTAvailability folds RESTRICTED into DENIED; the copy for a device policy differs.
        if (permission == BluetoothPermission.RESTRICTED) return ScanStep.BLOCKED
        return when (BTAvailability.of(permission, adapter)) {
            BTAvailability.NOT_DETERMINED -> ScanStep.ASK_PERMISSION
            BTAvailability.DENIED -> if (canAskAgain) ScanStep.ASK_PERMISSION else ScanStep.NOT_ALLOWED
            BTAvailability.POWERED_OFF -> ScanStep.BLUETOOTH_OFF
            BTAvailability.READY -> ScanStep.SCAN
        }
    }
}

/**
 * `BluetoothAdapter`'s state code (`getState()`, `EXTRA_STATE`) as `:ble`'s [AdapterState], or
 * null for any other value (the hidden low-energy-only states, a missing extra).
 */
fun adapterStateOf(code: Int): AdapterState? = when (code) {
    STATE_OFF -> AdapterState.OFF
    STATE_TURNING_ON -> AdapterState.TURNING_ON
    STATE_ON -> AdapterState.ON
    STATE_TURNING_OFF -> AdapterState.TURNING_OFF
    else -> null
}

// BluetoothAdapter.STATE_* (API 5); repeated here so the mapping is plain Kotlin.
private const val STATE_OFF = 10
private const val STATE_TURNING_ON = 11
private const val STATE_ON = 12
private const val STATE_TURNING_OFF = 13
