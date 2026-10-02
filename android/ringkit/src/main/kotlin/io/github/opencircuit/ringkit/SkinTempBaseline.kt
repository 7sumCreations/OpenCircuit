package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/SkinTempBaseline.swift (@ b1c2fdd),
// whole. The file first held only the normal-deviation band the composite sleep score reads; it grew
// into the rest of upstream's file in place, so that constant keeps its one home.
//
// Sleeping skin-temperature baseline + nightly deviation (Oura-style) — #69. Each night's MEAN sleeping
// skin temperature, a signed DEVIATION from a rolling baseline (the ring app uses the previous 30
// nights), and a normal / abnormal classification. The ring app's own copy defines it: "the baseline
// represents the average sleeping skin temperature of the previous 30 days … Temperatures within 1°C
// (1.8°F) of the baseline are considered normal."
//
// Skin temperature rides the LIVE descriptor (0.1 °C, PROTOCOL §5.4), not the bulk history drain, so a
// nightly value depends on the ring staying connected through the night. This file is the pure math:
// the nightly mean, the baseline, the signed offset and the raw anomaly flags. Fever (the HR +
// temperature cross-reference) is owned by `VitalsBaseline`; notifications by a later epic.
//
// Deliberate differences from upstream (PORTING.md): an unreadable (NaN or infinite) reading, night or
// offset is a missing one; a reading exactly on the end of the window counts in the window's last hour;
// a baseline window of zero or negative nights has no baseline.

import java.time.Instant

/** Sleeping skin-temperature baseline + nightly deviation. */
object SkinTempBaseline {

    /** Trailing nights the rolling baseline averages over (the app uses 30). */
    const val BASELINE_WINDOW_NIGHTS: Int = 30

    /**
     * Minimum prior nights before a baseline is considered meaningful. Below this the average swings
     * too much night-to-night to call a deviation, so callers should show the nightly value without an
     * offset until enough history accrues.
     */
    const val MIN_BASELINE_NIGHTS: Int = 3

    /**
     * A deviation within ± this many °C of the baseline is "normal" (the ring app's own copy: 1 °C).
     * Beyond it is an abnormal rise or drop. A signed, symmetric band — never a hardcoded temperature.
     */
    const val NORMAL_DEVIATION_C: Double = 1.0

    /**
     * Night-to-night change beyond ± this °C is a "fluctuation" rise / drop. Distinct from the baseline
     * deviation. Heuristic — labelled as such.
     */
    const val FLUCTUATION_C: Double = 0.6

    /**
     * BASELINE GATE for the fluctuation flags: a night-over-night change only counts when tonight ALSO
     * sits beyond ± this °C from the baseline, in the same direction. Without it one artifact night
     * (e.g. a 30 °C reading from holding something cold) alerts twice: correctly on the artifact night
     * ("fell sharply"), then WRONGLY on the recovery night, which is at baseline yet reads "+4 °C vs
     * last night". Half the fluctuation threshold: small enough to catch a genuine rapid rise before it
     * crosses the ±1 °C band, big enough to stay quiet on a plain return to normal.
     */
    const val FLUCTUATION_BASELINE_GATE_C: Double = FLUCTUATION_C / 2

    /**
     * One night's mean sleeping skin temperature, keyed by the night it belongs to ([night] is a start
     * of day or any stable per-night key). Doubles compare by IEEE `==`.
     */
    class NightlyTemp(val night: Instant, val celsius: Double) {
        override fun equals(other: Any?): Boolean = other is NightlyTemp && night == other.night && celsius == other.celsius

        override fun hashCode(): Int = 31 * night.hashCode() + ieeeHash(celsius)

        override fun toString(): String = "NightlyTemp(night=$night, celsius=$celsius)"
    }

    /**
     * Minimum readings before a night's mean is comparable to another night's — a cheap first cut, NOT
     * the real gate (see [MIN_NIGHTLY_COVERAGE]). 🟢 Measured on a tester's well-connected night: 533
     * worn readings across a 10 h window, 47–69 in every hour (about one per 68 s), so ten readings is
     * roughly eleven minutes of connected time. A count floor rejects only nights that barely connected
     * at all; it does nothing about a night connected for two hours of eight.
     */
    const val MIN_NIGHTLY_SAMPLES: Int = 10

    /**
     * The coverage threshold itself, kept as its own name because the tests assert against it directly
     * and because the value and the DECISION to ship it are separate facts ([MIN_NIGHTLY_COVERAGE] is
     * this value; declared first because a Kotlin constant must be initialised before another reads it).
     */
    const val CANDIDATE_NIGHTLY_COVERAGE: Double = 0.6

    /**
     * The REAL gate: the fraction of the night's hour-long buckets that must hold at least one reading
     * before the night's mean is comparable to another night's. Sleeping skin temperature follows a
     * circadian curve — it rises after onset, peaks in the small hours, falls before waking — so a mean
     * is comparable only if the two nights sampled the same SHAPE; 100 readings from one hour and 100
     * spread over ten are the same count and different measurements.
     *
     * Shipped ON at [CANDIDATE_NIGHTLY_COVERAGE] (0.6) since 2026-09-24, on a measurement over 39 nights
     * from 6 rings: the coverage distribution is bimodal with an EMPTY gap between 0.1538 and 0.7500, so
     * 0.6 is any point in an interval separating two populations that do not touch. Of the 4 nights
     * below the gap, 3 were already withheld by [MIN_NIGHTLY_SAMPLES]; the gate newly withholds exactly
     * one (a Gen 3 night, FR05.011: 31 samples over a 12 h 19 m window in 2 of its 13 hour buckets).
     * Coverage is normalised over the staged in-bed span, a pessimistic denominator; normalising over
     * the intersection with the recording window could only raise coverage. This gate governs the
     * nightly MEAN only, not per-sample health-store writes.
     */
    const val MIN_NIGHTLY_COVERAGE: Double = CANDIDATE_NIGHTLY_COVERAGE

    /**
     * Fraction of [window]'s hour-long buckets holding ≥ 1 reading, in 0…1: ⌈duration / 1 h⌉ buckets
     * (at least one) over the closed window. A reading exactly on the window's end counts in the last
     * bucket — upstream gave it a bucket of its own, so coverage could exceed 1 and a night read only
     * in its last hour and at its end counted two hours. Reads only the readings' times.
     */
    fun coverage(samples: List<TemperatureSample>, window: DateInterval): Double {
        val buckets = maxOf(1L, kotlin.math.ceil(secondsBetween(window.start, window.end) / 3600).toLong())
        val hit = HashSet<Long>()
        for (s in samples) {
            if (window.containsClosed(s.time)) hit += minOf((secondsBetween(window.start, s.time) / 3600).toLong(), buckets - 1)
        }
        return hit.size.toDouble() / buckets.toDouble()
    }

    /**
     * Mean of skin-temperature readings for a night, or null when there are too few to represent it
     * ([minSamples]; pass 1 for the old any-non-empty behaviour). An unreadable (NaN or infinite)
     * reading is not a reading: it is dropped before the count floor (upstream published NaN).
     */
    fun nightlyMean(celsius: List<Double>, minSamples: Int = MIN_NIGHTLY_SAMPLES): Double? {
        val readable = celsius.filter { it.isFinite() }
        if (readable.size < maxOf(1, minSamples)) return null
        return mean(readable)
    }

    /**
     * Why a night's mean was or was not published — the distinction [nightlyMean]'s null cannot carry,
     * and which the store needs in order to obey "gates only move one way".
     */
    sealed class NightlyVerdict {
        /** Enough readings, spread widely enough to represent the night. Doubles compare by IEEE `==`. */
        class Published(val celsius: Double) : NightlyVerdict() {
            override fun equals(other: Any?): Boolean = other is Published && celsius == other.celsius

            override fun hashCode(): Int = ieeeHash(celsius)

            override fun toString(): String = "Published(celsius=$celsius)"
        }

        /**
         * Too few readings to judge the night at all. The caller must PRESERVE any stored value — this
         * is "we barely looked", not a verdict on the night.
         */
        data object NotMeasured : NightlyVerdict()

        /**
         * Enough readings to judge but too clustered to represent the night; carries the [coverage]
         * that failed. The caller must CLEAR any stored value: this IS a verdict. Doubles compare by
         * IEEE `==`.
         */
        class RejectedCoverage(val coverage: Double) : NightlyVerdict() {
            override fun equals(other: Any?): Boolean = other is RejectedCoverage && coverage == other.coverage

            override fun hashCode(): Int = ieeeHash(coverage)

            override fun toString(): String = "RejectedCoverage(coverage=$coverage)"
        }
    }

    /**
     * Classify a night's temperature readings inside the closed [window] without publishing a number.
     * The count runs BEFORE the coverage (an empty pass must abstain, never reject). Readings with an
     * unreadable (NaN or infinite) temperature are dropped before both: upstream counted them and could
     * publish NaN, or reject a night that held too few real readings to judge.
     */
    fun nightlyVerdict(
        samples: List<TemperatureSample>,
        window: DateInterval,
        minSamples: Int = MIN_NIGHTLY_SAMPLES,
        minCoverage: Double = MIN_NIGHTLY_COVERAGE,
    ): NightlyVerdict {
        val inWindow = samples.filter { window.containsClosed(it.time) && it.celsius.isFinite() }
        if (inWindow.size < maxOf(1, minSamples)) return NightlyVerdict.NotMeasured
        val cov = coverage(inWindow, window)
        if (!(minCoverage <= 0 || cov >= minCoverage)) return NightlyVerdict.RejectedCoverage(cov)
        return NightlyVerdict.Published(mean(inWindow.map { it.celsius }))
    }

    /**
     * The night's mean inside the closed [window], gated on COVERAGE as well as count — the overload
     * production should use. [nightlyVerdict] keeping only the published case; `minCoverage = 0` is the
     * kill-switch for the coverage half.
     */
    fun nightlyMean(
        samples: List<TemperatureSample>,
        window: DateInterval,
        minSamples: Int = MIN_NIGHTLY_SAMPLES,
        minCoverage: Double = MIN_NIGHTLY_COVERAGE,
    ): Double? = (nightlyVerdict(samples, window, minSamples, minCoverage) as? NightlyVerdict.Published)?.celsius

    /**
     * Rolling baseline = mean of the most recent [windowNights] PRIOR nightly means (exclude tonight),
     * sorted by night (stable for equal keys) so order or extra history can't skew it; null below
     * [minNights]. An unreadable (NaN or infinite) night is skipped; an empty trailing window — zero or
     * negative [windowNights] — has no baseline (upstream traps on a negative window and averages
     * nothing into NaN).
     */
    fun baseline(priorNights: List<NightlyTemp>, windowNights: Int = BASELINE_WINDOW_NIGHTS, minNights: Int = MIN_BASELINE_NIGHTS): Double? {
        val trailing = priorNights.filter { it.celsius.isFinite() }.sortedBy { it.night }.takeLast(maxOf(0, windowNights))
        if (trailing.isEmpty() || trailing.size < minNights) return null
        return mean(trailing.map { it.celsius })
    }

    /** Signed nightly offset = tonight − baseline (°C). Positive = warmer than baseline. */
    fun offset(tonight: Double, baseline: Double): Double = tonight - baseline

    /** Where tonight's deviation from the baseline falls. [rawValue] is upstream's raw name. */
    enum class DeviationBand(val rawValue: String) { NORMAL("normal"), ABNORMAL_RISE("abnormalRise"), ABNORMAL_DROP("abnormalDrop") }

    /**
     * Normal within ±[normalC], else a signed rise / drop. A NaN offset cannot be placed in the band:
     * null (upstream called it normal). An infinite offset lies beyond it.
     */
    fun deviationBand(offset: Double, normalC: Double = NORMAL_DEVIATION_C): DeviationBand? {
        if (offset.isNaN()) return null
        if (offset > normalC) return DeviationBand.ABNORMAL_RISE
        if (offset < -normalC) return DeviationBand.ABNORMAL_DROP
        return DeviationBand.NORMAL
    }

    /**
     * The raw temperature anomaly flags, computed app-side: `abnormal*` compare tonight to the
     * BASELINE; `fluctuation*` compare tonight to the PREVIOUS night, gated on the baseline. Upstream's
     * struct has `var` fields set one by one; here it is an immutable value (change it with `copy`).
     */
    data class AnomalyFlags(
        val abnormalRise: Boolean = false,
        val abnormalDrop: Boolean = false,
        val fluctuationRise: Boolean = false,
        val fluctuationDrop: Boolean = false,
    ) {
        val any: Boolean get() = abnormalRise || abnormalDrop || fluctuationRise || fluctuationDrop
    }

    /**
     * Classify tonight against an optional baseline and an optional previous night. The fluctuation
     * flags are BASELINE-GATED (see [FLUCTUATION_BASELINE_GATE_C]); with no baseline the previous night
     * is the only reference, so the ungated comparison is kept.
     *
     * An unreadable (NaN or infinite) tonight raises nothing; an unreadable baseline or previous night
     * is treated as absent (upstream let a NaN baseline silence the ungated comparison and an infinite
     * one raise a flag).
     */
    fun anomalyFlags(
        tonight: Double,
        baseline: Double?,
        previousNight: Double?,
        normalC: Double = NORMAL_DEVIATION_C,
        fluctC: Double = FLUCTUATION_C,
    ): AnomalyFlags {
        if (!tonight.isFinite()) return AnomalyFlags()
        val base = baseline?.takeIf { it.isFinite() }
        val prev = previousNight?.takeIf { it.isFinite() }
        val band = base?.let { deviationBand(tonight - it, normalC) }
        var fluctuationRise = false
        var fluctuationDrop = false
        if (prev != null) {
            val d = tonight - prev
            val offsetFromBase = base?.let { tonight - it }
            if (d > fluctC && (offsetFromBase?.let { it > FLUCTUATION_BASELINE_GATE_C } ?: true)) {
                fluctuationRise = true
            } else if (d < -fluctC && (offsetFromBase?.let { it < -FLUCTUATION_BASELINE_GATE_C } ?: true)) {
                fluctuationDrop = true
            }
        }
        return AnomalyFlags(
            abnormalRise = band == DeviationBand.ABNORMAL_RISE,
            abnormalDrop = band == DeviationBand.ABNORMAL_DROP,
            fluctuationRise = fluctuationRise,
            fluctuationDrop = fluctuationDrop,
        )
    }

    /**
     * Everything the UI needs for one night in a single value: nightly mean, baseline (if enough
     * history), signed offset, band, and the raw anomaly flags. Doubles compare by IEEE `==`.
     */
    class NightReport(val nightlyC: Double, val baselineC: Double?, val offsetC: Double?, val band: DeviationBand?, val flags: AnomalyFlags) {
        override fun equals(other: Any?): Boolean =
            other is NightReport && nightlyC == other.nightlyC && baselineC == other.baselineC && offsetC == other.offsetC &&
                band == other.band && flags == other.flags

        override fun hashCode(): Int = listOf(ieeeHash(nightlyC), baselineC?.let { ieeeHash(it) }, offsetC?.let { ieeeHash(it) }, band, flags).hashCode()

        override fun toString(): String = "NightReport(nightlyC=$nightlyC, baselineC=$baselineC, offsetC=$offsetC, band=$band, flags=$flags)"
    }

    /**
     * Build a [NightReport] from tonight's mean and the prior nights' means. An unreadable (NaN or
     * infinite) tonight keeps the baseline but has no offset, no band and no flags.
     */
    fun report(
        tonight: Double,
        priorNights: List<NightlyTemp>,
        previousNight: Double? = null,
        windowNights: Int = BASELINE_WINDOW_NIGHTS,
        normalC: Double = NORMAL_DEVIATION_C,
        fluctC: Double = FLUCTUATION_C,
    ): NightReport {
        val base = baseline(priorNights, windowNights)
        val off = if (tonight.isFinite()) base?.let { offset(tonight, it) } else null
        val band = off?.let { deviationBand(it, normalC) }
        val flags = anomalyFlags(tonight, base, previousNight, normalC, fluctC)
        return NightReport(nightlyC = tonight, baselineC = base, offsetC = off, band = band, flags = flags)
    }

    /** Swift's `reduce(0, +) / count`, summed in order. */
    private fun mean(values: List<Double>): Double {
        var sum = 0.0
        for (v in values) sum += v
        return sum / values.size
    }
}
