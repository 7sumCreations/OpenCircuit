package io.github.opencircuit.app.today

import io.github.opencircuit.ringkit.ActivityScore
import io.github.opencircuit.ringkit.Calories
import io.github.opencircuit.ringkit.GoalHistory
import io.github.opencircuit.ringkit.SwiftFormat
import io.github.opencircuit.ringkit.UserProfile
import io.github.opencircuit.store.DayActivityInputs
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The Calories card: the day's total, its resting and active parts, or why there is none ([empty]). */
data class CaloriesUi(
    val heading: String = "Calories",
    /** "so far today" on today; null on a past day (its whole day). */
    val qualifier: String? = null,
    val total: String? = null,
    val resting: String? = null,
    val active: String? = null,
    /** The resting part's share of the total (0…1), for the stacked bar. */
    val restingShare: Float = 0f,
    val empty: String? = null,
    val description: String = "",
)

/** One goal ring: its label, "6,240 / 8,000" (or why there is no value), the centre text, the arc and the one sentence it says. */
data class GoalRingUi(
    val ring: GoalHistory.Ring,
    val label: String,
    val detail: String,
    val centre: String,
    val fraction: Float,
    val description: String,
)

/** The four goal rings (steps, active kcal, active minutes, sleep) and the steps caveat. */
data class GoalsUi(val rings: List<GoalRingUi> = emptyList(), val note: String = "")

/** The day's Calories card and goal rings, and the Activity Score readiness takes from the same estimate (null with no activity). */
data class DayActivityUi(val calories: CaloriesUi, val goals: GoalsUi, val activity: ActivityScore.Result?)

/**
 * The selected day's Calories card and four goal rings, from the store's one day reader
 * ([DayActivityInputs], the inputs the Health Connect energy writer reads too). Pure: the clock and
 * the zone are inputs; every number prints with an explicit locale.
 *
 * Calories follows upstream's Calories card (`ContentView.swift` `CaloriesCardView` @ 2d07e550):
 * resting = the profile's BMR over the part of the day gone (the whole day on a past day; the day's
 * own length, so a daylight-saving day is 23 or 25 hours), active = `Calories.dailyEstimate`, the
 * total rounded as upstream rounds it. The rings follow its Goals card (`GoalsCardView.swift` @
 * 2d07e550): steps, active kcal and elevated-heart-rate minutes from the same estimate (whole
 * numbers truncated, as upstream prints them), and sleep credited to the day a night ended plus its
 * naps outside that night (`GoalHistory.sleepCreditByDay`). A day with no stored step total says
 * "Steps not recorded", never 0; a ring with nothing to show says so, never a 0 % arc.
 */
object CaloriesGoals {

    fun present(
        activity: DayActivityInputs?,
        nights: List<TodayNight>,
        naps: List<GoalHistory.NapSleep>,
        day: LocalDate,
        now: Instant,
        zone: ZoneId,
        goals: GoalHistory.Goals,
        profile: UserProfile,
    ): DayActivityUi {
        val dayStart = day.atStartOfDay(zone).toInstant()
        val isToday = day == LocalDate.ofInstant(now, zone)
        val hasActivity = activity != null && (activity.heartRate.isNotEmpty() || activity.steps != null)
        val steps = activity?.steps?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt()
        val estimate = if (hasActivity) {
            Calories.dailyEstimate(activity!!.heartRate, steps ?: 0, profile, activity.sleepWindow, activity.stepWindows, dayStart)
        } else {
            null
        }
        val sleepCredit = GoalHistory.sleepCreditByDay(
            nights.map { GoalHistory.NightSleep(it.night, it.inBedStart, it.inBedEnd, it.asleepMin) }, naps, zone,
        )[dayStart]
        val progress = GoalHistory.build(
            listOf(GoalHistory.DayInput(dayStart, steps, estimate?.activeKcal, estimate?.elevatedMinutes, sleepCredit)), goals, now, zone,
        ).single()
        val score = estimate?.let {
            ActivityScore.score(
                ActivityScore.Input(steps ?: 0, goals.stepGoal(dayStart, zone), it.elevatedMinutes, goals.activityMinutes, it.activeKcal, goals.activeKcal),
            )
        }
        return DayActivityUi(calories(estimate, day, now, zone, isToday, profile), GoalsUi(rings(progress), STEPS_NOTE), score)
    }

    private fun calories(estimate: Calories.DailyEstimate?, day: LocalDate, now: Instant, zone: ZoneId, isToday: Boolean, profile: UserProfile): CaloriesUi {
        val qualifier = if (isToday) "so far today" else null
        if (estimate == null) return CaloriesUi(qualifier = qualifier, empty = NO_ACTIVITY, description = "Calories. $NO_ACTIVITY")
        val start = day.atStartOfDay(zone).toInstant()
        val length = Duration.between(start, day.plusDays(1).atStartOfDay(zone).toInstant()).toMillis().toDouble()
        val gone = if (isToday) (Duration.between(start, now).toMillis() / length).coerceIn(0.0, 1.0) else 1.0
        val resting = Calories.bmrKcalPerDay(profile) * gone
        val active = estimate.activeKcal
        val total = whole(resting + active)
        val heading = "Calories" + (qualifier?.let { " $it" } ?: "")
        return CaloriesUi(
            qualifier = qualifier,
            total = total,
            resting = whole(resting),
            active = whole(active),
            restingShare = if (resting + active > 0) (resting / (resting + active)).toFloat() else 0f,
            description = "$heading, estimate, $total kilocalories: resting ${whole(resting)}, active ${whole(active)}",
        )
    }

    private fun rings(day: GoalHistory.Day): List<GoalRingUi> = GoalHistory.Ring.entries.map { ring ->
        val progress = day.goalProgress(ring)
        val present = ring in day.present
        val percent = SwiftFormat.rounded(day.fraction(ring) * 100).toInt()
        val fraction = day.fraction(ring).toFloat()
        when (ring) {
            GoalHistory.Ring.STEPS -> if (present) {
                val (value, goal) = TileNumber.GROUPED.format(progress.current) to TileNumber.GROUPED.format(progress.goal)
                GoalRingUi(ring, "Steps", "$value / $goal", "$percent%", fraction, "Steps $value of $goal, $percent %")
            } else {
                GoalRingUi(ring, "Steps", TodayTile.STEPS_NOT_RECORDED, NONE, 0f, TodayTile.STEPS_NOT_RECORDED)
            }
            GoalHistory.Ring.ACTIVE_KCAL -> if (present) {
                val (value, goal) = truncated(progress.current) to truncated(progress.goal)
                GoalRingUi(ring, "Active kcal", "$value / $goal", "$percent%", fraction, "Active calories, estimate, $value of $goal, $percent %")
            } else {
                GoalRingUi(ring, "Active kcal", NO_DATA, NONE, 0f, "Active calories, $NO_DATA_SPOKEN")
            }
            GoalHistory.Ring.ACTIVITY_MINUTES -> if (present) {
                val (value, goal) = truncated(progress.current) to truncated(progress.goal)
                GoalRingUi(ring, "Active min", "$value / $goal min", "$percent%", fraction, "Active minutes, estimate, $value of $goal, $percent %")
            } else {
                GoalRingUi(ring, "Active min", NO_DATA, NONE, 0f, "Active minutes, $NO_DATA_SPOKEN")
            }
            GoalHistory.Ring.SLEEP_MINUTES -> if (present) {
                val (value, goal) = TileNumber.DURATION.format(progress.current) to TileNumber.DURATION.format(progress.goal)
                GoalRingUi(ring, "Sleep", "$value / $goal", "$percent%", fraction, "Sleep $value of $goal, $percent %")
            } else {
                GoalRingUi(ring, "Sleep", NO_NIGHT, NONE, 0f, "Sleep, no night ended on this day")
            }
        }
    }

    /** A whole number, rounded as upstream's `Int(x.rounded())`, grouped. */
    private fun whole(v: Double): String = TileNumber.GROUPED.format(v)

    /** A whole number truncated toward zero, as upstream's `Int(x)` prints a ring's value, grouped. */
    private fun truncated(v: Double): String = TileNumber.GROUPED.format(v.toLong().toDouble())

    private const val NONE = "—"
    private const val NO_DATA = "No data"
    private const val NO_DATA_SPOKEN = "no data for this day"
    private const val NO_NIGHT = "No night"
    private const val NO_ACTIVITY = "No activity recorded for this day."
    private const val STEPS_NOTE = "Steps: connected time only"
}
