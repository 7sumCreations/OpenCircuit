package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.ActivityScore.Input
import io.github.opencircuit.ringkit.ActivityScore.Result.Factor
import io.github.opencircuit.ringkit.ActivityScore.Tier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Kotlin-only hostile-input checks for the activity score: what the upstream vectors never feed in.
 * The day's active minutes and active energy come from the daily estimate, which answers NaN for an
 * impossible profile (a NaN body weight) and infinity for an unbounded one; the goals are user
 * settings that can be zero, negative or unreadable. So here every current value and goal arrives
 * NaN, infinite, signed zero, negative or at the ends of `Int`. Kept out of the upstream-port class
 * so its count stays exact.
 *
 * Every upstream outcome quoted below was measured on the pinned Swift build (Swift 6.3.2). Where the
 * port deliberately differs the test says so, and `PORTING.md` records why.
 */
class ActivityScoreHazardTest {

    private val nan = Double.NaN
    private val inf = Double.POSITIVE_INFINITY

    private fun score(steps: Int, stepGoal: Int, minutes: Double, minutesGoal: Double, kcal: Double, kcalGoal: Double) =
        ActivityScore.score(Input(steps, stepGoal, minutes, minutesGoal, kcal, kcalGoal))

    @Test
    fun anUnreadableAttainmentDropsItsFactorInsteadOfTrapping() {
        // Upstream: a NaN current value (or ∞ against an ∞ goal) gives a NaN attainment that its clamp
        // passes through (Swift's max(NaN, 0) is NaN), the weighted mean is NaN, and converting it to
        // Int TRAPS — measured, "Double value cannot be converted to Int because it is either infinite
        // or NaN" (exit 133) for NaN minutes, NaN energy and ∞/∞ minutes. A NaN body weight makes the
        // day's energy NaN, so this is one bad profile away from a crash. The port drops an unreadable
        // factor exactly as it drops a disabled goal, and scores the rest.
        val nanMinutes = score(5000, 10_000, nan, 30.0, 250.0, 500.0)
        assertEquals(50, nanMinutes.score)
        assertEquals(mapOf(Factor.STEPS to 0.5, Factor.ACTIVE_KCAL to 0.5), nanMinutes.factors)
        val nanKcal = score(5000, 10_000, 15.0, 30.0, nan, 500.0)
        assertEquals(50, nanKcal.score)
        assertEquals(mapOf(Factor.STEPS to 0.5, Factor.ACTIVE_MINUTES to 0.5), nanKcal.factors)
        val infOverInf = score(5000, 10_000, inf, inf, 250.0, 500.0)
        assertEquals(50, infOverInf.score)
        assertEquals(setOf(Factor.STEPS, Factor.ACTIVE_KCAL), infOverInf.factors.keys)
        val nothingReadable = score(5000, 0, nan, 30.0, -inf, -inf)
        assertEquals(0, nothingReadable.score, "no factor left: upstream's empty-mean answer")
        assertEquals(Tier.NEEDS_IMPROVEMENT, nothingReadable.tier)
        assertEquals(emptyMap(), nothingReadable.factors)

        // Over every combination of hostile and ordinary values: nothing throws; the score is an
        // integer in 0…100 with its tier; every factor is a number in 0…1 and never -0.0; a factor is
        // present exactly when its goal is above zero and its attainment is a number; and dropping an
        // unreadable factor gives the same answer as disabling its goal.
        val currents = listOf(nan, inf, -inf, 0.0, -0.0, -1.0, Double.MIN_VALUE, 15.0, 30.0, 1e308)
        val goals = listOf(nan, inf, -inf, 0.0, -0.0, -5.0, Double.MIN_VALUE, 30.0, 1e308)
        val steps = listOf(Int.MIN_VALUE, -1, 0, 5000, Int.MAX_VALUE)
        val stepGoals = listOf(Int.MIN_VALUE, -1, 0, 1, 10_000, Int.MAX_VALUE)
        var cases = 0
        for (st in steps) for (sg in stepGoals) for (m in currents) for (mg in goals) for (k in currents) for (kg in goals) {
            val r = score(st, sg, m, mg, k, kg)
            cases++
            assertTrue(r.score in 0..100, "$st $sg $m $mg $k $kg → ${r.score}")
            assertEquals(Tier.of(r.score), r.tier)
            for ((f, x) in r.factors) assertTrue(x in 0.0..1.0 && x.toRawBits() != (-0.0).toRawBits(), "$f = $x")
            val minutesReadable = mg > 0 && !(m / mg).isNaN()
            val kcalReadable = kg > 0 && !(k / kg).isNaN()
            assertEquals(sg > 0, Factor.STEPS in r.factors)
            assertEquals(minutesReadable, Factor.ACTIVE_MINUTES in r.factors, "$m / $mg")
            assertEquals(kcalReadable, Factor.ACTIVE_KCAL in r.factors, "$k / $kg")
            val disabled = score(st, sg, m, if (minutesReadable) mg else 0.0, k, if (kcalReadable) kg else 0.0)
            assertEquals(disabled, r, "$st $sg $m $mg $k $kg")
        }
        assertEquals(5 * 6 * 10 * 9 * 10 * 9, cases)
    }

    @Test
    fun goalsOfZeroOrBelowOrUnreadableDropTheirFactorAsUpstream() {
        // Measured: negative and zero goals → 0 needsImprovement with no factors; NaN goals → dropped,
        // 50 from steps alone; infinite goals → attainment 0 (23); every goal disabled → 0, no factors.
        val negative = score(5000, -1, 15.0, -30.0, 250.0, 0.0)
        assertEquals(0, negative.score)
        assertEquals(emptyMap(), negative.factors)
        val nanGoals = score(5000, 10_000, 15.0, nan, 250.0, nan)
        assertEquals(50, nanGoals.score)
        assertEquals(mapOf(Factor.STEPS to 0.5), nanGoals.factors)
        val infGoals = score(5000, 10_000, 15.0, inf, 250.0, inf)
        assertEquals(23, infGoals.score)
        assertEquals(mapOf(Factor.STEPS to 0.5, Factor.ACTIVE_MINUTES to 0.0, Factor.ACTIVE_KCAL to 0.0), infGoals.factors)
        val none = score(5000, 0, 15.0, 0.0, 250.0, -0.0)
        assertEquals(0, none.score)
        assertEquals(Tier.NEEDS_IMPROVEMENT, none.tier)
        assertEquals(emptyMap(), none.factors)
        // The smallest positive goal still counts (and caps at full credit).
        assertEquals(mapOf(Factor.ACTIVE_MINUTES to 1.0), score(0, 0, 1.0, Double.MIN_VALUE, 0.0, 0.0).factors)
    }

    @Test
    fun currentValuesOutsideTheGoalsRangeClampAsUpstream() {
        // Measured: +∞ minutes → full credit, -∞ energy → none (57); negative steps, minutes of -0.0
        // and negative energy → 0 with every factor +0.0 (Swift's max(-0.0, 0) is +0.0); Int32.max
        // steps over a goal of 1 → 45; Int32.min steps over Int32.max → 0.
        val infinite = score(5000, 10_000, inf, 30.0, -inf, 500.0)
        assertEquals(57, infinite.score)
        assertEquals(mapOf(Factor.STEPS to 0.5, Factor.ACTIVE_MINUTES to 1.0, Factor.ACTIVE_KCAL to 0.0), infinite.factors)
        val negative = score(-5, 10_000, -0.0, 30.0, -1.0, 500.0)
        assertEquals(0, negative.score)
        assertEquals(List(3) { 0.0.toRawBits() }, negative.factors.values.map { it.toRawBits() }, "+0.0, never -0.0")
        assertEquals(45, score(Int.MAX_VALUE, 1, 0.0, 30.0, 0.0, 500.0).score)
        assertEquals(0, score(Int.MIN_VALUE, Int.MAX_VALUE, 0.0, 30.0, 0.0, 500.0).score)
    }

    @Test
    fun factorsAreSummedInDeclarationOrderWhereUpstreamsOrderChangesFromOneLaunchToTheNext() {
        // Upstream adds the present factors by iterating a dictionary whose order Swift seeds per
        // process. A search of 20 000 000 seeded inputs on the pinned formula found 1 167 whose rounded
        // score depends on that order; measured over 20 launches of the pinned build, these three gave
        // 99 or 100, 78 or 79 and 71 or 72. The port adds steps, then active minutes, then active
        // energy — always.
        assertEquals(100, score(8867, 8000, 37.4, 30.0, 487.5, 500.0).score)
        assertEquals(79, score(10_865, 7500, 18.6, 30.0, 295.0, 500.0).score)
        assertEquals(71, score(4400, 12_000, 39.35, 30.0, 618.1, 500.0).score)
        assertEquals(Tier.GOOD, score(4400, 12_000, 39.35, 30.0, 618.1, 500.0).tier)
    }
}
