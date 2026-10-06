package io.github.opencircuit.app.ring

import androidx.lifecycle.ViewModel
import io.github.opencircuit.app.live.MeasureUi
import io.github.opencircuit.app.live.measureUi
import io.github.opencircuit.app.session.RingSessionController
import io.github.opencircuit.ble.LinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Everything the Ring screen shows. */
data class RingUiState(
    /** The screen title. */
    val title: String,
    /** One line saying where the link stands. */
    val status: String,
    /** Ring battery %, or null while unknown. */
    val batteryPercent: Int?,
    /** The Measure cards and the Live card; null unless the link is authenticated. */
    val measure: MeasureUi? = null,
)

/**
 * Hosts the Ring screen's state: maps the session's link state, device status and live measure
 * into one [RingUiState]. [scope] becomes the view model's scope (the app passes a main-thread
 * scope; tests pass a virtual-time one). Opening the screen connects the link.
 */
class RingViewModel(
    private val controller: RingSessionController?,
    title: String,
    scope: CoroutineScope,
) : ViewModel(scope) {

    /** The Ring screen's state. */
    val uiState: StateFlow<RingUiState>

    init {
        val initial = RingUiState(title = title, status = LinkState.Idle.statusLine(), batteryPercent = null)
        uiState = if (controller == null) {
            MutableStateFlow(initial).asStateFlow()
        } else {
            controller.connect()
            combine(controller.state, controller.deviceStatus.state, controller.liveMeasure.state) { link, status, live ->
                RingUiState(
                    title = title,
                    status = link.statusLine(),
                    batteryPercent = status.batteryPercent,
                    // Measuring needs an authenticated link (upstream draws the buttons only when ready, VT:319-337).
                    measure = if (link == LinkState.Authenticated) measureUi(live) else null,
                )
            }.stateIn(scope, SharingStarted.Eagerly, initial)
        }
    }

    /** Handles a Ring-screen action. */
    fun onAction(action: RingAction) {
        val live = controller?.liveMeasure ?: return
        when (action) {
            is RingAction.Measure -> live.start(action.mode)
            RingAction.StopMeasure -> live.stop()
        }
    }
}

/** A short line for each link state; the full copy and actions per state come with the connection card. */
internal fun LinkState.statusLine(): String = when (this) {
    LinkState.Idle -> "Not connected"
    LinkState.Connecting, LinkState.Discovering, LinkState.Preparing, LinkState.Authenticating -> "Connecting…"
    LinkState.PairingNeeded -> "Pairing…"
    is LinkState.PairingFailed -> "Pairing didn't finish"
    LinkState.Authenticated -> "Connected"
    LinkState.NotStreaming -> "Ring isn't streaming"
    is LinkState.Reconnecting, LinkState.WaitingForRing -> "Ring unreachable — reconnecting automatically"
    LinkState.BondLostSuspected -> "Ring forgot this phone"
    LinkState.BluetoothOff -> "Bluetooth off"
}
