package io.github.opencircuit.health

import java.time.Instant

/**
 * The record rules Health Connect applies on the phone, checked before a record is sent.
 *
 * On Android 14 and later a Jetpack record checks itself against the PLATFORM's rules when it is
 * built, and one bad record makes the whole call fail. Jetpack's own rules (what runs on the JVM)
 * are looser in places — steps of 0, a heart-rate series with no samples — so this table is the
 * platform's, made stricter wherever Jetpack is stricter. A record that breaks it is dropped and
 * counted, never clamped: clamping would invent a value the ring never measured.
 */
object HealthRanges {
    const val HEART_RATE_MIN_BPM = 1L
    const val HEART_RATE_MAX_BPM = 300L
    const val RMSSD_MIN_MS = 1.0
    const val RMSSD_MAX_MS = 200.0
    const val OXYGEN_SATURATION_MIN_PERCENT = 0.0
    const val OXYGEN_SATURATION_MAX_PERCENT = 100.0
    const val RESPIRATORY_RATE_MIN = 0.0
    const val RESPIRATORY_RATE_MAX = 1000.0
    const val SKIN_BASELINE_MIN_CELSIUS = 0.0
    const val SKIN_BASELINE_MAX_CELSIUS = 100.0
    const val SKIN_DELTA_MIN_CELSIUS = -30.0
    const val SKIN_DELTA_MAX_CELSIUS = 30.0
    const val STEPS_MIN = 1L
    const val STEPS_MAX = 1_000_000L
    const val DISTANCE_MIN_METERS = 0.0
    const val DISTANCE_MAX_METERS = 1_000_000.0
    const val ACTIVE_CALORIES_MIN_KCAL = 0.0
    const val ACTIVE_CALORIES_MAX_KCAL = 1_000_000.0
    const val BMR_MIN_KCAL_PER_DAY = 0.0
    const val BMR_MAX_KCAL_PER_DAY = 10_000.0
    const val PERIOD_MAX_DAYS = 31L

    /** The records that pass, in their given order, and how many were dropped. */
    data class Checked<R : HealthRecord>(val kept: List<R>, val rejected: Int)

    /** Why [record] breaks a rule at [now], or null when it breaks none. */
    fun violation(record: HealthRecord, now: Instant): String? {
        // Nothing may start in the future (an instant record: its time). A period's end may.
        val start = when (record) {
            is InstantHealthRecord -> record.time
            is IntervalHealthRecord -> record.start
        }
        if (start.isAfter(now)) return "starts in the future"
        if (record is IntervalHealthRecord) {
            // A heart-rate series may be a single instant; every other span must move forward.
            val forward = if (record is HeartRateSeries) !record.end.isBefore(record.start) else record.start.isBefore(record.end)
            if (!forward) return "ends before it starts"
        }
        return when (record) {
            is HeartRateSeries -> heartRateSeries(record)
            is RestingHeartRate -> outside("resting heart rate", record.beatsPerMinute, HEART_RATE_MIN_BPM, HEART_RATE_MAX_BPM)
            is HeartRateVariabilityRmssd -> outside("RMSSD", record.millis, RMSSD_MIN_MS, RMSSD_MAX_MS)
            is OxygenSaturation -> outside("oxygen saturation", record.percent, OXYGEN_SATURATION_MIN_PERCENT, OXYGEN_SATURATION_MAX_PERCENT)
            is RespiratoryRate -> outside("respiratory rate", record.breathsPerMinute, RESPIRATORY_RATE_MIN, RESPIRATORY_RATE_MAX)
            is SkinTemperature -> skinTemperature(record)
            is Steps -> outside("steps", record.count, STEPS_MIN, STEPS_MAX)
            is Distance -> outside("distance", record.meters, DISTANCE_MIN_METERS, DISTANCE_MAX_METERS)
            is ActiveCaloriesBurned -> outside("active calories", record.kilocalories, ACTIVE_CALORIES_MIN_KCAL, ACTIVE_CALORIES_MAX_KCAL)
            is BasalMetabolicRate -> outside("basal metabolic rate", record.kilocaloriesPerDay, BMR_MIN_KCAL_PER_DAY, BMR_MAX_KCAL_PER_DAY)
            is SleepSession -> sleepSession(record)
            is MenstruationFlow -> null
            is MenstruationPeriod ->
                if (record.end.isAfter(record.start.plusSeconds(PERIOD_MAX_DAYS * SECONDS_PER_DAY))) "a period lasts at most $PERIOD_MAX_DAYS days" else null
        }
    }

    /** Splits [records] into the ones that pass at [now] and a count of the ones dropped. */
    fun <R : HealthRecord> check(records: List<R>, now: Instant): Checked<R> {
        val kept = records.filter { violation(it, now) == null }
        return Checked(kept, records.size - kept.size)
    }

    private const val SECONDS_PER_DAY = 86_400L

    private fun heartRateSeries(r: HeartRateSeries): String? {
        if (r.samples.isEmpty()) return "a heart-rate series needs at least one sample"
        for (s in r.samples) {
            if (s.time.isBefore(r.start) || s.time.isAfter(r.end)) return "a heart-rate sample lies outside its series"
            outside("heart rate", s.beatsPerMinute, HEART_RATE_MIN_BPM, HEART_RATE_MAX_BPM)?.let { return it }
        }
        return null
    }

    private fun skinTemperature(r: SkinTemperature): String? {
        r.baselineCelsius?.let { b ->
            outside("skin temperature baseline", b, SKIN_BASELINE_MIN_CELSIUS, SKIN_BASELINE_MAX_CELSIUS)?.let { return it }
        }
        for (d in r.deltas) {
            if (d.time.isBefore(r.start) || !d.time.isBefore(r.end)) return "a skin temperature delta lies outside [start, end)"
            outside("skin temperature delta", d.deltaCelsius, SKIN_DELTA_MIN_CELSIUS, SKIN_DELTA_MAX_CELSIUS)?.let { return it }
        }
        return null
    }

    private fun sleepSession(r: SleepSession): String? {
        var previousEnd: Instant? = null
        for (stage in r.stages.sortedBy { it.start }) {
            if (!stage.start.isBefore(stage.end)) return "a sleep stage ends before it starts"
            if (stage.start.isBefore(r.start) || stage.end.isAfter(r.end)) return "a sleep stage lies outside its session"
            if (previousEnd != null && stage.start.isBefore(previousEnd)) return "sleep stages overlap"
            previousEnd = stage.end
        }
        return null
    }

    private fun outside(what: String, value: Long, min: Long, max: Long): String? =
        if (value in min..max) null else "$what $value is outside $min..$max"

    // A NaN is in no range, so it is refused too.
    private fun outside(what: String, value: Double, min: Double, max: Double): String? =
        if (value in min..max) null else "$what $value is outside $min..$max"
}
