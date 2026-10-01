package io.github.opencircuit.ringkit

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
 * goldens come only from the Swift generator, never from this code's output. The format is
 * documented at the top of the generator (`tools/sleep-differential/Sources/.../main.swift`).
 */
class SleepDifferentialTest {

    private class InputNight(val id: String, val shape: String, val records: List<BulkRecord>, val temps: List<TemperatureSample>)

    /** Golden lines whose fields after the first token are IEEE-754 bit patterns. */
    private val doubleFields = setOf("score")

    private val overnightZones = listOf("UTC", "Asia/Kolkata")

    private fun resourceLines(name: String): List<String> {
        val stream = assertNotNull(javaClass.classLoader.getResourceAsStream("sleep-differential/$name"), "missing test resource $name")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readLines() }.filter { !it.startsWith("#") }
    }

    private fun bitsToDouble(hex: String): Double = java.lang.Double.longBitsToDouble(java.lang.Long.parseUnsignedLong(hex, 16))

    private fun parseInputs(lines: List<String>): List<InputNight> {
        val out = mutableListOf<InputNight>()
        var i = 0
        while (i < lines.size) {
            val head = lines[i].split(' ')
            check(head.size == 3 && head[0] == "night") { "bad night header at input line ${i + 1}: ${lines[i]}" }
            val recs = mutableListOf<BulkRecord>()
            val temps = mutableListOf<TemperatureSample>()
            i++
            while (lines[i] != "end") {
                val f = lines[i].split(' ')
                when (f[0]) {
                    "r" -> recs += checkNotNull(BulkRecord.of(hex(f[1]))) { "bad record: ${lines[i]}" }
                    "t" -> temps += TemperatureSample(Instant.ofEpochSecond(f[1].toLong()), bitsToDouble(f[2]))
                    else -> error("bad input line ${i + 1}: ${lines[i]}")
                }
                i++
            }
            out += InputNight(head[1], head[2], recs, temps)
            i++
        }
        return out
    }

    private fun parseGoldens(lines: List<String>): LinkedHashMap<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        var i = 0
        while (i < lines.size) {
            val head = lines[i].split(' ')
            check(head.size == 2 && head[0] == "night") { "bad golden header at line ${i + 1}: ${lines[i]}" }
            val body = mutableListOf<String>()
            i++
            while (lines[i] != "end") body += lines[i++]
            out[head[1]] = body
            i++
        }
        return out
    }

    private fun bits(d: Double): String = java.lang.Long.toHexString(java.lang.Double.doubleToRawLongBits(d)).padStart(16, '0')
    private fun secs(t: Instant): String = t.epochSecond.toString().also { check(t.nano == 0) { "non-integral time $t" } }
    private fun name(a: Activity): String = if (a == Activity.SLEEP) "sleep" else "active"

    /** The Kotlin pipeline's canonical lines for one night, in the generator's format. */
    private fun render(n: InputNight): List<String> {
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
            for (z in overnightZones) {
                val zone = ZoneId.of(z)
                val plain = SleepWindow.isOvernightBlock(main.start, main.end, zone)
                val presumed = SleepWindow.isOvernightBlock(main.start, main.end, onsetIsUnobserved = true, zone = zone)
                lines += "overnight $z $plain $presumed"
            }
        }
        return lines
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
            if (eTokens[0] in doubleFields) {
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
        val inputs = parseInputs(resourceLines("inputs.txt"))
        val goldens = parseGoldens(resourceLines("goldens.txt"))
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
        val inputs = parseInputs(resourceLines("inputs.txt"))
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
        val coverage = resourceLines("coverage.txt").associate { line ->
            val f = line.split(' ')
            check(f.size == 3 && f[0] == "branch") { "bad coverage line: $line" }
            f[1] to f[2].toInt()
        }
        for (branch in listOf(
            "wear-gate-changed", "hr-gate-changed", "rescue-changed", "multi-fragment", "main-none", "onset-unobserved",
            "motion-primary", "motion-tail-degenerate", "motion-tail-constant-filler", "overnight-UTC",
            "overnight-presumed-only-UTC",
        )) {
            assertTrue((coverage[branch] ?: 0) >= 1, "branch $branch never reached (${coverage[branch] ?: 0})")
        }
    }
}
