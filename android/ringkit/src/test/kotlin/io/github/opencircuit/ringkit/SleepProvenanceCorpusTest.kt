package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * MEASURE THE PROVENANCE FIX ON THE WHOLE CORPUS — how many nights change, by how much, and what
 * stops reaching the health store.
 *
 * For every manifest row with raw bytes it stages the night through `SleepReplay.stage`, then — for
 * a row that also carries all three EDIT anchors — runs `SleepEdit.recompute` twice on identical
 * inputs: OFF (`coverage = null`, the kill switch) and ON (`coverage` = the record set), and reports
 * the difference. The OFF arm is load-bearing: the test ASSERTS both arms emit the same spans per
 * stage, so any reported change is a change of LABEL only. If the fix ever moves a boundary, this
 * fails. Coverage is built from the FULL record union, the most generous reading available.
 *
 * Two upstream rows replay as fully invented because their bytes hold NO record inside the app's
 * own in-bed window: `MeasuredCoverage.trusted` refuses to judge a window holding not one record, so
 * both come back with nothing asserted — no list of night ids involved. This asserts it, so deleting
 * the guard turns those rows red instead of quietly re-inflating the headline.
 *
 * Runs on the first SET of the PROVENANCE or BASELINE corpus variables (`SleepReplay.Corpus`).
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepProvenanceCorpusTests.swift
 * (@ b1c2fdd) — its 1 test. Upstream sets the process time zone around each night; here the row's
 * zone is passed explicitly.
 */
class SleepProvenanceCorpusTest {

    @Test
    fun measureProvenanceAcrossTheCorpus() {
        val dir = SleepReplay.requireCorpus(
            anyOf = listOf(SleepReplay.Corpus.PROVENANCE, SleepReplay.Corpus.BASELINE),
            purpose = "the corpus-wide measurement of how much stored sleep is user-asserted rather than measured",
            consequence = "The blast radius of the provenance change was NOT measured on this run.",
        )
        val rawRows = SleepReplay.rawManifestRows(dir) ?: fail("manifest.json has no `nights` array")
        val manifest = SleepReplay.loadManifest(dir)

        println("\n=== SLEEP PROVENANCE — corpus ${dir.path}")
        println(
            "=== ${rawRows.size} manifest rows; coverage from the FULL record union, epoch " +
                "${MeasuredCoverage.DEFAULT_EPOCH_SECONDS.seconds} s\n",
        )

        var withBytes = 0
        var staged = 0
        var replayableEdits = 0
        var nightsChanged = 0
        var totalAssertedAsleepMin = 0.0
        var totalAssertedAwakeMin = 0.0
        var totalUnknownAsleepMin = 0.0
        var totalUnknownAwakeMin = 0.0
        val untrustedNights = mutableListOf<String>()
        // LABELS, NOT RECORDS: staging emits only measured segments, so this is 0.0 by construction and
        // says nothing about interior holes in ordinary staging. Recorded so the claim is measured.
        var ordinaryPathLabelledAssertedAsleepMin = 0.0
        val ids = mutableListOf<String>()

        val lines = mutableListOf(
            SleepReplay.pad("night", 16) + " " + "cover".padStart(7) + " " + "displayed".padStart(8) + " " + "measured".padStart(9) + " " +
                "asserted".padStart(9) + " " + "unknown".padStart(8) + " " + "effOFF".padStart(8) + " " + "effON".padStart(8) + " " +
                "score".padStart(8),
        )

        for (r in rawRows) {
            val id = r.string("id") ?: "${r.string("ringId") ?: ""}_${r.string("night") ?: ""}"
            ids += id
            val recordsFile = r.string("recordsFile") ?: r.string("records") ?: ""
            if (recordsFile.isEmpty()) continue
            withBytes += 1
            val night = manifest.firstOrNull { it.id == id } ?: continue
            val records = SleepReplay.loadRecords(night, dir)
            if (records.isEmpty()) continue
            val zone = SleepReplay.rowZone(r) ?: ZoneOffset.UTC
            val coverage = MeasuredCoverage.ofRecords(records)

            val base = SleepReplay.stage(records, zone, night.temperatureSamples(), night.deepHRBaselineBPM).segments
            if (base.isNotEmpty()) {
                staged += 1
                ordinaryPathLabelledAssertedAsleepMin += SleepProvenanceBreakdown(base).assertedAsleep / 60
            }

            // --- EDIT PATH
            val b = SleepReplay.date(r.string("editedInBedStart"))
            val o = SleepReplay.date(r.string("editedOnset"))
            val w = SleepReplay.date(r.string("editedWake"))
            if (r.bool("isManuallyEdited") != true || b == null || o == null || w == null || !(w > o) || o < b) continue
            replayableEdits += 1

            val times = SleepEdit.Times(b, o, w)
            val off = SleepEdit.recompute(base, times, coverage = null)
            val on = SleepEdit.recompute(base, times, coverage = coverage)

            // THE LOAD-BEARING ASSERTION: the fix relabels, it never re-shapes. The ON arm splits a fill
            // into pieces, so segment counts legitimately differ, but not one second may move between stages.
            fun spanByStage(segs: List<SleepSegment>): Map<SleepStage, Duration> =
                segs.groupBy { it.stage }.mapValues { (_, v) -> v.fold(Duration.ZERO) { acc, s -> acc.plus(s.duration) } }
            val sOff = spanByStage(off)
            val sOn = spanByStage(on)
            assertEquals(sOff.keys, sOn.keys, "$id: a stage appeared or vanished")
            for ((stage, span) in sOff) {
                assertEquals(
                    SleepStaging.seconds(span), sOn[stage]?.let { SleepStaging.seconds(it) } ?: -1.0, 0.001,
                    "$id: $stage moved — the fix must relabel, not re-shape",
                )
            }
            assertEquals(off.minOfOrNull { it.start }, on.minOfOrNull { it.start }, "$id: night start moved")
            assertEquals(off.maxOfOrNull { it.end }, on.maxOfOrNull { it.end }, "$id: night end moved")

            val bOff = SleepProvenanceBreakdown(off)
            val bOn = SleepProvenanceBreakdown(on)
            val assertedAsleepMin = bOn.assertedAsleep / 60
            val assertedAwakeMin = bOn.assertedAwake / 60
            totalUnknownAsleepMin += bOn.unknownAsleep / 60
            totalUnknownAwakeMin += bOn.unknownAwake / 60

            // THE GUARD, OBSERVED RATHER THAN ASSUMED: a window holding no record comes back untrusted,
            // and recompute then reproduces the kill-switch arm exactly.
            if (coverage.trusted(DateInterval(times.inBedStart, times.inBedEnd)) == null) {
                untrustedNights += id
                assertEquals(off, on, "$id: coverage we cannot trust must behave as master")
                assertFalse(bOn.hasAssertedTime, "$id: an untrustworthy read must assert nothing")
            }
            if (bOn.hasAssertedTime) {
                nightsChanged += 1
                totalAssertedAsleepMin += assertedAsleepMin
                totalAssertedAwakeMin += assertedAwakeMin
            }

            fun eff(br: SleepProvenanceBreakdown): String = br.efficiency?.let { SleepReplay.fixed(it, 4) } ?: "withheld"
            lines += SleepReplay.pad(id, 16) + " " + SleepReplay.fixed(bOn.coverageFraction, 3, width = 6) + " " +
                SleepReplay.fixed(bOn.displayedAsleep / 60, 1, width = 8) + " " + SleepReplay.fixed(bOn.measuredAsleep / 60, 1, width = 9) + " " +
                SleepReplay.fixed(assertedAsleepMin, 1, width = 9) + " " + SleepReplay.fixed(bOn.unknownAsleep / 60, 1, width = 8) + " " +
                eff(bOff).padStart(8) + " " + eff(bOn).padStart(8) + " " + (if (bOn.isScorable) "keep" else "withheld").padStart(8) +
                if (id in BYTES_DISAGREE_WITH_THE_PHONE) "   bytes≠phone" else ""
        }

        for (l in lines) println(l)
        println(
            """

            --- corpus shape
            rows with raw bytes ............ $withBytes
            of those, staged a night ....... $staged
            replayable edits (3 anchors) ... $replayableEdits

            --- what changes
            nights carrying ANY asserted time ......... $nightsChanged of $withBytes
            ordinary staging path, LABELLED asserted .. ${SleepReplay.fixed(ordinaryPathLabelledAssertedAsleepMin, 1)} asleep-min TOTAL
              ⚠️ LABELS, NOT RECORDS: staging emits only measured segments, so this is 0.0 by construction.

            EDIT path asserted ASLEEP (proven holes) .. ${SleepReplay.fixed(totalAssertedAsleepMin, 1)} min = ${SleepReplay.fixed(totalAssertedAsleepMin / 60, 2)} h
            EDIT path asserted AWAKE  (proven holes) .. ${SleepReplay.fixed(totalAssertedAwakeMin, 1)} min
            EDIT path UNKNOWN asleep (unprovable) ..... ${SleepReplay.fixed(totalUnknownAsleepMin, 1)} min — published, as before
            EDIT path UNKNOWN awake  (unprovable) ..... ${SleepReplay.fixed(totalUnknownAwakeMin, 1)} min

            --- the retention guard
            nights whose record set could not be trusted at all: ${if (untrustedNights.isEmpty()) "none" else untrustedNights.joinToString(", ")}
            Each behaves exactly as the kill-switch arm (asserted 0.0, nothing withheld, nothing deleted).
            """.trimIndent(),
        )

        assertTrue(withBytes > 0, "nothing was replayed at all")

        // THE GUARD IS LOAD-BEARING, AND THESE TWO ROWS ARE THE PROOF.
        for (id in BYTES_DISAGREE_WITH_THE_PHONE.filter { it in ids }) {
            assertTrue(id in untrustedNights, "$id must be reclaimed by the guard, not by a list")
        }
    }

    private companion object {
        /**
         * Rows whose corpus BYTES provably are not what the phone staged from — kept ONLY to assert
         * that the production guard reclaims them without being told which nights they are.
         */
        val BYTES_DISAGREE_WITH_THE_PHONE = setOf("R1_2026-08-14", "R1_2026-08-15")
    }
}
