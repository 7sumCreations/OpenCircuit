package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/ExportEngine.swift (@ b1c2fdd), whole
// (see PORTING.md): pure export serialization — callers fetch from the store and pass plain rows here.
// Schema 2 — the sample, sleep, daily, step, nap, daytime-temperature and history-sync-evidence rows,
// their CSV writers (with the CSV field escaper and number text), the device-local `sessionID` /
// `dayStamp`; schema 3 — the metadata block, the OSA, sleep-session and epoch-archive rows, the
// metadata / sessions / hypnogram CSVs, the provenance / units / notes blocks as CSV, and `toJSON` with
// every section; and `SleepEdgeProvenanceRow`, which the sleep confidence tests also use. Every byte
// follows upstream's Foundation output on valid input (FoundationText, ExportJson); the export
// differential compares whole files. Upstream's formatter lock and cache are not ported: the formatters
// here are stateless functions of (instant, zone).
//
// No ambient environment: every writer that prints a device-local label (`night`, `day`) or a
// schema-3 offset takes the zone to print it in, and `toJSON` the export instant (upstream reads
// `Calendar.current` and defaults `now` to the device clock). Every time in the schema-2 sections
// prints UTC (`…Z`) whatever the zone, as upstream; the schema-3 sections print the zone's offset.

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

    /**
     * One persisted nightly sleep summary. [night] is the night's bucket instant (local midnight in the
     * zone it was bucketed with); minutes, scores and heart rates are Swift `Int`s (64-bit, so `Long`);
     * [efficiency] is a fraction, [skinTempC] degrees Celsius. [movementLevels] is copied in and read
     * only. Compares as Swift's synthesized `Equatable`: doubles by IEEE `==`.
     */
    class SleepRow(
        val night: Instant,
        val asleepMin: Long,
        val deepMin: Long,
        val lightMin: Long,
        val remMin: Long,
        val awakeMin: Long,
        val efficiency: Double,
        val inBedStart: Instant? = null,
        val inBedEnd: Instant? = null,
        val skinTempC: Double,
        val sleepScore: Long,
        val stressScore: Long,
        val feelScore: Long = 0,
        val hrDeep: Long = 0,
        val hrLight: Long = 0,
        val hrRem: Long = 0,
        val hrAwake: Long = 0,
        movementLevels: List<Long> = emptyList(),
    ) {
        /** A read-only copy of the list passed in (a Swift array is a value). */
        val movementLevels: List<Long> = Collections.unmodifiableList(ArrayList(movementLevels))

        private fun fields(): List<Any?> = listOf(
            night, asleepMin, deepMin, lightMin, remMin, awakeMin, ieeeHash(efficiency), inBedStart, inBedEnd, ieeeHash(skinTempC),
            sleepScore, stressScore, feelScore, hrDeep, hrLight, hrRem, hrAwake, movementLevels,
        )

        override fun equals(other: Any?): Boolean =
            other is SleepRow && efficiency == other.efficiency && skinTempC == other.skinTempC && night == other.night &&
                asleepMin == other.asleepMin && deepMin == other.deepMin && lightMin == other.lightMin && remMin == other.remMin &&
                awakeMin == other.awakeMin && inBedStart == other.inBedStart && inBedEnd == other.inBedEnd &&
                sleepScore == other.sleepScore && stressScore == other.stressScore && feelScore == other.feelScore &&
                hrDeep == other.hrDeep && hrLight == other.hrLight && hrRem == other.hrRem && hrAwake == other.hrAwake &&
                movementLevels == other.movementLevels

        override fun hashCode(): Int = fields().hashCode()

        override fun toString(): String =
            "SleepRow(night=$night, asleepMin=$asleepMin, deepMin=$deepMin, lightMin=$lightMin, remMin=$remMin, awakeMin=$awakeMin, " +
                "efficiency=$efficiency, inBedStart=$inBedStart, inBedEnd=$inBedEnd, skinTempC=$skinTempC, sleepScore=$sleepScore, " +
                "stressScore=$stressScore, feelScore=$feelScore, hrDeep=$hrDeep, hrLight=$hrLight, hrRem=$hrRem, hrAwake=$hrAwake, " +
                "movementLevels=$movementLevels)"
    }

    /** One day's step rollup; [day] is the day's bucket instant (local midnight). */
    class DailyRow(val day: Instant, val steps: Long) {
        override fun equals(other: Any?): Boolean = other is DailyRow && day == other.day && steps == other.steps

        override fun hashCode(): Int = listOf(day, steps).hashCode()

        override fun toString(): String = "DailyRow(day=$day, steps=$steps)"
    }

    /** One intraday step delta over [start, end]. */
    class StepSampleRow(val start: Instant, val end: Instant, val delta: Long) {
        override fun equals(other: Any?): Boolean =
            other is StepSampleRow && start == other.start && end == other.end && delta == other.delta

        override fun hashCode(): Int = listOf(start, end, delta).hashCode()

        override fun toString(): String = "StepSampleRow(start=$start, end=$end, delta=$delta)"
    }

    /** One daytime nap. */
    class NapRow(val start: Instant, val end: Instant, val asleepMin: Long, val isLongNap: Boolean) {
        override fun equals(other: Any?): Boolean =
            other is NapRow && start == other.start && end == other.end && asleepMin == other.asleepMin && isLongNap == other.isLongNap

        override fun hashCode(): Int = listOf(start, end, asleepMin, isLongNap).hashCode()

        override fun toString(): String = "NapRow(start=$start, end=$end, asleepMin=$asleepMin, isLongNap=$isLongNap)"
    }

    /** One daytime skin-temperature sample in degrees Celsius; [celsius] compares by IEEE `==`. */
    class DaytimeTemperatureRow(val time: Instant, val celsius: Double) {
        override fun equals(other: Any?): Boolean = other is DaytimeTemperatureRow && time == other.time && celsius == other.celsius

        override fun hashCode(): Int = listOf(time, ieeeHash(celsius)).hashCode()

        override fun toString(): String = "DaytimeTemperatureRow(time=$time, celsius=$celsius)"
    }

    /**
     * What one history drain did, for troubleshooting. [nightRowOutcome] is which branch of the
     * night-summary write ran ([SleepPersistOutcome.rawValue]), or null when the drain staged no
     * night. [channels] holds independent copies of the traces passed in — a drain keeps filling its
     * own — and every read hands out fresh copies, so the row is a value as upstream's struct is.
     */
    class HistorySyncEvidenceRow(
        val capturedAt: Instant,
        val ringID: String,
        val trigger: String,
        val sleepCommitted: Boolean,
        val stagedSleepSegments: Long,
        val mergedRecordCount: Long,
        val historySampleCount: Long,
        val rawRecordBlobBase64: String,
        channels: List<HistoryChannelTrace>,
        val nightRowOutcome: String? = null,
    ) {
        private val traces: List<HistoryChannelTrace> = channels.map { it.copy() }

        /**
         * Copies of the traces, read-only: changing what you are handed never changes the row. Every
         * read copies every trace afresh, so read it once per use rather than inside a loop.
         */
        val channels: List<HistoryChannelTrace> get() = Collections.unmodifiableList(traces.map { it.copy() })

        private fun fields(): List<Any?> = listOf(
            capturedAt, ringID, trigger, sleepCommitted, stagedSleepSegments, mergedRecordCount, historySampleCount,
            rawRecordBlobBase64, traces, nightRowOutcome,
        )

        override fun equals(other: Any?): Boolean = other is HistorySyncEvidenceRow && fields() == other.fields()

        override fun hashCode(): Int = fields().hashCode()

        override fun toString(): String =
            "HistorySyncEvidenceRow(capturedAt=$capturedAt, ringID=$ringID, trigger=$trigger, sleepCommitted=$sleepCommitted, " +
                "stagedSleepSegments=$stagedSleepSegments, mergedRecordCount=$mergedRecordCount, historySampleCount=$historySampleCount, " +
                "channels=${traces.size}, nightRowOutcome=$nightRowOutcome)"
    }

    /**
     * The app's own rolling epoch archive (written by a later slice of the export). [recordsBase64] is
     * `EpochArchive.encode` output in base64; [coverage] says how the evidence blobs compare with it,
     * held as a copy whose missing-epoch list is read-only.
     */
    class EpochArchiveRow(
        val ringID: String,
        val recordsBase64: String,
        val recordCount: Long,
        val firstEpoch: Instant?,
        val lastEpoch: Instant?,
        coverage: ArchiveEvidenceCoverage.Report,
    ) {
        val coverage: ArchiveEvidenceCoverage.Report =
            coverage.copy(missingFromEvidence = Collections.unmodifiableList(ArrayList(coverage.missingFromEvidence)))

        private fun fields(): List<Any?> = listOf(ringID, recordsBase64, recordCount, firstEpoch, lastEpoch, coverage)

        override fun equals(other: Any?): Boolean = other is EpochArchiveRow && fields() == other.fields()

        override fun hashCode(): Int = fields().hashCode()

        override fun toString(): String =
            "EpochArchiveRow(ringID=$ringID, recordCount=$recordCount, firstEpoch=$firstEpoch, lastEpoch=$lastEpoch, coverage=$coverage)"
    }

    // --- schema-3 rows ---

    /**
     * The fixed text carried in `meta.timestampPolicy`, verbatim from upstream, so a consumer never has
     * to guess which of the two time policies a key follows.
     */
    const val TIMESTAMP_POLICY_DESCRIPTION: String =
        "Timestamps in the schema-2 sections (samples, sleep, daily, stepSamples, naps, " +
            "daytimeTemperatures, historySyncEvidence) are ISO-8601 in UTC and end in 'Z'. " +
            "Timestamps in the schema-3 sections (meta, sleepSessions and its hypnogram/coverage) " +
            "are ISO-8601 with the exporting device's UTC offset. Date-only labels (night, day) are " +
            "yyyy-MM-dd in the device's local calendar in BOTH, which is the calendar the night/day " +
            "buckets were formed with."

    /**
     * Device, app and ring context for the export file. Every text field is a plain string, so an
     * unknown value is "" rather than a made-up placeholder.
     *
     * PRIVACY: [ringIdentifier] is a per-install identifier for the ring, never its MAC — this is a file
     * people hand to third parties. There is deliberately no device-name field. [ringModel] is a model
     * family; the caller strips the advertised name's MAC-derived suffix before it gets here. The ring
     * fields describe the LAST connected ring, which need not have produced every night in the file
     * (the file's own `ringIdentity` note says so).
     *
     * [timeZoneIdentifier] and [timeZoneOffsetSeconds] are the zone this block DECLARES; upstream
     * defaults both to the device's live zone, which is ambient here, so they are required. Use [of]
     * to derive both from the zone the file is printed in, so the declared zone and the printed one
     * cannot differ. [schemaVersion] and [timeZoneOffsetSeconds] are Swift `Int`s (64-bit).
     */
    data class ExportMetadata(
        val schemaVersion: Long = SCHEMA_VERSION.toLong(),
        val exportedAt: Instant,
        val rangeStart: Instant,
        val rangeEnd: Instant,
        val appVersion: String = "",
        val appBuild: String = "",
        val deviceModel: String = "",
        val osVersion: String = "",
        val ringModel: String = "",
        val ringFirmware: String = "",
        val ringGeneration: String = "",
        val ringIdentifier: String = "",
        val timeZoneIdentifier: String,
        val timeZoneOffsetSeconds: Long,
        val timestampPolicy: String = TIMESTAMP_POLICY_DESCRIPTION,
    ) {
        companion object {
            /**
             * The metadata for a file printed in [zone]: [timeZoneIdentifier] is the zone's id and
             * [timeZoneOffsetSeconds] the offset `meta.exportedAt` is printed with — taken at the
             * millisecond the timestamp prints, so even half a millisecond before a DST change (which
             * prints as the change) the declared offset is the printed one.
             */
            fun of(
                zone: ZoneId,
                exportedAt: Instant,
                rangeStart: Instant,
                rangeEnd: Instant,
                appVersion: String = "",
                appBuild: String = "",
                deviceModel: String = "",
                osVersion: String = "",
                ringModel: String = "",
                ringFirmware: String = "",
                ringGeneration: String = "",
                ringIdentifier: String = "",
                schemaVersion: Long = SCHEMA_VERSION.toLong(),
                timestampPolicy: String = TIMESTAMP_POLICY_DESCRIPTION,
            ): ExportMetadata = ExportMetadata(
                schemaVersion = schemaVersion,
                exportedAt = exportedAt,
                rangeStart = rangeStart,
                rangeEnd = rangeEnd,
                appVersion = appVersion,
                appBuild = appBuild,
                deviceModel = deviceModel,
                osVersion = osVersion,
                ringModel = ringModel,
                ringFirmware = ringFirmware,
                ringGeneration = ringGeneration,
                ringIdentifier = ringIdentifier,
                timeZoneIdentifier = zone.id,
                timeZoneOffsetSeconds = FoundationText.printedOffsetSeconds(exportedAt, zone).toLong(),
                timestampPolicy = timestampPolicy,
            )
        }
    }

    /**
     * A night's OSA SpO₂ assessment. [avgSpO2] is the validated metric; the event metrics are
     * experimental estimates. [validWindows] `<= 0` means nothing was drained: the row is not
     * exported at all, because zeros would read as measured values. Compares as Swift's synthesized
     * `Equatable`: doubles by IEEE `==`.
     */
    class OSARow(val avgSpO2: Double, val minSpO2: Double, val timeBelow90Sec: Double, val odi: Double, val validWindows: Long) {
        override fun equals(other: Any?): Boolean =
            other is OSARow && avgSpO2 == other.avgSpO2 && minSpO2 == other.minSpO2 && timeBelow90Sec == other.timeBelow90Sec &&
                odi == other.odi && validWindows == other.validWindows

        override fun hashCode(): Int = listOf(ieeeHash(avgSpO2), ieeeHash(minSpO2), ieeeHash(timeBelow90Sec), ieeeHash(odi), validWindows).hashCode()

        override fun toString(): String =
            "OSARow(avgSpO2=$avgSpO2, minSpO2=$minSpO2, timeBelow90Sec=$timeBelow90Sec, odi=$odi, validWindows=$validWindows)"
    }

    /**
     * One sleep SESSION: the night's bed and sleep boundaries, its hypnogram, the derived summary and,
     * when there are any, the OSA assessment, the coverage measurements and the edge verdicts.
     *
     * ABSENCE IS NOT ZERO. An empty [hypnogram] means NO TIMELINE WAS RECORDED (a night staged before
     * the hypnogram was stored), not a night with no stages; a null [osa], [coverage],
     * [referenceCoverage] or [edgeProvenance] means there is no such measurement — each is written as an
     * empty field or an omitted key, never as 0.
     *
     * [hypnogram] is taken in the shape `SleepStaging` produces, INCLUDING the overlapping in-bed
     * envelope(s); the writers drop them, so what reaches the file is a partition of the night. It is
     * copied in and read-only out. The `recorded*` instants are what the detector produced before a
     * manual correction, present only on an edited night. [referenceCoverage] is null only when
     * [coverage] is (there is nothing to compare against); [edgeProvenance] is null when the night has
     * no clock times to measure against.
     */
    class SleepSessionRow(
        val sessionID: String,
        val night: Instant,
        val inBedStart: Instant? = null,
        val inBedEnd: Instant? = null,
        val sleepOnset: Instant? = null,
        val sleepWake: Instant? = null,
        val isManuallyEdited: Boolean = false,
        val recordedInBedStart: Instant? = null,
        val recordedInBedEnd: Instant? = null,
        val recordedOnset: Instant? = null,
        val recordedWake: Instant? = null,
        hypnogram: List<SleepSegment> = emptyList(),
        val summary: SleepRow,
        val osa: OSARow? = null,
        val coverage: ExportCoverage.Assessment? = null,
        val referenceCoverage: ExportReferenceCoverage.Outcome? = null,
        val edgeProvenance: SleepEdgeProvenanceRow? = null,
    ) {
        /** A read-only copy of the list passed in (a Swift array is a value). */
        val hypnogram: List<SleepSegment> = Collections.unmodifiableList(ArrayList(hypnogram))

        private fun fields(): List<Any?> = listOf(
            sessionID, night, inBedStart, inBedEnd, sleepOnset, sleepWake, isManuallyEdited,
            recordedInBedStart, recordedInBedEnd, recordedOnset, recordedWake, hypnogram, summary, osa, coverage,
            referenceCoverage, edgeProvenance,
        )

        override fun equals(other: Any?): Boolean = other is SleepSessionRow && fields() == other.fields()

        override fun hashCode(): Int = fields().hashCode()

        override fun toString(): String =
            "SleepSessionRow(sessionID=$sessionID, night=$night, inBedStart=$inBedStart, inBedEnd=$inBedEnd, sleepOnset=$sleepOnset, " +
                "sleepWake=$sleepWake, isManuallyEdited=$isManuallyEdited, recordedInBedStart=$recordedInBedStart, " +
                "recordedInBedEnd=$recordedInBedEnd, recordedOnset=$recordedOnset, recordedWake=$recordedWake, " +
                "hypnogram=${hypnogram.size} segments, summary=$summary, osa=$osa, coverage=$coverage, " +
                "referenceCoverage=$referenceCoverage, edgeProvenance=$edgeProvenance)"
    }

    // --- device-local labels ---

    /**
     * Stable, human-sortable session id for a night bucket: `night-` + [dayStamp] in [zone], the zone
     * the night was bucketed with, so the id and the `night` label can never disagree.
     */
    fun sessionID(night: Instant, zone: ZoneId): String = "night-" + dayStamp(night, zone)

    /**
     * `yyyy-MM-dd` of [date] in [zone] — the same text the `night` / `day` labels inside the file use.
     * A caller needing a local day string (an export filename) uses this with the same zone rather
     * than a formatter of its own, so the name and the labels in the file it names cannot disagree.
     */
    fun dayStamp(date: Instant, zone: ZoneId): String = FoundationText.dateOnly(date, zone)

    // --- CSV ---

    /**
     * RFC-4180 field escaper; every CSV field goes through it. Quotes a value holding a comma, a quote,
     * a line feed or carriage return (so a CRLF is caught too), or a leading or trailing space, and
     * doubles its quotes; anything else is returned as is.
     *
     * Every trigger is tested per character. Upstream tests line breaks on Unicode scalars but the
     * comma, the quote and the edge spaces on Swift Characters, so one followed by a combining mark is
     * missed there and the row's later columns shift (measured); here it is quoted (PORTING.md D-134).
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

    /**
     * CSV of the nightly sleep summaries, every stored column. `night` is `yyyy-MM-dd` in [zone];
     * `efficiency` `%.4f`, `skinTempC` `%.2f`; the in-bed times ISO-8601 UTC, empty when absent;
     * `movementLevels` joined with `|`.
     */
    fun sleepCSV(rows: List<SleepRow>, zone: ZoneId): String {
        val lines = ArrayList<String>(rows.size + 1)
        lines += "night,asleepMin,deepMin,lightMin,remMin,awakeMin,efficiency,inBedStart,inBedEnd,skinTempC,sleepScore,stressScore,feelScore,hrDeep,hrLight,hrRem,hrAwake,movementLevels"
        for (r in rows) {
            lines += csvLine(
                listOf(
                    FoundationText.dateOnly(r.night, zone),
                    r.asleepMin.toString(), r.deepMin.toString(), r.lightMin.toString(), r.remMin.toString(), r.awakeMin.toString(),
                    swiftFixed(r.efficiency, 4),
                    r.inBedStart?.let(::iso8601) ?: "",
                    r.inBedEnd?.let(::iso8601) ?: "",
                    swiftFixed(r.skinTempC, 2),
                    r.sleepScore.toString(), r.stressScore.toString(), r.feelScore.toString(),
                    r.hrDeep.toString(), r.hrLight.toString(), r.hrRem.toString(), r.hrAwake.toString(),
                    r.movementLevels.joinToString("|"),
                ),
            )
        }
        return lines.joinToString("\n")
    }

    /** CSV of the daily step rollups. Header `day,steps`; `day` is `yyyy-MM-dd` in [zone]. */
    fun dailyCSV(rows: List<DailyRow>, zone: ZoneId): String {
        val lines = ArrayList<String>(rows.size + 1)
        lines += "day,steps"
        for (r in rows) lines += csvLine(listOf(FoundationText.dateOnly(r.day, zone), r.steps.toString()))
        return lines.joinToString("\n")
    }

    /** CSV of the intraday step deltas. Header `start,end,delta`; times ISO-8601 UTC. */
    fun stepSamplesCSV(rows: List<StepSampleRow>): String {
        val lines = ArrayList<String>(rows.size + 1)
        lines += "start,end,delta"
        for (r in rows) lines += csvLine(listOf(iso8601(r.start), iso8601(r.end), r.delta.toString()))
        return lines.joinToString("\n")
    }

    /** CSV of the daytime naps. Header `start,end,asleepMin,isLongNap`; times ISO-8601 UTC. */
    fun napsCSV(rows: List<NapRow>): String {
        val lines = ArrayList<String>(rows.size + 1)
        lines += "start,end,asleepMin,isLongNap"
        for (r in rows) lines += csvLine(listOf(iso8601(r.start), iso8601(r.end), r.asleepMin.toString(), r.isLongNap.toString()))
        return lines.joinToString("\n")
    }

    /** CSV of the daytime temperature samples. Header `time,celsius`; `celsius` `%.2f`. */
    fun daytimeTemperatureCSV(rows: List<DaytimeTemperatureRow>): String {
        val lines = ArrayList<String>(rows.size + 1)
        lines += "time,celsius"
        for (r in rows) lines += csvLine(listOf(iso8601(r.time), swiftFixed(r.celsius, 2)))
        return lines.joinToString("\n")
    }

    /**
     * CSV of the history-sync evidence. Each row's traces are flattened into one `channelSummary`
     * column, `|`-separated: `label:outcome:4c=…:47=…:50=…:added=…`, then `:4d=…` and `:sport=…`, each
     * only when that counter is present — a trace from before the counters existed has none, and a `0`
     * there would claim a count that build never took. `nightRowOutcome` is the appended last column,
     * so no earlier column index moves.
     */
    fun historySyncEvidenceCSV(rows: List<HistorySyncEvidenceRow>): String {
        val lines = ArrayList<String>(rows.size + 1)
        lines += "capturedAt,ringID,trigger,sleepCommitted,stagedSleepSegments,mergedRecordCount,historySampleCount,channelSummary,rawRecordBlobBase64,nightRowOutcome"
        for (r in rows) {
            val channelSummary = r.channels.joinToString("|") { c ->
                val s = StringBuilder()
                s.append(c.label).append(':').append(c.outcome.rawValue).append(":4c=").append(c.page4CCount).append(":47=").append(c.page47Count)
                    .append(":50=").append(c.endMarkerCount).append(":added=").append(c.recordsAdded)
                c.page4DCount?.let { s.append(":4d=").append(it) }
                c.sportSampleCount?.let { s.append(":sport=").append(it) }
                s.toString()
            }
            lines += csvLine(
                listOf(
                    iso8601(r.capturedAt), r.ringID, r.trigger, r.sleepCommitted.toString(),
                    r.stagedSleepSegments.toString(), r.mergedRecordCount.toString(), r.historySampleCount.toString(),
                    channelSummary, r.rawRecordBlobBase64, r.nightRowOutcome ?: "",
                ),
            )
        }
        return lines.joinToString("\n")
    }

    // --- schema-3 CSV ---

    /**
     * ISO 8601 with [zone]'s offset (`2026-08-03T23:14:00.000+02:00`, `Z` at a zero offset) — the
     * schema-3 sections' timestamp policy, where the schema-2 sections print UTC.
     */
    internal fun offsetISO8601(date: Instant, zone: ZoneId): String = FoundationText.iso8601(date, zone)

    /**
     * The metadata block as ordered (key, value) pairs: ONE list feeding both the JSON `meta` object
     * and [metadataCSV], so the two views can never name or order their fields differently. The three
     * times print with [zone]'s offset.
     */
    private fun metadataFields(m: ExportMetadata, zone: ZoneId): List<Pair<String, ExportJson>> = listOf(
        "schemaVersion" to int(m.schemaVersion),
        "exportedAt" to str(offsetISO8601(m.exportedAt, zone)),
        "rangeStart" to str(offsetISO8601(m.rangeStart, zone)),
        "rangeEnd" to str(offsetISO8601(m.rangeEnd, zone)),
        "appVersion" to str(m.appVersion),
        "appBuild" to str(m.appBuild),
        "deviceModel" to str(m.deviceModel),
        "osVersion" to str(m.osVersion),
        "ringModel" to str(m.ringModel),
        "ringFirmware" to str(m.ringFirmware),
        "ringGeneration" to str(m.ringGeneration),
        "ringIdentifier" to str(m.ringIdentifier),
        "timeZoneIdentifier" to str(m.timeZoneIdentifier),
        "timeZoneOffsetSeconds" to int(m.timeZoneOffsetSeconds),
        "timestampPolicy" to str(m.timestampPolicy),
    )

    /**
     * CSV of the metadata block. Header `field,value`; one row per `meta` field, in the same order and
     * under the same names as the JSON. Text values as given, integers in decimal (Swift's `"\(value)"`).
     */
    fun metadataCSV(meta: ExportMetadata, zone: ZoneId): String {
        val lines = ArrayList<String>(16)
        lines += "field,value"
        for ((key, value) in metadataFields(meta, zone)) {
            val text = when (value) {
                is ExportJson.JString -> value.value
                is ExportJson.JInt -> value.value.toString()
                else -> error("metadata field $key is neither text nor an integer")
            }
            lines += csvLine(listOf(key, text))
        }
        return lines.joinToString("\n")
    }

    /**
     * The OSA row to write, or null. `validWindows <= 0` means nothing was drained, so every other
     * field is a default rather than a reading — a row of zeros would look exactly like a measured
     * perfect night. Enforced here, not trusted to each caller.
     */
    private fun emittableOSA(row: SleepSessionRow): OSARow? = row.osa?.takeIf { it.validWindows > 0 }

    /**
     * The hypnogram segments to write: the stages, with every in-bed envelope removed (a stitched night
     * has one per fragment), so the written timeline is a PARTITION of the night and `durationSec` can be
     * summed. The in-bed window is already on the row as `inBedStart` / `inBedEnd`. Filtered here so the
     * CSV, the JSON and the `hypnogramSegments` count can never disagree.
     */
    private fun emittableHypnogram(row: SleepSessionRow): List<SleepSegment> = row.hypnogram.filter { it.stage != SleepStage.IN_BED }

    /**
     * `hypnogramSegments`: EMPTY when no timeline was recorded, a real count otherwise — including `0`
     * for a recorded night that holds only its envelope. Absence and zero are different facts.
     */
    private fun hypnogramSegmentCount(row: SleepSessionRow): String =
        if (row.hypnogram.isEmpty()) "" else emittableHypnogram(row).size.toString()

    /** The reference-coverage measurement, or null when none was made. */
    private fun referenceRow(row: SleepSessionRow): ExportReferenceCoverage.Row? =
        (row.referenceCoverage as? ExportReferenceCoverage.Outcome.Measured)?.row

    /**
     * Where the reference wake came from: its raw name, `none` when there was no wake the records did
     * not define, or empty when the night has no such verdict at all. `none` rather than blank, so a
     * reader can tell "the check could not run" from a file written before the column existed.
     */
    private fun referenceSource(row: SleepSessionRow): String = when (val o = row.referenceCoverage) {
        is ExportReferenceCoverage.Outcome.Measured -> o.row.reference.rawValue
        is ExportReferenceCoverage.Outcome.Unavailable -> "none"
        null -> ""
    }

    /** A segment's seconds as Swift's `duration` computes them: the difference of its two dates' doubles. */
    private fun durationSeconds(seg: SleepSegment): Double = dateSecondsBetween(seg.start, seg.end)

    /**
     * CSV of the sleep SESSIONS — one row per night: the boundaries, the derived summary, the OSA
     * assessment, the coverage measurement, the edge verdicts and the reference-wake measurement side by
     * side. Times print with [zone]'s offset, `night` as `yyyy-MM-dd` in [zone].
     *
     * Absent OSA / coverage / hypnogram / edges serialize as EMPTY fields, never 0: 0 is a real reading.
     * `coverageFraction` keeps its name and its index (21); the later columns were appended, so no
     * earlier column ever moves.
     */
    fun sleepSessionsCSV(rows: List<SleepSessionRow>, zone: ZoneId): String {
        fun iso(t: Instant?): String = t?.let { offsetISO8601(it, zone) } ?: ""
        val lines = ArrayList<String>(rows.size + 1)
        lines += "sessionID,night,inBedStart,inBedEnd,sleepOnset,sleepWake,isManuallyEdited,asleepMin,deepMin,lightMin,remMin,awakeMin,efficiency,sleepScore,stressScore,hypnogramSegments,osaAvgSpO2,osaMinSpO2,osaTimeBelow90Sec,osaODI,osaValidWindows,coverageFraction,expectedSamples,observedSamples,longestGapSeconds,bedtimeVerdict,bedtimeGapSeconds,wakeVerdict,wakeGapSeconds,confidenceReasons,durationBasis,referenceWakeSource,referenceWakeAt,coverageToReferenceWake"
        for (r in rows) {
            val osa = emittableOSA(r)
            val cov = r.coverage
            val edge = r.edgeProvenance
            val ref = referenceRow(r)
            lines += csvLine(
                listOf(
                    r.sessionID,
                    FoundationText.dateOnly(r.night, zone),
                    iso(r.inBedStart), iso(r.inBedEnd), iso(r.sleepOnset), iso(r.sleepWake),
                    r.isManuallyEdited.toString(),
                    r.summary.asleepMin.toString(), r.summary.deepMin.toString(), r.summary.lightMin.toString(),
                    r.summary.remMin.toString(), r.summary.awakeMin.toString(),
                    swiftFixed(r.summary.efficiency, 4),
                    r.summary.sleepScore.toString(), r.summary.stressScore.toString(),
                    hypnogramSegmentCount(r),
                    osa?.let { swiftFixed(it.avgSpO2, 2) } ?: "",
                    osa?.let { swiftFixed(it.minSpO2, 2) } ?: "",
                    osa?.let { swiftFixed(it.timeBelow90Sec, 1) } ?: "",
                    osa?.let { swiftFixed(it.odi, 2) } ?: "",
                    osa?.validWindows?.toString() ?: "",
                    cov?.let { swiftFixed(it.coverageFraction, 4) } ?: "",
                    cov?.expectedSamples?.toString() ?: "",
                    cov?.observedSamples?.toString() ?: "",
                    cov?.let { swiftFixed(it.longestGapSeconds, 1) } ?: "",
                    // A witnessed or unknown edge has NO gap: the field stays empty rather than a 0 that
                    // would read as a measured zero-second silence.
                    edge?.bedtimeVerdict ?: "",
                    edge?.bedtimeGapSeconds?.let { swiftFixed(it, 1) } ?: "",
                    edge?.wakeVerdict ?: "",
                    edge?.wakeGapSeconds?.let { swiftFixed(it, 1) } ?: "",
                    // Space-separated, so the field needs no quoting; empty = the classifier found nothing.
                    edge?.reasons?.joinToString(" ") ?: "",
                    edge?.durationBasis ?: "",
                    referenceSource(r),
                    ref?.let { offsetISO8601(it.referenceEnd, zone) } ?: "",
                    ref?.let { swiftFixed(it.assessment.coverageFraction, 4) } ?: "",
                ),
            )
        }
        return lines.joinToString("\n")
    }

    /**
     * CSV of the hypnogram: one row PER SEGMENT across all sessions, keyed back to its session. Header
     * `sessionID,start,end,stage,durationSec,provenance`; times with [zone]'s offset. A session with no
     * recorded timeline contributes no rows. The rows are a partition (see [emittableHypnogram]).
     * `provenance` is always printed here (the JSON omits it for `measured`), in the JSON's vocabulary
     * ([SleepProvenance.rawValue]), and is the last column so no earlier one moved.
     */
    fun hypnogramCSV(rows: List<SleepSessionRow>, zone: ZoneId): String {
        val lines = ArrayList<String>()
        lines += "sessionID,start,end,stage,durationSec,provenance"
        for (r in rows) {
            for (seg in emittableHypnogram(r)) {
                lines += csvLine(
                    listOf(
                        r.sessionID, offsetISO8601(seg.start, zone), offsetISO8601(seg.end, zone), seg.stage.rawValue,
                        plainNumber(durationSeconds(seg)), seg.provenance.rawValue,
                    ),
                )
            }
        }
        return lines.joinToString("\n")
    }

    // --- the honesty blocks as CSV: the SAME maps `toJSON` writes, so the two formats cannot disagree ---

    /**
     * CSV of the provenance map. Header `section,provenance` (the value column is named after the JSON
     * block it mirrors). Like upstream, it takes only [includesSleepSessions]: the epoch archive has no
     * CSV, so its classification appears in the JSON alone.
     */
    fun provenanceCSV(includesSleepSessions: Boolean): String =
        keyValueCSV("section,provenance", provenance(includesSleepSessions = includesSleepSessions))

    /** CSV of the units map. Header `field,unit`. */
    fun unitsCSV(): String = keyValueCSV("field,unit", units)

    /** CSV of the caveats. Header `topic,note`. Free text with commas and quotes — [csvField] keeps each note in its row. */
    fun notesCSV(): String = keyValueCSV("topic,note", notes)

    /**
     * One row per key in Swift's `.sorted()` order, NOT the JSON writer's key order (on the three shipped
     * maps the two happen to agree, measured). Kotlin sorts by UTF-16 code unit, which matches Swift's
     * order for the all-ASCII keys these maps hold; a key outside ASCII would need a Swift-order comparator.
     */
    private fun keyValueCSV(header: String, map: Map<String, String>): String {
        val lines = ArrayList<String>(map.size + 1)
        lines += header
        for (key in map.keys.sorted()) lines += csvLine(listOf(key, map.getValue(key)))
        return lines.joinToString("\n")
    }

    // --- JSON ---

    private fun str(s: String): ExportJson = ExportJson.JString(s)
    private fun int(n: Long): ExportJson = ExportJson.JInt(n)
    private fun int(n: Int): ExportJson = ExportJson.JInt(n.toLong())
    private fun dbl(x: Double): ExportJson = ExportJson.JDouble(x)
    private fun bool(b: Boolean): ExportJson = ExportJson.JBool(b)

    /** Upstream's `jsonOrNull`: the value, or an explicit JSON null — never an omitted key. */
    private fun jsonOrNull(s: String?): ExportJson = s?.let(::str) ?: ExportJson.JNull
    private fun jsonOrNull(n: Int?): ExportJson = n?.let(::int) ?: ExportJson.JNull

    /**
     * The nightly-summary object, shared by the schema-2 `sleep` section and (with another timestamp
     * policy, passed as [iso]) the schema-3 session summary, so the two can never differ in shape.
     * `night` is `yyyy-MM-dd` in [zone].
     */
    private fun sleepJSON(r: SleepRow, zone: ZoneId, iso: (Instant) -> String): ExportJson = ExportJson.obj(
        "night" to str(FoundationText.dateOnly(r.night, zone)),
        "asleepMin" to int(r.asleepMin),
        "deepMin" to int(r.deepMin),
        "lightMin" to int(r.lightMin),
        "remMin" to int(r.remMin),
        "awakeMin" to int(r.awakeMin),
        "efficiency" to dbl(r.efficiency),
        "inBedStart" to jsonOrNull(r.inBedStart?.let(iso)),
        "inBedEnd" to jsonOrNull(r.inBedEnd?.let(iso)),
        "skinTempC" to dbl(r.skinTempC),
        "sleepScore" to int(r.sleepScore),
        "stressScore" to int(r.stressScore),
        "feelScore" to int(r.feelScore),
        "hrDeep" to int(r.hrDeep),
        "hrLight" to int(r.hrLight),
        "hrRem" to int(r.hrRem),
        "hrAwake" to int(r.hrAwake),
        "movementLevels" to ExportJson.arr(r.movementLevels.map(::int)),
    )

    private fun channelJSON(c: HistoryChannelTrace): ExportJson = ExportJson.obj(
        "label" to str(c.label),
        "channel" to int(c.channel),
        "startedAt" to str(iso8601(c.startedAt)),
        "finishedAt" to jsonOrNull(c.finishedAt?.let(::iso8601)),
        "outcome" to str(c.outcome.rawValue),
        "sawSyncAck" to bool(c.sawSyncAck),
        "syncAckFlag" to jsonOrNull(c.syncAckFlag),
        "page4CCount" to int(c.page4CCount),
        "page47Count" to int(c.page47Count),
        // Null, not 0, on a trace from before the counters existed: "we counted zero sport pages" and
        // "this build never counted" are different claims.
        "page4DCount" to jsonOrNull(c.page4DCount),
        "sportSampleCount" to jsonOrNull(c.sportSampleCount),
        "endMarkerCount" to int(c.endMarkerCount),
        "recordsAtStart" to int(c.recordsAtStart),
        "recordsAtEnd" to int(c.recordsAtEnd),
        "recordsAdded" to int(c.recordsAdded),
        "firstOpcode" to jsonOrNull(c.firstOpcode),
        "lastOpcode" to jsonOrNull(c.lastOpcode),
        "exitReason" to jsonOrNull(c.exitReason?.rawValue),
    )

    /**
     * The whole export as one JSON text (`.prettyPrinted, .sortedKeys`), schema [SCHEMA_VERSION], with
     * `exportedAt` = [now]. Every schema-2 section is written, as an empty array when it has no rows.
     * Times in these sections print UTC (`…Z`); the `night` / `day` labels print `yyyy-MM-dd` in [zone],
     * the zone the rows were bucketed with. Schema 3 adds keys only: `meta` when [metadata] is given and
     * `sleepSessions` when [sleepSessions] is not empty, both with [zone]'s offset, and `epochArchive`
     * when [epochArchives] is not empty (its epochs UTC); the provenance map then classifies each of them.
     *
     * Returns null if any number in the tree is NaN or infinite: upstream documents nil for a failed
     * serialization but crashes there instead (PORTING.md).
     */
    fun toJSON(
        samples: List<SampleRow>,
        sleep: List<SleepRow>,
        daily: List<DailyRow>,
        stepSamples: List<StepSampleRow> = emptyList(),
        naps: List<NapRow> = emptyList(),
        daytimeTemperatures: List<DaytimeTemperatureRow> = emptyList(),
        historySyncEvidence: List<HistorySyncEvidenceRow> = emptyList(),
        zone: ZoneId,
        now: Instant,
        metadata: ExportMetadata? = null,
        sleepSessions: List<SleepSessionRow> = emptyList(),
        epochArchives: List<EpochArchiveRow> = emptyList(),
    ): String? {
        val root = linkedMapOf(
            "schemaVersion" to int(SCHEMA_VERSION),
            "exportedAt" to str(iso8601(now)),
            "samples" to ExportJson.arr(
                samples.map {
                    ExportJson.obj("kind" to str(it.kind), "start" to str(iso8601(it.start)), "end" to str(iso8601(it.end)), "value" to dbl(it.value))
                },
            ),
            "sleep" to ExportJson.arr(sleep.map { sleepJSON(it, zone, ::iso8601) }),
            "daily" to ExportJson.arr(daily.map { ExportJson.obj("day" to str(FoundationText.dateOnly(it.day, zone)), "steps" to int(it.steps)) }),
            "stepSamples" to ExportJson.arr(
                stepSamples.map { ExportJson.obj("start" to str(iso8601(it.start)), "end" to str(iso8601(it.end)), "delta" to int(it.delta)) },
            ),
            "naps" to ExportJson.arr(
                naps.map {
                    ExportJson.obj(
                        "start" to str(iso8601(it.start)), "end" to str(iso8601(it.end)),
                        "asleepMin" to int(it.asleepMin), "isLongNap" to bool(it.isLongNap),
                    )
                },
            ),
            "daytimeTemperatures" to ExportJson.arr(
                daytimeTemperatures.map { ExportJson.obj("time" to str(iso8601(it.time)), "celsius" to dbl(it.celsius)) },
            ),
            "historySyncEvidence" to ExportJson.arr(
                historySyncEvidence.map {
                    ExportJson.obj(
                        "capturedAt" to str(iso8601(it.capturedAt)),
                        "ringID" to str(it.ringID),
                        "trigger" to str(it.trigger),
                        "sleepCommitted" to bool(it.sleepCommitted),
                        "nightRowOutcome" to jsonOrNull(it.nightRowOutcome),
                        "stagedSleepSegments" to int(it.stagedSleepSegments),
                        "mergedRecordCount" to int(it.mergedRecordCount),
                        "historySampleCount" to int(it.historySampleCount),
                        "rawRecordBlobBase64" to str(it.rawRecordBlobBase64),
                        "channels" to ExportJson.arr(it.channels.map(::channelJSON)),
                    )
                },
            ),
        )
        // --- schema 3: new keys only; nothing above is touched ---
        if (metadata != null) root["meta"] = ExportJson.obj(*metadataFields(metadata, zone).toTypedArray())
        if (sleepSessions.isNotEmpty()) root["sleepSessions"] = ExportJson.arr(sleepSessions.map { sessionJSON(it, zone) })
        // The app's own deduped epoch archive and what the evidence blobs miss of it — written only when
        // there is one, so an export without an archive gains no empty section. Its epochs print UTC.
        if (epochArchives.isNotEmpty()) {
            root["epochArchive"] = ExportJson.arr(
                epochArchives.map { a ->
                    ExportJson.obj(
                        "ringID" to str(a.ringID),
                        "recordCount" to int(a.recordCount),
                        "firstEpoch" to jsonOrNull(a.firstEpoch?.let(::iso8601)),
                        "lastEpoch" to jsonOrNull(a.lastEpoch?.let(::iso8601)),
                        "recordsBase64" to str(a.recordsBase64),
                        "evidenceBlobCoverage" to ExportJson.obj(
                            "archiveRecordCount" to int(a.coverage.archiveRecordCount),
                            "evidenceRecordCount" to int(a.coverage.evidenceRecordCount),
                            "missingFromEvidenceCount" to int(a.coverage.missingFromEvidence.size),
                            "longestMissingRunSeconds" to int(a.coverage.longestMissingRunSeconds),
                            "isComplete" to bool(a.coverage.isComplete),
                        ),
                    )
                },
            )
        }
        root["provenance"] = stringMap(provenance(includesSleepSessions = sleepSessions.isNotEmpty(), includesEpochArchive = epochArchives.isNotEmpty()))
        root["units"] = stringMap(units)
        root["notes"] = stringMap(notes)
        return ExportJson.pretty(ExportJson.JObject(root))
    }

    /**
     * One `sleepSessions[]` object. Every optional block follows one rule — ABSENCE IS NOT ZERO: a key
     * with nothing behind it is omitted (or, for `referenceCoverage`, carries an explicit null reference
     * and a reason), never zero-filled.
     */
    private fun sessionJSON(s: SleepSessionRow, zone: ZoneId): ExportJson {
        fun iso(t: Instant): String = offsetISO8601(t, zone)
        val obj = linkedMapOf(
            "sessionID" to str(s.sessionID),
            "night" to str(FoundationText.dateOnly(s.night, zone)),
            "inBedStart" to jsonOrNull(s.inBedStart?.let(::iso)),
            "inBedEnd" to jsonOrNull(s.inBedEnd?.let(::iso)),
            "sleepOnset" to jsonOrNull(s.sleepOnset?.let(::iso)),
            "sleepWake" to jsonOrNull(s.sleepWake?.let(::iso)),
            "isManuallyEdited" to bool(s.isManuallyEdited),
            "summary" to sleepJSON(s.summary, zone, ::iso),
        )
        // The detector's own values on an edited night (a supervised label). Omitted on an unedited
        // night — absence means "nothing was overridden", which is not "the detector agreed".
        if (s.isManuallyEdited) {
            val recorded = linkedMapOf<String, ExportJson>()
            s.recordedInBedStart?.let { recorded["inBedStart"] = str(iso(it)) }
            s.recordedInBedEnd?.let { recorded["inBedEnd"] = str(iso(it)) }
            s.recordedOnset?.let { recorded["sleepOnset"] = str(iso(it)) }
            s.recordedWake?.let { recorded["sleepWake"] = str(iso(it)) }
            if (recorded.isNotEmpty()) obj["recorded"] = ExportJson.JObject(recorded)
        }
        // Omitted when no timeline was recorded, `[]` when one was and holds no stage blocks.
        if (s.hypnogram.isNotEmpty()) {
            obj["hypnogram"] = ExportJson.arr(
                emittableHypnogram(s).map { seg ->
                    val row = linkedMapOf(
                        "start" to str(iso(seg.start)),
                        "end" to str(iso(seg.end)),
                        "stage" to str(seg.stage.rawValue),
                        "durationSec" to dbl(durationSeconds(seg)),
                    )
                    // Only when not measured, so a fully measured night's JSON is unchanged.
                    if (seg.provenance != SleepProvenance.MEASURED) row["provenance"] = str(seg.provenance.rawValue)
                    ExportJson.JObject(row)
                },
            )
            // The night-level roll-up of the same fact, written only when the night holds asserted time.
            // Its buckets close: asleep = measured + asserted + coverage-unknown.
            val breakdown = SleepProvenanceBreakdown(s.hypnogram)
            if (breakdown.hasAssertedTime) {
                val summary = linkedMapOf(
                    "measuredAsleepSec" to dbl(breakdown.measuredAsleep),
                    "assertedOverMeasuredAsleepSec" to dbl(breakdown.assertedOverMeasuredAsleep),
                    "assertedAsleepSec" to dbl(breakdown.assertedAsleep),
                    "coverageUnknownAsleepSec" to dbl(breakdown.unknownAsleep),
                    "measuredAwakeSec" to dbl(breakdown.measuredAwake),
                    "assertedOverMeasuredAwakeSec" to dbl(breakdown.assertedOverMeasuredAwake),
                    "assertedAwakeSec" to dbl(breakdown.assertedAwake),
                    "coverageUnknownAwakeSec" to dbl(breakdown.unknownAwake),
                    "coveredInBedSec" to dbl(breakdown.coveredInBed),
                    "coverageUnknownInBedSec" to dbl(breakdown.unknownInBed),
                    "coverageFraction" to dbl(breakdown.coverageFraction),
                    "longestUnmeasuredGapSec" to dbl(breakdown.longestUnmeasuredGap),
                    "scorable" to bool(breakdown.isScorable),
                )
                // OMITTED when withheld — never 0 (a real efficiency) and never null.
                breakdown.efficiency?.let { summary["measuredEfficiency"] = dbl(it) }
                obj["provenanceSummary"] = ExportJson.JObject(summary)
            }
        }
        emittableOSA(s)?.let { osa ->
            obj["osa"] = ExportJson.obj(
                "avgSpO2" to dbl(osa.avgSpO2),
                "minSpO2" to dbl(osa.minSpO2),
                "timeBelow90Sec" to dbl(osa.timeBelow90Sec),
                "odi" to dbl(osa.odi),
                "validWindows" to int(osa.validWindows),
            )
        }
        s.coverage?.let { cov ->
            obj["coverage"] = ExportJson.obj(
                "windowStart" to str(iso(cov.windowStart)),
                "windowEnd" to str(iso(cov.windowEnd)),
                "expectedSamples" to int(cov.expectedSamples),
                "observedSamples" to int(cov.observedSamples),
                "coverageFraction" to dbl(cov.coverageFraction),
                // The same number under the name that says what it measures: coverage WITHIN the
                // reported window. Both keys are written; the old one is already in readers' hands.
                "coverageWithinReportedWindow" to dbl(cov.coverageFraction),
                "longestGapSeconds" to dbl(cov.longestGapSeconds),
                "gaps" to gapsJSON(cov.gaps, zone),
            )
        }
        // Written whenever the night carries a verdict — including "there was no reference", which says so.
        when (val ref = s.referenceCoverage) {
            is ExportReferenceCoverage.Outcome.Measured -> {
                val r = ref.row
                val a = r.assessment
                obj["referenceCoverage"] = ExportJson.obj(
                    "reference" to str(r.reference.rawValue),
                    "referenceEnd" to str(iso(r.referenceEnd)),
                    // Signed: negative means the reference closed EARLIER than the reported window.
                    "beyondReportedEndSeconds" to dbl(r.beyondReportedEndSeconds),
                    "windowStart" to str(iso(a.windowStart)),
                    "windowEnd" to str(iso(a.windowEnd)),
                    "expectedSamples" to int(a.expectedSamples),
                    "observedSamples" to int(a.observedSamples),
                    "coverageToReference" to dbl(a.coverageFraction),
                    "longestGapSeconds" to dbl(a.longestGapSeconds),
                    "gaps" to gapsJSON(a.gaps, zone),
                )
            }
            // An explicit null, not an omitted key: no denominator was invented.
            is ExportReferenceCoverage.Outcome.Unavailable ->
                obj["referenceCoverage"] = ExportJson.obj("reference" to ExportJson.JNull, "unavailableReason" to str(ref.reason))
            null -> Unit
        }
        // Omitted when the night has no clock times to measure against; each gap key only when that
        // verdict measured a silence (a 0 would turn "we don't know" into "we watched, it never stopped").
        s.edgeProvenance?.let { edge ->
            val block = linkedMapOf(
                "windowStart" to str(iso(edge.windowStart)),
                "windowEnd" to str(iso(edge.windowEnd)),
                "bedtimeVerdict" to str(edge.bedtimeVerdict),
                "wakeVerdict" to str(edge.wakeVerdict),
                "reasons" to ExportJson.arr(edge.reasons.map(::str)),
                "materialGapSeconds" to dbl(edge.materialGapSeconds),
                "durationBasis" to str(edge.durationBasis),
            )
            edge.bedtimeGapSeconds?.let { block["bedtimeGapSeconds"] = dbl(it) }
            edge.wakeGapSeconds?.let { block["wakeGapSeconds"] = dbl(it) }
            obj["edgeProvenance"] = ExportJson.JObject(block)
        }
        return ExportJson.JObject(obj)
    }

    private fun gapsJSON(gaps: List<ExportCoverage.Gap>, zone: ZoneId): ExportJson = ExportJson.arr(
        gaps.map { g ->
            ExportJson.obj("start" to str(offsetISO8601(g.start, zone)), "end" to str(offsetISO8601(g.end, zone)), "seconds" to dbl(g.seconds))
        },
    )

    private fun stringMap(map: Map<String, String>): ExportJson.JObject = ExportJson.JObject(map.mapValues { ExportJson.JString(it.value) })

    // --- provenance / units / notes ---

    /**
     * Which sections are raw and which are computed: `measured` came off the ring, `derived` was
     * computed on the device, `diagnostic` is troubleshooting exhaust, not health data.
     */
    private fun provenance(includesSleepSessions: Boolean, includesEpochArchive: Boolean = false): Map<String, String> {
        val map = linkedMapOf(
            "samples" to "measured",
            "stepSamples" to "measured",
            "daytimeTemperatures" to "measured",
            "sleep" to "derived",
            "daily" to "derived",
            "naps" to "derived",
            "historySyncEvidence" to "diagnostic",
        )
        if (includesEpochArchive) {
            // Measured: the ring's own bytes, deduped — nothing is estimated.
            map["epochArchive"] = "measured"
            // Diagnostic: a statement about the FILE, not about the wearer.
            map["epochArchive.evidenceBlobCoverage"] = "diagnostic"
        }
        if (includesSleepSessions) {
            map["sleepSessions"] = "derived"
            map["sleepSessions.summary"] = "derived"
            map["sleepSessions.osa"] = "derived"
            // Derived, emphatically: the ring transmits no hypnogram; these stages are an on-device estimate.
            map["sleepSessions.hypnogram"] = "derived"
            // Measured: coverage counts rows we actually hold and estimates nothing.
            map["sleepSessions.coverage"] = "measured"
            // Derived: the same counting, but over a window closed on a wake the WEARER's schedule named.
            map["sleepSessions.referenceCoverage"] = "derived"
            // Derived: the gaps are measured, but the verdicts and reasons are a classifier's output.
            map["sleepSessions.edgeProvenance"] = "derived"
        }
        return map
    }

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
