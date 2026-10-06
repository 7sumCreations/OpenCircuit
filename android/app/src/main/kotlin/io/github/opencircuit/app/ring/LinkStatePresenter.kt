package io.github.opencircuit.app.ring

import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ble.PairingFailure

/** The one thing the connection card offers for a link state. */
enum class LinkAction(
    /** The button's words (also its accessibility label). */
    val label: String,
    /** The button leaves the app for a system screen; the activity handles it, not the view model. */
    val opensSystemSettings: Boolean = false,
) {
    /** Find a ring and connect to it. */
    SCAN_AND_CONNECT("Scan & connect"),

    /** Stop a connection that is being made. */
    CANCEL("Cancel"),

    /** Ask for the bond again after pairing failed (the link does not retry on its own). */
    TRY_AGAIN("Try again"),

    /** Stop the automatic reconnects. */
    STOP_RECONNECTING("Stop reconnecting"),

    /** Open Android's Bluetooth settings, where a lost bond is forgotten. */
    BLUETOOTH_SETTINGS("Bluetooth settings", opensSystemSettings = true),

    /** Bluetooth is off: send the user to turn it on. */
    TURN_ON_BLUETOOTH("Turn on Bluetooth", opensSystemSettings = true),

    /** Close the connection. */
    DISCONNECT("Disconnect"),
}

/** The connection card's words for a link state, and its one action. */
data class LinkStateUi(
    /** The status line. */
    val headline: String,
    /** A sentence that explains it, or null when the headline says enough. */
    val detail: String?,
    val action: LinkAction,
    /** The status dot is green: the ring is connected and its data flows. */
    val connected: Boolean,
)

/**
 * The words and the action for each [LinkState]: upstream's `statusText` and its hints
 * (`ios/OpenCircuit/ContentView.swift:2227-2247, 1411-1424` @ b1c2fdd), rewritten for the
 * link's own states. One `when` over every state, so a new state does not compile until it has
 * words. Nothing here prints a `:ble` value; the ring's name is the only value in the copy.
 */
object LinkStatePresenter {

    fun present(state: LinkState, ringName: String): LinkStateUi = when (state) {
        LinkState.Idle -> LinkStateUi("Ready", null, LinkAction.SCAN_AND_CONNECT, connected = false)
        LinkState.Connecting -> connecting(ringName, null)
        LinkState.Discovering -> connecting(ringName, "Finding the ring's services…")
        LinkState.Preparing -> connecting(ringName, "Setting up the connection…")
        LinkState.Authenticating -> connecting(ringName, "Checking the ring's identity…")
        LinkState.PairingNeeded -> LinkStateUi(
            "Pairing with $ringName…",
            "Confirm the pairing request — it may appear as a notification.",
            LinkAction.CANCEL,
            connected = false,
        )
        is LinkState.PairingFailed -> LinkStateUi("Pairing didn't finish", pairingFailed(state.reason), LinkAction.TRY_AGAIN, connected = false)
        LinkState.Authenticated -> LinkStateUi("Connected", null, LinkAction.DISCONNECT, connected = true)
        // The connection stays open and the ring's first data frame moves the link on.
        LinkState.NotStreaming -> LinkStateUi(
            "Ring isn't streaming",
            "Connected, but the ring hasn't sent data yet. Check it's on your finger and off the charger, and " +
                "force-stop the official RingConn app — only one app can pull the ring at a time.",
            LinkAction.DISCONNECT,
            connected = false,
        )
        is LinkState.Reconnecting -> LinkStateUi(UNREACHABLE, null, LinkAction.STOP_RECONNECTING, connected = false)
        LinkState.WaitingForRing -> LinkStateUi(
            UNREACHABLE,
            "Waiting for the ring to come back in range.",
            LinkAction.STOP_RECONNECTING,
            connected = false,
        )
        // The link keeps reconnecting meanwhile; only the user can clear the bond, in Settings.
        LinkState.BondLostSuspected -> LinkStateUi(
            "Ring forgot this phone",
            "Forget the ring in Android's Bluetooth settings, then pair again here. Until then the app keeps trying to reconnect.",
            LinkAction.BLUETOOTH_SETTINGS,
            connected = false,
        )
        LinkState.BluetoothOff -> LinkStateUi("Bluetooth off", null, LinkAction.TURN_ON_BLUETOOTH, connected = false)
    }

    private fun connecting(ringName: String, detail: String?) =
        LinkStateUi("Connecting to $ringName…", detail, LinkAction.CANCEL, connected = false)

    private fun pairingFailed(reason: PairingFailure): String = when (reason) {
        PairingFailure.BOND_REQUEST_REJECTED ->
            "Android wouldn't start pairing with the ring. Turn Bluetooth off and on, then try again."
        PairingFailure.BOND_NOT_COMPLETED ->
            "The ring wasn't paired. Make sure it's on your finger and off the charger, then try again."
        PairingFailure.BOND_TIMED_OUT ->
            "The pairing request wasn't confirmed in time. Keep the ring close, then try again."
    }

    private const val UNREACHABLE = "Ring unreachable — reconnecting automatically"
}
