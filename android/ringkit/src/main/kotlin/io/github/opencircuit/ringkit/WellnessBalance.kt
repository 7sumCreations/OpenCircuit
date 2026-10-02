package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/WellnessBalance.swift
// (@ b1c2fdd), whole.
//
// Port notes:
//  • `Input` (upstream: a struct of `var`s) is an immutable data class; a changed input is a `copy`.
//  • `Result.factors` is copied in and read-only out, and always iterates in `Factor` declaration
//    order (sleep, recovery, vitals, activity). Upstream's `[Factor: Double]` iterates in an order
//    Swift seeds per process.
//  • Upstream sums the present factors in that per-process Dictionary order, so the rounded score of
//    an input whose weighted mean sits within a bit of a .5 tie can differ from one launch to the
//    next (measured on the pinned build). The port sums them in declaration order, always.
//  • Every sub-score is an integer and every factor is clamped to 0…1, so the mean is always a
//    number in 0…1 and the conversion to an integer score cannot fail.
//  • `trend` sums the prior scores in 64 bits, as upstream's `Int`, so no list of `Int`s wraps.

import java.util.Collections
import java.util.EnumMap

/**
 * Wellness Balance / readiness (#97) — a composite daily readiness score (0–100) that weight-combines
 * the sub-scores already computed: last night's Sleep Score, overnight recovery (the inverse of
 * overnight stress), Vitals Status vs the personal baseline, and the day's Activity Score (#95).
 * Tiers 85–100 Excellent / 60–84 Good / 0–59 Needs Improvement.
 *
 * Every factor is OPTIONAL and dropped (renormalised over the present factors) when its source hasn't
 * landed yet — so a morning with a sleep summary but no activity data still yields an honest
 * readiness, never a fabricated one. It is an on-device ESTIMATE, labeled as such in the UI; it is
 * NOT the RingConn app's proprietary readiness number.
 *
 * Weighting rationale: readiness is recovery-dominant. Sleep is the single biggest recovery driver,
 * then overnight autonomic recovery (stress/HRV), then vitals-vs-baseline, with the day's activity a
 * lighter contributor. Weights need not sum to 1 — renormalised on present.
 */
object WellnessBalance {

    /**
     * Readiness tiers per #97 (this project's own readiness bands: 85–100 / 60–84 / 0–59). The "good"
     * floor is 60 — intentionally more lenient than SleepScore/ActivityScore's 70 — because readiness
     * has no direct RingConn-app equivalent to match, and one low sub-score shouldn't tip an
     * otherwise-recovered day into "needs improvement". [rawValue] is upstream's case name.
     */
    enum class Tier(val rawValue: String) {
        /** ≥ 85 */
        EXCELLENT("excellent"),

        /** 60–84 */
        GOOD("good"),

        /** < 60 */
        NEEDS_IMPROVEMENT("needsImprovement"),
        ;

        companion object {
            fun of(score: Int): Tier = when {
                score >= 85 -> EXCELLENT
                score >= 60 -> GOOD
                else -> NEEDS_IMPROVEMENT
            }
        }
    }

    /**
     * Sub-scores, each optional so a missing one is renormalised out rather than invented:
     * [sleepScore] 0–100 (the night's stored sleep score), [overnightStress] the overnight stress
     * score (higher = MORE stress; SleepStress clamps it to 15…90), [vitalsStatus] normal / watch /
     * anomaly, [activityScore] 0–100 (#95).
     */
    data class Input(
        val sleepScore: Int? = null,
        val overnightStress: Int? = null,
        val vitalsStatus: VitalsBaseline.Status? = null,
        val activityScore: Int? = null,
    )

    /**
     * The composite plus each present factor's 0…1 sub-score (for a breakdown view). A value, as
     * upstream's struct is: [factors] is copied in and read-only out, and iterates in [Factor]
     * declaration order.
     */
    class Result(val score: Int, val tier: Tier, factors: Map<Factor, Double>) {
        val factors: Map<Factor, Double> =
            Collections.unmodifiableMap(EnumMap<Factor, Double>(Factor::class.java).apply { putAll(factors) })

        override fun equals(other: Any?): Boolean =
            other is Result && score == other.score && tier == other.tier && factors == other.factors

        override fun hashCode(): Int = listOf(score, tier, factors).hashCode()

        override fun toString(): String = "Result(score=$score, tier=$tier, factors=$factors)"

        /** The four factors; [rawValue] is upstream's case name. */
        enum class Factor(val rawValue: String) {
            SLEEP("sleep"),
            RECOVERY("recovery"),
            VITALS("vitals"),
            ACTIVITY("activity"),
        }
    }

    /** Default factor weights (the sum need not be 1 — renormalised over the PRESENT factors). */
    internal val FACTOR_WEIGHTS: Map<Result.Factor, Double> = mapOf(
        Result.Factor.SLEEP to 0.40, Result.Factor.RECOVERY to 0.25, Result.Factor.VITALS to 0.20, Result.Factor.ACTIVITY to 0.15,
    )

    /**
     * Composite readiness (0–100) with tiers. Returns null when NO sub-score is available, so the UI
     * shows "—" rather than a meaningless 0. Pure.
     */
    fun score(input: Input): Result? {
        val f = EnumMap<Result.Factor, Double>(Result.Factor::class.java)

        input.sleepScore?.let { s -> f[Result.Factor.SLEEP] = clamp01(s.toDouble() / 100) }
        input.overnightStress?.let { st ->
            // `overnightStress` is the SleepStress overnight score, which clamps to
            // [SleepStress.LOW_SCORE, SleepStress.HIGH_SCORE] (15…90) — NOT the full 1…100. Map that
            // ACTUAL achievable range to a 0…1 recovery factor (higher stress ⇒ lower recovery) so
            // recovery spans the full 0…1 instead of the compressed [0.10, 0.85] a naive
            // `1 − stress/100` would give. Values outside the range clamp.
            val lo = SleepStress.LOW_SCORE
            val hi = SleepStress.HIGH_SCORE
            f[Result.Factor.RECOVERY] = clamp01((hi - st.toDouble()) / (hi - lo))
        }
        input.vitalsStatus?.let { v -> f[Result.Factor.VITALS] = vitalsFactor(v) }
        input.activityScore?.let { a -> f[Result.Factor.ACTIVITY] = clamp01(a.toDouble() / 100) }

        if (f.isEmpty()) return null

        // Weighted mean over the PRESENT factors, in declaration order (an EnumMap iterates by ordinal).
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

    /**
     * Readiness ANCHORED on last night: null unless a sleep sub-score is present, so a day with only
     * activity data never synthesises a readiness from activity alone (never fabricate a health value
     * from a weak signal). This is the entry point the UI headline should call; the unanchored [score]
     * is the general renormalising blender. Pure.
     */
    fun anchoredScore(input: Input): Result? = if (input.sleepScore == null) null else score(input)

    /**
     * Vitals Status → recovery factor. A clean baseline is full credit; a "watch" halves it; an anomaly
     * (a Significant outlier or a suspected fever) zeroes it.
     */
    internal fun vitalsFactor(status: VitalsBaseline.Status): Double = when (status) {
        VitalsBaseline.Status.NORMAL -> 1.0
        VitalsBaseline.Status.WATCH -> 0.5
        VitalsBaseline.Status.ANOMALY -> 0.0
    }

    // Trend

    /** Today's readiness against recent days; [rawValue] is upstream's case name. */
    enum class Trend(val rawValue: String) { UP("up"), STEADY("steady"), DOWN("down") }

    /**
     * Today's readiness vs the mean of recent prior scores, with a [deadband] so tiny wiggles read as
     * steady. [prior] is recent readiness scores (any order); steady when empty. The prior scores are
     * summed in 64 bits, as upstream's `Int`.
     */
    fun trend(today: Int, prior: List<Int>, deadband: Int = 3): Trend {
        if (prior.isEmpty()) return Trend.STEADY
        var sum = 0L
        for (p in prior) sum += p
        val mean = sum.toDouble() / prior.size.toDouble()
        val delta = today.toDouble() - mean
        if (delta > deadband.toDouble()) return Trend.UP
        if (delta < -deadband.toDouble()) return Trend.DOWN
        return Trend.STEADY
    }

    /** Swift's `min(max(x, 0), 1)`. */
    private fun clamp01(x: Double): Double = swiftMin(swiftMax(x, 0.0), 1.0)
}
