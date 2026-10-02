package io.github.opencircuit.ringkit

// Pure reminder-firing predicates. Three kinds: sedentary / move, ring-not-worn, and bedtime wind-down.
// All logic is side-effect-free — callers route survivors through the shared [NotificationGate] (quiet
// hours + backoff) as the matching [HealthNotification] reminder case, whose raw name is the same.
// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/ReminderEngine.swift (@ b1c2fdd), whole.
//
// "Activity" for the sedentary rule is a nonzero step delta from the ring. The caller supplies
// `lastActivityAt` (from its settings store). null → false (never a cold-launch nag).
//
// Shape notes: upstream's `TimeInterval`s are `Double` seconds (a NaN or infinite one behaves as
// upstream's); elapsed time is [secondsBetween], an exact `Instant` difference; upstream's
// `Calendar = .current` is a required `ZoneId`. Values compare their doubles by IEEE `==` (−0.0 equals
// 0.0, NaN never equals), as Swift's synthesized `Equatable` does, and change only through `copy`.

import java.time.Instant
import java.time.ZoneId

// MARK: - Reminder kinds

/**
 * Stable identifiers for each reminder type. The raw value is the notification request identifier AND
 * the de-dupe key in the shared notification ledger — it must stay stable across launches, and it equals
 * the raw name of the matching [HealthNotification] reminder case.
 */
enum class ReminderKind(val rawValue: String) {
    SEDENTARY("reminder.sedentary"),
    WEAR("reminder.wear"),
    BEDTIME("reminder.bedtime"),
    ;

    companion object {
        /** The case whose raw value is exactly [rawValue] (Swift's `init?(rawValue:)`), else null. */
        fun fromRawValue(rawValue: String): ReminderKind? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

// MARK: - Sedentary / move reminder

/**
 * Fire if the user has been physically inactive for longer than [interval] seconds and we're inside the
 * daily active window ([activeStartMinutes] inclusive to [activeEndMinutes] exclusive, minutes since
 * local midnight; the window does not wrap past midnight). "Activity" = a nonzero step delta from the
 * ring; `lastActivityAt` is null until the first step arrives, so the rule stays silent on a fresh
 * session or a day the ring isn't worn (never a false positive).
 *
 * A RING ON THE CHARGER CANNOT COUNT STEPS: `lastActivityAt` only advances when a step delta arrives, so
 * any stretch the ring is off the finger reads as a stretch of zero steps. Absence of measurement is not
 * evidence of inactivity, so the rule needs the ring to have been ON THE FINGER for the whole window it
 * is about to complain about. Three suppressions carry that — the live charging byte, the newest
 * off-finger observation, and a ring silent for the whole interval — and they never CAUSE a fire, so a
 * ring that reports none of them behaves exactly as before.
 */
class SedentaryReminder(
    /** Inactivity threshold before firing, in seconds. */
    val interval: Double = 50 * 60.0,
    /** Minutes-since-midnight window within which the reminder may fire. Default 08:00–21:00. */
    val activeStartMinutes: Int = 8 * 60,
    val activeEndMinutes: Int = 21 * 60,
) {
    /**
     * True when inactive for ≥ [interval], inside the active window by [now]'s wall clock in [zone], and the
     * ring was on the finger for that whole stretch (so the stillness was actually MEASURED).
     *
     * @param lastActivityAt newest moment a nonzero step delta arrived, or null before the first one.
     * @param isOnCharger the ring reports itself docked right now (descriptor `[2] == 0x04`).
     * @param lastOffFingerAt newest moment we observed the ring off the finger. Aged against [now] with a
     *   clamp at 0, so a stamp dated ahead of [now] (ring clock drift, a zone change) reads as "just now".
     * @param lastRingDataAt newest moment any frame arrived; a ring silent for the whole [interval] earns no
     *   nudge. null means "no information" and does NOT suppress.
     *
     * An instant `java.time` cannot place in [zone] (the first and last year of `Instant`'s range) is
     * outside the active window: no reminder is raised on a clock that cannot be read.
     */
    fun shouldFire(
        lastActivityAt: Instant?,
        now: Instant,
        isOnCharger: Boolean = false,
        lastOffFingerAt: Instant? = null,
        lastRingDataAt: Instant? = null,
        zone: ZoneId,
    ): Boolean {
        if (isOnCharger) return false
        val last = lastActivityAt ?: return false
        if (!(secondsBetween(last, now) >= interval)) return false
        if (lastOffFingerAt != null && swiftMax(0.0, secondsBetween(lastOffFingerAt, now)) < interval) return false
        if (lastRingDataAt != null && swiftMax(0.0, secondsBetween(lastRingDataAt, now)) >= interval) return false
        val m = minuteOfDay(now, zone) ?: return false
        return m >= activeStartMinutes && m < activeEndMinutes
    }

    fun copy(
        interval: Double = this.interval,
        activeStartMinutes: Int = this.activeStartMinutes,
        activeEndMinutes: Int = this.activeEndMinutes,
    ): SedentaryReminder = SedentaryReminder(interval, activeStartMinutes, activeEndMinutes)

    override fun equals(other: Any?): Boolean =
        other is SedentaryReminder && interval == other.interval &&
            activeStartMinutes == other.activeStartMinutes && activeEndMinutes == other.activeEndMinutes

    override fun hashCode(): Int = (ieeeHash(interval) * 31 + activeStartMinutes) * 31 + activeEndMinutes

    override fun toString(): String =
        "SedentaryReminder(interval=$interval, activeStartMinutes=$activeStartMinutes, activeEndMinutes=$activeEndMinutes)"
}

// MARK: - Wear reminder

/**
 * Fire when the ring appears to be OFF THE FINGER. Opt-in (default off in the app) — never fires before
 * the first connection (`everConnected = false`).
 *
 * SILENCE IS NOT EVIDENCE OF NOT-WEARING: the BLE link drops routinely while the ring keeps recording on
 * the finger, so "no frame for a while" measures the connection, not the ring. Silence is only ONE
 * necessary condition; a live link, drained epochs proving the ring was worn, the user's sleep window and
 * a last frame that said "on the charger" (for [chargerGrace]) each suppress it.
 */
class WearReminder(
    /** Gap without ring data that triggers the reminder, in seconds. */
    val noDataInterval: Double = 60 * 60.0,
    /**
     * How long a last-seen-on-charger reading keeps suppressing the reminder, in seconds. 4 h is
     * deliberately well past a full charge (~1.5 h from empty), while a ring parked off the finger all
     * afternoon still gets nagged about.
     */
    val chargerGrace: Double = 4 * 3600.0,
) {
    /**
     * True when the ring looks genuinely un-worn: nothing has arrived for ≥ [noDataInterval], we are not
     * connected, we are not inside the user's sleep window, and no drained epoch proves the ring was on the
     * finger during the silence.
     *
     * @param lastRingDataAt newest moment ANY frame arrived (wall clock), or null.
     * @param everConnected a ring has been paired at least once.
     * @param lastWornEvidenceAt newest DEVICE timestamp of an epoch recorded while worn, or null. Aged
     *   against [now] with a clamp at 0, so a stamp newer than [now] is "as fresh as possible".
     * @param isConnected a ring is connected right now.
     * @param inSleepWindow [now] falls inside the user's configured sleep schedule.
     * @param lastKnownOnCharger the NEWEST descriptor we hold reported the ring docked, as of
     *   [lastRingDataAt] (aged against it, clamped the same way).
     */
    fun shouldFire(
        lastRingDataAt: Instant?,
        now: Instant,
        everConnected: Boolean,
        lastWornEvidenceAt: Instant? = null,
        isConnected: Boolean = false,
        inSleepWindow: Boolean = false,
        lastKnownOnCharger: Boolean = false,
    ): Boolean {
        if (!everConnected || isConnected || inSleepWindow) return false
        // We know where the ring is: on the charger, as of the last frame. Not "not detected".
        if (lastKnownOnCharger && lastRingDataAt != null && swiftMax(0.0, secondsBetween(lastRingDataAt, now)) < chargerGrace) return false
        // Positive proof the ring was worn recently outranks the absence of frames.
        if (lastWornEvidenceAt != null && swiftMax(0.0, secondsBetween(lastWornEvidenceAt, now)) < noDataInterval) return false
        val last = lastRingDataAt ?: return true // ever connected but no data
        return secondsBetween(last, now) >= noDataInterval
    }

    fun copy(noDataInterval: Double = this.noDataInterval, chargerGrace: Double = this.chargerGrace): WearReminder =
        WearReminder(noDataInterval, chargerGrace)

    override fun equals(other: Any?): Boolean =
        other is WearReminder && noDataInterval == other.noDataInterval && chargerGrace == other.chargerGrace

    override fun hashCode(): Int = ieeeHash(noDataInterval) * 31 + ieeeHash(chargerGrace)

    override fun toString(): String = "WearReminder(noDataInterval=$noDataInterval, chargerGrace=$chargerGrace)"
}

// MARK: - Bedtime reminder

/**
 * Fire inside the window [bed − [minutesBefore], bed) to give the user a heads-up before their configured
 * bedtime. The window is in minutes since local midnight and wraps past midnight. Returns false when
 * bed == wake (schedule not configured), matching [SleepWindow]'s convention.
 */
data class BedtimeReminder(
    /** How many minutes before the bedtime the window opens. */
    val minutesBefore: Int = 30,
) {
    /**
     * True when [now]'s time of day in [zone] falls inside [bed − minutesBefore, bed). Stored minutes are
     * taken as they are: the window start is computed in 64 bits, as Swift's `Int` (no pair of Kotlin
     * `Int`s wraps), with Swift's truncating remainder, so minutes outside 0…1439 compare as plain numbers.
     * An instant `java.time` cannot place in [zone] is outside every window.
     */
    fun shouldFire(now: Instant, bedMinutes: Int, wakeMinutes: Int, zone: ZoneId): Boolean {
        if (bedMinutes == wakeMinutes) return false // not configured
        val windowStart = (bedMinutes.toLong() - minutesBefore.toLong() + 1_440L) % 1_440L
        val windowEnd = bedMinutes.toLong()
        val m = minuteOfDay(now, zone) ?: return false
        return minuteInWindow(m.toLong(), windowStart, windowEnd)
    }

    private fun minuteInWindow(m: Long, start: Long, end: Long): Boolean {
        if (start == end) return false
        if (start < end) return m >= start && m < end
        return m >= start || m < end // wraps past midnight
    }
}

/** [t]'s wall-clock minutes since midnight in [zone] (seconds ignored), or null when `java.time` cannot place it. */
private fun minuteOfDay(t: Instant, zone: ZoneId): Int? = zonedOrNull { t.atZone(zone).let { it.hour * 60 + it.minute } }
