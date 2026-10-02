package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/GoalDefaults.swift (@ b1c2fdd), whole.
//
// Port notes:
//  • Upstream reads goals from `UserDefaults.standard` and today's date from the device clock and
//    calendar. `:ringkit` has no preferences store and no ambient clock, zone or calendar: the readers
//    take a caller-supplied read-only `GoalSettings` lookup, an `Instant` and a `ZoneId`, all required.
//  • Upstream's `object(forKey:) as? Int` / `as? Double` is Foundation's number bridging, not a type
//    check (measured on the pinned build): a stored number whose value is whole reads as an integer
//    (9000.0 → 9000, −0.0 → 0, a float 9000 → 9000, true → 1, false → 0), a number the double holds
//    exactly reads as a double (an integer → x.0, the float 0.1 → 0.10000000149011612, NaN, ∞), and
//    anything else (a fractional value read as an integer, a string, a collection) reads as missing,
//    so the default answers. The same rule is applied here to Kotlin's boxed numbers and `Boolean`.
//    One difference: upstream's integers are 64-bit, so a stored 2^31 or 2^40 is returned as the
//    goal; a Kotlin goal is an `Int`, and a value it cannot hold reads as missing.
//  • The weekend is Saturday or Sunday in the given zone. Measured: every Foundation calendar
//    (Gregorian, Buddhist, Chinese, Coptic, Ethiopic, Hebrew, Indian, Islamic, ISO 8601, Japanese,
//    Persian, Republic of China) numbers weekdays the same way, so the device calendar never
//    changed upstream's answer — only its time zone did. An instant no zone can place (the last
//    year of `Instant`'s range) is a workday.
//  • `GoalProgress.fraction` never drops below 0 and never reads NaN (upstream's documented
//    "clamped to [0, 1]"; upstream clamps only the top: −100 of 8 000 → −0.0125, NaN → NaN).
//  • `GoalProgress` compares its doubles by IEEE `==`, as Swift's synthesized `Equatable`.

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/**
 * A read-only view of the user's stored settings: the stored value for [value]'s key, or null when
 * nothing is stored. The goal readers use a stored value only when it bridges to the type they read
 * (see [GoalDefaults]); anything else reads as missing and the default answers. The app supplies it
 * over its own settings store.
 */
fun interface GoalSettings {
    fun value(key: String): Any?
}

/**
 * Daily goal settings — keys, defaults, and progress math (#77).
 *
 * Goals are APP-SIDE display only — no value is written to the ring. The ring's `TargetSyncModel`
 * (type=0 step / 1 calorie / 2 sleep / 3 bedSchedule) and `watchStepTargetData` are out of scope
 * (require the write-channel capture, #88).
 *
 * APK evidence: `workday_step_goal` / `weekend_step_goal`; `calTarget`;
 * `keySettingActivityDurationGoal`; `workdaySleepGoal` / `weekendSleepGoal`. WHO framing: 150–300
 * min/week activity.
 */
object GoalDefaults {

    // Steps — weekday / weekend split
    const val WORKDAY_STEPS = "goals.workdaySteps"
    const val WEEKEND_STEPS = "goals.weekendSteps"

    // Active calories — single goal (no weekday/weekend in APK data)
    const val ACTIVE_KCAL = "goals.activeKcal"

    // Activity / exercise duration minutes — single goal
    const val ACTIVITY_MINUTES = "goals.activityMinutes"

    // Sleep duration — weekday / weekend split (minutes)
    const val WORKDAY_SLEEP_MIN = "goals.workdaySleepMin"
    const val WEEKEND_SLEEP_MIN = "goals.weekendSleepMin"

    // Defaults
    const val DEFAULT_WORKDAY_STEPS = 8_000
    const val DEFAULT_WEEKEND_STEPS = 10_000
    const val DEFAULT_ACTIVE_KCAL = 300.0
    const val DEFAULT_ACTIVITY_MINUTES = 30.0 // WHO: 150 min/week ÷ 5
    const val DEFAULT_WORKDAY_SLEEP_MIN = 7 * 60 // 7 h
    const val DEFAULT_WEEKEND_SLEEP_MIN = 8 * 60 // 8 h

    /** Whether [date] is a weekend (Saturday or Sunday) in [zone]; an instant no zone can place is a workday. */
    fun isWeekend(date: Instant, zone: ZoneId): Boolean {
        val day = zonedOrNull { date.atZone(zone).dayOfWeek } ?: return false
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY
    }

    /** The step goal for [date]'s day in [zone]: the stored weekday or weekend goal, else its default. */
    fun stepsGoal(date: Instant, zone: ZoneId, settings: GoalSettings): Int =
        if (isWeekend(date, zone)) storedInt(settings, WEEKEND_STEPS) ?: DEFAULT_WEEKEND_STEPS
        else storedInt(settings, WORKDAY_STEPS) ?: DEFAULT_WORKDAY_STEPS

    /** The sleep goal (minutes) for [date]'s day in [zone]: the stored weekday or weekend goal, else its default. */
    fun sleepGoalMinutes(date: Instant, zone: ZoneId, settings: GoalSettings): Int =
        if (isWeekend(date, zone)) storedInt(settings, WEEKEND_SLEEP_MIN) ?: DEFAULT_WEEKEND_SLEEP_MIN
        else storedInt(settings, WORKDAY_SLEEP_MIN) ?: DEFAULT_WORKDAY_SLEEP_MIN

    /** The value stored under [key] read as upstream's `as? Int` reads it; null when it does not bridge (or is beyond `Int`). */
    internal fun storedInt(settings: GoalSettings, key: String): Int? = when (val v = settings.value(key)) {
        is Int -> v
        is Short -> v.toInt()
        is Byte -> v.toInt()
        is Long -> if (v >= Int.MIN_VALUE && v <= Int.MAX_VALUE) v.toInt() else null
        is Boolean -> if (v) 1 else 0
        is Double -> wholeInt(v)
        is Float -> wholeInt(v.toDouble())
        else -> null
    }

    /** The value stored under [key] read as upstream's `as? Double` reads it; null when it does not bridge exactly. */
    internal fun storedDouble(settings: GoalSettings, key: String): Double? = when (val v = settings.value(key)) {
        is Double -> v
        is Float -> v.toDouble()
        is Int -> v.toDouble()
        is Short -> v.toDouble()
        is Byte -> v.toDouble()
        is Long -> exactDouble(v)
        is Boolean -> if (v) 1.0 else 0.0
        else -> null
    }

    /** [d] as an `Int` when it is a whole number in range (−0.0 → 0); NaN, ±∞ and fractions are null. */
    private fun wholeInt(d: Double): Int? =
        if (d >= Int.MIN_VALUE.toDouble() && d <= Int.MAX_VALUE.toDouble() && d == Math.floor(d)) d.toInt() else null

    /** [v] as a double when the double holds it exactly (every `Long` up to 2^53, and some beyond). */
    private fun exactDouble(v: Long): Double? {
        val d = v.toDouble()
        if (d == TWO_TO_THE_63) return null // rounds up past Long.MAX_VALUE: not exact
        return if (d.toLong() == v) d else null
    }

    private const val TWO_TO_THE_63 = 9.223372036854775808E18
}

/** One metric's current value vs. its goal. A value: the goal is clamped at construction, as upstream's initializer does. */
class GoalProgress(val current: Double, goal: Double) {

    /** The goal, clamped with Swift's `max(goal, 0)`: zero or below becomes 0, NaN stays NaN. */
    val goal: Double = swiftMax(goal, 0.0)

    /** Fraction towards the goal, clamped to [0, 1]: 0 when the goal is not above zero or the ratio is not a number. */
    val fraction: Double
        get() {
            if (!(goal > 0)) return 0.0
            val ratio = current / goal
            return if (ratio.isNaN() || ratio < 0.0) 0.0 else swiftMin(ratio, 1.0)
        }

    /** True when the goal is fully met (current ≥ goal). */
    val met: Boolean get() = current >= goal && goal > 0

    override fun equals(other: Any?): Boolean = other is GoalProgress && current == other.current && goal == other.goal

    override fun hashCode(): Int = 31 * ieeeHash(current) + ieeeHash(goal)

    override fun toString(): String = "GoalProgress(current=$current, goal=$goal)"
}

/** Today's progress across all four tracked goals. */
data class DailyGoalProgress(
    val steps: GoalProgress,
    val activeKcal: GoalProgress,
    val activityMinutes: GoalProgress,
    val sleepMinutes: GoalProgress, // last night's sleep vs. tonight's goal
)
