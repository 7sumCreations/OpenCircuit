package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.ActivityScore.Input
import io.github.opencircuit.ringkit.ActivityScore.Result.Factor
import io.github.opencircuit.ringkit.ActivityScore.Tier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SYNTHETIC-ONLY tests for the daily Activity Score (#95). Controlled inputs; asserts tier cut-offs,
 * per-goal attainment capping, factor renormalisation when a goal is disabled, and that an active
 * day out-scores a sedentary one. No real health values.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/ActivityScoreTests.swift
 * (@ b1c2fdd), all 8 tests.
 */
class ActivityScoreTest {

    private fun input(
        steps: Int = 0,
        stepGoal: Int = 10_000,
        activeMinutes: Double = 0.0,
        activeMinutesGoal: Double = 30.0,
        activeKcal: Double = 0.0,
        activeKcalGoal: Double = 500.0,
    ): Input = Input(
        steps = steps, stepGoal = stepGoal,
        activeMinutes = activeMinutes, activeMinutesGoal = activeMinutesGoal,
        activeKcal = activeKcal, activeKcalGoal = activeKcalGoal,
    )

    @Test
    fun tierCutoffs() { // :17-22
        assertEquals(Tier.EXCELLENT, Tier.of(85))
        assertEquals(Tier.GOOD, Tier.of(84))
        assertEquals(Tier.GOOD, Tier.of(70))
        assertEquals(Tier.NEEDS_IMPROVEMENT, Tier.of(69))
    }

    @Test
    fun allGoalsMetScoresHundred() { // :24-30
        // Every goal met exactly → all factors 1.0 → score 100.
        val r = ActivityScore.score(input(steps = 10_000, activeMinutes = 30.0, activeKcal = 500.0))
        assertEquals(100, r.score)
        assertEquals(Tier.EXCELLENT, r.tier)
        assertEquals(3, r.factors.size)
    }

    @Test
    fun exceedingGoalIsCappedNotOverCredited() { // :32-37
        // 3× every goal must still cap each factor at 1.0 → 100, never > 100.
        val r = ActivityScore.score(input(steps = 30_000, activeMinutes = 90.0, activeKcal = 1500.0))
        assertEquals(100, r.score)
        for ((_, v) in r.factors) assertTrue(v <= 1.0)
    }

    @Test
    fun sedentaryDayScoresLow() { // :39-43
        val r = ActivityScore.score(input(steps = 800, activeMinutes = 0.0, activeKcal = 20.0))
        assertTrue(r.score < 20)
        assertEquals(Tier.NEEDS_IMPROVEMENT, r.tier)
    }

    @Test
    fun activeDayOutScoresSedentary() { // :45-49
        val active = ActivityScore.score(input(steps = 9_000, activeMinutes = 28.0, activeKcal = 460.0))
        val sedentary = ActivityScore.score(input(steps = 1_200, activeMinutes = 2.0, activeKcal = 40.0))
        assertTrue(active.score > sedentary.score)
    }

    @Test
    fun stepsOnlyReflectsWeighting() { // :51-57
        // Only the step goal met (0.45 of the weight) → ~45, and steps is the sole full factor.
        val r = ActivityScore.score(input(steps = 10_000, activeMinutes = 0.0, activeKcal = 0.0))
        assertEquals(45, r.score)
        assertEquals(1.0, r.factors[Factor.STEPS])
        assertEquals(0.0, r.factors[Factor.ACTIVE_MINUTES])
    }

    @Test
    fun disabledGoalIsDroppedAndRenormalised() { // :59-66
        // Active-kcal goal disabled (0) → that factor is absent; the remaining two renormalise.
        val r = ActivityScore.score(input(steps = 10_000, activeMinutes = 30.0, activeKcal = 0.0, activeKcalGoal = 0.0))
        assertEquals(2, r.factors.size)
        assertNull(r.factors[Factor.ACTIVE_KCAL])
        // Both present goals fully met → 100 despite the dropped factor.
        assertEquals(100, r.score)
    }

    @Test
    fun scoreAlwaysInRange() { // :68-77
        val zero = ActivityScore.score(input(steps = 0, activeMinutes = 0.0, activeKcal = 0.0))
        assertTrue(zero.score >= 0)
        assertTrue(zero.score <= 100)
        // All goals disabled → no factors → defined, in-range, low.
        val noGoals = ActivityScore.score(input(stepGoal = 0, activeMinutesGoal = 0.0, activeKcalGoal = 0.0))
        assertEquals(0, noGoals.factors.size)
        assertTrue(noGoals.score >= 0)
        assertTrue(noGoals.score <= 100)
    }
}
