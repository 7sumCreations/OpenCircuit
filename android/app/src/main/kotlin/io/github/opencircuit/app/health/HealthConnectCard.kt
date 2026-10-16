package io.github.opencircuit.app.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The Ring screen's "Health Connect" card, under the Ring data card: whether the app may write to
 * Health Connect, when it last wrote and how much, and the one button the state calls for
 * (Connect Health Connect, or Write to Health Connect now). Drawn from [ui] alone.
 */
@Composable
fun HealthConnectCard(ui: HealthCardUi, onAction: (HealthCardAction) -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Health Connect", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(
                ui.headline,
                style = MaterialTheme.typography.bodyLarge,
                // Announced when it changes ("Not connected" → "Connected").
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            ui.body?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            ui.rows.forEach { (label, value) ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Text(value, style = MaterialTheme.typography.bodyMedium)
                }
            }
            ui.button?.let { label ->
                FilledTonalButton(onClick = { ui.action?.let(onAction) }, enabled = ui.buttonEnabled && ui.action != null) {
                    Text(label)
                }
            }
        }
    }
}
