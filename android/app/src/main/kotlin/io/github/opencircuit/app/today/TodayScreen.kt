package io.github.opencircuit.app.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** What the user can do on Today. */
sealed interface TodayAction {
    /** Go to the Ring tab (to pair a ring, or see the connection). */
    data object OpenRing : TodayAction
}

/** The tag of Today's hero card. */
const val TODAY_HERO_TAG = "today.hero"

/**
 * The Today screen: the hero (the newest stored heart rate and SpO₂ with when they were taken, the
 * ring's link) and "Last synced". Takes its whole state as a value, so it renders the same from the
 * app or from a hand-built state in a test.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(state: TodayUiState, onAction: (TodayAction) -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("Today", modifier = Modifier.semantics { heading() }) }) },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Hero(state, onAction)
            Text(state.lastSynced, style = MaterialTheme.typography.bodyMedium)
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
                Reading(state.heartRate, Modifier.weight(1f))
                Reading(state.spo2, Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.ring, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (state.ring == NO_RING) TextButton(onClick = { onAction(TodayAction.OpenRing) }) { Text("Open Ring") }
            }
        }
    }
}

/** One reading, one node for a screen reader: its label, number, unit and when it was taken, in that order. */
@Composable
private fun Reading(reading: ReadingUi, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(reading.label, style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(reading.value ?: "—", style = MaterialTheme.typography.displayMedium)
            Text(" ${reading.unit}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (reading.note.isNotEmpty()) Text(reading.note, style = MaterialTheme.typography.bodySmall)
    }
}
