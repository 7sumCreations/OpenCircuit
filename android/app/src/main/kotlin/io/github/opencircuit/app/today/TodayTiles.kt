package io.github.opencircuit.app.today

import io.github.opencircuit.ringkit.BaselineTrend
import io.github.opencircuit.ringkit.RestingHR
import io.github.opencircuit.ringkit.SwiftFormat
import io.github.opencircuit.ringkit.TemperatureUnit
import io.github.opencircuit.ringkit.TrendsEngine
import io.github.opencircuit.ringkit.UnitsFormatter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Port of upstream ios/OpenCircuit/Dashboard/TodayTiles.swift (@ 2d07e550, the last MIT commit to
// touch it; the global pin stays b1c2fdd), plus `MetricDetailView.what` (MetricDetailView.swift:176
// @ 2d07e550) — the tile model: turns a two-week load into one tile per metric, each with its
// current value, a per-day series for the chart, and where the value sits against the user's own
// baseline (`BaselineTrend.usualRange`, so the band, its label and the arrow always agree).
//
// Which daily value each upstream tile uses: HRV / SpO₂ / resp. rate the OVERNIGHT average; skin
// temp the nightly value on the sleep summary; resting HR `RestingHR.dailyValues`; steps today's
// running total as the headline, the TREND on the last COMPLETE day (stated only when that day is
// yesterday). Nothing is interpolated: a day without data is a gap.
//
// Kotlin differences, each deliberate:
// - The calendar is an explicit `ZoneId` and `now` an explicit `Instant` (upstream reads
//   `Calendar.current` / `Date()`); days step by `LocalDate`, so a DST day is one day.
// - Upstream's per-device scope (strap ownership, decision 29) is dropped: every point counts, as on
//   every ring-only install (`scope = nil`).
// - Numbers print in ASCII whatever the default locale: whole values through Swift's `rounded()`,
//   one-decimal values through Swift's `%.1f` (`SwiftFormat`, ties to even on the binary value, so
//   36.55 prints "36.5" as on iOS), grouped values with an explicit locale ("1,200"), dates in
//   English ("Mon 28").
// - The icon and tint are the screen's (not the model's); the formatters are a [TileNumber], so two
//   equal tiles compare equal.
// - Two Android tiles beyond upstream's six (PORTING.md D-306): HEART_RATE (the day's average, its
//   low–high in [TodayTile.extra]; live heart rate stays in the hero) and SLEEP (the night's asleep
//   time keyed by its wake day, its sleep score in [TodayTile.extra]). Steps add a goal and a
//   "connected time only" line, and a missing total reads "Steps not recorded", never 0.

/** How a tile prints its numbers: the value and a signed difference, ASCII whatever the locale. */
enum class TileNumber {
    /** `String(Int(v.rounded()))`; deltas "+6" / "−6" / "±0". */
    WHOLE,

    /** `String(format: "%.1f", v)`; deltas rounded to a tenth first, "+0.4" / "−0.4" / "±0.0". */
    ONE_DECIMAL,

    /** A whole number with "," grouping ("1,200"). */
    GROUPED,

    /** Minutes as "7 h 05 m" ("45 m" under an hour). */
    DURATION,
    ;

    fun format(v: Double): String = when (this) {
        WHOLE -> whole(v).toString()
        ONE_DECIMAL -> SwiftFormat.fixed(v, 1)
        GROUPED -> grouped(whole(v))
        DURATION -> duration(whole(v))
    }

    fun formatDelta(d: Double): String = when (this) {
        WHOLE -> signed(whole(d)) { it.toString() }
        GROUPED -> signed(whole(d), ::grouped)
        DURATION -> signed(whole(d), ::duration)
        ONE_DECIMAL -> {
            val r = SwiftFormat.rounded(d * 10) / 10
            if (r > 0) SwiftFormat.fixed(r, 1, forceSign = true) else if (r < 0) MINUS + SwiftFormat.fixed(-r, 1) else "±0.0"
        }
    }

    private companion object {
        const val MINUS = "−"

        fun whole(v: Double): Long = SwiftFormat.rounded(v).toLong()

        fun signed(r: Long, text: (Long) -> String): String = if (r > 0) "+" + text(r) else if (r < 0) MINUS + text(-r) else "±" + text(0)

        fun grouped(n: Long): String = String.format(Locale.ROOT, "%,d", n)

        fun duration(minutes: Long): String =
            if (minutes < 60) "$minutes m" else "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')} m"
    }
}

/** One day's lowest and highest heart rate, bpm; [day] is the start of the day in the zone. */
data class DayRange(val day: Instant, val low: Double, val high: Double)

/** One Today tile: its value, its per-day series and where the value sits against the user's usual. */
data class TodayTile(
    val metric: Metric,
    val title: String,
    /** What the value is, in a few words ("overnight avg"). */
    val qualifier: String,
    /** The formatted current value, or null when there is no data at all. */
    val valueText: String?,
    val unit: String,
    /** The window's days, oldest first: the chart's x positions. */
    val days: List<Instant>,
    /** One slot per entry of [days]; null = no data that day. */
    val values: List<Double?>,
    /** The baseline comparison; null when there is no data. */
    val trend: BaselineTrend.Result?,
    /** For steps, the trend is about yesterday, not today's partial total. */
    val trendIsYesterday: Boolean,
    /** The day of the headline value. */
    val valueDate: Instant?,
    /** Nightly metrics are keyed on the day the night ENDED: "last night" is the one keyed today. */
    val isNightly: Boolean,
    /** The unit as a screen reader says it. */
    val spokenUnit: String,
    val number: TileNumber,
    /** An Android line under the value: the heart rate's low–high, the night's sleep score, the steps' goal. */
    val extra: String? = null,
    /**
     * The tile is for a past day the user picked (Android's day browser): its value is that day's
     * only, with no freshness (the heading names the day), and steps judge that whole day.
     */
    val pastDay: Boolean = false,
) {
    /** The metrics, in the dashboard's default order. Upstream's six, plus HEART_RATE and SLEEP (Android). */
    enum class Metric { HEART_RATE, HRV, RESTING_HR, SPO2, RESPIRATORY_RATE, SKIN_TEMP, SLEEP, STEPS }

    fun format(v: Double): String = number.format(v)

    fun formatDelta(d: Double): String = number.formatDelta(d)

    /** "last night" / "today" / "yesterday" / "as of Mon 28" / "night to Mon 28". */
    fun freshnessText(now: Instant, zone: ZoneId): String? {
        if (pastDay) return null
        val valueDate = valueDate ?: return null
        val today = LocalDate.ofInstant(now, zone)
        val d = LocalDate.ofInstant(valueDate, zone)
        if (isNightly) return if (d >= today) "last night" else "night to ${SHORT_DAY.format(d)}"
        if (d == today) return "today"
        if (d == today.minusDays(1)) return "yesterday"
        return "as of ${SHORT_DAY.format(d)}"
    }

    /**
     * True when the headline value is older than the most recent day it could be from: for a
     * nightly metric, anything but last night (keyed today); otherwise, older than yesterday.
     */
    fun isStale(now: Instant, zone: ZoneId): Boolean {
        if (pastDay) return false
        val valueDate = valueDate ?: return false
        val today = LocalDate.ofInstant(now, zone)
        return LocalDate.ofInstant(valueDate, zone) < (if (isNightly) today else today.minusDays(1))
    }

    /**
     * The trend whose verdict the tile may state: null while there is no judged baseline. Steps
     * headline today's partial total but judge the last COMPLETE day, so they state a verdict only
     * when that day is yesterday.
     */
    val statedTrend: BaselineTrend.Result?
        get() {
            val trend = trend ?: return null
            if (trend.direction == null) return null
            // A past day with nothing that day states no verdict about another day's value.
            if (pastDay) return if (valueText == null) null else trend
            return if (metric == Metric.STEPS && !trendIsYesterday) null else trend
        }

    /** "+6 ms vs usual" / "Yesterday −1,240 vs usual"; null when there is no verdict to state. */
    val deltaText: String?
        get() {
            val d = statedTrend?.delta ?: return null
            val unitPart = if (unit.isEmpty()) "" else " $unit"
            return (if (trendIsYesterday) "Yesterday " else "") + formatDelta(d) + unitPart + " vs usual"
        }

    /** "Usual 44–50 ms", the learning state while the baseline is thin, or why there is no value. */
    val rangeText: String
        get() {
            if (valueText == null) return if (metric == Metric.STEPS) STEPS_NOT_RECORDED else if (pastDay) NO_READING_THAT_DAY else "No data yet"
            val r = trend?.usualRange
            if (r != null) return "Usual ${format(r.start)}–${format(r.endInclusive)}${if (unit.isEmpty()) "" else " $unit"}"
            val need = BaselineTrend.DEFAULT_MIN_BASELINE_DAYS
            return "Learning your usual · ${minOf(trend?.baselineDays ?: 0, need)}/$need days"
        }

    /** What a screen reader says for the whole tile, in one sentence per part. */
    fun accessibilityLabel(now: Instant, zone: ZoneId): String {
        val parts = mutableListOf(title)
        if (valueText != null) {
            parts += listOf(valueText, spokenUnit).filter(String::isNotEmpty).joinToString(" ") + ", $qualifier"
            if (isStale(now, zone)) freshnessText(now, zone)?.let { parts += it }
            extra?.let { parts += it }
        } else {
            parts += when {
                metric == Metric.STEPS -> STEPS_NOT_RECORDED.lowercase(Locale.ROOT)
                pastDay -> NO_READING_THAT_DAY.lowercase(Locale.ROOT)
                else -> "no data yet"
            }
        }
        // The unit after a range, only when there is one (the sleep tile's durations carry their own).
        val unitWords = if (spokenUnit.isEmpty()) "" else " $spokenUnit"
        val stated = statedTrend
        val direction = stated?.direction
        val d = stated?.delta
        val r = stated?.usualRange
        if (direction != null && d != null && r != null) {
            val who = if (trendIsYesterday) "Yesterday was" else "That's"
            parts += "$who ${direction.rawValue} your usual range of ${format(r.start)} to ${format(r.endInclusive)}$unitWords, ${formatDelta(d)} versus your average"
        } else if (trend?.usualRange != null) {
            // A usual range, but no verdict to state about the headline (steps, yesterday missing).
            val usual = trend.usualRange!!
            parts += "Your usual range is ${format(usual.start)} to ${format(usual.endInclusive)}$unitWords"
        } else if (valueText != null) {
            parts += "still learning your usual range"
        }
        parts += "${values.count { it != null }} of the last ${values.size} days have data"
        return parts.joinToString(". ")
    }

    internal companion object {
        private val SHORT_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d", Locale.ENGLISH)

        /** A day with no stored step total: said instead of 0, on the tile and on the Steps ring. */
        const val STEPS_NOT_RECORDED = "Steps not recorded"
        private const val NO_READING_THAT_DAY = "No reading that day"
    }
}

/** Builds the Today tiles from a window of daily points (upstream `TodayTiles`). */
object TodayTiles {

    /** The shared two-week window (upstream `TrendsData.lookbackDays`). */
    const val WINDOW_DAYS: Int = 14

    /** A tile's name as its tile shows it (Edit order names tiles by it before any load); a test holds the two equal. */
    fun title(metric: TodayTile.Metric): String = when (metric) {
        TodayTile.Metric.HEART_RATE -> "Heart rate"
        TodayTile.Metric.HRV -> "HRV"
        TodayTile.Metric.RESTING_HR -> "Resting HR"
        TodayTile.Metric.SPO2 -> "SpO₂"
        TodayTile.Metric.RESPIRATORY_RATE -> "Resp. rate"
        TodayTile.Metric.SKIN_TEMP -> "Skin temp"
        TodayTile.Metric.SLEEP -> "Sleep"
        TodayTile.Metric.STEPS -> "Steps"
    }

    /** Every tile, in [TodayTile.Metric] order. */
    fun build(
        points: List<TrendsEngine.DailyPoint>,
        restingHR: List<RestingHR.DailyValue>,
        tempUnit: TemperatureUnit,
        windowDays: Int = WINDOW_DAYS,
        now: Instant,
        zone: ZoneId,
        heartRateRanges: List<DayRange> = emptyList(),
        stepGoal: Int? = null,
        shownDay: LocalDate? = null,
    ): List<TodayTile> = TodayTile.Metric.entries.map {
        build(it, points, restingHR, tempUnit, windowDays, now, zone, heartRateRanges, stepGoal, shownDay)
    }

    /**
     * One metric's tile over the [windowDays] days ending on [now]'s day in [zone] — or, for a
     * [shownDay] before that (Android's day browser), ending on [shownDay], showing that day's value
     * only and judging steps on that whole day.
     */
    fun build(
        metric: TodayTile.Metric,
        points: List<TrendsEngine.DailyPoint>,
        restingHR: List<RestingHR.DailyValue>,
        tempUnit: TemperatureUnit,
        windowDays: Int = WINDOW_DAYS,
        now: Instant,
        zone: ZoneId,
        heartRateRanges: List<DayRange> = emptyList(),
        stepGoal: Int? = null,
        shownDay: LocalDate? = null,
    ): TodayTile {
        val clockDay = LocalDate.ofInstant(now, zone)
        val pastDay = shownDay != null && shownDay.isBefore(clockDay)
        // From here "today" is the day the window ends on: the shown past day, or the clock's today.
        val todayDate = if (pastDay) shownDay!! else clockDay
        fun start(d: LocalDate): Instant = d.atStartOfDay(zone).toInstant()
        fun startOfDay(t: Instant): Instant = start(LocalDate.ofInstant(t, zone))
        val today = start(todayDate)
        val days = (maxOf(windowDays, 2) - 1 downTo 0).map { start(todayDate.minusDays(it.toLong())) }
        val first = days.first()
        val yesterday = start(todayDate.minusDays(1))

        fun inWindow(p: BaselineTrend.Point) = p.date >= first && p.date <= today
        fun series(pick: (TrendsEngine.DailyPoint) -> Double?): List<BaselineTrend.Point> = points.mapNotNull { p ->
            val v = pick(p) ?: return@mapNotNull null
            if (v > 0 && v.isFinite()) BaselineTrend.Point(startOfDay(p.date), v) else null
        }.filter(::inWindow)
        fun slots(pts: List<BaselineTrend.Point>): List<Double?> {
            val byDay = HashMap<Instant, Double>()
            for (p in pts) byDay[p.date] = p.value
            return days.map { byDay[it] }
        }
        fun tile(
            title: String,
            qualifier: String,
            unit: String,
            spokenUnit: String,
            pts: List<BaselineTrend.Point>,
            floor: Double,
            number: TileNumber,
            isNightly: Boolean = true,
            extra: (BaselineTrend.Point) -> String? = { null },
        ): TodayTile {
            val trend = BaselineTrend.evaluate(pts, minAbsoluteDelta = floor)
            // A past day shows its own value only; on today the newest in the window, however old.
            val shown = trend?.latest?.takeIf { !pastDay || it.date == today }
            return TodayTile(
                metric = metric, title = title, qualifier = qualifier, valueText = shown?.let { number.format(it.value) },
                unit = unit, days = days, values = slots(pts), trend = trend, trendIsYesterday = false,
                valueDate = shown?.date, isNightly = isNightly, spokenUnit = spokenUnit, number = number,
                extra = shown?.let(extra), pastDay = pastDay,
            )
        }

        return when (metric) {
            TodayTile.Metric.HRV ->
                tile("HRV", "overnight avg", "ms", "milliseconds", series { it.sleepHRVAvg }, 3.0, TileNumber.WHOLE)
            TodayTile.Metric.RESTING_HR -> {
                val pts = restingHR.map { BaselineTrend.Point(startOfDay(it.day), it.bpm) }.filter { it.value > 0 && inWindow(it) }
                tile("Resting HR", "daily estimate", "bpm", "beats per minute", pts, 2.0, TileNumber.WHOLE, isNightly = false)
            }
            TodayTile.Metric.SPO2 ->
                tile("SpO₂", "overnight avg", "%", "percent", series { p -> p.sleepSpO2Avg?.let { it * 100 } }, 1.0, TileNumber.WHOLE)
            TodayTile.Metric.RESPIRATORY_RATE ->
                tile(
                    "Resp. rate", "overnight avg", UnitsFormatter.RESPIRATORY_RATE_UNIT, "breaths per minute",
                    series { it.sleepRRAvg }, 0.5, TileNumber.ONE_DECIMAL,
                )
            TodayTile.Metric.SKIN_TEMP -> {
                // Converted to the user's unit BEFORE comparing, so the floor is in that unit too.
                val pts = series { it.skinTempC }.map { BaselineTrend.Point(it.date, tempUnit.convert(fromCelsius = it.value)) }
                tile(
                    "Skin temp", "overnight", tempUnit.symbol,
                    if (tempUnit == TemperatureUnit.FAHRENHEIT) "degrees Fahrenheit" else "degrees Celsius",
                    pts, tempUnit.convertDelta(fromCelsius = 0.3), TileNumber.ONE_DECIMAL,
                )
            }
            TodayTile.Metric.HEART_RATE -> {
                val ranges = heartRateRanges.associateBy { startOfDay(it.day) }
                val pts = series { it.dayHRAvg }
                tile(
                    "Heart rate", if (!pastDay && pts.any { it.date == today }) "today avg" else "day avg",
                    "bpm", "beats per minute", pts, 2.0, TileNumber.WHOLE, isNightly = false,
                ) { latest ->
                    ranges[latest.date]?.let { "Range ${TileNumber.WHOLE.format(it.low)}–${TileNumber.WHOLE.format(it.high)} bpm" }
                }
            }
            TodayTile.Metric.SLEEP -> {
                val scores = points.associate { startOfDay(it.date) to it.sleepScore }
                tile("Sleep", "asleep", "", "", series { p -> p.sleepMinutes?.toDouble() }, 30.0, TileNumber.DURATION) { latest ->
                    scores[latest.date]?.takeIf { it > 0 }?.let { "Sleep score $it" }
                }
            }
            TodayTile.Metric.STEPS -> if (pastDay) {
                // A past day is complete: its total is the headline and is judged against the days before it.
                val pts = series { p -> p.steps?.toDouble() }
                val day = pts.firstOrNull { it.date == today }
                TodayTile(
                    metric = metric, title = "Steps", qualifier = "that day",
                    valueText = day?.let { TileNumber.GROUPED.format(it.value) }, unit = "", days = days,
                    values = slots(pts), trend = BaselineTrend.evaluate(pts, minAbsoluteDelta = 1_000.0),
                    trendIsYesterday = false, valueDate = day?.date, isNightly = false,
                    spokenUnit = "steps", number = TileNumber.GROUPED, extra = "Connected time only", pastDay = true,
                )
            } else {
                // Headline = today's total; trend = the last complete day vs the days before it.
                val pts = series { p -> p.steps?.toDouble() }
                val trend = BaselineTrend.evaluate(pts.filter { it.date < today }, minAbsoluteDelta = 1_000.0)
                val todaySteps = pts.firstOrNull { it.date == today }
                val headline = todaySteps ?: pts.maxByOrNull { it.date }
                val goal = stepGoal?.takeIf { it > 0 && todaySteps != null }
                TodayTile(
                    metric = metric, title = "Steps", qualifier = if (todaySteps != null) "so far today" else "latest day",
                    valueText = headline?.let { TileNumber.GROUPED.format(it.value) }, unit = "", days = days,
                    // Complete days only: today's still-growing total would plot as a cliff.
                    values = slots(pts.filter { it.date < today }), trend = trend,
                    trendIsYesterday = trend?.latest?.date == yesterday, valueDate = headline?.date, isNightly = false,
                    spokenUnit = "steps", number = TileNumber.GROUPED,
                    extra = if (goal != null) "Goal ${TileNumber.GROUPED.format(goal.toDouble())} · connected time only" else "Connected time only",
                )
            }
        }
    }

    /** What the metric's number is, in plain words (upstream `MetricDetailView.what`; the two Android tiles' own). */
    fun what(metric: TodayTile.Metric): String = when (metric) {
        TodayTile.Metric.HRV -> "The average of your device's heart-rate-variability readings while you slept, one value per night."
        TodayTile.Metric.RESTING_HR -> "A daily resting heart rate: the day's lowest sustained heart rate (or its lowest reading when readings are sparse), the same estimate Vitals Status uses."
        TodayTile.Metric.SPO2 -> "The average of your device's blood-oxygen readings while you slept, one value per night."
        TodayTile.Metric.RESPIRATORY_RATE -> "The average of your device's breathing-rate readings while you slept, one value per night."
        TodayTile.Metric.SKIN_TEMP -> "Your device's skin temperature for each night. Skin temperature runs below core body temperature; what matters is the change from your usual."
        TodayTile.Metric.STEPS -> "Steps counted by your device each day. Today's total is still growing, so the comparison uses yesterday, the last complete day."
        TodayTile.Metric.HEART_RATE -> "Your average heart rate across the day, with its lowest and highest readings. Live heart rate is at the top of Today."
        TodayTile.Metric.SLEEP -> "How long you slept, one value per night, keyed to the day you woke. Sleep stages arrive with the Sleep tab later."
    }
}
