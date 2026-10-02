package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * IS THE EFFICIENCY THE GUARD EXPOSES ACTUALLY DISCLOSED TO THE USER?
 *
 * Enabling the observed-gap guard corrects the owner's 2026-08-19 bedtime (in-bed start error
 * −119 → −5 min) but pushes reported efficiency from 0.928 to 0.990 against a reference of 0.608 —
 * the documented in-bed==asleep signal ceiling, EXPOSED by a correct bedtime rather than created by
 * the guard. That is only acceptable if the app SAYS SO. This pins that it does.
 *
 * Upstream's sleep card renders the note only when THREE conditions hold. Two are pure library calls
 * and are asserted directly; the third is a one-line arithmetic gate in the view, restated here:
 *
 *   1. contiguous            — (inBedEnd − inBedStart) <= summary.inBed * 1.15
 *   2. !isLikelyTruncated    — SleepCaptureCoverage.classify(...) != LIKELY_TRUNCATED
 *   3. durationLikelyHigh    — SleepConfidence.classify(...) == DURATION_LIKELY_HIGH
 *
 * Measured inputs are upstream's `SleepBaselineTests` scoreboard rows for R3_2026-08-19 at cut 0 and
 * at the shipped 0.95.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/ObservedGapAbsorbDisclosureTests.swift
 * (@ b1c2fdd) — all 3 tests. Upstream passes the machine's clock (`Date()`) as the captured onset;
 * a night this long never reads it (see gate 2), so a fixed instant stands in for it here.
 */
class ObservedGapAbsorbDisclosureTest {

    // R3_2026-08-19, MEASURED both ways.
    private val inBedMinOff = 768.0
    private val asleepMinOff = 713.0
    private val wallClockMinOff = 768.12
    private val inBedMinOn = 654.0
    private val asleepMinOn = 648.0
    private val wallClockMinOn = 654.08

    /** The view's contiguity gate, restated. */
    private fun contiguous(wallClockMin: Double, inBedMin: Double): Boolean {
        if (!(inBedMin > 0)) return true
        return wallClockMin * 60 <= (inBedMin * 60) * 1.15
    }

    /** The full three-gate chain the view applies. */
    private fun noteWouldRender(wallClockMin: Double, inBedMin: Double, asleepMin: Double): Boolean {
        val inBed = inBedMin * 60
        val asleep = asleepMin * 60
        // Gate 2. No manual schedule is needed: a night this long is far past the ring buffer, so
        // `classify` short-circuits to FULL before it ever looks for a bedtime reference.
        val truncated = SleepCaptureCoverage.classify(
            capturedOnset = Instant.ofEpochSecond(1_787_000_000),
            capturedInBed = Duration.ofMillis((inBed * 1000).roundToLong()),
            scheduledBedtime = null,
        ) == SleepCaptureCoverage.Coverage.LIKELY_TRUNCATED
        return contiguous(wallClockMin, inBedMin) &&
            !truncated &&
            SleepConfidence.classify(asleep = asleep, inBed = inBed) == SleepConfidence.Level.DURATION_LIKELY_HIGH
    }

    /** WITH THE GUARD ON the efficiency is implausible AND the note fires — the exposure is disclosed. */
    @Test
    fun ownersNightIsFlaggedDurationLikelyHighAfterTheChange() {
        val efficiency = asleepMinOn / inBedMinOn
        assertEquals(0.990, efficiency, 0.001, "measured post-change efficiency")
        assertTrue(efficiency > SleepConfidence.IMPLAUSIBLE_EFFICIENCY)
        assertTrue(inBedMinOn * 60 >= SleepConfidence.MIN_NIGHT_FOR_FLAG, "must clear the multi-hour gate or the flag never applies")
        assertEquals(SleepConfidence.Level.DURATION_LIKELY_HIGH, SleepConfidence.classify(asleep = asleepMinOn * 60, inBed = inBedMinOn * 60))
        assertTrue(
            noteWouldRender(wallClockMinOn, inBedMinOn, asleepMinOn),
            "the confidence note must render — otherwise the change turns a DISCLOSED limitation into a SILENT one",
        )
    }

    /**
     * BEFORE the change the same night was under the cut and said nothing. This is what makes the
     * assertion above meaningful: the guard does not merely inherit an existing warning, it TRIPS one.
     */
    @Test
    fun theSameNightWasSilentBeforeTheChange() {
        val efficiency = asleepMinOff / inBedMinOff
        assertEquals(0.928, efficiency, 0.001, "measured pre-change efficiency")
        assertTrue(efficiency < SleepConfidence.IMPLAUSIBLE_EFFICIENCY)
        assertEquals(SleepConfidence.Level.NORMAL, SleepConfidence.classify(asleep = asleepMinOff * 60, inBed = inBedMinOff * 60))
        assertFalse(noteWouldRender(wallClockMinOff, inBedMinOff, asleepMinOff))
    }

    /**
     * The other night the guard moves, R3_2026-08-12: efficiency 0.855 → 0.963, also above the cut,
     * so it too gains the note rather than silently inflating.
     */
    @Test
    fun theSecondMovedNightIsAlsoFlagged() {
        assertEquals(SleepConfidence.Level.NORMAL, SleepConfidence.classify(asleep = 540 * 60.0, inBed = 631 * 60.0), "before: efficiency 0.855")
        assertEquals(SleepConfidence.Level.DURATION_LIKELY_HIGH, SleepConfidence.classify(asleep = 468 * 60.0, inBed = 486 * 60.0), "after: efficiency 0.963")
    }
}
