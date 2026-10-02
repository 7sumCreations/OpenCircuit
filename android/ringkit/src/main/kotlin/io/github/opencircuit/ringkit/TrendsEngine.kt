package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/TrendsEngine.swift (@ b1c2fdd),
// whole.
//
// Port notes:
//  • `DailyPoint` and `RollingAverages` are immutable data classes (upstream's structs) that compare
//    their doubles by IEEE `==`, as Swift's synthesized `Equatable` does (NaN is unequal to itself,
//    −0.0 equals 0.0 and hashes alike).
//  • The counts (`steps`, `sleepMinutes`, the scores, the bedtime minutes) are Kotlin `Int`s where
//    upstream's `Int` is 64-bit: every decoded count fits. They are summed in 64 bits, as upstream, so
//    no list of `Int`s can overflow the sum.
//  • Sums are left folds from zero, in list order, as upstream's `reduce(0, +)`.
//  • `cos`, `sin` and `log` are `StrictMath`'s (fdlibm), the same bits on every JVM and on Android;
//    the clamp of R is Swift's `min` / `max`.
//  • A negative window takes no days, as window 0 does; upstream traps on a negative suffix length.

import java.time.Instant
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Rolling daily-trend aggregates for every tracked metric: daily points, N-day rolling means, the
 * direction of a metric over two adjacent windows, and how regular bedtime is.
 *
 * Available data only: all-day and sleep-window HR / HRV / SpO2 / RR averages, steps, the activity
 * ESTIMATEs (active energy, distance, exercise minutes — derived from the day's steps and HR, the same
 * basis the health-store writer uses), nightly and daytime skin temperature, the sleep score and the
 * overnight stress score. The HR guard (`> 29` bpm) is the official app's SQL guard.
 */
object TrendsEngine {

    // Data types

    /**
     * One day's aggregated data for the trends display. Every metric is optional: null means not
     * available (no sleep window that night, samples pruned, metric not decoded). [date] is the start
     * of the calendar day the point represents — a label: the functions here take points by position.
     */
    data class DailyPoint(
        val date: Instant,
        // Activity
        val steps: Int? = null,
        // Sleep
        val sleepMinutes: Int? = null, // total asleep minutes
        val sleepScore: Int? = null, // composite 0–100 (null / 0 = not computed)
        val stressScore: Int? = null, // overnight stress 0–100 (null / 0 = not computed)
        val skinTempC: Double? = null, // nightly skin temp °C (null / 0 = no data)
        val dayTempC: Double? = null, // daytime skin temp °C average
        // Sleep-window vitals (stored epoch samples within the night's window)
        val sleepHRAvg: Double? = null,
        val sleepHRVAvg: Double? = null, // ms RMSSD
        val sleepSpO2Avg: Double? = null, // 0…1 fraction
        val sleepRRAvg: Double? = null, // breaths/min
        // All-day vitals (the same stored samples, over the whole calendar day)
        val dayHRAvg: Double? = null,
        val dayHRVAvg: Double? = null, // ms RMSSD
        val daySpO2Avg: Double? = null, // 0…1 fraction
        val dayRRAvg: Double? = null, // breaths/min
        // Activity ESTIMATEs (derived from the day's steps / HR)
        val activeEnergyKcal: Double? = null,
        val distanceM: Double? = null,
        val exerciseMin: Double? = null,
    ) {
        private fun doubles(): List<Double?> = listOf(
            skinTempC, dayTempC, sleepHRAvg, sleepHRVAvg, sleepSpO2Avg, sleepRRAvg,
            dayHRAvg, dayHRVAvg, daySpO2Avg, dayRRAvg, activeEnergyKcal, distanceM, exerciseMin,
        )

        override fun equals(other: Any?): Boolean =
            other is DailyPoint && date == other.date && steps == other.steps && sleepMinutes == other.sleepMinutes &&
                sleepScore == other.sleepScore && stressScore == other.stressScore && ieeeEqual(doubles(), other.doubles())

        override fun hashCode(): Int =
            listOf(date, steps, sleepMinutes, sleepScore, stressScore).hashCode() * 31 + ieeeHashOf(doubles())
    }

    // 7-day rolling averages

    /** Rolling averages over a window of [DailyPoint]s; null where the window holds no valid value. */
    data class RollingAverages(
        val steps: Double?,
        val sleepMinutes: Double?,
        val sleepScore: Double?,
        val stressScore: Double?,
        val skinTempC: Double?,
        val dayTempC: Double?,
        val sleepHRAvg: Double?,
        val sleepHRVAvg: Double?,
        val sleepSpO2Avg: Double?,
        val sleepRRAvg: Double?,
        val dayHRAvg: Double?,
        val dayHRVAvg: Double?,
        val daySpO2Avg: Double?,
        val dayRRAvg: Double?,
        val activeEnergyKcal: Double?,
        val distanceM: Double?,
        val exerciseMin: Double?,
    ) {
        private fun values(): List<Double?> = listOf(
            steps, sleepMinutes, sleepScore, stressScore, skinTempC, dayTempC, sleepHRAvg, sleepHRVAvg, sleepSpO2Avg,
            sleepRRAvg, dayHRAvg, dayHRVAvg, daySpO2Avg, dayRRAvg, activeEnergyKcal, distanceM, exerciseMin,
        )

        override fun equals(other: Any?): Boolean = other is RollingAverages && ieeeEqual(values(), other.values())

        override fun hashCode(): Int = ieeeHashOf(values())
    }

    /** The official app's SQL guard: readings ≤ 29 bpm are excluded. */
    const val MIN_VALID_HR: Double = 29.0

    /**
     * Rolling averages over the TRAILING [window] points (7 by default). Uses only present values that
     * pass each metric's guard (HR `> 29`, every other double and both scores `> 0`; steps and sleep
     * minutes as they are); null when there is no valid value at all. A negative window takes no points.
     */
    fun rollingAverages(points: List<DailyPoint>, window: Int = 7): RollingAverages {
        val tail = trailing(points, window)
        fun d(f: (DailyPoint) -> Double?, keep: (Double) -> Boolean): Double? = avgDouble(tail.mapNotNull(f).filter(keep))
        val positive: (Double) -> Boolean = { it > 0 }
        val validHR: (Double) -> Boolean = { it > MIN_VALID_HR }
        return RollingAverages(
            steps = avgInt(tail.mapNotNull { it.steps }),
            sleepMinutes = avgInt(tail.mapNotNull { it.sleepMinutes }),
            sleepScore = avgInt(tail.mapNotNull { it.sleepScore }.filter { it > 0 }),
            stressScore = avgInt(tail.mapNotNull { it.stressScore }.filter { it > 0 }),
            skinTempC = d({ it.skinTempC }, positive),
            dayTempC = d({ it.dayTempC }, positive),
            sleepHRAvg = d({ it.sleepHRAvg }, validHR),
            sleepHRVAvg = d({ it.sleepHRVAvg }, positive),
            sleepSpO2Avg = d({ it.sleepSpO2Avg }, positive),
            sleepRRAvg = d({ it.sleepRRAvg }, positive),
            dayHRAvg = d({ it.dayHRAvg }, validHR),
            dayHRVAvg = d({ it.dayHRVAvg }, positive),
            daySpO2Avg = d({ it.daySpO2Avg }, positive),
            dayRRAvg = d({ it.dayRRAvg }, positive),
            activeEnergyKcal = d({ it.activeEnergyKcal }, positive),
            distanceM = d({ it.distanceM }, positive),
            exerciseMin = d({ it.exerciseMin }, positive),
        )
    }

    // Trend direction

    /** The direction of a metric. [rawValue] is upstream's raw value. */
    enum class Trend(val rawValue: String) { UP("up"), DOWN("down"), FLAT("flat") }

    /**
     * Compares the mean of the trailing [window] points with the mean of the [window] points before
     * them. Null when there are fewer than 2 points, or when either window holds no value [extract]
     * returns. [minDeltaFraction] is the smallest relative change that is not flat; a prior mean of
     * zero (or not a number) is flat. A negative window takes no points.
     */
    fun trend(
        points: List<DailyPoint>,
        window: Int = 7,
        minDeltaFraction: Double = 0.03,
        extract: (DailyPoint) -> Double?,
    ): Trend? {
        if (points.size < 2) return null
        val recent = trailing(points, window)
        val prior = trailing(points.dropLast(recent.size), window)
        val recentVals = recent.mapNotNull(extract)
        val priorVals = prior.mapNotNull(extract)
        if (recentVals.isEmpty() || priorVals.isEmpty()) return null
        val recentMean = sum(recentVals) / recentVals.size.toDouble()
        val priorMean = sum(priorVals) / priorVals.size.toDouble()
        if (!(abs(priorMean) > 0)) return Trend.FLAT
        val delta = (recentMean - priorMean) / abs(priorMean)
        if (delta > minDeltaFraction) return Trend.UP
        if (delta < -minDeltaFraction) return Trend.DOWN
        return Trend.FLAT
    }

    // Sleep regularity

    /**
     * Sleep regularity score 0–100: how consistent bedtime is across the last [window] nights.
     * Bedtime is minutes since midnight — a CIRCULAR quantity (23:58 and 00:02 are 4 min apart, not
     * 1436), so each minute maps to an angle, R is the mean resultant length, and the circular
     * standard deviation is converted back to minutes: 0 min → 100, 60 min or more → 0. A tight
     * near-midnight cluster scores near 100. Null when fewer than 2 nights are in the window. A
     * negative window takes no nights.
     */
    fun sleepRegularity(bedtimeMinutes: List<Int>, window: Int = 7): Int? {
        val tail = trailing(bedtimeMinutes, window)
        if (tail.size < 2) return null
        val n = tail.size.toDouble()
        val angles = tail.map { 2.0 * Math.PI * it.toDouble() / 1440.0 }
        val meanCos = sum(angles.map { StrictMath.cos(it) }) / n
        val meanSin = sum(angles.map { StrictMath.sin(it) }) / n
        // Mean resultant length R ∈ [0, 1]: 1 = perfectly clustered, 0 = uniformly spread.
        val r = swiftMin(swiftMax(sqrt(meanCos * meanCos + meanSin * meanSin), 0.0), 1.0)
        // Circular standard deviation (radians) = sqrt(-2 ln R); R → 0 means maximal spread (π rad).
        val circStdDevRad = if (r > 1e-9) sqrt(-2.0 * StrictMath.log(r)) else Math.PI
        val stdDevMinutes = circStdDevRad * 1440.0 / (2.0 * Math.PI)
        // R ≤ 1 keeps the deviation finite and in 0…1475 min, so the truncation below cannot fail.
        val score = maxOf(0, ((1.0 - stdDevMinutes / 60.0) * 100.0).toInt())
        return minOf(score, 100)
    }

    // Private helpers

    /** The trailing [window] elements (Swift's `suffix`); a negative window takes none. */
    private fun <T> trailing(xs: List<T>, window: Int): List<T> = xs.takeLast(maxOf(window, 0))

    /** A left fold from zero, in order (upstream's `reduce(0, +)`). */
    private fun sum(vals: List<Double>): Double = vals.fold(0.0) { a, x -> a + x }

    private fun avgDouble(vals: List<Double>): Double? = if (vals.isEmpty()) null else sum(vals) / vals.size.toDouble()

    /** Summed in 64 bits, as upstream's `Int`. */
    private fun avgInt(vals: List<Int>): Double? =
        if (vals.isEmpty()) null else vals.fold(0L) { a, x -> a + x }.toDouble() / vals.size.toDouble()

    /** Optional doubles compared as Swift's `==`: both absent, or both present and IEEE-equal. */
    private fun ieeeEqual(a: List<Double?>, b: List<Double?>): Boolean =
        a.size == b.size && a.indices.all { k ->
            val x = a[k]
            val y = b[k]
            if (x == null || y == null) x == null && y == null else x == y
        }

    private fun ieeeHashOf(xs: List<Double?>): Int = xs.map { it?.let(::ieeeHash) }.hashCode()
}
