package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/SleepWindow.swift (@ b1c2fdd): pure,
// platform-agnostic sleep-window date math. Every function that upstream gives a `Calendar` takes an
// explicit `ZoneId` here, with NO default — nothing reads the machine's zone. Day arithmetic follows
// Foundation's: "midnight" is the zone's start of day, a day is added on the wall clock, and a
// minutes-of-day offset is then added in absolute seconds (so a 06:30 wake on a spring-forward day
// lands at 07:30 wall time, as upstream).
//
// An instant `java.time` cannot place in a zone (the last year of `Instant`'s range) yields no
// window rather than an exception; upstream's calendar returned a wrapped, wrong year there.

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Builds a concrete bedtime→wake [DateInterval] from a time-of-day schedule (minutes since
 * midnight), handling a window that crosses midnight (e.g. bed 22:30 → wake 06:30).
 */
object SleepWindow {

    /** Minutes in a day. */
    const val MINUTES_PER_DAY: Int = 1440

    /**
     * The sleep window whose WAKE time falls nearest to [nightEndingNear].
     *
     * [bedMinutes] / [wakeMinutes] are minutes since local midnight (22:30 → 1350) and may be any
     * value; they are reduced modulo a day. When the schedule crosses midnight the bedtime lands on
     * the previous day. Null for a degenerate zero-length schedule (`bed == wake`).
     */
    fun interval(bedMinutes: Int, wakeMinutes: Int, nightEndingNear: Instant, zone: ZoneId): DateInterval? =
        interval(bedMinutes.toLong(), wakeMinutes.toLong(), nightEndingNear, zone)

    /** [interval] over 64-bit minutes, as upstream's `Int` (the habitual window's margins may be large). */
    private fun interval(bedMinutes: Long, wakeMinutes: Long, nightEndingNear: Instant, zone: ZoneId): DateInterval? {
        val day = MINUTES_PER_DAY.toLong()
        val bed = minuteOfDay(bedMinutes)
        val wake = minuteOfDay(wakeMinutes)

        // Sleep duration, wrapping across midnight. bed == wake → 0 (degenerate; no window).
        val duration = ((wake - bed) + day) % day
        if (duration <= 0) return null

        // Candidate wake instants on the day before / of / after the reference; pick the nearest
        // (the earliest of equally near ones). A candidate java.time cannot represent is skipped.
        val midnight = startOfDay(nightEndingNear, zone) ?: return null
        val candidates = (-1L..1L).mapNotNull { offset ->
            guarded { midnight.plusDays(offset).toInstant().plusSeconds(wake * 60) }
        }
        val wakeDate = candidates.minByOrNull { Duration.between(nightEndingNear, it).abs() } ?: return null
        val bedDate = guarded { wakeDate.minusSeconds(duration * 60) } ?: return null
        return DateInterval(bedDate, wakeDate)
    }

    /**
     * The scheduled wake ONE CALENDAR DAY before [wake], where [wake] is a wake [interval] returned for
     * the same [wakeMinutes] in [zone]. It is placed by the same rule as [interval]'s: the day's start
     * plus the wake's minutes in absolute seconds. [wake]'s own day is recovered by taking those
     * minutes back off, so a wake pushed past midnight by a lost hour still counts as its own day's.
     * Unlike a fixed 24 h step, a day that gained or lost time never makes this skip a wake or repeat
     * one. Null when [zone] cannot place the instant.
     */
    internal fun wakeOneDayBefore(wake: Instant, wakeMinutes: Int, zone: ZoneId): Instant? {
        val offsetSeconds = minuteOfDay(wakeMinutes.toLong()) * 60
        return guarded {
            val dayBefore = wake.minusSeconds(offsetSeconds).atZone(zone).toLocalDate().minusDays(1)
            dayBefore.atStartOfDay(zone).toInstant().plusSeconds(offsetSeconds)
        }
    }

    /** Minutes reduced into one day, `[0, 1440)`, as upstream's `((m % 1440) + 1440) % 1440`. */
    private fun minuteOfDay(minutes: Long): Long {
        val day = MINUTES_PER_DAY.toLong()
        return ((minutes % day) + day) % day
    }

    /**
     * Minutes since midnight from an hour/minute pair: `(hour·60 + minute + 1440) % 1440`. Like
     * upstream it is not clamped — an hour below -24 gives a negative remainder — and it is computed
     * in 64 bits, as upstream's `Int`, so a hostile hour never wraps.
     */
    fun minutes(hour: Int, minute: Int): Int =
        ((hour.toLong() * 60 + minute + MINUTES_PER_DAY) % MINUTES_PER_DAY).toInt()

    /**
     * A HABITUAL sleep window learned from recent nights' actual onset and wake times — for gating
     * overnight capture (skin temperature rides the live descriptor and is not in the drainable
     * history, so it can only be captured in real time, inside a window).
     *
     * Each night contributes its onset and wake TIME OF DAY in [zone]. A robust MEDIAN of each is
     * taken (onsets before noon are unwrapped onto the evening scale, so 23:50 and 00:30 average to
     * ~00:10, not midday), widened by [bedMargin] / [wakeMargin] (whole minutes, truncated toward
     * zero), then built for the night ending near [nightEndingNear] via [interval]. Null when fewer
     * than [minNights] nights exist — the caller falls back to a fixed default.
     */
    fun habitualInterval(
        onsets: List<Instant>,
        wakes: List<Instant>,
        nightEndingNear: Instant,
        bedMargin: Duration = Duration.ofHours(1),
        wakeMargin: Duration = Duration.ofMinutes(90),
        minNights: Int = 3,
        zone: ZoneId,
    ): DateInterval? {
        if (onsets.size < minNights || wakes.size < minNights) return null
        fun tod(t: Instant): Int? = guarded { t.atZone(zone).let { it.hour * 60 + it.minute } }
        val onsetTods = onsets.map { tod(it) ?: return null }
        val wakeTods = wakes.map { tod(it) ?: return null }
        // Onsets straddle midnight, so times before noon unwrap onto the evening scale; wakes never
        // wrap (a pivot of 0 unwraps nothing).
        val onsetMin = medianMinutes(onsetTods, unwrapBelow = 12 * MINUTES_PER_DAY / 24)
        val wakeMin = medianMinutes(wakeTods, unwrapBelow = 0)
        return interval(
            bedMinutes = onsetMin - bedMargin.wholeSecondsTowardZero() / 60,
            wakeMinutes = wakeMin + wakeMargin.wholeSecondsTowardZero() / 60,
            nightEndingNear = nightEndingNear,
            zone = zone,
        )
    }

    /**
     * Median of minutes-since-midnight values, treating any value below [unwrapBelow] as belonging
     * to the NEXT day (a full day added before sorting); the result is wrapped back into
     * `[0, 1440)`. With `unwrapBelow == 0` nothing unwraps. An empty list gives 0.
     */
    internal fun medianMinutes(values: List<Int>, unwrapBelow: Int): Int {
        if (values.isEmpty()) return 0
        val unwrapped = values.map { if (it < unwrapBelow) it + MINUTES_PER_DAY else it }.sorted()
        val mid = unwrapped[unwrapped.size / 2]
        return ((mid % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
    }

    /**
     * Whether a detected sleep block looks like OVERNIGHT sleep rather than a daytime nap or a long
     * sedentary block: its MIDPOINT falls at or after 21:00 or before 09:00 local time. Decides
     * acceptance only — an accepted block is kept whole. A block whose end precedes its start is
     * judged as zero-length at [start]. An instant that cannot be placed in [zone] is not overnight.
     */
    fun isOvernightBlock(start: Instant, end: Instant, zone: ZoneId): Boolean = overnightVerdict(start, end, zone) ?: false

    /**
     * [isOvernightBlock], except that a block whose midpoint cannot be placed in [zone] gives null
     * instead of `false`, so a caller that must fail closed there (the manual nap edit) can.
     */
    internal fun overnightVerdict(start: Instant, end: Instant, zone: ZoneId): Boolean? {
        val safeEnd = maxOf(end, start)
        val mid = start.plus(Duration.between(start, safeEnd).dividedBy(2))
        val local = guarded { mid.atZone(zone) } ?: return null
        val minutes = local.hour * 60 + local.minute
        return minutes < 9 * 60 || minutes >= 21 * 60 // before 09:00 or at/after 21:00
    }

    /**
     * The span PRESUMED for a night whose onset was not captured: 7 h, long enough to drag a
     * truncated tail's midpoint back into the night, short enough that a daytime block with an
     * unobserved onset still has a daytime presumed midpoint. It doubles as the size of the hole
     * `BulkSleep.onsetIsUnobserved` demands before it will say the onset is missing.
     */
    val PRESUMED_TRUNCATED_NIGHT_SPAN: Duration = Duration.ofHours(7)

    /**
     * [isOvernightBlock], corrected for a night whose ONSET WAS NEVER RECORDED: when
     * [onsetIsUnobserved] it ALSO judges a presumed start of `end - PRESUMED_TRUNCATED_NIGHT_SPAN`
     * and accepts if EITHER midpoint is overnight. Never less accepting than the two-argument form,
     * and identical to it when [onsetIsUnobserved] is false.
     *
     * ⚠️ Not safe on its own: on date math alone it accepts any block whose wake falls in
     * `[00:30, 12:30)`. Only `BulkSleep.onsetIsUnobserved` (the presumed data must really be
     * missing) and the night selector's two-pass filter (the correction runs only when nothing else
     * qualified) make it safe. Nothing else should feed [onsetIsUnobserved].
     */
    fun isOvernightBlock(start: Instant, end: Instant, onsetIsUnobserved: Boolean, zone: ZoneId): Boolean {
        val asObserved = isOvernightBlock(start, end, zone)
        if (!onsetIsUnobserved) return asObserved
        val safeEnd = maxOf(end, start)
        // `min` (never `max`): the presumption may only move the start EARLIER.
        val presumed = guarded { safeEnd.minus(PRESUMED_TRUNCATED_NIGHT_SPAN) } ?: return asObserved
        val effectiveStart = minOf(start, presumed)
        return asObserved || isOvernightBlock(effectiveStart, safeEnd, zone)
    }

    private fun startOfDay(t: Instant, zone: ZoneId): ZonedDateTime? = guarded { t.atZone(zone).toLocalDate().atStartOfDay(zone) }

    /** Runs [block]; an instant `java.time` cannot represent yields null instead of an exception (the shared rule). */
    private inline fun <T> guarded(block: () -> T): T? = zonedOrNull(block)
}
