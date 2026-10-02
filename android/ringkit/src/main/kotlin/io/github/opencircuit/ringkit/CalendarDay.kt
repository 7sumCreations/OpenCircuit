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
//
// A set wall-clock time (the alarm's) follows the same rule, also measured on the pinned build
// (Foundation's `date(bySettingHour:minute:second:of:)`, every minute of both 2026 changing days in New
// York, Adelaide, Lord Howe and Santiago): a time the clock skips is the end of the gap, a time it
// repeats is the first instant, and the answer depends only on the date, never on the time of day it
// was asked from. The one place this differs from Foundation is a gap that is not a whole hour (Lord
// Howe's 30-minute change), where Foundation answers with the same time the NEXT day — see `atWallClock`.

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

    /** [t]'s Gregorian weekday in [zone], Foundation's numbering (1 = Sunday … 7 = Saturday), or null when unplaceable. */
    fun weekday(t: Instant, zone: ZoneId): Int? = zonedOrNull { t.atZone(zone).dayOfWeek.value % 7 + 1 }

    /**
     * The instant [date] shows [hour]:[minute]:00 on the wall clock in [zone] — Foundation's
     * `date(bySettingHour:minute:second: 0, of:)` for a day — or null for an hour outside 0…23, a minute
     * outside 0…59, or a date `java.time` cannot place.
     *
     * - A time the clock shows once is that instant.
     * - A time it shows twice (the hour repeated when clocks go back) is the FIRST, as Foundation.
     * - A time it skips (clocks going forward) is the end of the gap — the first instant after the
     *   change — as Foundation answers for every whole-hour gap (New York's 02:30 → 03:00), not the same
     *   wall clock a gap later as `java.time`'s own rule would (03:30). For a gap that is not a whole hour
     *   (Lord Howe's 02:00 → 02:30) Foundation answers with the same time the NEXT day, so an alarm set
     *   inside it would not sound that day at all; here it is the end of the gap that day too.
     */
    fun atWallClock(date: LocalDate, hour: Int, minute: Int, zone: ZoneId): Instant? {
        if (hour !in 0..23 || minute !in 0..59) return null
        return zonedOrNull {
            val local = date.atTime(hour, minute)
            val rules = zone.rules
            val offsets = rules.getValidOffsets(local)
            if (offsets.isEmpty()) rules.getTransition(local).instant else offsets.minOf { local.toInstant(it) }
        }
    }
}
