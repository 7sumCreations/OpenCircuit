package io.github.opencircuit.ringkit

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `SleepConfidence` flags a full night whose reported duration is likely over-counted because the
 * ring couldn't see still wakefulness (efficiency pinned implausibly near 100 %). Grounded in the
 * user's own decoded nights (2026-06-29 device pull): the near-100 % nights (06-20/22/24/25/28) are
 * the implausible ones; the nights with real detected wake (06-26 84 %, 06-27 88 %) must NOT flag.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepConfidenceTests.swift
 * (@ b1c2fdd) — all 9 tests.
 */
class SleepConfidenceTest {

    private fun mins(m: Double): Double = m * 60
    private fun mins(m: Long): Duration = Duration.ofMinutes(m)

    // MARK: - Flags the implausibly-still nights

    @Test
    fun near100PercentFullNightFlagsDurationLikelyHigh() {
        // 06-28: asleep 572 m, awake 7 m → in-bed 579 m, efficiency 98.8 %.
        val level = SleepConfidence.classify(asleep = mins(572.0), inBed = mins(579.0))
        assertEquals(SleepConfidence.Level.DURATION_LIKELY_HIGH, level, "a 9.5 h night with only 7 min detected wake reads implausibly high")
    }

    @Test
    fun exactly100PercentFullNightFlags() {
        // 06-24: asleep 487 m, awake 2 m → essentially 100 % efficiency.
        assertEquals(SleepConfidence.Level.DURATION_LIKELY_HIGH, SleepConfidence.classify(asleep = mins(487.0), inBed = mins(489.0)))
    }

    // MARK: - Does NOT flag the nights that are already realistic

    @Test
    fun realisticEfficiencyNightIsNormal() {
        // 06-27: asleep 553 m, awake 76 m → in-bed 629 m, efficiency 87.9 % (real WASO detected).
        assertEquals(SleepConfidence.Level.NORMAL, SleepConfidence.classify(asleep = mins(553.0), inBed = mins(629.0)), "a night with realistic detected wake is not flagged")
    }

    @Test
    fun lowEfficiencyNightIsNormal() {
        // 06-26: asleep 583 m, awake 110 m → in-bed 693 m, efficiency 84.1 %.
        assertEquals(SleepConfidence.Level.NORMAL, SleepConfidence.classify(asleep = mins(583.0), inBed = mins(693.0)))
    }

    // MARK: - Conservative guards

    @Test
    fun shortNightIsNeverFlaggedEvenAt100Percent() {
        // 06-23: a 68 m block at ~100 % efficiency — far below minNightForFlag, so unremarkable.
        assertEquals(
            SleepConfidence.Level.NORMAL,
            SleepConfidence.classify(asleep = mins(68.0), inBed = mins(70.0)),
            "a sub-5 h block (nap / truncated fragment) never flags on efficiency alone",
        )
    }

    @Test
    fun justUnderMinNightDurationIsNormal() {
        val justUnder = SleepConfidence.MIN_NIGHT_FOR_FLAG - 1
        assertEquals(SleepConfidence.Level.NORMAL, SleepConfidence.classify(asleep = justUnder, inBed = justUnder), "below the multi-hour gate we don't judge efficiency")
    }

    @Test
    fun degenerateInBedIsNormal() {
        assertEquals(SleepConfidence.Level.NORMAL, SleepConfidence.classify(asleep = 0.0, inBed = 0.0))
        assertEquals(SleepConfidence.Level.NORMAL, SleepConfidence.classify(asleep = mins(300.0), inBed = -1.0))
    }

    // MARK: - Threshold boundary

    @Test
    fun efficiencyAtThresholdIsNormalAboveItFlags() {
        val night = SleepConfidence.MIN_NIGHT_FOR_FLAG + mins(60.0) // a clearly multi-hour night
        // Exactly at the 0.95 threshold → not flagged (strict `>`).
        val atThreshold = SleepConfidence.IMPLAUSIBLE_EFFICIENCY * night
        assertEquals(SleepConfidence.Level.NORMAL, SleepConfidence.classify(asleep = atThreshold, inBed = night), "efficiency exactly at the threshold is not flagged")
        // A hair above → flagged.
        val above = (SleepConfidence.IMPLAUSIBLE_EFFICIENCY + 0.02) * night
        assertEquals(SleepConfidence.Level.DURATION_LIKELY_HIGH, SleepConfidence.classify(asleep = above, inBed = night))
    }

    // MARK: - Summary overload agrees with the primitive

    @Test
    fun summaryOverloadMatchesPrimitive() {
        // inBed 579 m partitioned: 7 m awake, rest asleep (light/deep/rem) → totalAsleep 572 m.
        val s = SleepStaging.Summary(inBed = mins(579), awake = mins(7), light = mins(380), deep = mins(45), rem = mins(147))
        assertEquals(572L, s.minutes.asleep)
        assertEquals(
            SleepConfidence.classify(asleep = s.totalAsleep.seconds.toDouble(), inBed = s.inBed.seconds.toDouble()),
            SleepConfidence.classify(s),
        )
        assertEquals(SleepConfidence.Level.DURATION_LIKELY_HIGH, SleepConfidence.classify(s))
    }
}
