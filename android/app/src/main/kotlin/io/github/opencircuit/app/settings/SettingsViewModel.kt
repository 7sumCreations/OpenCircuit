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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    /** What the last change could not save, and what that means; null when it was saved. */
    val saveError: String? = null,
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

    /** An ON that could not be saved: the switch stayed OFF. */
    const val ON_NOT_SAVED = "Could not save the setting, so it stays off. Try again."

    /** An OFF that could not be saved: writing stopped now, but the saved ON would come back at the next launch. */
    const val OFF_NOT_SAVED = "Could not save the setting. Sending is off for now, but may turn back on when the app restarts. Try again."

    fun present(on: Boolean, access: HealthAccessState, flush: HealthFlushState, now: Instant, saveError: String?): SettingsUiState =
        SettingsUiState(
            healthOn = on,
            healthLine = if (on) ON_LINE else OFF_LINE,
            // The Health Connect card exists only while the switch is ON. Until Health Connect has been
            // read since it went ON, it says so and offers no button: a Connect drawn from "not read
            // yet" could open the permission screen again over a grant that is already there.
            healthCard = when {
                !on -> null
                access.available == null -> CHECKING
                else -> HealthCardPresenter.present(HealthCardInput(access, flush, now))
            },
            saveError = saveError,
        )

    private val CHECKING = HealthCardUi(headline = "Checking Health Connect…")
}

/**
 * Hosts the Settings tab's state: the switch ([HealthSwitch]) and, while it is ON, the Health
 * Connect card from [health] ("Last written" moves on with [clockTicks]). [scope] becomes the view
 * model's scope (the app passes a main-thread scope; tests pass a virtual-time one).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(
    private val switch: HealthSwitch,
    private val health: HealthCardSources,
    scope: CoroutineScope,
    private val wallClock: () -> Instant = Instant::now,
    clockTicks: Flow<Unit> = everyMinute(),
) : ViewModel(scope) {

    private val saveError = MutableStateFlow<String?>(null)

    /** The minute clock only while ON: with the switch OFF there is no card and nothing that ages. */
    private val ticks: Flow<Unit> = switch.enabled.flatMapLatest { on -> if (on) clockTicks else flowOf(Unit) }

    /** The Settings tab's state. */
    val uiState: StateFlow<SettingsUiState> =
        combine(switch.enabled, health.access, health.flush, saveError, ticks) { on, access, flush, error, _ ->
            SettingsPresenter.present(on, access, flush, wallClock(), error)
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            SettingsPresenter.present(switch.isOn(), health.access.value, health.flush.value, wallClock(), saveError = null),
        )

    /** Handles a Settings action; the permission screen and Health Connect's settings page are the activity's. */
    fun onAction(action: SettingsAction) {
        when (action) {
            is SettingsAction.SetHealthWrite -> saveError.value = when {
                switch.set(action.on) -> null
                action.on -> SettingsPresenter.ON_NOT_SAVED
                else -> SettingsPresenter.OFF_NOT_SAVED
            }
            // Write now is the card's, and the card is there only while ON; asked anyway (the flush asks too).
            SettingsAction.WriteHealthNow -> if (switch.isOn()) health.writeNow()
            SettingsAction.ConnectHealth, SettingsAction.OpenHealthSettings -> Unit
        }
    }
}
