package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/SleepScore.swift (@ b1c2fdd),
// whole: the duration score adapted from openwhoop-algos `sleep.rs` (`:1-24`) and the 6-factor
// composite score (`:26-145`).
//
// Port notes:
//  • `CompositeInput` durations are `Double` seconds, as upstream's `TimeInterval`, so every input
//    upstream accepts (including ±Inf) gives upstream's answer.
//  • Upstream sums the present factors in Swift Dictionary order, which is seeded per process; the
//    port sums them in `Factor` declaration order. The weighted sum can differ in its last bit; the
//    rounded score differs only when the sum sits within that bit of a .5 tie.
//  • An input whose score is NaN (upstream traps converting it to `Int`) is rejected with an
//    `IllegalArgumentException`.

import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/** Sleep score, 0…100: the duration score and the 6-factor composite. */
object SleepScore {

    /** Ideal sleep duration in seconds (8 h). */
    const val IDEAL_DURATION_SECONDS: Int = 60 * 60 * 8

    /**
     * Score from a sleep duration in seconds, graded linearly and clamped at the 8 h ideal:
     * 4 h → 50, 6 h → 75, 8 h and more → 100, zero or negative → 0. openwhoop divides in integer
     * units, collapsing the score to 0-or-100; this ratio is floating point (upstream #28).
     * [durationSeconds] is 64-bit, as upstream's `Int`.
     */
    fun score(durationSeconds: Long): Double {
        val ratio = durationSeconds.toDouble() / IDEAL_DURATION_SECONDS.toDouble()
        return minOf(maxOf(ratio * 100.0, 0.0), 100.0)
    }

    /** [score] for a start/end span, in whole seconds truncated toward zero (as Swift's `Int(_:)`). */
    fun score(start: Instant, end: Instant): Double = score(Duration.between(start, end).wholeSecondsTowardZero())

    // Composite 0–100 sleep score.
    //
    // The duration score above is NOT the ring app's headline number. The app's Sleep Score is a
    // 6-factor composite — time asleep, sleep stages, sleep efficiency, heart rate, temperature and
    // time awake — with tiers ≥ 85 / 70–84 / < 70. This reproduces that SHAPE from what is decoded
    // and degrades gracefully when an optional input is missing (no resting HR / no temperature
    // baseline): the missing factor is dropped and the remaining factors are renormalised, never
    // fabricated. An on-device ESTIMATE — label it as such — not the app's proprietary algorithm.

    /** Quality tiers, at the app's cut-offs. [rawValue] is upstream's case name. */
    enum class Tier(val rawValue: String) {
        /** ≥ 85 */
        EXCELLENT("excellent"),

        /** 70–84 */
        GOOD("good"),

        /** < 70 */
        NEEDS_IMPROVEMENT("needsImprovement"),
        ;

        companion object {
            fun of(score: Int): Tier = when {
                score >= 85 -> EXCELLENT
                score >= 70 -> GOOD
                else -> NEEDS_IMPROVEMENT
            }
        }
    }

    /**
     * Inputs to the composite, all derived from decoded data; durations in seconds. The optional
     * [restingHR] (the night's resting/average HR, bpm) and [tempOffsetC] (the night's skin-temperature
     * offset from baseline) are dropped from the weighting when null, so the score never invents them.
     * [efficiency] is 0…1; [sleepGoal] is the target time asleep (default 8 h).
     */
    data class CompositeInput(
        val totalAsleep: Double,
        val timeAwake: Double,
        val efficiency: Double,
        val deep: Double,
        val light: Double,
        val rem: Double,
        val restingHR: Double? = null,
        val tempOffsetC: Double? = null,
        val sleepGoal: Double = IDEAL_DURATION_SECONDS.toDouble(),
    )

    /** The composite result plus each present factor's 0…1 sub-score (for a breakdown view). */
    data class Composite(val score: Int, val tier: Tier, val factors: Map<Factor, Double>) {
        /** The six factors; [rawValue] is upstream's case name. */
        enum class Factor(val rawValue: String) {
            TIME_ASLEEP("timeAsleep"),
            STAGES("stages"),
            EFFICIENCY("efficiency"),
            HEART_RATE("heartRate"),
            TEMPERATURE("temperature"),
            TIME_AWAKE("timeAwake"),
        }
    }

    /**
     * Default factor weights (the sum need not be 1 — the mean is renormalised over the PRESENT
     * factors). Time asleep and efficiency carry the most signal; HR and temperature are lighter
     * modifiers, as the app frames them.
     */
    internal val FACTOR_WEIGHTS: Map<Composite.Factor, Double> = mapOf(
        Composite.Factor.TIME_ASLEEP to 0.30, Composite.Factor.STAGES to 0.20, Composite.Factor.EFFICIENCY to 0.20,
        Composite.Factor.HEART_RATE to 0.10, Composite.Factor.TEMPERATURE to 0.10, Composite.Factor.TIME_AWAKE to 0.10,
    )

    /**
     * 6-factor composite sleep score (0–100) with its tier. Pure.
     *
     * @throws IllegalArgumentException when the inputs produce no number (a NaN efficiency, time
     *   awake, stage duration, resting HR or temperature offset, or ∞/∞ in a ratio) — upstream traps there.
     */
    fun composite(input: CompositeInput): Composite {
        val f = LinkedHashMap<Composite.Factor, Double>()

        // Time asleep: linear vs the goal, capped at 1 — the duration ratio, against the personal goal.
        f[Composite.Factor.TIME_ASLEEP] = clamp(if (input.sleepGoal > 0) input.totalAsleep / input.sleepGoal else 0.0)

        // Stages: reward a healthy share of restorative sleep (Deep + REM); ~40 % combined is the target.
        val asleep = swiftMax(input.deep + input.light + input.rem, 1.0)
        val restorative = (input.deep + input.rem) / asleep
        f[Composite.Factor.STAGES] = clamp(restorative / 0.40)

        // Efficiency: map [0.5, 0.95] → [0, 1].
        f[Composite.Factor.EFFICIENCY] = clamp((input.efficiency - 0.50) / (0.95 - 0.50))

        // Heart rate (optional): a lower sleeping HR is better recovery; [45, 75] bpm → [1, 0].
        input.restingHR?.let { hr -> f[Composite.Factor.HEART_RATE] = clamp((75 - hr) / (75 - 45)) }

        // Temperature (optional): closer to baseline is better; |offset| 0 → 1, ≥ the normal band → 0.
        input.tempOffsetC?.let { off -> f[Composite.Factor.TEMPERATURE] = clamp(1 - abs(off) / SkinTempBaseline.NORMAL_DEVIATION_C) }

        // Time awake: less awake-in-bed is better; 0 min → 1, ≥ 60 min → 0.
        f[Composite.Factor.TIME_AWAKE] = clamp(1 - input.timeAwake / (60 * 60))

        // Weighted mean over the PRESENT factors only, in declaration order.
        var num = 0.0
        var den = 0.0
        for (factor in Composite.Factor.entries) {
            val value = f[factor] ?: continue
            val w = FACTOR_WEIGHTS[factor] ?: 0.0
            num += w * value
            den += w
        }
        val raw = if (den > 0) num / den else 0.0
        require(!raw.isNaN()) { "composite sleep score inputs produce no number: $input" }
        val score = roundHalfAwayFromZero(raw * 100).toInt()
        val ordered = LinkedHashMap<Composite.Factor, Double>()
        for (factor in Composite.Factor.entries) f[factor]?.let { ordered[factor] = it }
        return Composite(score, Tier.of(score), ordered)
    }

    /** Swift's `min(max(x, 0), 1)`. */
    private fun clamp(x: Double): Double = swiftMin(swiftMax(x, 0.0), 1.0)
}
