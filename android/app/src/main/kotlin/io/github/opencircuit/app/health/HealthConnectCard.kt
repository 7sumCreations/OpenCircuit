package io.github.opencircuit.app.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The card's test tag. Its "Connected" means Health Connect access, not the ring's link (the
 * connection card's own "Connected"); a test that reads the link's state leaves this card out.
 */
const val HEALTH_CONNECT_CARD_TAG = "health-connect-card"

/**
 * The Settings tab's "Health Connect" card, under the "Send to Health Connect" switch and drawn only
 * while it is ON: whether the app may write to
 * Health Connect, when it last wrote and how much, an amber warning when types are not allowed or
 * their writes fail, and the buttons the state calls for (Connect Health Connect, or Write to
 * Health Connect now / Try again, and Change in Health Connect). Drawn from [ui] alone.
 */
@Composable
fun HealthConnectCard(ui: HealthCardUi, onAction: (HealthCardAction) -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth().testTag(HEALTH_CONNECT_CARD_TAG)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Health Connect", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(
                ui.headline,
                style = MaterialTheme.typography.bodyLarge,
                // Announced when it changes ("Not connected" → "Connected").
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            ui.body?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            ui.warning?.let { AmberWarning(it) }
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
            ui.secondButton?.let { label ->
                OutlinedButton(onClick = { ui.secondAction?.let(onAction) }, enabled = ui.secondAction != null) {
                    Text(label)
                }
            }
            // "Catching up…" and the live-only line, under the buttons as in the mockup.
            ui.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Types not allowed, or writes that failed: amber, read out once when it appears or changes. */
@Composable
private fun AmberWarning(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
    }
}
