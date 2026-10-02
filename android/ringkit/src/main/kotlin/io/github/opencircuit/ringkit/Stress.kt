package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/Stress.swift (@ b1c2fdd),
// whole. Upstream ported it from openwhoop-algos/src/stress.rs.
//
// Port notes:
//  • The RR range and the mode are taken in 64 bits, as upstream's `Int`: two 32-bit intervals can
//    differ by 2^32 − 1, which a 32-bit subtraction would wrap to −1 and read as constant RR.
//  • Bins use integer division truncating toward zero, as Swift's; `rounded()` and `min` are
//    Swift's (`roundHalfAwayFromZero`, `swiftMin`).
//
// Not the overnight stress score (`SleepStress`, from the decoded RMSSD). This index needs per-beat
// R-R intervals, which the ring does not expose today (see `HRV`).

/** Baevsky stress index over RR intervals, on a 0…10 scale. */
object Stress {

    /** Baevsky's standard 50 ms histogram bin width. */
    internal const val BIN_WIDTH: Int = 50

    /**
     * Stress index from RR intervals (ms), as `StressCalcParams::stress_score`. RR with no
     * variability (constant, a single value or none) returns the maximum, 10.0.
     */
    fun index(rr: List<Int>): Double {
        val count = rr.size
        val minRR = rr.minOrNull() ?: 0
        val maxRR = rr.maxOrNull() ?: 0

        // Histogram, 50 ms bins. On a tie the higher bin key wins (Rust BTreeMap max_by returns the
        // last maximal element in key order).
        val bins = HashMap<Int, Int>()
        for (v in rr) bins.merge(v / BIN_WIDTH, 1, Int::plus)
        var modeBin = 0
        var modeFreq = 0
        for (key in bins.keys.sorted()) {
            val freq = bins.getValue(key)
            if (freq >= modeFreq) {
                modeFreq = freq
                modeBin = key
            }
        }
        val mode = modeBin.toLong() * BIN_WIDTH + BIN_WIDTH / 2

        val vr = (maxRR.toLong() - minRR.toLong()).toDouble() / 1000.0
        if (vr < 0.0001) return 10.0

        val aMode = modeFreq.toDouble() / count.toDouble() * 100.0
        return swiftMin(roundHalfAwayFromZero(aMode / (2.0 * vr * mode.toDouble() / 1000.0)), 1000.0) / 100.0
    }
}
