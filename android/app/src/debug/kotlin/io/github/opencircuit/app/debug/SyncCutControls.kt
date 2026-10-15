package io.github.opencircuit.app.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Debug build only, at the foot of Connection details: the sync test action. Not armed, it offers
 * "Cut the next sync after N pages" for each of [SyncCutArming.PRESETS]; armed, it says "Next sync
 * will be cut after N pages" with a Disarm button. The cut sync's log line ends "cut by the test
 * action", and the next normal Sync now's log line shows whether the history joined.
 */
@Composable
internal fun SyncCutControls(arming: SyncCutArming) {
    val armed by arming.armed.collectAsState()
    Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Test action (debug build)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { heading() })
        val pages = armed
        if (pages != null) {
            Row {
                Text(
                    armedText(pages),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                )
                TextButton(
                    onClick = arming::disarm,
                    modifier = Modifier.semantics { contentDescription = "Disarm the sync cut" },
                ) { Text("Disarm") }
            }
        } else {
            Text(
                "Cut the next sync after N pages — then run Sync now again and read its continuity in the sync log.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SyncCutArming.PRESETS.forEach { n ->
                    OutlinedButton(
                        onClick = { arming.arm(n) },
                        modifier = Modifier.semantics { contentDescription = "Cut the next sync after $n pages" },
                    ) { Text("$n") }
                }
            }
        }
    }
}

/** What Connection details says while the cut is armed. */
internal fun armedText(pages: Int): String = "Next sync will be cut after $pages pages"
