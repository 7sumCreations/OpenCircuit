package io.github.opencircuit.ringkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The 6-factor composite sleep score: tier cut-offs, factor renormalisation when the optional
 * inputs are absent, and a good night out-scoring a poor one. Synthetic inputs only.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepScoreCompositeTests.swift
 * (@ b1c2fdd) — all 7 tests. Durations are seconds, as upstream's `TimeInterval`.
 */
class SleepScoreCompositeTest {

    private val h = 3600.0

    @Test
    fun tierCutoffs() { // :11-16
        assertEquals(SleepScore.Tier.EXCELLENT, SleepScore.Tier.of(85))
        assertEquals(SleepScore.Tier.GOOD, SleepScore.Tier.of(84))
        assertEquals(SleepScore.Tier.GOOD, SleepScore.Tier.of(70))
        assertEquals(SleepScore.Tier.NEEDS_IMPROVEMENT, SleepScore.Tier.of(69))
    }

    @Test
    fun durationOnlyScoreStillGraded() { // :18-22
        assertEquals(50.0, SleepScore.score(4L * 3600), 0.001)
        assertEquals(100.0, SleepScore.score(8L * 3600), 0.001)
    }

    @Test
    fun goodNightScoresHigh() { // :24-34
        val input = SleepScore.CompositeInput(
            totalAsleep = 8 * h, timeAwake = 10 * 60.0, efficiency = 0.94,
            deep = 1.5 * h, light = 4.5 * h, rem = 2 * h,
            restingHR = 48.0, tempOffsetC = 0.1,
        )
        val c = SleepScore.composite(input)
        assertTrue(c.score >= 85, "score ${c.score}")
        assertEquals(SleepScore.Tier.EXCELLENT, c.tier)
        assertEquals(6, c.factors.size, "all six factors present")
    }

    @Test
    fun poorNightScoresLow() { // :36-45
        val input = SleepScore.CompositeInput(
            totalAsleep = 4 * h, timeAwake = 90 * 60.0, efficiency = 0.55,
            deep = 0.2 * h, light = 3.7 * h, rem = 0.1 * h,
            restingHR = 78.0, tempOffsetC = 1.5,
        )
        val c = SleepScore.composite(input)
        assertTrue(c.score < 60, "score ${c.score}")
        assertEquals(SleepScore.Tier.NEEDS_IMPROVEMENT, c.tier)
    }

    @Test
    fun goodNightOutScoresPoorNight() { // :47-53
        val good = SleepScore.composite(
            SleepScore.CompositeInput(totalAsleep = 8 * h, timeAwake = 10 * 60.0, efficiency = 0.93, deep = 1.5 * h, light = 4.5 * h, rem = 2 * h),
        )
        val poor = SleepScore.composite(
            SleepScore.CompositeInput(totalAsleep = 4 * h, timeAwake = 80 * 60.0, efficiency = 0.6, deep = 0.2 * h, light = 3.7 * h, rem = 0.1 * h),
        )
        assertTrue(good.score > poor.score, "${good.score} vs ${poor.score}")
    }

    @Test
    fun missingOptionalFactorsAreRenormalised() { // :55-67
        val input = SleepScore.CompositeInput(
            totalAsleep = 8 * h, timeAwake = 10 * 60.0, efficiency = 0.93,
            deep = 1.5 * h, light = 4.5 * h, rem = 2 * h, // no restingHR / tempOffsetC
        )
        val c = SleepScore.composite(input)
        assertEquals(4, c.factors.size)
        assertNull(c.factors[SleepScore.Composite.Factor.HEART_RATE])
        assertNull(c.factors[SleepScore.Composite.Factor.TEMPERATURE])
        assertTrue(c.score >= 0)
        assertTrue(c.score <= 100)
        assertTrue(c.score >= 85, "a strong night still scores high without HR/temp")
    }

    @Test
    fun scoreAlwaysInRange() { // :69-74
        val zero = SleepScore.composite(
            SleepScore.CompositeInput(totalAsleep = 0.0, timeAwake = 8 * h, efficiency = 0.0, deep = 0.0, light = 0.0, rem = 0.0),
        )
        assertTrue(zero.score >= 0)
        assertTrue(zero.score <= 100)
    }
}
