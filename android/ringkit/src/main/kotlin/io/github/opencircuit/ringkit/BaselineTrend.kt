package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/BaselineTrend.swift
// (@ 2d07e550, the last MIT commit to touch it; the global pin stays b1c2fdd).
//
// Baseline trend — where the latest daily value of a metric sits against the user's OWN recent
// history, for the Today screen's metric tiles.
//
// Deliberately simple and honest: the baseline is the mean of the prior days in the series (the
// tiles pass the trailing 14 days), the latest value is compared to it with a deadband, and the
// answer is null — "still building a baseline" — until enough prior days exist. It never fills
// gaps, never extrapolates, and never scores anything: it only says above / within / below.
//
// The deadband is the LARGER of a per-metric absolute floor (so a tiny spread can't make ordinary
// noise read as a change) and half the baseline's standard deviation (so a naturally noisy metric
// needs a proportionally bigger move). This is a display heuristic, not the Vitals Status
// classifier (`VitalsBaseline`), which stays the one place that flags anomalies.
//
// Kotlin differences, each deliberate:
// - Points are ordered with a STABLE sort by date: Swift's `sorted` gives no order to two points on
//   the same date, so upstream's "latest" among equal dates is unspecified; here it is the one
//   listed last.
// - Values compare their doubles by IEEE `==`, as Swift's synthesized `Equatable`.

/** The latest daily value of a metric against the mean of the days before it. */
object BaselineTrend {

    /** Where the latest value sits against the usual range. [rawValue] is upstream's raw name. */
    enum class Direction(val rawValue: String) { ABOVE("above"), WITHIN("within"), BELOW("below") }

    /** One dated daily value. Callers pass one point per day, oldest first or in any order. */
    class Point(val date: Instant, val value: Double) {
        override fun equals(other: Any?): Boolean = other is Point && date == other.date && value == other.value

        override fun hashCode(): Int = 31 * date.hashCode() + ieeeHash(value)

        override fun toString(): String = "Point(date=$date, value=$value)"
    }

    /**
     * The comparison. [latest] is the newest value and its day; [baselineMean] the mean of the prior
     * days (null when there are none); [baselineDays] how many prior days it was built from;
     * [direction] null while the baseline is too thin to judge; [bandHalfWidth] the half-width of the
     * deadband around the mean, null while the baseline is too thin. The "usual range" drawn and
     * labelled in the UI is exactly this band, so a value inside it is the one the direction calls
     * [Direction.WITHIN] — the chart, the label and the arrow can't disagree.
     */
    class Result(
        val latest: Point,
        val baselineMean: Double?,
        val baselineDays: Int,
        val direction: Direction?,
        val bandHalfWidth: Double?,
    ) {
        /** `baselineMean ± bandHalfWidth`, or null while the baseline is too thin. */
        val usualRange: ClosedFloatingPointRange<Double>?
            get() {
                val m = baselineMean ?: return null
                val h = bandHalfWidth ?: return null
                return (m - h)..(m + h)
            }

        /** `latest.value - baselineMean`, or null without a baseline. */
        val delta: Double?
            get() = baselineMean?.let { latest.value - it }

        override fun equals(other: Any?): Boolean =
            other is Result && latest == other.latest && ieeeEquals(baselineMean, other.baselineMean) &&
                baselineDays == other.baselineDays && direction == other.direction && ieeeEquals(bandHalfWidth, other.bandHalfWidth)

        override fun hashCode(): Int =
            listOf(latest, baselineMean?.let(::ieeeHash), baselineDays, direction, bandHalfWidth?.let(::ieeeHash)).hashCode()

        override fun toString(): String =
            "Result(latest=$latest, baselineMean=$baselineMean, baselineDays=$baselineDays, direction=$direction, bandHalfWidth=$bandHalfWidth)"
    }

    /** Default number of prior days required before a direction is reported. */
    const val DEFAULT_MIN_BASELINE_DAYS: Int = 4

    /**
     * Compares the newest point against the mean of the others. Returns null for an empty series.
     * Non-finite values are dropped rather than trusted. Equal dates keep the order they were given.
     */
    fun evaluate(
        series: List<Point>,
        minAbsoluteDelta: Double,
        minBaselineDays: Int = DEFAULT_MIN_BASELINE_DAYS,
        sdFraction: Double = 0.5,
    ): Result? {
        val clean = series.filter { it.value.isFinite() }.sortedBy { it.date }
        val latest = clean.lastOrNull() ?: return null
        val prior = clean.dropLast(1).map { it.value }
        if (prior.isEmpty()) {
            return Result(latest, baselineMean = null, baselineDays = 0, direction = null, bandHalfWidth = null)
        }
        // Left-to-right summation, as Swift's `reduce(0, +)`.
        val mean = prior.fold(0.0) { acc, v -> acc + v } / prior.size
        if (prior.size < max(minBaselineDays, 1)) {
            return Result(latest, baselineMean = mean, baselineDays = prior.size, direction = null, bandHalfWidth = null)
        }
        val variance = prior.fold(0.0) { acc, v -> acc + (v - mean) * (v - mean) } / prior.size
        val band = max(abs(minAbsoluteDelta), sdFraction * sqrt(variance))
        val delta = latest.value - mean
        val direction = if (delta > band) Direction.ABOVE else if (delta < -band) Direction.BELOW else Direction.WITHIN
        return Result(latest, baselineMean = mean, baselineDays = prior.size, direction = direction, bandHalfWidth = band)
    }

    private fun ieeeEquals(a: Double?, b: Double?): Boolean = if (a == null || b == null) a == null && b == null else a == b
}
