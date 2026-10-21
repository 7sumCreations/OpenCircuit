package io.github.opencircuit.app.settings

import androidx.lifecycle.ViewModel
import io.github.opencircuit.app.health.HealthAccessState
import io.github.opencircuit.app.health.HealthCardInput
import io.github.opencircuit.app.health.HealthCardPresenter
import io.github.opencircuit.app.health.HealthCardSources
import io.github.opencircuit.app.health.HealthCardUi
import io.github.opencircuit.app.health.HealthFlushState
import io.github.opencircuit.app.health.HealthSwitch
import io.github.opencircuit.app.ring.everyMinute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant

/** Everything the Settings tab shows. */
data class SettingsUiState(
    /** "Send to Health Connect" is ON. */
    val healthOn: Boolean = false,
    /** The line under the switch, saying what ON or OFF means now. */
    val healthLine: String = SettingsPresenter.OFF_LINE,
    /** The Health Connect card; null while the switch is OFF (nothing of it is drawn). */
    val healthCard: HealthCardUi? = null,
    /** The last turn ON could not be saved, so the switch stayed OFF. */
    val saveFailed: Boolean = false,
)

/** What the user can do on the Settings tab. */
sealed interface SettingsAction {
    /** Turned "Send to Health Connect" [on] or off. */
    data class SetHealthWrite(val on: Boolean) : SettingsAction

    /** Tapped Connect Health Connect: Health Connect's own permission screen (the activity's). */
    data object ConnectHealth : SettingsAction

    /** Tapped Open Health Connect settings or Change in Health Connect: Health Connect's page for this app (the activity's). */
    data object OpenHealthSettings : SettingsAction

    /** Tapped Write to Health Connect now. */
    data object WriteHealthNow : SettingsAction
}

/** The Settings tab's state from the switch and the Health Connect card's sources. Pure. */
object SettingsPresenter {
    const val ON_LINE = "On. After each sync, a copy of your readings goes to Health Connect so other apps you allow can use it."
    const val OFF_LINE = "Off. Your readings stay only in this app. Turn it on to send a copy to Health Connect after each sync."

    fun present(on: Boolean, access: HealthAccessState, flush: HealthFlushState, now: Instant, saveFailed: Boolean): SettingsUiState =
        SettingsUiState(
            healthOn = on,
            healthLine = if (on) ON_LINE else OFF_LINE,
            // The card is the E10 one, unchanged; it exists only while the switch is ON.
            healthCard = if (on) HealthCardPresenter.present(HealthCardInput(access, flush, now)) else null,
            saveFailed = saveFailed,
        )
}

/**
 * Hosts the Settings tab's state: the switch ([HealthSwitch]) and, while it is ON, the Health
 * Connect card from [health] ("Last written" moves on with [clockTicks]). [scope] becomes the view
 * model's scope (the app passes a main-thread scope; tests pass a virtual-time one).
 */
class SettingsViewModel(
    private val switch: HealthSwitch,
    private val health: HealthCardSources,
    scope: CoroutineScope,
    private val wallClock: () -> Instant = Instant::now,
    clockTicks: Flow<Unit> = everyMinute(),
) : ViewModel(scope) {

    private val saveFailed = MutableStateFlow(false)

    /** The Settings tab's state. */
    val uiState: StateFlow<SettingsUiState> =
        combine(switch.enabled, health.access, health.flush, saveFailed, clockTicks) { on, access, flush, failed, _ ->
            SettingsPresenter.present(on, access, flush, wallClock(), failed)
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            SettingsPresenter.present(switch.isOn(), health.access.value, health.flush.value, wallClock(), saveFailed = false),
        )

    /** Handles a Settings action; the permission screen and Health Connect's settings page are the activity's. */
    fun onAction(action: SettingsAction) {
        when (action) {
            is SettingsAction.SetHealthWrite -> saveFailed.value = !switch.set(action.on) && action.on
            // Write now is the card's, and the card is there only while ON; asked anyway (the flush asks too).
            SettingsAction.WriteHealthNow -> if (switch.isOn()) health.writeNow()
            SettingsAction.ConnectHealth, SettingsAction.OpenHealthSettings -> Unit
        }
    }
}
