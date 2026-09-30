package io.github.opencircuit.ringkit

// Device-agnostic metric models — the typed values the codec produces and the Health Connect
// writer consumes. Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Metrics.swift:9-173
// (@ b1c2fdd). These are app-side data structures, not protocol facts; the byte-level decoders
// that populate them stay 🔴 until captures decode each metric (../docs/PROTOCOL.md §5).
//
// VALUE SEMANTICS ONLY. Swift's `Codable` conformances, including SleepSegment's
// hand-written `init(from:)` / `encode(to:)` (:175-218), are NOT ported: :ringkit carries no
// serialization dependency, and the persisted form is E6's decision. E6 inherits the requirement
// that an unreadable provenance label degrades to ASSERTED_COVERAGE_UNKNOWN, never MEASURED, and
// never drops the segment (PORTING.md D-10).

import java.time.Duration
import java.time.Instant

/**
 * One metric family. [rawValue] is the stable string id upstream uses as a persistence / cursor
 * key; keep it verbatim.
 */
enum class MetricKind(val rawValue: String) {
    HEART_RATE("heartRate"),
    RESTING_HEART_RATE("restingHeartRate"),

    /** Health stores SDNN, not RMSSD (see mapping notes). */
    HRV_SDNN("hrvSDNN"),
    SPO2("spo2"),
    TEMPERATURE("temperature"),
    RESPIRATORY_RATE("respiratoryRate"),
    STEPS("steps"),
    ACTIVE_ENERGY("activeEnergy"),

    /** Modeled as [SleepSegment], not [QuantitySample]. */
    SLEEP("sleep"),

    /**
     * Estimated walking/running distance derived from steps × stride (upstream #81). ESTIMATE
     * only — NOT GPS distance. Replaced by decoded device distance once 0x4c activity-epoch
     * [15:22] is decoded (upstream #93).
     */
    DISTANCE("distance"),

    /**
     * Estimated exercise time from elevated-HR minutes (upstream #82). ESTIMATE only — basic
     * threshold model. Full 4-level intensity follows the #93 decode.
     */
    EXERCISE_MINUTES("exerciseMinutes");

    /** Canonical unit each [QuantitySample.value] is expressed in, verbatim from upstream. */
    val unit: String
        get() = when (this) {
            HEART_RATE, RESTING_HEART_RATE, RESPIRATORY_RATE -> "count/min"
            HRV_SDNN -> "ms"
            SPO2 -> "fraction" // oxygen saturation is stored as 0…1
            TEMPERATURE -> "degC"
            STEPS -> "count"
            ACTIVE_ENERGY -> "kcal"
            SLEEP -> "category"
            DISTANCE -> "m" // meters
            EXERCISE_MINUTES -> "min" // minutes
        }

    /** Human-readable label for dashboards, verbatim from upstream. */
    val displayName: String
        get() = when (this) {
            HEART_RATE -> "Heart Rate"
            RESTING_HEART_RATE -> "Resting HR"
            HRV_SDNN -> "HRV"
            SPO2 -> "SpO₂"
            TEMPERATURE -> "Skin Temp"
            RESPIRATORY_RATE -> "Respiratory Rate"
            STEPS -> "Steps"
            ACTIVE_ENERGY -> "Active Energy"
            SLEEP -> "Sleep"
            DISTANCE -> "Distance (est.)"
            EXERCISE_MINUTES -> "Exercise Time (est.)"
        }
}

/**
 * A scalar metric sample carrying the device's own timestamps so history backfills correctly.
 * [end] defaults to [start] for instantaneous readings (upstream `end: Date? = nil` → `end ?? start`).
 */
data class QuantitySample(
    val kind: MetricKind,
    val start: Instant,
    val end: Instant = start,
    val value: Double,
)

/**
 * Sleep category values (upstream `HealthKit sleepAnalysis`; the Health Connect mapping is E6's).
 *
 * ⚠️ EXACTLY FIVE CASES, AND THAT IS DELIBERATE (upstream :80-86). The health writer is an
 * exhaustive `when` over this enum with no `else`, so a sixth case would force a decision at the
 * write site — but it would ALSO force one at every other `when` in the app, and the next person
 * to add an `else` re-buries it silently. Worse, upstream's hypnogram codec looks stages up in a
 * DICTIONARY, so a sixth case would compile clean and its segments would vanish from the stored
 * hypnogram while the MINUTES still counted them. "Time we did not measure" is therefore carried
 * as [SleepProvenance] — an orthogonal per-segment attribute — never as a stage.
 */
enum class SleepStage(val rawValue: String) {
    IN_BED("inBed"),
    AWAKE("awake"),
    ASLEEP_CORE("asleepCore"),
    ASLEEP_DEEP("asleepDeep"),
    ASLEEP_REM("asleepREM"),
}

/**
 * WHERE A SEGMENT'S CLAIM COMES FROM — orthogonal to its `stage` (upstream :91-148; read the
 * upstream comment for the defect this exists for).
 *
 * The governing rule, in four clauses:
 *  1. AN ASSERTION ALWAYS WINS FOR DISPLAY — it is the user's record and they were there.
 *  2. A MEASUREMENT IS NEVER DESTROYED — the ring-derived hypnogram is persisted separately.
 *  3. ASSERTED-**PROVEN-UNMEASURED** TIME NEVER ENTERS A DERIVED NUMBER — not a stage minute, not
 *     efficiency, not the sleep score. It IS written to the health store as the stage the
 *     wearer's edit assigned, marked user-entered, so the claim travels with its provenance.
 *  4. AND WE MUST BE ABLE TO PROVE IT. Where our record set could not have held the epochs, the
 *     honest answer is [ASSERTED_COVERAGE_UNKNOWN], which behaves exactly as before provenance
 *     existed: counted, displayed, published.
 *
 * Clause 3 excludes [ASSERTED] only. [ASSERTED_OVER_MEASURED] is a user label on top of real
 * recorded ground, so it participates in derived numbers normally.
 */
enum class SleepProvenance(val rawValue: String) {
    /** The ring recorded epochs across this span and this is what they said. */
    MEASURED("measured"),

    /**
     * The user asserted this span, the ring recorded NOTHING here, AND our record set reaches back
     * far enough to prove it. Excluded from every derived statistic (clause 3).
     */
    ASSERTED("asserted"),

    /** The user asserted this span and the ring DID record here; the user's label is displayed and counted. */
    ASSERTED_OVER_MEASURED("assertedOverMeasured"),

    /**
     * The user asserted this span and WE CANNOT SAY whether the ring recorded across it.
     * ⚠️ THIS IS "WE DO NOT KNOW", NOT "NOTHING WAS RECORDED" — retention must never be read as
     * absence, so this behaves like the pre-provenance build (counted, displayed, published, never
     * a delete driver).
     */
    ASSERTED_COVERAGE_UNKNOWN("assertedCoverageUnknown");

    /**
     * True when no measurement underlies this span AND we can prove it — the only case clause 3
     * excludes. Named so a reader cannot mistake [ASSERTED_COVERAGE_UNKNOWN] for it.
     */
    val isProvenUnmeasured: Boolean get() = this == ASSERTED

    /** True when real recorded ground underlies this span, whoever chose the label. */
    val hasMeasurement: Boolean get() = this == MEASURED || this == ASSERTED_OVER_MEASURED

    /** True when we hold no records here but cannot prove none were ever recorded. */
    val isCoverageUnknown: Boolean get() = this == ASSERTED_COVERAGE_UNKNOWN

    /** True when the user, not the ring, chose this label. */
    val isAsserted: Boolean get() = this != MEASURED
}

/**
 * One contiguous sleep-stage segment. A night = many of these, not one record.
 *
 * [provenance] defaults to [SleepProvenance.MEASURED] so every construction site keeps its exact
 * present meaning.
 */
data class SleepSegment(
    val start: Instant,
    val end: Instant,
    val stage: SleepStage,
    val provenance: SleepProvenance = SleepProvenance.MEASURED,
) {
    /** `end - start` (upstream `TimeInterval`, seconds). */
    val duration: Duration get() = Duration.between(start, end)

    /** Same span and stage, re-tagged. Used where a caller learns the provenance after the fact. */
    fun withProvenance(p: SleepProvenance): SleepSegment = copy(provenance = p)
}
