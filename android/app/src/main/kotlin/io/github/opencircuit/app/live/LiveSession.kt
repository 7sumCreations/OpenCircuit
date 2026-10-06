package io.github.opencircuit.app.live

/** One live reading on the chart: when it arrived (monotonic milliseconds) and its value. */
data class LivePoint(val atMillis: Long, val value: Int)

/** What the live readout draws, as an immutable copy. */
data class LiveSessionSnapshot(
    /** The chart's points, oldest first: the newest [LiveSession.CAPACITY], within [LiveSession.WINDOW_MILLIS]. */
    val points: List<LivePoint> = emptyList(),
    /** Low and high of EVERY reading since the last reset, once there are two; null before. */
    val range: IntRange? = null,
)

/**
 * The readings of one live measurement: the chart's rolling points and the low–high so far.
 *
 * The chart keeps the newest [CAPACITY] points and none more than [WINDOW_MILLIS] older than the
 * newest (upstream `LiveBuffer`, `ios/OpenCircuit/Design/LivelineCharts.swift:206-223` @ b1c2fdd,
 * with its 90 s chart window). The low–high covers every reading since the last [reset], not only
 * the chart's points (upstream 6e93d53), so a long measurement does not quietly narrow it.
 *
 * Not thread-safe: its owner serialises access.
 */
class LiveSession {
    private val points = ArrayDeque<LivePoint>()
    private var low = Int.MAX_VALUE
    private var high = Int.MIN_VALUE
    private var count = 0

    /** Adds a reading taken at [atMillis] (monotonic, not earlier than the previous one). */
    fun add(atMillis: Long, value: Int) {
        points.addLast(LivePoint(atMillis, value))
        while (points.size > CAPACITY) points.removeFirst()
        while (atMillis - points.first().atMillis > WINDOW_MILLIS) points.removeFirst()
        low = minOf(low, value)
        high = maxOf(high, value)
        count++
    }

    /** Forgets every reading. */
    fun reset() {
        points.clear()
        low = Int.MAX_VALUE
        high = Int.MIN_VALUE
        count = 0
    }

    /** The current points and range, copied. */
    fun snapshot(): LiveSessionSnapshot =
        LiveSessionSnapshot(points = points.toList(), range = if (count >= 2) low..high else null)

    companion object {
        /** Most points the chart keeps. */
        const val CAPACITY = 120

        /** How far back from the newest point the chart reaches. */
        const val WINDOW_MILLIS = 90_000L
    }
}
