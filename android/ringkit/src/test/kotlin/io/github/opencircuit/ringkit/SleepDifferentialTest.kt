package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.SleepDifferentialFixtures.bits
import io.github.opencircuit.ringkit.SleepDifferentialFixtures.bitsToDouble
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
 * Kotlin port over the same inputs, renders the same canonical lines, and compares them.
 *
 * Comparison rule: every line is compared WHOLE and exactly (times, stages, counts, flags), except
 * floating-point fields, which must agree within 1e-9; every double that is not bit-identical is
 * listed in the report this test prints, so a drift is visible even when it is tolerated. The
 * goldens come only from the Swift generator, never from this code's output.
 */
class SleepDifferentialTest {

    companion object {
        /** Golden lines whose fields after the first token are IEEE-754 bit patterns. */
        private val DOUBLE_FIELDS = setOf("score")

        private val OVERNIGHT_ZONES = listOf("UTC", "Asia/Kolkata")

        private fun secs(t: Instant): String = t.epochSecond.toString().also { check(t.nano == 0) { "non-integral time $t" } }
        private fun name(a: Activity): String = if (a == Activity.SLEEP) "sleep" else "active"

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
        assertTrue(report.mismatches.isEmpty(), "${report.mismatches.size} night(s) differ from upstream:\n" + report.mismatches.take(20).joinToString("\n"))
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

        // Every branch the detection pipeline can take was reached by at least one night.
        val coverage = SleepDifferentialFixtures.coverage()
        for (branch in listOf(
            "wear-gate-changed", "hr-gate-changed", "rescue-changed", "multi-fragment", "main-none", "onset-unobserved",
            "motion-primary", "motion-tail-degenerate", "motion-tail-constant-filler", "overnight-UTC",
            "overnight-presumed-only-UTC",
        )) {
            assertTrue((coverage[branch] ?: 0) >= 1, "branch $branch never reached (${coverage[branch] ?: 0})")
        }
    }
}
