package io.github.opencircuit.app.today

import java.util.Locale

/**
 * The saved order of Today's tiles, by the rules of upstream's section order
 * ([DashboardSectionOrder.decode]): unknown and duplicate ids are dropped in place, and every tile
 * the saved value does not name is appended in canonical ([TodayTile.Metric] declaration) order —
 * so a damaged or missing value reads as the default, and an order saved before a tile existed
 * keeps working. An id is the metric's name in lower case ("heart_rate"); it never changes.
 */
object TileOrder {

    fun decode(raw: String?): List<TodayTile.Metric> {
        val result = mutableListOf<TodayTile.Metric>()
        for (id in raw.orEmpty().split(",")) {
            val metric = TodayTile.Metric.entries.firstOrNull { idOf(it) == id } ?: continue
            if (metric !in result) result += metric
        }
        return result + TodayTile.Metric.entries.filter { it !in result }
    }

    fun encode(tiles: List<TodayTile.Metric>): String = tiles.joinToString(",", transform = ::idOf)

    /** [metric] moved [by] places (−1 up, +1 down); a move past either end leaves the order as it is. */
    fun moved(order: List<TodayTile.Metric>, metric: TodayTile.Metric, by: Int): List<TodayTile.Metric> {
        val from = order.indexOf(metric)
        val to = from + by
        if (from < 0 || to !in order.indices) return order
        return order.toMutableList().apply { add(to, removeAt(from)) }
    }

    private fun idOf(metric: TodayTile.Metric): String = metric.name.lowercase(Locale.ROOT)
}
