package io.github.opencircuit.app.today

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.opencircuit.app.ui.LocalTones
import io.github.opencircuit.app.ui.Motion
import io.github.opencircuit.app.ui.charts.TrendChart
import io.github.opencircuit.app.ui.charts.TrendChartColours

/** The tag of a tile's trend screen. */
const val TODAY_DETAIL_TAG = "today.detail"

/** The tag of the trend chart. */
const val TODAY_DETAIL_CHART_TAG = "today.detail.chart"

/** The tag of the MIN / AVG / MAX row. */
const val TODAY_DETAIL_STATS_TAG = "today.detail.stats"

/** The tag of the values table (shown on request). */
const val TODAY_DETAIL_TABLE_TAG = "today.detail.table"

/**
 * A tile's trend: back to Today, the headline with its delta, the 14 / 30 toggle, the chart (tap or
 * drag to read a day), the usual range and "N of M days", MIN / AVG / MAX, Measure for heart rate
 * and SpO₂ (the hero's own readings and button), what the number is, and the same rows as a table.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TileDetailScreen(detail: TileDetailUi, state: TodayUiState, onAction: (TodayAction) -> Unit, modifier: Modifier, animate: Boolean) {
    val backTo = if (state.browser.isToday) "Today" else state.browser.title
    Scaffold(
        modifier = modifier.fillMaxSize().testTag(TODAY_DETAIL_TAG),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { onAction(TodayAction.CloseDetail) }, modifier = Modifier.semantics { contentDescription = "Back to $backTo" }) {
                        Text("←", style = MaterialTheme.typography.titleLarge)
                    }
                },
                title = {
                    Column {
                        Text(detail.title, modifier = Modifier.semantics { heading() })
                        Text(detail.subtitle, style = MaterialTheme.typography.bodySmall)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Headline(detail, animate)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                TileDetailPresenter.RANGES.forEachIndexed { i, days ->
                    SegmentedButton(
                        selected = detail.days == days,
                        onClick = { onAction(TodayAction.DetailRange(days)) },
                        shape = SegmentedButtonDefaults.itemShape(i, TileDetailPresenter.RANGES.size),
                    ) { Text("$days days") }
                }
            }
            ChartCard(detail)
            if (!detail.loading && detail.stats.isNotEmpty()) Stats(detail)
            detail.measure?.let { Measure(it, state, onAction) }
            Section("What this is") { Text(detail.about, style = MaterialTheme.typography.bodyMedium) }
            Table(detail)
        }
    }
}

@Composable
private fun Headline(detail: TileDetailUi, animate: Boolean) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(detail.qualifier, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AnimatedContent(detail.value, transitionSpec = { fadeIn(Motion.effects<Float>(animate)) togetherWith fadeOut(Motion.effects<Float>(animate)) }, label = "detail.value") { value ->
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value ?: "—", style = MaterialTheme.typography.displaySmall)
                if (value != null && detail.unit.isNotEmpty()) Text(" ${detail.unit}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
        detail.delta?.let { delta ->
            val arrow = when (detail.direction) {
                TileDirection.UP -> "↑ "
                TileDirection.DOWN -> "↓ "
                TileDirection.FLAT -> "→ "
                null -> ""
            }
            Text(arrow + delta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChartCard(detail: TileDetailUi) {
    val tones = LocalTones.current
    // Keyed by the window's last day too: when the window moves (a new local day), a slot is another date.
    var picked by rememberSaveable(detail.metric, detail.days, detail.rows.lastOrNull()?.day?.toEpochDay()) { mutableStateOf<Int?>(null) }
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "${detail.title}${if (detail.unit.isEmpty()) "" else " (${detail.unit})"}, last ${detail.days} days",
                style = MaterialTheme.typography.labelLarge,
            )
            if (detail.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Loading ${detail.days} days…", style = MaterialTheme.typography.bodySmall)
            } else {
                val row = picked?.let { detail.rows.getOrNull(it) }
                // The read-out: the picked day, else the day shown.
                Text(
                    (row ?: detail.rows.lastOrNull())?.readout ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                TrendChart(
                    geometry = detail.chart,
                    slots = detail.rows.size,
                    xLabels = detail.xLabels.map { it.x to it.text },
                    tickLabels = detail.tickLabels,
                    colours = TrendChartColours(
                        line = Color(tones.ink3),
                        band = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        mean = MaterialTheme.colorScheme.primary,
                        accent = MaterialTheme.colorScheme.primary,
                        ring = MaterialTheme.colorScheme.surfaceContainerLowest,
                        grid = MaterialTheme.colorScheme.outlineVariant,
                        label = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    description = detail.description,
                    selected = picked,
                    onSelect = { picked = it },
                    modifier = Modifier.fillMaxWidth().height(220.dp).testTag(TODAY_DETAIL_CHART_TAG),
                )
            }
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }) {
                Text(detail.usual, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(detail.count, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Stats(detail: TileDetailUi) {
    Row(Modifier.fillMaxWidth().testTag(TODAY_DETAIL_STATS_TAG), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (stat in detail.stats) {
            Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = stat.spoken }) {
                Text(stat.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stat.value, style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

/** Heart rate: the hero's live (or stored) heart rate; SpO₂: its reading and the hero's Measure / Stop. */
@Composable
private fun Measure(measure: DetailMeasure, state: TodayUiState, onAction: (TodayAction) -> Unit) {
    when (measure) {
        DetailMeasure.HEART_RATE -> Section("Live heart rate") {
            HeroReading(state.heartRate, live = state.heartRateLive)
            if (!state.heartRateLive) {
                Text(
                    "Live heart rate starts by itself on Today once the ring is synced and connected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DetailMeasure.SPO2 -> Section("Measure now") {
            HeroReading(state.spo2, live = state.spo2Measure.stops)
            HeroMeasureButton(state.spo2Measure, onAction)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

/** The rows behind the chart as a table, on request; one node per day for a screen reader. */
@Composable
private fun Table(detail: TileDetailUi) {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = !open }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(if (open) "Hide the table" else "Show values as a table")
    }
    if (open) {
        Column(Modifier.fillMaxWidth().testTag(TODAY_DETAIL_TABLE_TAG)) {
            for (row in detail.rows.asReversed()) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) { },
                ) {
                    Text(row.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(row.value?.let { listOf(it, detail.unit).filter(String::isNotEmpty).joinToString(" ") } ?: "no reading", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
