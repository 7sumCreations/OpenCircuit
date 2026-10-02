package io.github.opencircuit.ringkit

// Is last night's reported DURATION likely over-counted because the night was very still? Port of
// upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/SleepConfidence.swift (@ b1c2fdd), plus the
// public surface of its three extension files — SleepConfidenceCoverage.swift (did the recording
// cover the night?), SleepConfidenceCopy.swift (the sentences the card prints) and
// SleepConfidenceExportNames.swift (the wire names). Swift spreads one caseless enum over four
// files; here the types and entry points live on this one object and each extension file's logic
// lives in its own Kotlin file, so every string literal sits beside its upstream twin.
//
// The ring detects wake from only two signals: motion (a coarse per-30 s count) and heart-rate
// elevation above the night's sleeping floor. When the user lies STILL while AWAKE — reading in bed,
// resting before rising — at a heart rate near their own sleeping level, the ring sees NEITHER, so
// that awake time is absorbed into light sleep and efficiency pins implausibly near 100 %. That is a
// HARDWARE CEILING (the discriminating signal isn't on the wire), so we cannot recover the lost wake
// — but we CAN stop presenting the inflated number as gospel. Conservative by design: judged only on
// a multi-hour night and only above a clearly-implausible efficiency, so it informs rather than nags.
//
// Shape notes: totals, gaps and thresholds are `Double` seconds (Swift `TimeInterval`), so NaN,
// infinity and the `0` / infinity sweep values keep upstream's meanings. Values compare as Swift's
// synthesized `Equatable` does — `Double` fields by IEEE `==` (NaN unequal to itself, -0.0 equal to
// 0.0) — and hold their own copies of every list (Swift arrays copy on assignment).

import java.time.Instant
import java.util.Collections

object SleepConfidence {

    /**
     * Minimum in-bed span (seconds) before we judge efficiency at all. Below this the night is a nap
     * or a buffer-truncated fragment, where a near-100 % efficiency carries no information about
     * still-wake under-detection — so we never flag it.
     */
    const val MIN_NIGHT_FOR_FLAG: Double = 5.0 * 3600

    /**
     * Efficiency above which a full night's reported duration is likely over-counted: a multi-hour
     * night with essentially no detected wake. Healthy efficiency tops ~90–95 %.
     */
    const val IMPLAUSIBLE_EFFICIENCY: Double = 0.95

    enum class Level {
        /** Nothing unusual — efficiency is in a plausible range, or the night is too short to judge. */
        NORMAL,

        /**
         * A very still night with near-zero detected wake. The reported duration likely reads high
         * because still-but-awake time was absorbed into light sleep (a ring-sensor limitation).
         */
        DURATION_LIKELY_HIGH,
    }

    /**
     * Classify confidence in the reported sleep DURATION from the night's totals: [asleep] (Light +
     * Deep + REM) and [inBed], both in seconds. [Level.DURATION_LIKELY_HIGH] only on a multi-hour
     * night (`inBed ≥ MIN_NIGHT_FOR_FLAG`) whose efficiency exceeds [IMPLAUSIBLE_EFFICIENCY];
     * [Level.NORMAL] otherwise, including a short night and a degenerate `inBed ≤ 0`.
     */
    fun classify(asleep: Double, inBed: Double): Level {
        if (!(inBed >= MIN_NIGHT_FOR_FLAG && inBed > 0)) return Level.NORMAL
        val efficiency = asleep / inBed
        return if (efficiency > IMPLAUSIBLE_EFFICIENCY) Level.DURATION_LIKELY_HIGH else Level.NORMAL
    }

    /** Convenience overload for a staged-night [SleepStaging.Summary]. */
    fun classify(summary: SleepStaging.Summary): Level =
        classify(SleepStaging.seconds(summary.totalAsleep), SleepStaging.seconds(summary.inBed))

    // MARK: - Coverage-aware confidence (logic in SleepConfidenceCoverage.kt)

    /**
     * The acquisition facts a night's confidence depends on, as instants a store can fetch with one
     * indexed row each — not the night's record array.
     *
     * The two "measurement" fields want a HEART-RATE observation specifically: HR is band-guarded, so
     * a charging or pocketed ring yields none, whereas skin temperature keeps arriving from a docked
     * ring.
     *
     * [inBedStart]: detected opening edge (staged segments' min start). [inBedEnd]: detected closing
     * edge (max end) — the instant the card prints as the wake time, but NOT necessarily the instant
     * the caveat names, because the run walk can consume records before the hole.
     * [lastMeasurementBeforeStart]: newest measurement strictly before [inBedStart], or null.
     * [firstMeasurementAfterEnd]: oldest measurement strictly after [inBedEnd], or null — kept for
     * callers that genuinely hold only one instant; it is the DEFEATABLE input (one record inside the
     * continuity tolerance buys a witnessed edge whatever follows), so prefer [measurementsAfterEnd].
     * [measurementsAfterEnd]: measurements after [inBedEnd], enough to walk the run that starts there;
     * empty is a legal, meaningful value ("we hold nothing after the edge"). Required, like
     * [earliestRetainedMeasurement], so no caller keeps the defeatable behaviour without deciding to.
     * [earliestRetainedMeasurement]: oldest measurement retained at all, or null on an empty store —
     * tells "the ring recorded nothing" from "retention no longer reaches back that far".
     */
    class Coverage(
        val inBedStart: Instant,
        val inBedEnd: Instant,
        val lastMeasurementBeforeStart: Instant?,
        val firstMeasurementAfterEnd: Instant?,
        measurementsAfterEnd: List<Instant>,
        val earliestRetainedMeasurement: Instant?,
    ) {
        /** A read-only copy of the list passed in. */
        val measurementsAfterEnd: List<Instant> = Collections.unmodifiableList(ArrayList(measurementsAfterEnd))

        override fun equals(other: Any?): Boolean =
            other is Coverage && inBedStart == other.inBedStart && inBedEnd == other.inBedEnd &&
                lastMeasurementBeforeStart == other.lastMeasurementBeforeStart &&
                firstMeasurementAfterEnd == other.firstMeasurementAfterEnd &&
                measurementsAfterEnd == other.measurementsAfterEnd &&
                earliestRetainedMeasurement == other.earliestRetainedMeasurement

        override fun hashCode(): Int =
            listOf(inBedStart, inBedEnd, lastMeasurementBeforeStart, firstMeasurementAfterEnd, measurementsAfterEnd, earliestRetainedMeasurement)
                .hashCode()

        override fun toString(): String =
            "Coverage(inBedStart=$inBedStart, inBedEnd=$inBedEnd, lastMeasurementBeforeStart=$lastMeasurementBeforeStart, " +
                "firstMeasurementAfterEnd=$firstMeasurementAfterEnd, measurementsAfterEnd=$measurementsAfterEnd, " +
                "earliestRetainedMeasurement=$earliestRetainedMeasurement)"
    }

    /**
     * One thing that is true about this night's number; several can hold at once. Each case carries
     * the INSTANT the copy should name. The names say "no recording", never "the ring stopped": what
     * we can observe is that OUR store holds nothing across that span.
     */
    sealed interface Reason {
        /**
         * Nothing was recorded for [silentFor] seconds after the night's trailing edge. The reported
         * duration reads LOW by an unknown amount BOUNDED BY (not equal to) that gap. [from] is the
         * LAST MEASUREMENT BEFORE THE SILENCE; the copy depends on `from + silentFor` being the
         * instant recording resumed.
         */
        data class NoRecordingAfterWake(val from: Instant, val silentFor: Double) : Reason {
            override fun equals(other: Any?): Boolean =
                other is NoRecordingAfterWake && from == other.from && silentFor == other.silentFor

            override fun hashCode(): Int = 31 * from.hashCode() + ieeeHash(silentFor)
        }

        /**
         * Nothing was recorded for [silentFor] seconds before the night's leading edge, so the printed
         * bedtime is where recording resumed, not where the user settled. A caller renders this OR a
         * bedtime hint of its own, never both.
         */
        data class NoRecordingBeforeBedtime(val until: Instant, val silentFor: Double) : Reason {
            override fun equals(other: Any?): Boolean =
                other is NoRecordingBeforeBedtime && until == other.until && silentFor == other.silentFor

            override fun hashCode(): Int = 31 * until.hashCode() + ieeeHash(silentFor)
        }

        /**
         * The legacy signal: a multi-hour night at implausibly high efficiency. Identical in meaning to
         * [Level.DURATION_LIKELY_HIGH]; emitted only when no acquisition reason applies and the back
         * edge was witnessed.
         */
        data object DurationLikelyHigh : Reason
    }

    /**
     * The full verdict on a night: the legacy [level], every reason that holds, the two edge
     * provenances the reasons were derived from, and the threshold they were produced under. Built
     * only by [assess] (upstream's memberwise initializer is module-internal).
     */
    class Assessment internal constructor(
        /** EXACTLY what [classify] returns for these totals, with no coverage influence whatsoever. */
        val level: Level,
        reasons: List<Reason>,
        /** Leading-edge provenance for these inputs. */
        val bedtime: BedtimeProvenance.Verdict,
        /** Trailing-edge provenance for these inputs. */
        val wake: WakeProvenance.Verdict,
        /**
         * The gap threshold [reasons] was produced under, carried on the verdict so a diagnostics
         * bundle can never state a different cut beside these reasons.
         */
        val materialGapSeconds: Double,
    ) {
        /**
         * Every reason that holds, most-important first, at most one of each case: the back edge, then
         * the front edge, then [Reason.DurationLikelyHigh] — and that last one only when neither
         * acquisition reason fired and the back edge was witnessed.
         */
        val reasons: List<Reason> = Collections.unmodifiableList(ArrayList(reasons))

        /** Whether anything at all should be said about this night. */
        val flags: Boolean get() = reasons.isNotEmpty()

        /** The single reason to show when there is room for only one. */
        val primary: Reason? get() = reasons.firstOrNull()

        /**
         * True when a reason describes MISSING RECORDS rather than an over-counted duration. The two are
         * opposite claims and must never be rendered together.
         */
        val hasAcquisitionReason: Boolean get() = reasons.any { it != Reason.DurationLikelyHigh }

        override fun equals(other: Any?): Boolean =
            other is Assessment && level == other.level && reasons == other.reasons && bedtime == other.bedtime &&
                wake == other.wake && materialGapSeconds == other.materialGapSeconds

        override fun hashCode(): Int = listOf(level, reasons, bedtime, wake, ieeeHash(materialGapSeconds)).hashCode()

        override fun toString(): String =
            "Assessment(level=$level, reasons=$reasons, bedtime=$bedtime, wake=$wake, materialGapSeconds=$materialGapSeconds)"
    }

    /**
     * Classify a night's confidence from its totals AND its acquisition coverage.
     *
     * [asleep], [inBed]: the night's totals in seconds. [coverage]: the acquisition instants, or null
     * when the caller has none — the result is then exactly the legacy verdict, as a reason list.
     * [materialGapSeconds]: how long the stream must be absent at an edge before it is worth telling the
     * user; `0` makes every non-witnessed edge material, infinity is the kill switch. The DURATION test
     * stays gated at [MIN_NIGHT_FOR_FLAG]; the acquisition tests are deliberately NOT gated on length.
     */
    fun assess(
        asleep: Double,
        inBed: Double,
        coverage: Coverage?,
        materialGapSeconds: Double = WakeProvenance.MATERIAL_GAP_SECONDS,
    ): Assessment = assessCoverage(asleep, inBed, coverage, materialGapSeconds)

    /** Convenience overload for a staged-night [SleepStaging.Summary]. */
    fun assess(
        summary: SleepStaging.Summary,
        coverage: Coverage?,
        materialGapSeconds: Double = WakeProvenance.MATERIAL_GAP_SECONDS,
    ): Assessment =
        assess(SleepStaging.seconds(summary.totalAsleep), SleepStaging.seconds(summary.inBed), coverage, materialGapSeconds)

    // MARK: - The card's sentences (logic in SleepConfidenceCopy.kt)

    /**
     * One caveat as the card renders it: the [reason] that produced it, the SF Symbol name beside it
     * (upstream's icon vocabulary, kept verbatim for a UI to map), and the sentence itself. Carries no
     * colour on purpose.
     */
    data class Hint(val reason: Reason, val systemImage: String, val text: String)

    /**
     * A gap rendered at the precision the measurement supports — whole minutes (half away from zero,
     * never below one) under an hour, then hours and minutes; never seconds, because every edge is a
     * 150 s epoch boundary.
     *
     * @throws IllegalArgumentException for a span upstream traps on: NaN, ±infinity, or a minute count
     *   outside 64 bits.
     */
    fun approximateDuration(seconds: Double): String = approximateSpan(seconds)

    /**
     * Render an assessment's reasons, IN ORDER, as the card's caveat rows. [clock] renders an instant
     * as a short local time; it is injected because that format is locale- and calendar-dependent, so
     * the caveat and the times printed above it are formatted identically. An empty list means SAY
     * NOTHING — the common case. There is deliberately no sentence for a no-prior-measurement bedtime
     * (unreachable from the card's real caller).
     */
    fun hints(assessment: Assessment, clock: (Instant) -> String): List<Hint> = confidenceHints(assessment, clock)

    // MARK: - Wire names (logic in SleepConfidenceExportNames.kt)

    /** Short, stable identifier for a reason — for the diagnostics bundle and the data export. APPEND-ONLY. */
    fun exportName(reason: Reason): String = reasonExportName(reason)

    /** Wire name for a leading-edge verdict. Same append-only contract. */
    fun exportName(verdict: BedtimeProvenance.Verdict): String = bedtimeExportName(verdict)

    /** Wire name for a trailing-edge verdict. Same append-only contract. */
    fun exportName(verdict: WakeProvenance.Verdict): String = wakeExportName(verdict)

    /**
     * The measured silence a leading-edge verdict carries, in seconds, or null when it carries none —
     * never 0, which would claim a continuous stream.
     */
    fun gapSeconds(verdict: BedtimeProvenance.Verdict): Double? = bedtimeGapSeconds(verdict)

    /** The measured silence a trailing-edge verdict carries, in seconds, or null. */
    fun gapSeconds(verdict: WakeProvenance.Verdict): Double? = wakeGapSeconds(verdict)
}

/** A hash consistent with IEEE `==` on a `Double`: -0.0 and 0.0 hash alike. */
internal fun ieeeHash(x: Double): Int = (x + 0.0).hashCode()
