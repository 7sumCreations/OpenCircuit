package io.github.opencircuit.ringkit

// The calendar-day questions the sleep code asks of a zone — the start of an instant's day, its local
// date, its hour — in one place, each answering null when `java.time` cannot place the instant in the
// zone (the last year of `Instant`'s range) instead of throwing. Every caller names its zone; nothing
// here reads the machine's.
//
// Measured against upstream's Foundation calendar on the pinned build: across both 2026 transitions in
// eleven zones (midnight gaps, a repeated midnight, a 30-minute shift, :30 and :45 offsets) the start
// of day, the hour and the same-day test agreed on every instant. A day whose midnight falls in a gap
// starts at the end of the gap; a day whose midnight repeats starts at the first one.

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Runs [block]; an instant `java.time` cannot represent or place yields null instead of an exception. */
internal inline fun <T> zonedOrNull(block: () -> T): T? =
    try {
        block()
    } catch (e: DateTimeException) {
        null
    } catch (e: ArithmeticException) {
        null
    }

internal object CalendarDay {

    /** The first instant of [t]'s day in [zone] (Foundation's `startOfDay(for:)`), or null when unplaceable. */
    fun startOfDay(t: Instant, zone: ZoneId): Instant? = zonedOrNull { t.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant() }

    /** [t]'s local date in [zone], or null when unplaceable. */
    fun date(t: Instant, zone: ZoneId): LocalDate? = zonedOrNull { t.atZone(zone).toLocalDate() }

    /** [t]'s local hour (0–23) in [zone], or null when unplaceable. */
    fun hour(t: Instant, zone: ZoneId): Int? = zonedOrNull { t.atZone(zone).hour }

    /** Foundation's `isDate(_:inSameDayAs:)` in [zone]; false when either instant cannot be placed. */
    fun isSameDay(a: Instant, b: Instant, zone: ZoneId): Boolean {
        val da = date(a, zone) ?: return false
        val db = date(b, zone) ?: return false
        return da == db
    }
}
