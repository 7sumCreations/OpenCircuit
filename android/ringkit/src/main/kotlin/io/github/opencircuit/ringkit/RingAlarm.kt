package io.github.opencircuit.ringkit

// Vibrating wake-up alarm driven by the Gen 3 motor (`RingVibration`) — the scheduling rules. Port of
// upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/RingAlarm.swift (@ b1c2fdd), whole.
//
// ══ READ THIS BEFORE TRUSTING THE ALARM ══ (upstream's warning, which holds on Android too)
//
// The phone cannot promise to run this code at an exact wall-clock instant: the app gets runtime when
// the ring pushes something over the connected link, so the design is "fire on the first breath of
// runtime at or after the alarm time", and the honest accuracy claim is bounded by frame cadence, not
// by a clock we control. Two consequences are owned rather than hidden:
//
//  1. The buzz can be LATE. `grace` bounds how late is still useful — past it the alarm is recorded
//     `Missed` and deliberately NOT delivered, because a wake-up buzz 40 minutes after the user is
//     already up is worse than none. The miss is surfaced, never swallowed.
//  2. The buzz can be MISSED ENTIRELY (ring on the charger, link down, app stopped). This is why
//     `RingAlarm.backupNotification` exists and defaults ON: an OS-scheduled notification fires
//     whatever our process is doing. The ring buzz is the nice version; the notification is the one
//     that is actually guaranteed to wake someone.
//
// A native firmware alarm — the ring keeping its own clock — would sidestep all of this. There is no
// evidence one exists: `0x0b` is the only motor opcode ever seen on the wire. 🔴 Unknown, not ruled out.
//
// Shape notes. The four rules upstream defaulted to the device's calendar take a required `ZoneId`; the
// clock (`now`) is always the caller's. A wall-clock time on a clock-change day is resolved by
// `CalendarDay.atWallClock`, which reproduces Foundation's measured rule (a skipped time is the end of
// the gap, a repeated one its first instant) — except a gap that is not a whole hour, where Foundation
// moves the alarm to the next day and the port keeps it that day. Days are walked by calendar date in the
// zone, never by fixed 86 400 s steps. An instant `java.time` cannot place in the zone (`Instant`'s
// first and last year) has no occurrence: the decision is idle. Elapsed times (`lateBy`, the warm-up
// window) are measured exactly between instants. Values compare their doubles by IEEE `==`, as Swift's
// synthesized `Equatable` does. The alarm's stored form is the storage layer's.

import java.time.Instant
import java.time.ZoneId
import java.util.Collections
import java.util.TreeSet

// MARK: - The alarm

/**
 * One wake-up alarm, as the user set it. Immutable: change it with [copy].
 *
 * @property hour local wall-clock hour, 0–23 (anything else never occurs).
 * @property minute local wall-clock minute, 0–59 (anything else never occurs).
 * @property weekdays Foundation weekday numbers (1 = Sunday … 7 = Saturday) the alarm repeats on, read-only.
 *   EMPTY MEANS EVERY DAY — see [repeats]. There is no one-shot mode: an alarm you have to re-arm each night
 *   is a worse product than one you switch off. A number outside 1…7 is kept as given and matches no day.
 * @property pattern the motor pattern each burst plays.
 * @property burstCount how many times to buzz, spaced [burstSpacing] apart. One buzz is easy to sleep through.
 * @property burstSpacing seconds between bursts.
 * @property backupNotification also schedule an OS-level notification at the same time. Defaults ON: it is
 *   the only part of this feature the platform actually guarantees (see the file header).
 */
class RingAlarm(
    val isEnabled: Boolean = false,
    val hour: Int = 7,
    val minute: Int = 0,
    weekdays: Set<Int> = emptySet(),
    val pattern: VibrationPattern = VibrationPattern.NOTIFICATION,
    val burstCount: Int = 3,
    val burstSpacing: Double = 4.0,
    val backupNotification: Boolean = true,
) {
    /** The weekdays, copied in and read-only, in ascending order. */
    val weekdays: Set<Int> = Collections.unmodifiableSet(TreeSet(weekdays))

    /** Empty [weekdays] = every day. Any other set is taken literally. */
    fun repeats(weekday: Int): Boolean = weekdays.isEmpty() || weekday in weekdays

    /** Bursts, clamped to something a motor and a sleeping human can both survive: 1…10. */
    val clampedBurstCount: Int get() = minOf(maxOf(burstCount, 1), 10)

    /** Seconds between bursts, clamped to 2…30 (a NaN setting stays NaN, as Swift's `min` / `max` pass it). */
    val clampedBurstSpacing: Double get() = swiftMin(swiftMax(burstSpacing, 2.0), 30.0)

    fun copy(
        isEnabled: Boolean = this.isEnabled,
        hour: Int = this.hour,
        minute: Int = this.minute,
        weekdays: Set<Int> = this.weekdays,
        pattern: VibrationPattern = this.pattern,
        burstCount: Int = this.burstCount,
        burstSpacing: Double = this.burstSpacing,
        backupNotification: Boolean = this.backupNotification,
    ): RingAlarm = RingAlarm(isEnabled, hour, minute, weekdays, pattern, burstCount, burstSpacing, backupNotification)

    override fun equals(other: Any?): Boolean =
        other is RingAlarm && isEnabled == other.isEnabled && hour == other.hour && minute == other.minute &&
            weekdays == other.weekdays && pattern == other.pattern && burstCount == other.burstCount &&
            ieeeEquals(burstSpacing, other.burstSpacing) && backupNotification == other.backupNotification

    override fun hashCode(): Int =
        listOf(isEnabled, hour, minute, weekdays, pattern, burstCount, ieeeHash(burstSpacing), backupNotification).hashCode()

    override fun toString(): String =
        "RingAlarm(isEnabled=$isEnabled, hour=$hour, minute=$minute, weekdays=$weekdays, pattern=$pattern, " +
            "burstCount=$burstCount, burstSpacing=$burstSpacing, backupNotification=$backupNotification)"
}

// MARK: - What the scheduler decided, and why

/**
 * The outcome of one evaluation. A skip carries a reason so the UI can tell the user what happened instead
 * of leaving them to guess why the ring stayed quiet — a silent alarm with no explanation is the single
 * worst failure this feature has.
 */
sealed interface RingAlarmDecision {
    /**
     * Buzz now. [scheduled] is the wall-clock time the alarm was set for; [lateBy] is how many seconds past
     * it we actually got runtime. Report [lateBy] rather than implying we hit the mark.
     */
    class Fire(val scheduled: Instant, val lateBy: Double) : RingAlarmDecision {
        override fun equals(other: Any?): Boolean = other is Fire && scheduled == other.scheduled && ieeeEquals(lateBy, other.lateBy)
        override fun hashCode(): Int = 31 * scheduled.hashCode() + ieeeHash(lateBy)
        override fun toString(): String = "Fire(scheduled=$scheduled, lateBy=$lateBy)"
    }

    /**
     * The alarm's moment passed without us ever getting runtime inside the grace window. Recorded, not
     * delivered — and worth showing the user, because it means the mechanism failed, not the clock.
     */
    data class Missed(val scheduled: Instant) : RingAlarmDecision

    /** Nothing to do (disabled, already handled, or the time simply hasn't come). */
    data object Idle : RingAlarmDecision
}

/**
 * Why a due alarm was not delivered to the motor. Distinct from [RingAlarmDecision.Missed]: these are
 * conditions we can see and explain right now. [rawValue] is upstream's case name.
 */
enum class RingAlarmBlock(val rawValue: String) {
    RING_ON_CHARGER("ringOnCharger"),
    RING_UNSUPPORTED("ringUnsupported"),
    LINK_NOT_READY("linkNotReady"),
    RING_BUSY("ringBusy"),
    ;

    companion object {
        /** The case whose raw name is exactly [raw] (Swift's `init?(rawValue:)`), or null. */
        fun fromRawValue(raw: String): RingAlarmBlock? = entries.firstOrNull { it.rawValue == raw }
    }
}

// MARK: - Scheduling

object RingAlarmSchedule {
    /**
     * How late (seconds) a wake-up buzz is still worth delivering. Past this the alarm is missed.
     *
     * 15 minutes is a judgement call, not a measurement: long enough to absorb a quiet overnight link (the
     * night keepalive is 60 s and the ring's own pushes are intermittent — a couple of minutes of silence is
     * ordinary), short enough that the buzz still lands inside the window where being woken is the point.
     */
    const val DEFAULT_GRACE: Double = 15 * 60.0

    /**
     * How long (seconds) before the alarm to start holding the link open. See [warmUpTarget].
     *
     * 5 minutes against a ring that pushes something roughly every 2.5 minutes gives about two chances to
     * catch an opening — enough to make catching one likely without burning radio any longer than needed.
     */
    const val DEFAULT_WARM_UP: Double = 5 * 60.0

    /**
     * The upcoming occurrence if [now] is inside its warm-up window (at most [warmUp] seconds before it),
     * else null. Never for a disabled alarm or a window that is not positive (NaN included).
     *
     * WHY A WARM-UP EXISTS: a suspended app cannot start a BLE write — the ring has to hand us the first
     * slice of runtime, on its own ~2.5 min beat. But once we ARE running, a request/response chain renews
     * itself. So take the first opening in the last few minutes before the alarm and hold the link from
     * there through the alarm time, and the buzz goes out in seconds instead of on the ring's next
     * spontaneous beat. It cannot make the alarm certain — one opening inside the window is still needed —
     * but it converts "late by up to the push cadence" into "late by the poll interval".
     */
    fun warmUpTarget(alarm: RingAlarm, now: Instant, zone: ZoneId, warmUp: Double = DEFAULT_WARM_UP): Instant? {
        if (!alarm.isEnabled || !(warmUp > 0)) return null
        val next = nextOccurrence(now, alarm, zone) ?: return null
        return if (secondsBetween(now, next) <= warmUp) next else null
    }

    /**
     * The most recent moment this alarm was scheduled to go off at or before [now], or null if there wasn't
     * one in the last week (which only happens for a sparse or out-of-range `weekdays` set, an hour or
     * minute outside the clock, or an instant that cannot be placed in [zone]).
     *
     * Walks back day by day through the calendar dates of [zone] rather than by a fixed 86 400 s day, so a
     * clock change can't shift the alarm by an hour.
     */
    fun mostRecentOccurrence(now: Instant, alarm: RingAlarm, zone: ZoneId): Instant? =
        walk(now, alarm, zone, direction = -1L) { candidate -> !candidate.isAfter(now) }

    /**
     * The next moment this alarm will go off strictly after [now]. Used for the settings screen's
     * "next alarm" line and to place the backup notification.
     */
    fun nextOccurrence(now: Instant, alarm: RingAlarm, zone: ZoneId): Instant? =
        walk(now, alarm, zone, direction = 1L) { candidate -> candidate.isAfter(now) }

    /**
     * Decide what to do with [alarm] right now.
     *
     * @param lastHandledAt the scheduled time of the last occurrence already acted on (fired OR recorded
     *   missed). NOT the time we acted — storing the OCCURRENCE is what makes this idempotent across the many
     *   wake-ups a single morning produces. null = never handled.
     * @param grace how late (seconds) a buzz is still worth delivering; the edge is inclusive.
     *
     * Called on every scrap of background runtime, so it must be cheap and must never fire twice for one morning.
     */
    fun decide(alarm: RingAlarm, now: Instant, lastHandledAt: Instant?, zone: ZoneId, grace: Double = DEFAULT_GRACE): RingAlarmDecision {
        if (!alarm.isEnabled) return RingAlarmDecision.Idle
        val scheduled = mostRecentOccurrence(now, alarm, zone) ?: return RingAlarmDecision.Idle
        // Already dealt with this occurrence (or a later one — a clock change can walk `scheduled`
        // backwards, and re-firing an alarm the user has already been woken by is unforgivable).
        if (lastHandledAt != null && lastHandledAt >= scheduled) return RingAlarmDecision.Idle
        val lateBy = secondsBetween(scheduled, now)
        return if (lateBy <= grace) RingAlarmDecision.Fire(scheduled, lateBy) else RingAlarmDecision.Missed(scheduled)
    }

    /** Today and the seven calendar days before ([direction] −1) or after (+1) it: the first occurrence [accept] takes. */
    private inline fun walk(now: Instant, alarm: RingAlarm, zone: ZoneId, direction: Long, accept: (Instant) -> Boolean): Instant? {
        val today = CalendarDay.date(now, zone) ?: return null
        for (dayOffset in 0L..7L) {
            val day = zonedOrNull { today.plusDays(direction * dayOffset) } ?: continue
            val candidate = CalendarDay.atWallClock(day, alarm.hour, alarm.minute, zone) ?: continue
            if (!accept(candidate)) continue
            val weekday = CalendarDay.weekday(candidate, zone) ?: continue
            if (alarm.repeats(weekday)) return candidate
        }
        return null
    }
}
