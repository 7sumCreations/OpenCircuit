package io.github.opencircuit.app.today

import io.github.opencircuit.ringkit.BaselineTrend
import io.github.opencircuit.ringkit.TodaySynthesis
import java.time.Instant
import java.time.ZoneId

/** What the readiness card is showing, handed to the synthesis line so the two agree. */
data class ReadinessReport(
    val readiness: TodaySynthesis.Readiness,
    /** Last night's asleep minutes, when a night that ended today is stored. */
    val lastNightAsleepMin: Int?,
)

/**
 * Gathers the synthesis line's input from what Today already holds. Port of upstream
 * `TodaySynthesis.input(trends:tiles:readiness:feverSuspected:lastSyncAt:now:)`
 * (ios/OpenCircuit/Dashboard/TodaySynthesisHeader.swift:23-51 @ 2d07e550). The zone is explicit
 * (upstream reads `Calendar.current`) and the per-device scope is dropped (every night counts, as
 * on every ring-only install).
 */
object TodaySynthesisInputs {

    /**
     * [trends] is the shared two-week load the tiles were built from; [readiness] what the
     * readiness card reported (null until it has); [feverSuspected] Vitals Status's own fever
     * verdict (the only thing that pairs skin temperature with resting heart rate); [lastSyncAt]
     * only tells "never had data" from "nothing in the last two weeks".
     */
    fun input(
        trends: TodayTrends,
        tiles: List<TodayTile>,
        readiness: ReadinessReport?,
        feverSuspected: Boolean = false,
        lastSyncAt: Instant?,
        now: Instant,
        zone: ZoneId,
    ): TodaySynthesis.Input {
        fun direction(metric: TodayTile.Metric): BaselineTrend.Direction? {
            val t = tiles.firstOrNull { it.metric == metric } ?: return null
            if (t.isStale(now, zone)) return null
            return t.trend?.direction
        }
        // Usual sleep = the nights BEFORE the newest one, so last night isn't compared with itself.
        val nights = trends.points.mapNotNull { p -> p.sleepMinutes?.takeIf { it > 0 } }
        val prior = nights.dropLast(1)
        val usualSleep = if (prior.isEmpty()) null else prior.fold(0L) { acc, m -> acc + m }.toDouble() / prior.size
        // Nothing in the window but the app HAS synced before: the data is at least as old as the
        // window, which is exactly what the "more than two weeks old" wording says.
        val newest = trends.newestSampleAt
            ?: lastSyncAt?.let { now.atZone(zone).minusDays(TodayTiles.WINDOW_DAYS.toLong()).toInstant() }
        return TodaySynthesis.Input(
            readiness = readiness?.readiness ?: TodaySynthesis.Readiness.Pending,
            hrv = direction(TodayTile.Metric.HRV),
            restingHR = direction(TodayTile.Metric.RESTING_HR),
            skinTemp = direction(TodayTile.Metric.SKIN_TEMP),
            feverSuspected = feverSuspected,
            lastNightSleepMinutes = readiness?.lastNightAsleepMin,
            usualSleepMinutes = usualSleep,
            newestDataAt = newest,
            now = now,
        )
    }
}
