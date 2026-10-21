package io.github.opencircuit.app.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** What the user can do on Today. */
sealed interface TodayAction {
    /** Go to the Ring tab (to pair a ring, or see the connection). */
    data object OpenRing : TodayAction

    /** Pull to refresh, or the overdue chip's Sync now: sync now (the automatic heart rate stands down first). */
    data object Refresh : TodayAction

    /** The hero's SpO₂ Measure. */
    data object MeasureSpO2 : TodayAction

    /** The hero's Stop, while SpO₂ is measured. */
    data object StopMeasure : TodayAction
}

/** The tag of Today's hero card. */
const val TODAY_HERO_TAG = "today.hero"

/** The tag of the sync strip's bar. */
const val TODAY_STRIP_TAG = "today.strip"

/** The tag of the overdue chip. */
const val TODAY_OVERDUE_TAG = "today.overdue"

/**
 * The Today screen: the hero (heart rate — stored, then live after a sync — and SpO₂ with its
 * Measure button), the sync strip while a sync runs, "Last synced" always, and the overdue chip.
 * Pull down to sync now; the indicator lets go at once and the strip shows the progress. Takes its
 * whole state as a value, so it renders the same from the app or from a hand-built state in a test.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(state: TodayUiState, onAction: (TodayAction) -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("Today", modifier = Modifier.semantics { heading() }) }) },
    ) { padding ->
        // Released at once: the strip, in the layout, shows the sync.
        PullToRefreshBox(isRefreshing = false, onRefresh = { onAction(TodayAction.Refresh) }, modifier = Modifier.padding(padding)) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Hero(state, onAction)
                SyncStrip(state)
                state.overdue?.let { OverdueChip(it, onAction) }
            }
        }
    }
}

@Composable
private fun Hero(state: TodayUiState, onAction: (TodayAction) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag(TODAY_HERO_TAG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Reading(state.heartRate, Modifier.weight(1f), live = state.heartRateLive)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Reading(state.spo2, live = state.spo2Measure.stops)
                    MeasureButton(state.spo2Measure, onAction)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.ring, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (state.ring == NO_RING) TextButton(onClick = { onAction(TodayAction.OpenRing) }) { Text("Open Ring") }
            }
        }
    }
}

/** One reading, one node for a screen reader: its label, number, unit, when it was taken and its live state, in that order. */
@Composable
private fun Reading(reading: ReadingUi, modifier: Modifier = Modifier, live: Boolean = false) {
    Column(
        modifier.semantics(mergeDescendants = true) {
            // A live number changes every 2 s: announced politely, never interrupting.
            if (live) liveRegion = LiveRegionMode.Polite
        },
    ) {
        Text(reading.label, style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                reading.value ?: "—",
                style = MaterialTheme.typography.displayMedium,
                color = if (live) MaterialTheme.colorScheme.primary else Color.Unspecified,
            )
            Text(" ${reading.unit}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (reading.note.isNotEmpty()) Text(reading.note, style = MaterialTheme.typography.bodySmall)
        reading.status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
}

/** Measure (off with its reason shown under it) or Stop. */
@Composable
private fun MeasureButton(button: MeasureButtonUi, onAction: (TodayAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (button.stops) {
            OutlinedButton(onClick = { onAction(TodayAction.StopMeasure) }) { Text(button.label) }
        } else {
            Button(onClick = { onAction(TodayAction.MeasureSpO2) }, enabled = button.enabled) { Text(button.label) }
        }
        button.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/**
 * The sync strip: while a sync runs, its line and a slim bar (determinate once the channel's count
 * is known); "Last synced …" under it, always. In the layout, never over the content.
 */
@Composable
private fun SyncStrip(state: TodayUiState) {
    Column(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        state.strip?.let { strip ->
            Text(strip.text, style = MaterialTheme.typography.bodyMedium)
            val bar = Modifier.fillMaxWidth().testTag(TODAY_STRIP_TAG)
            val fraction = strip.fraction
            if (fraction == null) LinearProgressIndicator(modifier = bar) else LinearProgressIndicator(progress = { fraction }, modifier = bar)
        }
        Text(state.lastSynced, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The overdue chip: amber or red with the Ring data card's own words (or the never-synced line), and Sync now when one can start. */
@Composable
private fun OverdueChip(overdue: TodayOverdueUi, onAction: (TodayAction) -> Unit) {
    val (container, content) = when (overdue.level) {
        TodayAlertLevel.RED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        TodayAlertLevel.AMBER -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        TodayAlertLevel.NEVER_SYNCED -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth().testTag(TODAY_OVERDUE_TAG),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(overdue.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (overdue.canSync) TextButton(onClick = { onAction(TodayAction.Refresh) }) { Text("Sync now") }
        }
    }
}
