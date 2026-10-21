package io.github.opencircuit.store

import io.github.opencircuit.ringkit.DateInterval
import io.github.opencircuit.ringkit.HRSample
import io.github.opencircuit.ringkit.StepWindow
import java.time.LocalDate

/**
 * What one local [day]'s activity estimate (`Calories.dailyEstimate`) is made from, as
 * [LocalStore.dayActivityInputs] reads it: the Health Connect energy writer and Today's Calories
 * card and goal rings read a day through it, so they price it alike.
 */
data class DayActivityInputs(
    val day: LocalDate,
    /** Every heart-rate reading that starts inside the day, oldest first. */
    val heartRate: List<HRSample>,
    /** The day's stored step total; null when none is stored (steps not recorded — never read as 0). */
    val steps: Long?,
    /**
     * The step deltas that start from [STEP_WINDOW_LOOKBACK_SECONDS] before the day's start to its
     * end, oldest first: a window across midnight contributes its in-day share.
     */
    val stepWindows: List<StepWindow>,
    /**
     * The in-bed window of the latest night that ENDED inside the day (its heart rate is not
     * exercise); a night with an empty or backwards window is none. Nights are looked for among
     * those keyed from two days before the day to two days after it.
     */
    val sleepWindow: DateInterval?,
) {
    companion object {
        /** How far before the day's start a step window may begin and still count (upstream's `-86_400`). */
        const val STEP_WINDOW_LOOKBACK_SECONDS = 86_400L
    }
}
