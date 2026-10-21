package io.github.opencircuit.app.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The colours a [TrendChart] draws with: all graphic colours (3:1 against the card), the labels' ink text-grade. */
data class TrendChartColours(val line: Color, val band: Color, val mean: Color, val accent: Color, val ring: Color, val grid: Color, val label: Color)

/**
 * A tile's trend over 14 or 30 days: the usual band as a tint with its mean as a dashed rule, the
 * days as a line broken at missing days, a lone day as a dot, the day shown as the larger accent
 * dot, a light grid at the tick values (labelled at the right) and day labels underneath. A tap or
 * a drag picks the nearest day ([onSelect], with [TrendGeometry.slotAt]); the picked day gets a
 * crosshair. It only draws what [geometry] placed; [description] is what a screen reader says for
 * it (the screen's table holds the same rows).
 */
@Composable
fun TrendChart(
    geometry: TrendGeometry,
    slots: Int,
    xLabels: List<Pair<Float, String>>,
    /** Each grid line's height (0 at the top, as [Tick.y]) and its label. */
    tickLabels: List<Pair<Float, String>>,
    colours: TrendChartColours,
    description: String,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = colours.label, fontSize = 11.sp)
    Canvas(
        modifier
            .semantics { contentDescription = description }
            .pointerInput(slots) { detectTapGestures { onSelect(TrendGeometry.slotAt(it.x / size.width.coerceAtLeast(1) * plotFraction(size.width.toFloat()), slots)) } }
            .pointerInput(slots) {
                detectHorizontalDragGestures { change, _ ->
                    onSelect(TrendGeometry.slotAt(change.position.x / size.width.coerceAtLeast(1) * plotFraction(size.width.toFloat()), slots))
                }
            },
    ) {
        val right = RIGHT_GUTTER.dp.toPx()
        val bottom = BOTTOM_GUTTER.dp.toPx()
        val w = (size.width - right).coerceAtLeast(1f)
        val h = (size.height - bottom).coerceAtLeast(1f)
        fun at(p: ChartPoint) = Offset(p.x * w, p.y * h)

        for ((fy, label) in tickLabels) {
            val y = fy * h
            drawLine(colours.grid, Offset(0f, y), Offset(w, y), strokeWidth = 1.dp.toPx())
            val text = measurer.measure(label, labelStyle)
            drawText(text, topLeft = Offset(w + 6.dp.toPx(), y - text.size.height / 2f))
        }
        geometry.band?.let { b -> drawRect(colours.band, topLeft = Offset(0f, b.top * h), size = Size(w, (b.bottom - b.top) * h)) }
        geometry.mean?.let { m ->
            drawLine(
                colours.mean, Offset(0f, m * h), Offset(w, m * h), strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
            )
        }
        val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        for (segment in geometry.segments) {
            val path = Path()
            segment.forEachIndexed { i, p -> at(p).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) } }
            drawPath(path, colours.line, style = stroke)
        }
        for (dot in geometry.dots) drawCircle(colours.line, radius = 3.dp.toPx(), center = at(dot))
        geometry.newest?.let {
            drawCircle(colours.ring, radius = 8.dp.toPx(), center = at(it))
            drawCircle(colours.accent, radius = 6.dp.toPx(), center = at(it))
        }
        if (selected != null && slots > 0) {
            val x = (if (slots <= 1) 0.5f else selected.toFloat() / (slots - 1)) * w
            drawLine(colours.label, Offset(x, 0f), Offset(x, h), strokeWidth = 1.dp.toPx())
        }
        for ((fx, label) in xLabels) {
            val text = measurer.measure(label, labelStyle)
            val x = (fx * w - text.size.width / 2f).coerceIn(0f, (w - text.size.width).coerceAtLeast(0f))
            drawText(text, topLeft = Offset(x, h + 6.dp.toPx()))
        }
    }
}

/** The right-hand gutter for the tick labels, in dp. */
private const val RIGHT_GUTTER = 40

/** The bottom gutter for the day labels, in dp. */
private const val BOTTOM_GUTTER = 22

/** A touch's x over the whole width, rescaled to the plot (the width less the right gutter). */
private fun androidx.compose.ui.input.pointer.PointerInputScope.plotFraction(width: Float): Float {
    val plot = (width - RIGHT_GUTTER.dp.toPx()).coerceAtLeast(1f)
    return width / plot
}
