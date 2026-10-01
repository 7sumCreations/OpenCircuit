package io.github.opencircuit.ringkit

// Manual nap edit / add: the pure rule for a valid nap window — a daytime block of a sensible length
// that ends in the past and overlaps neither the main night nor another nap. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/NapEdit.swift (@ b1c2fdd). The persistence (an
// overlay re-detection cannot clobber) belongs to the store.
//
// Shape notes: the daytime gate reads `SleepWindow.isOvernightBlock`, which upstream evaluates on the
// device calendar; here the zone is a required parameter. A window whose midpoint `java.time` cannot
// place in the zone is NOT daytime (fail closed; upstream's calendar also answers "not daytime" that
// far out, measured on the pinned build).

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Validation of a user-edited or user-added nap window. */
object NapEdit {

    /** Shortest block that counts as a nap — the auto-detector's 15 min. */
    val MIN_DURATION: Duration = NapDetection.MIN_NAP_DURATION

    /** A "nap" longer than this is a night: a manual add may not log a 10 h nap. */
    val MAX_DURATION: Duration = Duration.ofHours(6)

    /** A proposed nap window. */
    data class Window(val start: Instant, val end: Instant) {
        /** `end - start`, never negative. */
        val duration: Duration get() = maxOf(Duration.ZERO, Duration.between(start, end))
    }

    /** Why a proposed nap is rejected. A null from [validate] means it is allowed. */
    sealed interface Invalid {
        data object EndNotAfterStart : Invalid
        data class TooShort(val minMinutes: Int) : Invalid
        data class TooLong(val maxHours: Int) : Invalid
        data object NotDaytime : Invalid
        data object InFuture : Invalid
        data object OverlapsNight : Invalid
        data object OverlapsNap : Invalid
    }

    /**
     * Validate a proposed nap window (edit or add). [zone] is the wall clock "daytime" is judged in;
     * [night] is the main in-bed window to stay clear of (null when unknown); [otherNaps] are the OTHER
     * naps (exclude the one being edited); [now], when given, rejects a window that ends after it.
     * Overlaps are reported before the daytime gate (more actionable near the night's edge). A
     * user-asserted window is complete by construction, so the plain overnight test applies (never the
     * truncated-onset form).
     */
    fun validate(
        w: Window,
        zone: ZoneId,
        night: DateInterval? = null,
        otherNaps: List<DateInterval> = emptyList(),
        now: Instant? = null,
    ): Invalid? {
        if (w.end <= w.start) return Invalid.EndNotAfterStart
        if (w.duration < MIN_DURATION) return Invalid.TooShort(minMinutes = MIN_DURATION.toMinutes().toInt())
        if (w.duration > MAX_DURATION) return Invalid.TooLong(maxHours = MAX_DURATION.toHours().toInt())
        if (now != null && w.end > now) return Invalid.InFuture
        if (night != null && w.start < night.end && w.end > night.start) return Invalid.OverlapsNight
        for (o in otherNaps) if (w.start < o.end && w.end > o.start) return Invalid.OverlapsNap
        if (SleepWindow.overnightVerdict(w.start, w.end, zone) != false) return Invalid.NotDaytime
        return null
    }

    fun isValid(
        w: Window,
        zone: ZoneId,
        night: DateInterval? = null,
        otherNaps: List<DateInterval> = emptyList(),
        now: Instant? = null,
    ): Boolean = validate(w, zone, night, otherNaps, now) == null
}
