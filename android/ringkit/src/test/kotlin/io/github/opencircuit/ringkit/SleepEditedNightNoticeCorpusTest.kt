package io.github.opencircuit.ringkit

import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * HOW OFTEN DOES THE EDITED-NIGHT LINE FIRE ACROSS THE WHOLE CORPUS — and is it silent everywhere it
 * should be?
 *
 * The line makes no prediction, so this is a BLAST-RADIUS measurement, not an accuracy one: how many
 * nights see it, and can it appear on a night nobody edited. The second question is answered by
 * CONSTRUCTION as well as by count: staging emits measured segments only, so an unedited night's
 * breakdown asserts nothing and the line is null before the manual-edit gate is even reached. This
 * asserts that on every staged night, then re-checks the edited ones.
 *
 * Two upstream corpus rows are measured and NOT counted (printed under DISCOUNTED): their record
 * files hold no records inside the app's own recorded in-bed window while the app reported full
 * coverage, so the corpus input provably is not what the phone staged from.
 *
 * Runs on the first SET of the NOTICE, PROVENANCE or BASELINE corpus variables (`SleepReplay.Corpus`).
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepEditedNightNoticeCorpusTests.swift
 * (@ b1c2fdd) — its 1 test. Upstream sets the process time zone around each night; here the row's
 * zone is passed explicitly.
 */
class SleepEditedNightNoticeCorpusTest {

    @Test
    fun measureHowManyCorpusNightsShowTheEditedNightLine() {
        val dir = SleepReplay.requireCorpus(
            anyOf = listOf(SleepReplay.Corpus.NOTICE, SleepReplay.Corpus.PROVENANCE, SleepReplay.Corpus.BASELINE),
            purpose = "the count of corpus nights that would show the edited-night notice",
            consequence = "How often the new card line appears was NOT measured on this run.",
        )
        val rawRows = SleepReplay.rawManifestRows(dir) ?: fail("manifest.json has no `nights` array")
        val manifest = SleepReplay.loadManifest(dir)

        var withBytes = 0
        var stagedNights = 0
        var editedNights = 0
        var fires = 0
        var firesDiscounted = 0
        val silentEdited = mutableListOf<String>()
        val lines = mutableListOf<String>()

        for (r in rawRows) {
            val id = r.string("id") ?: "${r.string("ringId") ?: ""}_${r.string("night") ?: ""}"
            val recordsFile = r.string("recordsFile") ?: r.string("records") ?: ""
            if (recordsFile.isEmpty()) continue
            val night = manifest.firstOrNull { it.id == id } ?: continue
            val records = SleepReplay.loadRecords(night, dir)
            if (records.isEmpty()) continue
            withBytes += 1

            // Upstream falls back to UTC when the row names no usable zone.
            val zone: ZoneId = SleepReplay.rowZone(r) ?: ZoneOffset.UTC

            val isEdited = r.bool("isManuallyEdited") ?: false
            val b = SleepReplay.date(r.string("editedInBedStart"))
            val o = SleepReplay.date(r.string("editedOnset"))
            val w = SleepReplay.date(r.string("editedWake"))
            val coverage = MeasuredCoverage.ofRecords(records)

            val base = SleepReplay.stage(records, zone, night.temperatureSamples(), night.deepHRBaselineBPM).segments

            // --- UNEDITED PATH. The staged hypnogram is what a night with no edit stores, and the line
            //     must be null on it — before any gate, purely because nothing is asserted.
            if (base.isNotEmpty()) {
                stagedNights += 1
                val staged = SleepProvenanceBreakdown(base)
                assertEquals(0.0, staged.assertedAsleep, 0.001, "$id: ordinary staging asserted time — the line's premise moved")
                assertNull(
                    SleepEditedNightNotice.line(staged.measuredAsleep, staged.assertedAsleep, mirrorsSleepToHealth = true),
                    "$id: the line fired on an UNEDITED night",
                )
            }

            // The edit path is NOT gated on staging, so this count stays comparable with the provenance corpus test.
            if (!isEdited || b == null || o == null || w == null || !(w > o) || o < b) continue
            editedNights += 1

            val on = SleepEdit.recompute(base, SleepEdit.Times(b, o, w), coverage = coverage)
            val br = SleepProvenanceBreakdown(on)
            val text = SleepEditedNightNotice.line(br.measuredAsleep, br.assertedAsleep, mirrorsSleepToHealth = true)

            // The manual-edit gate is the card's, not the copy's: asserted time here only ever comes from an edit.
            if (text != null) assertTrue(isEdited, "$id: asserted time on a night with no edit")

            if (text != null) {
                if (id in DISCOUNTED) firesDiscounted += 1 else fires += 1
                lines += "$id${if (id in DISCOUNTED) "   [DISCOUNTED]" else ""}\n" +
                    "  displayed ${br.minutes.measuredAsleep + br.minutes.assertedAsleep} min = ${br.minutes.measuredAsleep} measured + " +
                    "${br.minutes.assertedAsleep} asserted · asserted awake ${br.minutes.assertedAwake} min · coverage ${SleepReplay.fixed(br.coverageFraction, 3)}\n" +
                    "  $text"
            } else {
                silentEdited += "$id (asserted asleep ${SleepReplay.fixed(br.assertedAsleep / 60, 1)} min, asserted awake ${SleepReplay.fixed(br.assertedAwake / 60, 1)} min)"
            }
        }

        println("\n=== EDITED-NIGHT CARD LINE — corpus ${dir.path}")
        println("=== ${rawRows.size} manifest rows\n")
        for (l in lines) println(l + "\n")
        println(
            """
            --- corpus shape
            rows with raw bytes ............... $withBytes
            of those, staged a night .......... $stagedNights
            of those, replayable EDITS ........ $editedNights

            --- how many nights show the line
            FIRES (counted) ................... $fires of $withBytes nights with bytes
            FIRES (discounted rows) ........... $firesDiscounted — measured, not counted
            edited nights that stay SILENT .... ${silentEdited.size}${if (silentEdited.isEmpty()) "" else " — " + silentEdited.joinToString(", ")}
            nights with NO edit ............... 0 fired (asserted by construction, above)
            """.trimIndent(),
        )

        assertTrue(withBytes > 0, "nothing was replayed at all")
    }

    private companion object {
        /** Rows whose corpus BYTES provably are not what the phone staged from. Measured, never counted. */
        val DISCOUNTED = setOf("R1_2026-08-14", "R1_2026-08-15")
    }
}
