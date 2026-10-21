package io.github.opencircuit.app.today

import io.github.opencircuit.app.ui.charts.TrendGeometry
import io.github.opencircuit.ringkit.BaselineTrend
import io.github.opencircuit.ringkit.GoalDefaults
import io.github.opencircuit.ringkit.SwiftFormat
import io.github.opencircuit.ringkit.TemperatureUnit
import io.github.opencircuit.ringkit.TodaySynthesis
import io.github.opencircuit.ringkit.WellnessBalance
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Which way a tile's stated verdict points: drawn as a neutral arrow, never red or green. */
enum class TileDirection { UP, FLAT, DOWN }

/**
 * One tile as the screen draws it: the value with its unit and freshness, the verdict line and its
 * arrow, the usual range (or why there is none), the Android extra line, the sparkline already
 * placed, and the one sentence a screen reader says for the whole tile.
 */
data class TileUi(
    val metric: TodayTile.Metric,
    val title: String,
    val value: String?,
    val unit: String,
    val freshness: String?,
    /** The value is older than the newest day it could be from: the freshness reads as a date. */
    val stale: Boolean,
    val delta: String?,
    val direction: TileDirection?,
    val usual: String,
    val extra: String?,
    val chart: TrendGeometry,
    val description: String,
)

/** One readiness factor's bar: its name and its 0–100 sub-score. */
data class FactorUi(val label: String, val percent: Int)

/**
 * The readiness ring as the screen draws it: the score with its tier word and glyph (the colour is
 * never the only cue), the arc's [fraction], the factor bars — or, without a score, why ([empty]).
 */
data class ReadinessUi(
    val heading: String,
    val score: Int? = null,
    val tier: WellnessBalance.Tier? = null,
    val tierWord: String? = null,
    val glyph: String? = null,
    val fraction: Float = 0f,
    val factors: List<FactorUi> = emptyList(),
    /** "Based on last night's sleep alone" when only one factor went in. */
    val note: String? = null,
    val empty: String? = null,
    val description: String,
)

/**
 * A past day's heart rate in place of the live hero: its low–high, average and resting estimate,
 * and the range as a bar on a fixed [PastDayHeroUi.SCALE_LOW]–[PastDayHeroUi.SCALE_HIGH] bpm scale
 * ([low], [average], [high] are fractions of it) — or, with nothing stored that day, why ([empty]).
 */
data class PastDayHeroUi(
    val title: String,
    /** "52–112"; null when the day has no heart rate. */
    val range: String? = null,
    /** "average 72 · resting 55". */
    val detail: String? = null,
    val low: Float = 0f,
    val average: Float = 0f,
    val high: Float = 0f,
    val empty: String? = null,
    val description: String,
) {
    companion object {
        const val SCALE_LOW = 40.0
        const val SCALE_HIGH = 160.0
    }
}

/** The dashboard part of Today: the tiles, the readiness ring, the synthesis sentence and the date line. */
data class DashboardUi(
    val tiles: List<TileUi> = emptyList(),
    val readiness: ReadinessUi? = null,
    val synthesis: String? = null,
    val dateLine: String = "",
    /** On a past day, its heart-rate range in place of the live hero; null on today. */
    val pastDay: PastDayHeroUi? = null,
)

/**
 * The dashboard's words and shapes for one day from the stored window. Pure: the clock and the
 * zone are inputs. Readiness follows upstream's readiness card (`WellnessBalanceCardView.swift`
 * :195-262 @ 2d07e550): anchored on the night keyed on the day, from its sleep score and overnight
 * stress; the activity factor joins when the day's activity estimate lands with the Calories card,
 * and Vitals Status is not on this screen (upstream passes none either). The synthesis line speaks
 * about now, so it is built for today only.
 */
object TodayDashboard {

    /**
     * [lastSyncAt] is the last logged sync's end; [syncKnown] whether the sync log has been read.
     * Before it has (or when reading it failed) an empty log proves nothing, so nothing here claims
     * the ring never synced.
     */
    fun present(trends: TodayTrends, day: LocalDate, now: Instant, zone: ZoneId, lastSyncAt: Instant?, syncKnown: Boolean = true): DashboardUi {
        val isToday = day == LocalDate.ofInstant(now, zone)
        val dayStart = day.atStartOfDay(zone).toInstant()
        val stepGoal = GoalDefaults.stepsGoal(dayStart, zone) { null }
        val tiles = TodayTiles.build(
            trends.points, trends.restingHR, TemperatureUnit.CELSIUS, now = now, zone = zone,
            heartRateRanges = trends.heartRateRanges, stepGoal = stepGoal, shownDay = day,
        )
        val (readiness, report) = readiness(trends, dayStart, isToday, neverSynced = syncKnown && lastSyncAt == null)
        // With no data in the window and the log unread, the sentence would have to guess between
        // "no data yet" and "more than two weeks old": it waits for the log.
        val unsure = !syncKnown && lastSyncAt == null && trends.newestSampleAt == null
        val synthesis = if (isToday && !unsure) {
            TodaySynthesis.sentence(TodaySynthesisInputs.input(trends, tiles, report, lastSyncAt = lastSyncAt, now = now, zone = zone))
        } else {
            null
        }
        val pastDay = if (isToday) null else pastDayHero(trends, day, zone)
        return DashboardUi(tiles.map { tileUi(it, now, zone) }, readiness, synthesis, dateLine(day), pastDay)
    }

    /** "Wednesday 30 September". */
    fun dateLine(day: LocalDate): String = DATE_LINE.format(day)

    /** [day]'s heart rate in place of the live hero: its low–high on the fixed scale, average and resting estimate. */
    private fun pastDayHero(trends: TodayTrends, day: LocalDate, zone: ZoneId): PastDayHeroUi {
        val start = day.atStartOfDay(zone).toInstant()
        val title = "Heart rate on ${SHORT_DATE.format(day)}"
        val spokenTitle = "Heart rate on ${DATE_LINE.format(day)}"
        val range = trends.heartRateRanges.firstOrNull { it.day == start }
            ?: return PastDayHeroUi(title, empty = NO_HEART_RATE, description = "$spokenTitle. $NO_HEART_RATE")
        val average = trends.points.firstOrNull { it.date == start }?.dayHRAvg
        val resting = trends.restingHR.firstOrNull { LocalDate.ofInstant(it.day, zone) == day }?.bpm
        fun whole(v: Double) = TileNumber.WHOLE.format(v)
        fun onScale(v: Double) = ((v - PastDayHeroUi.SCALE_LOW) / (PastDayHeroUi.SCALE_HIGH - PastDayHeroUi.SCALE_LOW)).coerceIn(0.0, 1.0).toFloat()
        val detail = listOfNotNull(average?.let { "average ${whole(it)}" }, resting?.let { "resting ${whole(it)}" }).joinToString(" · ")
        val description = "$spokenTitle: ${whole(range.low)} to ${whole(range.high)} beats per minute" +
            (average?.let { ", average ${whole(it)}" } ?: "") + (resting?.let { ", resting ${whole(it)}" } ?: "")
        return PastDayHeroUi(
            title = title,
            range = "${whole(range.low)}–${whole(range.high)}",
            detail = detail.ifEmpty { null },
            low = onScale(range.low),
            average = onScale(average ?: range.low),
            high = onScale(range.high),
            description = description,
        )
    }

    private fun tileUi(tile: TodayTile, now: Instant, zone: ZoneId): TileUi = TileUi(
        metric = tile.metric,
        title = tile.title,
        value = tile.valueText,
        unit = tile.unit,
        freshness = tile.freshnessText(now, zone),
        stale = tile.isStale(now, zone),
        delta = tile.deltaText,
        direction = tile.statedTrend?.direction?.let {
            when (it) {
                BaselineTrend.Direction.ABOVE -> TileDirection.UP
                BaselineTrend.Direction.WITHIN -> TileDirection.FLAT
                BaselineTrend.Direction.BELOW -> TileDirection.DOWN
            }
        },
        usual = tile.rangeText,
        extra = tile.extra,
        chart = TrendGeometry.of(tile.values, tile.trend?.usualRange),
        description = tile.accessibilityLabel(now, zone),
    )

    /** The readiness card for the night keyed on [dayStart], and what it reports to the synthesis line. */
    private fun readiness(trends: TodayTrends, dayStart: Instant, isToday: Boolean, neverSynced: Boolean): Pair<ReadinessUi, ReadinessReport> {
        val heading = if (isToday) "Readiness" else "Readiness that day"
        val point = trends.points.firstOrNull { it.date == dayStart }
        if (dayStart !in trends.nightKeys) {
            val empty = when {
                trends.nightKeys.isEmpty() && neverSynced -> "Readiness needs one night of sleep with the ring on. Wear it tonight and it appears tomorrow morning."
                isToday -> "Last night's sleep hasn't synced yet. Readiness appears once it does."
                else -> "No night was stored for this day, so there's no readiness for it."
            }
            return ReadinessUi(heading, empty = empty, description = "$heading. $empty") to ReadinessReport(TodaySynthesis.Readiness.NoNight, null)
        }
        val asleep = point?.sleepMinutes
        // A score or stress of 0 is "not computed", as upstream's card reads it (`s.sleepScore > 0`).
        val result = WellnessBalance.anchoredScore(
            WellnessBalance.Input(sleepScore = point?.sleepScore?.takeIf { it > 0 }, overnightStress = point?.stressScore?.takeIf { it > 0 }),
        )
        if (result == null) {
            val empty = if (isToday) "Last night has no sleep score, so there's no readiness today." else "That night has no sleep score, so there's no readiness for it."
            return ReadinessUi(heading, empty = empty, description = "$heading. $empty") to ReadinessReport(TodaySynthesis.Readiness.NoScore, asleep)
        }
        val factors = result.factors.mapNotNull { (factor, value) ->
            val label = when (factor) {
                WellnessBalance.Result.Factor.SLEEP -> "Sleep"
                WellnessBalance.Result.Factor.RECOVERY -> "Overnight recovery"
                WellnessBalance.Result.Factor.VITALS -> "Vitals"
                WellnessBalance.Result.Factor.ACTIVITY -> "Activity"
            }
            FactorUi(label, SwiftFormat.rounded(value * 100).toInt())
        }
        val tierWord = tierWord(result.tier)
        val note = if (result.factors.size <= 1) "Based on ${if (isToday) "last night's" else "that night's"} sleep alone" else null
        val spokenFactors = factors.mapIndexed { i, f -> "${if (i == 0) f.label else f.label.lowercase(Locale.ROOT)} ${f.percent}" }.joinToString(", ")
        val description = "$heading, estimate, ${result.score} out of 100, $tierWord. $spokenFactors" + (note?.let { ". $it" } ?: "")
        val ui = ReadinessUi(
            heading = heading, score = result.score, tier = result.tier, tierWord = tierWord, glyph = glyph(result.tier),
            fraction = result.score / 100f, factors = factors, note = note, description = description,
        )
        return ui to ReadinessReport(TodaySynthesis.Readiness.Scored(result.score, result.tier, result.factors.size), asleep)
    }

    private fun tierWord(tier: WellnessBalance.Tier): String = when (tier) {
        WellnessBalance.Tier.EXCELLENT -> "Excellent"
        WellnessBalance.Tier.GOOD -> "Good"
        WellnessBalance.Tier.NEEDS_IMPROVEMENT -> "Needs improvement"
    }

    /** A shape per tier, so the tier never rests on colour alone: a check, a level line, an alert. */
    private fun glyph(tier: WellnessBalance.Tier): String = when (tier) {
        WellnessBalance.Tier.EXCELLENT -> "✓"
        WellnessBalance.Tier.GOOD -> "–"
        WellnessBalance.Tier.NEEDS_IMPROVEMENT -> "!"
    }

    private val DATE_LINE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
    private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private const val NO_HEART_RATE = "No heart rate was stored for this day."
}
