package io.github.opencircuit.ble

import java.time.Duration

/**
 * Where the link to one ring stands. Published by [RingLink.state]; only the link's own event
 * loop changes it.
 *
 * Replaces upstream's separate flags `ready`, `notifySubscribed`, `notStreaming` and
 * `gotDataFrame` (`ios/OpenCircuit/BLE/RingSession.swift:98-111` @ b1c2fdd), which could take
 * combinations that mean nothing; here exactly one state holds at a time.
 */
sealed interface LinkState {
    /** No connection and none being made: before the first `connect()` and after `disconnect()`. */
    data object Idle : LinkState

    /** The GATT connection is being opened. */
    data object Connecting : LinkState

    /** Connected; the ring's services are being discovered. */
    data object Discovering : LinkState

    /** The ring is not bonded and the bond is being made; Android may be showing a pairing prompt. */
    data object PairingNeeded : LinkState

    /** The bond could not be made; the link is closed and is not retried on its own. */
    data class PairingFailed(
        /** Why the bond failed. */
        val reason: PairingFailure,
    ) : LinkState

    /** Preparing the link: the MTU exchange, enabling notifications, and the Device Information reads. */
    data object Preparing : LinkState

    /** `01 00 00` was written; waiting for the ring's challenge to be answered and data to flow. */
    data object Authenticating : LinkState

    /** The ring sent a frame other than `0x81` after the auth exchange: its data path is open. */
    data object Authenticated : LinkState

    /**
     * Only `0x81` frames for 10 s after notifications were enabled: the symptom of a ring that
     * did not accept this phone (not bonded, or the auth reply was wrong). The connection stays
     * open; the ring's first data frame still moves the link on to [Authenticated].
     */
    data object NotStreaming : LinkState

    /** The link dropped or failed; the next by-address attempt is scheduled. */
    data class Reconnecting(
        /** Which attempt this is, counted since the link last stayed up. */
        val attempt: Int,
        /** How long until the attempt. */
        val delay: Duration,
    ) : LinkState

    /** A standing background connection (`autoConnect = true`) is armed and waits for the ring to come in range. */
    data object WaitingForRing : LinkState

    /**
     * The phone reports the ring bonded, but connections keep dropping right away: the bond was
     * probably lost on the ring's side. Recovery is a user action in system Settings; the app never
     * removes a bond itself.
     */
    data object BondLostSuspected : LinkState

    /** Bluetooth is off on the phone. */
    data object BluetoothOff : LinkState
}

/** Why making the bond failed (see [LinkState.PairingFailed]). */
enum class PairingFailure {
    /** `createBond()` returned false: Android would not start the bond. */
    BOND_REQUEST_REJECTED,

    /** The bond started and went back to "not bonded" (for example, the user declined the prompt). */
    BOND_NOT_COMPLETED,

    /** No bond within 40 s. */
    BOND_TIMED_OUT,
}
