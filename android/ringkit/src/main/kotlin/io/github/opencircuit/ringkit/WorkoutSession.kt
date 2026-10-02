package io.github.opencircuit.ringkit

// Pure workout analytics: HR-zone classification, time-in-zone, session aggregation, the HR backfill
// merge and the fill of a manual workout's gaps from the ring's buffered sport records. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/WorkoutSession.swift (@ b1c2fdd), whole. No
// health-store or location code; all app-framework concerns stay in the app.
//
// Zone boundaries match the APK's SportRecordModel / "Exercise Heart Rate" screen (pp.txt:0x515c0,
// confirmed):
//   Warm-up       50–60% of maxHR  (data below 50% not counted — APK note)
//   Fat burning   61–70% of maxHR
//   Aerobic       71–80% of maxHR
//   Anaerobic     81–90% of maxHR
//   Extreme       91–100% of maxHR
//
// maxHR = 220 − age  (APK pp.txt:0x515c0 calculation formula).
//
// Live-HR (#45) WARNING: the 0x95→0x15 live-HR path is best-effort — it has no background refresh and
// on-demand polling often misses updates. A long workout session is the worst case. This code records only
// ACTUAL decoded readings; it never fills gaps by interpolation or fabrication. Gaps are preserved and
// surfaced to the user. See issue #45 for the underlying reliability constraint.
//
// Shape notes. Swift structs are immutable values here (`val` + `copy`), comparing their doubles by IEEE
// `==` as Swift's synthesized `Equatable` does. `WorkoutSessionAggregator` was already a reference type
// upstream; here it is a mutable class owned by ONE live session, with [WorkoutSessionAggregator.copy] for
// an independent snapshot. Elapsed times are measured exactly between instants (the carried exact-elapsed-
// time rule), then read as `Double` seconds where upstream's arithmetic takes a `TimeInterval`. A step
// count summed over records is a `Long` (Swift's 64-bit `Int`). Ring cursors (Swift `UInt32`) are `Long`
// in 0..0xFFFFFFFF.

import java.time.Instant

// MARK: - Sport types

/**
 * Sport types the app supports. Each maps to a health-store exercise type on the app side. Outdoor types
 * (walking, running, cycling, hiking) enable GPS route capture; indoor types (strength, yoga, other) do
 * not. [rawValue] is upstream's stored case name, in declaration order.
 */
enum class WorkoutSportType(val rawValue: String) {
    WALKING_OUTDOOR("walkingOutdoor"),
    RUNNING_OUTDOOR("runningOutdoor"),
    RUNNING_INDOOR("runningIndoor"),
    CYCLING_OUTDOOR("cyclingOutdoor"),
    CYCLING_INDOOR("cyclingIndoor"),
    ROWING("rowing"),
    HIKING("hiking"),
    STRENGTH_TRAINING("strengthTraining"),
    YOGA("yoga"),
    OTHER("other"),
    ;

    val displayName: String
        get() = when (this) {
            WALKING_OUTDOOR -> "Outdoor Walking"
            RUNNING_OUTDOOR -> "Outdoor Running"
            RUNNING_INDOOR -> "Indoor Running"
            CYCLING_OUTDOOR -> "Outdoor Cycling"
            CYCLING_INDOOR -> "Indoor Cycling"
            ROWING -> "Indoor Rowing"
            HIKING -> "Hiking"
            STRENGTH_TRAINING -> "Strength"
            YOGA -> "Yoga"
            OTHER -> "Other"
        }

    /** Whether this sport type benefits from GPS route capture (phone-side). Indoor types capture no route. */
    val isOutdoor: Boolean
        get() = when (this) {
            WALKING_OUTDOOR, RUNNING_OUTDOOR, CYCLING_OUTDOOR, HIKING -> true
            RUNNING_INDOOR, CYCLING_INDOOR, ROWING, STRENGTH_TRAINING, YOGA, OTHER -> false
        }

    /** Upstream's SF Symbols icon name, verbatim (the Android app maps it to its own icon). */
    val systemImageName: String
        get() = when (this) {
            WALKING_OUTDOOR -> "figure.walk"
            RUNNING_OUTDOOR -> "figure.run"
            RUNNING_INDOOR -> "figure.run.treadmill"
            CYCLING_OUTDOOR -> "bicycle"
            CYCLING_INDOOR -> "figure.indoor.cycle"
            ROWING -> "figure.rower"
            HIKING -> "mountain.2"
            STRENGTH_TRAINING -> "dumbbell"
            YOGA -> "figure.yoga"
            OTHER -> "heart.circle"
        }

    /**
     * The ring's native sport-mode type byte for `Command.sportStart` (🟢 #90). Types the ring doesn't
     * natively support (hiking / strength / other) map to the closest ring mode — the byte only tunes the
     * ring's own HR sampling; the health-store activity type is chosen separately. 0..255.
     */
    val firmwareByte: Int
        get() = when (this) {
            RUNNING_OUTDOOR -> SportType.OUTDOOR_RUNNING.rawValue // 0x01
            WALKING_OUTDOOR -> SportType.OUTDOOR_WALKING.rawValue // 0x02
            RUNNING_INDOOR -> SportType.INDOOR_RUNNING.rawValue // 0x03
            CYCLING_OUTDOOR -> SportType.OUTDOOR_CYCLING.rawValue // 0x04
            CYCLING_INDOOR -> SportType.INDOOR_CYCLING.rawValue // 0x05
            ROWING -> SportType.INDOOR_ROWING.rawValue // 0x06
            YOGA -> SportType.YOGA.rawValue // 0x07
            HIKING -> SportType.OUTDOOR_WALKING.rawValue // ≈ outdoor walk
            STRENGTH_TRAINING, OTHER -> SportType.YOGA.rawValue // ≈ generic indoor
        }

    companion object {
        /** Upstream's `init?(rawValue:)`: the case stored under [rawValue], or null. */
        fun fromRawValue(rawValue: String): WorkoutSportType? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

// MARK: - HR Zones

/** Five HR zones matching the APK's SportRecordModel zone schema. "Below zone" (< 50% maxHR) is NOT counted. */
enum class HRZone(val rawValue: Int) {
    WARM_UP(1), // 50–60%
    FAT_BURN(2), // 61–70%
    AEROBIC(3), // 71–80%
    ANAEROBIC(4), // 81–90%
    EXTREME(5), // 91–100%
    ;

    val displayName: String
        get() = when (this) {
            WARM_UP -> "Warm-up"
            FAT_BURN -> "Fat Burning"
            AEROBIC -> "Aerobic"
            ANAEROBIC -> "Anaerobic"
            EXTREME -> "Extreme"
        }

    /** Color token names (used in the app UI). */
    val colorName: String
        get() = when (this) {
            WARM_UP -> "zoneBlue"
            FAT_BURN -> "zoneGreen"
            AEROBIC -> "zoneYellow"
            ANAEROBIC -> "zoneOrange"
            EXTREME -> "zoneRed"
        }

    /** Percentage LOWER bound (inclusive) of this zone, as a fraction of maxHR. */
    val lowerFraction: Double
        get() = when (this) {
            WARM_UP -> 0.50
            FAT_BURN -> 0.61
            AEROBIC -> 0.71
            ANAEROBIC -> 0.81
            EXTREME -> 0.91
        }

    /** Percentage UPPER bound (inclusive) of this zone, as a fraction of maxHR. */
    val upperFraction: Double
        get() = when (this) {
            WARM_UP -> 0.60
            FAT_BURN -> 0.70
            AEROBIC -> 0.80
            ANAEROBIC -> 0.90
            EXTREME -> 1.00
        }

    companion object {
        /** Upstream's `init?(rawValue:)`: the zone numbered [rawValue], or null. */
        fun fromRawValue(rawValue: Int): HRZone? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

// MARK: - Zone classifier

/** Pure functions for HR zone classification and time-in-zone accumulation. */
object HRZoneClassifier {

    /**
     * Classify a single BPM reading against maxHR, returning the zone (or null if below 50% of maxHR — per
     * the APK, sub-50% readings are not counted in the zone distribution).
     */
    fun zone(bpm: Int, maxHR: Int): HRZone? {
        if (maxHR <= 0 || bpm <= 0) return null
        val frac = bpm.toDouble() / maxHR.toDouble()
        for (zone in HRZone.entries.asReversed()) {
            if (frac >= zone.lowerFraction) return zone
        }
        return null // below 50% — not counted
    }

    /**
     * Accumulate time (seconds) spent in each zone from a list of timestamped HR samples. Only actual
     * decoded readings are counted — no gap filling, no interpolation. Gaps between samples (e.g. polling
     * misses due to #45 flakiness) are NOT attributed to any zone; the duration used for each sample is the
     * sample's own interval (end − start), contributing 0 if end == start (instantaneous reading).
     */
    fun timeInZones(hrSamples: List<HRSample>, maxHR: Int): WorkoutZoneBreakdown {
        val seconds = DoubleArray(HRZone.entries.size)
        for (sample in hrSamples) {
            val dur = secondsBetween(sample.start, sample.end)
            if (!(dur > 0)) continue
            val zone = zone(sample.bpm, maxHR) ?: continue
            seconds[zone.ordinal] += dur
        }
        return WorkoutZoneBreakdown.of(seconds)
    }

    /**
     * Default cap for a single reading's held interval (seconds). The sport stream lands ~every 10 s, so
     * 30 s absorbs a missed reading or jitter while refusing to invent zone time across a genuine dropout
     * (ring off-wrist / lost link).
     */
    const val DEFAULT_HOLD_CAP_SECONDS: Double = 30.0

    /**
     * Time-in-zone using STEP-FUNCTION (last-value-held) attribution — the fix for zone totals reading far
     * short of the workout (e.g. 0:50 for a 5:05 ride). The ring reports HR PERIODICALLY (~every 10 s in
     * sport mode), so each reading represents the interval it covers, not just the ~2 s poll window it was
     * stamped with. Each sample is held until the NEXT sample's timestamp; the final sample is held until
     * [sessionEnd].
     *
     * Every held interval is CAPPED at [maxGapSeconds] so a real dropout is never fabricated into zone
     * time — the total stays honest: ≈ the workout duration when HR is continuous, and legitimately less
     * when readings were actually missed. Time before the first reading is not attributed. Sub-50%-maxHR
     * readings contribute no zone time. Samples are sorted by start with a STABLE sort, as upstream's
     * `sorted` is: of two readings stamped alike, the first given is held 0 s and the second takes the
     * hold. The cap follows Swift's `min(max(held, 0), cap)`, which lets a NaN cap through (no cap).
     */
    fun timeInZonesHeld(
        hrSamples: List<HRSample>,
        maxHR: Int,
        sessionEnd: Instant,
        maxGapSeconds: Double = DEFAULT_HOLD_CAP_SECONDS,
    ): WorkoutZoneBreakdown {
        val seconds = DoubleArray(HRZone.entries.size)
        val sorted = hrSamples.sortedWith(compareBy { it.start })
        for ((i, sample) in sorted.withIndex()) {
            val nextAnchor = if (i + 1 < sorted.size) sorted[i + 1].start else sessionEnd
            val held = swiftMin(swiftMax(secondsBetween(sample.start, nextAnchor), 0.0), maxGapSeconds)
            if (!(held > 0)) continue
            val zone = zone(sample.bpm, maxHR) ?: continue
            seconds[zone.ordinal] += held
        }
        return WorkoutZoneBreakdown.of(seconds)
    }
}

// MARK: - Zone breakdown

/** Time-in-zone breakdown for one workout (seconds per zone). Compares its doubles by IEEE `==`. */
data class WorkoutZoneBreakdown(
    val warmUpSeconds: Double = 0.0,
    val fatBurnSeconds: Double = 0.0,
    val aerobicSeconds: Double = 0.0,
    val anaerobicSeconds: Double = 0.0,
    val extremeSeconds: Double = 0.0,
) {
    fun seconds(zone: HRZone): Double = when (zone) {
        HRZone.WARM_UP -> warmUpSeconds
        HRZone.FAT_BURN -> fatBurnSeconds
        HRZone.AEROBIC -> aerobicSeconds
        HRZone.ANAEROBIC -> anaerobicSeconds
        HRZone.EXTREME -> extremeSeconds
    }

    /** Total zone-counted seconds (excludes below-50% / not-in-zone intervals). */
    val totalZoneSeconds: Double
        get() = warmUpSeconds + fatBurnSeconds + aerobicSeconds + anaerobicSeconds + extremeSeconds

    /** Fraction 0…1 for a zone's share of total zone time; 0 when the total is not positive. */
    fun fraction(zone: HRZone): Double {
        val total = totalZoneSeconds
        if (!(total > 0)) return 0.0
        return seconds(zone) / total
    }

    override fun equals(other: Any?): Boolean =
        other is WorkoutZoneBreakdown && warmUpSeconds == other.warmUpSeconds && fatBurnSeconds == other.fatBurnSeconds &&
            aerobicSeconds == other.aerobicSeconds && anaerobicSeconds == other.anaerobicSeconds && extremeSeconds == other.extremeSeconds

    override fun hashCode(): Int =
        listOf(warmUpSeconds, fatBurnSeconds, aerobicSeconds, anaerobicSeconds, extremeSeconds).map(::ieeeHash).hashCode()

    internal companion object {
        /** Upstream's internal `init(secondsInZone:)`, from seconds indexed by zone order. */
        fun of(seconds: DoubleArray): WorkoutZoneBreakdown =
            WorkoutZoneBreakdown(seconds[0], seconds[1], seconds[2], seconds[3], seconds[4])
    }
}

// MARK: - Workout summary

/**
 * Completed workout summary. Produced after the session ends; never contains fabricated values. HR stats
 * derive solely from actual decoded samples — gaps from #45 polling flakiness are NOT filled or
 * interpolated. Compares its doubles by IEEE `==`.
 */
data class WorkoutSummary(
    /** Sport type selected by the user. */
    val sport: WorkoutSportType,
    /** Wall-clock start time of the session. */
    val startDate: Instant,
    /** Wall-clock end time of the session (when the user tapped Stop). */
    val endDate: Instant,
    /** Average BPM across all actual decoded HR readings (null if no readings were captured). */
    val avgHR: Int?,
    /** Maximum BPM recorded during the session (null if no readings). */
    val maxHR: Int?,
    /**
     * Estimated active calories (ESTIMATE — labelled as such in the UI): Keytel HR→energy over the workout
     * duration when HR was captured, else a distance × body-mass estimate. Null only when there is NEITHER
     * any HR reading NOR a distance — never fabricated.
     */
    val estimatedActiveKcal: Double?,
    /** 5-zone HR breakdown using held (step-function) attribution. See [HRZoneClassifier.timeInZonesHeld]. */
    val zoneBreakdown: WorkoutZoneBreakdown,
    /** GPS distance in metres (phone-side). Null for indoor sports or when location permission was denied. */
    val distanceMeters: Double?,
    /** Whether a GPS route was captured. */
    val hasRoute: Boolean,
    /** Count of actual HR readings captured during the session. */
    val hrSampleCount: Int,
    /** Steps counted by the ring during the workout (native sport mode, #90); null when not recorded that way. */
    val steps: Long? = null,
    /** True when the user's max HR (220 − age) was used for zone calculations. Always true here (APK formula). */
    val usedFormulaMaxHR: Boolean = true,
) {
    /** Elapsed time in seconds (wall-clock duration, including periods with no HR readings). */
    val durationSeconds: Double get() = secondsBetween(startDate, endDate)

    override fun equals(other: Any?): Boolean =
        other is WorkoutSummary && sport == other.sport && startDate == other.startDate && endDate == other.endDate &&
            avgHR == other.avgHR && maxHR == other.maxHR && ieeeEquals(estimatedActiveKcal, other.estimatedActiveKcal) &&
            zoneBreakdown == other.zoneBreakdown && ieeeEquals(distanceMeters, other.distanceMeters) &&
            hasRoute == other.hasRoute && hrSampleCount == other.hrSampleCount && steps == other.steps &&
            usedFormulaMaxHR == other.usedFormulaMaxHR

    override fun hashCode(): Int =
        listOf(
            sport, startDate, endDate, avgHR, maxHR, estimatedActiveKcal?.let(::ieeeHash), zoneBreakdown,
            distanceMeters?.let(::ieeeHash), hasRoute, hrSampleCount, steps, usedFormulaMaxHR,
        ).hashCode()
}

// MARK: - Session aggregator

/**
 * Builds a [WorkoutSummary] from accumulated HR samples. Call [add] as readings arrive, then [finalize] to
 * produce the summary. [finalize] does not consume the session: it may be called again, and readings added
 * afterwards keep accumulating, as upstream's do.
 *
 * OWNERSHIP. A mutable class owned by ONE live workout (upstream: a main-actor-confined class). Sharing
 * the reference shares the state; a caller that needs a snapshot takes [copy], which is fully independent
 * (its samples and its live high-water mark included). [collectedSamples] is a read-only snapshot.
 *
 * @param startDate when the session started (wall clock).
 * @param userAge used for maxHR = 220 − age (APK formula); any value is bounded to a max HR of 1…219.
 */
class WorkoutSessionAggregator(private val startDate: Instant, private val userAge: Int) {

    private val samples: MutableList<HRSample> = mutableListOf()

    // `max(220 − max(userAge, 1), 1)`: with the inner max no Kotlin `Int` age can overflow the subtraction.
    private val formulaMaxHR: Int = maxOf(220 - maxOf(userAge, 1), 1)

    /**
     * High-water mark backing [liveActiveKcal] so the DISPLAYED estimate never ticks DOWN. Per session: a
     * fresh aggregator (one per workout) starts at 0.
     */
    private var liveKcalHighWater: Double = 0.0

    /**
     * Record a decoded HR reading. `start` and `end` should bound the interval the sample represents. For
     * instantaneous poll results pass end == start; the reading still counts in avg / max.
     */
    fun add(sample: HRSample) {
        samples.add(sample)
    }

    /**
     * Merge real HR the ring already has for this workout's window (e.g. surfaced by a history sync) into
     * the captured set, de-duplicating by timestamp. NEVER interpolates or fabricates — when the store has
     * nothing for the window the captured samples are left untouched (#45).
     */
    fun backfill(stored: List<HRSample>, window: DateInterval) {
        val merged = WorkoutHRBackfill.merge(captured = samples, stored = stored, window = window)
        samples.clear()
        samples.addAll(merged)
    }

    /**
     * Produce the final [WorkoutSummary]. Safe to call with zero samples — all HR fields are null rather
     * than fabricated.
     */
    fun finalize(
        sport: WorkoutSportType,
        endDate: Instant,
        distanceMeters: Double?,
        hasRoute: Boolean,
        profile: UserProfile,
        steps: Long? = null,
    ): WorkoutSummary {
        val avgHR: Int?
        val maxHRValue: Int?
        val hrKcal: Double?
        if (samples.isEmpty()) {
            avgHR = null
            maxHRValue = null
            hrKcal = null
        } else {
            avgHR = averageBpm()
            maxHRValue = samples.maxOf { it.bpm }
            // Active calories: Keytel HR→energy over the workout's TRUE duration (endDate − startDate).
            // Positive for any real exertion; numeric (incl. 0) whenever HR was captured, so a workout with
            // real readings never shows "--". LABELLED an estimate in the UI.
            hrKcal = Calories.workoutActiveKcal(avgHR = avgHR, durationSeconds = secondsBetween(startDate, endDate), profile = profile)
        }
        // Distance-based active-energy fallback: a GPS walk / run / hike / cycle whose HR never locked would
        // otherwise show "--". Estimate from the measured distance + body mass instead (clearly labelled an
        // estimate) and surface the LARGER of the two — Swift's `[hr, distance].compactMap { $0 }.max()`,
        // which keeps the first unless a later one compares greater (so a NaN never displaces a number).
        val distKcal = if (distanceMeters != null && distanceMeters > 0) Calories.activeKcalFromDistance(distanceMeters, profile) else null
        val estimatedKcal = listOfNotNull(hrKcal, distKcal).let { ks -> if (ks.isEmpty()) null else swiftSequenceMax(ks) }

        val zones = HRZoneClassifier.timeInZonesHeld(hrSamples = samples, maxHR = formulaMaxHR, sessionEnd = endDate)
        return WorkoutSummary(
            sport = sport,
            startDate = startDate,
            endDate = endDate,
            avgHR = avgHR,
            maxHR = maxHRValue,
            estimatedActiveKcal = estimatedKcal,
            zoneBreakdown = zones,
            distanceMeters = distanceMeters,
            hasRoute = hasRoute,
            hrSampleCount = samples.size,
            steps = steps,
            usedFormulaMaxHR = true,
        )
    }

    /** All samples collected so far (for the health-store HR series write) — a read-only snapshot. */
    val collectedSamples: List<HRSample> get() = samples.toList()

    // MARK: Live snapshot (for the in-progress UI)

    /**
     * Running average BPM across all readings captured so far, or null if none yet. Same integer truncation
     * as [finalize], so the live number matches the final summary once the session ends.
     */
    val currentAvgHR: Int? get() = if (samples.isEmpty()) null else averageBpm()

    /**
     * Live active-calorie estimate as of [asOf] (wall clock), using the same Keytel HR→energy model as
     * [finalize] — running avg HR over the elapsed session so far — CLAMPED to a monotonic high-water so the
     * displayed number never ticks down. Returns the high-water (initially 0) when no HR has locked yet.
     * HR-only: the distance fallback [finalize] applies is not included.
     *
     * SIDE EFFECT: advances the internal high-water; call it as the live progressive read, not as a pure
     * query. With constant HR it equals the instantaneous value, so at session end it matches [finalize]'s
     * HR-based estimate for a distance-less (indoor) workout.
     */
    fun liveActiveKcal(profile: UserProfile, asOf: Instant): Double {
        val avg = currentAvgHR ?: return liveKcalHighWater
        val instantaneous = Calories.workoutActiveKcal(avgHR = avg, durationSeconds = secondsBetween(startDate, asOf), profile = profile)
        liveKcalHighWater = swiftMax(liveKcalHighWater, instantaneous)
        return liveKcalHighWater
    }

    /** An independent copy: later readings or live reads on either aggregator never reach the other. */
    fun copy(): WorkoutSessionAggregator {
        val c = WorkoutSessionAggregator(startDate, userAge)
        c.samples.addAll(samples)
        c.liveKcalHighWater = liveKcalHighWater
        return c
    }

    /** The bpm sum is a `Long` (Swift's 64-bit `Int`); its quotient truncates toward zero, as Swift's `/`. */
    private fun averageBpm(): Int = (samples.sumOf { it.bpm.toLong() } / samples.size).toInt()
}

// MARK: - HR backfill

/**
 * Pure merge of real stored HR into a workout's captured HR, for filling a workout window from the ring's
 * own on-device record when the live poll missed it (#45). The durable source is the all-day HR stream
 * decode (#99); until that lands this is typically empty for daytime windows, and that empty result is
 * preserved — never interpolated or fabricated.
 */
object WorkoutHRBackfill {
    /**
     * Captured + in-window stored HR, de-duplicated by `start` instant (captured / live wins on a tie; within
     * one list the later sample wins), sorted ascending. Stored samples outside [window] (closed at both
     * ends, as Foundation's `DateInterval.contains`) are ignored. The map's values are sorted on their
     * unique starts, so its iteration order never reaches the result.
     */
    fun merge(captured: List<HRSample>, stored: List<HRSample>, window: DateInterval): List<HRSample> {
        val byStart = LinkedHashMap<Instant, HRSample>()
        for (s in stored) if (window.containsClosed(s.start)) byStart[s.start] = s
        for (s in captured) byStart[s.start] = s // captured (live) wins on an exact-timestamp tie
        return byStart.values.sortedWith(compareBy { it.start })
    }
}

// MARK: - Buffered ring sport records → a manual workout (tester report 2026-09-27)

/**
 * Fill a MANUAL workout's live-HR gaps from the ring's own buffered 10-second sport records.
 *
 * WHY. A manual workout records HR only from the LIVE `0x4e` stream, so with the phone out of range it
 * records nothing — yet the ring keeps measuring and hands the 10-s records (`0x4d`,
 * [HistoricalSportFrame]) over on reconnect.
 *
 * THE RULE. A buffered record is used only where live data is ABSENT. Coverage is decided primarily by the
 * ring's OWN clock: a live `0x4e` frame whose cursor falls in the record's 10-s interval `(end − 10, end]`
 * covers it — HR AND steps, because the live path already summed that frame's steps whether or not it
 * yielded an HR sample. A captured HR sample ending inside the interval (± [LIVE_OVERLAP_SLACK]) also
 * covers it — the only signal for the cursor-less `0x95` fallback poll. Records outside the window, or
 * without a valid HR, contribute no HR (a record's steps still count if its interval is uncovered and
 * inside the window). Never interpolates (#45) — every value returned is one the ring measured.
 */
object WorkoutBufferedSportFill {
    /**
     * Slack (seconds) when testing live coverage of a 10-s record: the live snapshot stamps a ~2-s window at
     * its capture time and the `0x4e` cadence is ~10 s, so a record within one snapshot of a live reading is
     * treated as covered.
     */
    const val LIVE_OVERLAP_SLACK: Double = 2.0

    /** The fill's result: HR samples sorted by start, the steps summed (a `Long`), and the cursors consumed. */
    class Fill internal constructor(hrSamples: List<HRSample>, val steps: Long, cursors: Set<Long>) {
        val hrSamples: List<HRSample> = hrSamples.toList()

        /** Cursors consumed (HR or steps), so a caller merging repeatedly never adds one twice. */
        val cursors: Set<Long> = cursors.toSet()

        override fun equals(other: Any?): Boolean =
            other is Fill && hrSamples == other.hrSamples && steps == other.steps && cursors == other.cursors

        override fun hashCode(): Int = (hrSamples.hashCode() * 31 + steps.hashCode()) * 31 + cursors.hashCode()

        override fun toString(): String = "Fill(hrSamples=$hrSamples, steps=$steps, cursors=$cursors)"
    }

    fun fill(
        captured: List<HRSample>,
        buffered: List<HistoricalSportFrame.Sample>,
        window: DateInterval,
        alreadyMerged: Set<Long> = emptySet(),
        liveFrameCursors: Set<Long> = emptySet(),
    ): Fill {
        val hr = mutableListOf<HRSample>()
        var steps = 0L
        val used = LinkedHashSet<Long>()
        val span = HistoricalSportFrame.INTERVAL_WHOLE_SECONDS
        val slackNanos = (LIVE_OVERLAP_SLACK * 1e9).toLong()
        for (record in buffered) {
            if (record.cursor in alreadyMerged) continue
            val end = record.endDate
            val start = end.minusSeconds(span)
            if (start.isBefore(window.start) || end.isAfter(window.end)) continue
            // Upstream's `record.cursor >= $0` guard (WorkoutSession.swift:543) means the subtraction never
            // goes below zero, so nothing here wraps and nothing is masked.
            if ((0 until span).any { off -> record.cursor >= off && (record.cursor - off) in liveFrameCursors }) continue
            val lo = start.minusNanos(slackNanos)
            val hi = end.plusNanos(slackNanos)
            if (captured.any { !it.end.isBefore(lo) && !it.end.isAfter(hi) }) continue
            record.heartRate?.let { bpm -> hr.add(HRSample(bpm = bpm, start = start, end = end)) }
            steps += record.steps
            used.add(record.cursor)
        }
        return Fill(hrSamples = hr.sortedWith(compareBy { it.start }), steps = steps, cursors = used)
    }
}
