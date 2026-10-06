package io.github.opencircuit.app.connect

import io.github.opencircuit.app.data.AppPrefs
import io.github.opencircuit.app.data.RememberedRingStore
import io.github.opencircuit.app.data.RingAddress
import io.github.opencircuit.ble.AdapterState
import io.github.opencircuit.ble.BluetoothPermission
import io.github.opencircuit.ble.RememberedRing
import io.github.opencircuit.ble.RingScanner
import io.github.opencircuit.ble.ScanUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Builds the scanner for one Scan & connect (`:ble`'s Android scanner, or the debug demo). */
fun interface RingScannerFactory {
    fun create(): RingScanner
}

/** Connects to the ring the scan selected or the user picked. */
fun interface RingConnector {
    fun connect(ring: RememberedRing)
}

/** Where a Scan & connect stands. */
sealed interface ScanPhase {
    /** No scan running and none to report. */
    data object Idle : ScanPhase

    /** Scanning; [found] are the rings matched so far. */
    data class Scanning(val found: List<RememberedRing>) : ScanPhase

    /** Several rings: the user picks one while the scan goes on. */
    data class Choosing(val rings: List<RememberedRing>) : ScanPhase

    /** No ring within the scan's 15 s. */
    data object NoRingFound : ScanPhase

    /** Android refused to start the scan (-1) or reported a scan error. */
    data class Failed(val errorCode: Int) : ScanPhase

    /** A ring was chosen: the app says what Android's pairing sheet is for before it opens. */
    data class ConfirmPairing(val ring: RememberedRing) : ScanPhase

    /** Waiting for Android's companion-device sheet and its answer. */
    data class Pairing(val ring: RememberedRing) : ScanPhase

    /** The user refused or closed Android's sheet: nothing was remembered or connected. */
    data object PairingCancelled : ScanPhase
}

/** Everything the Ring screen needs to show about finding a ring. */
data class ConnectFlowState(
    /** The Nearby-devices permission, as `:ble` names it. */
    val permission: BluetoothPermission = BluetoothPermission.NOT_DETERMINED,
    /** The system dialog can still be shown. */
    val canAskAgain: Boolean = true,
    val phase: ScanPhase = ScanPhase.Idle,
    /** The saved ring, read when a scan starts: marks "Last used" and adds the no-ring hint. */
    val savedRing: RememberedRing? = null,
)

/**
 * Scan & connect, from the first tap to a chosen ring (upstream's `RingScanner.start` and the
 * dashboard's connect control and picker, `ios/OpenCircuit/BLE/RingScanner.swift:313-340`,
 * `ios/OpenCircuit/ContentView.swift:1267-1406` @ b1c2fdd).
 *
 * The activity owns what only an activity can do (the permission dialog, turning Bluetooth on,
 * opening Settings) and hands this controller a fresh [PermissionSnapshot] on every tap, every
 * resume and every dialog answer. A tap goes through [ScanGate]: a device policy, the dialog, a
 * refusal for good and Bluetooth off each stop it with their own state; only a ready phone scans.
 *
 * One scan runs at a time, collected in [scope]. Selected ends it; after Choose the scan goes on
 * and the picker follows each new list until the user picks a ring or cancels, which cancels the
 * collection. A scan that throws or ends without an answer is shown, never silently dropped.
 *
 * A chosen ring is not connected at once: the card first says what Android's companion-device
 * sheet is for (its own wording is generic), and Continue hands the ring to [pairing]. The sheet
 * it returns is published on [pairingSheet] for the activity to show; the answer remembers and
 * connects the ring through [connector], or ends as "Pairing cancelled".
 */
class ConnectFlowController(
    private val prefs: AppPrefs,
    private val rings: RememberedRingStore,
    private val scanners: RingScannerFactory,
    private val connector: RingConnector,
    private val adapterState: StateFlow<AdapterState?>,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val pairing: CompanionPairing,
) {
    private val stateFlow = MutableStateFlow(ConnectFlowState())
    private val sheetFlow = MutableStateFlow<PairingSheet?>(null)

    // Set once this session has asked, even when the flag could not be saved. Main-thread only.
    private var askedThisSession = false
    private var scanJob: Job? = null

    val state: StateFlow<ConnectFlowState> = stateFlow.asStateFlow()

    /** Android's pairing sheet, waiting for the activity to show it; null when there is none. */
    val pairingSheet: StateFlow<PairingSheet?> = sheetFlow.asStateFlow()

    /** Continue on the explanation: ask Android's companion-device manager for the chosen ring. */
    fun continuePairing() {
        val ring = (stateFlow.value.phase as? ScanPhase.ConfirmPairing)?.ring ?: return
        stateFlow.update { it.copy(phase = ScanPhase.Pairing(ring)) }
        pairing.begin(ring, showSheet = { sheetFlow.value = it }) { outcome ->
            sheetFlow.value = null
            if (outcome.connects) {
                stateFlow.update { it.copy(phase = ScanPhase.Idle) }
                connector.connect(ring)
            } else {
                stateFlow.update { it.copy(phase = ScanPhase.PairingCancelled) }
            }
        }
    }

    /** The activity showed the sheet: it is not shown again. */
    fun onPairingSheetShown() {
        sheetFlow.value = null
    }

    /** The sheet closed with [resultCode] (the activity result's code). */
    fun onPairingSheetResult(resultCode: Int) {
        pairing.onSheetResult(resultCode)
    }

    /** The activity could not show the sheet. */
    fun onPairingSheetFailed() {
        sheetFlow.value = null
        pairing.onSheetNotShown()
    }

    /** The activity resumed: re-read the permission (a grant made in Settings has no callback). */
    fun onResume(snapshot: PermissionSnapshot) {
        refreshPermission(snapshot)
    }

    /**
     * Scan & connect, Search again or Allow Nearby devices. Returns the step taken; on
     * [ScanStep.ASK_PERMISSION] the activity shows the system dialog and reports back through
     * [onPermissionResult].
     */
    fun requestScan(snapshot: PermissionSnapshot): ScanStep {
        refreshPermission(snapshot)
        if (scanning) return ScanStep.SCAN
        val state = stateFlow.value
        val step = ScanGate.step(state.permission, state.canAskAgain, adapterState.value)
        if (step == ScanStep.SCAN) startScan()
        return step
    }

    /** The Nearby-devices dialog answered: remember that it was asked, then scan if the phone is ready. */
    fun onPermissionResult(snapshot: PermissionSnapshot) {
        askedThisSession = true
        if (!prefs.setPermissionAsked()) log("Could not save that Nearby devices was asked for")
        refreshPermission(snapshot)
        val state = stateFlow.value
        // Never shows the dialog again by itself: a refusal stays on the card until the user taps.
        if (ScanGate.step(state.permission, state.canAskAgain, adapterState.value) == ScanStep.SCAN) startScan()
    }

    /** Android turned Bluetooth on at the user's request: carry on with the scan they tapped for. */
    fun onBluetoothEnabled(snapshot: PermissionSnapshot) {
        requestScan(snapshot)
    }

    /** Cancel: stop the scan, or the pairing that followed it; nothing is connected. */
    fun cancelScan() {
        stopScan()
        stateFlow.update { it.copy(phase = ScanPhase.Idle) }
    }

    /** The user picked [ring] from the list: stop the scan and pair it. A ring not on the list is ignored. */
    fun pick(ring: RememberedRing) {
        val phase = stateFlow.value.phase as? ScanPhase.Choosing ?: return
        val chosen = phase.rings.firstOrNull { RingAddress.same(it.address, ring.address) } ?: return
        stopScan()
        stateFlow.update { it.copy(phase = ScanPhase.ConfirmPairing(chosen)) }
    }

    private val scanning: Boolean
        get() = stateFlow.value.phase.let { it is ScanPhase.Scanning || it is ScanPhase.Choosing }

    private fun refreshPermission(snapshot: PermissionSnapshot) {
        val asked = askedThisSession || prefs.permissionAsked
        stateFlow.update {
            it.copy(permission = PermissionMapper.permission(snapshot, asked), canAskAgain = PermissionMapper.canAskAgain(snapshot, asked))
        }
    }

    private fun startScan() {
        stopScan()
        stateFlow.update { it.copy(phase = ScanPhase.Scanning(emptyList()), savedRing = rings.load()) }
        scanJob = scope.launch {
            var answered = false
            try {
                scanners.create().scan().collect { update ->
                    when (update) {
                        is ScanUpdate.Found -> stateFlow.update { s ->
                            // While the picker shows, the scan goes on and the list follows it.
                            val phase = if (s.phase is ScanPhase.Choosing) ScanPhase.Choosing(update.rings) else ScanPhase.Scanning(update.rings)
                            s.copy(phase = phase)
                        }
                        is ScanUpdate.Choose -> stateFlow.update { it.copy(phase = ScanPhase.Choosing(update.rings)) }
                        is ScanUpdate.Selected -> {
                            answered = true
                            stateFlow.update { it.copy(phase = ScanPhase.ConfirmPairing(update.ring)) }
                        }
                        ScanUpdate.NoRingFound -> {
                            answered = true
                            stateFlow.update { it.copy(phase = ScanPhase.NoRingFound) }
                        }
                        is ScanUpdate.Failed -> {
                            answered = true
                            log("Ring scan failed with code ${update.errorCode}")
                            stateFlow.update { it.copy(phase = ScanPhase.Failed(update.errorCode)) }
                        }
                    }
                }
                if (!answered) stateFlow.update { it.copy(phase = ScanPhase.NoRingFound) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The scanner broke its contract; say the scan failed rather than hang on "Searching".
                log("Ring scan stopped with ${e::class.simpleName}")
                stateFlow.update { it.copy(phase = ScanPhase.Failed(SCAN_NOT_STARTED)) }
            }
        }
    }

    /** Ends whatever runs: the scan's collection, and a pairing request still waiting (a late answer changes nothing). */
    private fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        pairing.abandon()
        sheetFlow.value = null
    }

    private companion object {
        /** `ScanUpdate.Failed`'s code for a scan Android would not start. */
        const val SCAN_NOT_STARTED = -1
    }
}
