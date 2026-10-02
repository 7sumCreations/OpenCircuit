package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/HRV.swift (@ b1c2fdd), whole.
// Upstream ported it from openwhoop's openwhoop-algos/src/sleep.rs (calculate_rmssd / rolling_hrv /
// clean_rr).
//
// Port notes:
//  • Results are 64-bit (`Long`), as upstream's `Int`: a successive difference of two 32-bit RR
//    values can reach 2^32 − 1, and the summary's mean sums window values. Inputs stay `Int`
//    (an RR interval in ms), so every value upstream returns for them is returned exactly.
//  • Same arithmetic, same order: squared differences summed as doubles, the root truncated toward
//    zero (Swift `Int(_:)`; the root of a finite non-negative mean, so it never traps).
//
// Input caveat (upstream): these consume per-beat R-R INTERVALS in milliseconds, which the ring does
// not expose today (it sends only its own finished RMSSD, `BulkRecord.hrvRMSSD`). The maths is
// general and ready if a raw beat-to-beat stream is ever decoded.

import kotlin.math.sqrt

/** HRV as RMSSD over RR intervals (ms). */
object HRV {

    /**
     * RMSSD over one window of RR intervals (ms): the square root of the mean of squared successive
     * differences. `null` for windows shorter than 2. The result truncates toward zero, matching
     * openwhoop's `as u64`.
     */
    fun rmssd(window: List<Int>): Long? {
        if (window.size < 2) return null
        var sumSq = 0.0
        for (i in 1 until window.size) {
            val d = (window[i].toLong() - window[i - 1].toLong()).toDouble()
            sumSq += d * d
        }
        val mean = sumSq / (window.size - 1).toDouble()
        return sqrt(mean).toLong()
    }

    /**
     * Rolling RMSSD over consecutive windows of [windowSize] (openwhoop uses 300): one value per
     * window position; empty when [windowSize] is below 2 or longer than [rr]. The work is
     * `rr.size × windowSize`, as upstream's; nothing is allocated beyond the result.
     */
    fun rollingRMSSD(rr: List<Int>, windowSize: Int = 300): List<Long> {
        if (windowSize < 2 || rr.size < windowSize) return emptyList()
        val out = ArrayList<Long>(rr.size - windowSize + 1)
        for (start in 0..(rr.size - windowSize)) {
            rmssd(rr.subList(start, start + windowSize))?.let { out += it }
        }
        return out
    }

    /** Flatten per-reading RR sample groups and drop non-positive artifacts (openwhoop `clean_rr`). */
    fun cleanRR(groups: List<List<Int>>): List<Int> = groups.flatten().filter { it > 0 }

    /** Summary of rolling HRV across a span (e.g. a night). */
    data class Summary(val min: Long, val max: Long, val avg: Long)

    /** [Summary] of [rollingRMSSD]; `null` when there is no window. The mean is an integer mean, as openwhoop's. */
    fun summary(rr: List<Int>, windowSize: Int = 300): Summary? {
        val series = rollingRMSSD(rr, windowSize)
        if (series.isEmpty()) return null
        var sum = 0L
        for (v in series) sum += v
        return Summary(min = series.min(), max = series.max(), avg = sum / series.size)
    }
}
