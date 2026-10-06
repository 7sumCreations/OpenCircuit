package io.github.opencircuit.app.ring

import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ble.PairingFailure
import io.github.opencircuit.ble.RememberedRing

/** What the connection card offers for a link state or a step of finding a ring. */
enum class LinkAction(
    /** The button's words (also its accessibility label). */
    val label: String,
    /** The button opens a system screen or dialog; the activity handles it, not the view model. */
    val opensSystemSettings: Boolean = false,
) {
    /** Find a ring and connect to it (asks for Nearby devices first when needed). */
    SCAN_AND_CONNECT("Scan & connect"),

    /** Show Android's Nearby-devices dialog again after one refusal. */
    ALLOW_NEARBY("Allow Nearby devices"),

    /** Refused for good: open this app's page in Android's settings. */
    OPEN_APP_SETTINGS("Open settings", opensSystemSettings = true),

    /** Try the Nearby-devices dialog once more (Android answers at once if it was refused for good). */
    ASK_AGAIN("Ask again"),

    /** Stop the scan. */
    CANCEL_SCAN("Cancel"),

    /** Scan again after nothing was found or the scan failed. */
    SEARCH_AGAIN("Search again"),

    /** Go on from the app's explanation to Android's pairing sheet. */
    CONTINUE_PAIRING("Continue"),

    /** Stop a connection that is being made. */
    CANCEL("Cancel"),

    /** Ask for the bond again after pairing failed (the link does not retry on its own). */
    TRY_AGAIN("Try again"),

    /** Stop the automatic reconnects. */
    STOP_RECONNECTING("Stop reconnecting"),

    /** Open Android's Bluetooth settings, where a lost bond is forgotten. */
    BLUETOOTH_SETTINGS("Bluetooth settings", opensSystemSettings = true),

    /** Bluetooth is off: ask Android to turn it on (its own confirmation dialog). */
    TURN_ON_BLUETOOTH("Turn on Bluetooth", opensSystemSettings = true),

    /** Close the connection. */
    DISCONNECT("Disconnect"),
}

/** The connection card's words for a link state or a step of finding a ring, and its actions. */
data class LinkStateUi(
    /** The status line. */
    val headline: String,
    /** A sentence that explains it, or null when the headline says enough. */
    val detail: String?,
    /** The main button, or null when there is nothing the user can do here. */
    val action: LinkAction?,
    /** The status dot is green: the ring is connected and its data flows. */
    val connected: Boolean,
    /** A second, quieter button, or null. */
    val secondary: LinkAction? = null,
    /** Bulleted hints under the detail, in order. */
    val hints: List<String> = emptyList(),
    /** The rings to pick from, in the order shown; empty unless several rings were found. */
    val choices: List<RingChoiceUi> = emptyList(),
    /** A scan is running: drawn with a progress indicator. */
    val searching: Boolean = false,
)

/** One row of the ring picker. */
data class RingChoiceUi(
    /** The ring a tap connects to. */
    val ring: RememberedRing,
    /** The ring's advertised name, or "RingConn" when it had none (upstream's fallback, CV:1373). */
    val label: String,
    /** The ring the app last used: marked "Last used" and listed first. */
    val lastUsed: Boolean,
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
