package io.github.opencircuit.app.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Measure section of the Ring screen: while a measure runs, the Live card on top; below it
 * one card per metric with its last reading and a Measure / Stop button. Copy and layout follow
 * the tip's Measure readout (upstream 5c40605, 6e93d53, e0b2294; PORTING.md D-235).
 */
@Composable
fun MeasureSection(
    measure: MeasureUi,
    pulse: Boolean,
    onMeasure: (LiveMode) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        measure.live?.let { LiveCard(it, pulse = pulse, onStop = onStop) }
        MeasureCard(measure.heartRate, onMeasure = onMeasure, onStop = onStop)
        MeasureCard(measure.spo2, onMeasure = onMeasure, onStop = onStop)
    }
}

/** One metric's card: title, its line, a failure line when the last measure failed, the button. */
@Composable
fun MeasureCard(card: MeasureCardUi, onMeasure: (LiveMode) -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                    Text(card.title, style = MaterialTheme.typography.titleMedium)
                    if (card.estimate) EstimateMark()
                }
                Text(card.caption, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                card.failure?.let {
                    // Announced once when a measure fails; the 2 s readings are not announced.
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            if (card.measuring) {
                OutlinedButton(onClick = onStop, modifier = Modifier.semantics { contentDescription = card.actionLabel }) {
                    Text("Stop")
                }
            } else {
                FilledTonalButton(
                    onClick = { onMeasure(card.mode) },
                    enabled = card.enabled,
                    modifier = Modifier.semantics { contentDescription = card.actionLabel },
                ) { Text("Measure") }
            }
        }
    }
}

/** The running measure: large newest reading, low–high so far, what it is doing, chart, Stop. */
@Composable
fun LiveCard(live: LiveCardUi, pulse: Boolean, onStop: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                Text(live.title, style = MaterialTheme.typography.titleMedium)
                if (live.estimate) EstimateMark()
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(live.readout, fontSize = 56.sp, fontWeight = FontWeight.SemiBold)
                Text(live.unit, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 10.dp))
            }
            live.range?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            live.progress?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            LiveChart(
                points = live.points,
                windowMillis = live.windowMillis,
                pulse = pulse,
                modifier = Modifier.fillMaxWidth().height(110.dp),
            )
            Text(live.windowLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onStop) { Text("Stop") }
        }
    }
}

@Composable
private fun EstimateMark() {
    Text("est.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
