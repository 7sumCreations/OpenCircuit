package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.SleepDifferentialFixtures.bits
import io.github.opencircuit.ringkit.SleepDifferentialFixtures.bitsToDouble
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Differential check against upstream's own Swift pipeline: `tools/sleep-differential` ran the
 * pinned Swift code over seeded synthetic nights and upstream's fixture nights, and wrote their
 * inputs and canonical outputs to `src/test/resources/sleep-differential/`. This test runs the
 * Kotlin port over the same inputs, renders the same canonical lines, and compares them: sleep
 * detection, night selection in three zones, and staging (every segment, the summary and its
 * minutes, the sleep window, each staging switch turned off, and staging after selection).
 *
 * Comparison rule: every line is compared WHOLE and exactly (times, stages, counts, flags), except
 * floating-point fields, which must agree within 1e-9; every double that is not bit-identical is
 * listed in the report this test prints, so a drift is visible even when it is tolerated. The
 * goldens come only from the Swift generator, never from this code's output.
 */
class SleepDifferentialTest {

    companion object {
        /** Golden lines whose fields after the first token are IEEE-754 bit patterns. */
        private val DOUBLE_FIELDS = setOf("score", "sum")

        private val OVERNIGHT_ZONES = listOf("UTC", "Asia/Kolkata")

        /** Device zones the generator ran night selection in (upstream reads the device calendar). */
        private val SELECTION_ZONES = listOf("UTC", "Asia/Kolkata", "America/New_York")

        private fun secs(t: Instant): String = t.epochSecond.toString().also { check(t.nano == 0) { "non-integral time $t" } }
        private fun name(a: Activity): String = if (a == Activity.SLEEP) "sleep" else "active"

        /** `count first last` of a selected slice, "-" for an empty one. */
        private fun sliceKey(r: List<BulkRecord>): String = "${r.size} ${r.firstOrNull()?.counter ?: "-"} ${r.lastOrNull()?.counter ?: "-"}"

        /** The `sel` lines: night selection in every zone, shipped defaults and each switch off. */
        private fun selectionLines(n: DifferentialNight): List<String> = SELECTION_ZONES.flatMap { z ->
            val zone = ZoneId.of(z)
            fun select(
                cut: Double = BulkSleep.OBSERVED_GAP_ABSORB_COVERAGE_CUT,
                reanchor: Boolean = BulkSleep.DECLINED_BRIDGE_MAY_REANCHOR,
                morningGap: Duration = BulkSleep.MORNING_CONTINUATION_MAX_GAP,
            ) = BulkSleep.latestNightRecords(
                n.records,
                zone = zone,
                temperatures = n.temps,
                observedGapCoverageCut = cut,
                declinedBridgeMayReanchor = reanchor,
                morningContinuationGap = morningGap,
            )
            listOf(
                "default" to select(),
                "cut0" to select(cut = 0.0),
                "noreanchor" to select(reanchor = false),
                "nomorning" to select(morningGap = Duration.ZERO),
            ).map { (variant, slice) -> "sel $z $variant ${sliceKey(slice)}" }
        }

        private fun seconds(d: Duration): Double = d.seconds + d.nano / 1e9

        /** `<segments> <the six minutes> <onset|-> <wake|->` of one staged night. */
        private fun brief(segs: List<SleepSegment>): String {
            val m = SleepStaging.summary(segs).minutes
            val w = SleepStaging.sleepWindow(segs)
            return "${segs.size} ${m.inBed} ${m.awake} ${m.light} ${m.deep} ${m.rem} ${m.asleep} " +
                "${w?.let { secs(it.onset) } ?: "-"} ${w?.let { secs(it.wake) } ?: "-"}"
        }

        /** The staging lines: shipped tuning in full, each knob changed in brief, and staging after selection. */
        private fun stagingLines(n: DifferentialNight): List<String> {
            fun classify(
                tuning: SleepStaging.Tuning = SleepStaging.Tuning.DEFAULT,
                baseline: SleepStaging.PersonalBaseline? = null,
            ) = SleepStaging.classify(n.records, temperatures = n.temps, tuning = tuning, baseline = baseline)
            val segs = classify()
            val s = SleepStaging.summary(segs)
            val m = s.minutes
            val lines = segs.mapTo(mutableListOf()) { "stg ${it.stage.rawValue} ${secs(it.start)} ${secs(it.end)}" }
            lines += "sum ${bits(seconds(s.inBed))} ${bits(seconds(s.awake))} ${bits(seconds(s.light))} " +
                "${bits(seconds(s.deep))} ${bits(seconds(s.rem))} ${bits(s.efficiency)}"
            lines += "min ${m.inBed} ${m.awake} ${m.light} ${m.deep} ${m.rem} ${m.asleep}"
            lines += SleepStaging.sleepWindow(segs)?.let { "win ${secs(it.onset)} ${secs(it.wake)}" } ?: "win none"
            val t = SleepStaging.Tuning.DEFAULT
            listOf(
                "noleadprotect" to classify(t.copy(protectsLeadingHRWake = false)),
                "nocadence" to classify(t.copy(cadenceWakeQuietEpochs = 0)),
                "nowear" to classify(t.copy(stagedWearGate = false)),
                "nooffset" to classify(t.copy(offsetNoReturnSpreadFraction = 0.0)),
                "norescue" to classify(t.copy(hrWakeRescueCeilingBPM = 0.0)),
                "nowiden" to classify(t.copy(preOnsetBedtimeReachEpochs = 0)),
                "baseline" to classify(baseline = SleepStaging.PersonalBaseline(44.0)),
                "rrvar" to classify(t.copy(rrVarWeight = 0.5)),
            ).forEach { (variant, out) -> lines += "stgv $variant ${brief(out)}" }
            val zone = ZoneId.of("America/New_York")
            val selected = SleepStaging.classify(
                BulkSleep.latestNightRecords(n.records, zone = zone, temperatures = n.temps), temperatures = n.temps,
            )
            lines += "selstg America/New_York ${brief(selected)}"
            return lines
        }

        /** The Kotlin pipeline's canonical lines for one night, in the generator's format. */
        internal fun render(n: DifferentialNight): List<String> {
            val recs = n.records
            val periods = ActivityPeriod.detectFromMotion(
                BulkSleep.motionTimeline(recs),
                temperatureSamples = n.temps,
                heartRateSamples = BulkSleep.heartRateTimeline(recs),
                sleepVitalTimes = BulkSleep.sleepVitalTimeline(recs),
            )
            val main = BulkSleep.mainSleep(recs, temperatures = n.temps)
            val segs = BulkSleep.sleepSegments(recs, temperatures = n.temps)
            val lines = mutableListOf<String>()
            periods.forEach { lines += "p ${name(it.activity)} ${secs(it.start)} ${secs(it.end)}" }
            lines += if (main == null) "main none" else "main ${secs(main.start)} ${secs(main.end)}"
            segs.forEach { lines += "seg ${it.stage.rawValue} ${secs(it.start)} ${secs(it.end)}" }
            if (main != null) {
                lines += "score ${bits(SleepScore.score(main.start, main.end))}"
                lines += "onset ${BulkSleep.onsetIsUnobserved(DateInterval(main.start, main.end), recs)}"
                for (z in OVERNIGHT_ZONES) {
                    val zone = ZoneId.of(z)
                    val plain = SleepWindow.isOvernightBlock(main.start, main.end, zone)
                    val presumed = SleepWindow.isOvernightBlock(main.start, main.end, onsetIsUnobserved = true, zone = zone)
                    lines += "overnight $z $plain $presumed"
                }
            }
            lines += selectionLines(n)
            lines += stagingLines(n)
            return lines
        }
    }

    private class Report {
        val mismatches = mutableListOf<String>()
        val nonIdentical = mutableListOf<String>()
        var doubles = 0
        var lines = 0
    }

    /** Compare one night; whole lines, exact, except tolerated double fields. */
    private fun compare(id: String, expected: List<String>, actual: List<String>, report: Report) {
        report.lines += expected.size
        if (expected.size != actual.size) {
            report.mismatches += "$id: ${expected.size} golden lines vs ${actual.size} Kotlin lines\n  golden: $expected\n  kotlin: $actual"
            return
        }
        for (k in expected.indices) {
            val e = expected[k]
            val a = actual[k]
            val eTokens = e.split(' ')
            if (eTokens[0] in DOUBLE_FIELDS) {
                val aTokens = a.split(' ')
                if (aTokens[0] != eTokens[0] || aTokens.size != eTokens.size) {
                    report.mismatches += "$id line ${k + 1}: golden '$e' vs kotlin '$a'"
                    continue
                }
                for (j in 1 until eTokens.size) {
                    report.doubles++
                    if (eTokens[j] == aTokens[j]) continue
                    val ed = bitsToDouble(eTokens[j])
                    val ad = bitsToDouble(aTokens[j])
                    if (abs(ed - ad) <= 1e-9) {
                        report.nonIdentical += "$id ${eTokens[0]}: golden $ed (${eTokens[j]}) kotlin $ad (${aTokens[j]}) |Δ| ${abs(ed - ad)}"
                    } else {
                        report.mismatches += "$id line ${k + 1}: golden '$e' ($ed) vs kotlin '$a' ($ad)"
                    }
                }
            } else if (e != a) {
                report.mismatches += "$id line ${k + 1}: golden '$e' vs kotlin '$a'"
            }
        }
    }

    @Test
    fun kotlinMatchesTheSwiftGoldensForEveryNight() {
        val inputs = SleepDifferentialFixtures.inputs()
        val goldens = SleepDifferentialFixtures.goldens()
        assertEquals(goldens.keys.toList(), inputs.map { it.id }, "inputs and goldens list the same nights in the same order")

        val report = Report()
        for (n in inputs) compare(n.id, goldens.getValue(n.id), render(n), report)

        println(
            "sleep differential: ${inputs.size} nights, ${report.lines} golden lines, ${report.doubles} doubles, " +
                "${report.nonIdentical.size} not bit-identical (tolerated within 1e-9), ${report.mismatches.size} mismatches",
        )
        report.nonIdentical.forEach { println("  not bit-identical: $it") }
        val nightsDiffering = report.mismatches.map { it.substringBefore(' ').trimEnd(':') }.toSet().size
        assertTrue(
            report.mismatches.isEmpty(),
            "${report.mismatches.size} mismatch(es) in $nightsDiffering night(s) differ from upstream:\n" +
                report.mismatches.take(20).joinToString("\n"),
        )
    }

    @Test
    fun goldenSetCoversEveryShapeAndBranch() {
        val inputs = SleepDifferentialFixtures.inputs()
        val shapes = inputs.groupingBy { it.shape }.eachCount()
        val expectedShapes = listOf(
            "normal", "fragmented", "gapped", "raised-floor", "degenerate-primary", "constant-filler", "cold",
            "charging-gap", "all-active", "nap-bearing", "spo2-dip", "awake-evening", "restless-morning",
            "truncated-onset", "short",
        )
        for (s in expectedShapes) assertTrue((shapes[s] ?: 0) >= 10, "shape $s has ${shapes[s] ?: 0} nights")
        assertTrue((shapes["fixture"] ?: 0) >= 5, "upstream fixture nights present")
        assertTrue(inputs.size >= 200, "about 200 synthetic nights plus fixtures, got ${inputs.size}")
        // Multi-block archives built for night selection.
        val selectionShapes = listOf(
            "two-nights", "multi-drain-hole", "evening-block", "short-tail", "morning-continuation", "late-nap",
            "truncated-tail", "daytime-only", "all-day-spo2", "leapfrog", "intra-night-gap",
        )
        for (s in selectionShapes) assertTrue((shapes[s] ?: 0) >= 10, "selection shape $s has ${shapes[s] ?: 0} archives")
        // Single nights shaped to reach each staging pass.
        val stagingShapes = listOf("cadence-exit", "elevated-head", "second-bout", "bedtime-lead-in", "offset-rise", "temperature-block")
        for (s in stagingShapes) assertTrue((shapes[s] ?: 0) >= 10, "staging shape $s has ${shapes[s] ?: 0} nights")

        // Every branch the detection pipeline can take was reached by at least one night.
        val coverage = SleepDifferentialFixtures.coverage()
        for (branch in listOf(
            "wear-gate-changed", "hr-gate-changed", "rescue-changed", "multi-fragment", "main-none", "onset-unobserved",
            "motion-primary", "motion-tail-degenerate", "motion-tail-constant-filler", "overnight-UTC",
            "overnight-presumed-only-UTC",
            // Night selection: each switch changed at least one result, and both passes of the
            // overnight filter, several candidate nights and no night at all were all reached.
            "sel-guard-declined", "sel-reanchored", "sel-morning-absorbed", "sel-scoped", "sel-truncated-correction",
            "sel-no-night", "sel-several-nights",
            // Staging: every pass changed at least one night's hypnogram when switched off, stitched
            // nights staged, and every stage occurred.
            "stg-staged", "stg-multi-fragment", "stg-leading-wake", "stg-cadence-wake", "stg-wear-gate", "stg-offset",
            "stg-rescue", "stg-bedtime-widen", "stg-baseline", "stg-rr-variability", "stg-stage-awake",
            "stg-stage-asleepCore", "stg-stage-asleepDeep", "stg-stage-asleepREM",
        )) {
            assertTrue((coverage[branch] ?: 0) >= 1, "branch $branch never reached (${coverage[branch] ?: 0})")
        }
    }
}
