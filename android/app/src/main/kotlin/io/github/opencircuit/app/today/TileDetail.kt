package io.github.opencircuit.app.today

import io.github.opencircuit.app.ui.charts.TrendGeometry
import io.github.opencircuit.ringkit.BaselineTrend
import io.github.opencircuit.ringkit.TemperatureUnit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The live measure a detail offers under its chart: only the two vitals the ring reads on demand. */
enum class DetailMeasure { HEART_RATE, SPO2 }

/** One day of the detail's window: the chart's slot, the table's row and the read-out's words ("Mon 19 Oct: 48 ms"). */
data class DetailRow(val day: LocalDate, val label: String, val value: String?, val readout: String)

/** A MIN / AVG / MAX figure. */
data class DetailStat(val label: String, val value: String, val spoken: String)

/** An x-axis label at [x] (0 → 1 across the chart). */
data class AxisLabel(val x: Float, val text: String)

/**
 * A tile's trend screen: the headline, the chart already placed (band, mean rule, the day shown as
 * the emphasised last point), its axis labels, the rows behind it (oldest first, one per chart
 * slot), MIN / AVG / MAX, "N of M days", the usual range line, what the number is, the chart in
 * words, and which Measure (if any) sits under it.
 */
data class TileDetailUi(
    val metric: TodayTile.Metric,
    val days: Int,
    val title: String,
    val subtitle: String,
    val value: String?,
    val unit: String,
    val qualifier: String,
    val delta: String?,
    val direction: TileDirection?,
    val chart: TrendGeometry,
    val xLabels: List<AxisLabel>,
    val rows: List<DetailRow>,
    val stats: List<DetailStat>,
    val count: String,
    val usual: String,
    val about: String,
    val description: String,
    val measure: DetailMeasure?,
    /** The window this detail needs is still loading: the chart and figures wait for it. */
    val loading: Boolean = false,
)

/** Builds a tile's trend screen, as upstream `MetricDetailView` (MIT `2d07e550`) does: the tile rebuilt over the chosen window. */
object TileDetailPresenter {

    /** The two windows offered. */
    val RANGES: List<Int> = listOf(14, 30)

    /**
     * [metric]'s trend over the [days] days ending on [day], from a load covering at least that
     * window. Pure: the clock and the zone are inputs; numbers through the tile's own formatter.
     */
    fun present(trends: TodayTrends, metric: TodayTile.Metric, days: Int, day: LocalDate, now: Instant, zone: ZoneId): TileDetailUi {
        require(days in RANGES) { "a detail shows 14 or 30 days" }
        val isToday = day == LocalDate.ofInstant(now, zone)
        val tile = TodayTiles.build(
            metric, trends.points, trends.restingHR, TemperatureUnit.CELSIUS, windowDays = days, now = now, zone = zone,
            heartRateRanges = trends.heartRateRanges, shownDay = day,
        )
        val usual = tile.trend?.usualRange
        val unitWords = tile.unit.takeIf(String::isNotEmpty)
        fun withUnit(v: String) = listOfNotNull(v, unitWords).joinToString(" ")
        val rows = tile.days.zip(tile.values) { start, v ->
            val d = LocalDate.ofInstant(start, zone)
            val label = SHORT.format(d)
            DetailRow(d, label, v?.let(tile::format), "$label: ${v?.let { withUnit(tile.format(it)) } ?: "no reading"}")
        }
        val present = tile.values.filterNotNull()
        val stats = if (present.isEmpty()) emptyList() else listOf(
            "MIN" to "Lowest" to present.min(),
            "AVG" to "Average" to present.fold(0.0) { a, v -> a + v } / present.size,
            "MAX" to "Highest" to present.max(),
        ).map { (labels, v) -> DetailStat(labels.first, tile.format(v), "${labels.second} ${listOf(tile.format(v), tile.spokenUnit).filter(String::isNotEmpty).joinToString(" ")}") }
        val freshness = tile.freshnessText(now, zone)
        return TileDetailUi(
            metric = metric,
            days = days,
            title = tile.title,
            subtitle = if (isToday) "Ending today" else "Ending ${SHORT.format(day)}",
            value = tile.valueText,
            unit = tile.unit,
            qualifier = tile.qualifier.replaceFirstChar { it.titlecase(Locale.ROOT) } + (freshness?.let { " · $it" } ?: ""),
            delta = tile.deltaText,
            direction = tile.statedTrend?.direction?.let {
                when (it) {
                    BaselineTrend.Direction.ABOVE -> TileDirection.UP
                    BaselineTrend.Direction.WITHIN -> TileDirection.FLAT
                    BaselineTrend.Direction.BELOW -> TileDirection.DOWN
                }
            },
            chart = TrendGeometry.of(tile.values, usual, mean = tile.trend?.baselineMean),
            xLabels = xLabels(rows, isToday),
            rows = rows,
            stats = stats,
            count = "${present.size} of ${tile.values.size} days",
            usual = tile.rangeText,
            about = TodayTiles.what(metric),
            description = TrendGeometry.describeTrend(
                tile.title, tile.values, tile.spokenUnit, usual, tile::format, LONG.format(day), tile.values.lastOrNull(),
            ),
            measure = when (metric) {
                TodayTile.Metric.HEART_RATE -> DetailMeasure.HEART_RATE
                TodayTile.Metric.SPO2 -> DetailMeasure.SPO2
                else -> null
            },
        )
    }

    /** Every third day back from the last over 14 days, every seventh over 30; the last reads "Today" on today. */
    private fun xLabels(rows: List<DetailRow>, isToday: Boolean): List<AxisLabel> {
        val n = rows.size
        val every = if (n > 14) 7 else 3
        return (n - 1 downTo 0 step every).reversed().map { i ->
            val text = if (i == n - 1 && isToday) TODAY else rows[i].day.dayOfMonth.toString()
            AxisLabel(if (n <= 1) 0.5f else i.toFloat() / (n - 1), text)
        }
    }

    private val SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val LONG: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
}
