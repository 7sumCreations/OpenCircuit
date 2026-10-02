package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/ActiveEnergyWindow.swift (@ b1c2fdd),
// whole: where in the day an active-energy DELTA gets stamped in the health store.
//
// The store SUMS active energy, so the day's total lands right whatever window a delta is given; the
// window decides where the store's activity chart places it. Each clamp below guards a specific way a
// naive `[lastFlush, now]` window goes wrong — several of them silently and PERMANENTLY corrupting
// past days, since the flush never backfills.
//
// Port notes:
//  • Upstream's arithmetic and clamps, in its order. Instants are exact to the nanosecond where a
//    Swift `Date` carries a double; the two agree wherever the inputs sit on a millisecond grid.
//  • A Swift `Date` has no end: a window that would widen past the start of `Instant`'s range
//    saturates there instead of throwing (`addingSeconds`; PORTING.md D-81).

import java.time.Instant

/** Chooses the time window one active-energy delta is written over, or none. */
object ActiveEnergyWindow {

    /**
     * Ceiling on how fast a window may imply energy was burned, kcal per minute. A delta too large for
     * its elapsed window widens the window backwards instead of spiking: data arrives in bulk, and a
     * flush seconds after the previous one could otherwise carry a whole afternoon. 20 kcal/min is
     * around an elite athlete's sustained peak — a sanity bound on placement, not a physiological model.
     */
    const val MAX_PLAUSIBLE_KCAL_PER_MINUTE: Double = 20.0

    /**
     * Widen [start] backwards (never past [dayStart]) until the window is wide enough that [kcal] over
     * it does not exceed [MAX_PLAUSIBLE_KCAL_PER_MINUTE]. Returns [start] unchanged when the window is
     * already plausible or [kcal] is not positive (NaN included).
     */
    internal fun widenedStart(start: Instant, end: Instant, kcal: Double, dayStart: Instant): Instant {
        if (!(kcal > 0)) return start
        val needed = (kcal / MAX_PLAUSIBLE_KCAL_PER_MINUTE) * 60.0 // seconds
        if (!(secondsBetween(start, end) < needed)) return start
        val earliest = addingSeconds(end, -needed)!! // needed is positive or +∞ here, never NaN
        return if (earliest >= dayStart) earliest else dayStart // Swift's max(dayStart, earliest)
    }

    /**
     * The time window one active-energy delta should be stamped over, or null when there is no legal
     * window and the caller must NOT write.
     *
     * The window always ends at [now] and starts at the LATEST applicable floor:
     *  - [dayStart] — the load-bearing clamp: an unset stored date reads back as the epoch, and a
     *    multi-day quiet spell must not stamp today's delta across days already final.
     *  - [anchor] — the end of the last window written successfully TODAY; authoritative once set, so
     *    consecutive deltas tile without gaps or overlaps. A future anchor (a clock stepped forward) is
     *    discarded rather than honoured, so the writer self-heals instead of wedging.
     *  - [notBefore] — a first-flush floor only (in practice the end of the sleep window the estimate
     *    already excluded); never re-applied over a written anchor.
     *
     * Null when the resulting window is empty or inverted (start not before [now]): a skipped window is
     * self-healing (the kcal is still owed), where a rejected save would stall every later flush.
     */
    fun resolve(anchor: Instant?, notBefore: Instant?, now: Instant, dayStart: Instant, kcal: Double = 0.0): DateInterval? {
        if (!(dayStart < now)) return null

        if (anchor != null && anchor > dayStart && anchor <= now) {
            val start = widenedStart(anchor, now, kcal, dayStart)
            if (!(start < now)) return null
            return DateInterval(start, now)
        }

        // First write of the day: floor at the earliest moment this energy could have accrued.
        var start = dayStart
        if (notBefore != null && notBefore > start && notBefore < now) start = notBefore
        start = widenedStart(start, now, kcal, dayStart)
        if (!(start < now)) return null
        return DateInterval(start, now)
    }
}
