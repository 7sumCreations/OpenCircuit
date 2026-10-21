package io.github.opencircuit.app.ui.charts

/** A point in the chart's unit box: x 0 → 1 left to right, y 0 at the top. */
data class ChartPoint(val x: Float, val y: Float)

/** The usual-range band across the whole width, as its top and bottom in the unit box. */
data class BandRect(val top: Float, val bottom: Float)

/** A y-axis value and its height in the unit box. */
data class Tick(val value: Double, val y: Float)

/**
 * What a trend chart draws, already placed in a unit box, so the draw call only scales it:
 * [segments] (runs of two or more consecutive days), [dots] (a day alone between gaps), [newest]
 * (the last day, when it has a value: the larger dot), [band] (the usual range; null while the
 * baseline is learning), and the y domain with its [ticks].
 */
data class TrendGeometry(
    val segments: List<List<ChartPoint>>,
    val dots: List<ChartPoint>,
    val newest: ChartPoint?,
    val band: BandRect?,
    val yMin: Double,
    val yMax: Double,
    val ticks: List<Tick>,
    /** The usual mean's height (the detail chart's dashed rule); null without a usual range. */
    val mean: Float? = null,
) {
    companion object {

        /** How much the y domain is widened beyond the data and the band: 30 % of their span, half each side. */
        const val Y_PADDING: Double = 0.30

        /**
         * The chart in words ([describe]) followed by its emphasised last day, [lastDay] spelled out,
         * with its value or "no reading".
         */
        fun describeTrend(
            title: String,
            values: List<Double?>,
            spokenUnit: String,
            usual: ClosedFloatingPointRange<Double>?,
            format: (Double) -> String,
            lastDay: String,
            lastValue: Double?,
        ): String {
            val last = lastValue?.let { listOf(format(it), spokenUnit).filter(String::isNotEmpty).joinToString(" ") } ?: "no reading"
            return "${describe(title, values, spokenUnit, usual, format)}. $lastDay: $last."
        }

        /** The slot of [count] a touch at [x] (0 → 1) reads: the nearest, clamped to the ends; null with no slots. */
        fun slotAt(x: Float, count: Int): Int? {
            if (count <= 0) return null
            if (count == 1) return 0
            return Math.round(x.coerceIn(0f, 1f) * (count - 1)).coerceIn(0, count - 1)
        }

        /**
         * One slot per day, oldest first, null for a day with no value; [band] the usual range or
         * null; [mean] the usual mean (placed only with a band). Pure.
         */
        fun of(values: List<Double?>, band: ClosedFloatingPointRange<Double>?, mean: Double? = null): TrendGeometry {
            val present = values.filterNotNull().filter { it.isFinite() }
            if (present.isEmpty()) return TrendGeometry(emptyList(), emptyList(), null, null, 0.0, 1.0, emptyList())
            var lo = present.min()
            var hi = present.max()
            if (band != null) {
                lo = minOf(lo, band.start)
                hi = maxOf(hi, band.endInclusive)
            }
            val span = hi - lo
            val pad = if (span > 0) span * Y_PADDING / 2 else maxOf(kotlin.math.abs(hi) * 0.1, 1.0)
            val yMin = lo - pad
            val yMax = hi + pad
            fun y(v: Double): Float = ((yMax - v) / (yMax - yMin)).toFloat()
            val n = values.size
            fun x(i: Int): Float = if (n <= 1) 0.5f else i.toFloat() / (n - 1)

            val segments = mutableListOf<List<ChartPoint>>()
            val dots = mutableListOf<ChartPoint>()
            var run = mutableListOf<ChartPoint>()
            fun close() {
                if (run.size >= 2) segments += run.toList() else if (run.size == 1 && run[0].x != x(n - 1)) dots += run[0]
                run = mutableListOf()
            }
            values.forEachIndexed { i, v ->
                if (v == null || !v.isFinite()) close() else run += ChartPoint(x(i), y(v))
            }
            close()
            val last = values.lastOrNull()
            val newest = if (last != null && last.isFinite()) ChartPoint(x(n - 1), y(last)) else null
            val ticks = listOf(0.0, 0.5, 1.0).map { f -> (yMin + (yMax - yMin) * (0.15 + 0.7 * f)).let { Tick(it, y(it)) } }
            val meanY = if (band != null && mean != null && mean.isFinite()) y(mean).coerceIn(0f, 1f) else null
            return TrendGeometry(segments, dots, newest, band?.let { BandRect(y(it.endInclusive), y(it.start)) }, yMin, yMax, ticks, meanY)
        }

        /**
         * The chart in words, for a screen reader (upstream `MetricDetailView.chartSummary`,
         * MetricDetailView.swift:187-195 @ 2d07e550): how many days have data, the range, the usual band.
         */
        fun describe(
            title: String,
            values: List<Double?>,
            spokenUnit: String,
            usual: ClosedFloatingPointRange<Double>?,
            format: (Double) -> String,
        ): String {
            val present = values.filterNotNull()
            if (present.isEmpty()) return "$title chart, no data in the last ${values.size} days"
            var s = "$title over the last ${values.size} days, ${present.size} days with data, ranging from ${format(present.min())} to ${format(present.max())} $spokenUnit"
            if (usual != null) s += ". Usual range ${format(usual.start)} to ${format(usual.endInclusive)}"
            return s
        }
    }
}
