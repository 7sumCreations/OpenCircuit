package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/SleepNightKey.swift (@ b1c2fdd): the
// calendar day a night's summary is filed under — the single upsert key for a stored night and for
// every night-scoped overlay derived from it.
//
// WHY THIS EXISTS. The key used to be the start of day of the in-bed START, which ALIASES: a bedtime
// after midnight keys to the WAKE day, a bedtime before midnight to the day BEFORE the wake day, so two
// consecutive nights collapse onto ONE key whenever bedtime crosses midnight in opposite directions —
// an ordinary sleep pattern. Upstream proved it on a device (2026-08-08): the first night's row carried
// the wearer's edit, the store's preserve-the-edit early return dropped the second night entirely, and
// every retry hit the same guard.
//
// THE RULE: key on the day the sleep block ENDS on. A night is named for the morning you wake up into.
//
// Every function takes the zone the day is judged in; nothing reads the machine's zone. An instant
// `java.time` cannot place in the zone (the last year of `Instant`'s range) has no key.

import java.time.Instant
import java.time.ZoneId

object SleepNightKey {

    /**
     * The night key for an in-bed window: the start of the day the window ENDS on.
     *
     * [inBedEnd] is the anchor because it is the only edge that is stable under a bedtime that
     * straddles midnight. Falls back to [inBedStart] when the window is empty or inverted
     * (`inBedEnd <= inBedStart`) — a degenerate row still needs SOME deterministic key, and start-
     * anchoring it reproduces the historical behaviour for exactly the rows with no end to anchor to.
     * Null only when the anchor cannot be placed in [zone].
     */
    fun night(inBedStart: Instant, inBedEnd: Instant, zone: ZoneId): Instant? {
        val anchor = if (inBedEnd > inBedStart) inBedEnd else inBedStart
        return CalendarDay.startOfDay(anchor, zone)
    }

    /**
     * The night key for a staged hypnogram — the same rule applied to the segments' envelope (earliest
     * start, latest end, whichever segments they belong to). Null when there are no segments.
     */
    fun night(segments: List<SleepSegment>, zone: ZoneId): Instant? {
        val start = segments.minOfOrNull { it.start } ?: return null
        val end = segments.maxOfOrNull { it.end } ?: return null
        return night(inBedStart = start, inBedEnd = end, zone = zone)
    }

    /**
     * Hour of the key day before which a block's END is read as "woke up into this morning".
     *
     * A SEMANTIC boundary, not a measured threshold: the key names the day the sleeper woke up, so noon
     * splits "ended this morning" (owns the key) from "ended this evening" (a bout still in progress,
     * which will own TOMORROW's key once it finishes). Nothing physiological is claimed.
     */
    const val WAKE_WINDOW_END_HOUR: Int = 12

    /**
     * Whether a block that ends at [inBedEnd] is the night the key it lands on actually NAMES.
     *
     * Needed because the overnight test accepts any block whose midpoint falls in [21:00, 09:00), so an
     * evening drain completing before midnight stages a pre-midnight-only block that keys to TODAY — the
     * same key as the night that genuinely ended this morning. When those two contend for one key, the
     * one that ended in the morning is the night. An end that cannot be placed in [zone] never owns a key.
     */
    fun endsInWakeWindow(inBedEnd: Instant, zone: ZoneId): Boolean {
        val hour = CalendarDay.hour(inBedEnd, zone) ?: return false
        return hour < WAKE_WINDOW_END_HOUR
    }

    /**
     * The key a stored row SHOULD have, or null when it is already correct — or when the row gives no
     * evidence to judge it by. Drives the one-shot re-key migration; null for an already-correct row is
     * what makes the migration idempotent.
     *
     * THE DEGENERATE GUARD IS LOAD-BEARING, NOT DEFENSIVE. Stored in-bed edges default to the distant
     * past ([SleepEdit.DISTANT_PAST]) on rows written before those columns existed. Without the guard
     * such a row falls into [night]'s start-anchored fallback — the distant past's start of day, year 0
     * — and the migration would move the row and its overlays there: outside every date-ranged query,
     * behind a one-way latch with no reverse mapping. A row we cannot judge keeps whatever key it has;
     * so does a row whose window cannot be placed in [zone]. A stored key that cannot be placed is not
     * a day at all, so it is never the correct one: such a row is moved, as upstream moves it.
     */
    fun rekeyed(storedNight: Instant, inBedStart: Instant, inBedEnd: Instant, zone: ZoneId): Instant? {
        if (!(inBedEnd > inBedStart && inBedStart > SleepEdit.DISTANT_PAST)) return null
        val correct = night(inBedStart = inBedStart, inBedEnd = inBedEnd, zone = zone) ?: return null
        return if (correct == CalendarDay.startOfDay(storedNight, zone)) null else correct
    }
}
