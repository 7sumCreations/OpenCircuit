package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * THE STAGING MONOTONICITY HARNESS — does ONE more epoch record ever DESTROY a staged night?
 *
 * Every other entry point stages each corpus night exactly once, from the whole record file. That
 * cannot see the failure where the SAME night stages fine at one archive size and returns NOTHING
 * at the next — which is what a wearer actually experiences, because the archive grows one drain
 * at a time. So this replays each night at INCREASING record cutoffs and reports staged asleep
 * minutes as a function of N.
 *
 * WHAT IS ASSERTED, AND WHY IT IS NOT PLAIN MONOTONICITY. "Asleep minutes never decrease as records
 * are added" is too strong, for three measured, honest reasons: (1) the stage thresholds are
 * percentiles over the night's own distribution, so a longer window re-fits them; (2) the archive
 * merge prunes to a 30 h retention window relative to the newest record, so a growing prefix can
 * drop records off the front; (3) the night rolls over — `BulkSleep.latestNightRecords` scopes to
 * the most recent night, so the first record of the next night stops the staged minutes describing
 * last night at all. What none of those explains is a COLLAPSE: a staged night going to exactly
 * ZERO with the SAME night still in scope. That is asserted against.
 *
 * Neither number the collapse rule uses is a tolerance invented here: the floor is
 * `ActivityPeriod.MIN_SLEEP_DURATION` (the pipeline's own "there is a stageable night here") and the
 * rollover exemption is `BulkSleep.MAX_INTRA_NIGHT_GAP` (the pipeline's own "different nights").
 * Exempted rollovers are PRINTED, not silently dropped. Every decrease of any size is printed, so a
 * "fix" that merely reshuffles a collapse into a large-but-nonzero drop shows in the table.
 *
 * Upstream wrote this to FAIL and it did (a tester night: 463 staged asleep minutes → 0 when the
 * archive grew from 672 to 673 records); it is green as shipped because the declined-bridge
 * re-anchor (`BulkSleep.DECLINED_BRIDGE_MAY_REANCHOR`) keeps that night. Green here is a PROOF.
 *
 * Runs on the MONOTONICITY corpus variable (`SleepReplay.Corpus.MONOTONICITY`).
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepMonotonicityTests.swift
 * (@ b1c2fdd; its class is `SleepStagingMonotonicityTests`) — its 1 test. Upstream sets the
 * process time zone around each night; here the night's zone is passed explicitly.
 */
class SleepMonotonicityTest {

    /** One replay of one prefix of one night's records. */
    private data class Step(
        val cutoff: Int, // number of leading records fed in
        val lastRecord: Instant?, // timestamp of the newest record in that prefix
        val unionCount: Int, // after the archive merge's 30 h retention prune
        val nightCount: Int, // after BulkSleep.latestNightRecords
        val asleepMin: Long,
        val inBedStart: Instant?,
        val inBedEnd: Instant?,
    )

    /** What one step's DECREASE is. */
    private enum class Verdict { NONE, ROLLOVER, COLLAPSE }

    @Test
    fun addingOneRecordNeverDestroysAStagedNight() {
        val dir = SleepReplay.requireCorpus(
            SleepReplay.Corpus.MONOTONICITY,
            purpose = "the staging MONOTONICITY sweep (SleepMonotonicityTest)",
            consequence = "This is the only check that stages the same night more than once; unrun, nothing here can tell a fix " +
                "for the collapse defect from a re-shuffle of it.",
        )
        val nights = SleepReplay.loadManifest(dir)

        println("\n=== SLEEP STAGING MONOTONICITY — ${dir.path}")
        println("=== ${nights.size} manifest row(s), stride $STRIDE record(s) per step")
        println(
            "=== each step = SleepReplay.stage(records: <first N records>) at the shipped default " +
                "(observedGapAbsorbCoverageCut = ${BulkSleep.OBSERVED_GAP_ABSORB_COVERAGE_CUT})",
        )
        println(
            "=== collapse floor ${ActivityPeriod.MIN_SLEEP_DURATION.toMinutes()} min (ActivityPeriod.MIN_SLEEP_DURATION) · rollover exemption " +
                "${BulkSleep.MAX_INTRA_NIGHT_GAP.toHours()} h (BulkSleep.MAX_INTRA_NIGHT_GAP)\n",
        )

        val collapses = mutableListOf<String>()
        var replayed = 0

        for (night in nights) {
            val records = try {
                SleepReplay.loadRecords(night, dir)
            } catch (_: SleepReplay.ReplayError.NoRecords) {
                continue // summary-only manifest row
            }
            if (records.isEmpty()) continue
            replayed += 1

            val steps = (STRIDE..records.size step STRIDE).map { n ->
                val prefix = records.subList(0, n)
                // Truncate the temperature log to the prefix too: an archive holding records only up to
                // time T cannot also hold a temperature from after T.
                val horizon = prefix.last().date()
                val staged = SleepReplay.stage(
                    records = prefix,
                    zone = night.zone,
                    temperatures = night.temperatures.filter { !it.t.isAfter(horizon) }.map { TemperatureSample(it.t, it.c) },
                    deepHRBaseline = night.deepHRBaselineBPM,
                )
                val segs = staged.segments
                Step(
                    cutoff = n,
                    lastRecord = prefix.last().date(),
                    unionCount = staged.union.size,
                    nightCount = staged.nightRecords.size,
                    asleepMin = SleepStaging.summary(segs).minutes.asleep,
                    inBedStart = segs.minOfOrNull { it.start },
                    inBedEnd = segs.maxOfOrNull { it.end },
                )
            }

            report(night, records.size, steps)
            collapses += findings(night, steps)
        }

        assertTrue(replayed > 0, "no corpus night carried records — the sweep measured NOTHING and this green tick would be backed by zero replays")
        assertEquals(
            emptyList(), collapses,
            "a staged night was DESTROYED by adding records to the archive:\n" + collapses.joinToString("\n") +
                "\n\nStaging returned segments at one archive size and NOTHING at the next, with the same night still in scope. " +
                "Do NOT satisfy this by moving the floor or widening the rollover exemption — both are production constants.",
        )
    }

    // MARK: - Classification

    /** Is this step's fall to zero the defect, or the next night arriving? */
    private fun verdict(p: Step, s: Step): Verdict {
        if (s.asleepMin != 0L || p.asleepMin < ActivityPeriod.MIN_SLEEP_DURATION.toMinutes()) return Verdict.NONE
        val end = p.inBedEnd ?: return Verdict.NONE
        val added = s.lastRecord ?: return Verdict.NONE
        return if (SleepReplay.secondsBetween(end, added) > SleepStaging.seconds(BulkSleep.MAX_INTRA_NIGHT_GAP)) Verdict.ROLLOVER else Verdict.COLLAPSE
    }

    // MARK: - Reporting

    /** A row per CHANGE in staged asleep minutes, plus the first and last step. */
    private fun report(night: ReplayNight, records: Int, steps: List<Step>) {
        val zone = night.zone
        println("--- ${night.id}   ${night.ring ?: "?"}  build ${night.appBuild ?: "?"}  $records records, ${steps.size} replays")
        println("    cutoff  lastRecord        union  night  asleep   Δ   inBedStart     inBedEnd")
        fun line(s: Step, delta: Long?, mark: String) {
            println(
                "  $mark${s.cutoff.toString().padStart(6)}  " + SleepReplay.pad(SleepReplay.clock(s.lastRecord, zone), 16) +
                    "  ${s.unionCount.toString().padStart(5)}  ${s.nightCount.toString().padStart(5)}  ${s.asleepMin.toString().padStart(6)}  " +
                    (delta?.let { (if (it > 0) "+" else "") + it } ?: "·").padStart(5) + "  " +
                    SleepReplay.pad(SleepReplay.clock(s.inBedStart, zone), 14) + " " + SleepReplay.clock(s.inBedEnd, zone),
            )
        }
        var previous: Step? = null
        var worstDrop = 0L
        var worstDropAt: Step? = null
        var firstDropAt: Step? = null
        var decreases = 0
        var rollovers = 0
        steps.forEachIndexed { i, s ->
            val delta = previous?.let { s.asleepMin - it.asleepMin }
            val call = previous?.let { verdict(it, s) } ?: Verdict.NONE
            if (call == Verdict.ROLLOVER) rollovers += 1
            if (delta != null && delta < 0) {
                decreases += 1
                if (firstDropAt == null) firstDropAt = s
                if (-delta > worstDrop) {
                    worstDrop = -delta
                    worstDropAt = s
                }
            }
            val changed = delta?.let { it != 0L } ?: true
            if (changed || i == 0 || i == steps.size - 1) {
                val mark = when (call) {
                    Verdict.COLLAPSE -> "!! "
                    Verdict.ROLLOVER -> " ⟳ "
                    Verdict.NONE -> if ((delta ?: 0) < 0) " ↓ " else "   "
                }
                line(s, delta, mark)
            }
            previous = s
        }
        fun at(s: Step?): String = s?.let { " at cutoff ${it.cutoff} (${SleepReplay.clock(it.lastRecord, zone)})" } ?: ""
        println(
            "    peak asleep ${steps.maxOfOrNull { it.asleepMin } ?: 0} min · final ${steps.lastOrNull()?.asleepMin ?: 0} min · " +
                "$decreases decreasing step(s) · first drop${at(firstDropAt)} · worst drop $worstDrop min${at(worstDropAt)} · " +
                "$rollovers exempted rollover(s)",
        )
        println(
            "    ↓ = staged asleep minutes fell as records were ADDED.  ⟳ = fell to zero because the NEXT night arrived (exempt).  " +
                "!! = fell to zero with the same night in scope — the collapse this file asserts against.\n",
        )
    }

    /** Every collapse, rendered as the two archive states that bracket it. */
    private fun findings(night: ReplayNight, steps: List<Step>): List<String> {
        val zone = night.zone
        val out = mutableListOf<String>()
        var previous: Step? = null
        for (s in steps) {
            val p = previous
            previous = s
            if (p == null || verdict(p, s) != Verdict.COLLAPSE) continue
            val gap = if (s.lastRecord != null && p.inBedEnd != null) SleepReplay.secondsBetween(p.inBedEnd, s.lastRecord) else 0.0
            out += "  ${night.id}: ${p.asleepMin} staged asleep minutes → 0 when the archive grew from ${p.cutoff} to ${s.cutoff} records.\n" +
                "    kept through  ${SleepReplay.clock(p.lastRecord, zone)} → in-bed ${SleepReplay.clock(p.inBedStart, zone)} .. " +
                "${SleepReplay.clock(p.inBedEnd, zone)} (union ${p.unionCount}, night-scoped ${p.nightCount})\n" +
                "    destroyed at  ${SleepReplay.clock(s.lastRecord, zone)} → staging returned NO segments (union ${s.unionCount}, " +
                "night-scoped ${s.nightCount})\n" +
                "    the added record sits ${gap.toLong()} s past that in-bed end, well inside BulkSleep.MAX_INTRA_NIGHT_GAP " +
                "(${BulkSleep.MAX_INTRA_NIGHT_GAP.seconds} s), so it is the SAME night by the pipeline's own rule — not a rollover."
        }
        return out
    }

    private companion object {
        /** Sweep EVERY record index: a stride could step OVER the single record that destroys a night. */
        const val STRIDE = 1
    }
}
