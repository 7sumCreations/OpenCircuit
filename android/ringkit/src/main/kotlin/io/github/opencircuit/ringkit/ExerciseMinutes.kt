package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/ExerciseMinutes.swift
// (@ b1c2fdd), whole.
//
// Estimate Apple Exercise Time (elevated-HR minutes) from stored HR samples — a BASIC threshold
// model: minutes where HR ≥ 50 % of max HR (brisk walking, Apple's own exercise definition), using
// only decoded HR samples and EXCLUDING the overnight sleep window. The full 4-level intensity
// mapping waits on the still-uncaptured activity record (PROTOCOL.md §5.3.1); do not invent
// intensity bands from this threshold.
//
// Port notes:
//  • The personalised (heart-rate-reserve) threshold ships OFF, as upstream: read upstream's comment
//    on `personalisedThresholdEnabled` before turning it on (it measured the model as wrong in the
//    other direction — 95 elevated minutes to 0 on an unchanged day).
//  • Widths (`epochSeconds`, `pointSampleWidth`) are `Double` seconds as upstream's `TimeInterval`.
//    A widened point whose end would pass the end of `Instant`'s range ends there (upstream's `Date`
//    reaches infinity); a NaN, zero or negative width gives a point no time, as upstream
//    (PORTING.md D-75).
//  • The sleep window is Foundation's closed `DateInterval.contains` ([DateInterval.containsClosed]).

import java.time.Instant

object ExerciseMinutes {

    /**
     * THE PERSONALISED THRESHOLD IS OFF. `false` ⇒ the %-of-max model, byte-identical to upstream's
     * shipped model. The ONE switch: [elevatedPieces], [estimate] and `Calories` all resolve their
     * baseline through [effectiveRestingBaseline], so nothing can be left half-converted.
     */
    const val PERSONALISED_THRESHOLD_ENABLED: Boolean = false

    /** Fraction of HEART-RATE RESERVE at which a reading counts as elevated. Dormant while the switch is off. */
    const val HR_RESERVE_FRACTION: Double = 0.40

    /**
     * The baseline every consumer must resolve through, so the switch cannot be honoured in one place
     * and ignored in another: [restingBaseline] when [derive], else null (the %-of-max model).
     */
    fun effectiveRestingBaseline(hrSamples: List<HRSample>, derive: Boolean = PERSONALISED_THRESHOLD_ENABLED): Double? =
        if (derive) restingBaseline(hrSamples) else null

    /** Plausibility band for a derived resting HR; outside it the %-of-max model answers. */
    internal val PLAUSIBLE_RESTING_HR: ClosedFloatingPointRange<Double> = 35.0..90.0

    /** Minimum readings before a derived resting baseline is trusted. */
    internal const val MIN_RESTING_BASELINE_SAMPLES: Int = 12

    /** Minimum span (seconds) the readings must cover before a derived resting baseline is trusted — 2 h. */
    internal const val MIN_RESTING_BASELINE_SPAN: Double = 2.0 * 3600

    /**
     * HR threshold for exercise, in bpm. With [restingHR] null (or implausible, or not below the max)
     * this is the original model, 50 % of max HR; otherwise Karvonen's `RHR + fraction · (maxHR − RHR)`.
     * Never below 60 bpm. The conversion truncates toward zero, as upstream's `Int(_:)`.
     */
    fun threshold(maxHR: Int, restingHR: Double? = null): Int {
        val mx = maxOf(maxHR, 1).toDouble()
        if (restingHR == null || restingHR !in PLAUSIBLE_RESTING_HR || !(restingHR < mx)) {
            return maxOf((mx * 0.5).toInt(), 60)
        }
        return maxOf((restingHR + HR_RESERVE_FRACTION * (mx - restingHR)).toInt(), 60)
    }

    /**
     * The resting-HR baseline to price a day's elevated time against, derived from the SAME HR samples
     * the estimate is computed over: the lowest rolling 5-min mean, or null — too few samples, too short
     * a span, no genuinely sustained window, or a value outside [PLAUSIBLE_RESTING_HR].
     */
    fun restingBaseline(hrSamples: List<HRSample>): Double? {
        val valid = hrSamples.filter { it.bpm in LiveHR.VALID_BPM }
        if (valid.size < MIN_RESTING_BASELINE_SAMPLES) return null
        val first = valid.minOf { it.start }
        val last = valid.maxOf { it.start }
        if (!(secondsBetween(first, last) >= MIN_RESTING_BASELINE_SPAN)) return null
        val derived = RestingHR.lowestSustainedDetailed(valid, RestingHR.SUSTAINED_WINDOW) ?: return null
        if (!derived.wasSustained) return null
        return if (derived.value in PLAUSIBLE_RESTING_HR) derived.value else null
    }

    /**
     * Exercise minutes: the total duration of [elevatedPieces] (samples at or above the threshold and
     * outside the sleep window, point samples widened to an epoch only inside a run, overlaps merged).
     * An ESTIMATE based on the available HR samples only.
     */
    fun estimate(
        hrSamples: List<HRSample>,
        maxHR: Int,
        sleepWindow: DateInterval? = null,
        epochSeconds: Double = BulkRecord.EPOCH_SECONDS.toDouble(),
        pointSampleWidth: Double = 0.0,
        restingHR: Double? = null,
        deriveRestingHR: Boolean = PERSONALISED_THRESHOLD_ENABLED,
    ): Double {
        var seconds = 0.0
        for (p in elevatedPieces(hrSamples, maxHR, sleepWindow, epochSeconds, pointSampleWidth, restingHR, deriveRestingHR)) {
            seconds += p.seconds
        }
        return seconds / 60.0
    }

    /** One disjoint slice of elevated-HR time, carrying the bpm that priced it. */
    data class ElevatedPiece(val start: Instant, val end: Instant, val bpm: Int) {
        /** `end - start` in seconds, never negative. */
        val seconds: Double get() = swiftMax(0.0, secondsBetween(start, end))
    }

    /**
     * The elevated intervals [estimate] sums, as NON-OVERLAPPING, chronologically ordered slices that
     * each carry their own bpm. A real-span sample uses its span; a point sample (or one that ends
     * before it starts) gets [epochSeconds] when it neighbours another elevated reading within one
     * epoch, else [pointSampleWidth]. Where two elevated samples cover the same instant the EARLIER
     * keeps it. [restingHR] overrides the derived baseline; `deriveRestingHR = false` is the switch.
     */
    fun elevatedPieces(
        hrSamples: List<HRSample>,
        maxHR: Int,
        sleepWindow: DateInterval? = null,
        epochSeconds: Double = BulkRecord.EPOCH_SECONDS.toDouble(),
        pointSampleWidth: Double = 0.0,
        restingHR: Double? = null,
        deriveRestingHR: Boolean = PERSONALISED_THRESHOLD_ENABLED,
    ): List<ElevatedPiece> {
        val effectiveRHR = restingHR ?: effectiveRestingBaseline(hrSamples, derive = deriveRestingHR)
        val thresh = threshold(maxHR, effectiveRHR)
        val elevated = hrSamples
            .filter { s -> s.bpm >= thresh && (sleepWindow == null || !sleepWindow.containsClosed(s.start)) }
            .sortedBy { it.start } // stable, as Swift's sort
        if (elevated.isEmpty()) return emptyList()

        val pieces = mutableListOf<ElevatedPiece>()
        var cursor: Instant? = null
        for ((idx, s) in elevated.withIndex()) {
            val end: Instant? = if (secondsBetween(s.start, s.end) > 0) {
                s.end
            } else {
                val prevClose = idx > 0 && secondsBetween(elevated[idx - 1].start, s.start) <= epochSeconds
                val nextClose = idx < elevated.size - 1 && secondsBetween(s.start, elevated[idx + 1].start) <= epochSeconds
                addingSeconds(s.start, if (prevClose || nextClose) epochSeconds else pointSampleWidth)
            }
            // Collapse overlaps with a cursor: each interval contributes only the part not yet covered.
            val start = cursor?.let { if (s.start > it) s.start else it } ?: s.start
            if (end == null || end <= start) continue // fully covered, zero-width, or a NaN width
            pieces += ElevatedPiece(start, end, s.bpm)
            cursor = end
        }
        return pieces
    }
}
