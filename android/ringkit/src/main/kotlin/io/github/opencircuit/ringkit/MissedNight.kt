package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/MissedNight.swift (@ b1c2fdd): pure "is last
// night actually last night?" date math.
//
// Two surfaces used to answer this independently and could disagree: the Goals sleep ring credited the
// newest stored night as "today" with no recency guard (a days-old night filled the ring), and the
// Sleep card's missed-night banner fired every morning before the first sync and vanished every
// evening. Both are the SAME recency question, so it lives here and both call it.
//
// Invariant tying the two together: whenever the banner reads MISSING, the credit is withheld.
// MISSING requires the shown night did NOT end today, and the credit is granted ONLY when it did.
//
// Every function takes the zone the day is judged in and the current instant; nothing reads the
// machine's zone or clock (upstream's `now` defaults to the clock). An instant the calendar cannot
// place gives no wake, no claim and no credit.

import java.time.Instant
import java.time.ZoneId

object MissedNight {

    /** What the UI should say about the currently shown night's recency. */
    enum class Status {
        /** Nothing to flag: the shown night ended today, or it's too early / legacy to judge. */
        OK,

        /**
         * Past this morning's wake with no night ending today, but no sync has completed since wake —
         * last night may simply not have drained off the ring yet. A soft, neutral note.
         */
        NOT_SYNCED_YET,

        /** Past this morning's wake, a sync DID complete after wake, and still no night ending today. */
        MISSING,
    }

    /**
     * This morning's wake instant — the wake of the sleep window we are currently in or have most
     * recently passed, held FIXED at today's wake for the whole waking day.
     *
     * [SleepWindow.interval] returns the wake NEAREST [now], which after about wake + 12 h flips to
     * tomorrow's wake; that made the banner disappear every evening. So: at/after the nearest window's
     * bedtime, that window's wake is the reference (ahead only mid-sleep, which the caller's
     * `now > wake` gate handles); before it, the nearest window is a future night, so step back one
     * CALENDAR day from that window's wake.
     *
     * Deliberately not upstream's step: upstream steps `now` back a fixed 24 h and takes the nearest
     * window again. On a spring-forward evening that lands on the day before, so for up to 30 minutes
     * it answered yesterday's wake, and a sync made yesterday evening then read as "after this
     * morning's wake" (the banner could say MISSING where "not synced yet" was meant). Every other
     * instant gives upstream's answer, fall-back evenings included (stepping `now` itself back a
     * calendar day would there land 25 h back and skip to yesterday's wake). Null for a degenerate
     * zero-length schedule (`bed == wake`) or an instant the calendar cannot place.
     */
    fun morningWake(now: Instant, bedMinutes: Int, wakeMinutes: Int, zone: ZoneId): Instant? {
        val near = SleepWindow.interval(bedMinutes = bedMinutes, wakeMinutes = wakeMinutes, nightEndingNear = now, zone = zone) ?: return null
        if (now >= near.start) return near.end
        return SleepWindow.wakeOneDayBefore(wake = near.end, wakeMinutes = wakeMinutes, zone = zone)
    }

    /**
     * The night's wake reference: its real wake time ([inBedEnd]) when known, else the start-of-day
     * [nightKey] — so a legacy rollup with no clock time can't be credited on a later day.
     */
    fun nightWakeReference(inBedEnd: Instant?, nightKey: Instant): Instant = inBedEnd ?: nightKey

    /**
     * Whether the shown night counts as "last night" for crediting — its wake falls on [now]'s day in
     * [zone]. The SAME "ended today" test the banner uses, so the two agree.
     */
    fun endedToday(inBedEnd: Instant?, nightKey: Instant, now: Instant, zone: ZoneId): Boolean =
        CalendarDay.isSameDay(nightWakeReference(inBedEnd, nightKey), now, zone)

    /**
     * Classify the shown night's recency for the Sleep card.
     *
     * [nightWake] is the shown night's actual wake; [wakeKnown] false (or a null wake) for a legacy
     * rollup with no clock time, which is never judged. [lastSyncAt] is the completion time of the most
     * recent successful sync (not a sample timestamp — device timestamps can be an hour stale), null
     * when none is recorded.
     */
    fun status(
        now: Instant,
        bedMinutes: Int,
        wakeMinutes: Int,
        nightWake: Instant?,
        wakeKnown: Boolean,
        lastSyncAt: Instant?,
        zone: ZoneId,
    ): Status {
        // Legacy rollup with no clock time — can't reason about it.
        if (!wakeKnown || nightWake == null) return Status.OK
        val wake = morningWake(now = now, bedMinutes = bedMinutes, wakeMinutes = wakeMinutes, zone = zone) ?: return Status.OK
        // Not yet past this morning's wake (mid-sleep / early) — never claim a miss.
        if (!(now > wake)) return Status.OK
        // The shown night already ended today → it IS last night.
        if (CalendarDay.isSameDay(nightWake, now, zone)) return Status.OK
        // A genuine miss only once a sync has completed AFTER this morning's wake.
        if (lastSyncAt != null && lastSyncAt > wake) return Status.MISSING
        return Status.NOT_SYNCED_YET
    }

    /** The honest "no sleep recorded" banner should show iff this is true. */
    fun isMissing(
        now: Instant,
        bedMinutes: Int,
        wakeMinutes: Int,
        nightWake: Instant?,
        wakeKnown: Boolean,
        lastSyncAt: Instant?,
        zone: ZoneId,
    ): Boolean = status(now, bedMinutes, wakeMinutes, nightWake, wakeKnown, lastSyncAt, zone) == Status.MISSING
}
