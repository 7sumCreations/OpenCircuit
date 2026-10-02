package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/Calories.swift (@ b1c2fdd),
// whole: `HRSample` (:3-13), the constants and energy functions of `:15-166` (step / distance energy,
// Mifflin-St Jeor BMR, the resting-HR–adjusted basal energy, TRIMP energy), the daily estimate with
// its time-attributed energy buckets (:168-482) and `workoutActiveKcal` (:484-497).
//
// Port notes:
//  • Swift `min` / `max` / `sorted()` / `rounded()` semantics (`swiftMin`, `swiftMax`, `swiftSorted`,
//    `roundHalfAwayFromZero`): a NaN weight gives a zero Keytel rate as upstream (Swift's
//    `max(0, NaN)` is 0, Kotlin's `maxOf` is NaN), and a NaN prior day is ordered exactly as Swift
//    orders it before the trim.
//  • One deliberate difference: `restingEnergyScale` reads a NaN or infinite resting HR or baseline
//    as missing (1.0), where upstream clamps it to −20 % or +20 % (PORTING.md D-71).
//  • Energy from an impossible profile or a non-finite duration is returned as upstream computes it;
//    range checks belong to the profile's entry point and the health writer (PORTING.md D-73).
//  • The daily estimate attributes energy only for bucket widths from one second to one billion
//    seconds, and only when the day's attribution span fits in `Instant`; otherwise the legacy
//    estimate answers (upstream walks every bucket of a tiny width without end, traps on the `Int`
//    conversion, or starts a bucket at NaN — PORTING.md D-74).
//  • Upstream's 64-bit `Int`: credited steps, the residual and the qualifying-bpm sum are `Long`;
//    the max HR from the age is computed in 64 bits and saturated to `Int` (PORTING.md D-76).

import java.time.Instant
import java.util.Collections
import kotlin.math.floor

/** One heart-rate reading over `[start, end]` (a point reading when they are equal). */
data class HRSample(val bpm: Int, val start: Instant, val end: Instant = start)

/** Energy estimates: basal (Mifflin-St Jeor, scaled by resting HR), TRIMP, Keytel and step / distance. */
object Calories {

    const val TRIMP_KCAL_FACTOR: Double = 5.0
    const val DEFAULT_RESTING_HR: Int = 60

    /**
     * Net (above-resting) walking economy: ≈ 0.5 kcal per kg of body mass per km walked (gross
     * ≈ 1.0 kcal·kg⁻¹·km⁻¹ minus the resting component, the standard pedometer constant). An
     * ESTIMATE that lets a day with walking still report active energy instead of 0.
     */
    const val WALK_KCAL_PER_KG_PER_KM: Double = 0.5

    /** Active kcal estimate from a walked / ran distance (metres) and body mass; 0 unless the distance is positive. */
    fun activeKcalFromDistance(meters: Double, profile: UserProfile): Double {
        if (!(meters > 0)) return 0.0
        return (meters / 1000.0) * profile.weightKg * WALK_KCAL_PER_KG_PER_KM
    }

    /** Active kcal estimate from a step count, via [DistanceEstimate]; 0 for a non-positive count. */
    fun activeKcalFromSteps(steps: Int, profile: UserProfile): Double =
        activeKcalFromDistance(DistanceEstimate.meters(steps), profile)

    /** Mifflin-St Jeor basal metabolic rate, kcal per day. */
    fun bmrKcalPerDay(profile: UserProfile): Double {
        val base = (10.0 * profile.weightKg) + (6.25 * profile.heightCm) - (5.0 * profile.age.toDouble())
        return when (profile.sex) {
            BiologicalSex.MALE -> base + 5.0
            BiologicalSex.FEMALE -> base - 161.0
        }
    }

    fun bmrKcalPerHour(profile: UserProfile): Double = bmrKcalPerDay(profile) / 24.0

    // Resting-HR–adjusted basal energy: the formula BMR is nudged by how far the day's MEASURED
    // resting HR sits from the person's own recent baseline (≈ 1 % per bpm, capped at ±20 %).
    // Still an ESTIMATE, labelled as such at every write site.

    /** Fractional change in resting energy per bpm of resting-HR deviation from baseline. */
    const val RESTING_ENERGY_FRACTION_PER_BPM: Double = 0.01

    /** Hard cap on how far measured resting HR may move basal energy off the formula value, either way. */
    const val MAX_RESTING_ENERGY_ADJUSTMENT: Double = 0.20

    /** Fewest PRIOR daily resting-HR readings before a personal baseline is trusted. */
    const val MIN_RESTING_BASELINE_DAYS: Int = 3

    /** Fewest PRIOR days before the baseline's trimmed mean actually trims (below it: the plain mean). */
    const val MIN_TRIMMED_BASELINE_DAYS: Int = 5

    /**
     * Personal resting-HR baseline (mean bpm) from PRIOR daily resting-HR values; `null` below
     * [minDays] (or for a non-positive [minDays]). Below [MIN_TRIMMED_BASELINE_DAYS] it is the plain
     * mean of every day; from there a 10 % trimmed mean (at least one value off each end).
     */
    fun restingBaselineBpm(prior: List<Double>, minDays: Int = MIN_RESTING_BASELINE_DAYS): Double? {
        if (prior.size < minDays || minDays <= 0) return null
        val sorted = swiftSorted(prior)
        if (sorted.size < MIN_TRIMMED_BASELINE_DAYS) return sum(sorted, 0, sorted.size) / sorted.size.toDouble()
        val trimCount = maxOf(1, sorted.size / 10)
        return if (sorted.size > 2 * trimCount) {
            sum(sorted, trimCount, sorted.size - trimCount) / (sorted.size - 2 * trimCount).toDouble()
        } else {
            sum(sorted, 0, sorted.size) / sorted.size.toDouble()
        }
    }

    /** Sum of `a[from until to]` in index order, from 0.0, as Swift's `reduce(0, +)`. */
    private fun sum(a: DoubleArray, from: Int, to: Int): Double {
        var s = 0.0
        for (k in from until to) s += a[k]
        return s
    }

    /**
     * Multiplier on the static BMR from the day's measured resting HR against the personal
     * baseline; 1.0 is no change. Returns 1.0 when either input is missing — `null`, NaN or
     * infinite (upstream clamps a NaN or infinite reading instead; PORTING.md D-71) — or the
     * baseline is not above zero, and is clamped to ±[MAX_RESTING_ENERGY_ADJUSTMENT].
     */
    fun restingEnergyScale(restingHR: Double?, baselineRestingHR: Double?): Double {
        if (restingHR == null || baselineRestingHR == null || !(baselineRestingHR > 0)) return 1.0
        if (!restingHR.isFinite() || !baselineRestingHR.isFinite()) return 1.0
        val raw = 1.0 + RESTING_ENERGY_FRACTION_PER_BPM * (restingHR - baselineRestingHR)
        val lo = 1.0 - MAX_RESTING_ENERGY_ADJUSTMENT
        val hi = 1.0 + MAX_RESTING_ENERGY_ADJUSTMENT
        return swiftMin(hi, swiftMax(lo, raw))
    }

    /**
     * Basal (passive) energy for ONE hour: the per-hour BMR scaled by [restingEnergyScale]; exactly
     * the static per-hour BMR when the resting HR or its baseline is unavailable.
     */
    fun basalKcalPerHour(profile: UserProfile, restingHR: Double? = null, baselineRestingHR: Double? = null): Double =
        bmrKcalPerHour(profile) * restingEnergyScale(restingHR, baselineRestingHR)

    /** TRIMP energy over [DEFAULT_RESTING_HR]; 0 when the TRIMP is unavailable (too few samples, or `maxHR` not above it). */
    fun activeKcal(hrSamples: List<HRSample>, maxHR: Int): Double {
        val trimp = Strain.edwardsTRIMP(hrSamples, maxHR = maxHR, restingHR = DEFAULT_RESTING_HR) ?: return 0.0
        return trimp * TRIMP_KCAL_FACTOR
    }

    // The daily activity estimate. Elevated-HR minutes and HR active energy price the SAME qualifying
    // periods (`ExerciseMinutes.elevatedPieces`) with the Keytel model a recorded workout uses, so a
    // moderate session can no longer earn exercise time but zero HR calories.

    /**
     * One internally consistent daily activity estimate. [buckets] is the chronological,
     * non-overlapping time attribution of [activeKcal] — their sum equals it whenever attribution ran,
     * and the list is EMPTY when the inputs could not support it (see [dailyEstimate]). A value, as
     * upstream's struct: [buckets] is copied in and read-only out; doubles compare by IEEE `==`.
     */
    class DailyEstimate(val activeKcal: Double, val elevatedMinutes: Double, buckets: List<EnergyBucket> = emptyList()) {
        val buckets: List<EnergyBucket> = Collections.unmodifiableList(ArrayList(buckets))

        override fun equals(other: Any?): Boolean =
            other is DailyEstimate && activeKcal == other.activeKcal && elevatedMinutes == other.elevatedMinutes &&
                buckets == other.buckets

        override fun hashCode(): Int = listOf(ieeeHash(activeKcal), ieeeHash(elevatedMinutes), buckets).hashCode()

        override fun toString(): String = "DailyEstimate(activeKcal=$activeKcal, elevatedMinutes=$elevatedMinutes, buckets=$buckets)"
    }

    /**
     * Width of one attribution bucket, in seconds — PLACEMENT metadata only: the day total does not
     * depend on it. 15 min divides an hour exactly, so a bucket never straddles two hour bars.
     */
    const val ENERGY_BUCKET_SECONDS: Double = 15.0 * 60

    /** How far past the day start attribution will place energy, in seconds — wider than any day (25 h at a fall-back). */
    internal const val MAX_ATTRIBUTION_SECONDS: Double = 26.0 * 3600

    /** Narrowest bucket the attribution accepts, in seconds; below it the legacy estimate answers (PORTING.md D-74). */
    internal const val MIN_ATTRIBUTION_BUCKET_SECONDS: Double = 1.0

    /** Widest bucket the attribution accepts, in seconds; above it the legacy estimate answers (PORTING.md D-74). */
    internal const val MAX_ATTRIBUTION_BUCKET_SECONDS: Double = 1e9

    /**
     * One slice of the day with the active energy attributed to it, split by source: [hrKcal] is the
     * Keytel energy of the elevated-HR time inside it, [stepKcal] the walking energy credited here
     * (already netted against [hrKcal] where the two overlap). Doubles compare by IEEE `==`.
     */
    class EnergyBucket(
        val start: Instant,
        val end: Instant,
        val hrKcal: Double,
        val stepKcal: Double,
        val elevatedMinutes: Double,
    ) {
        val activeKcal: Double get() = hrKcal + stepKcal

        override fun equals(other: Any?): Boolean =
            other is EnergyBucket && start == other.start && end == other.end && hrKcal == other.hrKcal &&
                stepKcal == other.stepKcal && elevatedMinutes == other.elevatedMinutes

        override fun hashCode(): Int =
            listOf(start, end, ieeeHash(hrKcal), ieeeHash(stepKcal), ieeeHash(elevatedMinutes)).hashCode()

        override fun toString(): String =
            "EnergyBucket(start=$start, end=$end, hrKcal=$hrKcal, stepKcal=$stepKcal, elevatedMinutes=$elevatedMinutes)"
    }

    /**
     * The day's activity estimate. Pass [stepWindows] + [dayStart] to get TIME-ATTRIBUTED energy;
     * omit them (or have steps with no windows) and the result is the legacy estimate, with no
     * buckets — a call site or a day that cannot be attributed produces exactly the old number.
     */
    fun dailyEstimate(
        hrSamples: List<HRSample>,
        steps: Int,
        profile: UserProfile,
        sleepWindow: DateInterval? = null,
        stepWindows: List<StepWindow> = emptyList(),
        dayStart: Instant? = null,
        bucketSeconds: Double = ENERGY_BUCKET_SECONDS,
    ): DailyEstimate {
        if (dayStart != null && bucketSeconds > 0 && (steps == 0 || stepWindows.isNotEmpty())) {
            attributedDailyEstimate(hrSamples, steps, profile, sleepWindow, stepWindows, dayStart, bucketSeconds)?.let { return it }
        }
        return legacyDailyEstimate(hrSamples, steps, profile, sleepWindow)
    }

    /** Upstream's `max(220 - age, 1)` in 64 bits, saturated to `Int` (only an age below −2 147 483 427 saturates). */
    private fun maxHRFor(profile: UserProfile): Int = (220L - profile.age).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()

    /**
     * The pre-attribution estimate, kept verbatim as the degrade path: `max(hrKcal, stepKcal)` over
     * the whole day, where `hrKcal` prices the elevated minutes at the rounded mean qualifying bpm.
     */
    fun legacyDailyEstimate(
        hrSamples: List<HRSample>,
        steps: Int,
        profile: UserProfile,
        sleepWindow: DateInterval? = null,
    ): DailyEstimate {
        val maxHR = maxHRFor(profile)
        val elevatedMinutes = ExerciseMinutes.estimate(hrSamples, maxHR, sleepWindow)
        // The SAME threshold `elevatedPieces` used inside `estimate`, resolved through the switch.
        val threshold = ExerciseMinutes.threshold(maxHR, ExerciseMinutes.effectiveRestingBaseline(hrSamples))
        var qualifyingSum = 0L
        var qualifyingCount = 0
        for (s in hrSamples) {
            if (s.bpm >= threshold && (sleepWindow == null || !sleepWindow.containsClosed(s.start))) {
                qualifyingSum += s.bpm
                qualifyingCount++
            }
        }
        val hrKcal = if (elevatedMinutes > 0 && qualifyingCount > 0) {
            val average = roundHalfAwayFromZero(qualifyingSum.toDouble() / qualifyingCount.toDouble()).toInt()
            workoutActiveKcal(avgHR = average, durationSeconds = elevatedMinutes * 60, profile = profile)
        } else {
            0.0
        }
        val stepKcal = activeKcalFromSteps(steps, profile)
        return DailyEstimate(activeKcal = swiftMax(hrKcal, stepKcal), elevatedMinutes = elevatedMinutes)
    }

    /**
     * Time-attributed estimate, or null when the inputs cannot be attributed at all (the caller then
     * falls back to [legacyDailyEstimate]). Per bucket: `hrKcal` = Keytel priced on EACH elevated
     * piece's own bpm; `stepKcal` = walking energy where the wearer was not elevated, plus, where they
     * overlap, the excess the HR channel did not already price. Everything is linear in duration, so
     * splitting a span across bucket edges cannot change the day total.
     */
    internal fun attributedDailyEstimate(
        hrSamples: List<HRSample>,
        steps: Int,
        profile: UserProfile,
        sleepWindow: DateInterval?,
        stepWindows: List<StepWindow>,
        dayStart: Instant,
        bucketSeconds: Double,
    ): DailyEstimate? {
        // Bounded at the edge (PORTING.md D-74): a tiny width would walk billions of buckets, an
        // enormous or infinite one would put bucket edges outside `Instant` (upstream: NaN).
        if (!(bucketSeconds >= MIN_ATTRIBUTION_BUCKET_SECONDS && bucketSeconds <= MAX_ATTRIBUTION_BUCKET_SECONDS)) return null
        if ((Instant.MAX.epochSecond - dayStart.epochSecond).toDouble() <= MAX_ATTRIBUTION_SECONDS + 2 * bucketSeconds) return null

        val maxHR = maxHRFor(profile)
        val pieces = ExerciseMinutes.elevatedPieces(hrSamples, maxHR, sleepWindow)
        var elevatedSeconds = 0.0
        for (p in pieces) elevatedSeconds += p.seconds
        val elevatedMinutes = elevatedSeconds / 60.0
        val dayEnd = addingSeconds(dayStart, MAX_ATTRIBUTION_SECONDS)!!

        fun ordinal(t: Instant): Int = floor(secondsBetween(dayStart, t) / bucketSeconds).toInt()
        fun bucketStart(o: Int): Instant = addingSeconds(dayStart, o.toDouble() * bucketSeconds)!!

        // Spread [total] across the buckets `[from, to)` covers, in proportion to the time spent in
        // each. A zero-length span lands wholly in the bucket containing it.
        fun spread(from: Instant, to: Instant, total: Double, into: HashMap<Int, Double>) {
            if (total == 0.0) return
            val lo = if (dayStart >= from) dayStart else from
            val hi = if (dayEnd < to) dayEnd else to
            if (!(hi > lo)) {
                if (from >= dayStart && from < dayEnd) {
                    val o = ordinal(from)
                    into[o] = (into[o] ?: 0.0) + total
                }
                return
            }
            val span = secondsBetween(lo, hi)
            var cursor = lo
            var o = ordinal(lo)
            while (cursor < hi) {
                val next = bucketStart(o + 1)
                val edge = if (hi < next) hi else next
                into[o] = (into[o] ?: 0.0) + total * (secondsBetween(cursor, edge) / span)
                cursor = edge
                o += 1
            }
        }

        val hrByOrdinal = HashMap<Int, Double>()
        val minutesByOrdinal = HashMap<Int, Double>()
        val stepByOrdinal = HashMap<Int, Double>()

        for (piece in pieces) {
            val kcal = workoutActiveKcal(avgHR = piece.bpm, durationSeconds = piece.seconds, profile = profile)
            spread(piece.start, piece.end, kcal, hrByOrdinal)
            spread(piece.start, piece.end, piece.seconds / 60.0, minutesByOrdinal)
        }

        // Steps, netted against the elevated time they overlap. Prorated on METRES, never on the step
        // count — splitting an integer at a boundary truncates and quietly loses steps.
        var creditedSteps = 0L
        for (window in stepWindows) {
            if (window.delta <= 0) continue
            val lo = if (dayStart >= window.start) dayStart else window.start
            val hi = if (dayEnd < window.end) dayEnd else window.end
            if (!(window.start < dayEnd && window.end >= dayStart)) continue
            val metres = window.delta.toDouble() * DistanceEstimate.METERS_PER_STEP

            if (!(hi > lo)) { // point snapshot: nothing to net against, credit it whole
                creditedSteps += window.delta
                spread(lo, lo, activeKcalFromDistance(metres, profile), stepByOrdinal)
                continue
            }

            // Prorate against the window's FULL span, not the clipped one: a snapshot that opened
            // before midnight earned only the share of its steps that fell inside the day.
            val fullSpan = secondsBetween(window.start, window.end)
            val duration = if (fullSpan > 0) fullSpan else secondsBetween(lo, hi)
            creditedSteps += roundHalfAwayFromZero(window.delta.toDouble() * (secondsBetween(lo, hi) / duration)).toLong()
            var cursor = lo
            var idx = 0
            while (cursor < hi) {
                while (idx < pieces.size && pieces[idx].end <= cursor) idx++
                val piece = if (idx < pieces.size) pieces[idx] else null
                val segmentEnd: Instant
                val overlapped: ExerciseMinutes.ElevatedPiece?
                if (piece != null && piece.start <= cursor) {
                    segmentEnd = if (hi < piece.end) hi else piece.end
                    overlapped = piece
                } else if (piece != null && piece.start < hi) {
                    segmentEnd = piece.start
                    overlapped = null
                } else {
                    segmentEnd = hi
                    overlapped = null
                }
                if (!(segmentEnd > cursor)) break

                val seconds = secondsBetween(cursor, segmentEnd)
                val segmentKcal = activeKcalFromDistance(metres * (seconds / duration), profile)
                val credit = if (overlapped != null) {
                    val alreadyPriced = workoutActiveKcal(avgHR = overlapped.bpm, durationSeconds = seconds, profile = profile)
                    swiftMax(0.0, segmentKcal - alreadyPriced)
                } else {
                    segmentKcal
                }
                spread(cursor, segmentEnd, credit, stepByOrdinal)
                cursor = segmentEnd
            }
        }

        // Steps the daily counter knows about but no snapshot placed in time: credited at the earliest
        // bucket that already holds activity, never at midnight.
        val residual = steps.toLong() - creditedSteps
        if (residual > 0) {
            val kcal = activeKcalFromDistance(residual.toDouble() * DistanceEstimate.METERS_PER_STEP, profile)
            val earliest = listOfNotNull(stepByOrdinal.keys.minOrNull(), hrByOrdinal.keys.minOrNull()).minOrNull() ?: return null
            stepByOrdinal[earliest] = (stepByOrdinal[earliest] ?: 0.0) + kcal
        }

        val ordinals = (hrByOrdinal.keys + stepByOrdinal.keys).toSortedSet()
        if (ordinals.isEmpty()) return null
        val buckets = ordinals.map { o ->
            EnergyBucket(
                start = bucketStart(o),
                end = bucketStart(o + 1),
                hrKcal = hrByOrdinal[o] ?: 0.0,
                stepKcal = stepByOrdinal[o] ?: 0.0,
                elevatedMinutes = minutesByOrdinal[o] ?: 0.0,
            )
        }
        var total = 0.0
        for (b in buckets) total += b.activeKcal
        return DailyEstimate(activeKcal = total, elevatedMinutes = elevatedMinutes, buckets = buckets)
    }

    /**
     * Workout active energy from average HR and duration with the Keytel (2005) HR→energy model
     * (kJ·min⁻¹; W = body mass kg, A = age years):
     *   men:   −55.0969 + 0.6309·HR + 0.1988·W + 0.2017·A
     *   women: −20.4022 + 0.4472·HR − 0.1263·W + 0.0740·A
     * kcal = kJ / 4.184; the per-minute rate is clamped to ≥ 0. Returns 0 for a non-positive HR or
     * duration. Chosen over Edwards-TRIMP for calories because TRIMP gives zero weight below 50 %
     * heart-rate reserve. An ESTIMATE.
     */
    fun workoutActiveKcal(avgHR: Int, durationSeconds: Double, profile: UserProfile): Double {
        if (avgHR <= 0 || !(durationSeconds > 0)) return 0.0
        val hr = avgHR.toDouble()
        val w = profile.weightKg
        val a = profile.age.toDouble()
        val kJPerMin = when (profile.sex) {
            BiologicalSex.MALE -> -55.0969 + 0.6309 * hr + 0.1988 * w + 0.2017 * a
            BiologicalSex.FEMALE -> -20.4022 + 0.4472 * hr - 0.1263 * w + 0.0740 * a
        }
        val kcalPerMin = swiftMax(0.0, kJPerMin / 4.184)
        return kcalPerMin * (durationSeconds / 60.0)
    }
}
