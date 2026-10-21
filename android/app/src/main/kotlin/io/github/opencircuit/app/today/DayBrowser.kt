package io.github.opencircuit.app.today

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The days Today can show: every local date from [first] (the day of the first stored reading) to
 * [last] (today), oldest first, one pager page each. Counted, never listed, so years of days cost
 * nothing until a page or a date-strip cell asks for one.
 */
data class BrowsableDays(val first: LocalDate, val last: LocalDate) {
    init {
        require(!first.isAfter(last)) { "the first day is after the last" }
    }

    val size: Int get() = Math.toIntExact(last.toEpochDay() - first.toEpochDay() + 1)

    fun dateAt(index: Int): LocalDate {
        require(index in 0 until size) { "no day at $index" }
        return first.plusDays(index.toLong())
    }

    /** [day]'s page, or -1 when it is outside the days. */
    fun indexOf(day: LocalDate): Int = if (day in this) Math.toIntExact(day.toEpochDay() - first.toEpochDay()) else -1

    operator fun contains(day: LocalDate): Boolean = !day.isBefore(first) && !day.isAfter(last)

    companion object {
        /**
         * From the day of [earliest] (the first stored reading) to today, both in [zone]; today alone
         * when nothing is stored, or when the first reading is dated after today (a ring clock gone wrong).
         */
        fun of(earliest: Instant?, now: Instant, zone: ZoneId): BrowsableDays {
            val today = LocalDate.ofInstant(now, zone)
            val first = earliest?.let { LocalDate.ofInstant(it, zone) }?.takeIf { !it.isAfter(today) } ?: today
            return BrowsableDays(first, today)
        }

        /** [day]'s bounds in [zone]: its start to the next day's start (23 h or 25 h on a daylight-saving day). */
        fun bounds(day: LocalDate, zone: ZoneId): Pair<Instant, Instant> =
            day.atStartOfDay(zone).toInstant() to day.plusDays(1).atStartOfDay(zone).toInstant()
    }
}

/**
 * The day browser as the screen draws it: the days there are, the one picked, the heading for it,
 * and the dates marked because data landed on them while another day was open.
 */
data class DayBrowserUi(
    /** Null before the first load: one page, today. */
    val days: BrowsableDays? = null,
    val selected: LocalDate? = null,
    val isToday: Boolean = true,
    /** "Today", or the picked day's weekday ("Monday"). */
    val title: String = TODAY,
    /** Today's date in words, or the picked day's date and how long ago ("19 October · 2 days ago"). */
    val subtitle: String = "",
    val marked: Set<LocalDate> = emptySet(),
)

/** One date in the date strip: its short weekday (or "Today"), its day of the month, and what a screen reader says for it. */
data class DayCellUi(val top: String, val number: String, val spoken: String)

internal const val TODAY = "Today"

/** The browser's words. Pure; English dates whatever the phone's locale, as the rest of Today. */
object DayBrowser {

    fun present(days: BrowsableDays, selected: LocalDate, today: LocalDate, marked: Set<LocalDate>): DayBrowserUi {
        val isToday = selected == today
        val ago = today.toEpochDay() - selected.toEpochDay()
        return DayBrowserUi(
            days = days,
            selected = selected,
            isToday = isToday,
            title = if (isToday) TODAY else WEEKDAY.format(selected),
            subtitle = if (isToday) TodayDashboard.dateLine(today) else "${DAY_MONTH.format(selected)} · ${agoWords(ago)}",
            marked = marked,
        )
    }

    /** A date-strip cell: "Today" or the weekday's letter, the day of the month, and its spoken label. */
    fun cell(day: LocalDate, today: LocalDate, marked: Boolean): DayCellUi {
        val spoken = buildString {
            append(LONG.format(day))
            if (day == today) append(", today")
            if (marked) append(", new data just arrived")
        }
        return DayCellUi(if (day == today) TODAY else NARROW.format(day), day.dayOfMonth.toString(), spoken)
    }

    /** "1 day ago", "2 days ago"; "in 1 day" for a picked day after today (never offered, but never mis-said). */
    private fun agoWords(days: Long): String = when {
        days == 1L -> "1 day ago"
        days > 1 -> "$days days ago"
        days == -1L -> "in 1 day"
        else -> "in ${-days} days"
    }

    private val WEEKDAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)
    private val DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH)
    private val LONG: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
    private val NARROW: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEEE", Locale.ENGLISH)
}

/**
 * Which dates get the "new data" mark when a load replaces the one before it: days in both loads'
 * windows whose stored readings changed, days a drain added before the first stored day, and the day
 * of a newer newest heart rate or SpO₂ (data landing on today while a far-back day is open). The day
 * on screen is never marked: it folds in place. Pure.
 */
object DayMarks {

    /** The most days the first history ever stored can mark at once (a ring keeps about three weeks). */
    private const val MAX_FIRST_HISTORY_DAYS = 400L

    fun landed(before: TodayStored?, after: TodayStored, zone: ZoneId): Set<LocalDate> {
        if (before == null) return emptySet()
        fun dayOf(t: Instant): LocalDate = LocalDate.ofInstant(t, zone)
        val marks = mutableSetOf<LocalDate>()

        val beforeWindow = window(before)
        val afterWindow = window(after)
        if (beforeWindow != null && afterWindow != null) {
            val was = signatures(before.trends, zone)
            val now = signatures(after.trends, zone)
            var d = maxOf(beforeWindow.start, afterWindow.start)
            val end = minOf(beforeWindow.endInclusive, afterWindow.endInclusive)
            while (!d.isAfter(end)) {
                if (was[d] != now[d]) marks += d
                d = d.plusDays(1)
            }
        }

        val newEarliest = after.earliest?.let(::dayOf)
        val oldEarliest = before.earliest?.let(::dayOf)
        if (newEarliest != null && (oldEarliest == null || newEarliest.isBefore(oldEarliest))) {
            val newest = listOfNotNull(after.heartRate?.start, after.spo2?.start, after.trends.newestSampleAt).maxOrNull()?.let(::dayOf)
            val until = oldEarliest?.minusDays(1) ?: newest ?: newEarliest
            var d = maxOf(newEarliest, until.minusDays(MAX_FIRST_HISTORY_DAYS))
            while (!d.isAfter(until)) {
                marks += d
                d = d.plusDays(1)
            }
        }

        for ((was, now) in listOf(before.heartRate?.start to after.heartRate?.start, before.spo2?.start to after.spo2?.start)) {
            if (now != null && (was == null || now.isAfter(was))) marks += dayOf(now)
        }
        after.day?.let(marks::remove)
        return marks
    }

    /** The days a load covers: its [TodayStored.windowDays] ending on its day; null for a load not for a day. */
    private fun window(stored: TodayStored): ClosedRange<LocalDate>? {
        val day = stored.day ?: return null
        return day.minusDays(stored.windowDays - 1L)..day
    }

    /** What each day of a load shows: its rolled-up point and its heart-rate range. */
    private fun signatures(trends: TodayTrends, zone: ZoneId): Map<LocalDate, Any> {
        val out = HashMap<LocalDate, MutableList<Any>>()
        for (p in trends.points) out.getOrPut(LocalDate.ofInstant(p.date, zone)) { mutableListOf() } += p
        for (r in trends.heartRateRanges) out.getOrPut(LocalDate.ofInstant(r.day, zone)) { mutableListOf() } += r
        return out
    }
}
