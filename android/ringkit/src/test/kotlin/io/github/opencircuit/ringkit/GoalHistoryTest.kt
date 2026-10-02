package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.GoalHistory.DayInput
import io.github.opencircuit.ringkit.GoalHistory.NapSleep
import io.github.opencircuit.ringkit.GoalHistory.NightSleep
import io.github.opencircuit.ringkit.GoalHistory.Ring
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Historical goal-ring completion (`GoalHistory`) — the derivation behind the ring history strip.
 * Every case here pins a decision that would otherwise be a silent lie on screen: an absent metric
 * must not read as a failed ring, a still-running today must not read as a broken streak, and the
 * weekday/weekend goal split must follow the HISTORICAL date, not today's.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/GoalHistoryTests.swift
 * (@ b1c2fdd), all 29 tests. Upstream already fixes a UTC Gregorian calendar; here it is the UTC zone.
 */
class GoalHistoryTest {

    // A fixed UTC zone so weekday arithmetic is deterministic wherever this runs.
    private val cal: ZoneId = ZoneId.of("UTC")

    private fun day(iso: String): Instant = LocalDate.parse(iso).atStartOfDay(cal).toInstant()

    private val goals = GoalHistory.Goals(
        workdaySteps = 8_000, weekendSteps = 10_000,
        activeKcal = 300.0, activityMinutes = 30.0,
        workdaySleepMin = 420, weekendSleepMin = 480,
    )

    /** A day where every ring is comfortably over its goal. */
    private fun fullDay(iso: String) = DayInput(day(iso), steps = 20_000, activeKcal = 600.0, activityMinutes = 60.0, sleepMinutes = 600)

    // Goal selection

    @Test
    fun weekendGoalAppliesToTheHistoricalDateNotToday() { // :38-44
        // 2026-08-15 is a Saturday, 2026-08-17 a Monday.
        assertEquals(10_000, goals.stepGoal(day("2026-08-15"), cal))
        assertEquals(8_000, goals.stepGoal(day("2026-08-17"), cal))
        assertEquals(480, goals.sleepGoalMinutes(day("2026-08-15"), cal))
        assertEquals(420, goals.sleepGoalMinutes(day("2026-08-17"), cal))
    }

    @Test
    fun sameStepsCanCloseOnAWeekdayAndMissOnTheWeekend() { // :46-54
        val steps = 9_000
        val days = GoalHistory.build(
            listOf(
                DayInput(day("2026-08-14"), steps = steps), // Friday, goal 8000
                DayInput(day("2026-08-15"), steps = steps), // Saturday, goal 10000
            ),
            goals, now = day("2026-08-20"), zone = cal,
        )
        assertTrue(Ring.STEPS in days[0].met)
        assertFalse(Ring.STEPS in days[1].met)
    }

    // Absence is not failure

    @Test
    fun absentMetricIsNotPresentNotMetAndDrawnEmpty() { // :58-70
        val d = GoalHistory.build(listOf(DayInput(day("2026-08-17"), steps = 9_000)), goals, now = day("2026-08-20"), zone = cal)[0]
        assertEquals(setOf(Ring.STEPS), d.present)
        assertEquals(setOf(Ring.STEPS), d.met)
        assertEquals(1, d.ringsMet)
        assertFalse(d.closedAll)
        // The three unmeasured rings read as empty, not as 0 % of a goal the user failed.
        assertEquals(0.0, d.fraction(Ring.ACTIVE_KCAL))
        assertEquals(0.0, d.fraction(Ring.SLEEP_MINUTES))
        assertTrue(d.hasData)
    }

    @Test
    fun dayWithNoDataAtAllHasNoRingsAndCannotClose() { // :72-78
        val d = GoalHistory.build(listOf(DayInput(day("2026-08-17"))), goals, now = day("2026-08-20"), zone = cal)[0]
        assertFalse(d.hasData)
        assertFalse(d.closedAll)
        assertNull(d.attainment)
    }

    @Test
    fun attainmentAveragesOnlyThePresentRings() { // :80-86
        // Steps at half goal, sleep at goal; the other two never measured.
        val d = GoalHistory.build(listOf(DayInput(day("2026-08-17"), steps = 4_000, sleepMinutes = 420)), goals, now = day("2026-08-20"), zone = cal)[0]
        assertEquals(0.75, d.attainment ?: 0.0, 1e-9) // (0.5 + 1.0) / 2, not / 4
    }

    @Test
    fun exceedingAGoalIsCappedAtFullRing() { // :88-94
        val d = GoalHistory.build(listOf(fullDay("2026-08-17")), goals, now = day("2026-08-20"), zone = cal)[0]
        assertEquals(1.0, d.fraction(Ring.STEPS))
        assertEquals(1.0, d.attainment ?: 0.0, 1e-9)
        assertTrue(d.closedAll)
    }

    // Partial day

    @Test
    fun todayIsPartialAndAFutureDayToo() { // :98-107
        val days = GoalHistory.build(
            listOf(
                DayInput(day("2026-08-17"), steps = 20_000),
                DayInput(day("2026-08-18"), steps = 100),
                DayInput(day("2026-08-19"), steps = 0),
            ),
            goals, now = day("2026-08-18").plusSeconds(3_600L * 10), zone = cal,
        )
        assertFalse(days[0].isPartial)
        assertTrue(days[1].isPartial)
        assertTrue(days[2].isPartial)
    }

    @Test
    fun partialDayIsNotCountedAsAClosedDay() { // :109-116
        val s = GoalHistory.summarize(
            GoalHistory.build(listOf(fullDay("2026-08-17"), fullDay("2026-08-18")), goals, now = day("2026-08-18"), zone = cal),
            now = day("2026-08-18"), zone = cal,
        )
        // Both closed, but the 18th is today → only the finished 17th counts in daysAllClosed.
        assertEquals(1, s.daysAllClosed)
    }

    // Summary + streaks

    @Test
    fun streakCountsConsecutiveAllClosedDays() { // :120-130
        // `now` is the day after the newest row: the run reaches yesterday, so it is CURRENT.
        val now = day("2026-08-18")
        val s = GoalHistory.summarize(
            GoalHistory.build(listOf(fullDay("2026-08-15"), fullDay("2026-08-16"), fullDay("2026-08-17")), goals, now = now, zone = cal),
            now = now, zone = cal,
        )
        assertEquals(3, s.currentStreak)
        assertEquals(3, s.longestStreak)
        assertEquals(3, s.daysAllClosed)
        assertEquals(3, s.daysWithData)
    }

    @Test
    fun aMissedDayBreaksTheStreak() { // :132-141
        val days = GoalHistory.build(
            listOf(
                fullDay("2026-08-15"), fullDay("2026-08-16"),
                DayInput(day("2026-08-17"), steps = 10), // missed
                fullDay("2026-08-18"),
            ),
            goals, now = day("2026-08-19"), zone = cal,
        )
        val s = GoalHistory.summarize(days, now = day("2026-08-19"), zone = cal)
        assertEquals(1, s.currentStreak)
        assertEquals(2, s.longestStreak)
    }

    @Test
    fun aCalendarGapBreaksTheStreakEvenThoughTheRowsAreAdjacent() { // :143-151
        // 08-16 is absent from the window entirely — consecutive means consecutive DAYS.
        val now = day("2026-08-18")
        val s = GoalHistory.summarize(
            GoalHistory.build(listOf(fullDay("2026-08-15"), fullDay("2026-08-17")), goals, now = now, zone = cal),
            now = now, zone = cal,
        )
        assertEquals(1, s.currentStreak)
        assertEquals(1, s.longestStreak)
    }

    @Test
    fun anIncompleteTodayDoesNotResetYesterdaysStreak() { // :153-161
        // Today has barely started; yesterday and the day before both closed.
        val now = day("2026-08-18").plusSeconds(3_600L * 9)
        val s = GoalHistory.summarize(
            GoalHistory.build(
                listOf(fullDay("2026-08-16"), fullDay("2026-08-17"), DayInput(day("2026-08-18"), steps = 200)),
                goals, now = now, zone = cal,
            ),
            now = now, zone = cal,
        )
        assertEquals(2, s.currentStreak)
    }

    @Test
    fun anAlreadyClosedTodayExtendsTheStreak() { // :163-169
        val now = day("2026-08-18").plusSeconds(3_600L * 20)
        val s = GoalHistory.summarize(
            GoalHistory.build(listOf(fullDay("2026-08-16"), fullDay("2026-08-17"), fullDay("2026-08-18")), goals, now = now, zone = cal),
            now = now, zone = cal,
        )
        assertEquals(3, s.currentStreak)
    }

    @Test
    fun aMissingDayDoesNotSilentlyCountAsClosed() { // :171-181
        // A sync gap (no data) must break the streak rather than be papered over.
        val s = GoalHistory.summarize(
            GoalHistory.build(
                listOf(
                    fullDay("2026-08-15"),
                    DayInput(day("2026-08-16")), // nothing retained
                    fullDay("2026-08-17"),
                ),
                goals, now = day("2026-08-18"), zone = cal,
            ),
            now = day("2026-08-18"), zone = cal,
        )
        assertEquals(1, s.currentStreak)
        assertEquals(2, s.daysWithData)
    }

    /**
     * THE REGRESSION M4 (upstream's name) — a run that ended days ago is not a CURRENT streak.
     * Before the `now` parameter, rows for 08-15…08-17 opened on 08-20 reported `currentStreak = 3`
     * and the card rendered a green "3 days streak" three days after it stopped.
     */
    @Test
    fun aStreakThatDoesNotReachTodayOrYesterdayIsNotCurrent() { // :187-196
        val built = GoalHistory.build(listOf(fullDay("2026-08-15"), fullDay("2026-08-16"), fullDay("2026-08-17")), goals, now = day("2026-08-20"), zone = cal)
        assertEquals(0, GoalHistory.summarize(built, now = day("2026-08-20"), zone = cal).currentStreak)
        // …but the LONGEST streak is a historical fact and must survive.
        assertEquals(3, GoalHistory.summarize(built, now = day("2026-08-20"), zone = cal).longestStreak)
        // Yesterday still counts as current.
        assertEquals(3, GoalHistory.summarize(built, now = day("2026-08-18"), zone = cal).currentStreak)
    }

    /**
     * THE REGRESSION M1 (upstream's name) — a nap starting before midnight INSIDE a night credited
     * to the next day escaped the double-count guard, because the guard was looked up on the nap's
     * own start day while nights are keyed by WAKE day.
     */
    @Test
    fun aNapInsideACreditedNightIsExcludedEvenAcrossMidnight() { // :202-224
        val night = NightSleep(
            nightKey = day("2026-08-17"),
            inBedStart = day("2026-08-16").plusSeconds(23L * 3_600),
            inBedEnd = day("2026-08-17").plusSeconds(7L * 3_600),
            asleepMinutes = 400,
        )
        fun credit(napStartHour: Double): Map<Instant, Int> {
            val start = day("2026-08-16").plusMillis((napStartHour * 3_600_000).toLong())
            return GoalHistory.sleepCreditByDay(
                nights = listOf(night),
                naps = listOf(NapSleep(start = start, end = start.plusSeconds(3_600), asleepMinutes = 55)),
                zone = cal,
            )
        }
        // 23:30 on the 16th — inside the night credited to the 17th. Must NOT create a 16th ring.
        assertEquals(mapOf(day("2026-08-17") to 400), credit(napStartHour = 23.5))
        // The same nap 60 min later is on the other side of midnight and was already handled.
        assertEquals(mapOf(day("2026-08-17") to 400), credit(napStartHour = 24.5))
        // A genuine afternoon nap on the 16th still counts.
        assertEquals(mapOf(day("2026-08-17") to 400, day("2026-08-16") to 55), credit(napStartHour = 14.0))
    }

    /** A row dated in the FUTURE (ring or phone clock skew) is not a current streak either. */
    @Test
    fun aFutureDatedRowIsNotACurrentStreak() { // :228-232
        val built = GoalHistory.build(listOf(fullDay("2026-08-25")), goals, now = day("2026-08-20"), zone = cal)
        assertEquals(0, GoalHistory.summarize(built, now = day("2026-08-20"), zone = cal).currentStreak)
    }

    @Test
    fun perRingCountsSeparateMetFromMeasured() { // :234-245
        val days = GoalHistory.build(
            listOf(
                DayInput(day("2026-08-17"), steps = 20_000, sleepMinutes = 100),
                DayInput(day("2026-08-18"), steps = 100),
            ),
            goals, now = day("2026-08-20"), zone = cal,
        )
        val s = GoalHistory.summarize(days, now = day("2026-08-20"), zone = cal)
        assertEquals(2, s.dataCounts[Ring.STEPS])
        assertEquals(1, s.metCounts[Ring.STEPS])
        assertEquals(1, s.dataCounts[Ring.SLEEP_MINUTES]) // measured once
        assertNull(s.metCounts[Ring.SLEEP_MINUTES]) // never met
        assertNull(s.dataCounts[Ring.ACTIVE_KCAL]) // never measured
    }

    // Ordering / shape

    @Test
    fun buildSortsOldestToNewestAndNormalisesToStartOfDay() { // :249-256
        val noon = day("2026-08-17").plusSeconds(3_600L * 12)
        val days = GoalHistory.build(
            listOf(DayInput(day("2026-08-18"), steps = 1), DayInput(noon, steps = 1)),
            goals, now = day("2026-08-20"), zone = cal,
        )
        assertEquals(listOf(day("2026-08-17"), day("2026-08-18")), days.map { it.date })
    }

    @Test
    fun emptyWindowSummarisesToZeroes() { // :258-263
        val s = GoalHistory.summarize(emptyList(), now = day("2026-08-20"), zone = cal)
        assertEquals(0, s.daysWithData)
        assertEquals(0, s.currentStreak)
        assertEquals(0, s.longestStreak)
    }

    // Sleep credit (wake-day attribution + naps)

    private fun at(iso: String, h: Int, m: Int = 0): Instant = day(iso).plusSeconds(h * 3_600L + m * 60L)

    @Test
    fun nightIsCreditedToTheDayItEndedNotTheDayItStarted() { // :271-281
        // In bed 23:40 on the 16th, awake 07:20 on the 17th → the 17th's Sleep ring.
        val credit = GoalHistory.sleepCreditByDay(
            nights = listOf(NightSleep(day("2026-08-16"), at("2026-08-16", 23, 40), at("2026-08-17", 7, 20), 430)),
            naps = emptyList(), zone = cal,
        )
        assertEquals(430, credit[day("2026-08-17")])
        assertNull(credit[day("2026-08-16")])
    }

    @Test
    fun nightWhollyInsideOneDayStaysOnThatDay() { // :283-291
        val credit = GoalHistory.sleepCreditByDay(
            nights = listOf(NightSleep(day("2026-08-17"), at("2026-08-17", 1, 0), at("2026-08-17", 8, 0), 400)),
            naps = emptyList(), zone = cal,
        )
        assertEquals(400, credit[day("2026-08-17")])
    }

    @Test
    fun legacyNightWithNoClockTimeFallsBackToItsNightKey() { // :293-301
        // No in-bed window stored → credited to the night key, never drifted onto a later day.
        val credit = GoalHistory.sleepCreditByDay(
            nights = listOf(NightSleep(day("2026-08-16"), inBedStart = null, inBedEnd = null, asleepMinutes = 400)),
            naps = emptyList(), zone = cal,
        )
        assertEquals(400, credit[day("2026-08-16")])
        assertNull(credit[day("2026-08-17")])
    }

    @Test
    fun zeroMinuteNightContributesNoCreditKey() { // :303-310
        val credit = GoalHistory.sleepCreditByDay(
            nights = listOf(NightSleep(day("2026-08-16"), at("2026-08-16", 23, 0), at("2026-08-17", 7, 0), asleepMinutes = 0)),
            naps = emptyList(), zone = cal,
        )
        assertTrue(credit.isEmpty())
    }

    @Test
    fun napAddsToTheDayItStartedOn() { // :312-321
        val credit = GoalHistory.sleepCreditByDay(
            nights = listOf(NightSleep(day("2026-08-16"), at("2026-08-16", 23, 0), at("2026-08-17", 7, 0), 400)),
            naps = listOf(NapSleep(at("2026-08-17", 14, 0), at("2026-08-17", 14, 45), 40)),
            zone = cal,
        )
        assertEquals(440, credit[day("2026-08-17")])
    }

    @Test
    fun napOverlappingTheCreditedNightIsExcluded() { // :323-334
        // A manually-added "nap" sitting inside the night must not double-count (no auto-detection
        // night guard exists for a manual nap).
        val credit = GoalHistory.sleepCreditByDay(
            nights = listOf(NightSleep(day("2026-08-17"), at("2026-08-17", 1, 0), at("2026-08-17", 8, 0), 400)),
            naps = listOf(NapSleep(at("2026-08-17", 3, 0), at("2026-08-17", 4, 0), 55)),
            zone = cal,
        )
        assertEquals(400, credit[day("2026-08-17")])
    }

    @Test
    fun napOnADayWithNoNightStillCounts() { // :336-343
        val credit = GoalHistory.sleepCreditByDay(
            nights = emptyList(),
            naps = listOf(NapSleep(at("2026-08-17", 13, 0), at("2026-08-17", 14, 0), 50)),
            zone = cal,
        )
        assertEquals(50, credit[day("2026-08-17")])
    }

    @Test
    fun creditFeedsTheSleepRing() { // :345-358
        // End-to-end: the wake-day credit is what the Sleep ring is scored on.
        val credit = GoalHistory.sleepCreditByDay(
            nights = listOf(NightSleep(day("2026-08-16"), at("2026-08-16", 23, 0), at("2026-08-17", 7, 30), 425)),
            naps = emptyList(), zone = cal,
        )
        val d = GoalHistory.build(
            listOf(DayInput(day("2026-08-17"), sleepMinutes = credit[day("2026-08-17")])),
            goals, now = day("2026-08-20"), zone = cal,
        )[0]
        assertTrue(Ring.SLEEP_MINUTES in d.present)
        assertTrue(Ring.SLEEP_MINUTES in d.met) // 425 ≥ the 420-minute weekday goal
    }

    // Parity with the Today card

    /**
     * The history builder must produce exactly the `DailyGoalProgress` the Goals card builds for
     * the same numbers, or "today" and "today, in the strip" could disagree on screen.
     */
    @Test
    fun progressMatchesTheGoalsCardConstruction() { // :364-375
        val input = DayInput(day("2026-08-17"), steps = 6_000, activeKcal = 150.0, activityMinutes = 20.0, sleepMinutes = 210)
        val built = GoalHistory.build(listOf(input), goals, now = day("2026-08-20"), zone = cal)[0]
        val expected = DailyGoalProgress(
            steps = GoalProgress(current = 6_000.0, goal = 8_000.0),
            activeKcal = GoalProgress(current = 150.0, goal = 300.0),
            activityMinutes = GoalProgress(current = 20.0, goal = 30.0),
            sleepMinutes = GoalProgress(current = 210.0, goal = 420.0),
        )
        assertEquals(expected, built.progress)
    }
}
