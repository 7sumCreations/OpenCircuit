package io.github.opencircuit.app.ring

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.opencircuit.app.details.ConnectionDetailsCard
import io.github.opencircuit.app.live.LiveMode
import io.github.opencircuit.app.live.MeasureSection
import io.github.opencircuit.ble.RememberedRing

/** What the user can do on the Ring screen. */
sealed interface RingAction {
    /** Tapped Measure on the [mode] card: start, re-tap or switch to it. */
    data class Measure(val mode: LiveMode) : RingAction

    /** Tapped Stop. */
    data object StopMeasure : RingAction

    /** Tapped one of the connection card's buttons. */
    data class Link(val action: LinkAction) : RingAction

    /** Picked [ring] from the list of rings found. */
    data class Pick(val ring: RememberedRing) : RingAction

    /** Tapped Copy on Connection details: the masked text goes to the clipboard. */
    data object CopyDetails : RingAction
}

/**
 * The Ring screen: the connection card (link state and battery), then — once the ring is
 * authenticated — the Measure section, then Connection details (in every build). Takes its whole state as a value so it renders the same
 * from the app or from a hand-built state in a test. [pulse] animates the live chart's endpoint;
 * the app turns it off when the system asks for reduced motion, tests turn it off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RingScreen(state: RingUiState, onAction: (RingAction) -> Unit, modifier: Modifier = Modifier, pulse: Boolean = true) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(state.title) }) },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConnectionCard(state.card, onAction = { onAction(RingAction.Link(it)) }, onPick = { onAction(RingAction.Pick(it)) })
            state.measure?.let { measure ->
                MeasureSection(
                    measure = measure,
                    pulse = pulse,
                    onMeasure = { onAction(RingAction.Measure(it)) },
                    onStop = { onAction(RingAction.StopMeasure) },
                )
            }
            ConnectionDetailsCard(state.details, onCopy = { onAction(RingAction.CopyDetails) })
        }
    }
}
