package io.github.opencircuit.app.connect

import io.github.opencircuit.app.data.RingAddress
import io.github.opencircuit.app.ring.LinkAction
import io.github.opencircuit.app.ring.LinkStateUi
import io.github.opencircuit.app.ring.RingChoiceUi
import io.github.opencircuit.ble.AdapterState
import io.github.opencircuit.ble.BluetoothPermission
import io.github.opencircuit.ble.RememberedRing

/**
 * The connection card's words while no link is active (what a tap would do now) and while a scan
 * runs. Upstream's connect control, no-ring card and picker
 * (`ios/OpenCircuit/ContentView.swift:1267-1336, 1353-1406` @ b1c2fdd), in Android's words: the
 * permission is "Nearby devices", Bluetooth is turned on by Android's own dialog. The ring's name
 * is the only value shown; nothing here prints a `:ble` value's `toString`.
 */
object ConnectFlowPresenter {

    /** The card before a tap: ready, blocked by policy, not allowed, or Bluetooth off. */
    fun availability(state: ConnectFlowState, adapter: AdapterState?): LinkStateUi =
        when (ScanGate.step(state.permission, state.canAskAgain, adapter)) {
            ScanStep.BLOCKED -> LinkStateUi(
                "Bluetooth is blocked by a device policy",
                "Whoever manages this phone has turned Bluetooth off for apps, so OpenCircuit can't reach the ring.",
                action = null,
                connected = false,
            )
            // Never asked: the tap shows the dialog. Asked and refused once: say so first.
            ScanStep.ASK_PERMISSION -> if (state.permission == BluetoothPermission.DENIED) {
                LinkStateUi(
                    NOT_ALLOWED,
                    "OpenCircuit needs Nearby devices to find and connect to your ring. It never uses it for location.",
                    LinkAction.ALLOW_NEARBY,
                    connected = false,
                )
            } else {
                READY
            }
            ScanStep.NOT_ALLOWED -> LinkStateUi(
                NOT_ALLOWED,
                "OpenCircuit needs Nearby devices to find your ring. Allow it in Settings › Apps › OpenCircuit › Permissions, " +
                    "then come back and tap Scan & connect.",
                LinkAction.OPEN_APP_SETTINGS,
                connected = false,
                secondary = LinkAction.ASK_AGAIN,
            )
            ScanStep.BLUETOOTH_OFF -> LinkStateUi("Bluetooth off", null, LinkAction.TURN_ON_BLUETOOTH, connected = false)
            ScanStep.SCAN -> READY
        }

    /** The card for a scan in progress or just ended, or null when there is none ([ScanPhase.Idle]). */
    fun scan(state: ConnectFlowState): LinkStateUi? = when (val phase = state.phase) {
        ScanPhase.Idle -> null
        is ScanPhase.Scanning -> LinkStateUi("Searching for ring…", null, LinkAction.CANCEL_SCAN, connected = false, searching = true)
        is ScanPhase.Choosing -> LinkStateUi(
            "Multiple rings found — pick one",
            null,
            LinkAction.CANCEL_SCAN,
            connected = false,
            choices = choices(phase.rings, state.savedRing),
            searching = true,
        )
        ScanPhase.NoRingFound -> LinkStateUi(
            "No ring found",
            null,
            LinkAction.SEARCH_AGAIN,
            connected = false,
            hints = NO_RING_HINTS + listOfNotNull(SAVED_RING_HINT.takeIf { state.savedRing != null }),
        )
        is ScanPhase.Failed -> LinkStateUi(
            "Couldn't search for rings",
            if (phase.errorCode == SCAN_NOT_STARTED) {
                "Android didn't start the scan. Check that Bluetooth is on and Nearby devices is allowed, then search again."
            } else {
                "Android reported a scan error (code ${phase.errorCode}). Search again."
            },
            LinkAction.SEARCH_AGAIN,
            connected = false,
        )
        is ScanPhase.ConfirmPairing -> LinkStateUi(
            "Pair with ${label(phase.ring)}",
            PAIRING_EXPLAINED,
            LinkAction.CONTINUE_PAIRING,
            connected = false,
            secondary = LinkAction.CANCEL_SCAN,
        )
        is ScanPhase.Pairing -> LinkStateUi(
            "Waiting for Android's pairing sheet…",
            "Tap Allow when Android asks about ${label(phase.ring)}.",
            LinkAction.CANCEL_SCAN,
            connected = false,
            searching = true,
        )
        is ScanPhase.PairingCancelled -> LinkStateUi(
            "Pairing cancelled",
            "OpenCircuit didn't connect to the ring. Tap Scan & connect to try again. If Android's sheet " +
                "can't find your ring, pair without it: Android asks you to confirm the pairing instead.",
            LinkAction.SCAN_AND_CONNECT,
            connected = false,
            secondary = LinkAction.PAIR_WITHOUT_SHEET,
        )
    }

    /** The ring's advertised name, or "RingConn" when it had none. */
    private fun label(ring: RememberedRing): String = ring.name?.takeIf(String::isNotEmpty) ?: UNNAMED

    /**
     * Said before Android's companion-device sheet opens: the sheet's own summary is Android's
     * generic text for any companion device and cannot be changed.
     */
    private const val PAIRING_EXPLAINED =
        "Android will ask you to allow OpenCircuit to access this ring. Its message mentions syncing info like the " +
            "name of someone calling: that is Android's standard wording for any companion device. OpenCircuit only " +
            "reads the ring's own data, on this phone. Allowing it usually lets the pairing finish without another prompt."

    /**
     * The picker's rows: the saved ring first (marked "Last used"), then by name, then by
     * address. A stable order, not signal strength, so rows do not jump while the scan goes on
     * (upstream's rule, CV:1386-1398). A ring with no name sorts as an empty name and shows as
     * "RingConn".
     */
    fun choices(rings: List<RememberedRing>, savedRing: RememberedRing?): List<RingChoiceUi> {
        fun isSaved(ring: RememberedRing) = savedRing != null && RingAddress.same(ring.address, savedRing.address)
        return rings
            .sortedWith(compareBy<RememberedRing> { !isSaved(it) }.thenBy { it.name.orEmpty() }.thenBy { RingAddress.normalized(it.address) ?: it.address })
            .map { RingChoiceUi(ring = it, label = label(it), lastUsed = isSaved(it)) }
    }

    private const val NOT_ALLOWED = "Bluetooth not allowed"
    private const val UNNAMED = "RingConn"
    private const val SCAN_NOT_STARTED = -1

    private val READY = LinkStateUi("Ready", null, LinkAction.SCAN_AND_CONNECT, connected = false)

    private val NO_RING_HINTS = listOf(
        "Take the ring out of its charging case and put it on.",
        "Force-stop the official RingConn app — it can hold the Bluetooth connection.",
        "Keep the ring within a few feet of your phone.",
    )

    private const val SAVED_RING_HINT = "A ring you've paired before reconnects automatically once it's back in range."
}
