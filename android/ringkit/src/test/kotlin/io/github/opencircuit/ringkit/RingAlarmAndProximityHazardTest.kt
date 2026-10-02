package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.FoundationDate.unix
import io.github.opencircuit.ringkit.RingAlarmDecision.Fire
import io.github.opencircuit.ringkit.RingAlarmDecision.Idle
import io.github.opencircuit.ringkit.RingAlarmDecision.Missed
import io.github.opencircuit.ringkit.RingProximity.Band
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kotlin-only hostile-input checks for the ring alarm's scheduling and the ring-proximity estimate: what
 * the upstream vectors never feed in. The alarm's hour, minute, weekdays, burst settings, grace and
 * warm-up are stored, user-entered values, its "last handled" stamp is stored between launches, and its
 * clock is the phone's; the RSSI comes from the radio, sentinel and all. So here the alarm is set inside
 * both 2026 clock changes in New York, Adelaide (a half-hour offset), Lord Howe (a half-hour change) and
 * Santiago (a change at midnight), at every minute of each changing day; the stored values arrive out of
 * range, NaN, infinite, zero and negative; the clock arrives at Foundation's distant dates and `Instant`'s
 * ends; and the RSSI arrives absent, as the "not available" sentinel 127, non-negative and at `Int`'s ends.
 * Kept out of the upstream-port classes so their counts stay exact.
 *
 * Every upstream outcome quoted below was measured on the pinned Swift build (Swift 6.3.2, Foundation's
 * Gregorian calendar with each named zone). Where the port deliberately differs the test says so, and
 * `PORTING.md` records why.
 */
class RingAlarmAndProximityHazardTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val newYork: ZoneId = ZoneId.of("America/New_York")
    private val adelaide: ZoneId = ZoneId.of("Australia/Adelaide")
    private val lordHowe: ZoneId = ZoneId.of("Australia/Lord_Howe")
    private val santiago: ZoneId = ZoneId.of("America/Santiago")
    private val kolkata: ZoneId = ZoneId.of("Asia/Kolkata")

    private fun at(s: String): Instant = Instant.parse(s)

    private fun alarm(hour: Int, minute: Int, weekdays: Set<Int> = emptySet()) =
        RingAlarm(isEnabled = true, hour = hour, minute = minute, weekdays = weekdays)

    /** One measured upstream row: the most recent and next occurrences, the decision (never handled) and the warm-up target. */
    private fun row(a: RingAlarm, now: Instant, zone: ZoneId): String = listOf(
        RingAlarmSchedule.mostRecentOccurrence(now, a, zone),
        RingAlarmSchedule.nextOccurrence(now, a, zone),
        render(RingAlarmSchedule.decide(a, now, lastHandledAt = null, zone = zone)),
        RingAlarmSchedule.warmUpTarget(a, now, zone),
    ).joinToString(" ") { it?.toString() ?: "nil" }

    private fun row(a: RingAlarm, now: String, zone: ZoneId): String = row(a, at(now), zone)

    private fun render(d: RingAlarmDecision): String = when (d) {
        Idle -> "idle"
        is Missed -> "missed(${d.scheduled})"
        is Fire -> "fire(${d.scheduled}, ${d.lateBy})"
    }

    /** Every minute of [day] in [zone]: what the wall-clock helper gives, as an instant (or null). */
    private fun everyMinute(day: LocalDate, zone: ZoneId): Map<Int, Instant?> =
        (0 until 1_440).associateWith { m -> CalendarDay.atWallClock(day, m / 60, m % 60, zone) }

    /** The single instant with this wall clock when it is neither skipped nor repeated. */
    private fun plain(day: LocalDate, m: Int, zone: ZoneId): Instant = day.atTime(m / 60, m % 60).atZone(zone).toInstant()

    // MARK: the wall clock on a clock-change day (Foundation's `date(bySettingHour:minute:second:of:)`)

    @Test
    fun aWallClockTimeInAWholeHourGapResolvesToTheGapsEndAndARepeatedOneToItsFirstInstant() {
        // Measured on every minute of each changing day, with `of:` at 27 different times of that day (the
        // answer never depended on it): a time the clock skips resolves to the end of the gap — not to the
        // same wall clock an hour later, which is what `java.time`'s own rule would give (02:30 → 03:30);
        // a time the clock repeats resolves to its FIRST instant; every other minute is plain.
        data class Day(val zone: ZoneId, val date: LocalDate, val window: IntRange, val expected: (Int) -> Instant)
        val days = listOf(
            // New York spring-forward: 02:00–02:59 → 03:00 EDT (07:00Z).
            Day(newYork, LocalDate.of(2026, 3, 8), 120 until 180) { at("2026-03-08T07:00:00Z") },
            // New York fall-back: 01:00–01:59 → the EDT instant (05:mmZ), not the EST one an hour later.
            Day(newYork, LocalDate.of(2026, 11, 1), 60 until 120) { m -> at("2026-11-01T05:00:00Z").plusSeconds(60L * (m - 60)) },
            // Adelaide (UTC+9:30 / +10:30) fall-back: 02:00–02:59 → its first instant, 15:30Z the day before.
            Day(adelaide, LocalDate.of(2026, 4, 5), 120 until 180) { m -> at("2026-04-04T15:30:00Z").plusSeconds(60L * (m - 120)) },
            // Adelaide spring-forward: 02:00–02:59 → 03:00 local (16:30Z the day before).
            Day(adelaide, LocalDate.of(2026, 10, 4), 120 until 180) { at("2026-10-03T16:30:00Z") },
            // Santiago spring-forward at midnight: 00:00–00:59 → 01:00 local (04:00Z).
            Day(santiago, LocalDate.of(2026, 9, 6), 0 until 60) { at("2026-09-06T04:00:00Z") },
            // Lord Howe (a 30-minute change) fall-back: 01:30–01:59 → its first instant.
            Day(lordHowe, LocalDate.of(2026, 4, 5), 90 until 120) { m -> at("2026-04-04T14:30:00Z").plusSeconds(60L * (m - 90)) },
            // Kolkata keeps one half-hour offset all year: every minute plain.
            Day(kolkata, LocalDate.of(2026, 3, 8), IntRange.EMPTY) { error("no special minute") },
        )
        for (d in days) {
            for ((m, got) in everyMinute(d.date, d.zone)) {
                val want = if (m in d.window) d.expected(m) else plain(d.date, m, d.zone)
                assertEquals(want, got, "${d.zone} ${d.date} %02d:%02d".format(m / 60, m % 60))
            }
        }
    }

    @Test
    fun aWallClockTimeInAHalfHourGapResolvesToTheGapsEndThatDayWhereUpstreamSkipsTheDay() {
        // DELIBERATE DIFFERENCE (an improvement): Lord Howe moves its clock 30 minutes, 02:00 → 02:30, on
        // 4 Oct 2026. Measured: Foundation resolves 02:00–02:29 that day to the same time the NEXT day
        // (02:15 → 5 Oct 02:15, 2026-10-04T15:15Z), so the morning's alarm vanishes — the most recent
        // occurrence stays the 3rd's, the next one is the 5th's, and a 02:15 alarm never fires on the 4th.
        // Upstream's own test asks that a time the clock skips "resolve to a real instant rather than
        // vanishing for the day"; the port resolves it to the end of the gap that day, as Foundation does
        // for a whole-hour gap.
        val day = LocalDate.of(2026, 10, 4)
        val gapEnd = at("2026-10-03T15:30:00Z") // 02:30 local, the first instant after the change
        val minutes = everyMinute(day, lordHowe)
        for (m in 120 until 150) assertEquals(gapEnd, minutes[m], "02:%02d".format(m - 120))
        for (m in (0 until 120) + (150 until 1_440)) assertEquals(plain(day, m, lordHowe), minutes[m], "%02d:%02d".format(m / 60, m % 60))

        val a = alarm(2, 15)
        // At 02:30 local (15:30Z) the alarm is due now; upstream: missed(2026-10-02T15:45Z), the 3rd's.
        assertEquals(Fire(gapEnd, 0.0), RingAlarmSchedule.decide(a, gapEnd, lastHandledAt = at("2026-10-02T15:45:00Z"), zone = lordHowe))
        // Before it, the next occurrence is that morning's and the warm-up arms (upstream: the 5th's; no warm-up).
        assertEquals(gapEnd, RingAlarmSchedule.nextOccurrence(at("2026-10-03T15:10:00Z"), a, lordHowe))
        assertEquals(gapEnd, RingAlarmSchedule.warmUpTarget(a, at("2026-10-03T15:26:00Z"), lordHowe))
        // The next morning is ordinary, as upstream: 5 Oct 02:15 (UTC+11) = 2026-10-04T15:15Z.
        assertEquals("2026-10-04T15:15:00Z 2026-10-05T15:15:00Z fire(2026-10-04T15:15:00Z, 0.0) nil", row(a, "2026-10-04T15:15:00Z", lordHowe))

        // The rule holds for every gap in every zone this JVM knows, 2026 and 2027: a skipped minute
        // resolves to the first instant after the change, on the same local date; a repeated one to its
        // earlier instant; nothing throws and nothing is null.
        var gaps = 0
        for (id in ZoneId.getAvailableZoneIds().sorted()) {
            val zone = ZoneId.of(id)
            var t = at("2026-01-01T00:00:00Z")
            while (true) {
                val tr = zone.rules.nextTransition(t) ?: break
                if (tr.instant.isAfter(at("2028-01-01T00:00:00Z"))) break
                t = tr.instant
                val local = if (tr.isGap) tr.dateTimeBefore else tr.dateTimeAfter
                val date = local.toLocalDate()
                val first = local.hour * 60 + local.minute
                val span = (tr.duration.seconds / 60).toInt().let { if (it < 0) -it else it }
                for (m in first until minOf(first + span, 1_440)) {
                    val got = CalendarDay.atWallClock(date, m / 60, m % 60, zone)
                    if (tr.isGap) {
                        gaps++
                        assertEquals(tr.instant, got, "$id gap $date %02d:%02d".format(m / 60, m % 60))
                    } else {
                        assertEquals(date.atTime(m / 60, m % 60).toInstant(tr.offsetBefore), got, "$id overlap $date %02d:%02d".format(m / 60, m % 60))
                    }
                }
            }
        }
        assertTrue(gaps > 1_000, "the sweep reached the gaps ($gaps minutes)")
    }

    @Test
    fun anHourOrMinuteOutsideTheClockHasNoOccurrence() {
        // Measured: `date(bySettingHour:minute:)` is nil for an hour outside 0…23 or a minute outside 0…59 —
        // −1, 24, 25, 48, 60, 61, 1 440, and both ends of a 32-bit `Int` — so every occurrence is nil and
        // the decision is idle. (Swift's own `Int.max` is Foundation's "unset" marker and reads as 00:00 or
        // :00; a Kotlin `Int` cannot hold it.)
        val day = LocalDate.of(2026, 9, 1)
        val bad = listOf(-1 to 0, 24 to 0, 25 to 0, 48 to 0, Int.MIN_VALUE to 0, Int.MAX_VALUE to 0, 7 to -1, 7 to 60, 7 to 61, 7 to 1_440, 7 to Int.MAX_VALUE, 7 to Int.MIN_VALUE, 23 to 60, 0 to -1, 24 to -1)
        for ((h, m) in bad) {
            for (zone in listOf(utc, newYork)) assertNull(CalendarDay.atWallClock(day, h, m, zone), "$h:$m in $zone")
            assertEquals("nil nil idle nil", row(alarm(h, m), "2026-09-01T07:01:00Z", utc), "$h:$m")
        }
        // The edges of the clock are valid.
        assertEquals(at("2026-09-01T00:00:00Z"), CalendarDay.atWallClock(day, 0, 0, utc))
        assertEquals(at("2026-09-02T03:59:00Z"), CalendarDay.atWallClock(day, 23, 59, newYork))
    }

    @Test
    fun weekdaysOutsideTheWeekNeverFireAsUpstream() {
        // Kept as upstream (measured): a stored weekday outside 1…7 is taken literally, so a set holding
        // only such days never matches a real day — no occurrence, idle — while an empty set is every day.
        // `repeats` answered for −1…9:
        val rows = mapOf(
            setOf(0) to "01000000000",
            setOf(8) to "00000000010",
            setOf(-1) to "10000000000",
            setOf(1, 8) to "00100000010",
            setOf(Int.MAX_VALUE) to "00000000000",
            emptySet<Int>() to "11111111111",
        )
        for ((w, bits) in rows) {
            val a = alarm(7, 0, w)
            assertEquals(bits, (-1..9).joinToString("") { if (a.repeats(it)) "1" else "0" }, "$w")
        }
        for (w in listOf(setOf(0), setOf(8), setOf(-1), setOf(Int.MAX_VALUE))) {
            assertEquals("nil nil idle nil", row(alarm(7, 0, w), "2026-09-01T07:01:00Z", utc), "$w")
        }
        // A real Sunday beside a bogus day: the Sunday is used (2026-08-30 is a Sunday, weekday 1).
        assertEquals(
            "2026-08-30T07:00:00Z 2026-09-06T07:00:00Z missed(2026-08-30T07:00:00Z) nil",
            row(alarm(7, 0, setOf(1, 8)), "2026-09-01T07:01:00Z", utc),
        )
    }

    @Test
    fun hostileGraceWarmUpAndLastHandledFollowUpstream() {
        val a = alarm(7, 0)
        fun decide(grace: Double, now: String) = render(RingAlarmSchedule.decide(a, at(now), lastHandledAt = null, zone = utc, grace = grace))
        // Measured, at 07:00 and 07:01 on a 07:00 alarm: NaN, negative and −∞ grace never fire (always
        // missed); zero (and −0.0) fires only exactly on time; +∞ always fires; a grace a millisecond short
        // of the lateness misses, an exact one fires (the closed edge).
        val graceRows = listOf(
            Double.NaN to listOf("missed", "missed"),
            -1.0 to listOf("missed", "missed"),
            0.0 to listOf("fire 0.0", "missed"),
            -0.0 to listOf("fire 0.0", "missed"),
            Double.POSITIVE_INFINITY to listOf("fire 0.0", "fire 60.0"),
            Double.NEGATIVE_INFINITY to listOf("missed", "missed"),
            59.999 to listOf("fire 0.0", "missed"),
            60.0 to listOf("fire 0.0", "fire 60.0"),
        )
        for ((g, want) in graceRows) {
            val got = listOf("2026-09-01T07:00:00Z", "2026-09-01T07:01:00Z").map { n ->
                decide(g, n).let { if (it.startsWith("fire")) "fire " + it.substringAfter(", ").removeSuffix(")") else it.substringBefore("(") }
            }
            assertEquals(want, got, "grace $g")
        }
        // Measured warm-up targets at 06:55, 07:00 and 06:00: NaN, ≤ 0, −∞ and a nanosecond-sized window never
        // arm; 300 s arms at 06:55 only; +∞ (and anything past a week) arms on the next occurrence always —
        // at 07:00 itself that is tomorrow's, because the next occurrence is strictly after now.
        val today = at("2026-09-01T07:00:00Z")
        val tomorrow = at("2026-09-02T07:00:00Z")
        val warmRows = listOf(
            Double.NaN to listOf(null, null, null),
            -1.0 to listOf(null, null, null),
            0.0 to listOf(null, null, null),
            -0.0 to listOf(null, null, null),
            1e-9 to listOf(null, null, null),
            300.0 to listOf(today, null, null),
            Double.POSITIVE_INFINITY to listOf(today, tomorrow, today),
            Double.NEGATIVE_INFINITY to listOf(null, null, null),
            86_400 * 6.9 to listOf(today, tomorrow, today),
            86_400 * 8.0 to listOf(today, tomorrow, today),
        )
        for ((w, want) in warmRows) {
            val got = listOf("2026-09-01T06:55:00Z", "2026-09-01T07:00:00Z", "2026-09-01T06:00:00Z").map {
                RingAlarmSchedule.warmUpTarget(a, at(it), utc, warmUp = w)
            }
            assertEquals(want, got, "warm-up $w")
        }
        // A stored "last handled" stamp at or after the occurrence holds it — even one months in the future
        // (a clock that went backwards); one a second before lets it fire. Measured at 07:05.
        val now = at("2026-09-01T07:05:00Z")
        assertEquals(Idle, RingAlarmSchedule.decide(a, now, lastHandledAt = today, zone = utc))
        assertEquals(Idle, RingAlarmSchedule.decide(a, now, lastHandledAt = at("2027-01-01T00:00:00Z"), zone = utc))
        assertEquals(Fire(today, 300.0), RingAlarmSchedule.decide(a, now, lastHandledAt = at("2026-09-01T06:59:59Z"), zone = utc))
    }

    @Test
    fun clockChangeMorningsFollowUpstream() {
        // Measured rows (most recent, next, decision with nothing handled, warm-up target) through the alarm
        // functions themselves, so the day walk and the weekday read are checked with the wall-clock rule.
        val rows = listOf(
            // New York, 02:30 on the spring-forward morning → 03:00 EDT (07:00Z).
            Triple(alarm(2, 30), newYork, "2026-03-08T06:54:00Z") to "2026-03-07T07:30:00Z 2026-03-08T07:00:00Z missed(2026-03-07T07:30:00Z) nil",
            Triple(alarm(2, 30), newYork, "2026-03-08T06:55:00Z") to "2026-03-07T07:30:00Z 2026-03-08T07:00:00Z missed(2026-03-07T07:30:00Z) 2026-03-08T07:00:00Z",
            Triple(alarm(2, 30), newYork, "2026-03-08T07:00:00Z") to "2026-03-08T07:00:00Z 2026-03-09T06:30:00Z fire(2026-03-08T07:00:00Z, 0.0) nil",
            Triple(alarm(2, 30), newYork, "2026-03-08T07:15:00Z") to "2026-03-08T07:00:00Z 2026-03-09T06:30:00Z fire(2026-03-08T07:00:00Z, 900.0) nil",
            Triple(alarm(2, 30), newYork, "2026-03-08T07:16:00Z") to "2026-03-08T07:00:00Z 2026-03-09T06:30:00Z missed(2026-03-08T07:00:00Z) nil",
            Triple(alarm(2, 30), newYork, "2026-03-09T06:00:00Z") to "2026-03-08T07:00:00Z 2026-03-09T06:30:00Z missed(2026-03-08T07:00:00Z) nil",
            Triple(alarm(2, 30), newYork, "2026-03-09T07:30:00Z") to "2026-03-09T06:30:00Z 2026-03-10T06:30:00Z missed(2026-03-09T06:30:00Z) nil",
            Triple(alarm(2, 0), newYork, "2026-03-08T07:15:00Z") to "2026-03-08T07:00:00Z 2026-03-09T06:00:00Z fire(2026-03-08T07:00:00Z, 900.0) nil",
            Triple(alarm(3, 0), newYork, "2026-03-08T07:00:00Z") to "2026-03-08T07:00:00Z 2026-03-09T07:00:00Z fire(2026-03-08T07:00:00Z, 0.0) nil",
            // New York, 01:30 on the fall-back morning: the first 01:30 (05:30Z) only, never the second.
            Triple(alarm(1, 30), newYork, "2026-11-01T05:29:00Z") to "2026-10-31T05:30:00Z 2026-11-01T05:30:00Z missed(2026-10-31T05:30:00Z) 2026-11-01T05:30:00Z",
            Triple(alarm(1, 30), newYork, "2026-11-01T05:45:00Z") to "2026-11-01T05:30:00Z 2026-11-02T06:30:00Z fire(2026-11-01T05:30:00Z, 900.0) nil",
            Triple(alarm(1, 30), newYork, "2026-11-01T05:46:00Z") to "2026-11-01T05:30:00Z 2026-11-02T06:30:00Z missed(2026-11-01T05:30:00Z) nil",
            Triple(alarm(1, 30), newYork, "2026-11-01T06:30:00Z") to "2026-11-01T05:30:00Z 2026-11-02T06:30:00Z missed(2026-11-01T05:30:00Z) nil",
            Triple(alarm(1, 30), newYork, "2026-11-02T06:31:00Z") to "2026-11-02T06:30:00Z 2026-11-03T06:30:00Z fire(2026-11-02T06:30:00Z, 60.0) nil",
            Triple(alarm(7, 0), newYork, "2026-11-01T12:01:00Z") to "2026-11-01T12:00:00Z 2026-11-02T12:00:00Z fire(2026-11-01T12:00:00Z, 60.0) nil",
            Triple(alarm(7, 0), newYork, "2026-10-31T11:01:00Z") to "2026-10-31T11:00:00Z 2026-11-01T12:00:00Z fire(2026-10-31T11:00:00Z, 60.0) nil",
            // Adelaide: 02:30 on its spring-forward and fall-back mornings.
            Triple(alarm(2, 30), adelaide, "2026-10-03T16:25:00Z") to "2026-10-02T17:00:00Z 2026-10-03T16:30:00Z missed(2026-10-02T17:00:00Z) 2026-10-03T16:30:00Z",
            Triple(alarm(2, 30), adelaide, "2026-10-03T16:45:00Z") to "2026-10-03T16:30:00Z 2026-10-04T16:00:00Z fire(2026-10-03T16:30:00Z, 900.0) nil",
            Triple(alarm(2, 30), adelaide, "2026-10-03T16:46:00Z") to "2026-10-03T16:30:00Z 2026-10-04T16:00:00Z missed(2026-10-03T16:30:00Z) nil",
            Triple(alarm(2, 30), adelaide, "2026-04-04T15:59:00Z") to "2026-04-03T16:00:00Z 2026-04-04T16:00:00Z missed(2026-04-03T16:00:00Z) 2026-04-04T16:00:00Z",
            Triple(alarm(2, 30), adelaide, "2026-04-04T17:00:00Z") to "2026-04-04T16:00:00Z 2026-04-05T17:00:00Z missed(2026-04-04T16:00:00Z) nil",
            // Lord Howe's fall-back (01:45 repeats): the first instant, as upstream.
            Triple(alarm(1, 45), lordHowe, "2026-04-04T14:45:00Z") to "2026-04-04T14:45:00Z 2026-04-05T15:15:00Z fire(2026-04-04T14:45:00Z, 0.0) nil",
            Triple(alarm(1, 45), lordHowe, "2026-04-04T15:15:00Z") to "2026-04-04T14:45:00Z 2026-04-05T15:15:00Z missed(2026-04-04T14:45:00Z) nil",
            // Santiago: 00:30 on the morning its midnight is skipped → 01:00 (04:00Z); 23:30 on the evening it repeats → the first.
            Triple(alarm(0, 30), santiago, "2026-09-06T03:59:00Z") to "2026-09-05T04:30:00Z 2026-09-06T04:00:00Z missed(2026-09-05T04:30:00Z) 2026-09-06T04:00:00Z",
            Triple(alarm(0, 30), santiago, "2026-09-06T04:01:00Z") to "2026-09-06T04:00:00Z 2026-09-07T03:30:00Z fire(2026-09-06T04:00:00Z, 60.0) nil",
            Triple(alarm(23, 30), santiago, "2026-04-05T02:30:00Z") to "2026-04-05T02:30:00Z 2026-04-06T03:30:00Z fire(2026-04-05T02:30:00Z, 0.0) nil",
            Triple(alarm(23, 30), santiago, "2026-04-05T03:31:00Z") to "2026-04-05T02:30:00Z 2026-04-06T03:30:00Z missed(2026-04-05T02:30:00Z) nil",
        )
        for ((input, want) in rows) {
            val (a, zone, now) = input
            assertEquals(want, row(a, now, zone), "${a.hour}:${a.minute} $zone at $now")
        }
        // Once the first 01:30 is handled, the repeated hour is quiet (measured: idle at 06:30Z and 06:31Z).
        for (n in listOf("2026-11-01T06:30:00Z", "2026-11-01T06:31:00Z")) {
            assertEquals(Idle, RingAlarmSchedule.decide(alarm(1, 30), at(n), lastHandledAt = at("2026-11-01T05:30:00Z"), zone = newYork))
        }
    }

    @Test
    fun theEndsOfTimeNeverThrow() {
        val a = alarm(7, 0)
        // Foundation's distant dates, both sides of 1582's calendar switch and the last second of year 9999
        // (seconds since 1970): measured, the port agrees exactly — the day walk and the weekday survive
        // Foundation's switch to the Julian calendar before 1582 (whole days stay whole days; the week is
        // unbroken).
        val far = mapOf(
            unix(-62_135_769_600.0) to Pair(unix(-62_135_830_800.0), unix(-62_135_744_400.0)), // distantPast
            unix(64_092_211_200.0) to Pair(unix(64_092_150_000.0), unix(64_092_236_400.0)), // distantFuture
            unix(-12_219_292_800.0) to Pair(unix(-12_219_354_000.0), unix(-12_219_267_600.0)), // 1582-10-14 12:00
            unix(-12_219_206_400.0) to Pair(unix(-12_219_267_600.0), unix(-12_219_181_200.0)), // 1582-10-15 12:00
            at("9999-12-31T23:59:59Z") to Pair(at("9999-12-31T07:00:00Z"), at("+10000-01-01T07:00:00Z")),
        )
        for ((now, want) in far) {
            assertEquals(want, Pair(RingAlarmSchedule.mostRecentOccurrence(now, a, utc), RingAlarmSchedule.nextOccurrence(now, a, utc)), "$now")
            assertEquals(Missed(want.first), RingAlarmSchedule.decide(a, now, lastHandledAt = null, zone = utc), "$now")
        }
        // New York's local mean time before 1883 (UTC−4:56:02), as Foundation: 07:00 local = 11:56:02Z.
        assertEquals(unix(-62_135_744_400.0 + 17_762.0), RingAlarmSchedule.nextOccurrence(unix(-62_135_769_600.0), a, newYork))
        // KEPT DIFFERENCE: `Instant.MAX` / `MIN` (years ±1 000 000 000) cannot be placed in any zone, so there
        // is no occurrence and the decision is idle; nothing throws. Upstream's `Date` reaches them and its
        // calendar answers (measured at Instant.MAX: most recent "506713-02-06", missed; at MIN: next
        // "4713-01-02", idle) — dates no phone clock can hold.
        for (zone in listOf(utc, newYork, lordHowe)) {
            for (t in listOf(Instant.MAX, Instant.MIN)) {
                assertEquals("nil nil idle nil", row(a, t, zone), "$t in $zone")
                assertEquals(Idle, RingAlarmSchedule.decide(a, t, lastHandledAt = Instant.MIN, zone = zone))
            }
        }
    }

    @Test
    fun burstSettingsClampAsUpstreamIncludingNaN() {
        // Measured: counts clamp to 1…10 at every `Int`; spacings to 2…30 s, and a NaN spacing stays NaN
        // (Swift's `min` / `max` pass a NaN first argument through). Upstream's stored form cannot hold a
        // NaN (its JSON encoder refuses one), so only a hand-built alarm carries it.
        val counts = mapOf(Int.MIN_VALUE to 1, -1 to 1, 0 to 1, 1 to 1, 10 to 10, 11 to 10, Int.MAX_VALUE to 10)
        for ((c, want) in counts) assertEquals(want, RingAlarm(burstCount = c).clampedBurstCount, "count $c")
        val spacings = listOf(
            Double.NEGATIVE_INFINITY to 2.0, -0.0 to 2.0, 0.0 to 2.0, 1.999 to 2.0, 2.0 to 2.0, 4.0 to 4.0,
            30.0 to 30.0, 30.0001 to 30.0, Double.POSITIVE_INFINITY to 30.0,
        )
        for ((s, want) in spacings) {
            val got = RingAlarm(burstSpacing = s).clampedBurstSpacing
            assertEquals(want.toRawBits(), got.toRawBits(), "spacing $s → $got") // +2.0, never −0.0 or a −2.0
        }
        assertTrue(RingAlarm(burstSpacing = Double.NaN).clampedBurstSpacing.isNaN())
    }

    // MARK: proximity

    @Test
    fun aNonNegativeRSSIShowsNoSignalWhereUpstreamShowsAFullDial() {
        // DELIBERATE DIFFERENCE (an improvement): 127 is the Bluetooth "RSSI not available" value, and any
        // non-negative reading is one `band` already calls "searching". Measured: upstream's
        // `signalFraction` clamps it to −45 dBm and shows a FULL dial (1.0) beside "Searching…". The port
        // shows an empty one (0.0).
        for (r in listOf(0, 1, 127, Int.MAX_VALUE)) {
            assertEquals(Band.SEARCHING, RingProximity.band(r), "band $r")
            assertEquals(0.0, RingProximity.signalFraction(r), "fraction $r")
        }
        // Every other reading keeps upstream's value (measured): absent and very weak 0, the −95…−45 dBm
        // line, clamped to 1 above −45 dBm.
        val measured = mapOf(
            null to 0.0, Int.MIN_VALUE to 0.0, -1_000 to 0.0, -96 to 0.0, -95 to 0.0, -94 to 0.02, -81 to 0.28,
            -80 to 0.3, -70 to 0.5, -69 to 0.52, -68 to 0.54, -59 to 0.72, -55 to 0.8, -46 to 0.98, -45 to 1.0, -40 to 1.0, -1 to 1.0,
        )
        for ((r, want) in measured) assertEquals(want, RingProximity.signalFraction(r), "fraction $r")
        // Swept: the dial is always within 0…1, never falls as the signal strengthens below 0 dBm, and is
        // empty whenever the band says "searching" (upstream breaks only this last property, and only at 0 and above).
        val sweep = listOf(Int.MIN_VALUE, Int.MAX_VALUE) + (-2_000..2_000)
        var previous = -1.0
        for (r in sweep.sorted()) {
            val f = RingProximity.signalFraction(r)
            assertTrue(f in 0.0..1.0, "fraction $r = $f")
            if (r < 0) {
                assertTrue(f >= previous, "monotone at $r")
                previous = f
            }
            if (RingProximity.band(r) == Band.SEARCHING) assertEquals(0.0, f, "searching at $r")
        }
    }

    @Test
    fun hostileRSSIFollowsUpstream() {
        // Measured: the band, the distance (metres; nil outside −99…−1 dBm), the feet and the text.
        data class P(val band: Band, val meters: Double?, val text: String?)
        val rows = mapOf(
            null to P(Band.SEARCHING, null, null),
            Int.MIN_VALUE to P(Band.SEARCHING, null, null),
            -1_000 to P(Band.SEARCHING, null, null),
            -101 to P(Band.SEARCHING, null, null),
            -100 to P(Band.SEARCHING, null, null),
            -99 to P(Band.SEARCHING, 39.810717055349734, "≈ 20+ ft"),
            -96 to P(Band.SEARCHING, 30.19951720402016, "≈ 20+ ft"),
            -95 to P(Band.FAR, 27.542287033381662, "≈ 20+ ft"),
            -80 to P(Band.NEARBY, 6.918309709189364, "≈ 20+ ft"),
            -69 to P(Band.NEARBY, 2.51188643150958, "≈ 8 ft"),
            -59 to P(Band.CLOSE, 1.0, "≈ 3 ft"),
            -55 to P(Band.VERY_CLOSE, 0.6918309709189365, "≈ 2 ft"),
            -46 to P(Band.VERY_CLOSE, 0.3019951720402016, "Right here"),
            -1 to P(Band.VERY_CLOSE, 0.004786300923226385, "Right here"),
            0 to P(Band.SEARCHING, null, null),
            127 to P(Band.SEARCHING, null, null),
            Int.MAX_VALUE to P(Band.SEARCHING, null, null),
        )
        for ((r, want) in rows) {
            assertEquals(want.band, RingProximity.band(r), "band $r")
            assertEquals(want.text, RingProximity.distanceText(r), "text $r")
            val m = RingProximity.approximateMeters(r)
            val ft = RingProximity.approximateFeet(r)
            if (want.meters == null) {
                assertNull(m, "meters $r")
                assertNull(ft, "feet $r")
            } else {
                assertTrue(m != null && abs(m - want.meters) <= 1e-12 * want.meters, "meters $r = $m")
                assertEquals(m * 3.280839895, ft, "feet $r")
            }
        }
        // Where the text changes, measured over −100…0 dBm (the first reading of each text, weakest first).
        val firsts = linkedMapOf<String, Int>()
        for (r in -100..0) firsts.putIfAbsent(RingProximity.distanceText(r) ?: "nil", r)
        assertEquals(
            listOf(
                "nil" to -100, "≈ 20+ ft" to -99, "≈ 19 ft" to -78, "≈ 17 ft" to -77, "≈ 16 ft" to -76, "≈ 14 ft" to -75,
                "≈ 13 ft" to -74, "≈ 12 ft" to -73, "≈ 11 ft" to -72, "≈ 10 ft" to -71, "≈ 9 ft" to -70, "≈ 8 ft" to -69,
                "≈ 7 ft" to -67, "≈ 6 ft" to -66, "≈ 5 ft" to -64, "≈ 4 ft" to -62, "≈ 3 ft" to -59, "≈ 2 ft" to -56,
                "Right here" to -50,
            ),
            firsts.toList(),
        )
    }
}
