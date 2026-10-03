package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.ExportEngine.DailyRow
import io.github.opencircuit.ringkit.ExportEngine.DaytimeTemperatureRow
import io.github.opencircuit.ringkit.ExportEngine.ExportMetadata
import io.github.opencircuit.ringkit.ExportEngine.HistorySyncEvidenceRow
import io.github.opencircuit.ringkit.ExportEngine.NapRow
import io.github.opencircuit.ringkit.ExportEngine.OSARow
import io.github.opencircuit.ringkit.ExportEngine.SampleRow
import io.github.opencircuit.ringkit.ExportEngine.SleepEdgeProvenanceRow
import io.github.opencircuit.ringkit.ExportEngine.SleepRow
import io.github.opencircuit.ringkit.ExportEngine.SleepSessionRow
import io.github.opencircuit.ringkit.ExportEngine.StepSampleRow
import io.github.opencircuit.ringkit.ExportEngineTest.Companion.parseCSV
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/ExportSchemaV3Tests.swift (@ b1c2fdd)
 * — the 33 tests declared before its `:703` (the schema-v3 superset lock, the metadata block, the
 * session and hypnogram CSVs and the `sleepSessions` JSON); the other 22 port with the rest of
 * schema 3.
 *
 * Schema v3 is a strict SUPERSET of schema v2: every key v2 emitted still appears, at the same path,
 * with the same value. If the superset test fails, a consumer built against the old export just broke,
 * and the fix is to put the new data in a NEW key, not to relax the expectation.
 *
 * Upstream prints the device-local labels and the schema-3 offsets in `Calendar.current` and builds
 * its expectation in `TimeZone.current`; here both take [zone], fixed to America/New_York, where the
 * tests' midnight-UTC `night` is the previous local day and every offset is non-zero — so a label or
 * a timestamp printed in UTC fails. The two MAC tests keep upstream's own test MAC verbatim.
 */
class ExportSchemaV3Test {

    private val t0 = FoundationDate.unix(1_700_000_000.0) // 2023-11-14T22:13:20Z
    private val t1 = FoundationDate.unix(1_700_003_600.0) // +1 h
    private val night = FoundationDate.unix(1_699_920_000.0)
    private val zone: ZoneId = ZoneId.of("America/New_York")

    private fun plus(t: Instant, seconds: Long): Instant = t.plusSeconds(seconds)

    // MARK: - Fixtures

    private val sampleRow get() = SampleRow(kind = "heartRate", start = t0, end = t1, value = 72.0)

    private val sleepRow
        get() = SleepRow(
            night = night, asleepMin = 450, deepMin = 90, lightMin = 180,
            remMin = 120, awakeMin = 30, efficiency = 0.9375,
            inBedStart = t0, inBedEnd = t1, skinTempC = 36.5, sleepScore = 82, stressScore = 40,
            feelScore = 7, hrDeep = 55, hrLight = 60, hrRem = 64, hrAwake = 68,
            movementLevels = listOf(0, 1, 2),
        )

    private val evidenceRow: HistorySyncEvidenceRow
        get() {
            val trace = HistoryChannelTrace(label = "sleep", channel = 0x00, startedAt = t0)
            trace.finishedAt = t1
            trace.sawSyncAck = true
            trace.page4CCount = 1
            trace.endMarkerCount = 1
            trace.recordsAtStart = 2
            trace.recordsAtEnd = 8
            trace.exitReason = HistoryChannelExitReason.END_MARKER
            return HistorySyncEvidenceRow(
                capturedAt = t0, ringID = "ring-1", trigger = "manual",
                sleepCommitted = true, stagedSleepSegments = 4,
                mergedRecordCount = 8, historySampleCount = 10,
                rawRecordBlobBase64 = "AQID", channels = listOf(trace),
            )
        }

    private val hypnogram
        get() = listOf(
            SleepSegment(t0, plus(t0, 150), SleepStage.ASLEEP_CORE),
            SleepSegment(plus(t0, 150), plus(t0, 600), SleepStage.ASLEEP_DEEP),
        )

    /**
     * The shape `SleepStaging.stageSegments` ACTUALLY returns: a whole-night in-bed envelope, then the
     * stage segments tiling that same span. The stored blob carries it verbatim.
     */
    private val stagedNight: List<SleepSegment>
        get() {
            val end = plus(t0, 1_800)
            return listOf(
                SleepSegment(t0, end, SleepStage.IN_BED),
                SleepSegment(t0, plus(t0, 300), SleepStage.AWAKE),
                SleepSegment(plus(t0, 300), plus(t0, 900), SleepStage.ASLEEP_CORE),
                SleepSegment(plus(t0, 900), plus(t0, 1_500), SleepStage.ASLEEP_DEEP),
                SleepSegment(plus(t0, 1_500), end, SleepStage.AWAKE),
            )
        }

    /** A night stitched from two fragments carries one in-bed envelope PER FRAGMENT. */
    private val stitchedNight: List<SleepSegment>
        get() {
            val gap = plus(t0, 3_600)
            return listOf(
                SleepSegment(t0, plus(t0, 600), SleepStage.IN_BED),
                SleepSegment(t0, plus(t0, 600), SleepStage.ASLEEP_CORE),
                SleepSegment(gap, plus(gap, 600), SleepStage.IN_BED),
                SleepSegment(gap, plus(gap, 600), SleepStage.ASLEEP_REM),
            )
        }

    private val metadata
        get() = ExportMetadata(
            exportedAt = t0, rangeStart = t0, rangeEnd = t1,
            appVersion = "1.0", appBuild = "37", deviceModel = "iPhone15,2", osVersion = "18.5",
            ringModel = "RingConn Gen2", ringFirmware = "FR02.018", ringGeneration = "Gen 2",
            ringIdentifier = "1E2E3E4E-0000-0000-0000-000000000001",
            timeZoneIdentifier = "Europe/Amsterdam", timeZoneOffsetSeconds = 3_600,
        )

    private fun session(
        hypnogram: List<SleepSegment> = emptyList(),
        osa: OSARow? = null,
        coverage: ExportCoverage.Assessment? = null,
        edgeProvenance: SleepEdgeProvenanceRow? = null,
        night: Instant? = null,
    ): SleepSessionRow {
        val n = night ?: this.night
        return SleepSessionRow(
            sessionID = ExportEngine.sessionID(n, zone), night = n,
            inBedStart = t0, inBedEnd = t1, sleepOnset = plus(t0, 600),
            sleepWake = t1, isManuallyEdited = false,
            hypnogram = hypnogram, summary = sleepRow, osa = osa, coverage = coverage,
            edgeProvenance = edgeProvenance,
        )
    }

    /**
     * A night whose recording STOPPED at the wake and resumed 4 h later, built through the same
     * `SleepConfidence.assess` the app calls.
     */
    private val stoppedAtWakeEdge: SleepEdgeProvenanceRow
        get() {
            val assessment = SleepConfidence.assess(
                asleep = 5.0 * 3600, inBed = 6.0 * 3600,
                coverage = SleepConfidence.Coverage(
                    inBedStart = t0, inBedEnd = t1,
                    lastMeasurementBeforeStart = plus(t0, -100),
                    firstMeasurementAfterEnd = plus(t1, 4 * 3600),
                    measurementsAfterEnd = emptyList(),
                    earliestRetainedMeasurement = plus(t0, -7 * 86_400),
                ),
            )
            return SleepEdgeProvenanceRow(windowStart = t0, windowEnd = t1, assessment = assessment)
        }

    private fun parsed(json: String?): ReplayJson.Obj {
        if (json == null) fail("toJSON did not produce a JSON object")
        return ExportJsonReader.root(json)
    }

    private fun json(
        samples: List<SampleRow> = emptyList(),
        metadata: ExportMetadata? = null,
        sleepSessions: List<SleepSessionRow> = emptyList(),
    ): ReplayJson.Obj = parsed(ExportEngine.toJSON(samples = samples, sleep = emptyList(), daily = emptyList(), zone = zone, now = t0, metadata = metadata, sleepSessions = sleepSessions))

    private fun firstSession(obj: ReplayJson.Obj): ReplayJson.Obj? = obj["sleepSessions"]?.asObjectList()?.firstOrNull()

    // MARK: - 1. Superset lock

    @Test
    fun schemaV3IsAStrictSupersetOfSchemaV2() {
        val obj = parsed(
            ExportEngine.toJSON(
                samples = listOf(sampleRow), sleep = listOf(sleepRow),
                daily = listOf(DailyRow(day = night, steps = 8_000)),
                stepSamples = listOf(StepSampleRow(start = t0, end = t1, delta = 123)),
                naps = listOf(NapRow(start = t0, end = t1, asleepMin = 30, isLongNap = false)),
                daytimeTemperatures = listOf(DaytimeTemperatureRow(time = t0, celsius = 34.2)),
                historySyncEvidence = listOf(evidenceRow), zone = zone, now = t0,
            ),
        )

        // The one deliberate change from v2. Everything else below must be identical.
        assertEquals(3L, obj.long("schemaVersion"))
        assertEquals("2023-11-14T22:13:20.000Z", obj.string("exportedAt"))

        for (key in listOf("samples", "sleep", "daily", "stepSamples", "naps", "daytimeTemperatures", "historySyncEvidence")) {
            assertNotNull(obj[key]?.asObjectList(), "v2 section '$key' disappeared")
        }

        val dayLabel = localDayLabel(night)
        assertElement(
            obj, "samples",
            mapOf("kind" to "heartRate", "start" to "2023-11-14T22:13:20.000Z", "end" to "2023-11-14T23:13:20.000Z", "value" to 72.0),
        )
        assertElement(
            obj, "sleep",
            mapOf(
                "night" to dayLabel,
                "asleepMin" to 450, "deepMin" to 90, "lightMin" to 180, "remMin" to 120, "awakeMin" to 30,
                "efficiency" to 0.9375,
                "inBedStart" to "2023-11-14T22:13:20.000Z",
                "inBedEnd" to "2023-11-14T23:13:20.000Z",
                "skinTempC" to 36.5,
                "sleepScore" to 82, "stressScore" to 40, "feelScore" to 7,
                "hrDeep" to 55, "hrLight" to 60, "hrRem" to 64, "hrAwake" to 68,
                "movementLevels" to listOf(0, 1, 2),
            ),
        )
        assertElement(obj, "daily", mapOf("day" to dayLabel, "steps" to 8_000))
        assertElement(obj, "stepSamples", mapOf("start" to "2023-11-14T22:13:20.000Z", "end" to "2023-11-14T23:13:20.000Z", "delta" to 123))
        assertElement(
            obj, "naps",
            mapOf("start" to "2023-11-14T22:13:20.000Z", "end" to "2023-11-14T23:13:20.000Z", "asleepMin" to 30, "isLongNap" to false),
        )
        assertElement(obj, "daytimeTemperatures", mapOf("time" to "2023-11-14T22:13:20.000Z", "celsius" to 34.2))
        assertElement(
            obj, "historySyncEvidence",
            mapOf(
                "capturedAt" to "2023-11-14T22:13:20.000Z",
                "ringID" to "ring-1",
                "trigger" to "manual",
                "sleepCommitted" to true,
                "stagedSleepSegments" to 4,
                "mergedRecordCount" to 8,
                "historySampleCount" to 10,
                "rawRecordBlobBase64" to "AQID",
                // ADDITIVE: a NEW key inside a v2 section does not break a v2 consumer, which reads the
                // keys it knows.
                "nightRowOutcome" to null,
                "channels" to listOf(
                    mapOf(
                        "label" to "sleep",
                        "channel" to 0,
                        "startedAt" to "2023-11-14T22:13:20.000Z",
                        "finishedAt" to "2023-11-14T23:13:20.000Z",
                        "outcome" to "complete",
                        "sawSyncAck" to true,
                        "syncAckFlag" to null,
                        "page4CCount" to 1,
                        "page47Count" to 0,
                        // ADDITIVE, present as 0 because a new trace zeroes them; a trace from before
                        // they existed emits null instead.
                        "page4DCount" to 0,
                        "sportSampleCount" to 0,
                        "endMarkerCount" to 1,
                        "recordsAtStart" to 2,
                        "recordsAtEnd" to 8,
                        "recordsAdded" to 6,
                        "firstOpcode" to null,
                        "lastOpcode" to null,
                        "exitReason" to "endMarker",
                    ),
                ),
            ),
        )
    }

    @Test
    fun v2CallSiteWithoutNewParametersStillEmitsEverySection() {
        // Signature compatibility: the pre-v3 call shape must still compile and still work.
        val obj = parsed(ExportEngine.toJSON(samples = emptyList(), sleep = emptyList(), daily = emptyList(), zone = zone, now = t0))
        for (key in listOf("samples", "sleep", "daily", "stepSamples", "naps", "daytimeTemperatures", "historySyncEvidence")) {
            assertEquals(0, obj[key]?.asObjectList()?.size, "'$key' must still exist")
        }
        assertNull(obj["meta"], "meta must be absent when no metadata was supplied")
        assertNull(obj["sleepSessions"], "sleepSessions must be absent when empty")
    }

    // MARK: - 7. Metadata

    @Test
    fun metaBlockOmittedEntirelyWhenMetadataIsNil() {
        val obj = json(samples = listOf(sampleRow))
        assertNull(obj["meta"])
    }

    @Test
    fun metaBlockCarriesEveryField() {
        val meta = json(metadata = metadata).obj("meta") ?: fail("meta missing")
        assertEquals(3L, meta.long("schemaVersion"))
        assertEquals("1.0", meta.string("appVersion"))
        assertEquals("37", meta.string("appBuild"))
        assertEquals("iPhone15,2", meta.string("deviceModel"))
        assertEquals("18.5", meta.string("osVersion"))
        assertEquals("RingConn Gen2", meta.string("ringModel"))
        assertEquals("FR02.018", meta.string("ringFirmware"))
        assertEquals("Gen 2", meta.string("ringGeneration"))
        assertEquals("1E2E3E4E-0000-0000-0000-000000000001", meta.string("ringIdentifier"))
        assertEquals("Europe/Amsterdam", meta.string("timeZoneIdentifier"))
        assertEquals(3_600L, meta.long("timeZoneOffsetSeconds"))
        assertEquals(ExportEngine.TIMESTAMP_POLICY_DESCRIPTION, meta.string("timestampPolicy"))
        for (key in listOf("exportedAt", "rangeStart", "rangeEnd")) {
            assertNotNull(meta.string(key), "$key missing from meta")
        }
    }

    @Test
    fun metadataCSVRoundTripsEveryField() {
        val csv = ExportEngine.metadataCSV(metadata, zone)
        val records = parseCSV(csv)
        assertEquals(listOf("field", "value"), records.firstOrNull() ?: emptyList<String>())

        val byField = mutableMapOf<String, String>()
        for (record in records.drop(1)) if (record.size == 2) byField[record[0]] = record[1]
        assertEquals(records.size - 1, byField.size, "duplicate or malformed metadata rows")

        // Field names must be the JSON keys, so the two views can be joined.
        val meta = json(metadata = metadata).obj("meta") ?: fail("meta missing")
        assertEquals(meta.keys, byField.keys, "metadataCSV and the JSON meta block must name the same fields")

        assertEquals("3", byField["schemaVersion"])
        assertEquals("1.0", byField["appVersion"])
        assertEquals("37", byField["appBuild"])
        assertEquals("iPhone15,2", byField["deviceModel"])
        assertEquals("18.5", byField["osVersion"])
        assertEquals("RingConn Gen2", byField["ringModel"])
        assertEquals("FR02.018", byField["ringFirmware"])
        assertEquals("Gen 2", byField["ringGeneration"])
        assertEquals("1E2E3E4E-0000-0000-0000-000000000001", byField["ringIdentifier"])
        assertEquals("Europe/Amsterdam", byField["timeZoneIdentifier"])
        assertEquals("3600", byField["timeZoneOffsetSeconds"])
        assertEquals(ExportEngine.TIMESTAMP_POLICY_DESCRIPTION, byField["timestampPolicy"])
        assertEquals(ExportEngine.offsetISO8601(t0, zone), byField["exportedAt"])
    }

    @Test
    fun metadataCSVQuotesTheDeviceModelComma() {
        // "iPhone15,2" is the exact value that used to corrupt a row — it has a comma in it.
        val csv = ExportEngine.metadataCSV(metadata, zone)
        assertTrue(csv.contains("deviceModel,\"iPhone15,2\""), "deviceModel must be RFC-4180 quoted — got:\n$csv")
    }

    /**
     * PRIVACY, the half this layer can enforce: the metadata block's FIELD SET is locked, so no
     * MAC-carrying or device-name field can be added to the schema without failing here. (Stripping the
     * advertised name's MAC suffix is the caller's job and is tested there.)
     */
    @Test
    fun metadataFieldSetIsLockedAgainstAMACOrDeviceNameField() {
        val fields = parseCSV(ExportEngine.metadataCSV(metadata, zone)).drop(1).mapNotNull { it.firstOrNull() }.toSet()
        assertEquals(
            setOf(
                "schemaVersion", "exportedAt", "rangeStart", "rangeEnd",
                "appVersion", "appBuild", "deviceModel", "osVersion",
                "ringModel", "ringFirmware", "ringGeneration", "ringIdentifier",
                "timeZoneIdentifier", "timeZoneOffsetSeconds", "timestampPolicy",
            ),
            fields,
            "The metadata field set changed. If a field carrying a ring MAC or a user-assigned device name was added, " +
                "remove it — this file is one users hand to third parties. If the addition is benign, add it here deliberately.",
        )
    }

    /**
     * The detector the test above relies on is not vacuous: fed a metadata block that DOES carry a MAC,
     * the same search finds it.
     */
    @Test
    fun theMACSearchWouldActuallyCatchAMACIfOneWerePresent() {
        val clean = ExportEngine.metadataCSV(metadata, zone)
        assertFalse(clean.contains("F8:79:99:F7:03:AD"))
        assertFalse(clean.contains("-03AD"))

        val poisoned = ExportMetadata.of(
            zone, exportedAt = t0, rangeStart = t0, rangeEnd = t1,
            ringModel = "RingConn Gen2-03AD", // the raw ADVERTISED name
            ringIdentifier = "F8:79:99:F7:03:AD", // a MAC in the id slot
        )
        val poisonedCSV = ExportEngine.metadataCSV(poisoned, zone)
        assertTrue(poisonedCSV.contains("F8:79:99:F7:03:AD"))
        assertTrue(
            poisonedCSV.contains("-03AD"),
            "the serializer copies its inputs verbatim, so the MAC guarantee has to be made upstream — this test only proves the check can fail",
        )
    }

    // MARK: - 6. Session + hypnogram CSV

    @Test
    fun sleepSessionsCSVHeaderIsExactAndEmptyIsHeaderOnly() {
        val csv = ExportEngine.sleepSessionsCSV(emptyList(), zone)
        // The four trailing columns are APPENDED, and `coverageFraction` keeps index 21.
        assertEquals(
            "sessionID,night,inBedStart,inBedEnd,sleepOnset,sleepWake,isManuallyEdited,asleepMin,deepMin,lightMin,remMin,awakeMin,efficiency,sleepScore,stressScore,hypnogramSegments,osaAvgSpO2,osaMinSpO2,osaTimeBelow90Sec,osaODI,osaValidWindows,coverageFraction,expectedSamples,observedSamples,longestGapSeconds,bedtimeVerdict,bedtimeGapSeconds,wakeVerdict,wakeGapSeconds,confidenceReasons,durationBasis,referenceWakeSource,referenceWakeAt,coverageToReferenceWake",
            csv,
        )
        assertFalse(csv.contains("\n"), "empty input is header-only")
    }

    @Test
    fun sleepSessionsCSVWithoutOSAOrCoverageEmitsEmptyFieldsNotZeros() {
        val records = parseCSV(ExportEngine.sleepSessionsCSV(listOf(session()), zone))
        assertEquals(2, records.size)
        val row = records[1]
        assertEquals(34, row.size)
        for (index in 16..33) assertEquals("", row[index], "column $index must be EMPTY when absent — 0 is a real reading")
        assertEquals("night-${localDayLabel(night)}", row[0])
        assertEquals(localDayLabel(night), row[1])
        assertEquals(
            "", row[15],
            "no stored timeline is an ABSENCE — 0 would claim we staged the night and found no stage blocks, which is a different fact",
        )
    }

    // MARK: - hypnogramSegments: absence and zero must be different values

    @Test
    fun hypnogramSegmentsIsEmptyForANotRecordedTimelineAndZeroForAnEnvelopeOnlyNight() {
        val notRecorded = parseCSV(ExportEngine.sleepSessionsCSV(listOf(session(hypnogram = emptyList())), zone))[1]
        assertEquals("", notRecorded[15], "no timeline recorded ⇒ EMPTY, never 0")

        val envelopeOnly = parseCSV(ExportEngine.sleepSessionsCSV(listOf(session(hypnogram = listOf(SleepSegment(t0, t1, SleepStage.IN_BED)))), zone))[1]
        assertEquals("0", envelopeOnly[15], "a recorded timeline with no stage blocks IS a measured 0")
    }

    @Test
    fun hypnogramJSONKeyIsAbsentWhenNotRecordedAndEmptyWhenRecordedWithNoStages() {
        val absent = json(sleepSessions = listOf(session(hypnogram = emptyList())))
        assertNull(firstSession(absent)?.get("hypnogram"), "not recorded ⇒ omit the key, the same convention osa/coverage use")

        val recorded = json(sleepSessions = listOf(session(hypnogram = listOf(SleepSegment(t0, t1, SleepStage.IN_BED)))))
        assertEquals(0, firstSession(recorded)?.get("hypnogram")?.asObjectList()?.size, "recorded but no stage blocks ⇒ the key is present and empty")
    }

    @Test
    fun sleepSessionsCSVWithOSAAndCoverage() {
        val coverage = ExportCoverage.assess(sampleTimes = (0L until 20L).map { plus(t0, it * 150) }, from = t0, to = plus(t0, 24 * 150))
        val osa = OSARow(avgSpO2 = 95.4, minSpO2 = 88.0, timeBelow90Sec = 312.5, odi = 4.25, validWindows = 96)
        val row = parseCSV(ExportEngine.sleepSessionsCSV(listOf(session(hypnogram = hypnogram, osa = osa, coverage = coverage)), zone))[1]
        assertEquals("2", row[15], "two hypnogram segments")
        assertEquals("95.40", row[16])
        assertEquals("88.00", row[17])
        assertEquals("312.5", row[18])
        assertEquals("4.25", row[19])
        assertEquals("96", row[20])
        assertEquals(swiftFixed(coverage.coverageFraction, 4), row[21])
        assertEquals("24", row[22])
        assertEquals("20", row[23])
        assertEquals(swiftFixed(coverage.longestGapSeconds, 1), row[24])
    }

    @Test
    fun zeroValidWindowsIsTreatedAsNoAssessmentAtAll() {
        // A row of zeros from an undrained assessment is indistinguishable from a measured perfect
        // night — the serializer must drop it, not print it.
        val empty = OSARow(avgSpO2 = 0.0, minSpO2 = 0.0, timeBelow90Sec = 0.0, odi = 0.0, validWindows = 0)
        val row = parseCSV(ExportEngine.sleepSessionsCSV(listOf(session(osa = empty)), zone))[1]
        for (index in 16..20) assertEquals("", row[index], "OSA column $index must be empty, not 0")
        assertNull(firstSession(json(sleepSessions = listOf(session(osa = empty))))?.get("osa"))
    }

    // MARK: - edgeProvenance: the half `coverage` structurally cannot see

    @Test
    fun edgeProvenanceCSVCarriesBothVerdictsAndOnlyTheMeasuredGaps() {
        val row = parseCSV(ExportEngine.sleepSessionsCSV(listOf(session(edgeProvenance = stoppedAtWakeEdge)), zone))[1]
        assertEquals("witnessed", row[25], "a record 100 s before the edge is continuous")
        assertEquals("", row[26], "a witnessed edge measured NO silence — empty, never 0")
        assertEquals("stoppedThenResumed", row[27])
        assertEquals(swiftFixed(4 * 3600.0, 1), row[28])
        assertEquals(
            "noRecordingAfterWake", row[29],
            "the reason list is what the CLASSIFIER concluded — not what any screen showed. No coverage caveat ships, so a failure " +
                "here means the export lost the classifier's own output, never that the UI disagreed with it.",
        )
    }

    @Test
    fun edgeProvenanceJSONOmitsTheGapKeyRatherThanWritingZero() {
        val edge = firstSession(json(sleepSessions = listOf(session(edgeProvenance = stoppedAtWakeEdge))))?.obj("edgeProvenance")
            ?: fail("edgeProvenance block missing")
        assertEquals("witnessed", edge.string("bedtimeVerdict"))
        assertNull(
            edge["bedtimeGapSeconds"],
            "0 would turn 'the stream never stopped' and 'we could not look' into the same value — the exact absence-is-not-zero rule osa/coverage follow",
        )
        assertEquals("stoppedThenResumed", edge.string("wakeVerdict"))
        assertEquals(4 * 3600.0, edge.double("wakeGapSeconds"))
        assertEquals(listOf("noRecordingAfterWake"), edge["reasons"]?.asStringList())
        assertEquals(WakeProvenance.MATERIAL_GAP_SECONDS, edge.double("materialGapSeconds"))
    }

    @Test
    fun edgeProvenanceKeyIsAbsentWhenTheNightHasNoneAndTheCSVFieldsAreEmpty() {
        assertNull(firstSession(json(sleepSessions = listOf(session())))?.get("edgeProvenance"), "no measurable window ⇒ omit the key, the convention osa/coverage use")
        val row = parseCSV(ExportEngine.sleepSessionsCSV(listOf(session()), zone))[1]
        for (index in 25..29) assertEquals("", row[index], "edge column $index must be empty when unmeasured")
    }

    /** The claim the feature rests on: a night whose stream stops dead at the wake reports PERFECT coverage. */
    @Test
    fun aFullyCoveredWindowStillCarriesAStoppedWakeVerdict() {
        val span = Math.toIntExact(t1.epochSecond - t0.epochSecond)
        val coverage = ExportCoverage.assess(sampleTimes = (0 until span step 150).map { plus(t0, it.toLong()) }, from = t0, to = t1)
        assertTrue(coverage.coverageFraction > 0.95, "fixture must be a night the coverage fraction calls complete")
        val s = firstSession(json(sleepSessions = listOf(session(coverage = coverage, edgeProvenance = stoppedAtWakeEdge))))
        assertNotNull(s?.get("coverage"), "coverage still reports the window as covered")
        assertEquals("stoppedThenResumed", s?.obj("edgeProvenance")?.string("wakeVerdict"), "…while edgeProvenance reports the 4 h hole that begins AT the wake")
    }

    @Test
    fun sleepSessionsCSVOrderingIsDeterministic() {
        val earlier = night
        val later = night.plusSeconds(86_400)
        val rows = listOf(session(night = later), session(night = earlier))
        val ids = parseCSV(ExportEngine.sleepSessionsCSV(rows, zone)).drop(1).map { it[0] }
        assertEquals(listOf(rows[0].sessionID, rows[1].sessionID), ids, "rows are emitted in the order given, never reordered")
    }

    /**
     * THE HEADER IS A CONTRACT AND `provenance` IS PART OF IT: without that column an asleep block the
     * wearer asserted over ground holding no ring data is byte-identical to one the ring recorded.
     */
    @Test
    fun hypnogramCSVHeaderAndEmptyInput() {
        assertEquals("sessionID,start,end,stage,durationSec,provenance", ExportEngine.hypnogramCSV(emptyList(), zone))
    }

    /** The vocabulary must be the JSON's, character for character — one enum renders both. */
    @Test
    fun hypnogramCSVProvenanceVocabularyMatchesTheJSONExactly() {
        val mixed = listOf(
            SleepSegment(t0, plus(t0, 150), SleepStage.ASLEEP_CORE),
            SleepSegment(plus(t0, 150), plus(t0, 300), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED),
            SleepSegment(plus(t0, 300), plus(t0, 450), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED_OVER_MEASURED),
            SleepSegment(plus(t0, 450), plus(t0, 600), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED_COVERAGE_UNKNOWN),
        )
        val rows = parseCSV(ExportEngine.hypnogramCSV(listOf(session(hypnogram = mixed)), zone)).drop(1)
        assertEquals(listOf("measured", "asserted", "assertedOverMeasured", "assertedCoverageUnknown"), rows.map { it[5] })

        // …and every one of those strings is a SleepProvenance raw value, which is what the JSON emits.
        val vocabulary = SleepProvenance.entries.map { it.rawValue }.toSet()
        assertTrue(rows.all { it[5] in vocabulary })

        val json = firstSession(json(sleepSessions = listOf(session(hypnogram = mixed))))?.get("hypnogram")?.asObjectList()
        // JSON omits the key for measured (absence means measured); CSV always prints it.
        assertEquals(rows.map { it[5] }, json?.map { it.string("provenance") ?: "measured" })
    }

    /** The invented block must not be able to hide in the clinician's copy: same span and stage, different provenance, DIFFERENT bytes. */
    @Test
    fun anInventedBlockIsNoLongerByteIdenticalToAMeasuredOne() {
        val span = listOf(SleepSegment(t0, plus(t0, 14_758), SleepStage.ASLEEP_CORE))
        val invented = listOf(SleepSegment(t0, plus(t0, 14_758), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED))
        assertNotEquals(
            ExportEngine.hypnogramCSV(listOf(session(hypnogram = span)), zone),
            ExportEngine.hypnogramCSV(listOf(session(hypnogram = invented)), zone),
            "246 invented minutes serialised exactly like 246 recorded ones",
        )
    }

    @Test
    fun hypnogramCSVEmitsOneRowPerSegmentAcrossSessions() {
        val a = session(hypnogram = hypnogram)
        val b = session(hypnogram = listOf(SleepSegment(t1, plus(t1, 300), SleepStage.ASLEEP_REM)), night = night.plusSeconds(86_400))
        val records = parseCSV(ExportEngine.hypnogramCSV(listOf(a, b), zone))
        assertEquals(4, records.size, "header + 2 + 1 segments")
        assertEquals(a.sessionID, records[1][0])
        assertEquals("asleepCore", records[1][3])
        assertEquals("150", records[1][4])
        assertEquals("asleepDeep", records[2][3])
        assertEquals("450", records[2][4])
        assertEquals(b.sessionID, records[3][0])
        assertEquals("asleepREM", records[3][3])
        assertEquals("300", records[3][4])
    }

    @Test
    fun sessionWithNoHypnogramEmitsNoHypnogramRows() {
        assertEquals("sessionID,start,end,stage,durationSec,provenance", ExportEngine.hypnogramCSV(listOf(session()), zone))
    }

    // MARK: - The emitted hypnogram is a PARTITION, not a partition plus an umbrella

    @Test
    fun emittedHypnogramCarriesNoOverlappingInBedEnvelope() {
        val rows = parseCSV(ExportEngine.hypnogramCSV(listOf(session(hypnogram = stagedNight)), zone)).drop(1)
        assertEquals(4, rows.size, "the 4 stage blocks, not 5 rows")
        assertFalse(rows.any { it[3] == "inBed" }, "the in-bed envelope is carried by inBedStart/inBedEnd, not as a segment")
    }

    @Test
    fun emittedHypnogramDurationsSumToTheInBedSpanNotDoubleIt() {
        val inBedSpan = 1_800.0 // stagedNight's envelope
        val rows = parseCSV(ExportEngine.hypnogramCSV(listOf(session(hypnogram = stagedNight)), zone)).drop(1)
        val total = rows.mapNotNull { it[4].toDoubleOrNull() }.sum()
        assertEquals(inBedSpan, total, 0.001, "summing durationSec must give the night, not twice the night")
    }

    @Test
    fun emittedHypnogramSegmentsDoNotOverlap() {
        for (fixture in listOf(stagedNight, stitchedNight)) {
            val rows = parseCSV(ExportEngine.hypnogramCSV(listOf(session(hypnogram = fixture)), zone)).drop(1)
            val spans = rows.map { it[1] to it[2] }.sortedBy { it.first }
            for ((a, b) in spans.zipWithNext()) assertTrue(a.second <= b.first, "segments $a and $b overlap")
        }
    }

    @Test
    fun stitchedNightDropsEveryFragmentEnvelopeNotJustTheFirst() {
        val rows = parseCSV(ExportEngine.hypnogramCSV(listOf(session(hypnogram = stitchedNight)), zone)).drop(1)
        assertEquals(2, rows.size, "two fragments, two stage blocks, zero envelopes")
        assertEquals(listOf("asleepCore", "asleepREM"), rows.map { it[3] })
    }

    @Test
    fun hypnogramSegmentCountExcludesTheEnvelopeInBothViews() {
        val row = parseCSV(ExportEngine.sleepSessionsCSV(listOf(session(hypnogram = stagedNight)), zone))[1]
        assertEquals("4", row[15], "hypnogramSegments counts stage blocks, not the envelope")

        val segments = firstSession(json(sleepSessions = listOf(session(hypnogram = stagedNight))))?.get("hypnogram")?.asObjectList()
        assertEquals(4, segments?.size, "the JSON view must agree with the CSV count")
        assertFalse(segments?.any { it.string("stage") == "inBed" } ?: true)
    }

    /** A night whose stored blob is ONLY an envelope has no timeline to report. */
    @Test
    fun envelopeOnlyNightEmitsNoRowsRatherThanAnAllNightBar() {
        val envelopeOnly = listOf(SleepSegment(t0, t1, SleepStage.IN_BED))
        assertEquals("sessionID,start,end,stage,durationSec,provenance", ExportEngine.hypnogramCSV(listOf(session(hypnogram = envelopeOnly)), zone))
    }

    // MARK: - sleepSessions JSON

    @Test
    fun sleepSessionsJSONOmittedWhenEmpty() {
        assertNull(json(sleepSessions = emptyList())["sleepSessions"])
    }

    @Test
    fun sleepSessionJSONOmitsAbsentOSAAndCoverage() {
        val s = firstSession(json(sleepSessions = listOf(session()))) ?: fail("sleepSessions missing")
        assertNull(s["osa"], "absent OSA must be omitted, not zero-filled")
        assertNull(s["coverage"], "absent coverage must be omitted, not zero-filled")
        assertNull(s["hypnogram"], "a timeline that was never recorded must be omitted too")
        assertNotNull(s.obj("summary"))
        assertEquals("night-${localDayLabel(night)}", s.string("sessionID"))
    }

    @Test
    fun sleepSessionJSONCarriesHypnogramOSAAndCoverage() {
        val coverage = ExportCoverage.assess(sampleTimes = listOf(t0), from = t0, to = plus(t0, 3_000))
        val osa = OSARow(avgSpO2 = 95.4, minSpO2 = 88.0, timeBelow90Sec = 312.5, odi = 4.25, validWindows = 96)
        val s = firstSession(json(sleepSessions = listOf(session(hypnogram = hypnogram, osa = osa, coverage = coverage)))) ?: fail("sleepSessions missing")
        assertEquals(2, s["hypnogram"]?.asObjectList()?.size)
        assertEquals("asleepCore", s["hypnogram"]?.asObjectList()?.firstOrNull()?.string("stage"))
        assertEquals(96L, s.obj("osa")?.long("validWindows"))
        assertEquals(1L, s.obj("coverage")?.long("observedSamples"))
        assertEquals(20L, s.obj("coverage")?.long("expectedSamples"))
        assertEquals(1, s.obj("coverage")?.get("gaps")?.asObjectList()?.size)
    }

    // MARK: - Helpers

    /** yyyy-MM-dd in [zone] — reimplemented here so the expectation is independent of the engine's own formatter. */
    private fun localDayLabel(date: Instant): String = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT).withZone(zone).format(date)

    /**
     * Whole-object comparison of a section's single element, as upstream's `NSDictionary.isEqual(to:)`:
     * locks both the key SET and every value (numbers by value, so `72` equals `72.0`; booleans apart
     * from numbers; `null` for upstream's `NSNull`), so an added or renamed key inside a v2 section fails.
     */
    private fun assertElement(root: ReplayJson.Obj, section: String, expected: Map<String, Any?>) {
        val element = root[section]?.asObjectList()?.firstOrNull() ?: fail("section '$section' has no first element")
        assertTrue(same(element, expected), "Section '$section' changed shape or values.\nexpected: $expected\nactual keys: ${element.keys}")
    }

    private fun same(actual: ReplayJson.Value?, expected: Any?): Boolean = when (expected) {
        null -> actual == ReplayJson.Null
        is Boolean -> actual is ReplayJson.Bool && actual.value == expected
        is Number -> actual is ReplayJson.Number && actual.asDouble() == expected.toDouble()
        is String -> actual is ReplayJson.Str && actual.value == expected
        is List<*> -> {
            val items = actual?.asArray()
            items != null && items.size == expected.size && items.indices.all { same(items[it], expected[it]) }
        }
        is Map<*, *> -> {
            val obj = actual?.asObject()
            obj != null && obj.keys == expected.keys && expected.all { (k, v) -> same(obj[k as String], v) }
        }
        else -> error("unsupported expected value $expected")
    }
}
