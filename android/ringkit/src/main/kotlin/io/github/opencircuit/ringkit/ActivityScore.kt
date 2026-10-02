package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/ActivityScore.swift
// (@ b1c2fdd), whole.
//
// Port notes:
//  • `Input` (upstream: a struct of `var`s) is an immutable data class; a changed input is a `copy`.
//  • `Result.factors` is copied in and read-only out, and always iterates in `Factor` declaration
//    order (steps, active minutes, active energy). Upstream's `[Factor: Double]` iterates in an order
//    Swift seeds per process.
//  • Upstream sums the present factors in that per-process Dictionary order, so the rounded score of
//    an input whose weighted mean sits within a bit of a .5 tie can differ from one launch to the
//    next (measured on the pinned build). The port sums them in declaration order, always.
//  • A factor whose attainment is not a number (a NaN current value, or ∞ over an ∞ goal) is dropped
//    and the rest renormalised, exactly as a disabled goal is. Upstream lets the NaN through its
//    clamp and traps converting the NaN score to an integer (measured).

import java.util.Collections
import java.util.EnumMap

/**
 * Daily Activity Score (#95) — an on-device ESTIMATE of how active the day was, scored 0–100 with
 * tiers, from the same three daily activity goals the app tracks: STEPS, ELEVATED-HR MINUTES, and
 * ACTIVE CALORIES.
 *
 * WHY a goal-attainment proxy and NOT the RingConn app's headline number: the app's Activity Score is
 * a proprietary combination of 4 CALIBRATED intensity buckets (Vigorous/Moderate/Low/Inactive
 * durations) that live in the still-uncaptured activity record (#93) — proven cloud-computed, NOT on
 * the BLE wire. Those 4 buckets must not be fabricated (see `ExerciseMinutes`). Instead the day is
 * scored against the user's OWN step / active-minute / active-kcal goals — every input is a value
 * genuinely decoded (steps, HR) or derived (`ExerciseMinutes`, `Calories`), none invented. It is an
 * on-device ESTIMATE — label it as such in the UI — not the app's algorithm, which is unseen.
 *
 * Tiers ≥ 85 / 70–84 / < 70 reuse the SleepScore house tier convention (this project's own cut-offs).
 * The RingConn app's own activity-tier cut-offs are unseen, so parity with them is not claimed.
 */
object ActivityScore {

    /** Quality tiers, matching SleepScore.Tier's cut-offs. [rawValue] is upstream's case name. */
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
     * Inputs: each is a daily current value paired with its goal — steps from the descriptor counter,
     * active minutes and active kcal from the shared `Calories.dailyEstimate`. A factor whose goal is
     * ≤ 0 (unset / disabled) is dropped and the rest are renormalised.
     */
    data class Input(
        val steps: Int,
        val stepGoal: Int,
        val activeMinutes: Double,
        val activeMinutesGoal: Double,
        val activeKcal: Double,
        val activeKcalGoal: Double,
    )

    /**
     * The result plus each present factor's 0…1 goal attainment (capped at 1; for a breakdown view). A
     * value, as upstream's struct is: [factors] is copied in and read-only out, and iterates in
     * [Factor] declaration order.
     */
    class Result(val score: Int, val tier: Tier, factors: Map<Factor, Double>) {
        val factors: Map<Factor, Double> =
            Collections.unmodifiableMap(EnumMap<Factor, Double>(Factor::class.java).apply { putAll(factors) })

        override fun equals(other: Any?): Boolean =
            other is Result && score == other.score && tier == other.tier && factors == other.factors

        override fun hashCode(): Int = listOf(score, tier, factors).hashCode()

        override fun toString(): String = "Result(score=$score, tier=$tier, factors=$factors)"

        /** The three factors; [rawValue] is upstream's case name. */
        enum class Factor(val rawValue: String) {
            STEPS("steps"),
            ACTIVE_MINUTES("activeMinutes"),
            ACTIVE_KCAL("activeKcal"),
        }
    }

    /**
     * Default factor weights (the sum need not be 1 — renormalised over the PRESENT factors). Steps
     * carry the most reliable signal (a direct on-ring count); active minutes reward sustained
     * exertion; active calories are a lighter energy modifier (partly derived from steps + HR, so
     * weighted least to avoid double-counting movement volume).
     */
    internal val FACTOR_WEIGHTS: Map<Result.Factor, Double> = mapOf(
        Result.Factor.STEPS to 0.45, Result.Factor.ACTIVE_MINUTES to 0.35, Result.Factor.ACTIVE_KCAL to 0.20,
    )

    /** Goal-attainment Activity Score (0–100) with tiers. Pure. */
    fun score(input: Input): Result {
        val f = EnumMap<Result.Factor, Double>(Result.Factor::class.java)

        // Each factor is current/goal, capped at 1 — exceeding a goal is "full credit", never > 100 %.
        // A non-positive goal drops the factor (renormalised below), and so does an attainment that is
        // not a number.
        if (input.stepGoal > 0) f[Result.Factor.STEPS] = clamp(input.steps.toDouble() / input.stepGoal.toDouble())
        attainment(input.activeMinutes, input.activeMinutesGoal)?.let { f[Result.Factor.ACTIVE_MINUTES] = it }
        attainment(input.activeKcal, input.activeKcalGoal)?.let { f[Result.Factor.ACTIVE_KCAL] = it }

        // Weighted mean over the PRESENT factors only, in declaration order (an EnumMap iterates by
        // ordinal), so a disabled goal doesn't drag the score toward zero.
        var num = 0.0
        var den = 0.0
        for ((factor, value) in f) {
            val w = FACTOR_WEIGHTS[factor] ?: 0.0
            num += w * value
            den += w
        }
        val raw = if (den > 0) num / den else 0.0
        val score = roundHalfAwayFromZero(raw * 100).toInt()
        return Result(score, Tier.of(score), f)
    }

    /** [current] over [goal], clamped to 0…1; null when the goal is not above zero or the ratio is not a number. */
    private fun attainment(current: Double, goal: Double): Double? {
        if (!(goal > 0)) return null
        val ratio = current / goal
        return if (ratio.isNaN()) null else clamp(ratio)
    }

    /** Swift's `min(max(x, 0), 1)`. */
    private fun clamp(x: Double): Double = swiftMin(swiftMax(x, 0.0), 1.0)
}
