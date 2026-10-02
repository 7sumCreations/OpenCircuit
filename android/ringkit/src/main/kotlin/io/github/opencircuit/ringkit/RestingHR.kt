package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/RestingHR.swift (@ b1c2fdd),
// whole.
//
// Resting heart rate (daily) — derived on-device; the ring does not report it. Two tiers, in
// preference order: (1) the sleep mean — the average HR across the night's `asleep*` segments, the
// most stable, least motion-contaminated signal; (2) the lowest sustained — the minimum of a rolling
// window mean (default 5 min), Apple Health's own resting-HR convention; a multi-reading window
// rejects single-reading dips.
//
// Port notes:
//  • `dailyValues` takes the zone it groups days in (upstream defaults to the device calendar); a day
//    starts at `CalendarDay.startOfDay` and the next one a calendar day later in wall-clock time, as
//    Foundation's `date(byAdding: .day)` (so Santiago's day without a midnight runs 01:00 → 01:00).
//  • Window tests compare exact second differences instead of adding the window to an instant, so a
//    reading at the end of `Instant`'s range cannot overflow; a reading no zone can place in a day is
//    left out of the daily grouping (PORTING.md D-75).
//  • The window is `Double` seconds, as upstream's `TimeInterval`; a degenerate window behaves as
//    upstream (a single reading under a window of 0, below 0 or NaN gives NaN).

import java.time.Instant
import java.time.ZoneId

object RestingHR {

    /** Shortest span over which a depressed HR counts as "sustained" rather than a transient dip — 5 minutes, in seconds. */
    const val SUSTAINED_WINDOW: Double = 5.0 * 60

    /** Floor on the sleep-mean path: fewer asleep readings than this is too thin to trust as a night's resting value. */
    const val MIN_SLEEP_SAMPLES: Int = 3

    /**
     * One day's resting-HR estimate in bpm, or null when there are no readings at all. [sleep]
     * should be the segments overlapping this day; only `asleep*` stages count. Readings outside
     * [LiveHR.VALID_BPM] are dropped first, so a garbage epoch cannot become the day's resting HR.
     */
    fun value(
        hr: List<HRSample>,
        sleep: List<SleepSegment> = emptyList(),
        window: Double = SUSTAINED_WINDOW,
        minSleepSamples: Int = MIN_SLEEP_SAMPLES,
    ): Double? {
        val valid = hr.filter { it.bpm in LiveHR.VALID_BPM }
        sleepMean(valid, sleep, minSleepSamples)?.let { return it }
        return lowestSustained(valid, window)
    }

    /** Mean HR of readings that fall inside an `asleep*` segment; null below the floor or when there are no asleep segments. */
    internal fun sleepMean(hr: List<HRSample>, sleep: List<SleepSegment>, minSleepSamples: Int): Double? {
        val asleep = sleep.filter { isAsleep(it.stage) }
        if (asleep.isEmpty()) return null
        val inSleep = hr.filter { s -> asleep.any { seg -> s.start >= seg.start && s.start < seg.end } }
        if (inSleep.size < minSleepSamples) return null
        return mean(inSleep)
    }

    /**
     * Lowest rolling [window]-mean across the readings. Each window is anchored at a reading and must
     * hold ≥ 2 readings to count as "sustained"; if none qualifies (all readings isolated) the single
     * lowest reading is returned. null if empty. Upstream's two-pointer sweep, unchanged: every bpm is
     * a small integer, so the running double sum is the exact integer sum of the window.
     */
    internal fun lowestSustained(hr: List<HRSample>, window: Double): Double? {
        if (hr.isEmpty()) return null
        val sorted = hr.sortedBy { it.start } // stable, as Swift's sort
        val n = sorted.size
        var best: Double? = null
        var right = 0
        var windowSum = 0.0 // Σ bpm over [left, right)
        for (left in 0 until n) {
            val anchor = sorted[left].start
            while (right < n && secondsBetween(anchor, sorted[right].start) < window) {
                windowSum += sorted[right].bpm.toDouble()
                right++
            }
            val count = right - left
            if (count >= 2 || n == 1) {
                val m = windowSum / count.toDouble()
                best = best?.let { swiftMin(it, m) } ?: m
            }
            windowSum -= sorted[left].bpm.toDouble() // `left` leaves the window before the next step
        }
        if (best == null) best = swiftSequenceMin(sorted.map { it.bpm.toDouble() })
        return best
    }

    /** A [lowestSustained] value and whether a genuinely sustained window (≥ 2 readings) produced it. */
    internal data class Sustained(val value: Double, val wasSustained: Boolean)

    /**
     * As [lowestSustained], but reports whether a GENUINELY SUSTAINED window produced the value rather
     * than the single-lowest-reading fallback — which matters to a caller deriving a threshold from it.
     */
    internal fun lowestSustainedDetailed(hr: List<HRSample>, window: Double): Sustained? {
        val value = lowestSustained(hr, window) ?: return null
        return Sustained(value, hasSustainedWindow(hr, window))
    }

    /** Whether any two readings fall inside one [window] — the same `count >= 2` test as [lowestSustained]. */
    internal fun hasSustainedWindow(hr: List<HRSample>, window: Double): Boolean {
        val sorted = hr.sortedBy { it.start }
        if (sorted.size < 2) return false
        for (i in 0 until sorted.size - 1) {
            if (secondsBetween(sorted[i].start, sorted[i + 1].start) < window) return true
        }
        return false
    }

    /**
     * One day's resting HR: [day] is the start of the day in the zone it was grouped in. Compares as
     * Swift's synthesized `Equatable` (the bpm by IEEE `==`).
     */
    class DailyValue(val day: Instant, val bpm: Double) {
        override fun equals(other: Any?): Boolean = other is DailyValue && day == other.day && bpm == other.bpm

        override fun hashCode(): Int = 31 * day.hashCode() + ieeeHash(bpm)

        override fun toString(): String = "DailyValue(day=$day, bpm=$bpm)"
    }

    /**
     * Per-calendar-day resting HR over a span of readings, oldest day first. HR is bucketed by the
     * local day (in [zone]) of each reading's start; [sleep] segments are matched to a day by temporal
     * overlap (so an early-morning night lands on the day you woke). Days with no readings are omitted.
     */
    fun dailyValues(
        hr: List<HRSample>,
        sleep: List<SleepSegment> = emptyList(),
        zone: ZoneId,
        window: Double = SUSTAINED_WINDOW,
        minSleepSamples: Int = MIN_SLEEP_SAMPLES,
    ): List<DailyValue> {
        if (hr.isEmpty()) return emptyList()
        val byDay = LinkedHashMap<Instant, MutableList<HRSample>>()
        for (s in hr) {
            val day = CalendarDay.startOfDay(s.start, zone) ?: continue // unplaceable: no day to join
            byDay.getOrPut(day) { mutableListOf() } += s
        }
        val out = mutableListOf<DailyValue>()
        for (day in byDay.keys.sorted()) {
            val dayEnd = zonedOrNull { day.atZone(zone).plusDays(1).toInstant() } ?: addingSeconds(day, 86_400.0)!!
            val daySleep = sleep.filter { it.start < dayEnd && it.end > day }
            value(byDay.getValue(day), daySleep, window, minSleepSamples)?.let { out += DailyValue(day, it) }
        }
        return out
    }

    private fun isAsleep(stage: SleepStage): Boolean = when (stage) {
        SleepStage.ASLEEP_CORE, SleepStage.ASLEEP_DEEP, SleepStage.ASLEEP_REM -> true
        SleepStage.IN_BED, SleepStage.AWAKE -> false
    }

    private fun mean(samples: List<HRSample>): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (s in samples) sum += s.bpm.toDouble()
        return sum / samples.size.toDouble()
    }
}
