package io.github.opencircuit.ringkit

// The night the AUTOMATIC history drains stay away from, and when its quiet ends. Port of upstream
// `RingSession.refreshNightWindowIfNeeded` / `learnedNightWindow` / `isInSleepWindow`
// (ios/OpenCircuit/BLE/RingSession.swift:1497-1619 @ b1c2fdd) as one pure value: the zone and the
// clock are passed in, nothing reads the machine's. Why the quiet exists: each drain's open walks the
// ring's single resume pointer, and cadenced overnight drains left the ring with no night to hand
// off by morning (HistoryDrainCadence's header). A manual sync never asks this.
//
// Departures from upstream (PORTING.md D-271):
//  - No explicit schedule (a later epic): the window is learned from ≥ 3 of the last 14 stored nights
//    keyed within 14 days (upstream: 21 nights, 21 days), else the generous 21:30→10:00 fallback.
//  - The fallback ends at 10:00 itself. Upstream trims it like a learned window (quiet past 08:30 until
//    a step-confirmed wake, at most until 14:30); a fixed default has no learned wake to protect.
//  - The night `now` belongs to: the window whose wake is nearest `now`, or — once `now` is past that
//    window's quiet — the next night's. `SleepWindow` alone keeps last night's window until 12 h past
//    its wake, so upstream reads 21:30–22:00 as outside the fallback window.
//  - The step-confirmed wake is any descriptor whose quarter-hour step bucket is above 0 at or after
//    the earliest wake (upstream: a same-day step delta over a threshold, RingSession.swift:4969-4973).

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * One night's window ([window], half-open) and whether it was [learned] from stored nights or is
 * the fixed fallback.
 *
 * A learned window is the generous one ([SleepWindow.habitualInterval]: onset − 1 h → wake + 90 min).
 * Its quiet runs from [window]'s start to the [earliestWake] (the learned wake itself, `end − 90 min`),
 * and past it until a step-confirmed wake or the [quietCeiling] six hours later — the learned wake is
 * a median, and ending the quiet there would cut a lie-in's tail (upstream's 2026-07-12 truncation).
 * The fallback window is quiet exactly while it lasts.
 */
data class NightWindow(val window: DateInterval, val learned: Boolean) {

    /** The earliest instant the quiet may end: a learned window's wake, the fallback's end. */
    val earliestWake: Instant get() = if (learned) window.end.minus(WAKE_MARGIN_TRIM) else window.end

    /** The instant the quiet ends with no wake seen: six hours past a learned wake, the fallback's end. */
    val quietCeiling: Instant get() = if (learned) earliestWake.plus(MAX_QUIET_PAST_LEARNED_WAKE) else window.end

    /**
     * Whether an automatic drain must wait at [now]. [wakeConfirmedAt]: when a descriptor last
     * confirmed the wearer was up ([confirmsWake]); only a confirmation inside this night, and not
     * later than [now], ends a learned window's quiet early.
     */
    fun isQuiet(now: Instant, wakeConfirmedAt: Instant?): Boolean {
        if (!learned) return now in window
        // Trimmed away entirely: nothing of the night is left to protect (upstream's guard).
        if (!earliestWake.isAfter(window.start)) return false
        if (now.isBefore(window.start)) return false
        if (!now.isBefore(quietCeiling)) return false
        if (now.isBefore(earliestWake)) return true
        val confirmed = wakeConfirmedAt ?: return true
        return confirmed.isBefore(window.start) || confirmed.isAfter(now)
    }

    /**
     * Whether a descriptor seen [at] with [stepBucket] steps in the ring's current quarter-hour
     * confirms the wearer is up: a learned window only, steps above 0, at or after the
     * [earliestWake] (a walk before it is still the night).
     */
    fun confirmsWake(at: Instant, stepBucket: Int): Boolean =
        learned && stepBucket > 0 && !at.isBefore(earliestWake) && !at.isBefore(window.start)

    /** What a stored night teaches: its key ([night]) and when the wearer fell asleep and woke. */
    data class StoredNight(val night: Instant, val onset: Instant, val wake: Instant)

    companion object {
        /** The fallback window's bedtime, 21:30 (upstream `tempFallbackBedMinutes`). */
        const val FALLBACK_BED_MINUTES: Int = 21 * 60 + 30

        /** The fallback window's wake, 10:00 (upstream `tempFallbackWakeMinutes`). */
        const val FALLBACK_WAKE_MINUTES: Int = 10 * 60

        /** A learned window's wake margin, taken back off to find the learned wake (upstream `drainWakeMarginTrim`). */
        val WAKE_MARGIN_TRIM: Duration = Duration.ofMinutes(90)

        /** How long past the learned wake the quiet may last with no wake seen (upstream `maxQuietPastLearnedWake`). */
        val MAX_QUIET_PAST_LEARNED_WAKE: Duration = Duration.ofHours(6)

        /** Usable nights needed to learn a window. */
        const val MIN_NIGHTS: Int = 3

        /** The latest stored nights a window is learned from. */
        const val LEARNING_NIGHTS: Int = 14

        /** How far back a stored night's key may lie and still teach. */
        private val LEARNING_SPAN: Duration = Duration.ofDays(14)

        /**
         * The night [now] belongs to, in [zone]: learned from [nights] (any order; the latest
         * [LEARNING_NIGHTS] keyed within 14 days of [now] — a night with no onset, or waking before
         * it fell asleep, teaches nothing) when ≥ [MIN_NIGHTS] teach, else the fallback. It is the
         * window whose wake is nearest [now], or the next night's once [now] is at or past that
         * window's [quietCeiling]. Null when [zone] cannot place [now].
         */
        fun resolve(nights: List<StoredNight>, now: Instant, zone: ZoneId): NightWindow? {
            val cutoff = zonedOrNull { now.minus(LEARNING_SPAN) } ?: return null
            val recent = nights.sortedByDescending { it.night }.take(LEARNING_NIGHTS).filter { !it.night.isBefore(cutoff) }
            val onsets = recent.map { it.onset }.filter { it.isAfter(SleepEdit.DISTANT_PAST) }
            val wakes = recent.filter { it.wake.isAfter(it.onset) }.map { it.wake }

            fun endingNear(reference: Instant): NightWindow? {
                SleepWindow.habitualInterval(onsets, wakes, nightEndingNear = reference, minNights = MIN_NIGHTS, zone = zone)
                    ?.let { return NightWindow(it, learned = true) }
                return SleepWindow.interval(FALLBACK_BED_MINUTES, FALLBACK_WAKE_MINUTES, reference, zone)?.let { NightWindow(it, learned = false) }
            }

            val current = endingNear(now) ?: return null
            if (now.isBefore(current.quietCeiling)) return current
            return zonedOrNull { now.plus(Duration.ofDays(1)) }?.let(::endingNear) ?: current
        }
    }
}
