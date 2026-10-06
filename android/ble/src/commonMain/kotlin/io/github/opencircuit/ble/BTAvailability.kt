package io.github.opencircuit.ble

/**
 * Whether Bluetooth can be used right now, from the app's permission and the adapter's state.
 * Drives the app's connect card. A port of upstream's `BTAvailability`
 * (`ios/OpenCircuit/BLE/RingScanner.swift:18-43`, `:181-197` @ b1c2fdd).
 */
enum class BTAvailability {
    /** Permission granted and the adapter is not off: scanning and connecting can start. */
    READY,

    /** Permission granted but Bluetooth is off. */
    POWERED_OFF,

    /** The user denied the permission, or it is restricted. */
    DENIED,

    /** The permission has not been asked for yet. */
    NOT_DETERMINED,
    ;

    companion object {
        /**
         * The availability for a [permissions] state and an [adapterState]; `adapterState == null`
         * means the adapter state has not been read yet.
         *
         * Not built yet: it throws [NotImplementedError] until the upstream mapping lands together
         * with its six ported upstream tests (`ios/OpenCircuitTests/BTAvailabilityTests.swift:13-49`).
         */
        fun of(permissions: BluetoothPermission, adapterState: AdapterState?): BTAvailability =
            TODO("ported together with its upstream tests in a later change")
    }
}

/**
 * The app's Bluetooth permission state. Mirrors upstream's `CBManagerAuthorization` one to one;
 * the app maps Android's runtime permission state into it.
 */
enum class BluetoothPermission {
    /** Not asked yet. */
    NOT_DETERMINED,

    /** The user denied it. */
    DENIED,

    /** It cannot be granted on this device (for example, by policy). */
    RESTRICTED,

    /** Granted. */
    GRANTED,
}

/**
 * The phone's Bluetooth adapter state. [TURNING_ON] and [TURNING_OFF] stand for upstream's
 * transient `.unknown` / `.resetting` states.
 */
enum class AdapterState {
    /** Bluetooth is on. */
    ON,

    /** Bluetooth is off. */
    OFF,

    /** Bluetooth is turning on. */
    TURNING_ON,

    /** Bluetooth is turning off. */
    TURNING_OFF,
}
