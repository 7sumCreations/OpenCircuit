package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/GoalHistory.swift (@ b1c2fdd), whole.
//
// Port notes:
//  • Every `calendar: Calendar = .current` is a required `ZoneId`, every `now` a required `Instant`,
//    and `Goals.fromDefaults` reads a caller-supplied `GoalSettings` (see GoalDefaults.kt). Day starts
//    come from `CalendarDay` (a missing midnight starts the day at the first valid instant, as
//    Foundation's `startOfDay`); a rollup, night, nap or `now` that `java.time` cannot place (the
//    last year of `Instant`'s range) is left out — or, for `now`, compared as is and never current.
//  • An energy or minutes value that is not finite is unmeasured (its ring is not present). Upstream
//    marks it present: a NaN energy made the day's mean attainment NaN and +∞ closed the ring.
//  • A day's mean attainment adds the present rings in `Ring` declaration order; upstream adds a
//    `Set`'s elements in an order Swift seeds per process (last-bit differences only).
//  • A streak counts consecutive calendar dates in the zone. Upstream counts whole elapsed days
//    between day starts, so a day whose midnight does not exist (it starts at 01:00) is 23 hours
//    from the next one — zero days — and broke the streak (Santiago, 6 September 2026, measured).
//  • Sleep credit sums a day's minutes in 64 bits and saturates at `Int.MAX_VALUE` (upstream's 64-bit
//    sum would not fit an `Int`); the credit map iterates oldest day first. A nap is tested against
//    the credited nights by binary search over their windows sorted by start (with the latest end so
//    far), which answers exactly as upstream's test of every night, in O(log n) per nap.
//  • `Goals` and `DayInput` (upstream structs, `Goals` of `var`s) are immutable values comparing
//    doubles by IEEE `==`; `Day` is built only by `build` and holds read-only sets; `Summary` holds
//    read-only maps; sets and maps iterate in `Ring` declaration order.

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Collections
import java.util.EnumMap
import java.util.EnumSet
import java.util.TreeMap

/**
 * Historical daily goal-ring completion — the past-days twin of the Goals card's four rings.
 *
 * STORAGE: NONE. Every input a past day's rings need is already persisted and recomputed by the
 * trends pipeline (steps, the daily estimate's active kcal and elevated-HR minutes, stored sleep
 * and nap minutes), so ring history is a PURE FUNCTION of (stored daily rollups × the user's goals).
 *
 * Two honest consequences of deriving rather than snapshotting:
 *  1. GOALS ARE NOT VERSIONED. A day is scored against the goals set RIGHT NOW. Raising a goal
 *     re-scores history downward. The weekday/weekend split IS applied per day (a function of the
 *     date itself), so a Saturday is scored against the weekend goal.
 *  2. A DAY IS ONLY AS GOOD AS ITS RETAINED DATA. A day whose rollups were pruned has no rings — it
 *     reports `hasData == false` and is rendered as "no data", never as a failed day.
 *
 * Sleep is credited to the day the night ENDED (via `MissedNight.nightWakeReference`), matching the
 * Goals card's "last night" ring — deliberately different from the Sleep Duration chart, which plots
 * a night on its bedtime day.
 */
object GoalHistory {

    // Goals

    /**
     * The user's current goal settings, as a value so the history builder is pure and testable.
     * Mirrors the six `GoalDefaults` keys the Goals card reads. Compares its doubles by IEEE `==`.
     */
    data class Goals(
        val workdaySteps: Int = GoalDefaults.DEFAULT_WORKDAY_STEPS,
        val weekendSteps: Int = GoalDefaults.DEFAULT_WEEKEND_STEPS,
        val activeKcal: Double = GoalDefaults.DEFAULT_ACTIVE_KCAL,
        val activityMinutes: Double = GoalDefaults.DEFAULT_ACTIVITY_MINUTES,
        val workdaySleepMin: Int = GoalDefaults.DEFAULT_WORKDAY_SLEEP_MIN,
        val weekendSleepMin: Int = GoalDefaults.DEFAULT_WEEKEND_SLEEP_MIN,
    ) {
        /** The step goal that applies to [on]'s day in [zone] — the same weekday/weekend rule the Goals card uses. */
        fun stepGoal(on: Instant, zone: ZoneId): Int = if (GoalDefaults.isWeekend(on, zone)) weekendSteps else workdaySteps

        /** The sleep-minutes goal that applies to [on]'s day in [zone], weekday/weekend split as above. */
        fun sleepGoalMinutes(on: Instant, zone: ZoneId): Int = if (GoalDefaults.isWeekend(on, zone)) weekendSleepMin else workdaySleepMin

        override fun equals(other: Any?): Boolean =
            other is Goals && workdaySteps == other.workdaySteps && weekendSteps == other.weekendSteps &&
                activeKcal == other.activeKcal && activityMinutes == other.activityMinutes &&
                workdaySleepMin == other.workdaySleepMin && weekendSleepMin == other.weekendSleepMin

        override fun hashCode(): Int =
            listOf(workdaySteps, weekendSteps, ieeeHash(activeKcal), ieeeHash(activityMinutes), workdaySleepMin, weekendSleepMin).hashCode()

        companion object {
            /**
             * Read the six goals from [settings] using the same `GoalDefaults` keys the Goals card binds
             * to, each falling back to its default when nothing usable is stored.
             */
            fun fromDefaults(settings: GoalSettings): Goals = Goals(
                workdaySteps = GoalDefaults.storedInt(settings, GoalDefaults.WORKDAY_STEPS) ?: GoalDefaults.DEFAULT_WORKDAY_STEPS,
                weekendSteps = GoalDefaults.storedInt(settings, GoalDefaults.WEEKEND_STEPS) ?: GoalDefaults.DEFAULT_WEEKEND_STEPS,
                activeKcal = GoalDefaults.storedDouble(settings, GoalDefaults.ACTIVE_KCAL) ?: GoalDefaults.DEFAULT_ACTIVE_KCAL,
                activityMinutes = GoalDefaults.storedDouble(settings, GoalDefaults.ACTIVITY_MINUTES) ?: GoalDefaults.DEFAULT_ACTIVITY_MINUTES,
                workdaySleepMin = GoalDefaults.storedInt(settings, GoalDefaults.WORKDAY_SLEEP_MIN) ?: GoalDefaults.DEFAULT_WORKDAY_SLEEP_MIN,
                weekendSleepMin = GoalDefaults.storedInt(settings, GoalDefaults.WEEKEND_SLEEP_MIN) ?: GoalDefaults.DEFAULT_WEEKEND_SLEEP_MIN,
            )
        }
    }

    // Input

    /**
     * One calendar day's already-derived rollup. Every field is optional: null means "not available
     * for this day" and is scored as an EMPTY ring, never as a zero the user failed to beat.
     * [sleepMinutes] is the asleep minutes CREDITED to this day (see [sleepCreditByDay]). Compares
     * its doubles by IEEE `==`.
     */
    data class DayInput(
        val date: Instant, // start-of-day
        val steps: Int? = null,
        val activeKcal: Double? = null,
        val activityMinutes: Double? = null,
        val sleepMinutes: Int? = null,
    ) {
        override fun equals(other: Any?): Boolean =
            other is DayInput && date == other.date && steps == other.steps && ieeeEquals(activeKcal, other.activeKcal) &&
                ieeeEquals(activityMinutes, other.activityMinutes) && sleepMinutes == other.sleepMinutes

        override fun hashCode(): Int =
            listOf(date, steps, activeKcal?.let(::ieeeHash), activityMinutes?.let(::ieeeHash), sleepMinutes).hashCode()
    }

    // Output

    /** The four rings a metric can close, in the display order the Goals card uses. [rawValue] is upstream's case name. */
    enum class Ring(val rawValue: String) {
        STEPS("steps"),
        ACTIVE_KCAL("activeKcal"),
        ACTIVITY_MINUTES("activityMinutes"),
        SLEEP_MINUTES("sleepMinutes"),
    }

    /** One day of ring history; built by [build]. [present] and [met] are read-only and iterate in [Ring] order. */
    class Day internal constructor(
        val date: Instant,
        val progress: DailyGoalProgress,
        present: Set<Ring>,
        met: Set<Ring>,
        /** True when this day is still accumulating (today, or later than `now`). A partial day is never a miss. */
        val isPartial: Boolean,
    ) {
        /** Which rings had a measured value this day. A ring absent here is drawn empty and NOT counted as missed. */
        val present: Set<Ring> = readOnlyCopy(present)

        /** Which rings reached their goal. Always a subset of [present]. */
        val met: Set<Ring> = readOnlyCopy(met)

        val id: Instant get() = date

        /** Any measured data at all for this day. */
        val hasData: Boolean get() = present.isNotEmpty()

        /** How many rings closed (0…4). */
        val ringsMet: Int get() = met.size

        /** All four rings closed. The streak unit. */
        val closedAll: Boolean get() = met.size == Ring.entries.size

        /**
         * Mean attainment across the rings that HAVE data, 0…1 — null when the day has none. Averaging
         * over present rings only means a missing metric doesn't drag the day toward zero.
         */
        val attainment: Double?
            get() {
                if (!hasData) return null
                var sum = 0.0
                for (ring in present) sum += fraction(ring)
                return sum / present.size
            }

        /** This day's progress for one ring. */
        fun goalProgress(ring: Ring): GoalProgress = progressValue(progress, ring)

        /** Ring fill fraction 0…1 — 0 for a ring with no data (drawn empty). */
        fun fraction(ring: Ring): Double = if (ring in present) goalProgress(ring).fraction else 0.0

        override fun equals(other: Any?): Boolean =
            other is Day && date == other.date && progress == other.progress && present == other.present &&
                met == other.met && isPartial == other.isPartial

        override fun hashCode(): Int = listOf(date, progress, present, met, isPartial).hashCode()

        override fun toString(): String = "Day(date=$date, progress=$progress, present=$present, met=$met, isPartial=$isPartial)"
    }

    // Build

    /**
     * Score a run of daily rollups against the current goals: [days] in any order, the result sorted
     * oldest → newest, each dated at its start of day in [zone]; [now] decides which days are still
     * partial (its own day and later). A rollup no zone can place is left out.
     */
    fun build(days: List<DayInput>, goals: Goals, now: Instant, zone: ZoneId): List<Day> {
        val today = CalendarDay.startOfDay(now, zone) ?: now
        return days.sortedBy { it.date }.mapNotNull { input ->
            val dayStart = CalendarDay.startOfDay(input.date, zone) ?: return@mapNotNull null
            val stepGoal = goals.stepGoal(dayStart, zone).toDouble()
            val sleepGoal = goals.sleepGoalMinutes(dayStart, zone).toDouble()
            val activeKcal = input.activeKcal?.takeIf { it.isFinite() }
            val activityMinutes = input.activityMinutes?.takeIf { it.isFinite() }

            val progress = DailyGoalProgress(
                steps = GoalProgress((input.steps ?: 0).toDouble(), stepGoal),
                activeKcal = GoalProgress(activeKcal ?: 0.0, goals.activeKcal),
                activityMinutes = GoalProgress(activityMinutes ?: 0.0, goals.activityMinutes),
                sleepMinutes = GoalProgress((input.sleepMinutes ?: 0).toDouble(), sleepGoal),
            )

            // "Present" is about whether the metric was MEASURED, not whether it was non-zero.
            val present = EnumSet.noneOf(Ring::class.java)
            if (input.steps != null) present += Ring.STEPS
            if (activeKcal != null) present += Ring.ACTIVE_KCAL
            if (activityMinutes != null) present += Ring.ACTIVITY_MINUTES
            if (input.sleepMinutes != null) present += Ring.SLEEP_MINUTES

            val met = EnumSet.noneOf(Ring::class.java)
            for (ring in present) if (progressValue(progress, ring).met) met += ring

            Day(dayStart, progress, present, met, isPartial = dayStart >= today)
        }
    }

    internal fun progressValue(p: DailyGoalProgress, ring: Ring): GoalProgress = when (ring) {
        Ring.STEPS -> p.steps
        Ring.ACTIVE_KCAL -> p.activeKcal
        Ring.ACTIVITY_MINUTES -> p.activityMinutes
        Ring.SLEEP_MINUTES -> p.sleepMinutes
    }

    // Sleep credit

    /** One stored night, reduced to what the Sleep ring needs: its bedtime day key, its in-bed window (null on a legacy rollup), its minutes. */
    data class NightSleep(
        val nightKey: Instant,
        val inBedStart: Instant?,
        val inBedEnd: Instant?,
        val asleepMinutes: Int,
    )

    /** One nap's EFFECTIVE (post-edit) window. */
    data class NapSleep(
        val start: Instant,
        val end: Instant,
        val asleepMinutes: Int,
    )

    /**
     * Asleep minutes credited to each CALENDAR DAY in [zone], matching the Goals card's Sleep ring,
     * oldest day first:
     *  • a night is credited to the day it ENDED (`MissedNight.nightWakeReference` — the real wake
     *    when its in-bed window is known, else the night key);
     *  • naps fold into the day they started on, EXCEPT a nap overlapping ANY credited night's in-bed
     *    window (a manual nap has no night guard of its own; nights are keyed by WAKE day, so a 23:30
     *    nap sits inside a night credited to the NEXT day). The guard is all-or-nothing and needs an
     *    in-bed clock: a nap that merely clips a night loses all its minutes, and a legacy night with
     *    no clock guards nothing.
     * Rows with no minutes are skipped; a row no zone can place is left out; a day's total saturates
     * at `Int.MAX_VALUE`.
     */
    fun sleepCreditByDay(nights: List<NightSleep>, naps: List<NapSleep>, zone: ZoneId): Map<Instant, Int> {
        val credit = TreeMap<Instant, Long>()
        val creditedNight = HashMap<Instant, Array<Instant>>() // wake day → [start, end], widened

        for (night in nights) {
            if (night.asleepMinutes <= 0) continue
            val s = night.inBedStart
            val e = night.inBedEnd
            val hasClock = s != null && e != null && e > s
            val wake = MissedNight.nightWakeReference(inBedEnd = if (hasClock) e else null, nightKey = night.nightKey)
            val day = CalendarDay.startOfDay(wake, zone) ?: continue
            credit.merge(day, night.asleepMinutes.toLong(), Long::plus)
            if (hasClock) {
                // Widen rather than replace, so two nights landing on one wake day both guard.
                val existing = creditedNight[day]
                creditedNight[day] = if (existing == null) arrayOf(s!!, e!!) else arrayOf(minOf(existing[0], s!!), maxOf(existing[1], e!!))
            }
        }

        // A nap overlaps some window iff, among the windows starting before the nap ends (a prefix of
        // the windows sorted by start), the latest end is after the nap starts.
        val windows = creditedNight.values.sortedBy { it[0] }
        val starts = windows.map { it[0] }
        val latestEnd = ArrayList<Instant>(windows.size)
        for (w in windows) latestEnd += if (latestEnd.isEmpty()) w[1] else maxOf(latestEnd.last(), w[1])

        for (nap in naps) {
            if (nap.asleepMinutes <= 0) continue
            val before = startsBefore(starts, nap.end)
            if (before > 0 && latestEnd[before - 1] > nap.start) continue
            val day = CalendarDay.startOfDay(nap.start, zone) ?: continue
            credit.merge(day, nap.asleepMinutes.toLong(), Long::plus)
        }

        val out = LinkedHashMap<Instant, Int>()
        for ((day, minutes) in credit) out[day] = if (minutes > Int.MAX_VALUE) Int.MAX_VALUE else minutes.toInt()
        return Collections.unmodifiableMap(out)
    }

    /** How many of the ascending [starts] are strictly before [t]. */
    private fun startsBefore(starts: List<Instant>, t: Instant): Int {
        var lo = 0
        var hi = starts.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] < t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    // Summary

    /**
     * Roll-up stats over a window of ring history. [metCounts] / [dataCounts] are read-only, iterate
     * in [Ring] order and hold only rings with a count.
     *  • [daysWithData]: days in the window that had ANY measured ring.
     *  • [daysAllClosed]: finished days on which all four rings closed.
     *  • [currentStreak]: consecutive all-closed days ending at the most recent finished day; a
     *    still-running today extends it when it has already closed all four, and never breaks it. It is
     *    only CURRENT if it reaches today or yesterday.
     *  • [longestStreak]: longest all-closed run anywhere in the window (admits a closed partial today,
     *    as [currentStreak] does).
     */
    class Summary(
        val daysWithData: Int,
        val daysAllClosed: Int,
        metCounts: Map<Ring, Int>,
        dataCounts: Map<Ring, Int>,
        val currentStreak: Int,
        val longestStreak: Int,
    ) {
        val metCounts: Map<Ring, Int> = readOnlyCopy(metCounts)
        val dataCounts: Map<Ring, Int> = readOnlyCopy(dataCounts)

        override fun equals(other: Any?): Boolean =
            other is Summary && daysWithData == other.daysWithData && daysAllClosed == other.daysAllClosed &&
                metCounts == other.metCounts && dataCounts == other.dataCounts &&
                currentStreak == other.currentStreak && longestStreak == other.longestStreak

        override fun hashCode(): Int = listOf(daysWithData, daysAllClosed, metCounts, dataCounts, currentStreak, longestStreak).hashCode()

        override fun toString(): String =
            "Summary(daysWithData=$daysWithData, daysAllClosed=$daysAllClosed, metCounts=$metCounts, dataCounts=$dataCounts, " +
                "currentStreak=$currentStreak, longestStreak=$longestStreak)"
    }

    /**
     * Summarise a window produced by [build] (oldest → newest). Streak rules: only a day that closed
     * ALL FOUR rings extends a streak; a day with missing data does not close, so it breaks the
     * streak; a PARTIAL day that hasn't closed everything is skipped rather than counted as a break; a
     * gap in the calendar (a day absent from the window) breaks the streak — consecutive means
     * consecutive calendar dates in [zone]; and a run that does not reach today or yesterday (judged
     * against [now], required so a caller cannot silently reinstate the stale-streak bug) is not current.
     */
    fun summarize(days: List<Day>, now: Instant, zone: ZoneId): Summary {
        val metCounts = EnumMap<Ring, Int>(Ring::class.java)
        val dataCounts = EnumMap<Ring, Int>(Ring::class.java)
        var daysWithData = 0
        var daysAllClosed = 0
        for (day in days) {
            if (day.hasData) daysWithData += 1
            if (day.closedAll && !day.isPartial) daysAllClosed += 1
            for (ring in day.present) dataCounts.merge(ring, 1, Int::plus)
            for (ring in day.met) metCounts.merge(ring, 1, Int::plus)
        }

        // Longest run, oldest → newest; a partial day is admitted only when it has closed all four.
        var longest = 0
        var run = 0
        var previous: Instant? = null
        for (day in days) {
            if (day.isPartial && !day.closedAll) continue
            val adjacent = previous?.let { dateGap(it, day.date, zone) == 1L } ?: false
            if (day.closedAll) {
                run = if (adjacent) run + 1 else 1
                longest = maxOf(longest, run)
            } else {
                run = 0
            }
            previous = day.date
        }

        // Current streak: walk backwards from the newest day, skipping a not-yet-closed today.
        var current = 0
        var expected: Instant? = null
        for (day in days.asReversed()) {
            if (day.isPartial && !day.closedAll) continue
            if (expected != null && dateGap(day.date, expected, zone) != 1L) break
            if (!day.closedAll) break
            current += 1
            expected = day.date
        }
        // …and it is only CURRENT if its newest day is today or yesterday (a future row — clock skew —
        // is not current either; nor is a `now` no zone can place).
        if (current > 0) {
            val newestCounted = days.lastOrNull { !(it.isPartial && !it.closedAll) }?.date
            val daysSince = newestCounted?.let { dateGap(it, now, zone) }
            if (daysSince == null || daysSince !in 0L..1L) current = 0
        }

        return Summary(daysWithData, daysAllClosed, metCounts, dataCounts, current, longest)
    }

    /** Calendar dates from [a]'s day to [b]'s day in [zone]; null when either cannot be placed. */
    private fun dateGap(a: Instant, b: Instant, zone: ZoneId): Long? {
        val da = CalendarDay.date(a, zone) ?: return null
        val db = CalendarDay.date(b, zone) ?: return null
        return ChronoUnit.DAYS.between(da, db)
    }

    private fun readOnlyCopy(rings: Set<Ring>): Set<Ring> =
        Collections.unmodifiableSet(EnumSet.noneOf(Ring::class.java).apply { addAll(rings) })

    private fun readOnlyCopy(counts: Map<Ring, Int>): Map<Ring, Int> =
        Collections.unmodifiableMap(EnumMap<Ring, Int>(Ring::class.java).apply { putAll(counts) })

    private fun ieeeEquals(a: Double?, b: Double?): Boolean = if (a == null || b == null) a == null && b == null else a.toDouble() == b.toDouble()
}
