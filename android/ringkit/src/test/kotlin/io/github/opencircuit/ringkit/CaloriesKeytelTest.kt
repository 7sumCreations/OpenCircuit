package io.github.opencircuit.ringkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit checks for the Keytel (2005) HR→energy workout-calorie model (`Calories.workoutActiveKcal`),
 * the fix for workouts showing "-- kcal" (Edwards-TRIMP needed 600 samples and zeroed below 50% HRR).
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/CaloriesKeytelTests.swift (@ b1c2fdd)
 * — all 7 tests.
 */
class CaloriesKeytelTest {

    private val male = UserProfile(age = 35, weightKg = 75.0, heightCm = 178.0, sex = BiologicalSex.MALE)
    private val female = UserProfile(age = 30, weightKg = 60.0, heightCm = 165.0, sex = BiologicalSex.FEMALE)

    /**
     * Exact Keytel value, male 75 kg / 35 y / 101 bpm:
     * (−55.0969 + 0.6309·101 + 0.1988·75 + 0.2017·35) / 4.184 = 7.3120 kcal/min.
     */
    @Test
    fun maleRateMatchesFormula() { // :13-16
        val oneMinute = Calories.workoutActiveKcal(avgHR = 101, durationSeconds = 60.0, profile = male)
        assertEquals(7.3120, oneMinute, 0.001)
    }

    /**
     * Exact Keytel value, female 60 kg / 30 y / 140 bpm:
     * (−20.4022 + 0.4472·140 − 0.1263·60 + 0.074·30) / 4.184 = 8.8069 kcal/min.
     */
    @Test
    fun femaleRateMatchesFormula() { // :20-23
        val oneMinute = Calories.workoutActiveKcal(avgHR = 140, durationSeconds = 60.0, profile = female)
        assertEquals(8.8069, oneMinute, 0.001)
    }

    /** The reported case: 5m05s indoor cycle at avg 101 bpm → ≈ 37 kcal (not "--"). */
    @Test
    fun reportedIndoorCycleScenario() { // :26-29
        val kcal = Calories.workoutActiveKcal(avgHR = 101, durationSeconds = 305.0, profile = male)
        assertEquals(37.2, kcal, 0.5)
    }

    /** Energy scales linearly with duration (Keytel rate is per-minute). */
    @Test
    fun durationScalesLinearly() { // :32-36
        val five = Calories.workoutActiveKcal(avgHR = 120, durationSeconds = 300.0, profile = male)
        val ten = Calories.workoutActiveKcal(avgHR = 120, durationSeconds = 600.0, profile = male)
        assertEquals(five * 2, ten, 0.001)
    }

    /** Higher HR ⇒ more calories for the same duration/profile. */
    @Test
    fun monotonicInHR() { // :39-43
        val lo = Calories.workoutActiveKcal(avgHR = 100, durationSeconds = 300.0, profile = male)
        val hi = Calories.workoutActiveKcal(avgHR = 140, durationSeconds = 300.0, profile = male)
        assertTrue(hi > lo)
    }

    /** A very low HR yields a negative raw Keytel rate — it must clamp to 0, never negative kcal. */
    @Test
    fun lowHRClampsToZero() { // :46-48
        assertEquals(0.0, Calories.workoutActiveKcal(avgHR = 40, durationSeconds = 600.0, profile = male), 1e-9)
    }

    /** Guards: non-positive HR or duration → 0. */
    @Test
    fun nonPositiveInputsReturnZero() { // :51-55
        assertEquals(0.0, Calories.workoutActiveKcal(avgHR = 0, durationSeconds = 300.0, profile = male))
        assertEquals(0.0, Calories.workoutActiveKcal(avgHR = 120, durationSeconds = 0.0, profile = male))
        assertEquals(0.0, Calories.workoutActiveKcal(avgHR = 120, durationSeconds = -5.0, profile = male))
    }
}
