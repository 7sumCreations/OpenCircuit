package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/ExportEngine.swift (@ b1c2fdd), growing
// in slices (see PORTING.md): pure export serialization — callers fetch from the store and pass plain
// rows here. So far: the samples table (`SampleRow`, `samplesCSV`, the CSV field escaper and number
// text) and `toJSON` with its samples, provenance, units and notes, plus `SleepEdgeProvenanceRow`
// (`:354-427`), which the sleep confidence tests use. Every byte follows upstream's Foundation output
// on valid input (FoundationText, ExportJson); the export differential compares whole files.
//
// No ambient environment: `toJSON` takes the zone its device-local labels are printed in and the
// export instant (upstream reads `Calendar.current` and defaults `now` to the device clock). The v2
// sections, `exportedAt` and the samples print UTC (`…Z`) whatever the zone, as upstream.

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Collections

object ExportEngine {

    /** Upstream's `schemaVersion`: v3 is a byte superset of v2, so there is no variant to select. */
    const val SCHEMA_VERSION: Int = 3

    // --- rows ---

    /**
     * One quantity sample: a [MetricKind] raw name (any string is written as given), its span and its
     * value in the kind's unit. Compares as Swift's synthesized `Equatable`: [value] by IEEE `==`.
     */
    class SampleRow(val kind: String, val start: Instant, val end: Instant, val value: Double) {
        override fun equals(other: Any?): Boolean =
            other is SampleRow && kind == other.kind && start == other.start && end == other.end && value == other.value

        override fun hashCode(): Int = listOf(kind, start, end, ieeeHash(value)).hashCode()

        override fun toString(): String = "SampleRow(kind=$kind, start=$start, end=$end, value=$value)"
    }

    // --- CSV ---

    /**
     * RFC-4180 field escaper; every CSV field goes through it. Quotes a value holding a comma, a quote,
     * a line feed or carriage return (so a CRLF is caught too), or a leading or trailing space, and
     * doubles its quotes; anything else is returned as is.
     */
    internal fun csvField(value: String): String {
        val needsQuoting = value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r') ||
            value.startsWith(' ') || value.endsWith(' ')
        return if (needsQuoting) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }

    /** Whole numbers as `%.0f` (no point), anything else as Swift's `String(value)` — `nan`, `inf` included. */
    private fun plainNumber(value: Double): String =
        if (value % 1.0 == 0.0) swiftFixed(value, 0) else FoundationText.swiftDescription(value)

    private fun csvLine(fields: List<String>): String = fields.joinToString(",") { csvField(it) }

    private fun iso8601(t: Instant): String = FoundationText.iso8601(t, ZoneOffset.UTC)

    /** CSV of the samples. Header `kind,start,end,value`; times ISO-8601 UTC with milliseconds. */
    fun samplesCSV(rows: List<SampleRow>): String {
        val lines = ArrayList<String>(rows.size + 1)
        lines += "kind,start,end,value"
        for (r in rows) lines += csvLine(listOf(r.kind, iso8601(r.start), iso8601(r.end), plainNumber(r.value)))
        return lines.joinToString("\n")
    }

    // --- JSON ---

    /**
     * The whole export as one JSON text (`.prettyPrinted, .sortedKeys`), schema [SCHEMA_VERSION], with
     * `exportedAt` = [now]. The v2 sections without rows here print as empty arrays, as upstream's do
     * when it is handed none. [zone] is the zone the device-local labels are printed in.
     *
     * Returns null if any number in the tree is NaN or infinite: upstream documents nil for a failed
     * serialization but crashes there instead (PORTING.md).
     */
    fun toJSON(
        samples: List<SampleRow>,
        // Required now so no call site is written without it; the device-local sections that print
        // in it (daily, sleep sessions, metadata) arrive with their rows.
        @Suppress("UNUSED_PARAMETER") zone: ZoneId,
        now: Instant,
    ): String? {
        val root = linkedMapOf<String, ExportJson>(
            "schemaVersion" to ExportJson.JInt(SCHEMA_VERSION.toLong()),
            "exportedAt" to ExportJson.JString(iso8601(now)),
            "samples" to ExportJson.arr(
                samples.map {
                    ExportJson.obj(
                        "kind" to ExportJson.JString(it.kind), "start" to ExportJson.JString(iso8601(it.start)),
                        "end" to ExportJson.JString(iso8601(it.end)), "value" to ExportJson.JDouble(it.value),
                    )
                },
            ),
        )
        for (section in V2_SECTIONS_WITHOUT_ROWS) root[section] = ExportJson.arr(emptyList())
        root["provenance"] = stringMap(provenance())
        root["units"] = stringMap(units)
        root["notes"] = stringMap(notes)
        return ExportJson.pretty(ExportJson.JObject(root))
    }

    /** The v2 sections whose rows this port does not take yet; upstream emits each, empty without rows. */
    private val V2_SECTIONS_WITHOUT_ROWS = listOf("sleep", "daily", "stepSamples", "naps", "daytimeTemperatures", "historySyncEvidence")

    private fun stringMap(map: Map<String, String>): ExportJson.JObject = ExportJson.JObject(map.mapValues { ExportJson.JString(it.value) })

    // --- provenance / units / notes ---

    /**
     * Which sections are raw and which are computed: `measured` came off the ring, `derived` was
     * computed on the device, `diagnostic` is troubleshooting exhaust, not health data.
     */
    private fun provenance(): Map<String, String> = linkedMapOf(
        "samples" to "measured",
        "stepSamples" to "measured",
        "daytimeTemperatures" to "measured",
        "sleep" to "derived",
        "daily" to "derived",
        "naps" to "derived",
        "historySyncEvidence" to "diagnostic",
    )

    /**
     * The unit of every numeric field that expresses a quantity (plain counts and identifiers carry
     * none). Sample units come from [MetricKind.unit]. The OSA fields are listed under both their CSV
     * column and their JSON key, with the same unit.
     */
    private val units: Map<String, String> = LinkedHashMap<String, String>().apply {
        for (kind in MetricKind.entries) put(kind.rawValue, kind.unit)
        put("delta", "count")
        put("celsius", "degC")
        put("skinTempC", "degC")
        put("asleepMin", "min")
        put("deepMin", "min")
        put("lightMin", "min")
        put("remMin", "min")
        put("awakeMin", "min")
        put("efficiency", "fraction")
        put("hrDeep", "count/min")
        put("hrLight", "count/min")
        put("hrRem", "count/min")
        put("hrAwake", "count/min")
        put("sleepScore", "score (unitless)")
        put("stressScore", "score (unitless)")
        put("feelScore", "score (unitless)")
        put("movementLevels", "level (ring motion index, no physical unit)")
        for ((csvColumn, jsonKey, unit) in listOf(
            Triple("osaAvgSpO2", "avgSpO2", "percent"),
            Triple("osaMinSpO2", "minSpO2", "percent"),
            Triple("osaTimeBelow90Sec", "timeBelow90Sec", "s"),
            Triple("osaODI", "odi", "events/hour"),
        )) {
            put(csvColumn, unit)
            put(jsonKey, unit)
        }
        put("coverageFraction", "fraction")
        put("coverageWithinReportedWindow", "fraction")
        put("coverageToReference", "fraction")
        put("coverageToReferenceWake", "fraction")
        put("beyondReportedEndSeconds", "s")
        put("longestGapSeconds", "s")
        put("bedtimeGapSeconds", "s")
        put("wakeGapSeconds", "s")
        put("materialGapSeconds", "s")
        put("durationSec", "s")
        put("seconds", "s")
        put("timeZoneOffsetSeconds", "s")
    }

    /** The caveats shipped inside the file, verbatim from upstream. */
    private val notes: Map<String, String> = linkedMapOf(
        "hrvSDNN" to
            "The hrvSDNN sample kind carries RMSSD, not SDNN. The ring reports RMSSD and " +
            "HealthKit offers only an SDNN field; no fixed RMSSD→SDNN conversion exists, so the " +
            "RMSSD value is stored in the SDNN field and tagged in sample metadata rather than " +
            "converted (HealthKitWriter.swift:881-887, BulkSleep.swift:107).",
        "sleepStages" to
            "Sleep stages are an ON-DEVICE ESTIMATE. The ring transmits no hypnogram; stages are " +
            "computed here from the same per-epoch vitals the RingConn app uses. Stage TOTALS " +
            "approximate the app's, but per-epoch cycle placement is NOT validated against any " +
            "reference (SleepStaging.swift header: \"APPROXIMATION, NOT GROUND TRUTH\").",
        "hypnogram" to
            "The hypnogram rows for a session are a PARTITION of its sleep window: they never " +
            "overlap, so durationSec can be summed. The surrounding time in bed is carried by " +
            "inBedStart/inBedEnd on the session row, NOT as an extra all-night segment — a night " +
            "with no rows means no timeline was recorded, not a night with no stages. That " +
            "distinction is explicit: hypnogramSegments is EMPTY (and the JSON hypnogram key is " +
            "absent) when no timeline was recorded, and 0 only when one was recorded and contained " +
            "no stage blocks.",
        "hypnogramProvenance" to
            "Every hypnogram segment carries a provenance: measured = the ring recorded epochs " +
            "across this span; asserted = the wearer edited their sleep window over ground holding " +
            "NO ring data, so this block is their claim and not a measurement; " +
            "assertedOverMeasured = the wearer's label sits on ground the ring did record, and the " +
            "two disagree (the ring's own reading is kept separately); assertedCoverageUnknown = " +
            "the wearer's claim over ground this app no longer retains records for, so neither " +
            "reading is available. Only 'measured' is a device observation. In CSV the column is " +
            "always present; in JSON the key is omitted when the value is 'measured'.",
        "exportRange" to
            "meta.rangeStart and meta.rangeEnd are the window this file ACTUALLY covers, which can " +
            "be narrower than the one that was requested: the app caps how much it assembles in a " +
            "single pass and says so on screen. Trust these two fields over the filename or the " +
            "range you asked for.",
        "osa" to
            "osaAvgSpO2 is validated to ±1% against the RingConn app. osaMinSpO2, " +
            "osaTimeBelow90Sec and osaODI are EXPERIMENTAL estimates — reproducing the app's " +
            "numbers needs its proprietary artifact rejection and event scoring (OSASpO2.swift " +
            "header, docs/RUNBOOK_OSA_APNEA.md).",
        "skinTemperature" to
            "Skin temperature is LIVE-only: it is not part of the drainable 0x4c history, so a " +
            "window the app did not observe cannot be back-filled. Overnight coverage is NOT " +
            "predictable from the app's sleep-window quiet gate: a night recorded under that gate " +
            "has carried hundreds of overnight readings, so a quiet night is not an empty one. " +
            "Read the timestamps in this file rather than inferring coverage from the gate.",
        "ringIdentity" to
            "The meta.ring* fields describe the LAST RING THIS APP CONNECTED TO, which is not " +
            "necessarily the ring that produced every night in this file. Rings are used one at a " +
            "time and their data merges into a single shared timeline with no per-ring attribution " +
            "(RingScanner.swift:72-74), so on an install that has paired more than one ring an " +
            "older night may have come from a different ring than the one named here. " +
            "historySyncEvidence[].ringID is the only per-capture ring attribution in this file.",
        "coverage" to
            "coverageFraction measures what THIS APP holds for the window, not what the ring " +
            "recorded. A low value can mean the ring was not worn, was not drained yet, or that " +
            "epochs were lost — the export cannot tell those apart. The coverage fields are left " +
            "EMPTY for a night older than the app's raw-sample retention window: those epochs " +
            "were deleted by local housekeeping, so a number there would report routine " +
            "housekeeping as missing data. It is assessed over TWO witnesses unioned: the raw " +
            "0x4c epoch archive (~30 h, per ring) and the persisted heart-rate rows. The archive " +
            "witness exists because the persisted rows are filtered by a strictly forward-only " +
            "sync cursor, so a single late-stamped live sample can strand every earlier epoch " +
            "delivered after it and make a fully-recorded night read as a gap — that reports our " +
            "own cursor, not the ring. Neither witness can invent data: every counted instant is " +
            "a record or a sample actually on disk. Nights older than the archive's retention are " +
            "carried by the store witness alone, exactly as before. READ ITS NAME AS " +
            "coverageWithinReportedWindow, which is emitted beside it in JSON and is the same " +
            "number: the window is the night's REPORTED in-bed window (windowStart/windowEnd say " +
            "which). On a night the wearer never corrected, that window's right edge IS the last " +
            "record, so no amount of missing data at the wake can lower this fraction: a night " +
            "whose recording stopped four hours before the wearer got up reports 1.0000 with an " +
            "empty gaps list, and that is the arithmetic working as written, not a clean night. On " +
            "a night the wearer DID correct (isManuallyEdited true) the right edge is her own wake " +
            "instead, so a trailing hole is inside the window and this fraction does fall — a low " +
            "value there is real. referenceCoverage is the falsifiable companion for the nights " +
            "nobody corrected; the old key is kept because this file's schema version is unchanged.",
        "referenceCoverage" to
            "referenceCoverage measures the SAME records over a window whose right edge came from " +
            "somewhere the recording had no vote in — today the wake time in the wearer's own " +
            "manual sleep schedule (reference = manualScheduleWake). That is the only kind of " +
            "denominator a trailing data hole can actually show up in. It is a REFERENCE and not a " +
            "truth: a scheduled wake is when the wearer intends to get up, so a night they slept " +
            "in or rose early scores low for a reason that is about the schedule, not the ring — " +
            "compare beyondReportedEndSeconds (signed: negative means the reference closed EARLIER " +
            "than the reported window, so coverageToReference is over a shorter span and is not " +
            "comparable with coverageFraction). Nothing in the app is gated on it. It never reaches " +
            "past the moment the file was written: when the schedule wake has not arrived yet the " +
            "window is closed at exportedAt instead and reference says manualScheduleWakeSoFar, " +
            "because measuring to a wake in the future would report the future as a hole. When the " +
            "wearer has set no schedule the block is still emitted, with reference = null and an " +
            "unavailableReason, because no denominator is invented in its place and a missing key " +
            "would be indistinguishable from an older export. Only the right edge is moved: a " +
            "schedule also names a bedtime, but a wearer who went to bed late would then be " +
            "reported as a hole on a night nothing was wrong with.",
        "edgeProvenance" to
            "edgeProvenance says whether the record stream ran INTO the printed bedtime and " +
            "CONTINUED past the printed wake — the question coverageFraction structurally cannot " +
            "answer, because the detected window is defined by the records inside it (it measures " +
            "0.976-1.049 on all 21 nights of the development corpus, including two understated by " +
            "246 minutes, whose ~4 h hole starts exactly AT the in-bed end). The gaps are " +
            "measured; the verdicts and reasons are a classifier's output at " +
            "materialGapSeconds, a threshold the evidence does not pin down (gap sizes are " +
            "bimodal with an empty interval from 33 to 242 minutes, so every cut in there scores " +
            "identically). A gap BOUNDS the error in the reported duration, it does NOT estimate " +
            "it. 'unknown' means there was no measurement on that side at all — which is equally " +
            "consistent with the ring having stopped and with the app not having drained that far " +
            "yet, so it carries no claim. Measured against the RECORDED (detector) window, which " +
            "on an edited night is NOT the window inBedStart/inBedEnd report: the edit changes " +
            "what the app shows, not what was recorded. reasons[] is the classifier's own list, " +
            "NOT a record of what the app displayed: no coverage caveat is shown to the wearer in " +
            "this build, and any future card would apply its own render guards on top, so treat " +
            "reasons[] as an upper bound on what anyone actually saw. durationBasis says which " +
            "night's totals fed the durationLikelyHigh half of reasons[]: 'recorded' is the " +
            "recorded night's own totals, which is the same frame of reference as the edges, and " +
            "'edited' means the night was corrected AND predates the stored recorded timeline, so " +
            "only the post-edit totals were available. Before durationBasis existed the block " +
            "always used the post-edit totals against a recorded-window coverage, so an edit could " +
            "add or remove a caveat about a window it had never touched.",
        "provenanceSummary" to
            "In provenanceSummary, 'measured' means OVER GROUND THE RING RECORDED ACROSS — not " +
            "'the stage the ring reported'. measuredAsleepSec is the efficiency numerator and " +
            "therefore includes spans the wearer relabelled asleep over recorded ground; " +
            "assertedOverMeasuredAsleepSec states how much of it that is and is a SUBSET of it, " +
            "never a separate bucket to add in. measuredAwakeSec is the one exception: it counts " +
            "only what the ring's own staging called awake, and the wearer's awake paint over " +
            "recorded ground is reported separately as assertedOverMeasuredAwakeSec, because " +
            "nothing derived depends on that total and a reader takes it as the ring's word. So " +
            "asleep sums as measured + asserted + coverageUnknown, and awake as measured + " +
            "assertedOverMeasured + asserted + coverageUnknown.",
    )

    /**
     * The acquisition verdict on ONE night's two edges, in a form a tester bundle can carry. It is the
     * CLASSIFIER'S reason list, not "what the card showed" — a card applies its own render guards, so
     * this list is an upper bound on what a wearer saw. Measured at the RECORDED (detector) edges, not
     * the edited ones.
     *
     * [windowStart], [windowEnd]: the window the verdicts were measured against. [bedtimeVerdict] /
     * [wakeVerdict]: the wire names (`SleepConfidence.exportName`). [bedtimeGapSeconds] /
     * [wakeGapSeconds]: seconds of silence before / after the window, or null when the verdict carries
     * none — ABSENT rather than 0, because `witnessed` and `unknown` are different claims.
     * [reasons]: the wire name of every reason the classifier produced, in its order (`[]` = nothing to
     * say, the common case). [materialGapSeconds]: the threshold in force when those reasons were
     * produced. [durationBasis]: which night's totals the duration half of [reasons] was computed from —
     * [DURATION_BASIS_RECORDED] or [DURATION_BASIS_EDITED].
     *
     * Compares as Swift's synthesized `Equatable`: `Double` fields by IEEE `==`.
     */
    class SleepEdgeProvenanceRow(
        val windowStart: Instant,
        val windowEnd: Instant,
        val bedtimeVerdict: String,
        val bedtimeGapSeconds: Double?,
        val wakeVerdict: String,
        val wakeGapSeconds: Double?,
        reasons: List<String>,
        val materialGapSeconds: Double,
        val durationBasis: String = DURATION_BASIS_RECORDED,
    ) {
        /** A read-only copy of the list passed in. */
        val reasons: List<String> = Collections.unmodifiableList(ArrayList(reasons))

        /**
         * Build the row from an assessment measured over `[windowStart, windowEnd]`. Takes the
         * assessment rather than re-deriving the verdicts, and takes the threshold OFF the assessment,
         * so a caller sweeping the cut can never export the default beside reasons produced at another.
         */
        constructor(
            windowStart: Instant,
            windowEnd: Instant,
            assessment: SleepConfidence.Assessment,
            durationBasis: String = DURATION_BASIS_RECORDED,
        ) : this(
            windowStart = windowStart,
            windowEnd = windowEnd,
            bedtimeVerdict = SleepConfidence.exportName(assessment.bedtime),
            bedtimeGapSeconds = SleepConfidence.gapSeconds(assessment.bedtime),
            wakeVerdict = SleepConfidence.exportName(assessment.wake),
            wakeGapSeconds = SleepConfidence.gapSeconds(assessment.wake),
            reasons = assessment.reasons.map { SleepConfidence.exportName(it) },
            materialGapSeconds = assessment.materialGapSeconds,
            durationBasis = durationBasis,
        )

        override fun equals(other: Any?): Boolean =
            other is SleepEdgeProvenanceRow && windowStart == other.windowStart && windowEnd == other.windowEnd &&
                bedtimeVerdict == other.bedtimeVerdict && ieeeEquals(bedtimeGapSeconds, other.bedtimeGapSeconds) &&
                wakeVerdict == other.wakeVerdict && ieeeEquals(wakeGapSeconds, other.wakeGapSeconds) &&
                reasons == other.reasons && materialGapSeconds == other.materialGapSeconds && durationBasis == other.durationBasis

        override fun hashCode(): Int =
            listOf(
                windowStart, windowEnd, bedtimeVerdict, bedtimeGapSeconds?.let(::ieeeHash), wakeVerdict,
                wakeGapSeconds?.let(::ieeeHash), reasons, ieeeHash(materialGapSeconds), durationBasis,
            ).hashCode()

        override fun toString(): String =
            "SleepEdgeProvenanceRow(windowStart=$windowStart, windowEnd=$windowEnd, bedtimeVerdict=$bedtimeVerdict, " +
                "bedtimeGapSeconds=$bedtimeGapSeconds, wakeVerdict=$wakeVerdict, wakeGapSeconds=$wakeGapSeconds, " +
                "reasons=$reasons, materialGapSeconds=$materialGapSeconds, durationBasis=$durationBasis)"

        companion object {
            /** [durationBasis] for a verdict computed on the RECORDED night's totals — the edges' own frame. */
            const val DURATION_BASIS_RECORDED: String = "recorded"

            /** [durationBasis] for a verdict computed on POST-EDIT totals, emitted only where the recorded timeline is missing. */
            const val DURATION_BASIS_EDITED: String = "edited"

            private fun ieeeEquals(a: Double?, b: Double?): Boolean =
                if (a == null || b == null) a == null && b == null else a == b
        }
    }
}
