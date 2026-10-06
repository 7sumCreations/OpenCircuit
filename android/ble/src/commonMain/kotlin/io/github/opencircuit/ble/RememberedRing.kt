package io.github.opencircuit.ble

/**
 * One ring the user paired. The caller (the app) stores it and builds a [RingLink] from it.
 *
 * Replaces upstream's remembered peripheral id (`ios/OpenCircuit/BLE/RingScanner.swift:89-175` @ b1c2fdd):
 * Android reconnects by address, so the address and its type are what must be remembered.
 */
data class RememberedRing(
    /**
     * `BluetoothDevice.getAddress()`: upper-case, colon-separated. Also the identity a history
     * resume hint is stored under (`HistoryDrainPlan.ResumeHint.peripheralID`, PORTING.md D-31).
     */
    val address: String,
    /** The address type, needed to rebuild the device by address without a scan. */
    val addressType: AddressType,
    /** The advertised name when the ring was found, if it had one. */
    val name: String?,
) {
    /**
     * For logs: the address type only. The address is the ring's MAC and its advertised name ends
     * with two bytes of it, so neither is shown.
     */
    override fun toString(): String = "RememberedRing(addressType=$addressType, name=${if (name == null) "none" else "set"})"
}

/** The Bluetooth LE address type of a [RememberedRing]. */
enum class AddressType {
    /** A public (IEEE-assigned) device address. */
    PUBLIC,

    /** A random device address (the ring is believed to use a random static address). */
    RANDOM,
}
