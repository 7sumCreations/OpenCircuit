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
    /** The connection card: the link's state, its action, the battery. */
    val card: ConnectionCardUi,
    /** The Measure cards and the Live card; null unless the link is authenticated. */
    val measure: MeasureUi? = null,
)

/**
 * Hosts the Ring screen's state: maps the session's link state, device status, keepalive and
 * live measure into one [RingUiState]. [scope] becomes the view model's scope (the app passes a
 * main-thread scope; tests pass a virtual-time one). Opening the screen connects the link.
 */
class RingViewModel(
    private val controller: RingSessionController?,
    title: String,
    scope: CoroutineScope,
) : ViewModel(scope) {

    /** The Ring screen's state. */
    val uiState: StateFlow<RingUiState>

    init {
        val ringName = controller?.link?.ring?.name
        val initial = RingUiState(
            title = title,
            card = connectionCardUi(LinkState.Idle, ringName, DeviceStatusState(), measuring = false, keepaliveProblem = null),
        )
        uiState = if (controller == null) {
            MutableStateFlow(initial).asStateFlow()
        } else {
            controller.connect()
            combine(
                controller.state,
                controller.deviceStatus.state,
                controller.liveMeasure.state,
                controller.keepalive.problem,
            ) { link, status, live, problem ->
                val measuring = live.mode != null
                RingUiState(
                    title = title,
                    card = connectionCardUi(link, ringName, status, measuring, problem),
                    // Measuring needs an authenticated link (upstream draws the buttons only when ready, VT:319-337).
                    // Only the charger byte blocks Measure; an inferred charge never does (PORTING D-242).
                    measure = if (link == LinkState.Authenticated) measureUi(live, onCharger = status.onCharger) else null,
                )
            }.stateIn(scope, SharingStarted.Eagerly, initial)
        }
    }

    /**
     * Handles a Ring-screen action. A link action that opens a system screen is the activity's;
     * it does nothing here.
     */
    fun onAction(action: RingAction) {
        val controller = controller ?: return
        when (action) {
            // A ring on its charger is not on a finger: no new measure (the button is disabled too).
            is RingAction.Measure -> if (!controller.deviceStatus.state.value.onCharger) controller.liveMeasure.start(action.mode)
            RingAction.StopMeasure -> controller.liveMeasure.stop()
            is RingAction.Link -> when (action.action) {
                LinkAction.SCAN_AND_CONNECT, LinkAction.TRY_AGAIN -> controller.connect()
                LinkAction.CANCEL, LinkAction.STOP_RECONNECTING, LinkAction.DISCONNECT -> {
                    // The user's own disconnect ends a running measure as a Stop, not as a lost ring.
                    controller.liveMeasure.stop()
                    controller.link.disconnect()
                }
                LinkAction.BLUETOOTH_SETTINGS, LinkAction.TURN_ON_BLUETOOTH -> Unit
            }
        }
    }
}
