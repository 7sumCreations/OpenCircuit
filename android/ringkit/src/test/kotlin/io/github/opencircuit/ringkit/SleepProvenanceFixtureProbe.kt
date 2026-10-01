package io.github.opencircuit.ringkit

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test

/**
 * SCAFFOLD — prints the geometry needed to hand-write the committed provenance fixture. Not a test
 * of anything: it exists so the fixture's numbers are extracted from real bytes rather than typed
 * from memory. Delete-safe.
 *
 * Runs on the PROVENANCE corpus variable (`SleepReplay.Corpus.PROVENANCE`).
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepProvenanceFixtureProbe.swift
 * (@ b1c2fdd) — its 1 test. Upstream sets the process time zone around each night; here the
 * night's zone is passed explicitly.
 */
class SleepProvenanceFixtureProbe {

    @Test
    fun printGeometry() {
        val dir = SleepReplay.requireCorpus(
            SleepReplay.Corpus.PROVENANCE,
            purpose = "the scaffold that re-derives the committed provenance fixture's geometry from real bytes",
            consequence = "The fixture cannot be regenerated on this run; the committed numbers stand.",
        )
        val nights = SleepReplay.loadManifest(dir)
        fun fmt(d: Instant, zone: ZoneId): String = SleepReplay.iso(d, zone)

        for (id in listOf("R2_2026-08-18", "R2_2026-08-17")) {
            val n = nights.firstOrNull { it.id == id } ?: continue
            val zone = n.zone
            val records = SleepReplay.loadRecords(n, dir)
            val cov = MeasuredCoverage.ofRecords(records)
            println("\n### $id  tz=${SleepReplay.foundationIdentifier(zone)}  records=${records.size}")
            println("merged coverage intervals (${cov.intervals.size}):")
            for (iv in cov.intervals) {
                println(
                    "   ${fmt(iv.start, zone)} -> ${fmt(iv.end, zone)}  (${SleepReplay.secondsBetween(iv.start, iv.end).toLong()} s)" +
                        "  epoch1970 ${iv.start.epochSecond} .. ${iv.end.epochSecond}",
                )
            }
            val base = SleepReplay.stage(records, zone, n.temperatureSamples(), n.deepHRBaselineBPM).segments
            println("staged base segments: ${base.size}")
            val sleep = SleepStaging.sleepWindow(base)
            println("   staged sleep window: ${sleep?.let { fmt(it.onset, zone) } ?: "nil"} -> ${sleep?.let { fmt(it.wake, zone) } ?: "nil"}")
            val m = SleepStaging.summary(base).minutes
            println("   staged minutes inBed=${m.inBed} asleep=${m.asleep} awake=${m.awake} deep=${m.deep} rem=${m.rem} light=${m.light}")
            for (s in base.filter { it.stage != SleepStage.IN_BED }) {
                println("   SEG ${fmt(s.start, zone)} -> ${fmt(s.end, zone)} ${s.stage.rawValue} | epoch1970 ${s.start.epochSecond} ${s.end.epochSecond}")
            }
        }
    }
}
