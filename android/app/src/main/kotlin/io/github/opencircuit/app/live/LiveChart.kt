package io.github.opencircuit.app.live

import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Rising readings are drawn in green, falling ones in red (upstream's momentum colour). Upstream's
 * pastel pair is for a dark surface; on a light one the darker pair keeps the line at 3:1 or more.
 */
private val RisingOnDark = Color(0xFF6DD58C)
private val FallingOnDark = Color(0xFFFFB4AB)
private val RisingOnLight = Color(0xFF1B6D36)
private val FallingOnLight = Color(0xFFB3261E)

/**
 * What a screen reader hears for the chart: how many readings, the newest, and the trend of the
 * last two in words (the line's colour is the only drawn trend cue).
 */
fun liveChartDescription(points: List<LivePoint>): String {
    if (points.isEmpty()) return "Live chart, waiting for a reading"
    val count = if (points.size == 1) "1 reading" else "${points.size} readings"
    val latest = points.last().value
    val trend = when {
        points.size < 2 -> ""
        latest > points[points.size - 2].value -> ", rising"
        latest < points[points.size - 2].value -> ", falling"
        else -> ", steady"
    }
    return "Live chart, $count, latest $latest$trend"
}

/**
 * The live chart: a line through [points] over the last [windowMillis], a fading fill under it,
 * and a dot at the newest point. The line takes the momentum colour of the last two points —
 * green rising, red falling, the theme colour when flat (upstream's liveline chart,
 * `ios/OpenCircuit/Design/LivelineCharts.swift:24-63` @ b1c2fdd, redrawn by hand on a Canvas).
 *
 * With [pulse] the dot breathes; callers turn it off when the system asks for reduced motion and
 * in tests, where a never-ending animation has no use.
 */
@Composable
fun LiveChart(points: List<LivePoint>, windowMillis: Long, pulse: Boolean, modifier: Modifier = Modifier) {
    val onDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val accent = when {
        points.size < 2 -> MaterialTheme.colorScheme.primary
        points.last().value > points[points.size - 2].value -> if (onDark) RisingOnDark else RisingOnLight
        points.last().value < points[points.size - 2].value -> if (onDark) FallingOnDark else FallingOnLight
        else -> MaterialTheme.colorScheme.primary
    }
    // The animated value is read in the draw lambda, not here: the pulse redraws the chart each
    // frame without recomposing it.
    val dotScale: State<Float>? = if (pulse) {
        rememberInfiniteTransition(label = "live dot").animateFloat(
            initialValue = 1f,
            targetValue = 1.8f,
            animationSpec = InfiniteRepeatableSpec(tween(durationMillis = 900), RepeatMode.Reverse),
            label = "live dot scale",
        )
    } else {
        null
    }
    val description = liveChartDescription(points)
    Canvas(modifier = modifier.semantics { contentDescription = description }) {
        if (points.isEmpty()) return@Canvas
        val newest = points.last().atMillis
        val oldest = newest - windowMillis
        val low = points.minOf { it.value }
        val high = points.maxOf { it.value }
        // A little headroom so a flat line sits in the middle rather than on an edge.
        val pad = maxOf(2, (high - low) / 5)
        val bottom = (low - pad).toFloat()
        val span = (high + pad - (low - pad)).toFloat()
        fun x(at: Long) = size.width * (at - oldest).toFloat() / windowMillis.toFloat()
        fun y(value: Int) = size.height * (1f - (value - bottom) / span)

        val line = Path()
        points.forEachIndexed { i, p ->
            if (i == 0) line.moveTo(x(p.atMillis), y(p.value)) else line.lineTo(x(p.atMillis), y(p.value))
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(x(newest), size.height)
            lineTo(x(points.first().atMillis), size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(accent.copy(alpha = 0.35f), accent.copy(alpha = 0f))))
        drawPath(line, accent, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val end = Offset(x(newest), y(points.last().value))
        dotScale?.let { drawCircle(accent.copy(alpha = 0.25f), radius = 5.dp.toPx() * it.value, center = end) }
        drawCircle(accent, radius = 5.dp.toPx(), center = end)
    }
}
