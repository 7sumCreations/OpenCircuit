package io.github.opencircuit.app.ring

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The Ring screen's "Ring data" card: when the ring's history was last synced, the last sync's
 * result, Sync now, and the "Disconnect after syncing" switch. Drawn from [ui] alone.
 */
@Composable
fun RingDataCard(
    ui: RingDataUi,
    onSyncNow: () -> Unit,
    onDisconnectAfterSync: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Ring data", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(
                ui.headline,
                style = MaterialTheme.typography.bodyLarge,
                // Announced when it changes ("Syncing…" → "Last synced just now").
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            ui.lastSync?.let { DataRow(label = "Last sync", value = it) }
            ui.problem?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            FilledTonalButton(onClick = onSyncNow, enabled = ui.syncEnabled) { Text(ui.syncLabel) }
            ui.syncBlockedBy?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = ui.disconnectAfterSync, role = Role.Switch, onValueChange = onDisconnectAfterSync),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Disconnect after syncing", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                // The row handles the tap and announces it, so the switch itself takes none.
                Switch(checked = ui.disconnectAfterSync, onCheckedChange = null)
            }
            Text(ui.help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DataRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
