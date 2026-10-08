package io.github.opencircuit.app.ring

import androidx.lifecycle.ViewModel
import io.github.opencircuit.app.connect.ConnectFlowController
import io.github.opencircuit.app.connect.ConnectFlowPresenter
import io.github.opencircuit.app.connect.ConnectFlowState
import io.github.opencircuit.app.connect.PairingSheet
import io.github.opencircuit.app.connect.PermissionSnapshot
import io.github.opencircuit.app.connect.ScanStep
import io.github.opencircuit.app.data.AppPrefs
import io.github.opencircuit.app.details.ConnectionDetailsPresenter
import io.github.opencircuit.app.details.ConnectionDetailsUi
import io.github.opencircuit.app.details.DetailsInput
import io.github.opencircuit.app.details.DetailsSources
import io.github.opencircuit.app.live.LiveMeasureState
import io.github.opencircuit.app.live.MeasureUi
import io.github.opencircuit.app.live.measureUi
import io.github.opencircuit.app.session.KeepaliveProblem
import io.github.opencircuit.app.session.RingSessionController
import io.github.opencircuit.app.session.SessionHost
import io.github.opencircuit.app.sync.SyncState
import io.github.opencircuit.ble.AdapterState
import io.github.opencircuit.ble.LinkDiagnostic
import io.github.opencircuit.ble.LinkDiagnostics
import io.github.opencircuit.ble.LinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant

/** Everything the Ring screen shows. */
data class RingUiState(
    /** The screen title. */
    val title: String,
    /** The connection card: the link's state or the scan's, its actions, the battery. */
    val card: ConnectionCardUi,
    /** The Measure cards and the Live card; null unless the link is authenticated. */
    val measure: MeasureUi? = null,
    /** The Connection details card. */
    val details: ConnectionDetailsUi = ConnectionDetailsPresenter.present(DetailsInput()),
    /** The Ring data card: the last sync, Sync now and the "Disconnect after syncing" switch. */
    val ringData: RingDataUi = RingDataPresenter.present(sync = null, disconnectAfterSync = true, now = Instant.EPOCH),
)

/**
 * Hosts the Ring screen's state: maps the current session's link state, device status, keepalive
 * and live measure, and the Scan & connect flow, into one [RingUiState]. [scope] becomes the view
 * model's scope (the app passes a main-thread scope; tests pass a virtual-time one). Opening the
 * screen reconnects the remembered ring, by its address, with no scan. The session can come and
 * go while the screen is open: a pairing starts one, Stop reconnecting ends it.
 *
 * While a scan runs or has just ended, the card shows the scan. Otherwise, while the link is idle
 * (or there is no link yet), it shows what a Scan & connect tap would do now: ready, Nearby
 * devices not allowed, Bluetooth off, or blocked by a device policy, following [adapterState]
 * live. Any other link state shows the link's own words.
 *
 * The Connection details card follows the current session's link facts, timings, counters and
 * diagnostics (when the link keeps them), the pairing sheet's outcome and the scanner's last
 * match ([details]).
 */
class RingViewModel(
    private val sessions: SessionHost?,
    title: String,
    scope: CoroutineScope,
    private val connectFlow: ConnectFlowController? = null,
    adapterState: StateFlow<AdapterState?> = MutableStateFlow(null),
    details: DetailsSources = DetailsSources(),
    /** Where the "Disconnect after syncing" switch is kept; null keeps it in memory (ON). */
    private val prefs: AppPrefs? = null,
    /** The phone's wall clock, for "Last synced N ago". */
    private val wallClock: () -> Instant = Instant::now,
) : ViewModel(scope) {

    /** The Ring screen's state. */
    val uiState: StateFlow<RingUiState>

    /** The session the screen shows now, or null. */
    private val controller: RingSessionController? get() = sessions?.current?.value

    /** The switch as shown; follows what was saved. */
    private val disconnectAfterSync = MutableStateFlow(prefs?.disconnectAfterSync ?: true)

    init {
        fun render(parts: LinkParts, flow: ConnectFlowState, adapter: AdapterState?, detailsInput: DetailsInput, disconnectAfter: Boolean): RingUiState {
            val measuring = parts.live.mode != null
            val flowCard = ConnectFlowPresenter.scan(flow)
                ?: if (parts.link == LinkState.Idle) ConnectFlowPresenter.availability(flow, adapter) else null
            return RingUiState(
                title = title,
                card = connectionCardUi(parts.link, parts.ringName, parts.status, measuring, parts.problem, flowCard),
                // Measuring needs an authenticated link (upstream draws the buttons only when ready, VT:319-337).
                // Only the charger byte blocks Measure; an inferred charge never does (PORTING D-242).
                measure = if (parts.link == LinkState.Authenticated) measureUi(parts.live, onCharger = parts.status.onCharger) else null,
                details = ConnectionDetailsPresenter.present(detailsInput.copy(scanToSelectedMillis = flow.scanToSelectedMillis)),
                ringData = RingDataPresenter.present(parts.sync, disconnectAfter, wallClock()),
            )
        }

        val current: StateFlow<RingSessionController?> = sessions?.current ?: MutableStateFlow(null)
        val sessionDetails: Flow<DetailsInput> = current.flatMapLatest { session ->
            if (session == null) {
                flowOf(DetailsInput())
            } else {
                // Read through the additive interface the link may offer; never through its toString.
                val diagnostics: Flow<List<LinkDiagnostic>?> = (session.link as? LinkDiagnostics)?.diagnostics ?: flowOf(null)
                combine(session.link.info, session.timer.timings, session.dispatcher.counts, session.teardowns, diagnostics) { info, timings, counts, teardowns, lines ->
                    DetailsInput(ring = session.link.ring, info = info, timings = timings, counts = counts, teardowns = teardowns, diagnostics = lines)
                }.combine(session.liveMeasure.state.map { it.evidence }.distinctUntilChanged()) { input, evidence -> input.copy(lastMeasure = evidence) }
            }
        }
        val scanKept = details.scanMatch != null
        val detailsInput: Flow<DetailsInput> =
            combine(sessionDetails, details.pairingOutcome, details.scanMatch ?: flowOf(null)) { input, pairing, scan ->
                input.copy(pairing = pairing, scan = scan, scanKept = scanKept)
            }

        val linkParts: Flow<LinkParts> = current.flatMapLatest { session ->
            if (session == null) {
                flowOf(LinkParts())
            } else {
                combine(
                    session.state, session.deviceStatus.state, session.liveMeasure.state, session.keepalive.problem, session.sync.state,
                ) { link, status, live, problem, sync ->
                    LinkParts(link, status, live, problem, session.link.ring.name, sync)
                }
            }
        }
        sessions?.reconnectRemembered()
        val flowState: StateFlow<ConnectFlowState> = connectFlow?.state ?: MutableStateFlow(ConnectFlowState())
        val initialDetails = DetailsInput(pairing = details.pairingOutcome.value, scanKept = scanKept, scan = details.scanMatch?.value)
        uiState = combine(linkParts, flowState, adapterState, detailsInput, disconnectAfterSync, ::render)
            .stateIn(scope, SharingStarted.Eagerly, render(LinkParts(), flowState.value, adapterState.value, initialDetails, disconnectAfterSync.value))
    }

    /**
     * Handles a Ring-screen action. The actions that need a fresh permission read or open a
     * system screen or dialog are the activity's (it calls [requestScan] and the result hooks);
     * they do nothing here.
     */
    fun onAction(action: RingAction) {
        when (action) {
            is RingAction.Pick -> connectFlow?.pick(action.ring)
            // The clipboard is the activity's; the text is uiState's details.copyText.
            RingAction.CopyDetails -> Unit
            // A ring on its charger is not on a finger: no new measure (the button is disabled too).
            is RingAction.Measure -> controller?.let { if (!it.deviceStatus.state.value.onCharger) it.liveMeasure.start(action.mode) }
            RingAction.StopMeasure -> controller?.liveMeasure?.stop()
            RingAction.SyncNow -> controller?.sync?.syncNow()
            is RingAction.SetDisconnectAfterSync -> {
                if (prefs?.setDisconnectAfterSync(action.on) == false) {
                    // Not saved: the switch shows what is kept, so the user sees it did not change.
                    disconnectAfterSync.value = prefs.disconnectAfterSync
                } else {
                    disconnectAfterSync.value = action.on
                }
            }
            is RingAction.Link -> when (action.action) {
                LinkAction.CANCEL_SCAN -> connectFlow?.cancelScan()
                LinkAction.CONTINUE_PAIRING -> connectFlow?.continuePairing()
                LinkAction.PAIR_WITHOUT_SHEET -> connectFlow?.pairWithoutSheet()
                // Asks for the bond again on the same link (the link does not retry a failed bond itself).
                LinkAction.TRY_AGAIN -> controller?.connect()
                LinkAction.CANCEL, LinkAction.DISCONNECT -> controller?.let {
                    // The user's own disconnect ends a running measure as a Stop, not as a lost ring.
                    it.liveMeasure.stop()
                    it.link.disconnect()
                }
                // Forgets the ring (the measure is stopped first there too); the card goes back to Ready.
                LinkAction.STOP_RECONNECTING -> sessions?.stopReconnecting()
                LinkAction.SCAN_AND_CONNECT, LinkAction.ALLOW_NEARBY, LinkAction.ASK_AGAIN, LinkAction.SEARCH_AGAIN,
                LinkAction.OPEN_APP_SETTINGS, LinkAction.BLUETOOTH_SETTINGS, LinkAction.TURN_ON_BLUETOOTH,
                -> Unit
            }
        }
    }

    /** Android's companion-device sheet, waiting for the activity to show it; null when there is none. */
    val pairingSheet: StateFlow<PairingSheet?> = connectFlow?.pairingSheet ?: MutableStateFlow(null)

    /** The activity showed the pairing sheet. */
    fun onPairingSheetShown() {
        connectFlow?.onPairingSheetShown()
    }

    /** The pairing sheet closed with [resultCode]. */
    fun onPairingSheetResult(resultCode: Int) {
        connectFlow?.onPairingSheetResult(resultCode)
    }

    /** The activity could not show the pairing sheet. */
    fun onPairingSheetFailed() {
        connectFlow?.onPairingSheetFailed()
    }

    /** Scan & connect, Search again or Allow Nearby devices; null when this screen has no connect flow. */
    fun requestScan(snapshot: PermissionSnapshot): ScanStep? = connectFlow?.requestScan(snapshot)

    /** The Nearby-devices dialog answered. */
    fun onPermissionResult(snapshot: PermissionSnapshot) {
        connectFlow?.onPermissionResult(snapshot)
    }

    /** The activity resumed: the permission may have changed in Settings. */
    fun onResume(snapshot: PermissionSnapshot) {
        connectFlow?.onResume(snapshot)
    }

    /**
     * Android turned Bluetooth on at the user's request. With no active link the user was on the
     * way to a scan, so it goes on; a link that exists reconnects on its own.
     */
    fun onBluetoothEnabled(snapshot: PermissionSnapshot) {
        val link = controller?.state?.value ?: LinkState.Idle
        if (link == LinkState.Idle) connectFlow?.onBluetoothEnabled(snapshot)
    }

    /** The parts of the screen the ring session drives; an idle link when there is none. */
    private data class LinkParts(
        val link: LinkState = LinkState.Idle,
        val status: DeviceStatusState = DeviceStatusState(),
        val live: LiveMeasureState = LiveMeasureState(),
        val problem: KeepaliveProblem? = null,
        val ringName: String? = null,
        val sync: SyncState? = null,
    )
}
