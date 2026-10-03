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
import org.junit.jupiter.api.Timeout
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Byte-for-byte differential of the export writers against upstream's own Swift code:
 * `tools/sleep-differential` (its `ExportDifferential` generator, `regenerate.sh export`) ran the
 * pinned `ExportEngine` writers — every schema-2 CSV, `sessionID` / `dayStamp` and `toJSON` — over
 * synthetic cases: the measured number texts, hostile kind strings (every CSV quoting trigger and
 * JSON escape class), no rows, millisecond ties before and after 1970, a seeded sweep of doubles
 * across every exponent and the format boundaries, non-finite values, every schema-2 row kind in
 * Amsterdam, Kolkata and St John's across their DST changes (with the fixed-decimal edges, every
 * channel outcome and each sport counter absent on its own), hostile text and 64-bit extremes in
 * every evidence column, and a comma, quote or edge space carrying a combining mark; and the schema-3
 * core — metadata blocks (one exported half a millisecond before a DST change), sleep sessions of five
 * kinds in Kolkata and across St John's spring change (edited and unedited, staged, envelope-only,
 * stitched and absent hypnograms with every provenance, OSA with valid, zero and negative window counts,
 * coverage with and without holes, reference wakes measured, "so far" and unavailable, edge rows
 * assessed and built directly, a measured 0 s gap), every instant millisecond-stamped with varying
 * fractions; epoch archives (complete, missing epochs, empty) in Kolkata and in a case holding every
 * section at once in America/New_York across its autumn change; the provenance CSV of every schema-3
 * case and the units and notes CSVs — and wrote each output verbatim to
 * `src/test/resources/export-differential/`. A separate sweep of 10 000 seeded doubles records the text
 * `JSONSerialization` and the samples CSV wrote for each, and the port's two number paths must match it.
 * The key-order vocabulary is checked to be exactly the set of keys the JSON goldens hold. For the
 * sessions the port rebuilds the coverage, reference and edge verdicts from the same inputs with its own
 * code, so their seconds are compared as bytes. This test rebuilds the same rows,
 * runs the Kotlin writers in the case's zone and compares BYTES. It also sorts the export's key vocabulary and seeded random
 * printable-ASCII keys with the port's comparator and compares the order `JSONSerialization` wrote.
 * The format is documented at the top of the generator's `main.swift`.
 *
 * Comparison rule: every output is compared whole and exactly. The goldens come only from the Swift
 * generator, never from this code's output. Where the port deliberately differs from upstream on a
 * case, the output that moves is named in [DELIBERATE_DIVERGENCES] (case + output, with the
 * `PORTING.md` D-row) and reported; no other output may differ, and a listed output that stops
 * differing fails as stale. A "csvonly" case holds a value upstream's `toJSON` cannot serialize (it
 * raises and the app dies): it has no JSON golden, and the port's `toJSON` must return null there.
 */
class ExportDifferentialTest {

    /** One session's inputs; [row] rebuilds the coverage, reference-wake and edge verdicts with the port's own code. */
    private class SessionInput(val head: List<String>, val reader: ExportDifferentialTest) {
        var summary: SleepRow? = null
        val segments = mutableListOf<SleepSegment>()
        var osa: OSARow? = null
        var coverage: Triple<Instant, Instant, List<Instant>>? = null
        var reference: List<String>? = null
        var edge: List<String>? = null

        fun row(): SleepSessionRow = with(reader) {
            val cov = coverage?.let { (from, to, times) -> ExportCoverage.assess(times, from, to) }
            val ref = reference?.let { f ->
                when (f[0]) {
                    "rm" -> ExportReferenceCoverage.Outcome.Measured(
                        checkNotNull(
                            ExportReferenceCoverage.assess(
                                coverage?.third ?: emptyList(), date(f[2]), date(f[3]), date(f[4]),
                                checkNotNull(ExportReferenceCoverage.Reference.fromRawValue(hexString(f[1]))),
                            ),
                        ),
                    )
                    else -> ExportReferenceCoverage.Outcome.Unavailable(hexString(f[1]))
                }
            }
            val edgeRow = edge?.let { f ->
                when (f[0]) {
                    "ea" -> {
                        val start = date(f[3])
                        val end = date(f[4])
                        val assessment = SleepConfidence.assess(
                            double(f[1]), double(f[2]),
                            SleepConfidence.Coverage(start, end, opt(f[5], ::date), opt(f[6], ::date), f.drop(9).map(::date), opt(f[7], ::date)),
                        )
                        SleepEdgeProvenanceRow(start, end, assessment, hexString(f[8]))
                    }
                    else -> SleepEdgeProvenanceRow(
                        windowStart = date(f[1]), windowEnd = date(f[2]), bedtimeVerdict = hexString(f[3]), bedtimeGapSeconds = opt(f[4], ::double),
                        wakeVerdict = hexString(f[5]), wakeGapSeconds = opt(f[6], ::double), reasons = reasons(f[9]),
                        materialGapSeconds = double(f[7]), durationBasis = hexString(f[8]),
                    )
                }
            }
            SleepSessionRow(
                sessionID = hexString(head[1]), night = date(head[2]), inBedStart = opt(head[3], ::date), inBedEnd = opt(head[4], ::date),
                sleepOnset = opt(head[5], ::date), sleepWake = opt(head[6], ::date), isManuallyEdited = flag(head[7]),
                recordedInBedStart = opt(head[8], ::date), recordedInBedEnd = opt(head[9], ::date), recordedOnset = opt(head[10], ::date),
                recordedWake = opt(head[11], ::date), hypnogram = segments, summary = checkNotNull(summary) { "session without a summary" },
                osa = osa, coverage = cov, referenceCoverage = ref, edgeProvenance = edgeRow,
            )
        }
    }

    private class ECase(val id: String, val zone: ZoneId, val now: Instant, val json: Boolean, val v3: Boolean) {
        var meta: ExportMetadata? = null
        val archives = mutableListOf<ExportEngine.EpochArchiveRow>()
        val sessions = mutableListOf<SessionInput>()
        val sessionRows: List<SleepSessionRow> get() = sessions.map { it.row() }

        val rows = mutableListOf<SampleRow>()
        val sleep = mutableListOf<SleepRow>()
        val daily = mutableListOf<DailyRow>()
        val steps = mutableListOf<StepSampleRow>()
        val naps = mutableListOf<NapRow>()
        val temperatures = mutableListOf<DaytimeTemperatureRow>()
        val evidence = mutableListOf<Pair<EvidenceHead, MutableList<HistoryChannelTrace>>>()

        val evidenceRows: List<HistorySyncEvidenceRow>
            get() = evidence.map { (h, traces) ->
                HistorySyncEvidenceRow(
                    capturedAt = h.capturedAt, ringID = h.ringID, trigger = h.trigger, sleepCommitted = h.sleepCommitted,
                    stagedSleepSegments = h.staged, mergedRecordCount = h.merged, historySampleCount = h.history,
                    rawRecordBlobBase64 = h.blob, channels = traces, nightRowOutcome = h.outcome,
                )
            }

        /** Every double the case's rows hold — a non-finite one is what upstream's JSON cannot write. */
        val doubles: List<Double>
            get() = rows.map { it.value } + sleep.flatMap { listOf(it.efficiency, it.skinTempC) } + temperatures.map { it.celsius }
    }

    private class EvidenceHead(
        val capturedAt: Instant, val ringID: String, val trigger: String, val sleepCommitted: Boolean, val staged: Long,
        val merged: Long, val history: Long, val blob: String, val outcome: String?,
    )

    /** Each output file of a case, with the port's writer that must reproduce it. */
    private val writers: List<Pair<String, (ECase) -> String>> = listOf(
        "samples.csv" to { c -> ExportEngine.samplesCSV(c.rows) },
        "sleep.csv" to { c -> ExportEngine.sleepCSV(c.sleep, c.zone) },
        "daily.csv" to { c -> ExportEngine.dailyCSV(c.daily, c.zone) },
        "stepSamples.csv" to { c -> ExportEngine.stepSamplesCSV(c.steps) },
        "naps.csv" to { c -> ExportEngine.napsCSV(c.naps) },
        "daytimeTemperatures.csv" to { c -> ExportEngine.daytimeTemperatureCSV(c.temperatures) },
        "historySyncEvidence.csv" to { c -> ExportEngine.historySyncEvidenceCSV(c.evidenceRows) },
        "labels.txt" to { c -> (c.sleep.map { ExportEngine.sessionID(it.night, c.zone) } + c.daily.map { ExportEngine.dayStamp(it.day, c.zone) }).joinToString("\n") },
    )

    /** The schema-3 outputs of a "v3" case (the metadata CSV only when the case has a metadata block). */
    private fun v3Writers(c: ECase): List<Pair<String, (ECase) -> String>> =
        listOfNotNull(
            c.meta?.let { meta -> "metadata.csv" to { e: ECase -> ExportEngine.metadataCSV(meta, e.zone) } },
            "sleepSessions.csv" to { e: ECase -> ExportEngine.sleepSessionsCSV(e.sessionRows, e.zone) },
            "hypnogram.csv" to { e: ECase -> ExportEngine.hypnogramCSV(e.sessionRows, e.zone) },
            "provenance.csv" to { e: ECase -> ExportEngine.provenanceCSV(includesSleepSessions = e.sessions.isNotEmpty()) },
        ).takeIf { c.v3 } ?: emptyList()

    /** The outputs that take no inputs, written once. */
    private val globalWriters: List<Pair<String, () -> String>> = listOf(
        "units.csv" to { ExportEngine.unitsCSV() },
        "notes.csv" to { ExportEngine.notesCSV() },
    )

    private fun outputs(c: ECase): List<Pair<String, (ECase) -> String>> = writers + v3Writers(c)

    private fun json(c: ECase): String? = ExportEngine.toJSON(
        samples = c.rows, sleep = c.sleep, daily = c.daily, stepSamples = c.steps, naps = c.naps,
        daytimeTemperatures = c.temperatures, historySyncEvidence = c.evidenceRows, zone = c.zone, now = c.now,
        metadata = c.meta, sleepSessions = c.sessionRows, epochArchives = c.archives,
    )

    private data class Divergence(val case: String, val output: String)

    private class Report {
        val mismatches = mutableListOf<String>()
        val allowed = mutableMapOf<String, MutableList<Divergence>>()
        var compared = 0
        var bytes = 0L
    }

    /**
     * Each deliberate byte difference from upstream, keyed by the improvement (with its `PORTING.md`
     * D-row). A listed output that no longer differs fails as stale.
     *
     * D-134: upstream's CSV field escaper tests the comma, the quote and the edge spaces on Swift
     * Characters, so one carrying a combining mark, a joiner, a variation selector or a keycap is
     * missed and the row's later columns shift; the port quotes per character. The `v2-grapheme`
     * case holds only such values, in the kinds of its samples and the ring ids of its evidence, so
     * exactly those two CSVs differ; its JSON (escaped per character on both sides) does not.
     *
     * D-43 (the history-sync port): a channel that delivered epoch pages and went quiet without the
     * end marker is `partial` in the port, `complete` upstream, so its outcome prints differently in
     * the evidence CSV's channel summary and the JSON's `outcome`. Only `v2-quiet-after-pages` holds
     * such a trace; every other case's traces classify alike on both sides.
     */
    private val DELIBERATE_DIVERGENCES: Map<String, Set<Divergence>> = mapOf(
        "D-134 per-character CSV quoting" to setOf(Divergence("v2-grapheme", "samples.csv"), Divergence("v2-grapheme", "historySyncEvidence.csv")),
        "D-43 quiet after pages without the end marker is partial" to
            setOf(Divergence("v2-quiet-after-pages", "historySyncEvidence.csv"), Divergence("v2-quiet-after-pages", "json")),
    )

    // --- reading the generator's files ---

    private fun resource(name: String): ByteArray? = javaClass.classLoader.getResourceAsStream("export-differential/$name")?.use { it.readBytes() }

    private fun text(name: String): List<String> =
        String(checkNotNull(resource(name)) { "missing test resource $name" }, Charsets.UTF_8).lines().filter { it.isNotEmpty() && !it.startsWith("#") }

    private fun hexString(t: String): String {
        require(t.startsWith("x") && t.length % 2 == 1) { "bad string token $t" }
        val bytes = ByteArray((t.length - 1) / 2) { k -> Integer.parseInt(t.substring(1 + 2 * k, 3 + 2 * k), 16).toByte() }
        return String(bytes, Charsets.UTF_8)
    }

    private fun bitsOf(t: String): Long {
        require(t.length == 17 && t[0] == 'd') { "bad double token $t" }
        return java.lang.Long.parseUnsignedLong(t.substring(1), 16)
    }

    private fun double(t: String): Double = java.lang.Double.longBitsToDouble(bitsOf(t))
    private fun date(t: String): Instant = FoundationDate.referenceBits(bitsOf(t))

    private fun <T> opt(t: String, read: (String) -> T): T? = if (t == "-") null else read(t)

    private fun flag(t: String): Boolean = when (t) {
        "1" -> true
        "0" -> false
        else -> error("bad flag token $t")
    }

    private fun levels(t: String): List<Long> {
        require(t.startsWith("m")) { "bad movement-levels token $t" }
        return if (t.length == 1) emptyList() else t.substring(1).split('|').map { it.toLong() }
    }

    private fun reasons(t: String): List<String> {
        require(t.startsWith("r")) { "bad reasons token $t" }
        return if (t.length == 1) emptyList() else t.substring(1).split('|').map(::hexString)
    }

    /** The 18 fields of a sleep row after its line kind ("sl", or "sm" for a session's summary). */
    private fun sleepRow(f: List<String>) = SleepRow(
        night = date(f[1]), asleepMin = f[2].toLong(), deepMin = f[3].toLong(), lightMin = f[4].toLong(),
        remMin = f[5].toLong(), awakeMin = f[6].toLong(), efficiency = double(f[7]), inBedStart = opt(f[8], ::date),
        inBedEnd = opt(f[9], ::date), skinTempC = double(f[10]), sleepScore = f[11].toLong(), stressScore = f[12].toLong(),
        feelScore = f[13].toLong(), hrDeep = f[14].toLong(), hrLight = f[15].toLong(), hrRem = f[16].toLong(),
        hrAwake = f[17].toLong(), movementLevels = levels(f[18]),
    )

    private fun stage(raw: String): SleepStage = SleepStage.entries.singleOrNull { it.rawValue == raw } ?: error("unknown stage $raw")
    private fun provenance(raw: String): SleepProvenance = SleepProvenance.entries.singleOrNull { it.rawValue == raw } ?: error("unknown provenance $raw")

    private fun exitReason(raw: String): HistoryChannelExitReason =
        HistoryChannelExitReason.entries.singleOrNull { it.rawValue == raw } ?: error("unknown exit reason $raw")

    private fun trace(f: List<String>): HistoryChannelTrace {
        val t = HistoryChannelTrace(hexString(f[1]), f[2].toInt(), date(f[3]))
        t.finishedAt = opt(f[4], ::date)
        t.sawSyncAck = flag(f[5])
        t.syncAckFlag = opt(f[6]) { it.toInt() }
        t.sawEmptyHistorySignal = flag(f[7])
        t.openWriteFailed = opt(f[8], ::flag)
        t.fetchNudges = opt(f[9]) { it.toInt() }
        t.reopenRound = opt(f[10]) { it.toInt() }
        t.page4CCount = f[11].toInt()
        t.page47Count = f[12].toInt()
        t.page4DCount = opt(f[13]) { it.toInt() }
        t.sportSampleCount = opt(f[14]) { it.toInt() }
        t.endMarkerCount = f[15].toInt()
        t.recordsAtStart = f[16].toInt()
        t.recordsAtEnd = f[17].toInt()
        t.firstOpcode = opt(f[18]) { it.toInt() }
        t.lastOpcode = opt(f[19]) { it.toInt() }
        t.exitReason = opt(f[20]) { exitReason(hexString(it)) }
        return t
    }

    private fun inputs(): List<ECase> {
        val all = text("inputs.txt")
        val out = mutableListOf<ECase>()
        var i = 0
        while (i < all.size) {
            val head = all[i].split(' ')
            check(head.size in 5..6 && head[0] == "case" && head[4] in setOf("json", "csvonly") && (head.size == 5 || head[5] == "v3")) {
                "bad case header: ${all[i]}"
            }
            val c = ECase(head[1], ZoneId.of(head[2]), date(head[3]), head[4] == "json", head.size == 6)
            i++
            while (all[i] != "end") {
                val f = all[i].split(' ')
                val arity = mapOf(
                    "s" to 5, "sl" to 19, "dy" to 3, "st" to 4, "np" to 5, "dt" to 3, "ev" to 10, "ch" to 21,
                    "md" to 16, "ss" to 12, "sm" to 19, "sg" to 5, "os" to 6, "rm" to 5, "ru" to 2, "ed" to 10, "ar" to 10,
                )
                val variable = mapOf("cv" to 3, "ea" to 9) // at least this many tokens
                check(arity[f[0]] == f.size || (variable[f[0]] ?: Int.MAX_VALUE) <= f.size) { "bad row line: ${all[i]}" }
                when (f[0]) {
                    "s" -> c.rows += SampleRow(hexString(f[1]), date(f[2]), date(f[3]), double(f[4]))
                    "sl" -> c.sleep += sleepRow(f)
                    "md" -> c.meta = ExportMetadata(
                        schemaVersion = f[15].toLong(), exportedAt = date(f[1]), rangeStart = date(f[2]), rangeEnd = date(f[3]),
                        appVersion = hexString(f[4]), appBuild = hexString(f[5]), deviceModel = hexString(f[6]), osVersion = hexString(f[7]),
                        ringModel = hexString(f[8]), ringFirmware = hexString(f[9]), ringGeneration = hexString(f[10]),
                        ringIdentifier = hexString(f[11]), timeZoneIdentifier = hexString(f[12]), timestampPolicy = hexString(f[13]),
                        timeZoneOffsetSeconds = f[14].toLong(),
                    )
                    "ss" -> c.sessions += SessionInput(f, this)
                    "sm" -> c.sessions.last().summary = sleepRow(f)
                    "sg" -> c.sessions.last().segments += SleepSegment(date(f[1]), date(f[2]), stage(hexString(f[3])), provenance(hexString(f[4])))
                    "os" -> c.sessions.last().osa = OSARow(double(f[1]), double(f[2]), double(f[3]), double(f[4]), f[5].toLong())
                    "cv" -> c.sessions.last().coverage = Triple(date(f[1]), date(f[2]), f.drop(3).map(::date))
                    "rm", "ru" -> c.sessions.last().reference = f
                    "ea", "ed" -> c.sessions.last().edge = f
                    "ar" -> c.archives += ExportEngine.EpochArchiveRow(
                        ringID = hexString(f[1]), recordsBase64 = hexString(f[2]), recordCount = f[3].toLong(),
                        firstEpoch = opt(f[4], ::date), lastEpoch = opt(f[5], ::date),
                        coverage = ArchiveEvidenceCoverage.Report(
                            archiveRecordCount = f[6].toInt(), evidenceRecordCount = f[7].toInt(),
                            missingFromEvidence = levels(f[8]), longestMissingRunSeconds = f[9].toInt(),
                        ),
                    )
                    "dy" -> c.daily += DailyRow(date(f[1]), f[2].toLong())
                    "st" -> c.steps += StepSampleRow(date(f[1]), date(f[2]), f[3].toLong())
                    "np" -> c.naps += NapRow(date(f[1]), date(f[2]), f[3].toLong(), flag(f[4]))
                    "dt" -> c.temperatures += DaytimeTemperatureRow(date(f[1]), double(f[2]))
                    "ev" -> c.evidence += EvidenceHead(
                        date(f[1]), hexString(f[2]), hexString(f[3]), flag(f[4]), f[5].toLong(), f[6].toLong(), f[7].toLong(),
                        hexString(f[8]), opt(f[9], ::hexString),
                    ) to mutableListOf()
                    "ch" -> c.evidence.last().second += trace(f)
                }
                i++
            }
            out += c
            i++
        }
        return out
    }

    // --- comparing ---

    private fun describe(expected: ByteArray, actual: ByteArray?): String {
        if (actual == null) return "the port wrote nothing (null)"
        var k = 0
        while (k < expected.size && k < actual.size && expected[k] == actual[k]) k++
        fun around(b: ByteArray) = String(b.copyOfRange(maxOf(0, k - 40), minOf(b.size, k + 40)), Charsets.UTF_8)
            .replace("\n", "\\n").replace("\r", "\\r")
        return "first difference at byte $k of ${expected.size} (port: ${actual.size} bytes)\n      upstream: …${around(expected)}…\n      port:     …${around(actual)}…"
    }

    private fun compare(case: String, output: String, expected: ByteArray, actual: ByteArray?, divergences: Map<String, Set<Divergence>>, report: Report) {
        report.compared++
        report.bytes += expected.size
        if (actual != null && expected.contentEquals(actual)) return
        val improvement = divergences.entries.firstOrNull { Divergence(case, output) in it.value }?.key
        if (improvement != null) {
            report.allowed.getOrPut(improvement) { mutableListOf() } += Divergence(case, output)
        } else {
            report.mismatches += "$case / $output: ${describe(expected, actual)}"
        }
    }

    private fun staleEntries(report: Report, divergences: Map<String, Set<Divergence>>): List<String> =
        divergences.flatMap { (improvement, entries) -> entries.filter { it !in report.allowed[improvement].orEmpty() }.map { "$improvement: $it" } }

    private fun runAll(divergences: Map<String, Set<Divergence>>): Report {
        val report = Report()
        for (c in inputs()) {
            for ((output, write) in outputs(c)) {
                val golden = checkNotNull(resource("${c.id}.$output")) { "missing golden ${c.id}.$output" }
                compare(c.id, output, golden, write(c).toByteArray(Charsets.UTF_8), divergences, report)
            }
            if (c.json) {
                val golden = checkNotNull(resource("${c.id}.json")) { "missing golden ${c.id}.json" }
                compare(c.id, "json", golden, json(c)?.toByteArray(Charsets.UTF_8), divergences, report)
            }
        }
        for ((output, write) in globalWriters) {
            compare("-", output, checkNotNull(resource(output)) { "missing golden $output" }, write().toByteArray(Charsets.UTF_8), divergences, report)
        }
        return report
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    fun `every writer's output equals upstream's bytes for every case`() {
        val report = runAll(DELIBERATE_DIVERGENCES)
        println("export differential: ${inputs().size} cases, ${report.compared} outputs, ${report.bytes} bytes compared, ${report.mismatches.size} mismatches")
        for ((improvement, seen) in report.allowed) println("  deliberate divergence '$improvement': ${seen.joinToString()}")
        assertTrue(report.compared >= 174, "expected at least 174 compared outputs, got ${report.compared} — FIX THE READER")
        // The generator's own count of the rows it wrote (its stderr line): a reader that dropped a
        // line kind would compare less than it claims.
        val cases = inputs()
        assertEquals(686, cases.sumOf { it.rows.size }, "sample rows read")
        assertEquals(319, cases.sumOf { it.sleep.size + it.daily.size + it.steps.size + it.naps.size + it.temperatures.size + it.evidence.size }, "schema-2 rows read")
        assertEquals(146, cases.sumOf { c -> c.evidence.sumOf { it.second.size } }, "channel traces read")
        assertEquals(5, cases.count { it.meta != null }, "metadata blocks read")
        assertEquals(16, cases.sumOf { it.sessions.size }, "sessions read")
        assertEquals(57, cases.sumOf { c -> c.sessions.sumOf { it.segments.size } }, "hypnogram segments read")
        assertEquals(5, cases.sumOf { it.archives.size }, "epoch archives read")
        // Both kinds of archive are compared: complete and missing epochs from the evidence.
        assertTrue(cases.flatMap { it.archives }.map { it.coverage.isComplete }.toSet() == setOf(true, false), "archives complete and incomplete")
        assertTrue(report.mismatches.isEmpty(), "${report.mismatches.size} output(s) differ from upstream:\n" + report.mismatches.joinToString("\n"))
        val stale = staleEntries(report, DELIBERATE_DIVERGENCES)
        assertTrue(stale.isEmpty(), "listed deliberate divergence(s) no longer diverge — remove them:\n" + stale.joinToString("\n"))
    }

    @Test
    fun `where upstream's toJSON cannot serialize a value the port returns null and the CSV still writes`() {
        val csvOnly = inputs().filter { !it.json }
        assertTrue(csvOnly.isNotEmpty(), "the generator wrote no non-finite case")
        for (c in csvOnly) {
            assertTrue(c.doubles.any { !it.isFinite() }, "${c.id} is marked csvonly but every value is finite")
            assertNull(json(c), c.id)
            assertNull(resource("${c.id}.json"), "${c.id} has a JSON golden although upstream cannot write one")
        }
    }

    @Test
    fun `JSON keys sort in the order JSONSerialization wrote them`() {
        val lines = text("keyorder.txt")
        val v = lines.indexOf("vocabulary")
        val r = lines.indexOf("random")
        assertTrue(v == 0 && r > v, "keyorder.txt sections not found")
        val vocabulary = lines.subList(v + 1, r).map(::hexString)
        val random = lines.subList(r + 1, lines.size).map(::hexString)
        assertEquals(173, vocabulary.size)
        assertEquals(2_000, random.size)
        for ((name, foundation) in listOf("vocabulary" to vocabulary, "random" to random)) {
            for (seed in 1L..3L) {
                assertEquals(foundation, foundation.shuffled(java.util.Random(seed)).sortedWith(FoundationText.JSON_KEY_ORDER), "$name, shuffle $seed")
            }
        }
    }

    /**
     * The key-order golden covers EVERY key the export writes: the keys of every JSON golden (the
     * generator checks the same on its side) and of the port's own output for every case are all in the
     * vocabulary, and every vocabulary key is written somewhere — so the order check above is not a
     * sample of the keys but all of them.
     */
    @Test
    fun `the key-order vocabulary is exactly the set of keys the JSON goldens hold`() {
        fun keys(v: ReplayJson.Value?, into: MutableSet<String>) {
            val o = v?.asObject()
            if (o != null) for (k in o.keys) { into += k; keys(o[k], into) } else v?.asArray()?.forEach { keys(it, into) }
        }
        val golden = mutableSetOf<String>()
        val port = mutableSetOf<String>()
        for (c in inputs().filter { it.json }) {
            keys(ExportJsonReader.root(String(checkNotNull(resource("${c.id}.json")), Charsets.UTF_8)), golden)
            keys(ExportJsonReader.root(checkNotNull(json(c))), port)
        }
        val lines = text("keyorder.txt")
        val vocabulary = lines.subList(lines.indexOf("vocabulary") + 1, lines.indexOf("random")).map(::hexString).toSet()
        assertEquals(vocabulary, golden, "keys written by upstream vs the key-order vocabulary")
        assertEquals(golden, port, "keys written by the port vs upstream")
    }

    /**
     * 10 000 seeded finite doubles through both number paths, against the text upstream's own code wrote
     * for each: the JSON writer (`JSONSerialization`'s `%.17g`-shaped text) and the samples CSV's value
     * column (`%.0f` for a whole value, Swift's `String(Double)` otherwise).
     */
    @Test
    fun `every swept double prints upstream's JSON and CSV text`() {
        val lines = text("number-sweep.txt").map { it.split(' ') }
        assertEquals(10_000, lines.size)
        val values = lines.map { double(it[0]) }
        assertTrue(values.all { it.isFinite() })
        val csv = ExportEngine.samplesCSV(values.map { SampleRow("x", Instant.EPOCH, Instant.EPOCH, it) }).split('\n').drop(1).map { it.substringAfterLast(',') }
        val mismatches = mutableListOf<String>()
        for ((k, f) in lines.withIndex()) {
            check(f.size == 3) { "bad sweep line ${k + 2}" }
            val pretty = checkNotNull(ExportJson.pretty(ExportJson.arr(listOf(ExportJson.JDouble(values[k])))))
            val json = pretty.removePrefix("[\n  ").removeSuffix("\n]")
            if (json != f[1]) mismatches += "${f[0]} json: upstream ${f[1]}, port $json"
            if (csv[k] != f[2]) mismatches += "${f[0]} csv: upstream ${f[2]}, port ${csv[k]}"
        }
        println("export number sweep: ${lines.size} doubles, ${mismatches.size} mismatches")
        assertTrue(mismatches.isEmpty(), "${mismatches.size} swept text(s) differ:\n" + mismatches.take(20).joinToString("\n"))
    }

    @Test
    fun `the golden directory holds exactly the files the cases name`() {
        val root = assertNotNull(System.getProperty("opencircuit.androidRoot"), "system property opencircuit.androidRoot is not set — see ringkit/build.gradle.kts")
        val dir = File(root, "ringkit/src/test/resources/export-differential")
        val present = assertNotNull(dir.list(), "no directory $dir").toSet()
        val expected = setOf("inputs.txt", "keyorder.txt", "number-sweep.txt") + globalWriters.map { it.first } +
            inputs().flatMap { c -> outputs(c).map { "${c.id}.${it.first}" } + listOfNotNull(if (c.json) "${c.id}.json" else null) }
        assertEquals(expected, present, "stale or missing goldens — regenerate with regenerate.sh export")
    }

    @Test
    fun `every JSON golden reads back as an export with the rows it was written from`() {
        for (c in inputs().filter { it.json }) {
            val root = ExportJsonReader.root(String(checkNotNull(resource("${c.id}.json")), Charsets.UTF_8))
            assertEquals(3L, root.long("schemaVersion"), c.id)
            val samples = assertNotNull(root.array("samples"), c.id)
            assertEquals(c.rows.map { it.kind }, samples.map { it.asObject()?.string("kind") }, c.id)
            assertEquals(c.rows.size, samples.size, c.id)
            for ((row, s) in c.rows.zip(samples)) {
                val back = assertNotNull(s.asObject()?.double("value"), "${c.id} ${row.kind}")
                assertTrue(back == row.value, "${c.id}: ${row.value} read back as $back")
            }
            val sizes = listOf("sleep", "daily", "stepSamples", "naps", "daytimeTemperatures", "historySyncEvidence").map { root.array(it)?.size }
            assertEquals(listOf(c.sleep.size, c.daily.size, c.steps.size, c.naps.size, c.temperatures.size, c.evidence.size), sizes, c.id)
            val sleepBack = assertNotNull(root["sleep"]?.asObjectList(), c.id)
            for ((row, s) in c.sleep.zip(sleepBack)) {
                assertTrue(s.double("efficiency") == row.efficiency && s.double("skinTempC") == row.skinTempC, "${c.id}: $row")
                assertEquals(ExportEngine.dayStamp(row.night, c.zone), s.string("night"), c.id)
            }
            val traces = assertNotNull(root["historySyncEvidence"]?.asObjectList(), c.id).map { it.array("channels")?.size }
            assertEquals(c.evidence.map { it.second.size }, traces, c.id)
            assertEquals(c.meta != null, root.has("meta"), c.id)
            assertEquals(c.sessions.map { s -> hexString(s.head[1]) }, root["sleepSessions"]?.asObjectList()?.map { it.string("sessionID") } ?: emptyList<String>(), c.id)
        }
    }

    /**
     * Every metadata block the generator wrote declares the zone and the offset Foundation PRINTED for
     * its export instant (read back from upstream's own timestamp), so `ExportMetadata.of` must derive
     * exactly those from the case's zone — including half a millisecond before a DST change.
     */
    @Test
    fun `ExportMetadata of declares the zone and offset upstream printed`() {
        val withMeta = inputs().filter { it.meta != null }
        assertEquals(5, withMeta.size)
        for (c in withMeta) {
            val meta = checkNotNull(c.meta)
            val derived = ExportMetadata.of(
                c.zone, exportedAt = meta.exportedAt, rangeStart = meta.rangeStart, rangeEnd = meta.rangeEnd,
                appVersion = meta.appVersion, appBuild = meta.appBuild, deviceModel = meta.deviceModel, osVersion = meta.osVersion,
                ringModel = meta.ringModel, ringFirmware = meta.ringFirmware, ringGeneration = meta.ringGeneration, ringIdentifier = meta.ringIdentifier,
            )
            assertEquals(meta, derived, c.id)
        }
    }

    @Test
    fun `the comparison fails on a one-byte difference, on a missing output and on a stale entry`() {
        val report = Report()
        val golden = "kind,start\nhr,1".toByteArray()
        compare("c", "samples.csv", golden, "kind,start\nhr,2".toByteArray(), emptyMap(), report)
        compare("c", "json", golden, null, emptyMap(), report)
        compare("d", "json", golden, golden.copyOf(), emptyMap(), report)
        assertEquals(2, report.mismatches.size, report.mismatches.joinToString("\n"))
        assertTrue(report.mismatches[0].contains("first difference at byte 14"), report.mismatches[0])
        val listed = mapOf("D-0 example" to setOf(Divergence("c", "samples.csv"), Divergence("d", "json")))
        val withList = Report()
        compare("c", "samples.csv", golden, "kind,start\nhr,2".toByteArray(), listed, withList)
        compare("d", "json", golden, golden.copyOf(), listed, withList)
        assertTrue(withList.mismatches.isEmpty())
        assertEquals(listOf("D-0 example: ${Divergence("d", "json")}"), staleEntries(withList, listed))
    }
}
