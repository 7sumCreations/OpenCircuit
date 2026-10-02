package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/RobustBaseline.swift (@ b1c2fdd).
//
// Robust (median / MAD) personal baselines for the headache-signals index (#183).
//
// A DELIBERATELY SEPARATE namespace from `VitalsBaseline`, not a new `VitalsBaseline.Config` knob: a
// shared config with a new field is one careless default away from moving a shipped alert threshold.
//
// WHY MEDIAN/MAD RATHER THAN MEAN/SD: the days this index exists to notice are themselves the outliers
// that inflate an SD, so a mean/SD baseline is partly defined by the very days it is supposed to flag.
// The archive also contains real artifact nights (the 86 °F cold-object night documented on
// `SkinTempBaseline.FLUCTUATION_BASELINE_GATE_C`), which drag a mean far more than a median.
//
// Two deliberate differences from upstream (PORTING.md): an unreadable (NaN or infinite) prior day is
// a missing day, never a day that drags the median; and a z whose quotient overflows on finite
// readings is clamped by its sign instead of reading 0.

/** Robust (median / MAD) personal baselines. */
object RobustBaseline {

    /**
     * A robust location + scale estimate over a trailing window. [mad] is the median absolute
     * deviation, in the same units as [median], NOT yet scaled by [MAD_CONSISTENCY] — [z] applies that.
     * [n] is how many prior values the estimate was built from. Doubles compare by IEEE `==`, as
     * Swift's synthesized `Equatable`.
     */
    class Stats(val median: Double, val mad: Double, val n: Int) {
        override fun equals(other: Any?): Boolean = other is Stats && median == other.median && mad == other.mad && n == other.n

        override fun hashCode(): Int = listOf(ieeeHash(median), ieeeHash(mad), n).hashCode()

        override fun toString(): String = "Stats(median=$median, mad=$mad, n=$n)"
    }

    /**
     * Minimum prior days before any baseline exists. 🟡 Matches `VitalsBaseline.Config().minBaselineDays`
     * so the two engines agree on when a person is "known".
     */
    const val MIN_BASELINE_DAYS: Int = 7

    /**
     * Trailing window cap. 🟡 60 days. The asymptotic relative efficiency of the MAD against the SD for
     * Gaussian data is ≈ 0.368, so 60 MAD-days carry roughly the precision of 22 SD-days, not 30 — a
     * deliberate trade of precision for outlier immunity, sized to the coverage the app achieves.
     */
    const val MAX_BASELINE_DAYS: Int = 60

    /**
     * 🟢 The Gaussian consistency constant: for normally-distributed data, `1.4826 · MAD` estimates σ.
     * Without it a MAD-based z-score is on a different scale to an SD-based one.
     */
    const val MAD_CONSISTENCY: Double = 1.4826

    /**
     * 🔴 PROVISIONAL. Caps |z| so one absurd reading (a decode artifact, a ring read through a glove)
     * cannot dominate a weighted sum.
     */
    const val Z_CLAMP: Double = 4.0

    /**
     * Median + MAD over the trailing window of [prior] (oldest → newest), or null when there is not
     * enough history. [prior] must NOT include today. Null rather than a degenerate estimate below
     * [minDays]: a baseline built from three days is not a baseline.
     *
     * An unreadable (NaN or infinite) day is a missing day: it is dropped before the window is taken,
     * so it neither counts toward [minDays] nor pushes a readable day out of the window. Upstream counts
     * it, and a NaN sorts wherever `<` leaves it (two NaN days among eight give a NaN median and MAD).
     */
    fun stats(prior: List<Double>, minDays: Int = MIN_BASELINE_DAYS, maxDays: Int = MAX_BASELINE_DAYS): Stats? {
        if (minDays <= 0 || maxDays < minDays) return null
        val readable = prior.filter { it.isFinite() }
        val window = if (readable.size > maxDays) readable.takeLast(maxDays) else readable
        if (window.size < minDays) return null
        val med = median(window) ?: return null
        val deviations = window.map { kotlin.math.abs(it - med) }
        val mad = median(deviations) ?: return null
        return Stats(median = med, mad = mad, n = window.size)
    }

    /**
     * Robust z-score of [today] against [stats], clamped to ±[clamp].
     *
     * [noiseFloor] is the absolute deviation below which a difference is not worth calling a
     * difference. It floors the SCALE, not the result: a perfectly regular person has `mad == 0`, and
     * without the floor every 1-LSB wobble would come back as an infinite z.
     *
     * A result that is not finite reads as 0 (upstream's rule — an unreadable today or baseline
     * contributes nothing), EXCEPT a quotient of finite readings that overflows a positive scale: that
     * is the largest deviation there is, and it is clamped by its sign. Upstream returns 0 there too,
     * so with a zero floor and a zero MAD a deviation of 1 read 4 while one of 10 read 0.
     */
    fun z(today: Double, stats: Stats, noiseFloor: Double, clamp: Double = Z_CLAMP): Double {
        val scale = swiftMax(MAD_CONSISTENCY * stats.mad, swiftMax(noiseFloor, java.lang.Double.MIN_NORMAL))
        val raw = (today - stats.median) / scale
        if (!raw.isFinite()) {
            val overflow = raw.isInfinite() && today.isFinite() && stats.median.isFinite() && scale > 0.0
            if (!overflow) return 0.0
        }
        return swiftMin(swiftMax(raw, -clamp), clamp)
    }

    private const val DAY_MINUTES: Int = 24 * 60

    /**
     * Circular median of clock times expressed as minutes since midnight, for quantities like habitual
     * bedtime that WRAP: the plain median of 23:50 and 00:10 is midday, which would make a perfectly
     * regular sleeper look maximally irregular.
     *
     * Rotates the samples to each candidate origin and chooses the rotation with the smallest sum of
     * absolute circular deviations (an exact tie keeps the earlier candidate in input order) — exact
     * for the small n (≤ 60) this sees. Out-of-range minutes are normalised into 0…1439.
     */
    fun circularMedianMinutes(minutesSinceMidnight: List<Int>): Int? {
        val pts = minutesSinceMidnight.map { Math.floorMod(it, DAY_MINUTES) }
        if (pts.isEmpty()) return null
        if (pts.size == 1) return pts[0]

        var best = pts[0]
        var bestCost = Double.POSITIVE_INFINITY
        for (candidate in pts) {
            // Unwrap every point into the half-open window starting at `candidate`, take the plain
            // median there, then map back. Cost is the total circular distance to that centre.
            val unwrapped = pts.map { p ->
                val d = ((p - candidate + DAY_MINUTES) % DAY_MINUTES).toDouble()
                if (d > DAY_MINUTES / 2.0) d - DAY_MINUTES else d
            }
            val m = median(unwrapped) ?: continue
            var cost = 0.0
            for (u in unwrapped) cost += kotlin.math.abs(u - m)
            if (cost < bestCost) {
                bestCost = cost
                best = ((candidate + roundHalfAwayFromZero(m).toInt() % DAY_MINUTES) + DAY_MINUTES) % DAY_MINUTES
            }
        }
        return best
    }

    /**
     * Circular absolute difference between two clock times, in minutes (0 … 720). The difference is
     * taken in 64 bits, so no pair of `Int`s wraps (upstream's 64-bit subtraction traps on
     * `Int.max - (-1)`).
     */
    fun circularDeltaMinutes(a: Int, b: Int): Int {
        val d = Math.floorMod(a.toLong() - b.toLong(), DAY_MINUTES.toLong())
        return minOf(d, DAY_MINUTES - d).toInt()
    }

    /** Plain median (Swift's `sorted()` order). Even counts average the two central values. */
    internal fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = swiftSorted(values)
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
    }
}
