package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/SleepStress.swift (@ b1c2fdd),
// whole.
//
// Port notes:
//  • State durations are `Duration`s (upstream `TimeInterval`); each is a whole number of seconds.
//  • A NaN RMSSD (upstream traps converting the score to `Int`) is rejected with an
//    `IllegalArgumentException`; every other value scores as upstream.

import java.time.Duration
import kotlin.math.ln

/**
 * Overnight / sleep stress score from sleep-window HRV. NOT a port of a Baevsky stress index over
 * RR intervals (those are not decoded): the only HRV decoded is the per-epoch RMSSD at `0x4c[5]`
 * during sleep, so this maps that one statistic to the app's stress bands. OVERNIGHT stress only —
 * all-day stress needs daytime HRV, which is not decoded. Label it "overnight stress (estimate)".
 *
 * Bands are the app's own (1–29 relaxed, 30–59 normal, 60–79 medium, 80–100 high). Higher HRV ⇒
 * more parasympathetic ⇒ LOWER stress. The RMSSD → score curve is a heuristic reference range,
 * interpolated in log space (HRV is roughly log-normal); the band thresholds are the app's.
 */
object SleepStress {

    /** The app's stress bands over a 1–100 score. [rawValue] is upstream's case name. */
    enum class Band(val rawValue: String, val label: String) {
        /** 1–29 */
        RELAXED("relaxed", "Relaxed"),

        /** 30–59 */
        NORMAL("normal", "Normal"),

        /** 60–79 */
        MEDIUM("medium", "Medium"),

        /** 80–100 */
        HIGH("high", "High"),
        ;

        companion object {
            fun of(score: Int): Band = when {
                score < 30 -> RELAXED
                score < 60 -> NORMAL
                score < 80 -> MEDIUM
                else -> HIGH
            }
        }
    }

    /**
     * Reference RMSSD bounds (ms). At/above [RESTED_RMSSD] the score floors near "relaxed"; at/below
     * [STRESSED_RMSSD] it tops out near "high". They bracket a broad adult nocturnal RMSSD range.
     */
    const val RESTED_RMSSD: Double = 70.0
    const val STRESSED_RMSSD: Double = 15.0

    /** Score endpoints the bounds map to — inside, not at, 1/100, so one value is never over-claimed. */
    internal const val LOW_SCORE: Double = 15.0
    internal const val HIGH_SCORE: Double = 90.0

    /**
     * Map a single RMSSD (ms) to a 0–100 overnight stress score (higher = more stress). Monotonic
     * decreasing in RMSSD, clamped to the reference window; below 1 ms reads as 1 ms.
     *
     * @throws IllegalArgumentException when [rmssdMs] is NaN (upstream traps there).
     */
    fun score(rmssdMs: Double): Int {
        require(!rmssdMs.isNaN()) { "RMSSD is NaN" }
        val rmssd = swiftMax(rmssdMs, 1.0) // guard log(0)
        val hi = ln(RESTED_RMSSD)
        val lo = ln(STRESSED_RMSSD)
        // t = 0 at the rested bound, 1 at the stressed bound.
        val t = (hi - ln(rmssd)) / (hi - lo)
        val clamped = swiftMin(swiftMax(t, 0.0), 1.0)
        val s = LOW_SCORE + clamped * (HIGH_SCORE - LOW_SCORE)
        return roundHalfAwayFromZero(s).toInt()
    }

    /**
     * Overnight stress from the night's per-epoch RMSSD values: the MEDIAN (upper median for an even
     * count) of the positive values, robust to the odd noisy epoch. Null when there is no HRV (a
     * connection-free night, SpO2-only epochs). Non-positive values are dropped.
     */
    fun overnightScore(rmssd: List<Int>): Int? {
        val valid = rmssd.filter { it > 0 }.sorted()
        if (valid.isEmpty()) return null
        return score(valid[valid.size / 2].toDouble())
    }

    /** [overnightScore] straight from the night's records — the STRICT sleep-vitals HRV only. */
    @JvmName("overnightScoreOfRecords")
    fun overnightScore(records: List<BulkRecord>): Int? = overnightScore(rmssd = records.mapNotNull { it.hrvRMSSD })

    /**
     * Time in each band across the night: each epoch's RMSSD is classified on its own and counted
     * for [epochSeconds], so the UI can show "X min relaxed · Y min high" from the data rather than
     * from the nightly score. Epochs without a positive RMSSD are skipped.
     */
    fun stateDurations(rmssd: List<Int>, epochSeconds: Int = BulkRecord.EPOCH_SECONDS): Map<Band, Duration> {
        val out = LinkedHashMap<Band, Duration>()
        for (v in rmssd) {
            if (v <= 0) continue
            val band = Band.of(score(v.toDouble()))
            out[band] = (out[band] ?: Duration.ZERO).plusSeconds(epochSeconds.toLong())
        }
        return out
    }

    /** [stateDurations] straight from the night's records — the STRICT sleep-vitals HRV only. */
    @JvmName("stateDurationsOfRecords")
    fun stateDurations(records: List<BulkRecord>): Map<Band, Duration> = stateDurations(rmssd = records.mapNotNull { it.hrvRMSSD })
}
