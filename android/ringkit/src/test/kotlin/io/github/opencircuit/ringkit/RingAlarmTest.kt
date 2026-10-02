package io.github.opencircuit.ringkit

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The alarm's scheduling rules — port of upstream
 * ios/OpenCircuitKit/Tests/OpenCircuitKitTests/RingAlarmTests.swift (@ b1c2fdd), suite `RingAlarmTests`.
 * Everything here is pure calendar arithmetic against a FIXED zone and explicit instants — no clock, no
 * BLE — so a failure means the rule is wrong rather than the environment being odd.
 *
 * Upstream's two command-byte tests (`:24`, `:31`) live in `RingVibrationTest`. `roundTripsThroughJSON`
 * (`:174`) needs the alarm's stored form, which belongs to the storage layer, and is not ported (named in
 * `PORTING.md`). The zone is passed explicitly everywhere, as upstream's tests pass their calendar: UTC
 * (upstream's pinned choice) except the two clock-change tests, which name New York and pick the 2026
 * transition days so they bite.
 */
class RingAlarmTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val newYork: ZoneId = ZoneId.of("America/New_York")

    private fun date(y: Int, m: Int, d: Int, hh: Int, mm: Int, zone: ZoneId = utc): Instant =
        LocalDateTime.of(y, m, d, hh, mm).atZone(zone).toInstant()

    // MARK: - Firing

    @Test
    fun firesOnceInsideTheGraceWindow() { // RingAlarmTests.swift:43-61
        val alarm = RingAlarm(isEnabled = true, hour = 7, minute = 0)
        val scheduled = date(2026, 9, 1, 7, 0)

        // 90 s of runtime after the alarm time: fire, and report the lateness truthfully.
        val d = RingAlarmSchedule.decide(alarm, now = date(2026, 9, 1, 7, 1), lastHandledAt = null, zone = utc)
        assertEquals(RingAlarmDecision.Fire(scheduled = scheduled, lateBy = 60.0), d)

        // Having handled that occurrence, every later wake-up in the same morning is a no-op.
        // This is the property that stops a 60-second keepalive from buzzing the user 15 times.
        for (minute in 1..14) {
            val again = RingAlarmSchedule.decide(alarm, now = date(2026, 9, 1, 7, minute), lastHandledAt = scheduled, zone = utc)
            assertEquals(RingAlarmDecision.Idle, again)
        }
    }

    @Test
    fun pastTheGraceWindowItIsMissedNotDelivered() { // RingAlarmTests.swift:63-71
        val alarm = RingAlarm(isEnabled = true, hour = 7, minute = 0)
        // 07:16 — the app finally got runtime, but waking someone 16 minutes late is worse than
        // not waking them, so this must be recorded rather than delivered.
        val d = RingAlarmSchedule.decide(alarm, now = date(2026, 9, 1, 7, 16), lastHandledAt = null, zone = utc)
        assertEquals(RingAlarmDecision.Missed(scheduled = date(2026, 9, 1, 7, 0)), d)
    }

    @Test
    fun exactlyAtTheGraceBoundaryStillFires() { // RingAlarmTests.swift:73-79
        val alarm = RingAlarm(isEnabled = true, hour = 7, minute = 0)
        val d = RingAlarmSchedule.decide(alarm, now = date(2026, 9, 1, 7, 15), lastHandledAt = null, zone = utc)
        assertEquals(RingAlarmDecision.Fire(scheduled = date(2026, 9, 1, 7, 0), lateBy = 15 * 60.0), d)
    }

    @Test
    fun beforeTheAlarmTimeNothingHappens() { // RingAlarmTests.swift:81-89
        val alarm = RingAlarm(isEnabled = true, hour = 7, minute = 0)
        // 06:59 on a day the alarm runs. The most recent occurrence is YESTERDAY 07:00, which was
        // already handled — the rule must not treat "yesterday" as due.
        val d = RingAlarmSchedule.decide(alarm, now = date(2026, 9, 1, 6, 59), lastHandledAt = date(2026, 8, 31, 7, 0), zone = utc)
        assertEquals(RingAlarmDecision.Idle, d)
    }

    @Test
    fun aDisabledAlarmNeverFires() { // RingAlarmTests.swift:91-96
        val alarm = RingAlarm(isEnabled = false, hour = 7, minute = 0)
        assertEquals(RingAlarmDecision.Idle, RingAlarmSchedule.decide(alarm, now = date(2026, 9, 1, 7, 1), lastHandledAt = null, zone = utc))
    }

    // MARK: - Weekdays

    @Test
    fun weekdaySelectionIsRespected() { // RingAlarmTests.swift:100-116
        // 2026-09-01 is a Tuesday (weekday 3). Arm the alarm for Mondays only.
        assertEquals(3, CalendarDay.weekday(date(2026, 9, 1, 7, 0), utc))
        val mondayOnly = RingAlarm(isEnabled = true, hour = 7, minute = 0, weekdays = setOf(2))

        // Tuesday 07:01 — the most recent MONDAY occurrence is 24 h back, far outside grace.
        val d = RingAlarmSchedule.decide(mondayOnly, now = date(2026, 9, 1, 7, 1), lastHandledAt = date(2026, 8, 31, 7, 0), zone = utc)
        assertEquals(RingAlarmDecision.Idle, d)

        // The following Monday it fires.
        val onMonday = RingAlarmSchedule.decide(mondayOnly, now = date(2026, 9, 7, 7, 1), lastHandledAt = date(2026, 8, 31, 7, 0), zone = utc)
        assertEquals(RingAlarmDecision.Fire(scheduled = date(2026, 9, 7, 7, 0), lateBy = 60.0), onMonday)
    }

    @Test
    fun anEmptyWeekdaySetMeansEveryDay() { // RingAlarmTests.swift:118-121
        val alarm = RingAlarm(isEnabled = true, hour = 7, minute = 0, weekdays = emptySet())
        for (weekday in 1..7) assertTrue(alarm.repeats(weekday))
    }

    @Test
    fun nextOccurrenceSkipsToday() { // RingAlarmTests.swift:123-130
        val alarm = RingAlarm(isEnabled = true, hour = 7, minute = 0)
        // Asked at 07:30, "next" is tomorrow — not the one that already went off this morning.
        val next = RingAlarmSchedule.nextOccurrence(date(2026, 9, 1, 7, 30), alarm, utc)
        assertEquals(date(2026, 9, 2, 7, 0), next)
    }

    // MARK: - Daylight saving

    /**
     * The alarm must land on the wall clock the user set, not on a fixed 86 400 s offset. On
     * 2026-11-01 US clocks go back an hour; a naive "yesterday = now − 86400" would slide a 07:00
     * alarm to 06:00 and fire it an hour early.
     */
    @Test
    fun survivesAFallBackTransition() { // RingAlarmTests.swift:137-149
        val alarm = RingAlarm(isEnabled = true, hour = 7, minute = 0)
        val now = date(2026, 11, 1, 7, 1, zone = newYork) // the morning clocks went back
        val d = RingAlarmSchedule.decide(alarm, now = now, lastHandledAt = null, zone = newYork)
        if (d !is RingAlarmDecision.Fire) fail("expected the alarm to fire, got $d")
        assertEquals(60.0, d.lateBy)
        // 07:00 local, whatever that is in absolute time today.
        val parts = d.scheduled.atZone(newYork)
        assertEquals(7, parts.hour)
        assertEquals(0, parts.minute)
    }

    /**
     * Spring forward: 02:30 does not exist on 2026-03-08 in New York. The alarm must resolve to a
     * real instant rather than vanishing for the day.
     */
    @Test
    fun aNonexistentLocalTimeStillResolves() { // RingAlarmTests.swift:153-160
        val alarm = RingAlarm(isEnabled = true, hour = 2, minute = 30)
        val now = date(2026, 3, 8, 12, 0, zone = newYork)
        val occurrence = RingAlarmSchedule.mostRecentOccurrence(now, alarm, newYork)
        assertNotNull(occurrence)
    }

    // MARK: - Clamps

    @Test
    fun burstSettingsAreClamped() { // RingAlarmTests.swift:164-172
        var alarm = RingAlarm(isEnabled = true, burstCount = 99, burstSpacing = 0.1)
        assertEquals(10, alarm.clampedBurstCount)
        assertEquals(2.0, alarm.clampedBurstSpacing)
        alarm = alarm.copy(burstCount = 0, burstSpacing = 600.0)
        assertEquals(1, alarm.clampedBurstCount)
        assertEquals(30.0, alarm.clampedBurstSpacing)
    }
}

/**
 * The pre-alarm warm-up window — the cue that tells the ring session to start holding the BLE link open
 * so the buzz lands in seconds rather than on the ring's next spontaneous push. Port of upstream
 * ios/OpenCircuitKit/Tests/OpenCircuitKitTests/RingAlarmTests.swift (@ b1c2fdd), suite
 * `RingAlarmWarmUpTests`, with upstream's pinned UTC zone.
 */
class RingAlarmWarmUpTest {

    private val utc: ZoneId = ZoneOffset.UTC

    private fun date(y: Int, m: Int, d: Int, hh: Int, mm: Int): Instant = LocalDateTime.of(y, m, d, hh, mm).atZone(utc).toInstant()

    @Test
    fun armsInsideTheWindowAndNotBefore() { // RingAlarmTests.swift:199-213
        val alarm = RingAlarm(isEnabled = true, hour = 7, minute = 0)
        val target = date(2026, 9, 1, 7, 0)

        // 06:54 — one minute too early. Holding the radio open longer than the problem needs is
        // the cost side of this feature, so the boundary matters.
        assertNull(RingAlarmSchedule.warmUpTarget(alarm, now = date(2026, 9, 1, 6, 54), zone = utc))
        // 06:55 — exactly at the window edge.
        assertEquals(target, RingAlarmSchedule.warmUpTarget(alarm, now = date(2026, 9, 1, 6, 55), zone = utc))
        assertEquals(target, RingAlarmSchedule.warmUpTarget(alarm, now = date(2026, 9, 1, 6, 59), zone = utc))
    }

    @Test
    fun neverArmsForADisabledAlarm() { // RingAlarmTests.swift:215-219
        assertNull(RingAlarmSchedule.warmUpTarget(RingAlarm(isEnabled = false, hour = 7, minute = 0), now = date(2026, 9, 1, 6, 58), zone = utc))
    }

    @Test
    fun doesNotArmForADayTheAlarmSkips() { // RingAlarmTests.swift:221-230
        // Mondays only; 2026-09-01 is a Tuesday, so the next occurrence is six days out.
        val alarm = RingAlarm(isEnabled = true, hour = 7, minute = 0, weekdays = setOf(2))
        assertNull(RingAlarmSchedule.warmUpTarget(alarm, now = date(2026, 9, 1, 6, 58), zone = utc))
        // On the Monday it arms as usual.
        assertEquals(date(2026, 9, 7, 7, 0), RingAlarmSchedule.warmUpTarget(alarm, now = date(2026, 9, 7, 6, 58), zone = utc))
    }

    @Test
    fun stopsArmingOnceTheAlarmTimeHasPassed() { // RingAlarmTests.swift:232-239
        val alarm = RingAlarm(isEnabled = true, hour = 7, minute = 0)
        // 07:01 — `nextOccurrence` has rolled to tomorrow, which is nowhere near its window, so the
        // hold releases instead of running all day.
        assertNull(RingAlarmSchedule.warmUpTarget(alarm, now = date(2026, 9, 1, 7, 1), zone = utc))
    }
}
