package io.github.opencircuit.ringkit

// PARTIAL port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/Calories.swift
// (@ b1c2fdd): `HRSample` (:3-13), the constants and energy functions of `:15-166` (step / distance
// energy, Mifflin-St Jeor BMR, the resting-HR–adjusted basal energy, TRIMP energy) and
// `workoutActiveKcal` (:484-497). The daily estimate and its energy buckets (:168-482) arrive with
// the resting-HR and exercise-minute ports; this object grows into them.
//
// Port notes:
//  • Swift `min` / `max` / `sorted()` semantics (`swiftMin`, `swiftMax`, `swiftSorted`): a NaN
//    weight gives a zero Keytel rate as upstream (Swift's `max(0, NaN)` is 0, Kotlin's `maxOf` is
//    NaN), and a NaN prior day is ordered exactly as Swift orders it before the trim.
//  • One deliberate difference: `restingEnergyScale` reads a NaN or infinite resting HR or baseline
//    as missing (1.0), where upstream clamps it to −20 % or +20 % (PORTING.md D-71).
//  • Energy from an impossible profile or a non-finite duration is returned as upstream computes it;
//    range checks belong to the profile's entry point and the health writer (PORTING.md D-73).

import java.time.Instant

/** One heart-rate reading over `[start, end]` (a point reading when they are equal). */
data class HRSample(val bpm: Int, val start: Instant, val end: Instant = start)

/** Energy estimates: basal (Mifflin-St Jeor, scaled by resting HR), TRIMP, Keytel and step / distance. */
object Calories {

    const val TRIMP_KCAL_FACTOR: Double = 5.0
    const val DEFAULT_RESTING_HR: Int = 60

    /**
     * Net (above-resting) walking economy: ≈ 0.5 kcal per kg of body mass per km walked (gross
     * ≈ 1.0 kcal·kg⁻¹·km⁻¹ minus the resting component, the standard pedometer constant). An
     * ESTIMATE that lets a day with walking still report active energy instead of 0.
     */
    const val WALK_KCAL_PER_KG_PER_KM: Double = 0.5

    /** Active kcal estimate from a walked / ran distance (metres) and body mass; 0 unless the distance is positive. */
    fun activeKcalFromDistance(meters: Double, profile: UserProfile): Double {
        if (!(meters > 0)) return 0.0
        return (meters / 1000.0) * profile.weightKg * WALK_KCAL_PER_KG_PER_KM
    }

    /** Active kcal estimate from a step count, via [DistanceEstimate]; 0 for a non-positive count. */
    fun activeKcalFromSteps(steps: Int, profile: UserProfile): Double =
        activeKcalFromDistance(DistanceEstimate.meters(steps), profile)

    /** Mifflin-St Jeor basal metabolic rate, kcal per day. */
    fun bmrKcalPerDay(profile: UserProfile): Double {
        val base = (10.0 * profile.weightKg) + (6.25 * profile.heightCm) - (5.0 * profile.age.toDouble())
        return when (profile.sex) {
            BiologicalSex.MALE -> base + 5.0
            BiologicalSex.FEMALE -> base - 161.0
        }
    }

    fun bmrKcalPerHour(profile: UserProfile): Double = bmrKcalPerDay(profile) / 24.0

    // Resting-HR–adjusted basal energy: the formula BMR is nudged by how far the day's MEASURED
    // resting HR sits from the person's own recent baseline (≈ 1 % per bpm, capped at ±20 %).
    // Still an ESTIMATE, labelled as such at every write site.

    /** Fractional change in resting energy per bpm of resting-HR deviation from baseline. */
    const val RESTING_ENERGY_FRACTION_PER_BPM: Double = 0.01

    /** Hard cap on how far measured resting HR may move basal energy off the formula value, either way. */
    const val MAX_RESTING_ENERGY_ADJUSTMENT: Double = 0.20

    /** Fewest PRIOR daily resting-HR readings before a personal baseline is trusted. */
    const val MIN_RESTING_BASELINE_DAYS: Int = 3

    /** Fewest PRIOR days before the baseline's trimmed mean actually trims (below it: the plain mean). */
    const val MIN_TRIMMED_BASELINE_DAYS: Int = 5

    /**
     * Personal resting-HR baseline (mean bpm) from PRIOR daily resting-HR values; `null` below
     * [minDays] (or for a non-positive [minDays]). Below [MIN_TRIMMED_BASELINE_DAYS] it is the plain
     * mean of every day; from there a 10 % trimmed mean (at least one value off each end).
     */
    fun restingBaselineBpm(prior: List<Double>, minDays: Int = MIN_RESTING_BASELINE_DAYS): Double? {
        if (prior.size < minDays || minDays <= 0) return null
        val sorted = swiftSorted(prior)
        if (sorted.size < MIN_TRIMMED_BASELINE_DAYS) return sum(sorted, 0, sorted.size) / sorted.size.toDouble()
        val trimCount = maxOf(1, sorted.size / 10)
        return if (sorted.size > 2 * trimCount) {
            sum(sorted, trimCount, sorted.size - trimCount) / (sorted.size - 2 * trimCount).toDouble()
        } else {
            sum(sorted, 0, sorted.size) / sorted.size.toDouble()
        }
    }

    /** Sum of `a[from until to]` in index order, from 0.0, as Swift's `reduce(0, +)`. */
    private fun sum(a: DoubleArray, from: Int, to: Int): Double {
        var s = 0.0
        for (k in from until to) s += a[k]
        return s
    }

    /**
     * Multiplier on the static BMR from the day's measured resting HR against the personal
     * baseline; 1.0 is no change. Returns 1.0 when either input is missing — `null`, NaN or
     * infinite (upstream clamps a NaN or infinite reading instead; PORTING.md D-71) — or the
     * baseline is not above zero, and is clamped to ±[MAX_RESTING_ENERGY_ADJUSTMENT].
     */
    fun restingEnergyScale(restingHR: Double?, baselineRestingHR: Double?): Double {
        if (restingHR == null || baselineRestingHR == null || !(baselineRestingHR > 0)) return 1.0
        if (!restingHR.isFinite() || !baselineRestingHR.isFinite()) return 1.0
        val raw = 1.0 + RESTING_ENERGY_FRACTION_PER_BPM * (restingHR - baselineRestingHR)
        val lo = 1.0 - MAX_RESTING_ENERGY_ADJUSTMENT
        val hi = 1.0 + MAX_RESTING_ENERGY_ADJUSTMENT
        return swiftMin(hi, swiftMax(lo, raw))
    }

    /**
     * Basal (passive) energy for ONE hour: the per-hour BMR scaled by [restingEnergyScale]; exactly
     * the static per-hour BMR when the resting HR or its baseline is unavailable.
     */
    fun basalKcalPerHour(profile: UserProfile, restingHR: Double? = null, baselineRestingHR: Double? = null): Double =
        bmrKcalPerHour(profile) * restingEnergyScale(restingHR, baselineRestingHR)

    /** TRIMP energy over [DEFAULT_RESTING_HR]; 0 when the TRIMP is unavailable (too few samples, or `maxHR` not above it). */
    fun activeKcal(hrSamples: List<HRSample>, maxHR: Int): Double {
        val trimp = Strain.edwardsTRIMP(hrSamples, maxHR = maxHR, restingHR = DEFAULT_RESTING_HR) ?: return 0.0
        return trimp * TRIMP_KCAL_FACTOR
    }

    /**
     * Workout active energy from average HR and duration with the Keytel (2005) HR→energy model
     * (kJ·min⁻¹; W = body mass kg, A = age years):
     *   men:   −55.0969 + 0.6309·HR + 0.1988·W + 0.2017·A
     *   women: −20.4022 + 0.4472·HR − 0.1263·W + 0.0740·A
     * kcal = kJ / 4.184; the per-minute rate is clamped to ≥ 0. Returns 0 for a non-positive HR or
     * duration. Chosen over Edwards-TRIMP for calories because TRIMP gives zero weight below 50 %
     * heart-rate reserve. An ESTIMATE.
     */
    fun workoutActiveKcal(avgHR: Int, durationSeconds: Double, profile: UserProfile): Double {
        if (avgHR <= 0 || !(durationSeconds > 0)) return 0.0
        val hr = avgHR.toDouble()
        val w = profile.weightKg
        val a = profile.age.toDouble()
        val kJPerMin = when (profile.sex) {
            BiologicalSex.MALE -> -55.0969 + 0.6309 * hr + 0.1988 * w + 0.2017 * a
            BiologicalSex.FEMALE -> -20.4022 + 0.4472 * hr - 0.1263 * w + 0.0740 * a
        }
        val kcalPerMin = swiftMax(0.0, kJPerMin / 4.184)
        return kcalPerMin * (durationSeconds / 60.0)
    }
}
