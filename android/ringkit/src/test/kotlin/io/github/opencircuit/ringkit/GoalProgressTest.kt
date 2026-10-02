package io.github.opencircuit.ringkit

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Goal progress maths and the weekday / weekend goal selection (#77).
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/GoalProgressTests.swift
 * (@ b1c2fdd), all 10 tests.
 *
 * Upstream finds "this week" from the device clock (`Date()`) and a Gregorian `en_US` calendar in the
 * device's time zone, and reads goals from an emptied `UserDefaults(suiteName:)` suite. The port has
 * no ambient clock, zone or store, so the week is fixed and named here: the `en_US` week of Sunday
 * 5 April 2026 in Pacific/Auckland — the week New Zealand's clocks go back (5 April, 03:00 → 02:00).
 * It bites: Saturday 11 April 00:00 there is Friday 12:00 in UTC, and Monday 6 April 00:00 is Sunday
 * 12:00 in UTC, so a weekend rule that ignored the zone would fail both selections. The emptied suite
 * is an empty map-backed `GoalSettings`.
 */
class GoalProgressTest {

    private val zone: ZoneId = ZoneId.of("Pacific/Auckland")
    private val weekSunday: LocalDate = LocalDate.of(2026, 4, 5)
    private val emptySettings = GoalSettings { key -> emptyMap<String, Any>()[key] }

    /** A local midnight on [weekday] (1 = Sunday … 7 = Saturday, Gregorian) of the fixed week. */
    private fun dateOnWeekday(weekday: Int): Instant = weekSunday.plusDays(weekday - 1L).atStartOfDay(zone).toInstant()

    // GoalProgress math

    @Test
    fun fractionAtZeroIsZero() { // :8-12
        val p = GoalProgress(current = 0.0, goal = 8000.0)
        assertEquals(0.0, p.fraction, 0.0001)
        assertFalse(p.met)
    }

    @Test
    fun fractionAtHalf() { // :14-18
        val p = GoalProgress(current = 4000.0, goal = 8000.0)
        assertEquals(0.5, p.fraction, 0.0001)
        assertFalse(p.met)
    }

    @Test
    fun fractionCappedAt1() { // :20-24
        val p = GoalProgress(current = 12000.0, goal = 8000.0)
        assertEquals(1.0, p.fraction, 0.0001)
        assertTrue(p.met)
    }

    @Test
    fun fractionExactlyMet() { // :26-30
        val p = GoalProgress(current = 8000.0, goal = 8000.0)
        assertEquals(1.0, p.fraction, 0.0001)
        assertTrue(p.met)
    }

    @Test
    fun zeroGoalIsZeroFraction() { // :32-36
        val p = GoalProgress(current = 100.0, goal = 0.0)
        assertEquals(0.0, p.fraction, 0.0001)
        assertFalse(p.met)
    }

    // Weekday / weekend selection

    @Test
    fun weekendDetection() { // :50-55
        val saturday = dateOnWeekday(7) // 7 = Saturday in Gregorian
        assertTrue(GoalDefaults.isWeekend(saturday, zone))
    }

    @Test
    fun weekdayDetection() { // :57-62
        val monday = dateOnWeekday(2) // 2 = Monday in Gregorian
        assertFalse(GoalDefaults.isWeekend(monday, zone))
    }

    // Steps goal selection

    @Test
    fun stepsGoalFallsBackToDefault() { // :66-74
        val monday = dateOnWeekday(2) // Monday = weekday
        val goal = GoalDefaults.stepsGoal(monday, zone, emptySettings)
        assertEquals(GoalDefaults.DEFAULT_WORKDAY_STEPS, goal)
    }

    @Test
    fun weekendStepsGoalDefault() { // :76-84
        val saturday = dateOnWeekday(7) // Saturday = weekend
        val goal = GoalDefaults.stepsGoal(saturday, zone, emptySettings)
        assertEquals(GoalDefaults.DEFAULT_WEEKEND_STEPS, goal)
    }

    // DailyGoalProgress

    @Test
    fun dailyProgressSummary() { // :88-99
        val p = DailyGoalProgress(
            steps = GoalProgress(current = 6000.0, goal = 8000.0),
            activeKcal = GoalProgress(current = 150.0, goal = 300.0),
            activityMinutes = GoalProgress(current = 20.0, goal = 30.0),
            sleepMinutes = GoalProgress(current = 420.0, goal = 480.0),
        )
        assertEquals(0.75, p.steps.fraction, 0.0001)
        assertEquals(0.5, p.activeKcal.fraction, 0.0001)
        assertFalse(p.activityMinutes.met)
        assertFalse(p.sleepMinutes.met)
    }
}
