package io.github.opencircuit.app.today

import io.github.opencircuit.ringkit.RestingHR
import io.github.opencircuit.ringkit.TrendsEngine
import java.time.Instant

/**
 * The two-week load the Today tiles, the readiness card and the synthesis line read (the part of
 * upstream `TrendsData` they use, ios/OpenCircuit/Trends/TrendsData.swift @ 2d07e550): one
 * [TrendsEngine.DailyPoint] per day that has data, oldest first; the derived resting heart rate;
 * each day's heart-rate low–high; and the time of the newest stored reading of any kind.
 */
data class TodayTrends(
    val points: List<TrendsEngine.DailyPoint> = emptyList(),
    val restingHR: List<RestingHR.DailyValue> = emptyList(),
    val heartRateRanges: List<DayRange> = emptyList(),
    val newestSampleAt: Instant? = null,
)
