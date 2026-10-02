package io.github.opencircuit.ringkit

import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * BACKWARD-CLUSTER-CHAIN PROBE — measure, on every corpus night, WHAT the backward absorb in
 * `BulkSleep.latestNightRecords` actually bridges, before changing it.
 *
 * Why: the observed-gap guard declines the backward absorb when the bridged gap is FULL OF OBSERVED
 * ACTIVE EPOCHS while keeping it for an EMPTY UNOBSERVED HOLE (the multi-drain stitch the chain
 * exists for). Whether that is safe is an empirical question, answered from the corpus instead of
 * from argument. It touches NO production source: it re-walks the steps `latestNightRecords` takes
 * and prints one line per bridge. If the walk ever disagrees with production's own scoping,
 * [probeAgreesWithProduction] fails — the probe is not allowed to drift.
 *
 * Runs on the SLEEP corpus variable (`SleepReplay.Corpus.SLEEP`); the optional probe-output setting
 * (`SleepReplay.Setting.PROBE_OUT`) names a file to write the report to.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepAbsorbProbeTests.swift
 * (@ b1c2fdd) — its 2 tests. Upstream sets the process time zone around each night; here the
 * night's zone is passed explicitly.
 */
class SleepAbsorbProbeTest {

    data class Bridge(
        val candidateStart: Instant,
        val candidateEnd: Instant,
        val gapSec: Double,
        val recordsInGap: Int,
        val expectedInGap: Double,
        val coverage: Double,
        val movedClusterStart: Boolean,
        val newClusterStart: Instant,
    )

    data class Walk(
        val anchorStart: Instant,
        val anchorEnd: Instant,
        val nightBlocks: Int,
        val usedPass2: Boolean,
        val bridges: List<Bridge>,
        val clusterStartBeforeClip: Instant,
        val clusterStartAfterClip: Instant,
    )

    @Test
    fun probeBackwardAbsorbAcrossCorpus() {
        val dir = SleepReplay.requireCorpus(
            SleepReplay.Corpus.SLEEP,
            purpose = "the backward-cluster-chain probe that the shipped absorb cut was chosen from",
            consequence = "The evidence behind `BulkSleep.OBSERVED_GAP_ABSORB_COVERAGE_CUT` was NOT reproduced.",
        )
        val nights = SleepReplay.loadManifest(dir)
        val lines = mutableListOf("=== BACKWARD-CLUSTER-CHAIN PROBE ===", "cov = observed records in the bridged gap / expected at 150 s cadence", "")
        var firing = 0
        var movingBridges = 0
        val covOfMoving = mutableListOf<Double>()
        for (n in nights.filter { it.recordsFile.isNotEmpty() }) {
            val recs = SleepReplay.loadRecords(n, dir)
            val zone = n.zone
            val w = walk(recs, n.temperatureSamples(), zone)
            if (w == null) {
                lines += "${n.id}: no night block"
                continue
            }
            lines += "${n.id}  anchor ${SleepReplay.clock(w.anchorStart, zone)} -> ${SleepReplay.clock(w.anchorEnd, zone)}  " +
                "nightBlocks=${w.nightBlocks}" + if (w.usedPass2) "  [pass2]" else ""
            if (w.bridges.isEmpty()) lines += "      (no backward bridge considered)"
            for (b in w.bridges) {
                firing += 1
                if (b.movedClusterStart) {
                    movingBridges += 1
                    covOfMoving += b.coverage
                }
                lines += "      cand ${SleepReplay.clock(b.candidateStart, zone)} -> ${SleepReplay.clock(b.candidateEnd, zone)}  " +
                    "gap ${SleepReplay.fixed(b.gapSec / 60, 1, width = 6)} min  recs ${b.recordsInGap.toString().padStart(3)} / " +
                    "${SleepReplay.fixed(b.expectedInGap, 1, width = 6)}  cov ${SleepReplay.fixed(b.coverage, 3)}  " +
                    if (b.movedClusterStart) "MOVES clusterStart -> ${SleepReplay.clock(b.newClusterStart, zone)}" else "no-op"
            }
            if (w.clusterStartAfterClip != w.clusterStartBeforeClip) {
                lines += "      head clip: ${SleepReplay.clock(w.clusterStartBeforeClip, zone)} -> ${SleepReplay.clock(w.clusterStartAfterClip, zone)}"
            }
        }
        lines += ""
        lines += "bridges considered $firing; bridges that MOVED clusterStart $movingBridges"
        if (covOfMoving.isNotEmpty()) {
            val sorted = covOfMoving.sorted()
            lines += "coverage of MOVING bridges: min ${SleepReplay.fixed(sorted.first(), 3)}  median ${SleepReplay.fixed(sorted[sorted.size / 2], 3)}  " +
                "max ${SleepReplay.fixed(sorted.last(), 3)}"
            lines += "all coverages: " + sorted.joinToString(", ") { SleepReplay.fixed(it, 3) }
        }
        val out = lines.joinToString("\n")
        println(out)
        val path = SleepReplay.setting(SleepReplay.Setting.PROBE_OUT)
        if (!path.isNullOrEmpty()) File(path).writeText(out)
    }

    /**
     * The probe must reproduce production's own night scoping, or its evidence is worthless.
     * Compared against production AT CUT 0: the walk deliberately transcribes the UNGUARDED backward
     * chain (it enumerates every bridge the chain *considers* — the evidence the cut is chosen from),
     * so comparing it with the guarded default would compare two different algorithms. Upstream
     * measured that at the shipped default this disagrees on exactly the 2 nights the guard moves.
     */
    @Test
    fun probeAgreesWithProduction() {
        val dir = SleepReplay.requireCorpus(
            SleepReplay.Corpus.SLEEP,
            purpose = "the anti-drift check that the probe still walks what production walks",
            consequence = "A silently drifted probe would make every number it prints meaningless.",
        )
        var checked = 0
        for (n in SleepReplay.loadManifest(dir).filter { it.recordsFile.isNotEmpty() }) {
            val recs = SleepReplay.loadRecords(n, dir)
            val union = EpochArchive.merge(existing = emptyList(), incoming = recs)
            val temps = n.temperatureSamples()
            val prod = BulkSleep.latestNightRecords(union, n.zone, temperatures = temps, observedGapCoverageCut = 0.0)
            val w = walk(union, temps, n.zone)
            if (w == null) {
                // No night: production returns the records unchanged.
                assertEquals(union.size, prod.size, "${n.id}: no-night case must pass through")
                continue
            }
            // Reproduce the production slice's LOWER edge from the walk (backward chain + head clip),
            // which is what this candidate touches; the morning absorb only moves the upper edge.
            val lo = w.clusterStartAfterClip.minus(Duration.ofMinutes(30))
            val expectedLo = union.firstOrNull { !it.date().isBefore(lo) }?.date()
            assertEquals(expectedLo, prod.firstOrNull()?.date(), "${n.id}: probe's cluster start disagrees with production's slice")
            checked += 1
        }
        println("probe/production agreement checked on $checked night(s)")
    }

    companion object {
        /**
         * A transcription of `BulkSleep.latestNightRecords`' pass-1/pass-2 + backward chain, with the
         * per-bridge evidence exposed. Deliberately duplicated rather than refactored out of
         * production, so production stays exactly as shipped while this runs.
         */
        fun walk(records: List<BulkRecord>, temperatures: List<TemperatureSample>, zone: ZoneId): Walk? {
            val epoch = Command.SYNC_EPOCH
            val recs = records.sortedBy { it.counter }
            val periods = ActivityPeriod.detectFromMotion(
                BulkSleep.motionTimeline(recs, epoch),
                temperatureSamples = temperatures,
                heartRateSamples = BulkSleep.heartRateTimeline(recs, epoch),
                sleepVitalTimes = BulkSleep.sleepVitalTimeline(recs, epoch),
            )
            val sleepBlocks = periods.filter { it.activity == Activity.SLEEP && it.duration > ActivityPeriod.MIN_SLEEP_DURATION }
            var usedPass2 = false
            var nights = sleepBlocks.filter { SleepWindow.isOvernightBlock(it.start, it.end, zone) }
            if (nights.isEmpty()) {
                usedPass2 = true
                nights = sleepBlocks.filter {
                    val unobserved = BulkSleep.onsetIsUnobserved(DateInterval(it.start, maxOf(it.end, it.start)), recs, epoch)
                    SleepWindow.isOvernightBlock(it.start, it.end, onsetIsUnobserved = unobserved, zone = zone)
                }
            }
            val anchor = nights.maxByOrNull { it.end } ?: return null

            val times = recs.map { it.date(epoch) }
            val maxGap = SleepStaging.seconds(BulkSleep.MAX_INTRA_NIGHT_GAP)
            var clusterStart = anchor.start
            val bridges = mutableListOf<Bridge>()
            for (p in nights.sortedByDescending { it.start }) {
                if (p.end > anchor.end) continue
                val gap = SleepReplay.secondsBetween(p.end, clusterStart)
                if (gap > maxGap) continue
                // Records strictly INSIDE the bridged gap (p.end, clusterStart).
                val inGap = times.count { it > p.end && it < clusterStart }
                val expected = maxOf(gap / BulkRecord.EPOCH_SECONDS, 0.0)
                val next = minOf(clusterStart, p.start)
                bridges += Bridge(
                    candidateStart = p.start, candidateEnd = p.end, gapSec = gap, recordsInGap = inGap, expectedInGap = expected,
                    coverage = if (expected > 0) inGap / expected else 1.0, movedClusterStart = next < clusterStart, newClusterStart = next,
                )
                clusterStart = next
            }
            val beforeClip = clusterStart
            val afterClip = maxOf(clusterStart, anchor.end.minus(BulkSleep.MAX_NIGHT_SPAN))
            return Walk(anchor.start, anchor.end, nights.size, usedPass2, bridges, beforeClip, afterClip)
        }
    }
}
