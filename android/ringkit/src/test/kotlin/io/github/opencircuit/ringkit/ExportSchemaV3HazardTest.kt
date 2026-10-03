package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.ExportEngine.ExportMetadata
import io.github.opencircuit.ringkit.ExportEngine.OSARow
import io.github.opencircuit.ringkit.ExportEngine.SleepEdgeProvenanceRow
import io.github.opencircuit.ringkit.ExportEngine.SleepRow
import io.github.opencircuit.ringkit.ExportEngine.SleepSessionRow
import io.github.opencircuit.ringkit.ExportEngineTest.Companion.parseCSV
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Kotlin-only checks of the schema-3 core where a port could quietly differ from upstream: absence
 * versus zero in every optional block (an OSA row with no valid window, a reference-wake block with no
 * coverage, a recorded-but-empty timeline, a gap of exactly 0 s, the edited night's `recorded` block),
 * the declared zone against the printed offset, Swift `Int` text in the metadata, the hypnogram's
 * seconds, and the session provenance keys. Kept out of the upstream-port classes so their counts stay
 * exact.
 *
 * Every expected value was printed by upstream's own `ExportEngine` at the pin, built with Swift 6.3.2
 * on macOS 26, for the same inputs, with Asia/Kolkata as the process zone.
 */
class ExportSchemaV3HazardTest {

    private val t0 = FoundationDate.unix(1_700_000_000.0)
    private val t1 = FoundationDate.unix(1_700_003_600.0)
    private val night = FoundationDate.unix(1_699_920_000.0)
    private val kolkata: ZoneId = ZoneId.of("Asia/Kolkata")

    private val summary = SleepRow(
        night = night, asleepMin = 450, deepMin = 90, lightMin = 180, remMin = 120, awakeMin = 30,
        efficiency = 0.9375, inBedStart = t0, inBedEnd = t1, skinTempC = 36.5, sleepScore = 82, stressScore = 40,
    )

    private fun session(
        id: String,
        hypnogram: List<SleepSegment> = emptyList(),
        osa: OSARow? = null,
        referenceCoverage: ExportReferenceCoverage.Outcome? = null,
        edge: SleepEdgeProvenanceRow? = null,
        edited: Boolean = false,
        recordedInBedStart: Instant? = null,
        recordedInBedEnd: Instant? = null,
    ) = SleepSessionRow(
        sessionID = id, night = night, inBedStart = t0, inBedEnd = t1, isManuallyEdited = edited,
        recordedInBedStart = recordedInBedStart, recordedInBedEnd = recordedInBedEnd, hypnogram = hypnogram, summary = summary,
        osa = osa, referenceCoverage = referenceCoverage, edgeProvenance = edge,
    )

    private fun csvRow(row: SleepSessionRow): List<String> = parseCSV(ExportEngine.sleepSessionsCSV(listOf(row), kolkata))[1]

    private fun jsonSession(row: SleepSessionRow): ReplayJson.Obj {
        val text = ExportEngine.toJSON(emptyList(), emptyList(), emptyList(), zone = kolkata, now = t0, sleepSessions = listOf(row)) ?: fail("toJSON null")
        return ExportJsonReader.root(text)["sleepSessions"]?.asObjectList()?.single() ?: fail("no session")
    }

    @Test
    fun anOsaRowIsWrittenOnlyWhenItHasAValidWindowAndThenEvenAsZeros() {
        // Measured: validWindows −1 → five empty fields and no `osa` key; validWindows 1 with every
        // reading 0 → `0.00,0.00,0.0,0.00,1` and an `osa` block of zeros (a measured perfect night).
        val negative = session("a", osa = OSARow(0.0, 0.0, 0.0, 0.0, validWindows = -1))
        assertEquals(List(5) { "" }, csvRow(negative).subList(16, 21))
        assertNull(jsonSession(negative)["osa"])

        val one = session("b", osa = OSARow(0.0, 0.0, 0.0, 0.0, validWindows = 1))
        assertEquals(listOf("0.00", "0.00", "0.0", "0.00", "1"), csvRow(one).subList(16, 21))
        val osa = assertNotNull(jsonSession(one).obj("osa"))
        assertEquals(setOf("avgSpO2", "minSpO2", "odi", "timeBelow90Sec", "validWindows"), osa.keys)
        assertEquals(1L, osa.long("validWindows"))
        assertEquals(0.0, osa.double("odi"))
    }

    @Test
    fun aMeasuredZeroSecondGapIsPrintedAndAnAbsentOneIsNot() {
        // Measured: bedtime gap 0.0 → CSV `0.0` and JSON `"bedtimeGapSeconds" : 0`; the absent wake gap
        // → empty field and no key; materialGapSeconds 1800 → `1800`.
        val edge = SleepEdgeProvenanceRow(t0, t1, "resumedAfterGap", 0.0, "unknown", null, emptyList(), 1800.0)
        val row = csvRow(session("c", edge = edge))
        assertEquals(listOf("resumedAfterGap", "0.0", "unknown", "", "", "recorded"), row.subList(25, 31))
        val block = assertNotNull(jsonSession(session("c", edge = edge)).obj("edgeProvenance"))
        assertEquals(
            setOf("bedtimeGapSeconds", "bedtimeVerdict", "durationBasis", "materialGapSeconds", "reasons", "wakeVerdict", "windowEnd", "windowStart"),
            block.keys,
        )
        assertEquals("0", block["bedtimeGapSeconds"].toString())
        assertNull(block["wakeGapSeconds"])
        assertEquals(emptyList(), block["reasons"]?.asStringList())
        assertEquals("2023-11-15T04:43:20.000+05:30", block.string("windowEnd"))
    }

    @Test
    fun aReferenceWakeVerdictIsWrittenEvenWithoutACoverageBlock() {
        // Measured: `.unavailable(reason:)` with coverage nil → CSV `none,,` and a JSON block with an
        // explicit null reference and the reason, while `coverage` stays absent.
        val row = session("d", referenceCoverage = ExportReferenceCoverage.Outcome.Unavailable(ExportReferenceCoverage.Outcome.NO_MANUAL_SLEEP_SCHEDULE))
        assertEquals(listOf("", "none", "", ""), csvRow(row).subList(30, 34))
        val s = jsonSession(row)
        assertNull(s["coverage"])
        val ref = assertNotNull(s.obj("referenceCoverage"))
        assertEquals(setOf("reference", "unavailableReason"), ref.keys)
        assertEquals(ReplayJson.Null, ref["reference"])
        assertEquals("noManualSleepSchedule", ref.string("unavailableReason"))
    }

    @Test
    fun anAssertedEnvelopeOnlyNightIsARecordedTimelineWithNoStageBlocks() {
        // Measured: one asserted in-bed segment → hypnogramSegments `0`, `"hypnogram" : []`, and no
        // provenanceSummary (no asserted asleep or awake time).
        val row = session("e", hypnogram = listOf(SleepSegment(t0, t1, SleepStage.IN_BED, SleepProvenance.ASSERTED)))
        assertEquals("0", csvRow(row)[15])
        assertEquals("sessionID,start,end,stage,durationSec,provenance", ExportEngine.hypnogramCSV(listOf(row), kolkata))
        val s = jsonSession(row)
        assertEquals(0, s["hypnogram"]?.asArray()?.size)
        assertNull(s["provenanceSummary"])
    }

    @Test
    fun theRecordedBlockAppearsOnlyOnAnEditedNightThatHasOne() {
        // Measured: edited with no recorded instants → no `recorded`; unedited with recorded instants →
        // no `recorded`; edited with only a recorded end (half a millisecond past t1) → `{ inBedEnd }`,
        // printed with the millisecond carry.
        assertNull(jsonSession(session("f", edited = true))["recorded"])
        assertNull(jsonSession(session("g", recordedInBedStart = t0, recordedInBedEnd = t1))["recorded"])
        val recorded = assertNotNull(jsonSession(session("h", edited = true, recordedInBedEnd = FoundationDate.unix(1_700_003_600.0005))).obj("recorded"))
        assertEquals(setOf("inBedEnd"), recorded.keys)
        assertEquals("2023-11-15T04:43:20.001+05:30", recorded.string("inBedEnd"))
    }

    @Test
    fun theDeclaredOffsetIsThePrintedOneEvenHalfAMillisecondBeforeADstChange() {
        // Measured (Europe/Amsterdam, 2024-03-31 01:00Z): Foundation prints 0.5 ms before the change as
        // `03:00:00.000+02:00` — the millisecond rounds onto the change — while the zone's offset AT that
        // instant is still 3600. 1 ms before prints `01:59:59.999+01:00`.
        val amsterdam = ZoneId.of("Europe/Amsterdam")
        for ((unix, printed, offset) in listOf(
            Triple(1_711_846_799.9995, "2024-03-31T03:00:00.000+02:00", 7_200L),
            Triple(1_711_846_799.999, "2024-03-31T01:59:59.999+01:00", 3_600L),
            Triple(1_711_846_800.0, "2024-03-31T03:00:00.000+02:00", 7_200L),
        )) {
            val at = FoundationDate.unix(unix)
            val meta = ExportMetadata.of(amsterdam, exportedAt = at, rangeStart = at, rangeEnd = at)
            assertEquals(offset, meta.timeZoneOffsetSeconds, "declared offset for $unix")
            assertEquals("Europe/Amsterdam", meta.timeZoneIdentifier)
            val fields = parseCSV(ExportEngine.metadataCSV(meta, amsterdam)).drop(1).associate { it[0] to it[1] }
            assertEquals(printed, fields["exportedAt"])
            assertEquals(offset.toString(), fields["timeZoneOffsetSeconds"])
        }
    }

    @Test
    fun metadataPrintsSwiftIntsAndQuotesEdgeSpacesInThePassedZone() {
        // Measured with Kolkata as the process zone and a block declaring St John's: the times print in
        // the PROCESS zone (the declared fields are just text), Int.max and a negative offset print as
        // decimal integers, empty fields stay empty, an edge-spaced value is quoted.
        val meta = ExportMetadata(
            schemaVersion = Long.MAX_VALUE, exportedAt = t0, rangeStart = t0, rangeEnd = t1, deviceModel = " x ",
            timeZoneIdentifier = "America/St_Johns", timeZoneOffsetSeconds = -12_600,
        )
        val lines = ExportEngine.metadataCSV(meta, kolkata).split("\n")
        assertEquals(
            listOf(
                "field,value", "schemaVersion,9223372036854775807", "exportedAt,2023-11-15T03:43:20.000+05:30",
                "rangeStart,2023-11-15T03:43:20.000+05:30", "rangeEnd,2023-11-15T04:43:20.000+05:30", "appVersion,", "appBuild,",
                "deviceModel,\" x \"", "osVersion,", "ringModel,", "ringFirmware,", "ringGeneration,", "ringIdentifier,",
                "timeZoneIdentifier,America/St_Johns", "timeZoneOffsetSeconds,-12600",
            ),
            lines.dropLast(1),
        )
        assertTrue(lines.last().startsWith("timestampPolicy,\"Timestamps in the schema-2 sections (samples, sleep,"))
    }

    @Test
    fun aMillisecondStampedSegmentsSecondsAreTheDifferenceOfItsDateDoubles() {
        // Measured: `SleepSegment(start: …003000.913, end: …020000.207).duration` = 16999.293999910355
        // (the exact duration is 16999.294), printed by `String(Double)` in the CSV and with 17
        // significant digits in the JSON.
        val seg = SleepSegment(FoundationDate.unix(1_755_003_000.913), FoundationDate.unix(1_755_020_000.207), SleepStage.ASLEEP_CORE)
        val row = session("i", hypnogram = listOf(seg))
        assertEquals("16999.293999910355", parseCSV(ExportEngine.hypnogramCSV(listOf(row), kolkata))[1][4])
        val back = jsonSession(row)["hypnogram"]?.asObjectList()?.single()?.get("durationSec")
        assertEquals("16999.293999910355", back.toString())
    }

    @Test
    fun theSessionSectionsAreClassifiedOnlyWhenSessionsAreWritten() {
        // Measured: with sessions the provenance map gains these seven keys; without, none of them.
        val sessionKeys = mapOf(
            "sleepSessions" to "derived", "sleepSessions.summary" to "derived", "sleepSessions.osa" to "derived",
            "sleepSessions.hypnogram" to "derived", "sleepSessions.coverage" to "measured",
            "sleepSessions.referenceCoverage" to "derived", "sleepSessions.edgeProvenance" to "derived",
        )
        fun provenance(sessions: List<SleepSessionRow>): Map<String, String?> {
            val text = ExportEngine.toJSON(emptyList(), emptyList(), emptyList(), zone = kolkata, now = t0, sleepSessions = sessions) ?: fail("toJSON null")
            val p = ExportJsonReader.root(text).obj("provenance") ?: fail("no provenance")
            return p.keys.associateWith { p.string(it) }
        }
        val with = provenance(listOf(session("j")))
        assertEquals(sessionKeys, with.filterKeys { it.startsWith("sleepSessions") })
        assertEquals(14, with.size)
        assertFalse(provenance(emptyList()).keys.any { it.startsWith("sleepSessions") })
    }

    @Test
    fun theEdgeRowKeepsBothConstructorsAndSwiftsEquality() {
        // The E3 row is consumed unchanged: its absence-is-not-zero gaps compare as Swift's optionals.
        val assessment = SleepConfidence.assess(
            5.0 * 3600, 6.0 * 3600,
            SleepConfidence.Coverage(t0, t1, t0.minusSeconds(100), t1.plusSeconds(4 * 3600), emptyList(), t0.minusSeconds(7 * 86_400)),
        )
        val built = SleepEdgeProvenanceRow(t0, t1, assessment)
        val direct = SleepEdgeProvenanceRow(t0, t1, "witnessed", null, "stoppedThenResumed", 14_400.0, listOf("noRecordingAfterWake"), WakeProvenance.MATERIAL_GAP_SECONDS)
        assertEquals(direct, built)
        assertEquals(direct.hashCode(), built.hashCode())
        assertNotEquals(direct, SleepEdgeProvenanceRow(t0, t1, "witnessed", 0.0, "stoppedThenResumed", 14_400.0, listOf("noRecordingAfterWake"), WakeProvenance.MATERIAL_GAP_SECONDS))
        assertEquals(SleepEdgeProvenanceRow.DURATION_BASIS_RECORDED, built.durationBasis)
    }
}
