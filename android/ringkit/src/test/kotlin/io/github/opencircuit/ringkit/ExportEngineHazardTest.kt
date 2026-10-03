package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.ExportEngine.DailyRow
import io.github.opencircuit.ringkit.ExportEngine.DaytimeTemperatureRow
import io.github.opencircuit.ringkit.ExportEngine.HistorySyncEvidenceRow
import io.github.opencircuit.ringkit.ExportEngine.NapRow
import io.github.opencircuit.ringkit.ExportEngine.SleepRow
import io.github.opencircuit.ringkit.ExportEngine.StepSampleRow
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Kotlin-only hazards of the export's schema-2 rows and CSV writers — what a Kotlin stand-in can get
 * wrong that upstream's Swift types or Foundation calls decided for it. Kept out of the upstream-port
 * class (`ExportEngineTest`) so its count stays exact.
 *
 * Every upstream outcome quoted below was printed by Swift 6.3.2 on macOS 26 for the same input
 * (`String(format:)`, string interpolation, and upstream's own `csvField` body); the export
 * differential runs the pinned writers themselves over the same kinds of rows.
 */
class ExportEngineHazardTest {

    private val t0 = FoundationDate.unix(1_700_000_000.0) // 2023-11-14T22:13:20Z
    private val t1 = FoundationDate.unix(1_700_003_600.0)
    private val utc = ZoneId.of("UTC")

    private fun sleepRow(efficiency: Double = 0.9375, skinTempC: Double = 36.5, movementLevels: List<Long> = emptyList()) =
        SleepRow(
            night = t0, asleepMin = 450, deepMin = 90, lightMin = 180, remMin = 120, awakeMin = 30, efficiency = efficiency,
            skinTempC = skinTempC, sleepScore = 82, stressScore = 40, movementLevels = movementLevels,
        )

    private fun evidence(ringID: String = "ring-1", trigger: String = "manual", channels: List<HistoryChannelTrace> = emptyList(), blob: String = "AQID", outcome: String? = null) =
        HistorySyncEvidenceRow(
            capturedAt = t0, ringID = ringID, trigger = trigger, sleepCommitted = true, stagedSleepSegments = 4,
            mergedRecordCount = 8, historySampleCount = 10, rawRecordBlobBase64 = blob, channels = channels, nightRowOutcome = outcome,
        )

    private fun trace(label: String = "sport", p4d: Int?, sport: Int?): HistoryChannelTrace {
        val t = HistoryChannelTrace(label, 0x02, t0)
        t.sawSyncAck = true
        t.endMarkerCount = 1
        t.page4DCount = p4d
        t.sportSampleCount = sport
        return t
    }

    private fun dataLine(csv: String): String = csv.split("\n")[1]

    // --- a CSV trigger that carries a combining mark ---

    @Test
    fun aCommaQuoteOrLeadingSpaceCarryingACombiningMarkIsStillQuotedAndItsQuotesDoubled() {
        // Upstream tests `,` `"` and the edge spaces on Swift Characters (grapheme clusters), so a
        // trigger followed by a combining mark, a joiner or a variation selector is a different
        // Character and goes unseen. Measured: csvField("a,́b") == "a,́b" (a raw comma
        // in an unquoted field: every later column shifts), csvField("q\"́\"x") ==
        // "\"q\"́\"\"x\"" (the first quote not doubled), csvField(" ́lead") unquoted.
        // The port tests every trigger per character, as upstream already does for line breaks
        // (PORTING.md D-134): the field is quoted and every quote doubled.
        val cases = mapOf(
            "a,́b" to "\"a,́b\"",
            "a,‍b" to "\"a,‍b\"",
            "a,️b" to "\"a,️b\"",
            "a,⃣" to "\"a,⃣\"",
            "a\"́b" to "\"a\"\"́b\"",
            "q\"́\"x" to "\"q\"\"́\"\"x\"",
            " ́lead" to "\" ́lead\"",
            " ̈" to "\" ̈\"",
        )
        for ((value, quoted) in cases) assertEquals(quoted, ExportEngine.csvField(value), value)
        // Unchanged from upstream: a mark AFTER a trailing space ends the field with the mark, which
        // no parser strips — neither side quotes it; a combining mark on an ordinary letter is plain text.
        assertEquals("trail ́", ExportEngine.csvField("trail ́"))
        assertEquals("é", ExportEngine.csvField("é"))
        // And the row survives a reader: ten columns, the ring id verbatim, the blob still in column 9.
        for (ringID in cases.keys) {
            val records = ExportEngineTest.parseCSV(ExportEngine.historySyncEvidenceCSV(listOf(evidence(ringID = ringID))))
            assertEquals(2, records.size, ringID)
            assertEquals(10, records[1].size, ringID)
            assertEquals(ringID, records[1][1])
            assertEquals("AQID", records[1][8], ringID)
        }
    }

    // --- every free-form column takes hostile text ---

    @Test
    fun everyFreeFormEvidenceColumnRoundTripsHostileText() {
        val hostile = listOf("a,b", "say \"hi\"", "line\nbreak", "car\rriage", "crlf\r\nrow", " lead", "trail ", "café", "emoji 😀", "日本", "")
        for (h in hostile) {
            val row = evidence(ringID = h, trigger = h, channels = listOf(trace(label = h, p4d = 1, sport = 2)), blob = h, outcome = h)
            val records = ExportEngineTest.parseCSV(ExportEngine.historySyncEvidenceCSV(listOf(row)))
            assertEquals(2, records.size, h)
            assertEquals(10, records[1].size, h)
            assertEquals(listOf(h, h, h), listOf(records[1][1], records[1][2], records[1][8]), h)
            assertEquals("$h:sportOnly:4c=0:47=0:50=1:added=0:4d=1:sport=2", records[1][7], h)
            assertEquals(h, records[1][9], h)
        }
        // Non-ASCII text needs no quoting and is written as is.
        assertEquals(
            "2023-11-14T22:13:20.000Z,café,日本,true,4,8,10,,AQID,",
            dataLine(ExportEngine.historySyncEvidenceCSV(listOf(evidence(ringID = "café", trigger = "日本")))),
        )
    }

    // --- %.4f / %.2f at the half-way edges ---

    @Test
    fun fixedDecimalColumnsRoundTheExactBinaryValueHalfToEven() {
        // Upstream's String(format: "%.4f") / "%.2f", printed for these doubles: an exact binary tie
        // rounds to the even digit, a value just above or below its decimal tie goes its own way,
        // negative zero and a negative value that rounds to zero keep the sign, NaN prints "nan".
        val efficiency = mapOf(
            0.03125 to "0.0312", 0.09375 to "0.0938", 0.15625 to "0.1562", 0.96875 to "0.9688", 0.00005 to "0.0001",
            0.93755 to "0.9375", 0.99995 to "1.0000", 1.0 to "1.0000", -0.03125 to "-0.0312", -0.0 to "-0.0000",
            -0.00001 to "-0.0000", 5e-324 to "0.0000", Double.NaN to "nan", Double.NEGATIVE_INFINITY to "-inf",
        )
        for ((v, text) in efficiency) assertEquals(text, dataLine(ExportEngine.sleepCSV(listOf(sleepRow(efficiency = v)), utc)).split(",")[6], "efficiency $v")
        val celsius = mapOf(
            36.125 to "36.12", 36.375 to "36.38", 36.625 to "36.62", 36.875 to "36.88", 0.125 to "0.12", -0.125 to "-0.12",
            -0.001 to "-0.00", 34.2 to "34.20", 36.005 to "36.01", 36.015 to "36.02", 99.995 to "100.00",
            1e20 to "100000000000000000000.00", -Double.NaN to "nan", Double.POSITIVE_INFINITY to "inf",
        )
        for ((v, text) in celsius) {
            assertEquals(text, dataLine(ExportEngine.sleepCSV(listOf(sleepRow(skinTempC = v)), utc)).split(",")[9], "skinTempC $v")
            assertEquals("2023-11-14T22:13:20.000Z,$text", dataLine(ExportEngine.daytimeTemperatureCSV(listOf(DaytimeTemperatureRow(t0, v)))), "celsius $v")
        }
    }

    // --- the sport counters of a trace that predates them ---

    @Test
    fun eachMissingSportCounterIsOmittedOnItsOwnAndAZeroIsPrinted() {
        // Upstream appends ":4d=" and ":sport=" each under its own `if let`; the export differential
        // prints every combination through the pinned writer.
        fun summary(vararg traces: HistoryChannelTrace) =
            dataLine(ExportEngine.historySyncEvidenceCSV(listOf(evidence(channels = traces.toList())))).split(",")[7]
        assertEquals("sport:empty:4c=0:47=0:50=1:added=0:4d=0:sport=0", summary(trace(p4d = 0, sport = 0)))
        assertEquals("sport:empty:4c=0:47=0:50=1:added=0:sport=5", summary(trace(p4d = null, sport = 5)))
        assertEquals("sport:sportOnly:4c=0:47=0:50=1:added=0:4d=3", summary(trace(p4d = 3, sport = null)))
        assertEquals("sport:empty:4c=0:47=0:50=1:added=0", summary(trace(p4d = null, sport = null)))
        assertEquals(
            "a:empty:4c=0:47=0:50=1:added=0|b:sportOnly:4c=0:47=0:50=1:added=0:4d=7:sport=210",
            summary(trace("a", null, null), trace("b", 7, 210)),
        )
        assertEquals("", summary())
    }

    // --- 64-bit counts ---

    @Test
    fun theNewRowsCarrySwiftsSixtyFourBitIntsThroughEveryWriter() {
        // A Swift Int is 64 bits; interpolation prints Int.max as 9223372036854775807 and Int.min as
        // -9223372036854775808 (measured). The new rows' counts are Kotlin Longs, so the whole range
        // passes through; a ported type's 32-bit count is widened at the call site.
        val max = Long.MAX_VALUE
        val min = Long.MIN_VALUE
        assertEquals("2023-11-14T22:13:20.000Z,2023-11-14T23:13:20.000Z,$max", dataLine(ExportEngine.stepSamplesCSV(listOf(StepSampleRow(t0, t1, max)))))
        assertEquals("2023-11-14T22:13:20.000Z,2023-11-14T23:13:20.000Z,$min,true", dataLine(ExportEngine.napsCSV(listOf(NapRow(t0, t1, min, true)))))
        assertEquals("2023-11-14,$max", dataLine(ExportEngine.dailyCSV(listOf(DailyRow(t0, max)), utc)))
        val traced = trace(p4d = 1, sport = 1)
        traced.recordsAtEnd = Int.MAX_VALUE
        val row = HistorySyncEvidenceRow(
            capturedAt = t0, ringID = "r", trigger = "t", sleepCommitted = false, stagedSleepSegments = max,
            mergedRecordCount = traced.recordsAdded.toLong(), historySampleCount = min, rawRecordBlobBase64 = "", channels = listOf(traced),
        )
        assertEquals(
            "2023-11-14T22:13:20.000Z,r,t,false,$max,2147483647,$min,sport:sportOnly:4c=0:47=0:50=1:added=2147483647:4d=1:sport=1,,",
            dataLine(ExportEngine.historySyncEvidenceCSV(listOf(row))),
        )
        val sleep = SleepRow(
            night = t0, asleepMin = max, deepMin = min, lightMin = 0, remMin = -1, awakeMin = 1, efficiency = 0.5, skinTempC = 36.0,
            sleepScore = max, stressScore = min, feelScore = max, hrDeep = min, hrLight = 0, hrRem = 1, hrAwake = -1, movementLevels = listOf(max, min, 0),
        )
        assertEquals(
            "2023-11-14,$max,$min,0,-1,1,0.5000,,,36.00,$max,$min,$max,$min,0,1,-1,$max|$min|0",
            dataLine(ExportEngine.sleepCSV(listOf(sleep), utc)),
        )
    }

    // --- device-local labels: one zone, passed in ---

    @Test
    fun theNightAndDayLabelsAndTheSessionIdArePrintedInThePassedZone() {
        // Upstream prints these in Calendar.current; the port takes the zone. yyyy-MM-dd follows the
        // same rounded millisecond as the ISO-8601 text (measured: half a millisecond before midnight
        // prints the next day).
        val midnightUtc = FoundationDate.unix(1_699_920_000.0) // 2023-11-14T00:00:00Z
        val labels = mapOf(
            "UTC" to "2023-11-14", "America/New_York" to "2023-11-13", "America/St_Johns" to "2023-11-13",
            "Asia/Kolkata" to "2023-11-14", "Europe/Amsterdam" to "2023-11-14", "Pacific/Kiritimati" to "2023-11-14",
            "Pacific/Pago_Pago" to "2023-11-13",
        )
        for ((id, label) in labels) {
            val zone = ZoneId.of(id)
            assertEquals(label, ExportEngine.dayStamp(midnightUtc, zone), id)
            assertEquals("night-$label", ExportEngine.sessionID(midnightUtc, zone), id)
            assertEquals(label, dataLine(ExportEngine.sleepCSV(listOf(sleepRow().withNight(midnightUtc)), zone)).substringBefore(','), id)
            assertEquals("$label,1", dataLine(ExportEngine.dailyCSV(listOf(DailyRow(midnightUtc, 1)), zone)), id)
            val root = ExportJsonReader.root(ExportEngine.toJSON(samples = emptyList(), sleep = listOf(sleepRow().withNight(midnightUtc)), daily = listOf(DailyRow(midnightUtc, 1)), zone = zone, now = t0)!!)
            assertEquals(label, root["sleep"]?.asObjectList()?.single()?.string("night"), id)
            assertEquals(label, root["daily"]?.asObjectList()?.single()?.string("day"), id)
        }
        // Local midnights on both sides of a DST change (the bucket a night is keyed by).
        val amsterdam = ZoneId.of("Europe/Amsterdam")
        assertEquals("night-2023-10-29", ExportEngine.sessionID(FoundationDate.unix(1_698_530_400.0), amsterdam)) // 2023-10-28T22:00Z
        assertEquals("night-2023-10-30", ExportEngine.sessionID(FoundationDate.unix(1_698_620_400.0), amsterdam)) // 2023-10-29T23:00Z
        assertEquals("night-2024-03-31", ExportEngine.sessionID(FoundationDate.unix(1_711_839_600.0), amsterdam)) // 2024-03-30T23:00Z
        assertEquals("night-2024-04-01", ExportEngine.sessionID(FoundationDate.unix(1_711_922_400.0), amsterdam)) // 2024-03-31T22:00Z
        // Half a millisecond before Kolkata's midnight of 2023-11-15 is printed as the next day; 0.6 ms
        // before is not (the Date doubles Foundation was measured on, as IEEE-754 bits).
        val kolkata = ZoneId.of("Asia/Kolkata")
        assertEquals("night-2023-11-15", ExportEngine.sessionID(FoundationDate.referenceBits(4739337093954662302L), kolkata))
        assertEquals("2023-11-14", ExportEngine.dayStamp(FoundationDate.referenceBits(4739337093954661463L), kolkata))
    }

    // --- the schema-2 JSON sections, field by field ---

    @Test
    fun everySchemaTwoSectionCarriesEveryFieldWithExplicitNullsAndTypedValues() {
        val withTimes = SleepRow(
            night = t0, asleepMin = 450, deepMin = 90, lightMin = 180, remMin = 120, awakeMin = 30, efficiency = 0.9375,
            inBedStart = t0, inBedEnd = t1, skinTempC = 34.2, sleepScore = 82, stressScore = 40, feelScore = 7,
            hrDeep = 55, hrLight = 60, hrRem = 64, hrAwake = 68, movementLevels = listOf(0, 1, 2),
        )
        val full = HistoryChannelTrace("sleep", 0x00, t0)
        full.finishedAt = t1
        full.sawSyncAck = true
        full.syncAckFlag = 0x01
        full.page4CCount = 3
        full.page47Count = 2
        full.endMarkerCount = 1
        full.recordsAtStart = 2
        full.recordsAtEnd = 8
        full.firstOpcode = 0x4C
        full.lastOpcode = 0x50
        full.exitReason = HistoryChannelExitReason.END_MARKER
        val legacy = trace(p4d = null, sport = null)
        val json = ExportEngine.toJSON(
            samples = emptyList(), sleep = listOf(withTimes, sleepRow()), daily = listOf(DailyRow(t0, 8000)),
            stepSamples = listOf(StepSampleRow(t0, t1, 123)), naps = listOf(NapRow(t0, t1, 30, true)),
            daytimeTemperatures = listOf(DaytimeTemperatureRow(t0, 34.2)),
            historySyncEvidence = listOf(evidence(channels = listOf(full, legacy), outcome = "updated"), evidence()),
            zone = utc, now = t1,
        )
        val root = ExportJsonReader.root(json!!)
        assertEquals("2023-11-14T23:13:20.000Z", root.string("exportedAt"))

        val (s, noTimes) = root["sleep"]!!.asObjectList()!!
        assertEquals(
            setOf(
                "night", "asleepMin", "deepMin", "lightMin", "remMin", "awakeMin", "efficiency", "inBedStart", "inBedEnd", "skinTempC",
                "sleepScore", "stressScore", "feelScore", "hrDeep", "hrLight", "hrRem", "hrAwake", "movementLevels",
            ),
            s.keys,
        )
        assertEquals("2023-11-14", s.string("night"))
        assertEquals(listOf(450L, 90L, 180L, 120L, 30L, 82L, 40L, 7L, 55L, 60L, 64L, 68L), listOf("asleepMin", "deepMin", "lightMin", "remMin", "awakeMin", "sleepScore", "stressScore", "feelScore", "hrDeep", "hrLight", "hrRem", "hrAwake").map { s.long(it) })
        assertEquals(0.9375, s.double("efficiency"))
        assertEquals(34.2, s.double("skinTempC"))
        assertEquals("2023-11-14T22:13:20.000Z", s.string("inBedStart"))
        assertEquals("2023-11-14T23:13:20.000Z", s.string("inBedEnd"))
        assertEquals(listOf(0L, 1L, 2L), s.array("movementLevels")!!.map { it.asLong() })
        assertEquals(ReplayJson.Null, noTimes["inBedStart"], "an absent in-bed time is an explicit null")
        assertEquals(ReplayJson.Null, noTimes["inBedEnd"])
        assertEquals(emptyList(), noTimes.array("movementLevels"))

        val d = root["daily"]!!.asObjectList()!!.single()
        assertEquals(setOf("day", "steps"), d.keys)
        assertEquals(8000L, d.long("steps"))
        val st = root["stepSamples"]!!.asObjectList()!!.single()
        assertEquals(mapOf("start" to "2023-11-14T22:13:20.000Z", "end" to "2023-11-14T23:13:20.000Z"), mapOf("start" to st.string("start"), "end" to st.string("end")))
        assertEquals(setOf("start", "end", "delta"), st.keys)
        assertEquals(123L, st.long("delta"))
        val n = root["naps"]!!.asObjectList()!!.single()
        assertEquals(setOf("start", "end", "asleepMin", "isLongNap"), n.keys)
        assertEquals(ReplayJson.Bool(true), n["isLongNap"], "a boolean, not a number")
        assertEquals(30L, n.long("asleepMin"))
        val tmp = root["daytimeTemperatures"]!!.asObjectList()!!.single()
        assertEquals(setOf("time", "celsius"), tmp.keys)
        assertEquals("34.200000000000003", tmp["celsius"].toString(), "Foundation's %.17g number text")

        val (e, bare) = root["historySyncEvidence"]!!.asObjectList()!!
        assertEquals(
            setOf("capturedAt", "ringID", "trigger", "sleepCommitted", "nightRowOutcome", "stagedSleepSegments", "mergedRecordCount", "historySampleCount", "rawRecordBlobBase64", "channels"),
            e.keys,
        )
        assertEquals("updated", e.string("nightRowOutcome"))
        assertEquals(ReplayJson.Null, bare["nightRowOutcome"], "no staged night is an explicit null")
        assertEquals(ReplayJson.Bool(true), e["sleepCommitted"])
        assertEquals(listOf(4L, 8L, 10L), listOf(e.long("stagedSleepSegments"), e.long("mergedRecordCount"), e.long("historySampleCount")))
        assertEquals(emptyList(), bare.array("channels"))
        val (c, l) = e["channels"]!!.asObjectList()!!
        assertEquals(
            setOf(
                "label", "channel", "startedAt", "finishedAt", "outcome", "sawSyncAck", "syncAckFlag", "page4CCount", "page47Count", "page4DCount",
                "sportSampleCount", "endMarkerCount", "recordsAtStart", "recordsAtEnd", "recordsAdded", "firstOpcode", "lastOpcode", "exitReason",
            ),
            c.keys,
        )
        assertEquals(listOf("sleep", "complete", "2023-11-14T22:13:20.000Z", "2023-11-14T23:13:20.000Z", "endMarker"), listOf("label", "outcome", "startedAt", "finishedAt", "exitReason").map { c.string(it) })
        assertEquals(listOf(0L, 1L, 3L, 2L, 0L, 0L, 1L, 2L, 8L, 6L, 0x4CL, 0x50L), listOf("channel", "syncAckFlag", "page4CCount", "page47Count", "page4DCount", "sportSampleCount", "endMarkerCount", "recordsAtStart", "recordsAtEnd", "recordsAdded", "firstOpcode", "lastOpcode").map { c.long(it) })
        assertEquals(ReplayJson.Bool(true), c["sawSyncAck"])
        // A trace that predates the sport counters, and one that never finished: nulls, not zeros.
        for (key in listOf("finishedAt", "syncAckFlag", "page4DCount", "sportSampleCount", "firstOpcode", "lastOpcode", "exitReason")) {
            assertEquals(ReplayJson.Null, l[key], key)
        }
        assertEquals("empty", l.string("outcome"))
        assertEquals(c.keys, l.keys)
    }

    @Test
    fun aNonFiniteValueInAnySchemaTwoSectionMakesToJsonNullWhileItsCsvStillWrites() {
        // Upstream's JSONSerialization raises on NaN or an infinity and the app dies (measured); the
        // port's toJSON returns null instead (PORTING.md D-132), whichever section holds it.
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            fun json(sleep: List<SleepRow> = emptyList(), temps: List<DaytimeTemperatureRow> = emptyList()) =
                ExportEngine.toJSON(samples = emptyList(), sleep = sleep, daily = emptyList(), daytimeTemperatures = temps, zone = utc, now = t0)
            assertEquals(null, json(sleep = listOf(sleepRow(), sleepRow(efficiency = bad))), "efficiency $bad")
            assertEquals(null, json(sleep = listOf(sleepRow(skinTempC = bad))), "skinTempC $bad")
            assertEquals(null, json(temps = listOf(DaytimeTemperatureRow(t0, 36.0), DaytimeTemperatureRow(t1, bad))), "celsius $bad")
            assertTrue(json(sleep = listOf(sleepRow()), temps = listOf(DaytimeTemperatureRow(t0, 36.0))) != null)
            val text = if (bad.isNaN()) "nan" else if (bad > 0) "inf" else "-inf"
            assertEquals(text, dataLine(ExportEngine.sleepCSV(listOf(sleepRow(efficiency = bad)), utc)).split(",")[6])
            assertEquals("2023-11-14T22:13:20.000Z,$text", dataLine(ExportEngine.daytimeTemperatureCSV(listOf(DaytimeTemperatureRow(t0, bad)))))
        }
    }

    private fun SleepRow.withNight(n: java.time.Instant) = SleepRow(
        night = n, asleepMin = asleepMin, deepMin = deepMin, lightMin = lightMin, remMin = remMin, awakeMin = awakeMin,
        efficiency = efficiency, inBedStart = inBedStart, inBedEnd = inBedEnd, skinTempC = skinTempC, sleepScore = sleepScore,
        stressScore = stressScore, feelScore = feelScore, hrDeep = hrDeep, hrLight = hrLight, hrRem = hrRem, hrAwake = hrAwake,
        movementLevels = movementLevels,
    )

    // --- rows are values: copied in, read-only out ---

    @Test
    fun rowListsAreCopiedInAndCannotBeChangedThroughTheRow() {
        // A Swift struct holds its arrays and traces by value; Kotlin lists and HistoryChannelTrace
        // are shared references, so the rows copy them in and hand out read-only snapshots.
        val levels = mutableListOf(0L, 1L, 2L)
        val sleep = sleepRow(movementLevels = levels)
        levels[0] = 9L
        levels += 7L
        assertEquals(listOf(0L, 1L, 2L), sleep.movementLevels)
        @Suppress("UNCHECKED_CAST")
        assertFailsWith<UnsupportedOperationException> { (sleep.movementLevels as MutableList<Long>).add(5L) }
        assertTrue(dataLine(ExportEngine.sleepCSV(listOf(sleep), utc)).endsWith(",0|1|2"))

        val live = trace(p4d = 1, sport = 1)
        val traces = mutableListOf(live)
        val row = evidence(channels = traces)
        val before = ExportEngine.historySyncEvidenceCSV(listOf(row))
        live.page4CCount = 99 // the drain keeps filling its own trace
        traces += trace("extra", null, null)
        row.channels[0].page47Count = 42 // what a reader is handed is its own copy
        assertEquals(before, ExportEngine.historySyncEvidenceCSV(listOf(row)))
        assertEquals(1, row.channels.size)
        assertFalse(row.channels[0] === row.channels[0], "each read is a fresh snapshot")
        @Suppress("UNCHECKED_CAST")
        assertFailsWith<UnsupportedOperationException> { (row.channels as MutableList<HistoryChannelTrace>).clear() }
    }
}
