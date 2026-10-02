package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.VitalsBaseline.Status
import io.github.opencircuit.ringkit.WellnessBalance.Input
import io.github.opencircuit.ringkit.WellnessBalance.Result.Factor
import io.github.opencircuit.ringkit.WellnessBalance.Tier
import io.github.opencircuit.ringkit.WellnessBalance.Trend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SYNTHETIC-ONLY tests for the Wellness Balance / readiness capstone (#97). Controlled inputs;
 * asserts tier cut-offs, stress inversion, the vitals-status mapping, factor renormalisation when a
 * sub-score is absent, nil when nothing is available, and the trend deadband. No real health values.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/WellnessBalanceTests.swift
 * (@ b1c2fdd), all 11 tests.
 */
class WellnessBalanceTest {

    @Test
    fun tierCutoffs() { // :10-15
        assertEquals(Tier.EXCELLENT, Tier.of(85))
        assertEquals(Tier.GOOD, Tier.of(84))
        assertEquals(Tier.GOOD, Tier.of(60))
        assertEquals(Tier.NEEDS_IMPROVEMENT, Tier.of(59))
    }

    @Test
    fun goodDayScoresHigh() { // :17-23
        val r = assertNotNull(WellnessBalance.score(Input(sleepScore = 90, overnightStress = 20, vitalsStatus = Status.NORMAL, activityScore = 85)))
        assertTrue(r.score >= 85)
        assertEquals(Tier.EXCELLENT, r.tier)
        assertEquals(4, r.factors.size)
    }

    @Test
    fun poorDayScoresLow() { // :25-30
        val r = assertNotNull(WellnessBalance.score(Input(sleepScore = 45, overnightStress = 85, vitalsStatus = Status.ANOMALY, activityScore = 30)))
        assertTrue(r.score < 60)
        assertEquals(Tier.NEEDS_IMPROVEMENT, r.tier)
    }

    @Test
    fun goodDayOutScoresPoorDay() { // :32-38
        val good = WellnessBalance.score(Input(sleepScore = 88, overnightStress = 25, vitalsStatus = Status.NORMAL, activityScore = 80))!!
        val poor = WellnessBalance.score(Input(sleepScore = 50, overnightStress = 80, vitalsStatus = Status.WATCH, activityScore = 35))!!
        assertTrue(good.score > poor.score)
    }

    @Test
    fun stressIsInvertedIntoRecovery() { // :40-51
        // overnightStress is a SleepStress score clamped to [15, 90]; map that REAL range to a full
        // 0…1 recovery factor (not a compressed [0.10, 0.85]). Sleep held constant to isolate it.
        val calm = WellnessBalance.score(Input(sleepScore = 80, overnightStress = 15))!!
        val tense = WellnessBalance.score(Input(sleepScore = 80, overnightStress = 90))!!
        assertTrue(calm.score > tense.score)
        assertEquals(1.0, calm.factors[Factor.RECOVERY]!!, 0.0001) // most-relaxed → full recovery
        assertEquals(0.0, tense.factors[Factor.RECOVERY]!!, 0.0001) // most-stressed → zero recovery
        // A mid-range stress lands mid-recovery: (90 − 52) / 75 = 0.5067.
        val mid = WellnessBalance.score(Input(sleepScore = 80, overnightStress = 52))!!
        assertEquals(0.5067, mid.factors[Factor.RECOVERY]!!, 0.001)
    }

    @Test
    fun anchoredScoreRequiresSleep() { // :53-62
        // Activity (or stress) alone must NOT synthesise a readiness — the anchor requires a sleep
        // sub-score, so we never fabricate readiness from a weak signal.
        assertNull(WellnessBalance.anchoredScore(Input(activityScore = 60)))
        assertNull(WellnessBalance.anchoredScore(Input(overnightStress = 20, activityScore = 60)))
        assertNotNull(WellnessBalance.anchoredScore(Input(sleepScore = 80, activityScore = 60)))
        // The unanchored blender intentionally DOES return an activity-only result — this pins that
        // documented contract so the anchor policy can't silently move into it.
        assertNotNull(WellnessBalance.score(Input(activityScore = 60)))
    }

    @Test
    fun vitalsStatusMapping() { // :64-68
        assertEquals(1.0, WellnessBalance.vitalsFactor(Status.NORMAL))
        assertEquals(0.5, WellnessBalance.vitalsFactor(Status.WATCH))
        assertEquals(0.0, WellnessBalance.vitalsFactor(Status.ANOMALY))
    }

    @Test
    fun missingFactorsAreRenormalised() { // :70-80
        // Only sleep present → the score equals the sleep sub-score (renormalised to 1 factor).
        val r = assertNotNull(WellnessBalance.score(Input(sleepScore = 80)))
        assertEquals(1, r.factors.size)
        assertEquals(80, r.score)
        // Sleep + activity only → weighted mean over just those two.
        val r2 = assertNotNull(WellnessBalance.score(Input(sleepScore = 90, activityScore = 60)))
        assertEquals(2, r2.factors.size)
        // 0.40*0.9 + 0.15*0.6 = 0.45 over 0.55 → 0.8181… → 82
        assertEquals(82, r2.score)
    }

    @Test
    fun nilWhenNoSubScores() { // :82-84
        assertNull(WellnessBalance.score(Input()))
    }

    @Test
    fun scoreAlwaysInRange() { // :86-94
        val worst = WellnessBalance.score(Input(sleepScore = 0, overnightStress = 100, vitalsStatus = Status.ANOMALY, activityScore = 0))!!
        assertEquals(0, worst.score)
        val best = WellnessBalance.score(Input(sleepScore = 100, overnightStress = 1, vitalsStatus = Status.NORMAL, activityScore = 100))!!
        assertTrue(best.score <= 100)
        assertTrue(best.score >= 95)
    }

    @Test
    fun trendDeadband() { // :96-101
        assertEquals(Trend.UP, WellnessBalance.trend(today = 80, prior = listOf(70, 72, 74)))
        assertEquals(Trend.DOWN, WellnessBalance.trend(today = 60, prior = listOf(70, 72, 74)))
        assertEquals(Trend.STEADY, WellnessBalance.trend(today = 73, prior = listOf(70, 72, 74)))
        assertEquals(Trend.STEADY, WellnessBalance.trend(today = 80, prior = emptyList()))
    }
}
