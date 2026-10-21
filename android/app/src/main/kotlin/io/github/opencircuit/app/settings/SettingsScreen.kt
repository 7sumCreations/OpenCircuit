package io.github.opencircuit.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.opencircuit.app.health.HealthCardAction
import io.github.opencircuit.app.health.HealthConnectCard

/** The tag of the "Send to Health Connect" row (the whole row is the switch). */
const val SETTINGS_HEALTH_SWITCH_TAG = "settings.health.switch"

/**
 * The Settings tab: "Send to Health Connect" (OFF until the user turns it on) with what ON or OFF
 * means, the Health Connect card under it only while ON, and where the data lives. Takes its whole
 * state as a value, so it renders the same from the app or from a hand-built state in a test.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: SettingsUiState, onAction: (SettingsAction) -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("Settings", modifier = Modifier.semantics { heading() }) }) },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag(SETTINGS_HEALTH_SWITCH_TAG)
                            .toggleable(value = state.healthOn, role = Role.Switch, onValueChange = { onAction(SettingsAction.SetHealthWrite(it)) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Send to Health Connect", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        // The row handles the tap and announces it, so the switch itself takes none.
                        Switch(checked = state.healthOn, onCheckedChange = null)
                    }
                    Text(
                        state.healthLine,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    // Shown after a tap, away from the focus: announced, so a screen reader hears it where it is.
                    state.saveError?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    Text(
                        "Turning it off later stops new copies. What was already written stays in Health Connect until you remove it there.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.healthCard?.let { card ->
                HealthConnectCard(
                    card,
                    onAction = { action ->
                        when (action) {
                            HealthCardAction.CONNECT -> onAction(SettingsAction.ConnectHealth)
                            // Changing what is allowed happens on Health Connect's own page for the app.
                            HealthCardAction.MANAGE -> onAction(SettingsAction.OpenHealthSettings)
                            HealthCardAction.WRITE_NOW -> onAction(SettingsAction.WriteHealthNow)
                        }
                    },
                )
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Your data", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Text(
                        "Every reading from the ring is kept on this phone for good. The dashboard reads only this app's own store; it never reads from Health Connect.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
