package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.ExportEngine.SampleRow
import java.time.Duration
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The export's first end-to-end path: sample rows → `samplesCSV` and `toJSON` → read back. Upstream's
 * own rules (`ExportEngine.swift`: `csvField`, `plainNumber`, `samplesCSV`, `toJSON`) over Foundation
 * text measured on the pinned toolchain; the export differential compares the same writers' whole
 * files with upstream's bytes.
 */
class ExportEngineTracerTest {

    private val t0 = FoundationDate.unix(1_700_000_000.0) // 2023-11-14T22:13:20Z
    private fun row(kind: String, value: Double, offsetMinutes: Long = 0) =
        SampleRow(kind, t0.plus(Duration.ofMinutes(offsetMinutes)), t0.plus(Duration.ofMinutes(offsetMinutes + 1)), value)

    @Test
    fun `samples CSV quotes hostile kinds and prints whole values without a point and fractions as Swift does`() {
        val csv = ExportEngine.samplesCSV(
            listOf(
                row("heartRate", 72.0), row("hrvSDNN", 0.1, 1), row("spo2", 1e-5, 2), row("temperature", 1e20, 3),
                row("steps", -0.0, 4), row("weird, \"kind\"", Double.NaN, 5), row(" lead", Double.POSITIVE_INFINITY, 6),
                row("a\r\nb", 2.5, 7), row("plain/kind", -3.0, 8),
            ),
        )
        val expected = listOf(
            "kind,start,end,value",
            "heartRate,2023-11-14T22:13:20.000Z,2023-11-14T22:14:20.000Z,72",
            "hrvSDNN,2023-11-14T22:14:20.000Z,2023-11-14T22:15:20.000Z,0.1",
            "spo2,2023-11-14T22:15:20.000Z,2023-11-14T22:16:20.000Z,1e-05",
            "temperature,2023-11-14T22:16:20.000Z,2023-11-14T22:17:20.000Z,100000000000000000000",
            "steps,2023-11-14T22:17:20.000Z,2023-11-14T22:18:20.000Z,-0",
            "\"weird, \"\"kind\"\"\",2023-11-14T22:18:20.000Z,2023-11-14T22:19:20.000Z,nan",
            "\" lead\",2023-11-14T22:19:20.000Z,2023-11-14T22:20:20.000Z,inf",
            "\"a\r\nb\",2023-11-14T22:20:20.000Z,2023-11-14T22:21:20.000Z,2.5",
            "plain/kind,2023-11-14T22:21:20.000Z,2023-11-14T22:22:20.000Z,-3",
        ).joinToString("\n")
        assertEquals(expected, csv)
        assertEquals("kind,start,end,value", ExportEngine.samplesCSV(emptyList()))
    }

    @Test
    fun `the CSV field escaper quotes only what needs quoting`() {
        assertEquals("abc", ExportEngine.csvField("abc"))
        assertEquals("", ExportEngine.csvField(""))
        assertEquals("\"a,b\"", ExportEngine.csvField("a,b"))
        assertEquals("\"a\"\"b\"", ExportEngine.csvField("a\"b"))
        assertEquals("\"a\nb\"", ExportEngine.csvField("a\nb"))
        assertEquals("\"a\rb\"", ExportEngine.csvField("a\rb"))
        assertEquals("\"trail \"", ExportEngine.csvField("trail "))
        assertEquals("\" \"", ExportEngine.csvField(" "))
        assertEquals("in side", ExportEngine.csvField("in side"))
    }

    @Test
    fun `toJSON writes schema 3, the samples, every v2 section and the provenance, units and notes`() {
        val samples = listOf(row("heartRate", 72.0), row("spo2", 0.97, 1))
        val json = assertNotNull(ExportEngine.toJSON(samples = samples, zone = ZoneId.of("Asia/Kolkata"), now = t0))
        val root = ExportJsonReader.root(json)
        assertEquals(
            setOf(
                "schemaVersion", "exportedAt", "samples", "sleep", "daily", "stepSamples", "naps", "daytimeTemperatures",
                "historySyncEvidence", "provenance", "units", "notes",
            ),
            root.keys,
        )
        assertTrue(ExportJsonReader.isNumber(root["schemaVersion"]))
        assertEquals(3L, root.long("schemaVersion"))
        assertEquals(3, ExportEngine.SCHEMA_VERSION)
        // The v2 sections print UTC whatever zone is passed.
        assertEquals("2023-11-14T22:13:20.000Z", root.string("exportedAt"))
        val s = assertNotNull(root.array("samples")).map { assertNotNull(it.asObject()) }
        assertEquals(listOf("heartRate", "spo2"), s.map { it.string("kind") })
        assertEquals(listOf("2023-11-14T22:13:20.000Z", "2023-11-14T22:14:20.000Z"), s.map { it.string("start") })
        assertEquals(listOf("2023-11-14T22:14:20.000Z", "2023-11-14T22:15:20.000Z"), s.map { it.string("end") })
        assertEquals(listOf(72.0, 0.97), s.map { it.double("value") })
        for (section in listOf("sleep", "daily", "stepSamples", "naps", "daytimeTemperatures", "historySyncEvidence")) {
            assertEquals(emptyList(), root.array(section), section)
        }
        val provenance = assertNotNull(root.obj("provenance"))
        assertEquals(
            mapOf(
                "samples" to "measured", "stepSamples" to "measured", "daytimeTemperatures" to "measured", "sleep" to "derived",
                "daily" to "derived", "naps" to "derived", "historySyncEvidence" to "diagnostic",
            ),
            provenance.keys.associateWith { provenance.string(it) },
        )
        val units = assertNotNull(root.obj("units"))
        for (kind in MetricKind.entries) assertEquals(kind.unit, units.string(kind.rawValue), kind.rawValue)
        assertEquals("events/hour", units.string("odi"))
        assertEquals("events/hour", units.string("osaODI"))
        assertEquals("percent", units.string("avgSpO2"))
        assertEquals("s", units.string("timeZoneOffsetSeconds"))
        assertEquals("level (ring motion index, no physical unit)", units.string("movementLevels"))
        assertEquals(48, units.keys.size)
        val notes = assertNotNull(root.obj("notes"))
        assertEquals(
            setOf(
                "hrvSDNN", "sleepStages", "hypnogram", "hypnogramProvenance", "exportRange", "osa", "skinTemperature",
                "ringIdentity", "coverage", "referenceCoverage", "edgeProvenance", "provenanceSummary",
            ),
            notes.keys,
        )
        assertTrue(assertNotNull(notes.string("hrvSDNN")).contains("RMSSD→SDNN"))
    }

    @Test
    fun `a non-finite sample value makes toJSON return null while the CSV still writes`() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val rows = listOf(row("heartRate", 60.0), row("temperature", bad, 1))
            assertNull(ExportEngine.toJSON(samples = rows, zone = ZoneId.of("UTC"), now = t0), "toJSON with $bad")
            assertTrue(ExportEngine.samplesCSV(rows).lines().last().endsWith(if (bad.isNaN()) ",nan" else if (bad > 0) ",inf" else ",-inf"))
        }
        assertNotNull(ExportEngine.toJSON(samples = listOf(row("heartRate", 60.0)), zone = ZoneId.of("UTC"), now = t0))
    }

    @Test
    fun `a sample row compares as Swift's synthesized Equatable`() {
        assertEquals(row("k", 0.0), row("k", -0.0))
        assertEquals(row("k", 0.0).hashCode(), row("k", -0.0).hashCode())
        assertNotEquals(row("k", Double.NaN), row("k", Double.NaN))
        assertNotEquals(row("k", 1.0), row("k", 1.0, 1))
        assertNotEquals(row("k", 1.0), row("j", 1.0))
        assertFalse(row("k", 1.0).equals("k"))
    }
}
