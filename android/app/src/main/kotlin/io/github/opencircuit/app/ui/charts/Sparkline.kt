package io.github.opencircuit.app.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * A tile's small trend: the usual-range band as a tint, the days as a neutral line broken at
 * missing days, a lone day as a small dot, the newest day as the accent dot. It only draws: the
 * points and the band were placed by [TrendGeometry] (in the presenter), and the tile's own
 * semantics say what it shows, so the chart itself is not read out.
 */
@Composable
fun Sparkline(geometry: TrendGeometry, line: Color, band: Color, accent: Color, ring: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        fun at(p: ChartPoint) = Offset(p.x * size.width, p.y * size.height)
        geometry.band?.let { b ->
            drawRect(band, topLeft = Offset(0f, b.top * size.height), size = Size(size.width, (b.bottom - b.top) * size.height))
        }
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        for (segment in geometry.segments) {
            val path = Path()
            segment.forEachIndexed { i, p -> at(p).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) } }
            drawPath(path, line, style = stroke)
        }
        for (dot in geometry.dots) drawCircle(line, radius = 2.dp.toPx(), center = at(dot))
        geometry.newest?.let {
            drawCircle(ring, radius = 6.5.dp.toPx(), center = at(it))
            drawCircle(accent, radius = 4.5.dp.toPx(), center = at(it))
        }
    }
}
