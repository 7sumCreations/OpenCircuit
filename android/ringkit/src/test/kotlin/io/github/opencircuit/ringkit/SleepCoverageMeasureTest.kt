package io.github.opencircuit.ringkit

import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * COVERAGE MEASUREMENT — score `SleepConfidence.assess` against every staged night of a corpus and
 * print the before/after scoreboard in one run. Staging comes from `SleepReplay.measure`.
 *
 * HOW THE ACQUISITION EVIDENCE IS BUILT, and the one place it differs from a device. A corpus night
 * is a fixed slice of ONE capture artifact; the device's store is continuous. Two consequences:
 * ABSENCE PROVES NOTHING PAST THE ARTIFACT — a 12 h HORIZON encodes that (past it the edge is
 * withheld and the classifier answers "unknown"), so the firing rates are LOWER BOUNDS; and A RESUME
 * ONLY COUNTS IF IT IS BRACKETED INSIDE ONE ARTIFACT — upstream once asked the ring-wide union "did
 * the stream resume?" and got a measured false positive that was only the owner exporting a second
 * file later. The ring-wide union is still built, purely to LABEL a withheld edge `XFILE(…)`.
 *
 * Runs on the COVERAGE corpus variable (`SleepReplay.Corpus.COVERAGE`); unset, it SKIPS.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepCoverageMeasureTests.swift
 * (@ b1c2fdd) — its 1 test.
 */
class SleepCoverageMeasureTest {

    private class Row(
        val id: String,
        val gen: String,
        val labelled: Boolean,
        val worstErrMin: Double?,
        val inBedMin: Long,
        val efficiency: Double,
        /** Totals straight from `SleepStaging.summary` — never rebuilt from rounded minutes. */
        val asleep: Duration,
        val inBed: Duration,
        val coverage: SleepConfidence.Coverage,
        /** Distance to the nearest neighbour IN THE NIGHT'S OWN ARTIFACT; null = none in this file. */
        val holeBefore: Double?,
        val holeAfter: Double?,
        /** Distance to the nearest neighbour anywhere in the ring's corpus, only when the own artifact has none. Reported, never scored. */
        val crossFileBefore: Double?,
        val crossFileAfter: Double?,
        val legacy: SleepConfidence.Level,
    ) {
        val asleepSec: Double get() = SleepStaging.seconds(asleep)
        val inBedSec: Double get() = SleepStaging.seconds(inBed)
    }

    @Test
    fun measureCoverageAcrossCorpus() {
        val dir = SleepReplay.requireCorpus(
            SleepReplay.Corpus.COVERAGE,
            purpose = "the acquisition-coverage scoreboard (SleepCoverageMeasureTest)",
            consequence = "The firing rates, the labelled TP/FP cross-tab and the threshold sweep were all NOT produced, so none of them may be quoted from this run.",
        )
        val rawRows = SleepReplay.rawManifestRows(dir) ?: fail("manifest.json has no `nights` array")
        val nights = SleepReplay.loadManifest(dir)
        assertEquals(rawRows.size, nights.size, "manifest row count changed under us")

        // --- record instants, indexed BY ARTIFACT (the evidence) and ring-wide (the label only)
        val byFile = HashMap<String, MutableList<Instant>>()
        val union = HashMap<String, MutableList<Instant>>()
        for (rawRow in rawRows) {
            val file = rawRow.string("recordsFile")
            val ring = rawRow.string("ringId")
            if (file.isNullOrEmpty() || ring == null) continue
            val data = SleepReplay.recordsBytes(File(dir, file)) ?: fail("bad base64 in $file")
            val instants = EpochArchive.decode(data).map { it.date() }
            byFile.getOrPut(file) { mutableListOf() } += instants
            union.getOrPut(ring) { mutableListOf() } += instants
        }
        val ownIndex = byFile.mapValues { (_, v) -> v.toSortedSet().toList() }
        val ringIndex = union.mapValues { (_, v) -> v.toSortedSet().toList() }

        // --- one row per staged night
        val rows = mutableListOf<Row>()
        for ((n, rawRow) in nights.zip(rawRows)) {
            if (n.recordsFile.isEmpty()) continue
            val r = SleepReplay.measure(n, dir)
            val start = r.inBedStart ?: continue
            val end = r.inBedEnd ?: continue
            val ring = rawRow.string("ringId") ?: fail("no ring union for ${n.id}")
            val ringWide = ringIndex[ring] ?: fail("no ring union for ${n.id}")
            val own = ownIndex[n.recordsFile] ?: fail("no per-artifact index for ${n.id} (${n.recordsFile})")

            // Nearest record strictly outside each edge, IN THE NIGHT'S OWN ARTIFACT, withheld past the horizon.
            val before = own.lastOrNull { it < start }
            val after = own.firstOrNull { it > end }
            val holeBefore = before?.let { SleepReplay.secondsBetween(it, start) }
            val holeAfter = after?.let { SleepReplay.secondsBetween(end, it) }
            val usableBefore = if (holeBefore != null && holeBefore <= HORIZON) before else null
            val usableAfter = if (holeAfter != null && holeAfter <= HORIZON) after else null

            // Reported only, so a withheld edge shows WHY it was withheld.
            val crossFileBefore = if (before == null) ringWide.lastOrNull { it < start }?.let { SleepReplay.secondsBetween(it, start) } else null
            val crossFileAfter = if (after == null) ringWide.firstOrNull { it > end }?.let { SleepReplay.secondsBetween(end, it) } else null

            val coverage = SleepConfidence.Coverage(
                inBedStart = start,
                inBedEnd = end,
                lastMeasurementBeforeStart = usableBefore,
                firstMeasurementAfterEnd = usableAfter,
                // The run that starts at the in-bed end, withheld past the horizon like the single instant is.
                measurementsAfterEnd = if (usableAfter == null) emptyList() else own.filter { it > end && SleepReplay.secondsBetween(end, it) <= HORIZON },
                // Withheld together with the predecessor: an undeterminable leading edge must read unknown.
                earliestRetainedMeasurement = if (usableBefore == null) null else own.firstOrNull(),
            )

            val labelled = rawRow.bool("isLabelled") ?: false
            val labStart = SleepReplay.date(rawRow.string("editedInBedStart"))
            val labEnd = SleepReplay.date(rawRow.string("editedInBedEnd"))
            fun errMin(a: Instant?, b: Instant?): Double? = if (a == null || b == null) null else roundHalfAwayFromZero(SleepReplay.secondsBetween(b, a) / 60)
            val errs = listOfNotNull(errMin(start, labStart), errMin(end, labEnd), errMin(r.onset, n.label?.onset), errMin(r.wake, n.label?.wake))
            val summary = SleepStaging.summary(r.segments)
            rows += Row(
                id = n.id, gen = rawRow.string("ringGeneration") ?: "?", labelled = labelled,
                worstErrMin = errs.maxOfOrNull { abs(it) }, inBedMin = r.inBedMin, efficiency = r.efficiency,
                asleep = summary.totalAsleep, inBed = summary.inBed, coverage = coverage, holeBefore = holeBefore, holeAfter = holeAfter,
                crossFileBefore = crossFileBefore, crossFileAfter = crossFileAfter, legacy = SleepConfidence.classify(summary),
            )
        }
        rows.sortBy { it.id }
        assertFalse(rows.isEmpty(), "nothing staged — the measurement would be vacuous")

        fun assess(row: Row, cut: Double): SleepConfidence.Assessment =
            SleepConfidence.assess(row.asleepSec, row.inBedSec, row.coverage, materialGapSeconds = cut)
        val material = WakeProvenance.MATERIAL_GAP_SECONDS

        // ---------------------------------------------------------------- report
        println("\n=== SLEEP COVERAGE — SleepConfidence.assess over ${dir.path}")
        println(
            "=== ${rows.size} staged nights · materialGapSeconds ${material.toLong()} · continuousTolerance " +
                "${WakeProvenance.CONTINUOUS_TOLERANCE_SECONDS.toLong()} · horizon ${(HORIZON / 3600).toLong()} h",
        )

        // ---- TABLE 0: the number `WakeProvenance.MATERIAL_GAP_SECONDS`' documentation quotes.
        val evidencedAfterGaps = rows.mapNotNull { it.holeAfter }.filter { it <= HORIZON }.sorted()
        val withheldXFile = rows.filter { it.holeAfter == null && it.crossFileAfter != null }
        println("\n--- TABLE 0: sorted SAME-ARTIFACT gaps after the in-bed end (minutes), n=${evidencedAfterGaps.size}")
        println("    " + evidencedAfterGaps.joinToString(" · ") { SleepReplay.fixed(it / 60, 1) })
        println(
            "    withheld as cross-file (reported, never scored): ${withheldXFile.size}  [" +
                withheldXFile.joinToString(", ") { "${it.id} @${SleepReplay.fixed((it.crossFileAfter ?: 0.0) / 60, 1)}m" } + "]",
        )

        println("\n--- TABLE 1: every staged night")
        println(
            pad("night", 16) + pad("gen", 11) + pad("lab", 4) + pad("worstErr", 9) + pad("inBed", 6) + pad("eff", 7) +
                pad("holeBefore", 11) + pad("holeAfter", 11) + pad("bedtime", 13) + pad("wake", 13) + pad("legacy", 10) + "reasons",
        )
        for (row in rows) {
            val a = SleepConfidence.assess(row.asleepSec, row.inBedSec, row.coverage)
            println(
                pad(row.id, 16) + pad(row.gen, 11) + pad(if (row.labelled) "Y" else "·", 4) +
                    pad(row.worstErrMin?.let { SleepReplay.fixed(it, 0) } ?: "—", 9) + pad(row.inBedMin.toString(), 6) +
                    pad(SleepReplay.fixed(row.efficiency, 4), 7) + pad(mins(row.holeBefore, row.crossFileBefore), 11) +
                    pad(mins(row.holeAfter, row.crossFileAfter), 11) + pad(short(a.bedtime), 13) + pad(short(a.wake), 13) +
                    pad(if (row.legacy == SleepConfidence.Level.DURATION_LIKELY_HIGH) "HIGH" else "·", 10) +
                    (if (a.reasons.isEmpty()) "—" else a.reasons.joinToString(" + ") { name(it) }),
            )
        }

        // ---- BEFORE / AFTER counts
        val before = rows.filter { it.legacy == SleepConfidence.Level.DURATION_LIKELY_HIGH }
        val after = rows.filter { assess(it, material).flags }
        val acq = rows.filter { assess(it, material).hasAcquisitionReason }
        println("\n--- TABLE 2: how many nights say something")
        println("BEFORE  SleepConfidence.classify == DURATION_LIKELY_HIGH : ${before.size}/${rows.size}  [${before.joinToString(", ") { it.id }}]")
        println("AFTER   assess().flags (any reason at all)              : ${after.size}/${rows.size}  [${after.joinToString(", ") { it.id }}]")
        println("        …of which ACQUISITION reasons                   : ${acq.size}/${rows.size}  [${acq.joinToString(", ") { it.id }}]")

        // ---- the RETIRED two-hint card, re-run for comparison (the shipped card renders assess().reasons).
        val cardDuration = mutableListOf<String>()
        val cardDurationPreGate = mutableListOf<String>()
        val cardBedtime = mutableListOf<String>()
        val truncated = mutableListOf<String>()
        val nonContiguous = mutableListOf<String>()
        for (row in rows) {
            val a = assess(row, material)
            val wallSpan = SleepReplay.secondsBetween(row.coverage.inBedStart, row.coverage.inBedEnd)
            val contiguous = row.inBedSec <= 0 || wallSpan <= row.inBedSec * 1.15
            // No corpus night carries a sleep schedule — the device state for any user who never set one.
            val isTruncated = SleepCaptureCoverage.classify(
                capturedOnset = row.coverage.inBedStart, capturedInBed = row.inBed, scheduledBedtime = null,
            ) == SleepCaptureCoverage.Coverage.LIKELY_TRUNCATED
            if (isTruncated) truncated += row.id
            if (!contiguous) nonContiguous += row.id
            if (contiguous && !isTruncated && row.legacy == SleepConfidence.Level.DURATION_LIKELY_HIGH) {
                cardDurationPreGate += row.id
                if (a.wake == WakeProvenance.Verdict.Witnessed) cardDuration += row.id
            }
            if (!isTruncated && a.bedtime == BedtimeProvenance.Verdict.NoPriorMeasurement) cardBedtime += row.id
            if (!isTruncated && a.bedtime is BedtimeProvenance.Verdict.ResumedAfterGap) cardBedtime += row.id
        }
        val cardToday = cardDuration.toSet() + cardBedtime
        println("\nCARD BEFORE 2026-09-01 (the retired two-hint card, re-run for comparison):")
        println("  confidenceHint (pre wake-gate) : ${cardDurationPreGate.size}/${rows.size}  [${cardDurationPreGate.joinToString(", ")}]")
        println("  confidenceHint fires        : ${cardDuration.size}/${rows.size}  [${cardDuration.joinToString(", ")}]")
        println("    …suppressed by wake != witnessed : ${(cardDurationPreGate.toSet() - cardDuration.toSet()).sorted().joinToString(", ")}")
        println("  bedtimeProvenanceHint fires : ${cardBedtime.size}/${rows.size}  [${cardBedtime.sorted().joinToString(", ")}]")
        println("  SleepCaptureCoverage == LIKELY_TRUNCATED : ${truncated.size}/${rows.size}")
        println("  contiguity guard fails      : ${nonContiguous.size}/${rows.size}")
        println("  ⇒ SOME caveat, retired card : ${cardToday.size}/${rows.size}")
        println("  ⇒ SOME caveat, SHIPPED card (renders assess().reasons) : ${after.size}/${rows.size}")
        val doubled = rows.filter { row -> assess(row, material).reasons.any { it is SleepConfidence.Reason.NoRecordingBeforeBedtime } }
            .map { it.id }.toSet().intersect(cardBedtime.toSet())
        println("  ⚠️ nights the retired bedtime hint would DOUBLE the front-edge reason: ${doubled.size}  [${doubled.sorted().joinToString(", ")}]")

        // ---- labelled cross-tab
        println("\n--- TABLE 3: the labelled nights (BAD = worst edge error >= ${BAD_EDGE_MINUTES.toLong()} min)")
        println(pad("night", 16) + pad("worstErr", 9) + pad("verdict", 9) + pad("BEFORE", 8) + pad("AFTER-acq", 11) + "AFTER reasons")
        var tp = 0
        var fp = 0
        var fn = 0
        var beforeTP = 0
        var beforeFP = 0
        for (row in rows.filter { it.labelled }) {
            val err = row.worstErrMin ?: continue
            val bad = err >= BAD_EDGE_MINUTES
            val a = assess(row, material)
            val fires = a.hasAcquisitionReason
            when {
                bad && fires -> tp += 1
                !bad && fires -> fp += 1
                bad -> fn += 1
            }
            if (row.legacy == SleepConfidence.Level.DURATION_LIKELY_HIGH) {
                if (bad) beforeTP += 1 else beforeFP += 1
            }
            println(
                pad(row.id, 16) + pad(SleepReplay.fixed(err, 0), 9) + pad(if (bad) "BAD" else "GOOD", 9) +
                    pad(if (row.legacy == SleepConfidence.Level.DURATION_LIKELY_HIGH) "HIGH" else "·", 8) +
                    pad(if (fires) "FIRES" else "·", 11) + (if (a.reasons.isEmpty()) "—" else a.reasons.joinToString(" + ") { name(it) }),
            )
        }
        println("BEFORE (durationLikelyHigh): TP $beforeTP  FP $beforeFP")
        println("AFTER  (acquisition)       : TP $tp  FP $fp  FN $fn")
        println("⚠️  every FP count here is out of ONE good labelled night. It is not a rate.")

        // ---- threshold sweep
        println("\n--- TABLE 4: materialGapSeconds sweep (acquisition firings)")
        println(pad("cut(min)", 10) + pad("fires/N", 10) + pad("lab TP", 8) + pad("lab FP", 8) + "nights")
        for (cutMin in listOf(0.0, 5.0, 6.0, 15.0, 30.0, 33.0, 45.0, 60.0, 120.0, 242.0, 300.0)) {
            val hit = rows.filter { assess(it, cutMin * 60).hasAcquisitionReason }
            val lab = hit.filter { it.labelled }
            val t = lab.count { (it.worstErrMin ?: 0.0) >= BAD_EDGE_MINUTES }
            println(
                pad(SleepReplay.fixed(cutMin, 0), 10) + pad("${hit.size}/${rows.size}", 10) + pad(t.toString(), 8) +
                    pad((lab.size - t).toString(), 8) + hit.joinToString(", ") { it.id },
            )
        }

        // ---- invariants
        for (row in rows) {
            val a = assess(row, material)
            assertEquals(row.legacy, a.level, "${row.id}: coverage moved the legacy duration verdict")
            assertFalse(
                a.hasAcquisitionReason && SleepConfidence.Reason.DurationLikelyHigh in a.reasons,
                "${row.id}: opposite claims offered together",
            )
            assertFalse(assess(row, Double.POSITIVE_INFINITY).hasAcquisitionReason, "${row.id}: kill switch did not silence acquisition")
        }
        // The trap upstream's campaign identified: a rule that fires everywhere is not a rule.
        assertTrue(acq.size < rows.size / 2, "an acquisition flag on half the corpus is noise, not information")

        // A CROSS-FILE resume is an export seam, not an absence, and must never reach a reason.
        for (row in rows.filter { it.holeAfter == null && it.crossFileAfter != null }) {
            val a = assess(row, material)
            assertEquals(
                WakeProvenance.Verdict.Unknown, a.wake,
                "${row.id}: its only post-wake record is in ANOTHER capture artifact (${SleepReplay.fixed((row.crossFileAfter ?: 0.0) / 60, 1)} min away), " +
                    "so the edge is UNEVIDENCED. Scoring it manufactures a gap out of the owner's export schedule.",
            )
            assertFalse(a.reasons.any { it is SleepConfidence.Reason.NoRecordingAfterWake }, "${row.id}: a cross-file resume produced an acquisition reason")
        }
    }

    // MARK: formatting

    private fun pad(s: String, w: Int): String = if (s.length >= w) "$s " else s + " ".repeat(w - s.length)

    /** Minutes, or why the edge carries no evidence, with the RAW distance; `XFILE(…)` is the discarded cross-file resume. */
    private fun mins(t: Double?, crossFile: Double?): String {
        if (t != null) return if (t > HORIZON) "UNDET(${SleepReplay.fixed(t / 3600, 0)}h)" else SleepReplay.fixed(t / 60, 1)
        if (crossFile != null) return if (crossFile > HORIZON) "XFILE(${SleepReplay.fixed(crossFile / 3600, 0)}h)" else "XFILE(${SleepReplay.fixed(crossFile / 60, 0)}m)"
        return "UNDET(none)"
    }

    private fun short(v: WakeProvenance.Verdict): String = when (v) {
        WakeProvenance.Verdict.Witnessed -> "witnessed"
        is WakeProvenance.Verdict.StoppedThenResumed -> "stop ${SleepReplay.fixed(v.seconds / 60, 0)}m"
        WakeProvenance.Verdict.Unknown -> "unknown"
    }

    private fun short(v: BedtimeProvenance.Verdict): String = when (v) {
        BedtimeProvenance.Verdict.Witnessed -> "witnessed"
        is BedtimeProvenance.Verdict.ResumedAfterGap -> "gap ${SleepReplay.fixed(v.seconds / 60, 0)}m"
        BedtimeProvenance.Verdict.NoPriorMeasurement -> "noPrior"
        BedtimeProvenance.Verdict.Unknown -> "unknown"
    }

    private fun name(r: SleepConfidence.Reason): String = when (r) {
        is SleepConfidence.Reason.NoRecordingAfterWake -> "STOPPED@wake(${SleepReplay.fixed(r.silentFor / 60, 0)}m)"
        is SleepConfidence.Reason.NoRecordingBeforeBedtime -> "RESUMED@bed(${SleepReplay.fixed(r.silentFor / 60, 0)}m)"
        SleepConfidence.Reason.DurationLikelyHigh -> "durationLikelyHigh"
    }

    private companion object {
        /** Past this, the next record belongs to a different capture artifact and its absence proves nothing. */
        const val HORIZON: Double = 12.0 * 3600

        /** A night is BAD when its worst measured edge error is at least this (the campaign's cut). */
        const val BAD_EDGE_MINUTES = 60.0
    }
}
