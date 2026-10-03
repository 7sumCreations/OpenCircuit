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
 * Also the coverage-honesty sources the export carries: the reference-wake raw names and reason
 * tokens, `Outcome` / `Row` equality, and `ExportCoverageWitness.Edges` as a value.
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

    // --- the coverage-honesty sources (reference-wake coverage, coverage witness) ---

    private fun assessment(fraction: Double = 0.5) =
        ExportCoverage.Assessment(t0, t1, expectedSamples = 24, observedSamples = 12, coverageFraction = fraction, gaps = emptyList(), longestGapSeconds = 0.0)

    private fun row(beyond: Double = 0.0, reference: ExportReferenceCoverage.Reference = ExportReferenceCoverage.Reference.MANUAL_SCHEDULE_WAKE) =
        ExportReferenceCoverage.Row(reference, t1, beyond, assessment())

    @Test
    fun referenceRawNamesAndReasonTokensArePinnedAndParsedExactly() {
        assertEquals(
            listOf("manualScheduleWake", "manualScheduleWakeSoFar"),
            ExportReferenceCoverage.Reference.entries.map { it.rawValue },
        )
        assertEquals(ExportReferenceCoverage.Reference.MANUAL_SCHEDULE_WAKE_SO_FAR, ExportReferenceCoverage.Reference.fromRawValue("manualScheduleWakeSoFar"))
        // Swift's `init(rawValue:)` is exact (measured: "ManualScheduleWake" → nil), whatever the machine's case rules.
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            for (raw in listOf("ManualScheduleWake", "MANUALSCHEDULEWAKE", " manualScheduleWake", "manualScheduleWake ", "MANUAL_SCHEDULE_WAKE", "")) {
                assertEquals(null, ExportReferenceCoverage.Reference.fromRawValue(raw), "'$raw'")
            }
        } finally {
            Locale.setDefault(saved)
        }
        assertEquals("noManualSleepSchedule", ExportReferenceCoverage.Outcome.NO_MANUAL_SLEEP_SCHEDULE)
        assertEquals("referenceNotAfterBedtime", ExportReferenceCoverage.Outcome.REFERENCE_NOT_AFTER_BEDTIME)
    }

    @Test
    fun outcomesAndRowsCompareAsSwiftsSynthesizedEquatable() {
        val unavailable = ExportReferenceCoverage.Outcome.Unavailable(ExportReferenceCoverage.Outcome.NO_MANUAL_SLEEP_SCHEDULE)
        assertEquals(unavailable, ExportReferenceCoverage.Outcome.Unavailable("noManualSleepSchedule"))
        assertNotEquals<ExportReferenceCoverage.Outcome>(unavailable, ExportReferenceCoverage.Outcome.Unavailable("NoManualSleepSchedule"), "reasons compare exactly")
        assertNotEquals<ExportReferenceCoverage.Outcome>(unavailable, ExportReferenceCoverage.Outcome.Measured(row()))
        // Doubles by IEEE ==: -0.0 equals 0.0 (and hashes alike), NaN equals nothing, itself included.
        assertEquals(ExportReferenceCoverage.Outcome.Measured(row(0.0)), ExportReferenceCoverage.Outcome.Measured(row(-0.0)))
        assertEquals(row(0.0).hashCode(), row(-0.0).hashCode())
        assertNotEquals(row(Double.NaN), row(Double.NaN))
        assertNotEquals(row(), row(reference = ExportReferenceCoverage.Reference.MANUAL_SCHEDULE_WAKE_SO_FAR))
        assertNotEquals(row(), ExportReferenceCoverage.Row(ExportReferenceCoverage.Reference.MANUAL_SCHEDULE_WAKE, t1, 0.0, assessment(0.25)))
    }

    @Test
    fun edgesAreValuesHoldingTheirOwnReadOnlyRun() {
        val run = mutableListOf(t1, t1.plusSeconds(150))
        val edges = ExportCoverageWitness.Edges(t0, t1, t0.minusSeconds(150), t1, run, t0.minusSeconds(86_400), 3, true)
        run += t1.plusSeconds(300)
        assertEquals(listOf(t1, t1.plusSeconds(150)), edges.measurementsAfterEnd, "a later change to the caller's list does not reach the edges")
        assertEquals(listOf(t1, t1.plusSeconds(150)), edges.coverage.measurementsAfterEnd)
        @Suppress("UNCHECKED_CAST")
        assertFailsWith<UnsupportedOperationException> { (edges.measurementsAfterEnd as MutableList<java.time.Instant>).clear() }

        val same = ExportCoverageWitness.Edges(t0, t1, t0.minusSeconds(150), t1, listOf(t1, t1.plusSeconds(150)), t0.minusSeconds(86_400), 3, true)
        assertEquals(same, edges)
        assertEquals(same.hashCode(), edges.hashCode())
        assertNotEquals(ExportCoverageWitness.Edges(t0, t1, t0.minusSeconds(150), t1, listOf(t1), t0.minusSeconds(86_400), 3, true), edges)
        assertNotEquals(ExportCoverageWitness.Edges(t0, t1, t0.minusSeconds(150), t1, listOf(t1, t1.plusSeconds(150)), t0.minusSeconds(86_400), 3, false), edges)
        assertNotEquals(ExportCoverageWitness.Edges(t0, t1, t0.minusSeconds(150), t1, listOf(t1, t1.plusSeconds(150)), t0.minusSeconds(86_400), 4, true), edges)
        // Two probes of the same records are the same value.
        val archive = listOf(listOf(record(t0.minusSeconds(150)), record(t1.plusSeconds(150))))
        assertEquals(
            ExportCoverageWitness.edges(archive, null, null, null, t0, t1),
            ExportCoverageWitness.edges(archive.map { it.toList() }, null, null, null, t0, t1),
        )
    }

    @Test
    fun theWitnessDescriptionPrintsAsciiDigitsUnderEveryMachineLocale() {
        val edges = ExportCoverageWitness.Edges(t0, t1, null, null, emptyList(), null, 1_234_567, true)
        val saved = Locale.getDefault()
        try {
            for (tag in listOf("ar-EG-u-nu-arab", "hi-IN-u-nu-deva", "th-TH-u-nu-thai", "fa-IR", "tr-TR")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals("store+archive(1234567,moved)", edges.witnessDescription, tag)
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    /** A worn epoch with a heart rate, on the raw path. */
    private fun record(at: java.time.Instant): BulkRecord {
        val counter = at.epochSecond - Command.SYNC_EPOCH
        val raw = ByteArray(BulkRecord.LENGTH)
        raw[0] = (counter ushr 24).toByte(); raw[1] = (counter ushr 16).toByte()
        raw[2] = (counter ushr 8).toByte(); raw[3] = counter.toByte()
        raw[4] = 58; raw[8] = 0x60; raw[9] = 0x0a
        for (i in 10 until 15) raw[i] = 2
        return BulkRecord.of(raw)!!
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

    // --- schema-3 core ---

    private fun meta(zone: ZoneId, at: java.time.Instant = t0) =
        ExportEngine.ExportMetadata.of(zone, exportedAt = at, rangeStart = night, rangeEnd = t1, appVersion = "1.0", appBuild = "1234567", deviceModel = "iPhone15,2")

    private fun sessions(zone: ZoneId): List<ExportEngine.SleepSessionRow> {
        val seg = { a: Long, b: Long, st: SleepStage, p: SleepProvenance -> SleepSegment(t0.plusSeconds(a), t0.plusSeconds(b), st, p) }
        val coverage = ExportCoverage.assess((0L until 20L).filter { it !in 5L..9L }.map { t0.plusMillis(it * 150_250) }, t0, t0.plusSeconds(3_600))
        val reference = ExportReferenceCoverage.assess(listOf(t0.plusSeconds(60)), t0, t0.plusSeconds(3_000), t1.plusMillis(1_234), ExportReferenceCoverage.Reference.MANUAL_SCHEDULE_WAKE)!!
        val edge = ExportEngine.SleepEdgeProvenanceRow(t0, t1, "resumedAfterGap", 1_234.5, "stoppedThenResumed", 14_400.25, listOf("noRecordingAfterWake"), 3_600.0)
        return listOf(
            ExportEngine.SleepSessionRow(
                sessionID = ExportEngine.sessionID(night, zone), night = night, inBedStart = t0, inBedEnd = t1, sleepOnset = t0.plusSeconds(600),
                sleepWake = t1, isManuallyEdited = true, recordedInBedEnd = t1.minusSeconds(60),
                hypnogram = listOf(
                    seg(0, 3_600, SleepStage.IN_BED, SleepProvenance.MEASURED), seg(0, 1_234, SleepStage.ASLEEP_CORE, SleepProvenance.MEASURED),
                    seg(1_234, 3_600, SleepStage.ASLEEP_DEEP, SleepProvenance.ASSERTED),
                ),
                summary = sleep(efficiency = 0.123456), osa = ExportEngine.OSARow(95.4, 88.0, 1_312.5, 14.25, 1_234_567),
                coverage = coverage, referenceCoverage = ExportReferenceCoverage.Outcome.Measured(reference), edgeProvenance = edge,
            ),
            ExportEngine.SleepSessionRow(sessionID = "night-x", night = t1, summary = sleep(), referenceCoverage = ExportReferenceCoverage.Outcome.Unavailable("noManualSleepSchedule")),
        )
    }

    /** Every schema-3 writer's text, for rows whose numbers have digits a localized formatter would change. */
    private fun schemaThreeTexts(zone: ZoneId): List<String> = listOf(
        ExportEngine.metadataCSV(meta(zone), zone),
        ExportEngine.sleepSessionsCSV(sessions(zone), zone),
        ExportEngine.hypnogramCSV(sessions(zone), zone),
        ExportEngine.toJSON(emptyList(), emptyList(), emptyList(), zone = zone, now = t1, metadata = meta(zone), sleepSessions = sessions(zone))!!,
    )

    @Test
    fun everySchemaThreeWriterPrintsTheSameAsciiTextUnderEveryMachineLocaleAndZone() {
        val savedLocale = Locale.getDefault()
        val savedZone = TimeZone.getDefault()
        val zone = ZoneId.of("America/St_Johns")
        try {
            Locale.setDefault(Locale.ROOT)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val reference = schemaThreeTexts(zone)
            for (t in reference.dropLast(1)) assertTrue(t.all { it.code < 0x80 }, "ASCII: $t")
            assertTrue(reference.last().replace(Regex("\"notes\" : \\{[^}]*\\}"), "").all { it.code < 0x80 }, "ASCII outside the notes")
            assertTrue(reference[1].contains(",95.40,88.00,1312.5,14.25,1234567,"), reference[1])
            assertTrue(reference[0].contains("timeZoneOffsetSeconds,-12600"), reference[0])
            for (tag in listOf("ar-EG-u-nu-arab", "hi-IN-u-nu-deva", "th-TH-u-nu-thai", "fa-IR", "tr-TR")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
                assertEquals(reference, schemaThreeTexts(zone), tag)
            }
        } finally {
            Locale.setDefault(savedLocale)
            TimeZone.setDefault(savedZone)
        }
    }

    @Test
    fun theMetadataCsvAndJsonAreOneOrderedFieldList() {
        // Upstream's field order, which the CSV keeps (the JSON object is written with sorted keys).
        val order = listOf(
            "schemaVersion", "exportedAt", "rangeStart", "rangeEnd", "appVersion", "appBuild", "deviceModel", "osVersion",
            "ringModel", "ringFirmware", "ringGeneration", "ringIdentifier", "timeZoneIdentifier", "timeZoneOffsetSeconds", "timestampPolicy",
        )
        val zone = ZoneId.of("Asia/Kolkata")
        val rows = ExportEngineTest.parseCSV(ExportEngine.metadataCSV(meta(zone), zone)).drop(1)
        assertEquals(order, rows.map { it[0] })
        val json = ExportJsonReader.root(ExportEngine.toJSON(emptyList(), emptyList(), emptyList(), zone = zone, now = t1, metadata = meta(zone))!!).obj("meta")!!
        assertEquals(order.toSet(), json.keys)
        for ((key, value) in rows) assertEquals(value, json[key].let { it?.asString() ?: it.toString() }, key)
    }

    @Test
    fun ofDeclaresThePrintedOffsetAcrossEveryTransition() {
        // At every 2024 transition of these zones (a 30-minute DST in Lord Howe), and a hair either side
        // of it, the offset `of` declares is the one `meta.exportedAt` is printed with.
        fun printedOffset(text: String): Long {
            if (text.endsWith("Z")) return 0
            val s = text.takeLast(6)
            val sign = if (s[0] == '-') -1 else 1
            return sign * (s.substring(1, 3).toLong() * 3600 + s.substring(4, 6).toLong() * 60)
        }
        var checked = 0
        for (id in listOf("Europe/Amsterdam", "America/St_Johns", "Australia/Lord_Howe", "Asia/Kolkata", "Pacific/Kiritimati", "America/New_York", "Europe/London")) {
            val zone = ZoneId.of(id)
            var at = java.time.Instant.parse("2024-01-01T00:00:00Z")
            val instants = mutableListOf(at)
            while (true) {
                val tr = zone.rules.nextTransition(at) ?: break
                if (tr.instant.isAfter(java.time.Instant.parse("2025-01-01T00:00:00Z"))) break
                for (nanos in listOf(-1_000_000_000L, -600_000L, -500_000L, -400_000L, 0L, 400_000L, 500_000L, 1_000_000_000L)) instants += tr.instant.plusNanos(nanos)
                at = tr.instant
            }
            for (t in instants) {
                val m = meta(zone, t)
                val fields = ExportEngineTest.parseCSV(ExportEngine.metadataCSV(m, zone)).drop(1).associate { it[0] to it[1] }
                assertEquals(printedOffset(fields.getValue("exportedAt")), m.timeZoneOffsetSeconds, "$id at $t: ${fields["exportedAt"]}")
                assertEquals(id, m.timeZoneIdentifier)
                checked++
            }
        }
        assertEquals(7 + 5 * 2 * 8, checked, "instants checked (five of the zones change twice in 2024)")
    }

    /**
     * One builder per place a caller-supplied double reaches the JSON, each taking the value to put
     * there. The hypnogram's `durationSec` and the provenance summary are computed from instants and
     * cannot be non-finite; `meta`, `historySyncEvidence` and `epochArchive` carry no double at all.
     */
    private val doubleSites: List<Pair<String, (Double) -> String?>> = run {
        val zone = ZoneId.of("Asia/Kolkata")
        fun cov(fraction: Double = 0.5, longest: Double = 0.0) =
            ExportCoverage.Assessment(t0, t1, expectedSamples = 24, observedSamples = 12, coverageFraction = fraction, gaps = emptyList(), longestGapSeconds = longest)
        fun ref(beyond: Double = 0.0, a: ExportCoverage.Assessment = cov()) =
            ExportReferenceCoverage.Outcome.Measured(ExportReferenceCoverage.Row(ExportReferenceCoverage.Reference.MANUAL_SCHEDULE_WAKE, t1, beyond, a))
        fun edge(bed: Double? = 1.0, wake: Double? = 1.0, material: Double = 3_600.0) =
            ExportEngine.SleepEdgeProvenanceRow(t0, t1, "resumedAfterGap", bed, "stoppedThenResumed", wake, emptyList(), material)
        fun osa(a: Double = 95.0, m: Double = 88.0, t: Double = 1.0, o: Double = 2.0) = ExportEngine.OSARow(a, m, t, o, validWindows = 3)
        fun session(summary: SleepRow = sleep(), osa: ExportEngine.OSARow? = osa(), cov: ExportCoverage.Assessment? = cov(),
                    ref: ExportReferenceCoverage.Outcome? = ref(), edge: ExportEngine.SleepEdgeProvenanceRow? = edge()) =
            ExportEngine.SleepSessionRow(
                sessionID = "s", night = night, inBedStart = t0, inBedEnd = t1, summary = summary, osa = osa, coverage = cov,
                referenceCoverage = ref, edgeProvenance = edge,
            )
        fun json(samples: List<SampleRow> = emptyList(), sleepRows: List<SleepRow> = emptyList(), temps: List<DaytimeTemperatureRow> = emptyList(),
                 sessions: List<ExportEngine.SleepSessionRow> = listOf(session())) =
            ExportEngine.toJSON(samples, sleepRows, emptyList(), daytimeTemperatures = temps, zone = zone, now = t0, sleepSessions = sessions)
        listOf(
            "samples.value" to { x -> json(samples = listOf(SampleRow("heartRate", t0, t1, 60.0), SampleRow("heartRate", t0, t1, x))) },
            "sleep.efficiency" to { x -> json(sleepRows = listOf(sleep(efficiency = x))) },
            "sleep.skinTempC" to { x -> json(sleepRows = listOf(sleep(skinTempC = x))) },
            "daytimeTemperatures.celsius" to { x -> json(temps = listOf(DaytimeTemperatureRow(t0, x))) },
            "sleepSessions.summary.efficiency" to { x -> json(sessions = listOf(session(summary = sleep(efficiency = x)))) },
            "sleepSessions.summary.skinTempC" to { x -> json(sessions = listOf(session(summary = sleep(skinTempC = x)))) },
            "sleepSessions.osa.avgSpO2" to { x -> json(sessions = listOf(session(osa = osa(a = x)))) },
            "sleepSessions.osa.minSpO2" to { x -> json(sessions = listOf(session(osa = osa(m = x)))) },
            "sleepSessions.osa.timeBelow90Sec" to { x -> json(sessions = listOf(session(osa = osa(t = x)))) },
            "sleepSessions.osa.odi" to { x -> json(sessions = listOf(session(osa = osa(o = x)))) },
            "sleepSessions.coverage.coverageFraction" to { x -> json(sessions = listOf(session(cov = cov(fraction = x), ref = null))) },
            "sleepSessions.coverage.longestGapSeconds" to { x -> json(sessions = listOf(session(cov = cov(longest = x), ref = null))) },
            "sleepSessions.referenceCoverage.beyondReportedEndSeconds" to { x -> json(sessions = listOf(session(ref = ref(beyond = x)))) },
            "sleepSessions.referenceCoverage.coverageToReference" to { x -> json(sessions = listOf(session(ref = ref(a = cov(fraction = x))))) },
            "sleepSessions.referenceCoverage.longestGapSeconds" to { x -> json(sessions = listOf(session(ref = ref(a = cov(longest = x))))) },
            "sleepSessions.edgeProvenance.bedtimeGapSeconds" to { x -> json(sessions = listOf(session(edge = edge(bed = x)))) },
            "sleepSessions.edgeProvenance.wakeGapSeconds" to { x -> json(sessions = listOf(session(edge = edge(wake = x)))) },
            "sleepSessions.edgeProvenance.materialGapSeconds" to { x -> json(sessions = listOf(session(edge = edge(material = x)))) },
        )
    }

    @Test
    fun aNonFiniteNumberInAnySectionMakesToJsonReturnNullAndNeverThrow() {
        // Upstream hands JSONSerialization a NaN or an infinity wherever the caller put one, and it raises
        // an uncaught exception there — the app dies (measured). The port returns null (PORTING.md D-132):
        // checked at every place a caller's double reaches the tree, each with a finite control that writes.
        val bad = listOf(Double.NaN, -Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, java.lang.Double.longBitsToDouble(0x7ff4_0000_0000_0000L))
        for ((site, write) in doubleSites) {
            val control = write(0.25)
            assertTrue(control != null && ExportJsonReader.root(control).has("schemaVersion"), "$site: the finite control must write")
            for (x in bad) assertEquals(null, write(x), "$site = $x")
        }
        assertEquals(18, doubleSites.size, "every caller-supplied double in the tree")
    }

    // --- schema 3 whole: the honesty blocks as CSV and the epoch archive ---

    private fun archives() = listOf(
        EpochArchiveRow("ring-1", "AQID", 1_234_567, t0, t1, ArchiveEvidenceCoverage.Report(1_234_567, 1_234_000, (1L..567L).toList(), 85_050)),
        EpochArchiveRow("ring-2", "", 0, null, null, ArchiveEvidenceCoverage.Report(0, 0, emptyList(), 0)),
    )

    /** The full export: every section, sessions and archives included. */
    private fun everything(zone: ZoneId): String = ExportEngine.toJSON(
        listOf(SampleRow("heartRate", t0, t1, 72.5)), listOf(sleep()), listOf(DailyRow(night, 1_234_567)),
        listOf(StepSampleRow(t0, t1, 12)), listOf(NapRow(t0, t1, 95, true)), listOf(DaytimeTemperatureRow(t0, 34.2)), listOf(evidence()),
        zone = zone, now = t1, metadata = meta(zone), sleepSessions = sessions(zone), epochArchives = archives(),
    )!!

    @Test
    fun theHonestyCsvsAndTheArchivePrintTheSameUnderEveryMachineLocaleAndZone() {
        val savedLocale = Locale.getDefault()
        val savedZone = TimeZone.getDefault()
        val zone = ZoneId.of("America/St_Johns")
        fun texts() = listOf(
            ExportEngine.provenanceCSV(includesSleepSessions = true), ExportEngine.provenanceCSV(includesSleepSessions = false),
            ExportEngine.unitsCSV(), ExportEngine.notesCSV(), everything(zone),
        )
        try {
            Locale.setDefault(Locale.ROOT)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val reference = texts()
            assertTrue(reference[2].lines().drop(1).map { it.substringBefore(',') }.let { it == it.sorted() }, "units rows in code-point order")
            assertTrue(reference[4].contains("\"recordCount\" : 1234567") && reference[4].contains("\"longestMissingRunSeconds\" : 85050"), "ASCII digits in the archive")
            for (tag in listOf("ar-EG-u-nu-arab", "hi-IN-u-nu-deva", "th-TH-u-nu-thai", "fa-IR", "tr-TR")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
                assertEquals(reference, texts(), tag)
            }
        } finally {
            Locale.setDefault(savedLocale)
            TimeZone.setDefault(savedZone)
        }
    }

    @Test
    fun everyTopLevelSectionOfTheFullExportIsClassifiedAndEveryNumberHasAUnitOrIsACount() {
        // Upstream's own checks run on exports without an archive; this runs them on one with every section.
        val root = ExportJsonReader.root(everything(ZoneId.of("Asia/Kolkata")))
        val provenance = root.obj("provenance")!!
        val sections = root.keys - setOf("schemaVersion", "exportedAt", "meta", "provenance", "units", "notes")
        assertEquals(
            setOf("samples", "sleep", "daily", "stepSamples", "naps", "daytimeTemperatures", "historySyncEvidence", "sleepSessions", "epochArchive"),
            sections,
        )
        for (s in sections) assertTrue(provenance.has(s), "section $s is not classified")
        assertEquals(sections, provenance.keys.filter { '.' !in it }.toSet(), "no classification for a section that is not written")
        for (k in provenance.keys.filter { '.' in it }) assertTrue(k.substringBefore('.') in sections, "sub-classification $k without its section")

        val units = root.obj("units")!!
        // Upstream's allow-list of unitless numeric keys, verbatim (ExportSchemaV3Tests).
        val unitless = setOf(
            "schemaVersion", "channel", "firstOpcode", "lastOpcode", "syncAckFlag", "stagedSleepSegments", "mergedRecordCount",
            "historySampleCount", "page4CCount", "page47Count", "page4DCount", "sportSampleCount", "endMarkerCount", "recordsAtStart",
            "recordsAtEnd", "recordsAdded", "validWindows", "expectedSamples", "observedSamples", "recordCount", "archiveRecordCount",
            "evidenceRecordCount", "missingFromEvidenceCount", "longestMissingRunSeconds", "value",
        )
        val numeric = mutableSetOf<String>()
        fun walk(v: ReplayJson.Value?) {
            val o = v?.asObject()
            if (o != null) for (k in o.keys) { if (ExportJsonReader.isNumber(o[k])) numeric += k; walk(o[k]) } else v?.asArray()?.forEach(::walk)
        }
        walk(root)
        assertTrue(setOf("recordCount", "archiveRecordCount", "missingFromEvidenceCount", "longestMissingRunSeconds", "odi", "durationSec").all { it in numeric })
        // KNOWN UPSTREAM GAP, pinned exactly so no other key can join it: an edited night's
        // `provenanceSummary` emits these twelve quantities, and upstream's `units` names none of them
        // (its own audit runs on a fully measured night, which writes no summary). Upstream at the pin
        // prints the same units block; changing it would move every JSON file away from upstream's bytes.
        val provenanceSummaryGap = setOf(
            "measuredAsleepSec", "assertedOverMeasuredAsleepSec", "assertedAsleepSec", "coverageUnknownAsleepSec",
            "measuredAwakeSec", "assertedOverMeasuredAwakeSec", "assertedAwakeSec", "coverageUnknownAwakeSec",
            "coveredInBedSec", "coverageUnknownInBedSec", "longestUnmeasuredGapSec", "measuredEfficiency",
        )
        val withoutUnit = numeric.filter { !units.has(it) && it !in unitless }.toSet()
        // This night withholds its efficiency (too little covered ground), so `measuredEfficiency` is not
        // written here; it has no unit either.
        assertEquals(provenanceSummaryGap - "measuredEfficiency", withoutUnit, "numbers with no unit and not an allow-listed count")
        assertFalse(units.has("measuredEfficiency"))
    }

    @Test
    fun sessionAndOsaRowsAreValuesComparedAsSwift() {
        val source = mutableListOf(SleepSegment(t0, t1, SleepStage.ASLEEP_CORE))
        val row = ExportEngine.SleepSessionRow(sessionID = "s", night = night, hypnogram = source, summary = sleep())
        source += SleepSegment(t1, t1.plusSeconds(60), SleepStage.AWAKE)
        assertEquals(1, row.hypnogram.size, "the row keeps its own copy")
        assertFailsWith<UnsupportedOperationException> { (row.hypnogram as MutableList<SleepSegment>).clear() }
        assertEquals(row, ExportEngine.SleepSessionRow(sessionID = "s", night = night, hypnogram = listOf(SleepSegment(t0, t1, SleepStage.ASLEEP_CORE)), summary = sleep()))
        assertNotEquals(row, ExportEngine.SleepSessionRow(sessionID = "s", night = night, summary = sleep()))
        assertEquals(ExportEngine.OSARow(0.0, 1.0, 2.0, 3.0, 4), ExportEngine.OSARow(-0.0, 1.0, 2.0, 3.0, 4))
        assertEquals(ExportEngine.OSARow(0.0, 1.0, 2.0, 3.0, 4).hashCode(), ExportEngine.OSARow(-0.0, 1.0, 2.0, 3.0, 4).hashCode())
        assertNotEquals(ExportEngine.OSARow(Double.NaN, 1.0, 2.0, 3.0, 4), ExportEngine.OSARow(Double.NaN, 1.0, 2.0, 3.0, 4))
        assertNotEquals(ExportEngine.OSARow(0.0, 1.0, 2.0, 3.0, 4), ExportEngine.OSARow(0.0, 1.0, 2.0, 3.0, 5))
    }
}
