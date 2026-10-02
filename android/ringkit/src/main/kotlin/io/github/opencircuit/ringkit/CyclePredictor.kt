package io.github.opencircuit.ringkit

// Women's health cycle prediction. Pure maths: rolling average cycle length from logged period history,
// next-period date, fertile / ovulation window, and an optional soft skin-temperature corroboration
// signal; plus the bounds of the period mirror (which days an open or ended period covers in the health
// store). Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/CyclePredictor.swift
// (@ b1c2fdd), whole.
//
// IMPORTANT: all outputs are ESTIMATES only. This is NOT a medical device; outputs are NOT a basis for
// contraception or medical decisions. Label all predictions in the UI.
//
// Skin-temp integration: a post-ovulation BBT rise (typically +0.2–0.5 °C) is used as a SOFT
// corroboration signal only — it never overrides the calendar estimate.
//
// DATE ARITHMETIC. Upstream's `Date` is one `Double` of seconds since 2001-01-01 UTC, and
// `cycleStats` / `predict` add, subtract and compare in those doubles (a cycle of 28.479… days is added
// as 2 460 600.000… seconds, rounded at every step of the roll-forward). The port does exactly the same
// arithmetic on the same doubles and converts each result to an `Instant` once, to the nearest
// nanosecond, so a prediction lands on the instant upstream's does (at 2026 a double holds about a tenth
// of a microsecond, so the conversion loses nothing). The day helpers work on calendar days (`LocalDate`)
// in the caller's zone. Shape notes: upstream's `Calendar = .current` is a required `ZoneId`, and
// `predict`'s `now` is required; a day count is a `Long` (Swift's 64-bit `Int`); values compare their
// doubles by IEEE `==`, as Swift's synthesized `Equatable` does.

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.floor

object CyclePredictor {

    // MARK: Constants

    /** Minimum logged period starts (separate cycles) before predicting. Two give one cycle length. */
    const val MIN_PERIODS_FOR_PREDICTION: Int = 2

    /** Typical luteal phase used to estimate ovulation from the predicted next period (clinical mean ≈ 14 days). */
    const val LUTEAL_PHASE_DAYS: Int = 14

    /** Days before ovulation included in the fertile window (+ ovulation day = 6 days total). */
    const val FERTILE_WINDOW_DAYS_BEFORE_OVULATION: Int = 5

    /** Minimum sane cycle length (days); shorter intervals are excluded as likely logging errors. */
    const val MIN_CYCLE_LENGTH_DAYS: Int = 21

    /** Maximum sane cycle length (days); longer intervals are excluded (likely a skipped log). */
    const val MAX_CYCLE_LENGTH_DAYS: Int = 45

    /** Skin-temp offset (°C above baseline) that counts as a "post-ovulation rise" night. */
    const val TEMP_RISE_CORROBORATION_C: Double = 0.2

    /** Nights with a qualifying rise needed to set [CyclePrediction.tempCorroborated]. */
    const val TEMP_RISE_NIGHTS_REQUIRED: Int = 2

    /**
     * Maximum number of days an OPEN period (started, never ended) may AUTO-EXTEND itself — FIGO's ≤ 8-day
     * normal menstrual bleeding duration, inclusive of the start day. This bounds auto-extension only: a
     * period with an EXPLICIT logged end is untouched at any length, and days already written to the health
     * store are never withdrawn — reaching the cap only stops the app ADDING further days on its own.
     */
    const val MAX_AUTO_EXTEND_PERIOD_DAYS: Int = 8

    /**
     * How many cycles [predict] adds one at a time, as upstream does, before jumping the rest in one step.
     * 2²⁰ cycles of the shortest length are about 57 000 years: every date Foundation represents is reached
     * one addition at a time (its distant future from 2026 is about 35 000); only a clock near `Instant`'s
     * own ends goes past it, where upstream would add for minutes.
     */
    internal const val MAX_ROLL_FORWARD_STEPS: Int = 1 shl 20

    private const val SECONDS_PER_DAY: Double = 86_400.0

    /** Foundation's reference date, 2001-01-01 00:00:00 UTC. */
    private val REFERENCE_DATE: Instant = Instant.ofEpochSecond(978_307_200L)

    // MARK: Input types

    /** One manually logged period entry: its first day, and its last day once the user logs it. */
    data class PeriodEntry(val start: Instant, val end: Instant? = null)

    /**
     * One night's signed skin-temperature offset (°C above baseline), from `SkinTempBaseline` — upstream's
     * `(night: Date, offsetC: Double)` tuple. Compares its offset by IEEE `==`.
     */
    class SkinTempNight(val night: Instant, val offsetC: Double) {
        override fun equals(other: Any?): Boolean = other is SkinTempNight && night == other.night && offsetC == other.offsetC
        override fun hashCode(): Int = night.hashCode() * 31 + ieeeHash(offsetC)
        override fun toString(): String = "SkinTempNight(night=$night, offsetC=$offsetC)"
    }

    // MARK: Output types

    /** Descriptive statistics derived from logged cycle history. */
    class CycleStats(
        /** Rolling mean cycle length (days) from the valid inter-period intervals. */
        val avgCycleLengthDays: Double,
        /** Number of complete cycle intervals used (always ≥ 1 from [cycleStats]). */
        val sampleCount: Int,
        /** Mean period duration (days) from completed (start + end) entries, or null. */
        val avgPeriodDurationDays: Double?,
    ) {
        override fun equals(other: Any?): Boolean =
            other is CycleStats && avgCycleLengthDays == other.avgCycleLengthDays && sampleCount == other.sampleCount &&
                ieeeEquals(avgPeriodDurationDays, other.avgPeriodDurationDays)

        override fun hashCode(): Int =
            (ieeeHash(avgCycleLengthDays) * 31 + sampleCount) * 31 + (avgPeriodDurationDays?.let(::ieeeHash) ?: 0)

        override fun toString(): String =
            "CycleStats(avgCycleLengthDays=$avgCycleLengthDays, sampleCount=$sampleCount, avgPeriodDurationDays=$avgPeriodDurationDays)"
    }

    /** ESTIMATE — predicted next period + fertile / ovulation window. Label every date in the UI. */
    class CyclePrediction(
        /** Predicted first day of the next period (ESTIMATE). */
        val nextPeriodStart: Instant,
        /** Predicted last day of the next period (ESTIMATE — start + average duration). */
        val nextPeriodEnd: Instant,
        /** Predicted fertile window start (ESTIMATE — ovulation − 5 days). */
        val fertileWindowStart: Instant,
        /** Predicted fertile window end = ovulation day (ESTIMATE). */
        val fertileWindowEnd: Instant,
        /** Predicted ovulation day (ESTIMATE — next period start − [LUTEAL_PHASE_DAYS]). */
        val ovulationEstimate: Instant,
        /** Average cycle length used for this prediction (days). */
        val avgCycleLengthDays: Double,
        /** True when skin-temp data shows a qualifying rise near the predicted ovulation (soft signal only). */
        val tempCorroborated: Boolean,
    ) {
        override fun equals(other: Any?): Boolean =
            other is CyclePrediction && nextPeriodStart == other.nextPeriodStart && nextPeriodEnd == other.nextPeriodEnd &&
                fertileWindowStart == other.fertileWindowStart && fertileWindowEnd == other.fertileWindowEnd &&
                ovulationEstimate == other.ovulationEstimate && avgCycleLengthDays == other.avgCycleLengthDays &&
                tempCorroborated == other.tempCorroborated

        override fun hashCode(): Int =
            listOf(nextPeriodStart, nextPeriodEnd, fertileWindowStart, fertileWindowEnd, ovulationEstimate).hashCode() * 31 +
                ieeeHash(avgCycleLengthDays) * 2 + (if (tempCorroborated) 1 else 0)

        override fun toString(): String =
            "CyclePrediction(nextPeriodStart=$nextPeriodStart, nextPeriodEnd=$nextPeriodEnd, fertileWindowStart=$fertileWindowStart, " +
                "fertileWindowEnd=$fertileWindowEnd, ovulationEstimate=$ovulationEstimate, avgCycleLengthDays=$avgCycleLengthDays, " +
                "tempCorroborated=$tempCorroborated)"
    }

    // MARK: Foundation's date arithmetic

    /** [t] as upstream's `Date` holds it: `Double` seconds since 2001. */
    private fun seconds(t: Instant): Double = secondsBetween(REFERENCE_DATE, t)

    /** Upstream's `Date` [s] as an `Instant`, to the nearest nanosecond; past `Instant`'s ends it saturates. */
    private fun instant(s: Double): Instant = checkNotNull(addingSeconds(REFERENCE_DATE, s)) { "a date is never NaN here" }

    /** The entries with their starts in seconds, sorted by start; ties keep their given order (Swift's sort is stable). */
    private fun sortedByStart(periods: List<PeriodEntry>): List<Pair<PeriodEntry, Double>> =
        periods.map { it to seconds(it.start) }.sortedBy { it.second }

    // MARK: Core functions

    /**
     * Rolling cycle statistics from logged period history. Periods are sorted by start; inter-period
     * intervals outside [MIN_CYCLE_LENGTH_DAYS]…[MAX_CYCLE_LENGTH_DAYS] are excluded as likely errors.
     * Null when fewer than [MIN_PERIODS_FOR_PREDICTION] entries or no valid interval exist.
     */
    fun cycleStats(periods: List<PeriodEntry>): CycleStats? {
        val sorted = sortedByStart(periods)
        if (sorted.size < MIN_PERIODS_FOR_PREDICTION) return null

        var intervalSum = 0.0
        var intervalCount = 0
        for (i in 1 until sorted.size) {
            val days = (sorted[i].second - sorted[i - 1].second) / SECONDS_PER_DAY
            if (days >= MIN_CYCLE_LENGTH_DAYS.toDouble() && days <= MAX_CYCLE_LENGTH_DAYS.toDouble()) {
                intervalSum += days
                intervalCount++
            }
        }
        if (intervalCount == 0) return null
        val avgCycle = intervalSum / intervalCount.toDouble()

        // Average period duration — only from entries with both start and end; sanity 1–10 days.
        var durationSum = 0.0
        var durationCount = 0
        for ((entry, start) in sorted) {
            val end = entry.end ?: continue
            val d = (seconds(end) - start) / SECONDS_PER_DAY
            if (d >= 1 && d <= 10) {
                durationSum += d
                durationCount++
            }
        }
        val avgDuration = if (durationCount == 0) null else durationSum / durationCount.toDouble()

        return CycleStats(avgCycleLengthDays = avgCycle, sampleCount = intervalCount, avgPeriodDurationDays = avgDuration)
    }

    /**
     * Predict the next period and the fertile / ovulation window from logged history.
     *
     * @param periods all logged period entries, in any order.
     * @param skinTempDeviations nightly signed offsets (°C above baseline); a SOFT corroboration signal only.
     *   Each entry counts once, so pass one value per night.
     * @param now the present the prediction is rolled forward to: the next period is the first one at or
     *   after it (a user who stopped logging still sees the NEXT period, not one already elapsed).
     * @return null when [cycleStats] is null.
     */
    fun predict(periods: List<PeriodEntry>, skinTempDeviations: List<SkinTempNight> = emptyList(), now: Instant): CyclePrediction? {
        val stats = cycleStats(periods) ?: return null
        val last = sortedByStart(periods).lastOrNull() ?: return null

        val cycleInterval = stats.avgCycleLengthDays * SECONDS_PER_DAY
        // One cycle after the last LOGGED period, then rolled forward by whole cycles until it is not
        // before now.
        var nextStart = last.second + cycleInterval
        if (cycleInterval > 0) nextStart = rollForward(nextStart, cycleInterval, seconds(now))

        val durationDays = stats.avgPeriodDurationDays ?: 5.0 // default 5 days
        val nextEnd = nextStart + durationDays * SECONDS_PER_DAY

        // Ovulation = predicted next period start − luteal phase (14 days).
        val ovulation = nextStart + -LUTEAL_PHASE_DAYS.toDouble() * SECONDS_PER_DAY
        // Fertile window: 5 days before ovulation up to and including ovulation day.
        val fertileStart = ovulation + -FERTILE_WINDOW_DAYS_BEFORE_OVULATION.toDouble() * SECONDS_PER_DAY

        // Skin-temp corroboration: ≥ TEMP_RISE_NIGHTS_REQUIRED nights with offsetC ≥ TEMP_RISE_CORROBORATION_C
        // in a ±3-day window (closed) around predicted ovulation. A SOFT signal — it cannot confirm ovulation.
        val windowStart = ovulation + -3 * SECONDS_PER_DAY
        val windowEnd = ovulation + 3 * SECONDS_PER_DAY
        val risingNights = skinTempDeviations.count {
            val night = seconds(it.night)
            night >= windowStart && night <= windowEnd && it.offsetC >= TEMP_RISE_CORROBORATION_C
        }

        val ovulationInstant = instant(ovulation)
        return CyclePrediction(
            nextPeriodStart = instant(nextStart),
            nextPeriodEnd = instant(nextEnd),
            fertileWindowStart = instant(fertileStart),
            fertileWindowEnd = ovulationInstant, // fertile window ends on ovulation day
            ovulationEstimate = ovulationInstant,
            avgCycleLengthDays = stats.avgCycleLengthDays,
            tempCorroborated = risingNights >= TEMP_RISE_NIGHTS_REQUIRED,
        )
    }

    /**
     * Upstream's `while nextStart < now { nextStart += cycle }`, addition by addition, for up to
     * [MAX_ROLL_FORWARD_STEPS] cycles; past that the remaining whole cycles are added in one step and
     * upstream's loop finishes the last one or two, so the answer is still the first step at or after
     * [now] and the loop ends at any clock.
     */
    private fun rollForward(first: Double, cycle: Double, now: Double): Double {
        var next = first
        var steps = 0
        while (next < now) {
            if (steps == MAX_ROLL_FORWARD_STEPS) {
                val wholeCycles = floor((now - next) / cycle)
                if (wholeCycles > 0) next += wholeCycles * cycle
                while (next < now) next += cycle
                return next
            }
            next += cycle
            steps++
        }
        return next
    }

    // MARK: Day-classification helpers

    /** [t]'s calendar day in [zone], or null when `java.time` cannot place it. */
    private fun day(t: Instant, zone: ZoneId): LocalDate? = CalendarDay.date(t, zone)

    private fun earlier(a: LocalDate, b: LocalDate): LocalDate = if (b.isBefore(a)) b else a

    private fun later(a: LocalDate, b: LocalDate): LocalDate = if (b.isAfter(a)) b else a

    /**
     * Whether [date]'s day in [zone] falls within a logged period (start…end days, inclusive); an entry with
     * no end covers its start day only. An instant `java.time` cannot place is no day.
     */
    fun isLoggedPeriodDay(date: Instant, entries: List<PeriodEntry>, zone: ZoneId): Boolean {
        val day = day(date, zone) ?: return false
        for (entry in entries) {
            val start = day(entry.start, zone) ?: continue
            val end = if (entry.end == null) start else day(entry.end, zone) ?: continue
            if (!day.isBefore(start) && !day.isAfter(end)) return true
        }
        return false
    }

    /** Whether [date]'s day in [zone] falls within the predicted period (inclusive). */
    fun isInPredictedPeriod(date: Instant, prediction: CyclePrediction, zone: ZoneId): Boolean =
        isWithinDays(date, prediction.nextPeriodStart, prediction.nextPeriodEnd, zone)

    /** Whether [date]'s day in [zone] falls within the predicted fertile window (inclusive). */
    fun isInFertileWindow(date: Instant, prediction: CyclePrediction, zone: ZoneId): Boolean =
        isWithinDays(date, prediction.fertileWindowStart, prediction.fertileWindowEnd, zone)

    /** Whether [date] is on the predicted ovulation day in [zone]. */
    fun isOvulationDay(date: Instant, prediction: CyclePrediction, zone: ZoneId): Boolean =
        CalendarDay.isSameDay(date, prediction.ovulationEstimate, zone)

    private fun isWithinDays(date: Instant, first: Instant, last: Instant, zone: ZoneId): Boolean {
        val day = day(date, zone) ?: return false
        val s = day(first, zone) ?: return false
        val e = day(last, zone) ?: return false
        return !day.isBefore(s) && !day.isAfter(e)
    }

    // MARK: Health-store mirror bounds (open-period auto-extension)
    //
    // The health store models flow as one sample PER DAY, so mirroring a period means deciding which DAYS
    // it covers. Days are calendar days in the caller's zone, so a period that starts on a day whose
    // midnight the zone skips counts that day like any other.

    /**
     * The last day an OPEN period (no logged end) may cover — the start of that day in [zone]: today, or the
     * auto-extension cap, whichever comes FIRST — but NEVER earlier than the span already mirrored.
     *
     * [alreadyCoveredDays] IS THE ANTI-RETRACTION FLOOR AND IT IS LOAD-BEARING: the cap stops the app ADDING
     * days the wearer never stated; it must never take back a day already written (a period left open
     * before the cap shipped may already have N ≫ 8 samples). Pass the number of days already mirrored (one
     * sample per covered day), 0 for a period never written. The floor is clamped to today: a stale
     * tracking count never pushes the mirror into the future.
     *
     * @return null when `java.time` cannot place [start] or [today] in [zone].
     */
    fun openPeriodAutoExtendLastDay(start: Instant, today: Instant, alreadyCoveredDays: Int = 0, zone: ZoneId): Instant? =
        autoExtendLastDay(start, today, alreadyCoveredDays, zone)?.let { startOfDay(it, zone) }

    /**
     * The last day this period should be mirrored (the start of that day in [zone]). An EXPLICIT end is
     * authoritative and NOT capped — the user stated it — clamped only to today, since a future day is
     * never asserted. Only the open case, where the app would otherwise invent days, is bounded.
     *
     * @return null when `java.time` cannot place a date it needs in [zone].
     */
    fun periodMirrorLastDay(start: Instant, end: Instant?, today: Instant, alreadyCoveredDays: Int = 0, zone: ZoneId): Instant? =
        mirrorLastDay(start, end, today, alreadyCoveredDays, zone)?.let { startOfDay(it, zone) }

    /**
     * How many one-day samples this period should currently have: 0 when the span is empty (a start dated
     * in the future, or after its end) or a date cannot be placed in [zone].
     */
    fun periodMirrorDayCount(start: Instant, end: Instant?, today: Instant, alreadyCoveredDays: Int = 0, zone: ZoneId): Long {
        val firstDay = day(start, zone) ?: return 0
        val lastDay = mirrorLastDay(start, end, today, alreadyCoveredDays, zone) ?: return 0
        if (lastDay.isBefore(firstDay)) return 0
        return ChronoUnit.DAYS.between(firstDay, lastDay) + 1
    }

    /**
     * Whether an already-mirrored period's copy is still CORRECT — rebuilding it now would produce the same
     * set of days, so a rewrite carries no new information and must be skipped. [writtenSampleCount] is the
     * number of samples tracked for this entry (one per covered day, so it IS the covered span). This answers
     * only "has a new DAY appeared?"; a change to a clinical field leaves the count equal and is caught by
     * the caller clearing its written watermark. A count of 0 expected is "nothing to do", never "up to date".
     */
    fun periodMirrorIsUpToDate(writtenSampleCount: Int, start: Instant, end: Instant?, today: Instant, zone: ZoneId): Boolean {
        val expected = periodMirrorDayCount(start, end, today, alreadyCoveredDays = writtenSampleCount, zone = zone)
        return expected > 0 && writtenSampleCount.toLong() == expected
    }

    /** Whether an OPEN period has reached the auto-extension cap and stopped growing (drives the UI copy). */
    fun openPeriodHasReachedAutoExtendCap(start: Instant, today: Instant, zone: ZoneId): Boolean {
        val firstDay = day(start, zone) ?: return false
        val todayDay = day(today, zone) ?: return false
        val elapsed = ChronoUnit.DAYS.between(firstDay, todayDay) + 1
        return elapsed >= MAX_AUTO_EXTEND_PERIOD_DAYS
    }

    private fun autoExtendLastDay(start: Instant, today: Instant, alreadyCoveredDays: Int, zone: ZoneId): LocalDate? {
        val firstDay = day(start, zone) ?: return null
        val todayDay = day(today, zone) ?: return null
        // − 1 because the span is INCLUSIVE of the start day: an 8-day cap covers day 1…day 8. Upstream falls
        // back to the first day where its calendar answers no date; a calendar day past the end of
        // `LocalDate`'s range does the same.
        val capDay = plusDays(firstDay, MAX_AUTO_EXTEND_PERIOD_DAYS - 1L) ?: firstDay
        val capped = earlier(todayDay, capDay)
        if (alreadyCoveredDays <= 0) return capped
        val coveredDay = plusDays(firstDay, alreadyCoveredDays - 1L) ?: firstDay
        // The floor is clamped to today too: neither the cap nor the floor may assert a future day.
        return later(capped, earlier(coveredDay, todayDay))
    }

    private fun mirrorLastDay(start: Instant, end: Instant?, today: Instant, alreadyCoveredDays: Int, zone: ZoneId): LocalDate? {
        if (end == null) return autoExtendLastDay(start, today, alreadyCoveredDays, zone)
        val endDay = day(end, zone) ?: return null
        val todayDay = day(today, zone) ?: return null
        return earlier(endDay, todayDay)
    }

    private fun plusDays(d: LocalDate, days: Long): LocalDate? = zonedOrNull { d.plusDays(days) }

    private fun startOfDay(d: LocalDate, zone: ZoneId): Instant? = zonedOrNull { d.atStartOfDay(zone).toInstant() }
}
