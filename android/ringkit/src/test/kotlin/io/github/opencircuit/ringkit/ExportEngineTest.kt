package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.ExportEngine.DailyRow
import io.github.opencircuit.ringkit.ExportEngine.DaytimeTemperatureRow
import io.github.opencircuit.ringkit.ExportEngine.HistorySyncEvidenceRow
import io.github.opencircuit.ringkit.ExportEngine.NapRow
import io.github.opencircuit.ringkit.ExportEngine.SampleRow
import io.github.opencircuit.ringkit.ExportEngine.SleepRow
import io.github.opencircuit.ringkit.ExportEngine.StepSampleRow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/ExportEngineTests.swift (@ b1c2fdd)
 * — all 31 tests. The JSON is read back through [ExportJsonReader], as upstream reads it with
 * `JSONSerialization.jsonObject`.
 *
 * Upstream formats the device-local labels (`night`, `day`) in `Calendar.current` and builds its
 * expectation in `TimeZone.current`; here both take [zone], fixed to America/New_York, where the
 * test's midnight-UTC `night` falls on the previous local day — so a label printed in UTC instead
 * would fail. `testLegacyChannelSummaryOmitsTheSportCountersEntirely` decodes a stored trace from
 * before the sport counters existed; the stored form is not ported yet (PORTING.md D-135), so the
 * trace it decodes is built directly, with both counters null.
 */
class ExportEngineTest {

    // Reference dates for deterministic output
    private val t0 = FoundationDate.unix(1_700_000_000.0) // 2023-11-14T22:13:20Z
    private val t1 = FoundationDate.unix(1_700_003_600.0) // +1 h
    private val night = FoundationDate.unix(1_699_920_000.0) // 2023-11-14T00:00:00Z
    private val zone: ZoneId = ZoneId.of("America/New_York")

    // MARK: samplesCSV

    @Test
    fun samplesCSVHeader() {
        val csv = ExportEngine.samplesCSV(emptyList())
        assertTrue(csv.startsWith("kind,start,end,value"), "header missing — got: $csv")
    }

    @Test
    fun samplesCSVOneRow() {
        val row = SampleRow(kind = "heartRate", start = t0, end = t1, value = 72.0)
        val csv = ExportEngine.samplesCSV(listOf(row))
        val lines = csv.split("\n")
        assertEquals(2, lines.size, "expected header + 1 data line")
        assertTrue(lines[1].startsWith("heartRate,"), "first field should be kind")
        assertTrue(lines[1].endsWith(",72"), "last field should be value 72")
    }

    @Test
    fun samplesCSVEmptyIsHeaderOnly() {
        val csv = ExportEngine.samplesCSV(emptyList())
        assertEquals("kind,start,end,value", csv)
    }

    @Test
    fun samplesCSVMultipleRows() {
        val rows = listOf(
            SampleRow(kind = "heartRate", start = t0, end = t1, value = 72.0),
            SampleRow(kind = "spo2", start = t1, end = t1, value = 0.98),
        )
        val lines = ExportEngine.samplesCSV(rows).split("\n")
        assertEquals(3, lines.size)
    }

    // MARK: sleepCSV

    @Test
    fun sleepCSVHeader() {
        val csv = ExportEngine.sleepCSV(emptyList(), zone)
        assertTrue(csv.startsWith("night,asleepMin,"), "header missing — got: $csv")
    }

    @Test
    fun sleepCSVOneRow() {
        val row = SleepRow(
            night = night, asleepMin = 450, deepMin = 90, lightMin = 180,
            remMin = 120, awakeMin = 30, efficiency = 0.9375,
            skinTempC = 36.5, sleepScore = 82, stressScore = 40,
        )
        val csv = ExportEngine.sleepCSV(listOf(row), zone)
        val lines = csv.split("\n")
        assertEquals(2, lines.size)
        assertTrue(lines[1].contains("450"), "asleepMin should appear")
        assertTrue(lines[1].contains("36.50"), "skinTempC should appear as 2 dp")
    }

    @Test
    fun sleepCSVEmptyIsHeaderOnly() {
        val csv = ExportEngine.sleepCSV(emptyList(), zone)
        assertTrue(csv.startsWith("night,"))
        assertFalse(csv.contains("\n"), "no newline in header-only result")
    }

    // MARK: dailyCSV

    @Test
    fun dailyCSVHeader() {
        val csv = ExportEngine.dailyCSV(emptyList(), zone)
        assertEquals("day,steps", csv)
    }

    @Test
    fun dailyCSVOneRow() {
        val row = DailyRow(day = night, steps = 8_000)
        val lines = ExportEngine.dailyCSV(listOf(row), zone).split("\n")
        assertEquals(2, lines.size)
        assertTrue(lines[1].endsWith(",8000"), "steps should be last field")
    }

    @Test
    fun stepSamplesCSVHeader() {
        assertEquals("start,end,delta", ExportEngine.stepSamplesCSV(emptyList()))
    }

    @Test
    fun napsCSVHeader() {
        assertEquals("start,end,asleepMin,isLongNap", ExportEngine.napsCSV(emptyList()))
    }

    @Test
    fun daytimeTemperatureCSVHeader() {
        assertEquals("time,celsius", ExportEngine.daytimeTemperatureCSV(emptyList()))
    }

    @Test
    fun historySyncEvidenceCSVHeader() {
        assertEquals(
            // `nightRowOutcome` is APPENDED — every pre-existing column keeps its index, which is the
            // compatibility property this header lock exists to protect.
            "capturedAt,ringID,trigger,sleepCommitted,stagedSleepSegments,mergedRecordCount,historySampleCount,channelSummary,rawRecordBlobBase64,nightRowOutcome",
            ExportEngine.historySyncEvidenceCSV(emptyList()),
        )
    }

    // MARK: toJSON

    @Test
    fun toJSONReturnsValidJSON() {
        val sRow = SampleRow(kind = "heartRate", start = t0, end = t1, value = 72.0)
        val slRow = SleepRow(
            night = night, asleepMin = 450, deepMin = 90, lightMin = 180,
            remMin = 120, awakeMin = 30, efficiency = 0.9375,
            inBedStart = t0, inBedEnd = t1, skinTempC = 36.5, sleepScore = 82, stressScore = 40,
            feelScore = 7, hrDeep = 55, hrLight = 60, hrRem = 64, hrAwake = 68, movementLevels = listOf(0, 1, 2),
        )
        val dRow = DailyRow(day = night, steps = 8_000)
        val stepRow = StepSampleRow(start = t0, end = t1, delta = 123)
        val napRow = NapRow(start = t0, end = t1, asleepMin = 30, isLongNap = false)
        val tempRow = DaytimeTemperatureRow(time = t0, celsius = 34.2)
        val trace = HistoryChannelTrace(label = "sleep", channel = 0x00, startedAt = t0)
        trace.finishedAt = t1
        trace.sawSyncAck = true
        trace.page4CCount = 1
        trace.endMarkerCount = 1
        trace.recordsAtStart = 2
        trace.recordsAtEnd = 8
        trace.exitReason = HistoryChannelExitReason.END_MARKER
        val evidenceRow = HistorySyncEvidenceRow(
            capturedAt = t0, ringID = "ring-1", trigger = "manual",
            sleepCommitted = true, stagedSleepSegments = 4,
            mergedRecordCount = 8, historySampleCount = 10,
            rawRecordBlobBase64 = "AQID", channels = listOf(trace),
        )

        val json = ExportEngine.toJSON(samples = listOf(sRow), sleep = listOf(slRow), daily = listOf(dRow), zone = zone, now = t0)
        assertNotNull(json, "toJSON should not return nil")

        val obj = ExportJsonReader.root(json)
        assertNotNull(obj["exportedAt"], "exportedAt key required")
        // Bumped 2 → 3 with the rich export. v3 is a byte-SUPERSET of v2, so this is the only value
        // that changed; `ExportSchemaV3Tests` proves every other v2 key is untouched.
        assertEquals(3L, obj.long("schemaVersion"))
        assertNotNull(obj["samples"]?.asObjectList())
        assertNotNull(obj["sleep"]?.asObjectList())
        assertNotNull(obj["daily"]?.asObjectList())
        val full = ExportEngine.toJSON(
            samples = listOf(sRow), sleep = listOf(slRow), daily = listOf(dRow),
            stepSamples = listOf(stepRow), naps = listOf(napRow),
            daytimeTemperatures = listOf(tempRow),
            historySyncEvidence = listOf(evidenceRow), zone = zone, now = t0,
        )
        assertNotNull(full)
        val fullObj = ExportJsonReader.root(full)
        assertEquals(1, fullObj["stepSamples"]?.asObjectList()?.size)
        assertEquals(1, fullObj["naps"]?.asObjectList()?.size)
        assertEquals(1, fullObj["daytimeTemperatures"]?.asObjectList()?.size)
        assertEquals(1, fullObj["historySyncEvidence"]?.asObjectList()?.size)
    }

    @Test
    fun toJSONEmptyInputsStillValid() {
        val json = ExportEngine.toJSON(samples = emptyList(), sleep = emptyList(), daily = emptyList(), zone = zone, now = t0)
        assertNotNull(json)
        val obj = ExportJsonReader.root(json)
        assertEquals(0, obj["samples"]?.asObjectList()?.size)
        assertEquals(0, obj["sleep"]?.asObjectList()?.size)
        assertEquals(0, obj["daily"]?.asObjectList()?.size)
    }

    @Test
    fun toJSONExportedAtPresent() {
        val json = ExportEngine.toJSON(samples = emptyList(), sleep = emptyList(), daily = emptyList(), zone = zone, now = t0)!!
        assertTrue(json.contains("exportedAt"), "exportedAt timestamp must appear")
    }

    // MARK: CSV byte identity (RFC-4180 escaper must be a no-op for clean values)

    // These expectations are hardcoded on purpose: they are what the writers emitted BEFORE the
    // escaper existed. If one of them fails, the escaper started quoting something it used to leave
    // alone and every downstream consumer's column offsets moved.

    @Test
    fun samplesCSVBytesUnchanged() {
        val rows = listOf(
            SampleRow(kind = "heartRate", start = t0, end = t1, value = 72.0),
            SampleRow(kind = "spo2", start = t0, end = t0, value = 0.98),
        )
        assertEquals(
            """
            kind,start,end,value
            heartRate,2023-11-14T22:13:20.000Z,2023-11-14T23:13:20.000Z,72
            spo2,2023-11-14T22:13:20.000Z,2023-11-14T22:13:20.000Z,0.98
            """.trimIndent(),
            ExportEngine.samplesCSV(rows),
        )
    }

    @Test
    fun stepSamplesCSVBytesUnchanged() {
        val row = StepSampleRow(start = t0, end = t1, delta = 123)
        assertEquals(
            """
            start,end,delta
            2023-11-14T22:13:20.000Z,2023-11-14T23:13:20.000Z,123
            """.trimIndent(),
            ExportEngine.stepSamplesCSV(listOf(row)),
        )
    }

    @Test
    fun napsCSVBytesUnchanged() {
        val row = NapRow(start = t0, end = t1, asleepMin = 30, isLongNap = false)
        assertEquals(
            """
            start,end,asleepMin,isLongNap
            2023-11-14T22:13:20.000Z,2023-11-14T23:13:20.000Z,30,false
            """.trimIndent(),
            ExportEngine.napsCSV(listOf(row)),
        )
    }

    @Test
    fun daytimeTemperatureCSVBytesUnchanged() {
        val row = DaytimeTemperatureRow(time = t0, celsius = 34.2)
        assertEquals(
            """
            time,celsius
            2023-11-14T22:13:20.000Z,34.20
            """.trimIndent(),
            ExportEngine.daytimeTemperatureCSV(listOf(row)),
        )
    }

    @Test
    fun historySyncEvidenceCSVBytesUnchanged() {
        val trace = HistoryChannelTrace(label = "sleep", channel = 0x00, startedAt = t0)
        trace.finishedAt = t1
        trace.sawSyncAck = true
        trace.page4CCount = 1
        trace.endMarkerCount = 1
        trace.recordsAtStart = 2
        trace.recordsAtEnd = 8
        trace.exitReason = HistoryChannelExitReason.END_MARKER
        val row = HistorySyncEvidenceRow(
            capturedAt = t0, ringID = "ring-1", trigger = "manual",
            sleepCommitted = true, stagedSleepSegments = 4,
            mergedRecordCount = 8, historySampleCount = 10,
            rawRecordBlobBase64 = "AQID", channels = listOf(trace),
            nightRowOutcome = SleepPersistOutcome.UPDATED.rawValue,
        )
        // `4d`/`sport` are APPENDED after `added=` inside the same `channelSummary` column: every
        // key=value pair a v2 consumer already parses keeps its text and its order, and no CSV column
        // index moves. They appear here because `HistoryChannelTrace`'s constructor zeroes the
        // counters — see `legacyChannelSummaryOmitsTheSportCountersEntirely` for the trace that
        // predates them, where they are omitted rather than reported as a measured zero.
        assertEquals(
            """
            capturedAt,ringID,trigger,sleepCommitted,stagedSleepSegments,mergedRecordCount,historySampleCount,channelSummary,rawRecordBlobBase64,nightRowOutcome
            2023-11-14T22:13:20.000Z,ring-1,manual,true,4,8,10,sleep:complete:4c=1:47=0:50=1:added=6:4d=0:sport=0,AQID,updated
            """.trimIndent(),
            ExportEngine.historySyncEvidenceCSV(listOf(row)),
        )
    }

    @Test
    fun sportChannelSummaryCarriesItsOwnEvidence() {
        // The sport channel streams no 0x4c and no 0x47, so before the 0x4d counters a drain full of
        // workout history exported `sport:empty:4c=0:47=0:50=1:added=0` — indistinguishable from a
        // channel that returned nothing. `added` is a bulkRecords delta and is structurally 0 here;
        // `4d`/`sport` are the only evidence this channel can produce.
        val trace = HistoryChannelTrace(label = "sport", channel = 0x02, startedAt = t0)
        trace.finishedAt = t1
        trace.sawSyncAck = true
        trace.page4DCount = 7
        trace.sportSampleCount = 210
        trace.endMarkerCount = 1
        trace.exitReason = HistoryChannelExitReason.END_MARKER
        val row = HistorySyncEvidenceRow(
            capturedAt = t0, ringID = "ring-1", trigger = "manual",
            sleepCommitted = false, stagedSleepSegments = 0,
            mergedRecordCount = 0, historySampleCount = 0,
            rawRecordBlobBase64 = "", channels = listOf(trace),
        )
        assertTrue(
            ExportEngine.historySyncEvidenceCSV(listOf(row))
                .contains("sport:sportOnly:4c=0:47=0:50=1:added=0:4d=7:sport=210"),
        )
    }

    @Test
    fun legacyChannelSummaryOmitsTheSportCountersEntirely() {
        // A trace decoded from a pre-2026-08-27 bundle has nil counters. Emitting `4d=0` there would
        // claim a measurement that build never took, so the pairs are omitted — the CSV then reads
        // byte-for-byte as it did before this change.
        // Upstream decodes {"label":"sport","channel":2,"startedAt":0,"sawSyncAck":true,
        // "sawEmptyHistorySignal":false,"page4CCount":0,"page47Count":0,"endMarkerCount":1,
        // "recordsAtStart":0,"recordsAtEnd":0,"exitReason":"endMarker"} with JSONDecoder; the stored
        // form is not ported yet (PORTING.md D-135), so the same trace is built field by field.
        val trace = HistoryChannelTrace(label = "sport", channel = 2, startedAt = FoundationDate.reference(0.0))
        trace.sawSyncAck = true
        trace.sawEmptyHistorySignal = false
        trace.page4CCount = 0
        trace.page47Count = 0
        trace.page4DCount = null
        trace.sportSampleCount = null
        trace.endMarkerCount = 1
        trace.recordsAtStart = 0
        trace.recordsAtEnd = 0
        trace.exitReason = HistoryChannelExitReason.END_MARKER
        val row = HistorySyncEvidenceRow(
            capturedAt = t0, ringID = "ring-1", trigger = "manual",
            sleepCommitted = false, stagedSleepSegments = 0,
            mergedRecordCount = 0, historySampleCount = 0,
            rawRecordBlobBase64 = "", channels = listOf(trace),
        )
        val csv = ExportEngine.historySyncEvidenceCSV(listOf(row))
        assertTrue(csv.contains("sport:empty:4c=0:47=0:50=1:added=0"))
        assertFalse(csv.contains("4d="))
        assertFalse(csv.contains(":sport="))
    }

    @Test
    fun sleepCSVBytesUnchanged() {
        // `night` is formatted in the DEVICE's local calendar (that is the calendar it was bucketed
        // with), so the expected label is built independently here rather than pinned.
        val row = SleepRow(
            night = night, asleepMin = 450, deepMin = 90, lightMin = 180,
            remMin = 120, awakeMin = 30, efficiency = 0.9375,
            inBedStart = t0, inBedEnd = t1, skinTempC = 36.5, sleepScore = 82, stressScore = 40,
            feelScore = 7, hrDeep = 55, hrLight = 60, hrRem = 64, hrAwake = 68,
            movementLevels = listOf(0, 1, 2),
        )
        assertEquals(
            """
            night,asleepMin,deepMin,lightMin,remMin,awakeMin,efficiency,inBedStart,inBedEnd,skinTempC,sleepScore,stressScore,feelScore,hrDeep,hrLight,hrRem,hrAwake,movementLevels
            ${localDayLabel(night)},450,90,180,120,30,0.9375,2023-11-14T22:13:20.000Z,2023-11-14T23:13:20.000Z,36.50,82,40,7,55,60,64,68,0|1|2
            """.trimIndent(),
            ExportEngine.sleepCSV(listOf(row), zone),
        )
    }

    @Test
    fun dailyCSVBytesUnchanged() {
        val row = DailyRow(day = night, steps = 8_000)
        assertEquals(
            """
            day,steps
            ${localDayLabel(night)},8000
            """.trimIndent(),
            ExportEngine.dailyCSV(listOf(row), zone),
        )
    }

    // MARK: RFC-4180 escaping

    @Test
    fun csvFieldIsNoOpForCleanValues() {
        for (clean in listOf(
            "", "heartRate", "2023-11-14T22:13:20.000Z", "0|1|2", "72", "ring-1",
            "sleep:complete:4c=1:47=0:50=1:added=6", "a b c",
        )) {
            assertEquals(clean, ExportEngine.csvField(clean), "clean value $clean must not be quoted")
        }
    }

    @Test
    fun csvFieldQuotesComma() {
        assertEquals("\"ring,1\"", ExportEngine.csvField("ring,1"))
    }

    @Test
    fun csvFieldDoublesEmbeddedQuotes() {
        assertEquals("\"he said \"\"go\"\"\"", ExportEngine.csvField("he said \"go\""))
    }

    @Test
    fun csvFieldQuotesNewlines() {
        assertEquals("\"line1\nline2\"", ExportEngine.csvField("line1\nline2"))
        assertEquals("\"line1\r\nline2\"", ExportEngine.csvField("line1\r\nline2"))
    }

    @Test
    fun csvFieldQuotesLeadingAndTrailingSpace() {
        // Unquoted, most parsers strip these and the value silently changes.
        assertEquals("\" leading\"", ExportEngine.csvField(" leading"))
        assertEquals("\"trailing \"", ExportEngine.csvField("trailing "))
    }

    @Test
    fun hostileValuesRoundTripThroughTheCSV() {
        // The real latent bug: a comma in a free-form column used to shift every later column.
        val hostile = listOf(
            "ring,with,commas" to "manual",
            "ring\"quoted\"" to "auto",
            "ring\nnewline" to "background",
            "ring\r\nCRLF" to "background", // one grapheme in Swift — see csvField's note
            " padded " to "manual",
        )
        for ((ringID, trigger) in hostile) {
            val row = HistorySyncEvidenceRow(
                capturedAt = t0, ringID = ringID, trigger = trigger,
                sleepCommitted = true, stagedSleepSegments = 4,
                mergedRecordCount = 8, historySampleCount = 10,
                rawRecordBlobBase64 = "AQID", channels = emptyList(),
            )
            val records = parseCSV(ExportEngine.historySyncEvidenceCSV(listOf(row)))
            assertEquals(2, records.size, "header + exactly one record for $ringID")
            assertEquals(10, records[1].size, "column count must survive $ringID")
            assertEquals(ringID, records[1][1], "ringID must round-trip verbatim")
            assertEquals(trigger, records[1][2], "trigger must round-trip verbatim")
            assertEquals("AQID", records[1][8], "later columns must not shift — `nightRowOutcome` is appended, not inserted")
        }
    }

    // MARK: Helpers

    /** yyyy-MM-dd in [zone] — reimplemented here so the expectation is independent of the engine's own formatter. */
    private fun localDayLabel(date: Instant): String = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT).withZone(zone).format(date)

    companion object {
        /**
         * Minimal RFC-4180 reader (quoted fields, doubled quotes, embedded newlines) used to prove the
         * writer's output is actually parseable rather than merely different. Upstream walks Swift
         * Characters, where CRLF is one; it is only ever inside quotes here, where both keep it whole.
         */
        fun parseCSV(text: String): List<List<String>> {
            val records = mutableListOf<List<String>>()
            var fields = mutableListOf<String>()
            val field = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < text.length) {
                val c = text[i]
                i++
                if (inQuotes) {
                    if (c == '"') {
                        if (i < text.length && text[i] == '"') {
                            field.append('"')
                            i++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        field.append(c)
                    }
                } else if (c == '"') {
                    inQuotes = true
                } else if (c == ',') {
                    fields.add(field.toString())
                    field.setLength(0)
                } else if (c == '\n') {
                    fields.add(field.toString())
                    field.setLength(0)
                    records.add(fields)
                    fields = mutableListOf()
                } else if (c == '\r') {
                    continue
                } else {
                    field.append(c)
                }
            }
            fields.add(field.toString())
            records.add(fields)
            return records
        }
    }
}
