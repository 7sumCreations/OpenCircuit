package io.github.opencircuit.ringkit

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Kotlin-only hostile-input checks for the goal settings readers, the weekend rule and goal
 * progress: what the upstream vectors never feed in. Goals are user settings read back from a
 * store, so here every stored value arrives as the wrong type, a fractional or non-finite number, a
 * boolean, a 64-bit integer or nothing at all; the weekend rule meets both 2026 clock changes in
 * three zones and the far end of `Instant`'s range; and progress meets goals of zero or below and
 * current values that are negative or not a number. Kept out of the upstream-port class so its
 * count stays exact.
 *
 * Every upstream outcome quoted below was measured on the pinned Swift build (Swift 6.3.2), where
 * upstream reads a stored goal with `UserDefaults.object(forKey:) as? Int` or `as? Double`. Where the
 * port deliberately differs the test says so, and `PORTING.md` records why.
 */
class GoalDefaultsHazardTest {

    private val utc: ZoneId = ZoneId.of("UTC")
    private val monday: Instant = Instant.parse("2026-08-17T00:00:00Z")
    private val saturday: Instant = Instant.parse("2026-08-15T00:00:00Z")

    /** A store holding [stored] under every goal key (as upstream's probe stored it under each). */
    private fun storeOf(stored: Any?) = GoalSettings { key -> if (key.startsWith("goals.")) stored else null }

    private class Row(val stored: Any?, val intGoal: Int?, val doubleGoal: Double?)

    @Test
    fun aStoredValueIsReadExactlyAsFoundationBridgesAStoredNumber() {
        // Upstream bridges the stored object to a number: `as? Int` takes any number whose value is a
        // whole number in range (a whole double, a float, a boolean as 1 or 0); `as? Double` takes any
        // number the double holds exactly (an integer up to 2^53, a float widened, NaN, infinity).
        // Anything else — a string, a list, a fractional value read as an integer — reads as missing,
        // and the default answers. Measured, one stored value at a time (null = the default answers).
        val rows = listOf(
            Row(9000, 9000, 9000.0),
            Row(0, 0, 0.0),
            Row(-5, -5, -5.0),
            Row(300L, 300, 300.0),
            Row(9000.0, 9000, 9000.0),
            Row(9000.5, null, 9000.5),
            Row(-0.0, 0, -0.0),
            Row(Double.NaN, null, Double.NaN),
            Row(Double.POSITIVE_INFINITY, null, Double.POSITIVE_INFINITY),
            Row(1e19, null, 1e19),
            Row(5e-324, null, 5e-324),
            Row(2147483647, 2147483647, 2147483647.0),
            Row(9000f, 9000, 9000.0),
            Row(0.1f, null, 0.10000000149011612),
            Row(true, 1, 1.0),
            Row(false, 0, 0.0),
            Row(7.toByte(), 7, 7.0),
            Row((-3).toShort(), -3, -3.0),
            Row(Long.MIN_VALUE, null, -9.223372036854775808E18),
            Row(9007199254740993L, null, null),
            Row("9000", null, null),
            Row("9000.5", null, null),
            Row(listOf(9000), null, null),
            Row(mapOf("a" to 1), null, null),
            Row(byteArrayOf(1, 2), null, null),
            Row(null, null, null),
        )
        for (row in rows) {
            val settings = storeOf(row.stored)
            val label = "stored ${row.stored} (${row.stored?.let { it::class.simpleName }})"
            assertEquals(row.intGoal ?: 8_000, GoalDefaults.stepsGoal(monday, utc, settings), "$label → workday steps")
            assertEquals(row.intGoal ?: 420, GoalDefaults.sleepGoalMinutes(monday, utc, settings), "$label → workday sleep")
            val goals = GoalHistory.Goals.fromDefaults(settings)
            assertEquals(row.intGoal ?: 8_000, goals.workdaySteps, "$label → fromDefaults steps")
            val kcal = goals.activeKcal
            val expected = row.doubleGoal ?: 300.0
            assertEquals(expected.toRawBits(), kcal.toRawBits(), "$label → fromDefaults active kcal $kcal")
        }
    }

    @Test
    fun aStoredIntegerBeyondThirtyTwoBitsReadsAsMissing() {
        // Upstream's `Int` is 64-bit, so it returns these (measured: 2^31 → 2147483648, 2^40 →
        // 1099511627776, 2^63 as a double → Int.max, Int64.min → itself). A Kotlin goal is an `Int`
        // and cannot hold them: the port reads a value it cannot hold exactly as one of the wrong type,
        // and the default answers, rather than wrapping or saturating it into a goal nobody set.
        for (stored in listOf<Any>(2147483648L, 1L shl 40, 9.223372036854775807E18, -9.223372036854775808E18, 2147483648.0, -2147483649.0, Long.MAX_VALUE)) {
            val settings = storeOf(stored)
            assertEquals(8_000, GoalDefaults.stepsGoal(monday, utc, settings), "stored $stored")
            assertEquals(480, GoalDefaults.sleepGoalMinutes(saturday, utc, storeOf(stored)), "stored $stored (weekend key)")
            assertEquals(GoalHistory.Goals(), GoalHistory.Goals.fromDefaults(GoalSettings { key ->
                if (key == GoalDefaults.WORKDAY_STEPS || key == GoalDefaults.WEEKEND_STEPS ||
                    key == GoalDefaults.WORKDAY_SLEEP_MIN || key == GoalDefaults.WEEKEND_SLEEP_MIN) stored else null
            }), "stored $stored")
        }
        // …while the double reader keeps every 64-bit integer the double holds exactly, as upstream
        // (measured: 2^40 → 1099511627776.0; Int64.max and 2^53 + 1 are not exact → the default).
        assertEquals(1099511627776.0, GoalHistory.Goals.fromDefaults(storeOf(1L shl 40)).activeKcal)
        assertEquals(300.0, GoalHistory.Goals.fromDefaults(storeOf(Long.MAX_VALUE)).activeKcal)
        assertEquals(30.0, GoalHistory.Goals.fromDefaults(storeOf((1L shl 53) + 1)).activityMinutes)
    }

    @Test
    fun theWeekendKeyAndItsDefaultFollowTheDayInTheGivenZone() {
        // The weekend reader asks the weekend key, the workday reader the workday key; a key that is
        // absent answers its own default.
        val stored = GoalSettings { key ->
            when (key) {
                GoalDefaults.WEEKEND_STEPS -> 12_345
                GoalDefaults.WORKDAY_SLEEP_MIN -> 400
                else -> null
            }
        }
        assertEquals(12_345, GoalDefaults.stepsGoal(saturday, utc, stored))
        assertEquals(8_000, GoalDefaults.stepsGoal(monday, utc, stored))
        assertEquals(480, GoalDefaults.sleepGoalMinutes(saturday, utc, stored))
        assertEquals(400, GoalDefaults.sleepGoalMinutes(monday, utc, stored))
        // Saturday 00:30 in Auckland is still Friday in UTC: the zone decides which goal applies.
        val aucklandSaturday = Instant.parse("2026-08-14T12:30:00Z")
        assertEquals(12_345, GoalDefaults.stepsGoal(aucklandSaturday, ZoneId.of("Pacific/Auckland"), stored))
        assertEquals(8_000, GoalDefaults.stepsGoal(aucklandSaturday, utc, stored))
    }

    @Test
    fun weekendIsSaturdayOrSundayInTheZoneAcrossBothTwentyTwentySixClockChanges() {
        // Upstream asks the device calendar for `.weekday` 1 or 7. Measured on the pinned build: all
        // sixteen Foundation calendars (Gregorian, Buddhist, Chinese, Coptic, both Ethiopic, Hebrew,
        // Indian, four Islamic, ISO 8601, Japanese, Persian, Republic of China), with any first weekday,
        // number the weekday the same way — Sunday 1 … Saturday 7 — so the weekend is Saturday and
        // Sunday in the calendar's time zone whatever the device calendar is. The port takes the zone.
        class Probe(val zone: String, val instant: String, val weekend: Boolean)
        val probes = listOf(
            // New York: Saturday 7 March 2026 ends at local midnight; 8 March loses 02:00–03:00.
            Probe("America/New_York", "2026-03-07T04:59:59Z", false), // Fri 23:59:59 EST
            Probe("America/New_York", "2026-03-07T05:00:00Z", true), // Sat 00:00 EST
            Probe("America/New_York", "2026-03-09T03:59:59Z", true), // Sun 23:59:59 EDT
            Probe("America/New_York", "2026-03-09T04:00:00Z", false), // Mon 00:00 EDT
            Probe("America/New_York", "2026-11-01T05:30:00Z", true), // Sun 01:30 EDT (first)
            Probe("America/New_York", "2026-11-01T06:30:00Z", true), // Sun 01:30 EST (repeated)
            Probe("America/New_York", "2026-11-02T04:59:59Z", true), // Sun 23:59:59 EST
            Probe("America/New_York", "2026-11-02T05:00:00Z", false), // Mon 00:00 EST
            // London: 29 March and 25 October 2026 are Sundays.
            Probe("Europe/London", "2026-03-27T23:59:59Z", false),
            Probe("Europe/London", "2026-03-28T00:00:00Z", true),
            Probe("Europe/London", "2026-03-29T22:59:59Z", true), // Sun 23:59:59 BST
            Probe("Europe/London", "2026-03-29T23:00:00Z", false), // Mon 00:00 BST
            Probe("Europe/London", "2026-10-25T23:59:59Z", true),
            Probe("Europe/London", "2026-10-26T00:00:00Z", false),
            // Santiago: 6 September 2026 has no midnight (00:00 → 01:00); 5 April repeats 23:00–24:00.
            Probe("America/Santiago", "2026-09-05T03:59:59Z", false), // Fri 23:59:59 −04
            Probe("America/Santiago", "2026-09-05T04:00:00Z", true), // Sat 00:00 −04
            Probe("America/Santiago", "2026-09-06T04:00:00Z", true), // Sun 01:00 −03 (first instant)
            Probe("America/Santiago", "2026-09-07T02:59:59Z", true), // Sun 23:59:59 −03
            Probe("America/Santiago", "2026-09-07T03:00:00Z", false), // Mon 00:00 −03
            Probe("America/Santiago", "2026-04-06T03:59:59Z", true), // Sun 23:59:59 −04
            Probe("America/Santiago", "2026-04-06T04:00:00Z", false), // Mon 00:00 −04
        )
        for (p in probes) assertEquals(p.weekend, GoalDefaults.isWeekend(Instant.parse(p.instant), ZoneId.of(p.zone)), "${p.zone} ${p.instant}")
        // One instant, two answers: Friday 23:30 in Pago Pago is Saturday 23:30 in Kiritimati.
        val t = Instant.parse("2026-08-15T10:30:00Z")
        assertFalse(GoalDefaults.isWeekend(t, ZoneId.of("Pacific/Pago_Pago")))
        assertTrue(GoalDefaults.isWeekend(t, ZoneId.of("Pacific/Kiritimati")))
    }

    @Test
    fun anInstantNoZoneCanPlaceIsAWorkdayInsteadOfAThrow() {
        // Kotlin-only: `java.time` cannot place the last year of `Instant`'s range in a zone ahead of
        // UTC (a Swift `Date` has no such end). The weekend rule answers "workday" there — the workday
        // goal applies — instead of throwing out of a settings read.
        val tokyo = ZoneId.of("Asia/Tokyo")
        assertFalse(GoalDefaults.isWeekend(Instant.MAX, tokyo))
        assertEquals(8_000, GoalDefaults.stepsGoal(Instant.MAX, tokyo, storeOf(null)))
        assertEquals(420, GoalDefaults.sleepGoalMinutes(Instant.MAX, tokyo, storeOf(null)))
        assertEquals(10_000, GoalHistory.Goals().stepGoal(Instant.parse("2026-08-15T12:00:00Z"), tokyo))
        assertEquals(8_000, GoalHistory.Goals().stepGoal(Instant.MAX, tokyo))
    }

    @Test
    fun progressAgainstAGoalOfZeroOrBelowOrUnreadableAsUpstream() {
        // The goal is clamped with Swift's `max(goal, 0)`: zero, negative and −∞ become 0, NaN stays
        // NaN; a goal that is not above zero never fills and is never met. Measured.
        for (goal in listOf(0.0, -0.0, -5.0, Double.NEGATIVE_INFINITY, Double.NaN)) {
            for (current in listOf(-100.0, 0.0, 100.0, 8000.0, Double.POSITIVE_INFINITY, Double.NaN)) {
                val p = GoalProgress(current, goal)
                assertEquals(0.0, p.fraction, "fraction of $current over $goal")
                assertFalse(p.met, "met $current over $goal")
            }
        }
        assertEquals(0.0, GoalProgress(1.0, -5.0).goal)
        assertEquals(0.0.toRawBits(), GoalProgress(1.0, -0.0).goal.toRawBits(), "max(−0.0, 0) is +0.0")
        assertTrue(GoalProgress(1.0, Double.NaN).goal.isNaN())
        // An infinite goal: nothing finite fills it (measured 0.0); ∞ over ∞ is met but its ratio is
        // not a number (upstream fraction NaN → see the next test).
        assertEquals(0.0, GoalProgress(9000.0, Double.POSITIVE_INFINITY).fraction)
        assertTrue(GoalProgress(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY).met)
        // The smallest positive goal: any positive current fills it (measured 1.0, met).
        assertEquals(1.0, GoalProgress(100.0, Double.MIN_VALUE).fraction)
        assertTrue(GoalProgress(100.0, Double.MIN_VALUE).met)
    }

    @Test
    fun progressFractionStaysInsideZeroToOneForANegativeOrUnreadableCurrentValue() {
        // Upstream promises "clamped to [0, 1]" but clamps only the top: measured, −100 of 8 000 →
        // −0.0125, −∞ → −∞, NaN → NaN, ∞ over ∞ → NaN, −100 over the smallest positive goal → −∞. A
        // day's mean attainment then reads negative or NaN. The port clamps the bottom too and reads a
        // ratio that is not a number as 0; every other ratio is unchanged (−0.0 stays −0.0).
        assertEquals(0.0, GoalProgress(-100.0, 8000.0).fraction)
        assertEquals(0.0, GoalProgress(Double.NEGATIVE_INFINITY, 8000.0).fraction)
        assertEquals(0.0, GoalProgress(Double.NaN, 8000.0).fraction)
        assertEquals(0.0, GoalProgress(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY).fraction)
        assertEquals(0.0, GoalProgress(-100.0, Double.MIN_VALUE).fraction)
        assertEquals((-0.0).toRawBits(), GoalProgress(-0.0, 8000.0).fraction.toRawBits(), "−0.0 kept, as upstream")
        assertFalse(GoalProgress(Double.NaN, 8000.0).met)

        // Property over a sweep of currents and goals: the fraction is in [0, 1]; where upstream's
        // ratio was already a number of at least 0 (capped at 1), the answer is upstream's.
        val values = listOf(
            Double.NEGATIVE_INFINITY, -1e308, -8000.0, -1.0, -Double.MIN_VALUE, -0.0, 0.0, Double.MIN_VALUE, 0.5, 1.0, 299.0,
            4000.0, 8000.0, 9000.0, 1e308, Double.POSITIVE_INFINITY, Double.NaN,
        )
        for (current in values) {
            for (goal in values) {
                val f = GoalProgress(current, goal).fraction
                assertTrue(f >= 0.0 && f <= 1.0, "fraction of $current over $goal = $f")
                val upstream = if (goal > 0) (current / goal).let { r -> if (1.0 < r) 1.0 else r } else 0.0
                if (upstream >= 0.0) assertEquals(upstream.toRawBits(), f.toRawBits(), "$current over $goal")
            }
        }
    }
}
