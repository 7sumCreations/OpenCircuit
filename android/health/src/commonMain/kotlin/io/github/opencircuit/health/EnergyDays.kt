package io.github.opencircuit.health

import io.github.opencircuit.ringkit.BiologicalSex
import io.github.opencircuit.ringkit.Calories
import io.github.opencircuit.ringkit.DateInterval
import io.github.opencircuit.ringkit.HRSample
import io.github.opencircuit.ringkit.RestingHR
import io.github.opencircuit.ringkit.StepWindow
import io.github.opencircuit.ringkit.UserProfile
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The wearer's profile for the energy formulas when none is set (upstream `storedUserProfile` defaults, HKW:1789-1796). */
object EnergyProfile {
    /** 35 years, 70 kg, 170 cm, male: what upstream uses until the wearer enters a profile. The app has no profile screen yet. */
    val DEFAULT: UserProfile = UserProfile(age = 35, weightKg = 70.0, heightCm = 170.0, sex = BiologicalSex.MALE)
}

/**
 * The records written once per local day — resting heart rate and basal metabolic rate (pure).
 * Upstream finalizes a day once its start is 12 hours past (`restingFinalizationDelay`,
 * HealthKitWriter.swift:1391; `d.day <= cutoff`, :1426) and looks back 7 days before today's start
 * (:1417-1419); both are kept here for both records.
 */
object DailyRecords {
    /** Days before today a write looks back to (upstream :1417). */
    const val LOOKBACK_DAYS = 7L

    /** A day is final once its start is this far past (upstream :1391). */
    val FINALIZATION: Duration = Duration.ofHours(12)

    /** Days of heart rate the basal rate's resting baseline is read from (upstream `basalRHRLookbackDays`, :1441). */
    const val BASAL_LOOKBACK_DAYS = 32L

    /**
     * The final local days still to write: after [mark] (none written yet when null) and not before
     * today − [LOOKBACK_DAYS], up to the last day whose start is [FINALIZATION] or more before [now]
     * (inclusive, as upstream). With no mark and [firstRunFromToday], only today: a first run starts
     * there, as upstream's basal meter starts at the current hour.
     */
    fun daysToWrite(mark: LocalDate?, now: Instant, zone: ZoneId, firstRunFromToday: Boolean): List<LocalDate> {
        val today = now.atZone(zone).toLocalDate()
        val floor = today.minusDays(LOOKBACK_DAYS)
        val earliest = when {
            mark != null -> maxOf(mark.plusDays(1), floor)
            firstRunFromToday -> today
            else -> floor
        }
        val cutoff = now.minus(FINALIZATION)
        return generateSequence(earliest) { it.plusDays(1) }
            .takeWhile { !it.isAfter(today) }
            .filter { !it.atStartOfDay(zone).toInstant().isAfter(cutoff) }
            .toList()
    }

    /** `oc:rhr:<day>` at the day's start, the day's value rounded to whole beats per minute. */
    fun restingHeartRate(day: LocalDate, zone: ZoneId, bpm: Double, device: RingDevice, version: Long): RestingHeartRate {
        val start = day.atStartOfDay(zone).toInstant()
        return RestingHeartRate(start, zone.rules.getOffset(start), Math.round(bpm), RecordMeta(RecordIds.restingHeartRate(day), version, RecordingMethod.AUTOMATICALLY_RECORDED, device))
    }

    /** `oc:bmr:<day>` at the day's start. */
    fun basalMetabolicRate(day: LocalDate, zone: ZoneId, kcalPerDay: Double, device: RingDevice, version: Long): BasalMetabolicRate {
        val start = day.atStartOfDay(zone).toInstant()
        return BasalMetabolicRate(start, zone.rules.getOffset(start), kcalPerDay, RecordMeta(RecordIds.basalMetabolicRate(day), version, RecordingMethod.AUTOMATICALLY_RECORDED, device))
    }

    /**
     * A day's basal rate in kilocalories per day: upstream's hourly `Calories.basalKcalPerHour` × 24,
     * nudged by the day's resting heart rate against the trimmed mean of the prior days'
     * (`restingEnergyInputs`, HealthKitWriter.swift:1515-1521); the static rate when the day has no
     * resting heart rate or the baseline too few days. [daily] comes from heart rate alone (no
     * sleep), as upstream's `dailyRestingHR` (:1508-1512).
     */
    fun basalKcalPerDay(dayStart: Instant, daily: List<RestingHR.DailyValue>, profile: UserProfile): Double {
        val today = daily.firstOrNull { it.day == dayStart }?.bpm
        val baseline = today?.let { Calories.restingBaselineBpm(daily.filter { it.day < dayStart }.map { d -> d.bpm }) }
        return Calories.basalKcalPerHour(profile, today, baseline) * HOURS_PER_DAY
    }

    private const val HOURS_PER_DAY = 24.0
}

/**
 * Active energy as absolute per-bucket records (pure). A day's estimate (`Calories.dailyEstimate`,
 * the same one the app's rings show) attributes the day's active kilocalories to 15-minute buckets
 * from the local midnight; each bucket is one record `oc:ae:<bucket start>` holding the bucket's
 * whole kilocalories. Health Connect counts only the newest of overlapping records from one app
 * (measured on the API 36 emulator), so the records never overlap and each carries the absolute
 * value: a rewritten bucket replaces itself, and a lowered estimate lowers Health Connect.
 */
object ActiveEnergyDays {
    /** Days rebuilt each flush, today included: a sync can bring a day's heart rate days later. */
    const val LOOKBACK_DAYS = 7L

    /** Two kilocalorie values closer than this are the same (floating-point noise, not a change). */
    private const val SAME_KCAL = 1e-9

    private val BUCKET_MILLIS = (Calories.ENERGY_BUCKET_SECONDS * 1000).toLong()

    /** One local day's inputs. */
    class DayInputs(
        val heartRate: List<HRSample>,
        val steps: Long,
        val stepWindows: List<StepWindow>,
        val sleepWindow: DateInterval?,
    )

    /** One bucket to write: its span and the kilocalories it carries (0 when it lowers a bucket that is gone). */
    data class Change(val start: Instant, val end: Instant, val kilocalories: Double) {
        /** The bucket's record `oc:ae:<start>`, measured by [device]. */
        fun record(zone: ZoneId, device: RingDevice, version: Long): ActiveCaloriesBurned = ActiveCaloriesBurned(
            start = start,
            startZoneOffset = zone.rules.getOffset(start),
            end = end,
            endZoneOffset = zone.rules.getOffset(end),
            kilocalories = kilocalories,
            meta = RecordMeta(RecordIds.activeCalories(start), version, RecordingMethod.AUTOMATICALLY_RECORDED, device),
        )
    }

    /** The buckets of local [day] in [zone] with their kilocalories, inside the day only; empty when nothing was attributed. */
    fun buckets(day: LocalDate, zone: ZoneId, inputs: DayInputs, profile: UserProfile): Map<Instant, Double> {
        val dayStart = day.atStartOfDay(zone).toInstant()
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()
        val estimate = Calories.dailyEstimate(
            hrSamples = inputs.heartRate,
            steps = inputs.steps.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
            profile = profile,
            sleepWindow = inputs.sleepWindow,
            stepWindows = inputs.stepWindows,
            dayStart = dayStart,
        )
        return estimate.buckets.filter { !it.start.isBefore(dayStart) && it.start.isBefore(dayEnd) }.associate { it.start to it.activeKcal }
    }

    /**
     * What to write for one day: every bucket whose kilocalories differ from what [written] holds
     * for it, a bucket with energy never written included; and every written bucket with energy that
     * now has none, at 0. A bucket with no energy that was never written (or was written at 0) is not
     * written. Each record spans its bucket, ending at [now] for the bucket still running; a bucket
     * starting at or after [now] waits.
     */
    fun changes(day: LocalDate, zone: ZoneId, buckets: Map<Instant, Double>, written: Map<Instant, Double>, now: Instant): List<Change> {
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()
        return (buckets.keys + written.keys).toSortedSet().mapNotNull { start ->
            val kcal = buckets[start] ?: 0.0
            val before = written[start]
            val changed = if (before == null) kcal > 0 else Math.abs(kcal - before) > SAME_KCAL
            if (!changed) return@mapNotNull null
            val end = minOf(start.plusMillis(BUCKET_MILLIS), dayEnd, now)
            if (start.isBefore(end)) Change(start, end, kcal) else null
        }
    }
}
