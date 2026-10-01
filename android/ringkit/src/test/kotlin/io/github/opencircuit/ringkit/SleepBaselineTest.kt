package io.github.opencircuit.ringkit

import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * THE PINNED GOLDEN — the only tracked record of the scoreboard hash upstream's sleep campaign
 * quotes, and of the corpus it was measured on.
 *
 * Both values are upstream's, measured on upstream's private tester corpus (real health data, never
 * committed anywhere): the sha256 of its `manifest.json`, and the sha256 of the `baseline.tsv` this
 * emitter produces from it at the shipped default. [SleepBaselineTest.emitBaselineTsv] asserts the
 * baseline hash ONLY when the manifest it just read matches the fingerprint; against any other
 * corpus it prints both hashes and asserts nothing. Upstream also records the same scoreboard with
 * the evening-absorb guard OFF, so the two can never be mistaken for each other again (a gate pinned
 * to the guard-off hash was once green exactly when the feature was off).
 *
 * The Kotlin emitter writes the same columns in the same formats, so on that corpus the hash is a
 * byte-identity proof for this port as well.
 */
object SleepBaselineGolden {
    /** sha256 of `manifest.json` for the corpus the baseline below was measured on. */
    const val CORPUS_MANIFEST_SHA256 = "63a07be8f28714b2c31a410c950dfb9c6f69b899097dccbc5f92f18b41061c0e"

    /** sha256 of the `baseline.tsv` this emitter produces from that corpus AT THE SHIPPED DEFAULT. */
    const val BASELINE_SHA256 = "58fef4b861246576b87a9881fc3ae6e89f04d9c97d5260b5c194232b16b8c6c5"

    /** The same scoreboard with the evening-absorb guard OFF (absorb cut 0). */
    const val BASELINE_SHA256_ABSORB_GUARD_OFF = "ef5dc087a16f0461d14d656d2e3461cc479cceb85ef5d30f5e4dd741eaa13e8f"

    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }
}

/**
 * BASELINE EMITTER — run the replay harness over EVERY row of a corpus and write one
 * machine-readable TSV: the scoreboard every candidate change is scored against. Every column an
 * analysis needs — the manifest's own census fields joined to the staged result — so a question
 * can be answered from a file instead of from a screenshot. Staging comes from `SleepReplay.measure`.
 *
 * SUMMARY-ONLY ROWS ARE EMITTED TOO, with status=summaryOnly and empty staged columns but their
 * STORED columns filled: dropping them would silently narrow the baseline. The status column keeps
 * them out of any "detected" aggregate.
 *
 * Runs on the BASELINE corpus variable (`SleepReplay.Corpus.BASELINE`). Settings (all optional, read
 * through `SleepReplay.setting`): BASELINE_OUT — where to write the TSV (default: `baseline.tsv` in
 * the corpus); ABSORB_CUT — a CANDIDATE `observedGapCoverageCut` (unset = the shipped default, a real
 * measurement, not a skipped one); MOTION_CUT — turn the decoded-magnitude motion channel ON at that
 * active cut (unset = the shipped channel policy).
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepBaselineTests.swift
 * (@ b1c2fdd) — its 1 test, with `SleepBaselineGolden`.
 */
class SleepBaselineTest {

    @Test
    fun emitBaselineTsv() {
        val dir = SleepReplay.requireCorpus(
            SleepReplay.Corpus.BASELINE,
            purpose = "the scoreboard emitter (SleepBaselineTest)",
            consequence = "No baseline.tsv was written, so any sha256 you were about to quote as a byte-identity proof would be from a stale file.",
        )
        val outPath = SleepReplay.setting(SleepReplay.Setting.BASELINE_OUT) ?: File(dir, "baseline.tsv").path
        // CANDIDATE A/B. Unset = the shipped default (and the emitted TSV is then the master scoreboard).
        val absorbCut = SleepReplay.setting(SleepReplay.Setting.ABSORB_CUT)?.let { SleepReplay.swiftDouble(it) }
            ?: BulkSleep.OBSERVED_GAP_ABSORB_COVERAGE_CUT
        // CANDIDATE A/B, second axis. Unset = the shipped channel policy (decoded-magnitude channel OFF).
        val motionPolicy = SleepReplay.setting(SleepReplay.Setting.MOTION_CUT)?.let { SleepReplay.swiftInt(it) }
            ?.let { BulkSleep.MotionChannelPolicy(magnitudeChannelEnabled = true, magnitudeActiveCut = it) }
            ?: BulkSleep.MotionChannelPolicy.DEFAULT

        // --- raw manifest, for the census columns the replay model does not carry
        val manifestData = File(dir, "manifest.json").readBytes()
        val manifestHash = SleepBaselineGolden.sha256Hex(manifestData)
        val rawRows = SleepReplay.rawManifestRows(dir) ?: fail("manifest.json has no `nights` array")
        val nights = SleepReplay.loadManifest(dir)
        assertEquals(rawRows.size, nights.size, "manifest row count changed under us")

        val columns = listOf(
            // identity
            "night_id", "ringId", "ringGeneration", "firmware", "night", "timeZone", "appBuilds", "status",
            // input
            "recordsFile", "recordCount", "recordsLoaded", "recordsAfterRetention", "nightScopedRecords",
            "hasNightBytes", "recordCountInRecordedInBed", "recordCoverageOfRecordedInBed",
            "gapsOver6MinInWindow", "gapsOver6MinInRecordedInBed",
            "placeholderMotionShare", "placeholderMotionShareInRecordedInBed",
            "layoutSleepVitals", "layoutActivity",
            // detected (staging, this run)
            "detInBedStart", "detInBedEnd", "detOnset", "detWake",
            "detInBedMin", "detAsleepMin", "detAwakeMin", "detDeepMin", "detRemMin", "detLightMin", "detEfficiency",
            // what the app stored at the time
            "storedInBedStart", "storedInBedEnd", "storedOnset", "storedWake",
            "storedAsleepMin", "storedAwakeMin", "storedEfficiency", "edgePrecision", "dStartVsStored", "dEndVsStored",
            // ground truth
            "isManuallyEdited", "editFlagProvenance", "isLabelled",
            "labelOnset", "labelWake", "labelInBedStart", "labelInBedEnd",
            "errOnset", "errWake", "errInBedStart", "errInBedEnd",
            // flags
            "flagEffOver097", "flagSpanOver12h", "flagStagedNothing",
        )

        val lines = mutableListOf(columns.joinToString("\t"))
        var replayed = 0
        var summaryOnly = 0
        var failed = 0
        val failures = mutableListOf<String>()

        fun isoStr(d: Instant?, zone: ZoneId): String = SleepReplay.iso(d, zone)
        fun signed(v: Long?): String = v?.toString() ?: ""
        fun deltaMin(a: Instant?, b: Instant?): Long? = ReplayResult.delta(a, b)

        for ((n, rawRow) in nights.zip(rawRows)) {
            val rawId = "${rawRow.string("ringId") ?: ""}_${rawRow.string("night") ?: ""}"
            check(n.id == rawId || n.id == (rawRow.string("id") ?: "")) { "manifest row order changed — the census join would be wrong" }
            val zone = n.zone
            var r: ReplayResult? = null
            var status = "replayed"
            if (n.recordsFile.isEmpty()) {
                status = "summaryOnly"
                summaryOnly += 1
            } else {
                try {
                    r = SleepReplay.measure(n, dir, observedGapCoverageCut = absorbCut, motionPolicy = motionPolicy)
                    replayed += 1
                } catch (e: Exception) {
                    status = "loadFailed"
                    failed += 1
                    failures += "${n.id}: $e"
                }
            }

            val labelInBedStart = SleepReplay.date(rawRow.string("editedInBedStart"))
            val labelInBedEnd = SleepReplay.date(rawRow.string("editedInBedEnd"))
            val start = r?.inBedStart
            val end = r?.inBedEnd
            val span = if (start != null && end != null) SleepReplay.secondsBetween(start, end) / 60 else null
            val stagedNothing = r != null && r.inBedStart == null
            val layout = rawRow.obj("layoutCounts")

            val cells = listOf(
                n.id,
                SleepReplay.num(rawRow["ringId"]), SleepReplay.num(rawRow["ringGeneration"]), SleepReplay.num(rawRow["firmware"]),
                SleepReplay.num(rawRow["night"]), SleepReplay.foundationIdentifier(zone),
                rawRow.array("appBuilds")?.joinToString("/") { SleepReplay.describe(it) } ?: "",
                status,

                n.recordsFile, SleepReplay.num(rawRow["recordCount"]),
                r?.recordsLoaded?.toString() ?: "", r?.recordsAfterRetention?.toString() ?: "", r?.nightScopedRecords?.toString() ?: "",
                SleepReplay.num(rawRow["hasNightBytes"]), SleepReplay.num(rawRow["recordCountInRecordedInBed"]),
                SleepReplay.num(rawRow["recordCoverageOfRecordedInBed"]),
                (rawRow.array("gapsOver6MinInWindow")?.size ?: 0).toString(),
                (rawRow.array("gapsOver6MinInRecordedInBed")?.size ?: 0).toString(),
                SleepReplay.num(rawRow["placeholderMotionShare"]), SleepReplay.num(rawRow["placeholderMotionShareInRecordedInBed"]),
                SleepReplay.num(layout?.get("sleepVitals")), SleepReplay.num(layout?.get("activity")),

                isoStr(r?.inBedStart, zone), isoStr(r?.inBedEnd, zone), isoStr(r?.onset, zone), isoStr(r?.wake, zone),
                r?.inBedMin?.toString() ?: "", r?.asleepMin?.toString() ?: "", r?.awakeMin?.toString() ?: "",
                r?.deepMin?.toString() ?: "", r?.remMin?.toString() ?: "", r?.lightMin?.toString() ?: "",
                r?.let { SleepReplay.fixed(it.efficiency, 4) } ?: "",

                isoStr(n.stored.inBedStart, zone), isoStr(n.stored.inBedEnd, zone),
                isoStr(n.stored.sleepOnset, zone), isoStr(n.stored.sleepWake, zone),
                n.stored.asleepMin?.toString() ?: "", n.stored.awakeMin?.toString() ?: "",
                SleepReplay.num(rawRow["efficiency"]), SleepReplay.num(rawRow["edgePrecision"]),
                signed(r?.storedStartDeltaMin), signed(r?.storedEndDeltaMin),

                SleepReplay.num(rawRow["isManuallyEdited"]), SleepReplay.num(rawRow["editFlagProvenance"]),
                SleepReplay.num(rawRow["isLabelled"]),
                isoStr(n.label?.onset, zone), isoStr(n.label?.wake, zone),
                isoStr(labelInBedStart, zone), isoStr(labelInBedEnd, zone),
                signed(deltaMin(r?.onset, n.label?.onset)), signed(deltaMin(r?.wake, n.label?.wake)),
                signed(deltaMin(r?.inBedStart, labelInBedStart)), signed(deltaMin(r?.inBedEnd, labelInBedEnd)),

                r?.let { if (it.efficiency > 0.97) "true" else "false" } ?: "",
                span?.let { if (it > 12 * 60) "true" else "false" } ?: "",
                if (r == null) "" else if (stagedNothing) "true" else "false",
            )
            check(cells.size == columns.size) { "column/cell mismatch on ${n.id}" }
            lines += cells.joinToString("\t") { it.replace("\t", " ") }
        }

        val tsv = lines.joinToString("\n") + "\n"
        File(outPath).writeText(tsv)
        val tsvHash = SleepBaselineGolden.sha256Hex(tsv.toByteArray(Charsets.UTF_8))
        // Both halves are load-bearing: the cut says WHICH staging produced the scoreboard, and the
        // hash is what the pinned golden compares against.
        println(
            "\n=== SLEEP BASELINE — observedGapAbsorbCoverageCut = $absorbCut" +
                (if (absorbCut == BulkSleep.OBSERVED_GAP_ABSORB_COVERAGE_CUT) " (shipped default)" else " (CANDIDATE)") +
                ", corpus ${dir.path}",
        )
        println(
            "=== motion channel = " +
                if (motionPolicy.magnitudeChannelEnabled) "activityMagnitudes @ cut ${motionPolicy.magnitudeActiveCut} (CANDIDATE)"
                else "shipped default (decoded-magnitude channel OFF)",
        )
        println("=== ${nights.size} manifest rows: $replayed replayed, $summaryOnly summary-only, $failed load failures")
        println("=== wrote ${lines.size - 1} rows x ${columns.size} columns -> $outPath")
        println("=== manifest sha256 $manifestHash")
        println("=== baseline sha256 $tsvHash")
        for (f in failures) println("  LOAD FAILURE  $f")
        assertTrue(failures.isEmpty(), "a corpus row failed to load — the baseline would be incomplete")
        assertTrue(replayed > 0, "nothing was replayed at all")

        // THE PINNED COMPARISON. Only meaningful against the corpus the golden was measured on.
        if (manifestHash != SleepBaselineGolden.CORPUS_MANIFEST_SHA256) {
            println("=== golden NOT CHECKED — this is a different corpus.")
            println("===   pinned manifest ${SleepBaselineGolden.CORPUS_MANIFEST_SHA256}")
            println("===   this   manifest $manifestHash")
            println("===   pinned baseline ${SleepBaselineGolden.BASELINE_SHA256} (not comparable)")
            return
        }
        // ⚠️ THE SUCCESS LINE IS GUARDED: a MATCH line printed after a failed comparison once read as
        // a green gate off a red run.
        if (tsvHash != SleepBaselineGolden.BASELINE_SHA256) {
            println("=== golden MOVED — baseline.tsv is NOT the pinned scoreboard. See the failure.")
            fail(
                "THE SCOREBOARD MOVED. This is the pinned corpus (manifest $manifestHash) but the emitted baseline.tsv hashes " +
                    "to $tsvHash, not the pinned ${SleepBaselineGolden.BASELINE_SHA256}. Either a staging number changed — say so " +
                    "and re-pin deliberately — or the emitter's columns changed. Do NOT quote 'hash unchanged' after seeing this.",
            )
        }
        println("=== golden MATCH — baseline.tsv is byte-identical to the pinned scoreboard.")
    }
}
