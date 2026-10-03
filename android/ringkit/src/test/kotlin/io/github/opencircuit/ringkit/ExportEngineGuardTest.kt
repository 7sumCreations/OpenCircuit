package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.ExportEngine.DailyRow
import io.github.opencircuit.ringkit.ExportEngine.DaytimeTemperatureRow
import io.github.opencircuit.ringkit.ExportEngine.EpochArchiveRow
import io.github.opencircuit.ringkit.ExportEngine.HistorySyncEvidenceRow
import io.github.opencircuit.ringkit.ExportEngine.NapRow
import io.github.opencircuit.ringkit.ExportEngine.SampleRow
import io.github.opencircuit.ringkit.ExportEngine.SleepRow
import io.github.opencircuit.ringkit.ExportEngine.StepSampleRow
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What Swift's types and Foundation's fixed formats guaranteed the export for free, checked on the
 * Kotlin side: no writer reads the machine's locale or time zone, and the rows are values (Swift's
 * synthesized `Equatable`, arrays copied on assignment). Kept apart from the upstream-port class.
 */
class ExportEngineGuardTest {

    private val t0 = FoundationDate.unix(1_700_000_000.0) // 2023-11-14T22:13:20Z
    private val t1 = FoundationDate.unix(1_700_003_600.0)
    private val night = FoundationDate.unix(1_699_920_000.0) // 2023-11-14T00:00:00Z

    private fun sleep(efficiency: Double = 0.9375, skinTempC: Double = 36.5, levels: List<Long> = listOf(0, 1, 2)) = SleepRow(
        night = night, asleepMin = 450, deepMin = 90, lightMin = 180, remMin = 120, awakeMin = 30, efficiency = efficiency,
        inBedStart = t0, inBedEnd = t1, skinTempC = skinTempC, sleepScore = 82, stressScore = 40, feelScore = 7,
        hrDeep = 55, hrLight = 60, hrRem = 64, hrAwake = 68, movementLevels = levels,
    )

    private fun trace(): HistoryChannelTrace {
        val t = HistoryChannelTrace("sleep", 0x00, t0)
        t.finishedAt = t1
        t.sawSyncAck = true
        t.syncAckFlag = 0xAB
        t.page4CCount = 1234
        t.endMarkerCount = 1
        t.recordsAtStart = 2
        t.recordsAtEnd = 1_000_008
        t.firstOpcode = 0x82
        t.lastOpcode = 0x50
        t.exitReason = HistoryChannelExitReason.END_MARKER
        return t
    }

    private fun evidence(channels: List<HistoryChannelTrace> = listOf(trace())) = HistorySyncEvidenceRow(
        capturedAt = t0, ringID = "ring-1", trigger = "manual", sleepCommitted = true, stagedSleepSegments = 1_234_567,
        mergedRecordCount = -8, historySampleCount = 10, rawRecordBlobBase64 = "AQID", channels = channels, nightRowOutcome = "updated",
    )

    /** Every schema-2 writer's text for rows whose numbers have digits a localized formatter would change. */
    private fun texts(zone: ZoneId): List<String> {
        val sleepRows = listOf(sleep(), sleep(efficiency = 0.123456, skinTempC = -1234.5, levels = listOf(-7, 1_000_000)))
        val daily = listOf(DailyRow(night, 1_234_567))
        val steps = listOf(StepSampleRow(t0, t1, -9_876))
        val naps = listOf(NapRow(t0, t1, 95, true))
        val temps = listOf(DaytimeTemperatureRow(t0, 34.2), DaytimeTemperatureRow(t1, -0.001))
        val samples = listOf(SampleRow("heartRate", t0, t1, 72.5), SampleRow("steps", t0, t1, 1_234_567.0))
        val ev = listOf(evidence())
        return listOf(
            ExportEngine.samplesCSV(samples),
            ExportEngine.sleepCSV(sleepRows, zone),
            ExportEngine.dailyCSV(daily, zone),
            ExportEngine.stepSamplesCSV(steps),
            ExportEngine.napsCSV(naps),
            ExportEngine.daytimeTemperatureCSV(temps),
            ExportEngine.historySyncEvidenceCSV(ev),
            ExportEngine.sessionID(night, zone),
            ExportEngine.dayStamp(night, zone),
            ExportEngine.toJSON(samples, sleepRows, daily, steps, naps, temps, ev, zone = zone, now = t1)!!,
        )
    }

    @Test
    fun everySchemaTwoWriterPrintsTheSameAsciiTextUnderEveryMachineLocaleAndZone() {
        // Foundation's formats here are fixed (en_US_POSIX dates, %f, interpolation): the machine's
        // language never reaches them. Kotlin's String.format and DateTimeFormatter would read it, so
        // the same text must come out under Arabic, Devanagari, Thai and Persian digits and Turkish
        // case rules — and under a machine zone that is neither the one passed nor UTC.
        val savedLocale = Locale.getDefault()
        val savedZone = TimeZone.getDefault()
        val zone = ZoneId.of("America/St_Johns")
        try {
            Locale.setDefault(Locale.ROOT)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val reference = texts(zone)
            // ASCII throughout, except the JSON's verbatim notes (em dashes, arrows): its digits are checked below.
            for (t in reference.dropLast(1)) assertTrue(t.all { it.code < 0x80 }, "ASCII: $t")
            assertTrue(reference.last().replace(Regex("\"notes\" : \\{[^}]*\\}"), "").all { it.code < 0x80 }, "ASCII outside the notes")
            assertEquals("day,steps\n2023-11-13,1234567", reference[2])
            for (tag in listOf("ar-EG-u-nu-arab", "hi-IN-u-nu-deva", "th-TH-u-nu-thai", "fa-IR", "tr-TR")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
                assertEquals(reference, texts(zone), tag)
            }
        } finally {
            Locale.setDefault(savedLocale)
            TimeZone.setDefault(savedZone)
        }
    }

    @Test
    fun rowsCompareAsSwiftsSynthesizedEquatable() {
        // Doubles by IEEE ==: -0.0 equals 0.0 (and hashes alike), NaN equals nothing, itself included.
        assertEquals(sleep(efficiency = 0.0), sleep(efficiency = -0.0))
        assertEquals(sleep(efficiency = 0.0).hashCode(), sleep(efficiency = -0.0).hashCode())
        assertEquals(sleep(skinTempC = 0.0), sleep(skinTempC = -0.0))
        assertNotEquals(sleep(efficiency = Double.NaN), sleep(efficiency = Double.NaN))
        assertNotEquals(sleep(levels = listOf(0, 1)), sleep(levels = listOf(1, 0)))
        assertEquals(DaytimeTemperatureRow(t0, 0.0), DaytimeTemperatureRow(t0, -0.0))
        assertEquals(DaytimeTemperatureRow(t0, 0.0).hashCode(), DaytimeTemperatureRow(t0, -0.0).hashCode())
        assertNotEquals(DaytimeTemperatureRow(t0, Double.NaN), DaytimeTemperatureRow(t0, Double.NaN))
        assertEquals(DailyRow(night, 1), DailyRow(night, 1))
        assertNotEquals(DailyRow(night, 1), DailyRow(t0, 1))
        assertEquals(StepSampleRow(t0, t1, 3), StepSampleRow(t0, t1, 3))
        assertNotEquals(NapRow(t0, t1, 3, true), NapRow(t0, t1, 3, false))
        // Two traces with the same content are equal; the row compares its traces by content.
        assertEquals(evidence(), evidence())
        assertEquals(evidence().hashCode(), evidence().hashCode())
        val other = trace()
        other.page47Count = 1
        assertNotEquals(evidence(), evidence(listOf(other)))
        assertNotEquals(evidence(), evidence(emptyList()))
    }

    @Test
    fun theEpochArchiveRowHoldsItsOwnReadOnlyCopyOfTheCoverageReport() {
        val missing = mutableListOf(5L, 6L)
        val report = ArchiveEvidenceCoverage.Report(archiveRecordCount = 10, evidenceRecordCount = 8, missingFromEvidence = missing, longestMissingRunSeconds = 300)
        val row = EpochArchiveRow("ring-1", "AQID", 10, t0, t1, report)
        missing += 7L
        assertEquals(listOf(5L, 6L), row.coverage.missingFromEvidence)
        assertFalse(row.coverage.isComplete)
        @Suppress("UNCHECKED_CAST")
        assertFailsWith<UnsupportedOperationException> { (row.coverage.missingFromEvidence as MutableList<Long>).clear() }
        assertEquals(EpochArchiveRow("ring-1", "AQID", 10, t0, t1, report.copy(missingFromEvidence = listOf(5L, 6L))), row)
        assertNotEquals(EpochArchiveRow("ring-1", "AQID", 10, t0, null, report), row)
    }

    @Test
    fun theNightAndDayLabelsMoveWithTheZoneWhileEveryOtherSchemaTwoTimeStaysUtc() {
        val east = ZoneId.of("Pacific/Kiritimati")
        val west = ZoneId.of("Pacific/Pago_Pago")
        fun root(zone: ZoneId) = ExportJsonReader.root(ExportEngine.toJSON(emptyList(), listOf(sleep()), listOf(DailyRow(night, 1)), zone = zone, now = t1)!!)
        val a = root(east)
        val b = root(west)
        val sa = a["sleep"]!!.asObjectList()!!.single()
        val sb = b["sleep"]!!.asObjectList()!!.single()
        assertEquals("2023-11-14", sa.string("night"))
        assertEquals("2023-11-13", sb.string("night"))
        assertEquals("2023-11-13", b["daily"]!!.asObjectList()!!.single().string("day"))
        assertEquals("2023-11-14T22:13:20.000Z", sb.string("inBedStart"))
        assertEquals(sa.string("inBedStart"), sb.string("inBedStart"))
        assertEquals(sa.string("inBedEnd"), sb.string("inBedEnd"))
        assertEquals(a.string("exportedAt"), b.string("exportedAt"))
        val csvEast = ExportEngine.sleepCSV(listOf(sleep()), east).split("\n")[1].split(",")
        val csvWest = ExportEngine.sleepCSV(listOf(sleep()), west).split("\n")[1].split(",")
        assertEquals(listOf("2023-11-14", "2023-11-13"), listOf(csvEast[0], csvWest[0]))
        assertEquals(csvEast.drop(1), csvWest.drop(1), "only the night label depends on the zone")
    }
}
