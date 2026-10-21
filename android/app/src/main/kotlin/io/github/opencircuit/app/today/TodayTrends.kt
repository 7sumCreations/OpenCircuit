package io.github.opencircuit.app.today

import io.github.opencircuit.ringkit.HRSample
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.ringkit.RestingHR
import io.github.opencircuit.ringkit.SleepEdit
import io.github.opencircuit.ringkit.TrendsEngine
import io.github.opencircuit.store.DailySteps
import io.github.opencircuit.store.DaytimeTemperature
import io.github.opencircuit.store.StepSample
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The part of one stored night the dashboard reads; [night] is the start of its WAKE day. */
data class TodayNight(
    val night: Instant,
    val asleepMin: Int,
    val sleepScore: Int,
    val stressScore: Int,
    val skinTempC: Double,
    val inBedStart: Instant,
    val inBedEnd: Instant,
)

/** What the dashboard reads from the store for one window: bounded reads, oldest first. */
data class TodayWindowInputs(
    val nights: List<TodayNight> = emptyList(),
    val dailies: List<DailySteps> = emptyList(),
    val hr: List<QuantitySample> = emptyList(),
    val hrv: List<QuantitySample> = emptyList(),
    val spo2: List<QuantitySample> = emptyList(),
    val rr: List<QuantitySample> = emptyList(),
    val dayTemps: List<DaytimeTemperature> = emptyList(),
    val stepDeltas: List<StepSample> = emptyList(),
)

/**
 * The window load the Today tiles, the readiness card and the synthesis line read — the part of
 * upstream `TrendsData` they use (ios/OpenCircuit/Trends/TrendsData.swift @ 2d07e550): one
 * [TrendsEngine.DailyPoint] per day with data, oldest first; the derived resting heart rate; each
 * day's heart-rate low–high (the Android heart-rate tile, PORTING.md D-306); and the time of the
 * newest stored reading of any kind.
 */
data class TodayTrends(
    val points: List<TrendsEngine.DailyPoint> = emptyList(),
    val restingHR: List<RestingHR.DailyValue> = emptyList(),
    val heartRateRanges: List<DayRange> = emptyList(),
    val newestSampleAt: Instant? = null,
    /** The wake days (start of day) a stored night is keyed on, scored or not: readiness tells "no night" from "no score". */
    val nightKeys: Set<Instant> = emptySet(),
    /** The window's stored nights as read: the Sleep goal ring credits the one that ended on its day. */
    val nights: List<TodayNight> = emptyList(),
) {
    companion object {

        /**
         * The per-day rollup (upstream `computePoints`, `TrendsData.swift:228-300`, the fields the
         * dashboard reads): a night's HRV, SpO₂ and breathing rate are averaged over its in-bed window
         * and land on its wake day; the day's heart rate keeps readings above the trends guard
         * (`TrendsEngine.MIN_VALID_HR`); 0 on a night's summary means not measured. Days are calendar
         * days in [zone]. Pure.
         */
        fun of(inputs: TodayWindowInputs, zone: ZoneId): TodayTrends {
            fun dayOf(t: Instant): LocalDate = LocalDate.ofInstant(t, zone)
            val nightsByDay = inputs.nights.associateBy { dayOf(it.night) }
            val stepsByDay = inputs.dailies.associate { dayOf(it.day) to it.steps.coerceIn(0, Int.MAX_VALUE.toLong()).toInt() }
            val tempDays = inputs.dayTemps.map { dayOf(it.time) }
            val vitalsDays = (inputs.hr + inputs.hrv + inputs.spo2 + inputs.rr).map { dayOf(it.start) }
            val allDays = (nightsByDay.keys + stepsByDay.keys + vitalsDays + tempDays).toSortedSet()
            val hrByDay = inputs.hr.groupBy { dayOf(it.start) }

            val ranges = mutableListOf<DayRange>()
            val points = allDays.map { day ->
                val start = day.atStartOfDay(zone).toInstant()
                val s = nightsByDay[day]
                // Both ends guarded: a legacy or partial row can carry a start with no end.
                val window = s?.takeIf { it.inBedStart.isAfter(SleepEdit.DISTANT_PAST) && it.inBedEnd.isAfter(it.inBedStart) }
                    ?.let { it.inBedStart to it.inBedEnd }
                val dayHR = hrByDay[day].orEmpty().map { it.value }.filter { it > TrendsEngine.MIN_VALID_HR }
                if (dayHR.isNotEmpty()) ranges += DayRange(start, dayHR.min(), dayHR.max())
                TrendsEngine.DailyPoint(
                    date = start,
                    steps = stepsByDay[day],
                    sleepMinutes = s?.asleepMin?.takeIf { it > 0 },
                    sleepScore = s?.sleepScore?.takeIf { it > 0 },
                    stressScore = s?.stressScore?.takeIf { it > 0 },
                    skinTempC = s?.skinTempC?.takeIf { it > 0 },
                    sleepHRAvg = average(inputs.hr, window, TrendsEngine.MIN_VALID_HR),
                    sleepHRVAvg = average(inputs.hrv, window),
                    sleepSpO2Avg = average(inputs.spo2, window),
                    sleepRRAvg = average(inputs.rr, window),
                    // The day's own readings (its range's too): a reading at the next midnight is the
                    // next day's only, where upstream's closed `DateInterval` counts it for both.
                    dayHRAvg = if (dayHR.isEmpty()) null else dayHR.fold(0.0) { acc, v -> acc + v } / dayHR.size,
                )
            }
            val restingHR = RestingHR.dailyValues(
                hr = inputs.hr.filter { it.value > 0 }.map { HRSample(bpm = it.value.toInt(), start = it.start, end = it.end) },
                zone = zone,
            )
            val newest = listOfNotNull(
                inputs.hr.maxOfOrNull { it.start }, inputs.hrv.maxOfOrNull { it.start }, inputs.spo2.maxOfOrNull { it.start },
                inputs.rr.maxOfOrNull { it.start }, inputs.dayTemps.maxOfOrNull { it.time }, inputs.stepDeltas.maxOfOrNull { it.end },
            ).maxOrNull()
            return TodayTrends(points, restingHR, ranges, newest, nightsByDay.keys.map { it.atStartOfDay(zone).toInstant() }.toSet(), inputs.nights)
        }

        /** The mean of the values above [minValue] whose start is inside [window] (both ends included, as `DateInterval.contains`). */
        private fun average(samples: List<QuantitySample>, window: Pair<Instant, Instant>?, minValue: Double = 0.0): Double? {
            val (from, to) = window ?: return null
            val values = samples.filter { !it.start.isBefore(from) && !it.start.isAfter(to) && it.value > minValue }.map { it.value }
            if (values.isEmpty()) return null
            return values.fold(0.0) { acc, v -> acc + v } / values.size
        }
    }
}
