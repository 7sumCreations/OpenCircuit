package io.github.opencircuit.app.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * The Connection details card. Collapsed, it shows a few rows with the ring's address and name
 * masked; "Show all" expands it to every row with full values, the decoded advertisement and the
 * link's whole diagnostics list (nothing cut off: Bluetooth on/off and bond changes must be
 * readable there). Copy hands the masked text to [onCopy]; the user's tap is the only way it
 * leaves the screen. Drawn from [details] alone.
 */
@Composable
fun ConnectionDetailsCard(details: ConnectionDetailsUi, onCopy: () -> Unit, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Connection details",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.semantics {
                        contentDescription = if (expanded) "Hide all connection details" else "Show all connection details"
                        stateDescription = if (expanded) "Expanded" else "Collapsed"
                    },
                ) { Text(if (expanded) "Hide" else "Show all") }
            }
            (if (expanded) details.rows else details.summary).forEach { DetailLine(it) }
            if (expanded) {
                Section("Advertisement")
                details.advertisement.forEach { DetailLine(it) }
                Section("Link diagnostics")
                Text(
                    DIAGNOSTICS_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                details.diagnostics.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Copy masks the ring's address and name.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.padding(4.dp))
                TextButton(onClick = onCopy, modifier = Modifier.semantics { contentDescription = "Copy connection details" }) { Text("Copy") }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
}

@Composable
private fun DetailLine(row: DetailRow) {
    // Label and value are one stop for TalkBack, not two.
    Row(modifier = Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(row.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.45f))
        Text(row.value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.55f))
    }
}

/** Says what to look for in the list: the link's adapter and bond events, which only a phone shows. */
const val DIAGNOSTICS_HINT =
    "Turning Bluetooth off and on, and each pairing step, should add a line here (Bluetooth state, bond state). " +
        "Newest last."
