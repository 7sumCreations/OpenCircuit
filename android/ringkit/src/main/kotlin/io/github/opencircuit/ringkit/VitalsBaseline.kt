package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/VitalsBaseline.swift (@ b1c2fdd).
//
// Vitals baseline + acute anomaly detection (Vitals Status / fever) — #72.
//
// The app's "Vitals Status" engine compares each day's vitals to a PERSONAL rolling baseline (the ring
// app uses a 7–30 day window) and flags Minor / Significant outliers, then cross-references HR + skin
// temperature for a suspected-fever flag. This file holds the PURE detection math.
//
// All baselines are PERSONAL (derived from the user's own trailing history) — never a hardcoded
// "healthy" range, so no medical value is fabricated. Skin temperature is NOT recomputed here: the
// canonical nightly offset comes from `SkinTempBaseline` and is passed in.
//
// One deliberate difference from upstream (PORTING.md): an unreadable (NaN or infinite) reading — a
// prior day, today's value, the resting HR or the temperature offset — is a missing reading, never a
// day judged "normal" with a NaN baseline behind it and never an alarm raised by an infinity.

/** Vitals baseline, per-vital classification, suspected fever and the Vitals Status report. */
object VitalsBaseline {

    // Vitals

    /** The direction that is CLINICALLY concerning for a vital. */
    enum class Concern { HIGH, LOW, BOTH }

    /**
     * The per-metric vitals that carry a personal baseline, in upstream's declaration order. Skin
     * temperature is folded into the status via its canonical offset (see [report]). [rawValue] is
     * upstream's raw name.
     */
    enum class Vital(val rawValue: String) {
        RESTING_HR("restingHR"),
        OVERNIGHT_SPO2("overnightSpO2"), // percent (0…100)
        OVERNIGHT_HRV("overnightHRV"), // RMSSD ms
        ;

        /**
         * The direction that is CLINICALLY concerning — a deviation the other way (an unusually high
         * HRV, an unusually low resting HR) is healthy, not an anomaly.
         */
        val concern: Concern
            get() = when (this) {
                RESTING_HR -> Concern.HIGH // a rising resting HR is the concern
                OVERNIGHT_SPO2 -> Concern.LOW // desaturation is the concern
                OVERNIGHT_HRV -> Concern.LOW // falling HRV (stress/illness) is the concern
            }
    }

    /** How far today's value sits from the personal baseline. */
    enum class Severity(val rawValue: String) { NORMAL("normal"), MINOR("minor"), SIGNIFICANT("significant") }

    enum class Direction(val rawValue: String) { RISE("rise"), DROP("drop") }

    // Config

    /**
     * Window + threshold knobs. Defaults follow the ring app's 7–30 day window; the z-score cut-offs
     * and absolute noise floors are defensible heuristics, and the caller may override them (with
     * `copy`). Never a person's data — only policy. Doubles compare by IEEE `==`.
     *
     * [minorZ]: |z| at/above this (and past the absolute floor) is a Minor outlier; [significantZ]:
     * at/above this, Significant. [minDeltaRestingHR] / [minDeltaSpO2] / [minDeltaHRV]: the absolute
     * deviation a vital must exceed before ANY flag. [tempMinorC] / [tempSignificantC]: skin-temp
     * offset bands (°C). Suspected fever needs BOTH a skin-temp rise ≥ [feverTempRiseC] AND a resting-HR
     * rise ≥ [feverHRRiseBpm].
     */
    data class Config(
        val minBaselineDays: Int = 7,
        val maxBaselineDays: Int = 30,
        val minorZ: Double = 1.5,
        val significantZ: Double = 2.5,
        val minDeltaRestingHR: Double = 5.0,
        val minDeltaSpO2: Double = 2.0,
        val minDeltaHRV: Double = 8.0,
        val tempMinorC: Double = 0.5,
        val tempSignificantC: Double = SkinTempBaseline.NORMAL_DEVIATION_C,
        val feverTempRiseC: Double = SkinTempBaseline.NORMAL_DEVIATION_C,
        val feverHRRiseBpm: Double = 8.0,
    ) {
        internal fun minDelta(vital: Vital): Double = when (vital) {
            Vital.RESTING_HR -> minDeltaRestingHR
            Vital.OVERNIGHT_SPO2 -> minDeltaSpO2
            Vital.OVERNIGHT_HRV -> minDeltaHRV
        }

        private fun doubles(): List<Double> = listOf(
            minorZ, significantZ, minDeltaRestingHR, minDeltaSpO2, minDeltaHRV, tempMinorC, tempSignificantC, feverTempRiseC, feverHRRiseBpm,
        )

        override fun equals(other: Any?): Boolean {
            if (other !is Config || minBaselineDays != other.minBaselineDays || maxBaselineDays != other.maxBaselineDays) return false
            val a = doubles()
            val b = other.doubles()
            for (i in a.indices) if (!(a[i] == b[i])) return false // IEEE: NaN unequal to itself, -0.0 equal to 0.0
            return true
        }

        override fun hashCode(): Int = listOf(minBaselineDays, maxBaselineDays, doubles().map { ieeeHash(it) }).hashCode()
    }

    // Baseline statistics

    /**
     * Mean / standard deviation over the trailing `maxBaselineDays` PRIOR daily values ([n] of them).
     * Doubles compare by IEEE `==`.
     */
    class Stats(val mean: Double, val sd: Double, val n: Int) {
        override fun equals(other: Any?): Boolean = other is Stats && mean == other.mean && sd == other.sd && n == other.n

        override fun hashCode(): Int = listOf(ieeeHash(mean), ieeeHash(sd), n).hashCode()

        override fun toString(): String = "Stats(mean=$mean, sd=$sd, n=$n)"
    }

    /**
     * Mean / standard deviation over the trailing `maxBaselineDays` of [prior] (chronological,
     * oldest → newest); null below `minBaselineDays` (too little history to call an outlier).
     *
     * An unreadable (NaN or infinite) day is a missing day, dropped before the window is taken. An
     * empty window — a zero or negative `maxBaselineDays`, or no readable day with a minimum of 0 —
     * has no baseline (upstream traps on a negative window and averages nothing into NaN).
     */
    fun stats(prior: List<Double>, config: Config = Config()): Stats? {
        val readable = prior.filter { it.isFinite() }
        val window = readable.takeLast(maxOf(0, config.maxBaselineDays))
        if (window.isEmpty() || window.size < config.minBaselineDays) return null
        var sum = 0.0
        for (x in window) sum += x
        val mean = sum / window.size
        var squares = 0.0
        for (x in window) squares += (x - mean) * (x - mean)
        val variance = squares / window.size
        return Stats(mean = mean, sd = kotlin.math.sqrt(variance), n = window.size)
    }

    // Per-vital classification

    /**
     * One day's classification of a vital against its personal baseline: [delta] is today − baseline
     * mean (0 when there is no baseline), [direction] its sign. Doubles compare by IEEE `==`.
     */
    class Classification(val severity: Severity, val baseline: Stats?, val delta: Double, val direction: Direction) {
        override fun equals(other: Any?): Boolean =
            other is Classification && severity == other.severity && baseline == other.baseline && delta == other.delta && direction == other.direction

        override fun hashCode(): Int = listOf(severity, baseline, ieeeHash(delta), direction).hashCode()

        override fun toString(): String = "Classification(severity=$severity, baseline=$baseline, delta=$delta, direction=$direction)"
    }

    /**
     * Classify today's value of [vital] against the prior daily series. Only deviations in the vital's
     * concern direction, past both the z-score cut-off AND the absolute floor, are flagged — so a
     * healthy swing (high HRV, low resting HR) stays normal.
     *
     * An unreadable (NaN or infinite) [today] is not judged: the answer is upstream's own "no
     * baseline" one (normal, no baseline, delta 0), never a verdict.
     */
    fun classify(today: Double, prior: List<Double>, vital: Vital, config: Config = Config()): Classification {
        val stats = (if (today.isFinite()) stats(prior, config) else null)
            ?: return Classification(Severity.NORMAL, baseline = null, delta = 0.0, direction = Direction.RISE)
        val delta = today - stats.mean
        val direction = if (delta >= 0) Direction.RISE else Direction.DROP
        if (!isConcerning(delta, vital.concern) || !(kotlin.math.abs(delta) >= config.minDelta(vital))) {
            return Classification(Severity.NORMAL, baseline = stats, delta = delta, direction = direction)
        }
        val z = if (stats.sd > 1e-9) kotlin.math.abs(delta) / stats.sd else Double.MAX_VALUE
        val severity = when {
            z >= config.significantZ -> Severity.SIGNIFICANT
            z >= config.minorZ -> Severity.MINOR
            else -> Severity.NORMAL
        }
        return Classification(severity, baseline = stats, delta = delta, direction = direction)
    }

    /**
     * Severity of a skin-temperature deviation from the canonical baseline offset — both directions are
     * concerning (fever vs. illness/recovery drop), so it isn't direction-gated. A NaN offset cannot be
     * placed in any band: null (upstream calls it normal). An infinite offset is beyond every band.
     */
    fun tempSeverity(offsetC: Double, config: Config = Config()): Severity? {
        if (offsetC.isNaN()) return null
        val a = kotlin.math.abs(offsetC)
        if (a >= config.tempSignificantC) return Severity.SIGNIFICANT
        if (a >= config.tempMinorC) return Severity.MINOR
        return Severity.NORMAL
    }

    private fun isConcerning(delta: Double, concern: Concern): Boolean = when (concern) {
        Concern.HIGH -> delta > 0
        Concern.LOW -> delta < 0
        Concern.BOTH -> delta != 0.0
    }

    // Fever (HR + temperature cross-reference)

    /**
     * Suspected fever fires ONLY on COMBINED elevation: a skin-temp rise (the canonical offset from
     * `SkinTempBaseline`, NOT recomputed here) AND a resting-HR rise above the personal HR baseline.
     * Either alone is not enough. Missing, unreadable (NaN or infinite) or insufficient inputs ⇒ false
     * (never a false positive).
     */
    fun suspectedFever(restingHRToday: Double?, restingHRPrior: List<Double>, skinTempOffsetC: Double?, config: Config = Config()): Boolean {
        val offset = skinTempOffsetC?.takeIf { it.isFinite() } ?: return false
        if (!(offset >= config.feverTempRiseC)) return false
        val hr = restingHRToday?.takeIf { it.isFinite() } ?: return false
        val hrStats = stats(restingHRPrior, config) ?: return false
        return (hr - hrStats.mean) >= config.feverHRRiseBpm
    }

    // Vitals Status report

    /**
     * One day's value of a vital plus the prior daily series it's judged against. [prior] is copied in
     * and read-only out. Doubles compare by IEEE `==`.
     */
    class VitalInput(val vital: Vital, val today: Double, prior: List<Double>) {
        val prior: List<Double> = java.util.Collections.unmodifiableList(prior.toList())

        override fun equals(other: Any?): Boolean =
            other is VitalInput && vital == other.vital && today == other.today && ieeeEqual(prior, other.prior)

        override fun hashCode(): Int = listOf(vital, ieeeHash(today), prior.map { ieeeHash(it) }).hashCode()

        override fun toString(): String = "VitalInput(vital=$vital, today=$today, prior=$prior)"
    }

    /**
     * A single contributing signal in the Vitals Status panel. [vital] is null for the skin-temperature
     * signal (carried by [isTemperature]). Doubles compare by IEEE `==`.
     */
    class Signal(
        val vital: Vital?,
        val isTemperature: Boolean,
        val severity: Severity,
        val delta: Double,
        val direction: Direction,
        val baselineMean: Double?,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Signal && vital == other.vital && isTemperature == other.isTemperature && severity == other.severity &&
                delta == other.delta && direction == other.direction && baselineMean == other.baselineMean

        override fun hashCode(): Int = listOf(vital, isTemperature, severity, ieeeHash(delta), direction, baselineMean?.let { ieeeHash(it) }).hashCode()

        override fun toString(): String =
            "Signal(vital=$vital, isTemperature=$isTemperature, severity=$severity, delta=$delta, direction=$direction, baselineMean=$baselineMean)"
    }

    /** Overall Vitals Status, mirroring the app's normal / watch / anomaly framing. */
    enum class Status(val rawValue: String) { NORMAL("normal"), WATCH("watch"), ANOMALY("anomaly") }

    /** The day's status; [signals] holds only the non-normal contributors, in input order, copied in and read-only out. */
    class Report(val status: Status, signals: List<Signal>, val feverSuspected: Boolean) {
        val signals: List<Signal> = java.util.Collections.unmodifiableList(signals.toList())

        override fun equals(other: Any?): Boolean =
            other is Report && status == other.status && signals == other.signals && feverSuspected == other.feverSuspected

        override fun hashCode(): Int = listOf(status, signals, feverSuspected).hashCode()

        override fun toString(): String = "Report(status=$status, signals=$signals, feverSuspected=$feverSuspected)"
    }

    /**
     * Build the Vitals Status report. [skinTempOffsetC] is the canonical nightly offset from
     * `SkinTempBaseline` — temperature is NOT re-derived here. Status escalates to anomaly on any
     * Significant outlier or a suspected fever, watch on any Minor outlier.
     *
     * An unreadable (NaN or infinite) offset is no temperature at all; an unreadable today gives its
     * vital no signal (see [classify]). The fever check reads the first resting-HR input, as upstream.
     */
    fun report(inputs: List<VitalInput>, skinTempOffsetC: Double? = null, config: Config = Config()): Report {
        val signals = mutableListOf<Signal>()
        for (input in inputs) {
            val c = classify(input.today, input.prior, input.vital, config)
            if (c.severity != Severity.NORMAL) {
                signals += Signal(
                    input.vital, isTemperature = false, severity = c.severity, delta = c.delta, direction = c.direction,
                    baselineMean = c.baseline?.mean,
                )
            }
        }

        val offset = skinTempOffsetC?.takeIf { it.isFinite() }
        if (offset != null) {
            val sev = tempSeverity(offset, config)
            if (sev != null && sev != Severity.NORMAL) {
                val direction = if (offset >= 0) Direction.RISE else Direction.DROP
                signals += Signal(null, isTemperature = true, severity = sev, delta = offset, direction = direction, baselineMean = null)
            }
        }

        val hr = inputs.firstOrNull { it.vital == Vital.RESTING_HR }
        val fever = suspectedFever(hr?.today, hr?.prior ?: emptyList(), skinTempOffsetC, config)

        val status = when {
            fever || signals.any { it.severity == Severity.SIGNIFICANT } -> Status.ANOMALY
            signals.any { it.severity == Severity.MINOR } -> Status.WATCH
            else -> Status.NORMAL
        }
        return Report(status, signals, fever)
    }

    /** Swift's `==` on `[Double]`: same length and every element equal by IEEE comparison. */
    private fun ieeeEqual(a: List<Double>, b: List<Double>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if (!(a[i] == b[i])) return false
        return true
    }
}
