package io.github.opencircuit.app.today

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.opencircuit.app.ui.LocalTones
import io.github.opencircuit.app.ui.Motion
import io.github.opencircuit.app.ui.charts.Sparkline
import io.github.opencircuit.ringkit.WellnessBalance
import java.time.LocalDate
import java.util.Locale

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

    /** Show [day]: a swipe, a chevron, a date-strip tap or the date picker. Today (or a later day) is Today. */
    data class SelectDay(val day: LocalDate) : TodayAction

    /** The "Today" chip: back to today. */
    data object ShowToday : TodayAction
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
fun TodayScreen(state: TodayUiState, onAction: (TodayAction) -> Unit, modifier: Modifier = Modifier, animate: Boolean = false) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("Today", modifier = Modifier.semantics { heading() }) }) },
    ) { padding ->
        // Released at once: the strip, in the layout, shows the sync.
        PullToRefreshBox(isRefreshing = false, onRefresh = { onAction(TodayAction.Refresh) }, modifier = Modifier.padding(padding)) {
            // Tiles two to a row on a phone; at a large font each needs more room, so one to a row.
            val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
            // The arc's fill lives here, not in the grid item: scrolling the card away and back
            // must not empty it and fill it again.
            val readinessSweep = remember { Animatable(0f) }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(TILE_MIN_WIDTH * fontScale),
                modifier = Modifier.fillMaxSize().testTag(TODAY_LIST_TAG),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                wide("hero") { Hero(state, onAction, animate) }
                wide("strip") { SyncStrip(state) }
                state.overdue?.let { overdue -> wide("overdue") { OverdueChip(overdue, onAction) } }
                wide("synthesis") { SynthesisLine(state, animate) }
                wide("readiness") { ReadinessCard(state.readiness, readinessSweep, animate) }
                if (state.tiles.isEmpty()) {
                    items(TodayTile.Metric.entries.size, key = { "placeholder.$it" }) { TilePlaceholder(fontScale) }
                } else {
                    items(state.tiles, key = { it.metric.name }) { MetricTile(it, fontScale, animate) }
                }
            }
        }
    }
}

/** An item across the whole grid width. */
private fun LazyGridScope.wide(key: String, content: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
private fun Hero(state: TodayUiState, onAction: (TodayAction) -> Unit, animate: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag(TODAY_HERO_TAG),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f)) {
                    if (state.heartRateLive) LiveDot(animate)
                    Reading(state.heartRate, live = state.heartRateLive)
                }
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

/** The narrowest a tile gets at the default font size: two to a row on a phone. */
private val TILE_MIN_WIDTH = 160.dp

/** Every tile's height at the default font size (a placeholder's too), grown with the font. */
private val TILE_HEIGHT = 184.dp

/** The tag of Today's scrolling list. */
const val TODAY_LIST_TAG = "today.list"

/** The tag of the readiness card. */
const val TODAY_READINESS_TAG = "today.readiness"

/** The tag of a tile-sized placeholder shown before the first load. */
const val TODAY_PLACEHOLDER_TAG = "today.tile.placeholder"

/** The tag of the synthesis line. */
const val TODAY_SYNTHESIS_TAG = "today.synthesis"

/** The tag of one metric's tile. */
fun todayTileTag(metric: TodayTile.Metric): String = "today.tile." + metric.name.lowercase(Locale.ROOT)

/**
 * The live heart rate's dot: the dashboard's one never-ending animation, a slow breath while the
 * number is live. Still when [animate] is off (reduced motion, tests).
 */
@Composable
private fun LiveDot(animate: Boolean) {
    val heart = Color(LocalTones.current.heart)
    val alpha = if (animate) {
        val breath = rememberInfiniteTransition(label = "live")
        val a by breath.animateFloat(1f, 0.35f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "live.alpha")
        a
    } else {
        1f
    }
    Canvas(Modifier.size(10.dp).clearAndSetSemantics { }) { drawCircle(heart.copy(alpha = alpha)) }
}

/** Today's date and the one-sentence read of it; the sentence fades to its new words in place. */
@Composable
private fun SynthesisLine(state: TodayUiState, animate: Boolean) {
    val sentence = state.synthesis ?: return
    Column(
        Modifier.fillMaxWidth().testTag(TODAY_SYNTHESIS_TAG).semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(state.dateLine, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AnimatedContent(sentence, transitionSpec = { fadeIn(Motion.effects<Float>(animate)) togetherWith fadeOut(Motion.effects<Float>(animate)) }, label = "synthesis") {
            Text(it, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * The readiness ring: an arc in the tier's colour with the score inside, the tier's word and glyph
 * beside it (the colour is never the only cue), and a bar per factor — or the reason there is no
 * score. The arc fills with the expressive spring when its score first shows or changes; [sweep]
 * is held by the screen, so the card scrolling away and back keeps its fill. The tier word is in
 * the ink: the tier colours are graphic colours (3:1), not text colours (4.5:1).
 */
@Composable
private fun ReadinessCard(readiness: ReadinessUi?, sweep: Animatable<Float, AnimationVector1D>, animate: Boolean) {
    val tones = LocalTones.current
    Card(
        modifier = Modifier.fillMaxWidth().testTag(TODAY_READINESS_TAG)
            .clearAndSetSemantics { readiness?.let { contentDescription = it.description } },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val tierColour = when (readiness?.tier) {
                WellnessBalance.Tier.EXCELLENT -> Color(tones.tierExcellent)
                WellnessBalance.Tier.GOOD -> Color(tones.tierGood)
                WellnessBalance.Tier.NEEDS_IMPROVEMENT -> Color(tones.tierNeeds)
                null -> Color(tones.ink3)
            }
            val target = readiness?.fraction ?: 0f
            LaunchedEffect(target) { sweep.animateTo(target, Motion.expressive(animate)) }
            val track = Color(tones.tonal)
            Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
                    val inset = stroke.width / 2
                    val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
                    drawArc(track, -90f, 360f, false, Offset(inset, inset), arcSize, style = stroke)
                    if (sweep.value > 0f) drawArc(tierColour, -90f, 360f * sweep.value, false, Offset(inset, inset), arcSize, style = stroke)
                }
                Text(readiness?.score?.toString() ?: "—", style = MaterialTheme.typography.headlineMedium)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(readiness?.heading ?: "Readiness", style = MaterialTheme.typography.titleMedium)
                if (readiness?.score != null) {
                    Text("${readiness.glyph} ${readiness.tierWord}", style = MaterialTheme.typography.labelLarge)
                    for (factor in readiness.factors) {
                        Text("${factor.label} ${factor.percent}", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(
                            progress = { factor.percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = track,
                        )
                    }
                    readiness.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    Text(readiness?.empty ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * One metric's tile — its name and freshness, the value (fading to a new value in place), the
 * verdict with a neutral arrow, the sparkline over the usual band, and the usual range — read out
 * as the one sentence of [TileUi.description].
 */
@Composable
private fun MetricTile(tile: TileUi, fontScale: Float, animate: Boolean) {
    val tones = LocalTones.current
    Card(
        modifier = Modifier.fillMaxWidth().height(TILE_HEIGHT * fontScale).testTag(todayTileTag(tile.metric))
            .clearAndSetSemantics { contentDescription = tile.description },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Column(Modifier.padding(12.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tile.title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                tile.freshness?.let {
                    Text(
                        if (tile.stale) "◷ $it" else it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            AnimatedContent(tile.value, transitionSpec = { fadeIn(Motion.effects<Float>(animate)) togetherWith fadeOut(Motion.effects<Float>(animate)) }, label = "value") { value ->
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(value ?: "—", style = MaterialTheme.typography.headlineSmall, maxLines = 1)
                    if (value != null && tile.unit.isNotEmpty()) Text(" ${tile.unit}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 3.dp))
                }
            }
            val arrow = when (tile.direction) {
                TileDirection.UP -> "↑ "
                TileDirection.DOWN -> "↓ "
                TileDirection.FLAT -> "→ "
                null -> ""
            }
            Text(
                tile.delta?.let { arrow + it } ?: tile.extra ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Sparkline(
                tile.chart,
                line = Color(tones.ink3),
                band = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                accent = MaterialTheme.colorScheme.primary,
                ring = MaterialTheme.colorScheme.surfaceContainerLowest,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 4.dp),
            )
            Text(tile.usual, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (tile.delta != null) tile.extra?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A tile-sized space while the first load runs: the grid never jumps when the tiles arrive. */
@Composable
private fun TilePlaceholder(fontScale: Float) {
    Card(
        modifier = Modifier.fillMaxWidth().height(TILE_HEIGHT * fontScale).testTag(TODAY_PLACEHOLDER_TAG).clearAndSetSemantics { },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) { }
}
