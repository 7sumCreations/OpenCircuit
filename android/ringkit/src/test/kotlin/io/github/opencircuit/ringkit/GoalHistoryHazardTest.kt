package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.GoalHistory.DayInput
import io.github.opencircuit.ringkit.GoalHistory.NapSleep
import io.github.opencircuit.ringkit.GoalHistory.NightSleep
import io.github.opencircuit.ringkit.GoalHistory.Ring
import org.junit.jupiter.api.Timeout
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kotlin-only hostile-input checks for goal-ring history: what the upstream vectors never feed in.
 * The day rollups and the sleep rows are stored values, so here they arrive duplicated, unsorted,
 * dated in the future, with zero, negative or 32-bit-overflowing minutes, with reversed in-bed and
 * nap windows, with energy or minutes that are not a number, at the far end of `Instant`'s range,
 * and as a history centuries long; and every zone question is asked at both 2026 clock changes in
 * New York, London and Santiago (whose 6 September has no midnight). Kept out of the upstream-port
 * class so its count stays exact.
 *
 * Every upstream outcome quoted below was measured on the pinned Swift build (Swift 6.3.2). Where the
 * port deliberately differs the test says so, and `PORTING.md` records why.
 */
class GoalHistoryHazardTest {

    private val utc: ZoneId = ZoneId.of("UTC")
    private val newYork: ZoneId = ZoneId.of("America/New_York")
    private val london: ZoneId = ZoneId.of("Europe/London")
    private val santiago: ZoneId = ZoneId.of("America/Santiago")
    private val goals = GoalHistory.Goals()
    private val d15: Instant = Instant.parse("2026-08-15T00:00:00Z")
    private fun day(k: Long): Instant = d15.plus(Duration.ofDays(k))
    private fun full(date: Instant) = DayInput(date, steps = 20_000, activeKcal = 600.0, activityMinutes = 60.0, sleepMinutes = 600)
    private fun ms(t: Long): Instant = Instant.ofEpochMilli(t)

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun aHistoryCenturiesLongIsCreditedQuicklyAndExactly() {
        // Upstream tests every nap against every credited night: measured on its debug build, 1 000
        // nights and naps take 0.013 s, 5 000 take 0.26 s, 20 000 (55 years) take 4.2 s — quadratic.
        // The port finds an overlapping night by binary search over the wake-day windows sorted by
        // start (with a running latest end), which gives the same answer for every input. Here: 200 000
        // nights in bed 22:00 → 06:00 (credited to the wake day), a 14:00 nap on every day, and a 23:00
        // nap inside every tenth night (excluded).
        val n = 200_000
        val base = Instant.parse("2001-09-09T00:00:00Z")
        val nights = (0 until n).map { k ->
            val key = base.plus(Duration.ofDays(k.toLong()))
            NightSleep(key, key.plusSeconds(79_200), key.plusSeconds(108_000), 420)
        }
        val naps = (0 until n).map { k ->
            val dayStart = base.plus(Duration.ofDays(k.toLong()))
            if (k % 10 == 0) NapSleep(dayStart.plusSeconds(82_800), dayStart.plusSeconds(84_600), 30)
            else NapSleep(dayStart.plusSeconds(50_400), dayStart.plusSeconds(52_200), 25)
        }
        val credit = GoalHistory.sleepCreditByDay(nights, naps, utc)
        assertEquals(n, credit.size, "every wake day; the first day's only nap sits inside a night")
        var total = 0L
        for ((d, minutes) in credit) {
            val k = Duration.between(base, d).toDays()
            val expected = (if (k >= 1) 420 else 0) + (if (k in 0 until n && k % 10 != 0L) 25 else 0)
            assertEquals(expected, minutes, "day $k")
            total += minutes
        }
        assertEquals(420L * n + 25L * (n - n / 10), total)
        assertEquals(credit.keys.sorted(), credit.keys.toList(), "credit map iterates oldest day first")
    }

    @Test
    fun aDaysCreditSaturatesInsteadOfWrapping() {
        // Upstream adds minutes in 64 bits: two nights of Int32.max minutes on one day plus a nap of
        // Int32.max read 6 442 450 941 (measured). A Kotlin `Int` sum would wrap to a negative credit.
        // The port adds in 64 bits and saturates at Int.MAX_VALUE, which every goal comparison reads
        // the same way (Int.MAX_VALUE is at least any `Int` goal).
        val big = Int.MAX_VALUE
        val credit = GoalHistory.sleepCreditByDay(
            listOf(NightSleep(day(0), null, null, big), NightSleep(day(0), null, null, big)),
            listOf(NapSleep(day(0).plusSeconds(3600), day(0).plusSeconds(7200), big)),
            utc,
        )
        assertEquals(mapOf(day(0) to Int.MAX_VALUE), credit)
        val ring = GoalHistory.build(listOf(DayInput(day(2), sleepMinutes = credit[day(0)])), goals, day(5), utc).single()
        assertTrue(Ring.SLEEP_MINUTES in ring.met)
    }

    @Test
    fun sleepRowsWithNoMinutesReversedWindowsAndNoClockAsUpstream() {
        // Measured: a night whose in-bed end is not after its start has no clock (credited to its
        // night key, guards nothing); zero and negative minutes credit nothing; a reversed nap is
        // credited on its start day; a zero-length nap counts; two clocked nights on one wake day
        // widen one guard window that excludes a nap between them.
        val credit = GoalHistory.sleepCreditByDay(
            listOf(
                NightSleep(day(0), day(1), day(0).plusSeconds(3600), 300),
                NightSleep(day(2), day(2), day(2), 200),
                NightSleep(day(3), null, day(3).plusSeconds(30_000), 100),
                NightSleep(day(4), day(4), day(4).plusSeconds(30_000), -50),
                NightSleep(day(5), day(5), day(5).plusSeconds(30_000), 0),
            ),
            listOf(
                NapSleep(day(6).plusSeconds(7200), day(6).plusSeconds(3600), 40),
                NapSleep(day(7), day(7).plusSeconds(3600), -10),
                NapSleep(day(0).plusSeconds(1800), day(0).plusSeconds(1800), 15),
            ),
            utc,
        )
        assertEquals(mapOf(day(0) to 315, day(2) to 200, day(3) to 100, day(6) to 40), credit)
        val widened = GoalHistory.sleepCreditByDay(
            listOf(
                NightSleep(day(0), day(0).plusSeconds(3600), day(0).plusSeconds(7200), 50),
                NightSleep(day(0), day(0).plusSeconds(36_000), day(0).plusSeconds(39_600), 60),
            ),
            listOf(NapSleep(day(0).plusSeconds(18_000), day(0).plusSeconds(19_800), 25)),
            utc,
        )
        assertEquals(mapOf(day(0) to 110), widened)
    }

    @Test
    fun sleepRowsAtTheEndOfTimeAreLeftOutInsteadOfThrowing() {
        // Kotlin-only: a wake time or nap `java.time` cannot place in the zone (the last year of
        // `Instant`'s range) has no calendar day. The row is left out of the credit (a Swift `Date`
        // has no end; upstream keys it on a day no calendar shows).
        val tokyo = ZoneId.of("Asia/Tokyo")
        val credit = GoalHistory.sleepCreditByDay(
            listOf(NightSleep(Instant.MAX, null, null, 400), NightSleep(day(0), day(0).plusSeconds(3600), day(0).plusSeconds(7200), 300)),
            listOf(NapSleep(Instant.MAX.minusSeconds(10), Instant.MAX, 20), NapSleep(day(1), day(1).plusSeconds(600), 10)),
            tokyo,
        )
        assertEquals(mapOf(day(0).minusSeconds(9 * 3600) to 300, day(1).minusSeconds(9 * 3600) to 10), credit)
        // build leaves out a rollup it cannot place, and a `now` it cannot place is compared as is.
        val days = GoalHistory.build(listOf(full(Instant.MAX), full(day(0))), goals, Instant.MAX, tokyo)
        assertEquals(listOf(day(0).minusSeconds(9 * 3600)), days.map { it.date })
        assertFalse(days.single().isPartial)
        assertEquals(1, GoalHistory.summarize(days, Instant.MAX, tokyo).longestStreak)
        assertEquals(0, GoalHistory.summarize(days, Instant.MAX, tokyo).currentStreak, "a now no zone can place is not today")
    }

    @Test
    fun buildTreatsEnergyOrMinutesThatAreNotANumberAsUnmeasured() {
        // Upstream marks a ring "present" whenever the value is non-nil: a NaN energy (the daily
        // estimate's answer for a NaN body weight) makes the day's mean attainment NaN, an infinite one
        // closes the ring (measured: attainment NaN; ∞ kcal met). The port treats an energy or minutes
        // value that is not finite as unmeasured — the ring is drawn empty and not counted — the same
        // rule the vitals baselines apply to an unreadable reading. Steps and sleep are integers.
        val nan = GoalHistory.build(listOf(DayInput(day(2), steps = 4000, activeKcal = Double.NaN, activityMinutes = 30.0)), goals, day(9), utc).single()
        assertEquals(setOf(Ring.STEPS, Ring.ACTIVITY_MINUTES), nan.present)
        assertEquals(0.75, nan.attainment)
        assertEquals(0.0, nan.fraction(Ring.ACTIVE_KCAL))
        val inf = GoalHistory.build(listOf(DayInput(day(2), activeKcal = Double.POSITIVE_INFINITY, activityMinutes = Double.NEGATIVE_INFINITY)), goals, day(9), utc).single()
        assertFalse(inf.hasData)
        assertNull(inf.attainment)
        assertEquals(emptySet(), inf.met)

        // Negative stored values stay measured (a negative count is a value, not a missing one) and
        // their rings read empty rather than below zero (upstream: −0.0125 steps, −0.1667 minutes).
        val negative = GoalHistory.build(listOf(DayInput(day(2), steps = -100, activeKcal = -5.0, activityMinutes = -5.0, sleepMinutes = Int.MIN_VALUE)), goals, day(9), utc).single()
        assertEquals(Ring.entries.toSet(), negative.present)
        assertEquals(0.0, negative.attainment)

        // Property: over every combination of hostile and ordinary values the mean attainment is null
        // exactly when nothing is measured, and otherwise a number in [0, 1].
        val doubles = listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0, -0.0, 0.0, 150.0, 300.0, 1e308)
        val ints = listOf(null, Int.MIN_VALUE, -1, 0, 4000, 8000, Int.MAX_VALUE)
        for (steps in ints) for (kcal in doubles) for (minutes in doubles) for (sleep in ints) {
            val d = GoalHistory.build(listOf(DayInput(day(2), steps, kcal, minutes, sleep)), goals, day(9), utc).single()
            val a = d.attainment
            if (d.present.isEmpty()) assertNull(a) else assertTrue(a != null && a >= 0.0 && a <= 1.0, "$steps $kcal $minutes $sleep → $a")
            assertTrue(d.present.containsAll(d.met))
        }
    }

    @Test
    fun goalsOfZeroOrBelowOrUnreadableNeverCloseARing() {
        // Measured: workday steps −1, energy NaN, minutes ∞ and sleep 0 — no ring closes, attainment 0.
        val hostileGoals = GoalHistory.Goals(workdaySteps = -1, activeKcal = Double.NaN, activityMinutes = Double.POSITIVE_INFINITY, workdaySleepMin = 0)
        val d = GoalHistory.build(listOf(DayInput(day(3), steps = 50, activeKcal = 10.0, activityMinutes = 5.0, sleepMinutes = 10)), hostileGoals, day(9), utc).single()
        assertEquals(Ring.entries.toSet(), d.present)
        assertEquals(emptySet(), d.met)
        assertEquals(0.0, d.attainment)
    }

    @Test
    fun duplicatedUnsortedAndFutureRowsSummariseAsUpstream() {
        // Measured: a duplicated day is two rows (not adjacent to each other, so a run restarts) —
        // current 2, longest 2, 4 closed; summarize expects build's oldest-first order, and reversed
        // rows give current 0, longest 1; a row dated after now is partial, and the run it ends is not
        // current (current 0, longest 2, 2 closed).
        val dup = GoalHistory.summarize(GoalHistory.build(listOf(full(day(0)), full(day(1)), full(day(1)), full(day(2))), goals, day(3), utc), day(3), utc)
        assertEquals(listOf(2, 2, 4, 4), listOf(dup.currentStreak, dup.longestStreak, dup.daysAllClosed, dup.daysWithData))
        assertEquals(Ring.entries.associateWith { 4 }, dup.metCounts)
        val reversed = GoalHistory.build(listOf(full(day(0)), full(day(1)), full(day(2))), goals, day(3), utc).reversed()
        val rev = GoalHistory.summarize(reversed, day(3), utc)
        assertEquals(listOf(0, 1), listOf(rev.currentStreak, rev.longestStreak))
        val future = GoalHistory.build(listOf(full(day(0)), full(day(1)), full(day(9))), goals, day(2), utc)
        assertEquals(listOf(false, false, true), future.map { it.isPartial })
        val fut = GoalHistory.summarize(future, day(2), utc)
        assertEquals(listOf(0, 2, 2), listOf(fut.currentStreak, fut.longestStreak, fut.daysAllClosed))
        // Days built in UTC and summarised in New York or Tokyo keep their streak (measured 3 / 3).
        val builtUtc = GoalHistory.build(listOf(full(day(0)), full(day(1)), full(day(2))), goals, day(3), utc)
        assertEquals(3, GoalHistory.summarize(builtUtc, day(3), newYork).currentStreak)
        assertEquals(3, GoalHistory.summarize(builtUtc, day(3).minusSeconds(6 * 3600), ZoneId.of("Asia/Tokyo")).currentStreak)
    }

    @Test
    fun dayStartsAndWeekendGoalsAtBothClockChangesInThreeZones() {
        // Measured day starts (epoch ms) for noon inputs, as upstream's `startOfDay`; Santiago's
        // 6 September starts at 01:00, the first instant after its missing midnight.
        class Zone(val zone: ZoneId, val noons: List<String>, val starts: List<Long>, val weekend: List<Boolean>)
        val zones = listOf(
            Zone(newYork, listOf("2026-03-06T17:00:00Z", "2026-03-07T17:00:00Z", "2026-03-08T16:00:00Z", "2026-03-09T16:00:00Z", "2026-10-31T16:00:00Z", "2026-11-01T17:00:00Z", "2026-11-02T17:00:00Z"),
                listOf(1772773200000, 1772859600000, 1772946000000, 1773028800000, 1793419200000, 1793505600000, 1793595600000),
                listOf(false, true, true, false, true, true, false)),
            Zone(london, listOf("2026-03-28T12:00:00Z", "2026-03-29T11:00:00Z", "2026-03-30T11:00:00Z", "2026-10-24T11:00:00Z", "2026-10-25T12:00:00Z", "2026-10-26T12:00:00Z"),
                listOf(1774656000000, 1774742400000, 1774825200000, 1792796400000, 1792882800000, 1792972800000),
                listOf(true, true, false, true, true, false)),
            Zone(santiago, listOf("2026-04-04T15:00:00Z", "2026-04-05T16:00:00Z", "2026-04-06T16:00:00Z", "2026-09-05T16:00:00Z", "2026-09-06T15:00:00Z", "2026-09-07T15:00:00Z"),
                listOf(1775271600000, 1775361600000, 1775448000000, 1788580800000, 1788667200000, 1788750000000),
                listOf(true, true, false, true, true, false)),
        )
        for (z in zones) {
            val noons = z.noons.map { Instant.parse(it) }
            val built = GoalHistory.build(noons.map { DayInput(it, steps = 9_000) }, goals, noons.first(), z.zone)
            assertEquals(z.starts.map(::ms), built.map { it.date }, "${z.zone} day starts")
            assertEquals(z.weekend, built.map { GoalDefaults.isWeekend(it.date, z.zone) }, "${z.zone} weekends")
            assertEquals(z.weekend.map { !it }, built.map { Ring.STEPS in it.met }, "${z.zone}: 9 000 steps close a workday only")
            assertTrue(built.all { it.isPartial }, "now = the first noon: every day is today or later")
            // The 400-minute night ending the morning after the change goes to that day; a nap starting
            // 15 minutes before that day begins sits inside the night; an afternoon nap counts.
            val wakeDay = ms(z.starts[1])
            val credit = GoalHistory.sleepCreditByDay(
                listOf(NightSleep(wakeDay, wakeDay.minusSeconds(1800), wakeDay.plusSeconds(7 * 3600), 400)),
                listOf(NapSleep(wakeDay.minusSeconds(900), wakeDay.plusSeconds(900), 20), NapSleep(ms(z.starts[2]).plusSeconds(14 * 3600), ms(z.starts[2]).plusSeconds(15 * 3600), 30)),
                z.zone,
            )
            assertEquals(mapOf(wakeDay to 400, ms(z.starts[2]) to 30), credit, "${z.zone} credit")
        }
    }

    @Test
    fun aStreakRunsAcrossADayWithoutAMidnight() {
        // Upstream tests adjacency with the calendar's whole-day difference between the two day
        // starts. Santiago's 6 September 2026 starts at 01:00 (its midnight does not exist), and
        // 01:00 → 00:00 the next day is 23 hours — zero whole days — so a user who closed every ring on
        // 5, 6 and 7 September had their streak broken (measured: current 1, longest 3 over April's and
        // September's changes). The port counts calendar dates: consecutive dates are adjacent.
        // New York and London, whose midnights all exist, answer as upstream (measured 3 / 4 and 3 / 3).
        fun streaks(zone: ZoneId, starts: List<Long>): Pair<Int, Int> {
            val days = GoalHistory.build(starts.map { full(ms(it)) }, goals, ms(starts.last()).plusSeconds(36 * 3600), zone)
            val s = GoalHistory.summarize(days, ms(starts.last()).plusSeconds(36 * 3600), zone)
            return s.currentStreak to s.longestStreak
        }
        assertEquals(3 to 4, streaks(newYork, listOf(1772773200000, 1772859600000, 1772946000000, 1773028800000, 1793419200000, 1793505600000, 1793595600000)))
        assertEquals(3 to 3, streaks(london, listOf(1774656000000, 1774742400000, 1774825200000, 1792796400000, 1792882800000, 1792972800000)))
        assertEquals(3 to 3, streaks(santiago, listOf(1775271600000, 1775361600000, 1775448000000, 1788580800000, 1788667200000, 1788750000000)))
        // …and a run whose newest day is two calendar days ago is not current, even though the elapsed
        // time from 6 September 01:00 to 8 September 00:00 is under two whole days (upstream: current 2).
        val days = GoalHistory.build(listOf(full(ms(1788580800000)), full(ms(1788667200000))), goals, Instant.parse("2026-09-08T12:00:00Z"), santiago)
        assertEquals(0, GoalHistory.summarize(days, Instant.parse("2026-09-08T12:00:00Z"), santiago).currentStreak)
        assertEquals(2, GoalHistory.summarize(days, Instant.parse("2026-09-07T12:00:00Z"), santiago).currentStreak)
    }
}
