package io.github.opencircuit.ringkit

import org.junit.jupiter.api.Timeout
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Differential check of the vitals and energy maths against upstream's own Swift code:
 * `tools/sleep-differential` (its `VitalsDifferential` generator, `regenerate.sh vitals`) ran the
 * pinned Swift functions over seeded synthetic inputs and wrote the inputs and canonical outputs to
 * `src/test/resources/vitals-differential/`. This test runs the Kotlin port over the same inputs,
 * renders the same canonical lines and compares them: HRV (clean, RMSSD, rolling, summary), the
 * stress index, TRIMP and strain over heart-rate series, TRIMP and TRIMP energy over timestamped
 * samples (including days across both 2026 clock changes, duplicated and reversed samples), the
 * profile energies (BMR, distance, steps, Keytel, resting-HR-scaled basal, resting-HR baseline), and
 * synthetic days of heart rate, step windows and sleep (resting HR and its daily values in a zone,
 * the derived resting baseline, the thresholds, the elevated pieces, exercise minutes, the legacy
 * daily estimate and the attributed estimate with every bucket at several widths — including days
 * across both 2026 New York clock changes and a day without a midnight, duplicated and unsorted
 * samples, windows that straddle the day's edges, and hostile windows and widths).
 * The format is documented at the top of the generator's `main.swift`.
 *
 * Comparison rule: every line is compared WHOLE, token by token and exactly, except doubles (tokens
 * `d` + 16 hex digits), which must agree within 1e-9; every double that is not bit-identical is
 * listed in the report this test prints. The goldens come only from the Swift generator, never from
 * this code's output. Where the port deliberately differs from upstream, the lines that move are
 * named in [DELIBERATE_DIVERGENCES] (case + line kind, with the `PORTING.md` D-row) and reported;
 * no other line may differ, and a listed line that stops differing fails as stale.
 */
class VitalsDifferentialTest {

    private class VCase(val id: String, val kind: String, val shape: String, val lines: List<String>)

    private data class Divergence(val case: String, val kind: String)

    private fun d(x: Double): String = "d" + java.lang.Long.toHexString(java.lang.Double.doubleToRawLongBits(x)).padStart(16, '0')
    private fun optD(x: Double?): String = x?.let { d(it) } ?: "-"
    private fun isDouble(t: String): Boolean = t.length == 17 && t[0] == 'd' && t.substring(1).all { it in '0'..'9' || it in 'a'..'f' }
    private fun toDouble(t: String): Double = java.lang.Double.longBitsToDouble(java.lang.Long.parseUnsignedLong(t.substring(1), 16))
    private fun inDouble(t: String): Double? = if (t == "-") null else toDouble(t.also { require(isDouble(it)) { "bad double token $it" } })
    private fun ints(xs: List<Number>): String = xs.joinToString("") { " $it" }

    // --- reading the generator's files (each read once per test) ---

    private fun lines(name: String): List<String> {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream("vitals-differential/$name")) { "missing test resource $name" }
        return stream.bufferedReader(Charsets.UTF_8).use { it.readLines() }.filter { !it.startsWith("#") }
    }

    private fun inputs(): List<VCase> {
        val all = lines("inputs.txt")
        val out = mutableListOf<VCase>()
        var i = 0
        while (i < all.size) {
            val head = all[i].split(' ')
            check(head.size == 4 && head[0] == "case") { "bad case header at input line ${i + 1}: ${all[i]}" }
            val body = mutableListOf<String>()
            i++
            while (all[i] != "end") body += all[i++]
            out += VCase(head[1], head[2], head[3], body)
            i++
        }
        return out
    }

    private fun goldens(): LinkedHashMap<String, List<String>> {
        val all = lines("goldens.txt")
        val out = LinkedHashMap<String, List<String>>()
        var i = 0
        while (i < all.size) {
            val head = all[i].split(' ')
            check(head.size == 2 && head[0] == "case") { "bad golden header at line ${i + 1}: ${all[i]}" }
            val body = mutableListOf<String>()
            i++
            while (all[i] != "end") body += all[i++]
            out[head[1]] = body
            i++
        }
        return out
    }

    private fun coverage(): Map<String, Int> = lines("coverage.txt").associate { line ->
        val f = line.split(' ')
        check(f.size == 3 && f[0] == "branch") { "bad coverage line: $line" }
        f[1] to f[2].toInt()
    }

    // --- rendering the Kotlin port's canonical lines ---

    private fun render(c: VCase): List<String> = when (c.kind) {
        "rr" -> renderRR(c)
        "bpm" -> renderBpm(c)
        "hrs" -> renderHrs(c)
        "energy" -> renderEnergy(c)
        "day" -> renderDay(c)
        else -> error("unknown case kind ${c.kind} in ${c.id}")
    }

    private fun tokens(c: VCase, tag: String): List<String> =
        c.lines.single { it == tag || it.startsWith("$tag ") }.split(' ').drop(1)

    private fun renderRR(c: VCase): List<String> {
        val groups = tokens(c, "g").map { t -> if (t == "_") emptyList() else t.split(',').map { it.toInt() } }
        val windows = tokens(c, "ws").map { it.toInt() }
        val raw = groups.flatten()
        val clean = HRV.cleanRR(groups)
        val out = mutableListOf(
            "clean" + ints(clean),
            "rmssd ${HRV.rmssd(clean) ?: "-"}",
            "rmssdraw ${HRV.rmssd(raw) ?: "-"}",
            "stress ${d(Stress.index(clean))}",
            "stressraw ${d(Stress.index(raw))}",
        )
        for (ws in windows) {
            out += "roll $ws" + ints(HRV.rollingRMSSD(clean, windowSize = ws))
            out += HRV.summary(clean, windowSize = ws)?.let { "sum $ws ${it.min} ${it.max} ${it.avg}" } ?: "sum $ws none"
        }
        return out
    }

    private fun renderBpm(c: VCase): List<String> {
        val bpms = tokens(c, "b").map { it.toInt() }
        return c.lines.filter { it.startsWith("p ") }.flatMapIndexed { k, line ->
            val f = line.split(' ')
            val maxHR = f[1].toInt()
            val restingHR = f[2].toInt()
            val seconds = toDouble(f[3])
            listOf(
                "trimp $k ${optD(Strain.edwardsTRIMP(bpms, maxHR = maxHR, restingHR = restingHR, sampleSeconds = seconds))}",
                "strain $k ${optD(Strain(maxHR = maxHR, restingHR = restingHR).calculate(bpms, sampleSeconds = seconds))}",
            )
        }
    }

    private fun renderHrs(c: VCase): List<String> {
        val triples = tokens(c, "s").map { it.toLong() }
        check(triples.size % 3 == 0) { "${c.id}: samples are not triples" }
        val samples = triples.chunked(3).map { (bpm, start, end) -> HRSample(bpm.toInt(), Instant.ofEpochMilli(start), Instant.ofEpochMilli(end)) }
        val out = c.lines.filter { it.startsWith("p ") }.mapIndexed { k, line ->
            val f = line.split(' ')
            "trimphr $k ${optD(Strain.edwardsTRIMP(samples, maxHR = f[1].toInt(), restingHR = f[2].toInt()))}"
        }.toMutableList()
        for (m in tokens(c, "k").map { it.toInt() }) out += "kcal $m ${d(Calories.activeKcal(samples, maxHR = m))}"
        return out
    }

    private fun renderEnergy(c: VCase): List<String> {
        val p = c.lines.first().split(' ')
        check(p[0] == "profile") { "${c.id}: the profile comes first" }
        val profile = UserProfile(
            age = p[1].toInt(), weightKg = toDouble(p[2]), heightCm = toDouble(p[3]),
            sex = when (p[4]) { "male" -> BiologicalSex.MALE; "female" -> BiologicalSex.FEMALE; else -> error("bad sex ${p[4]}") },
        )
        val out = mutableListOf("bmr ${d(Calories.bmrKcalPerDay(profile))} ${d(Calories.bmrKcalPerHour(profile))}")
        for (line in c.lines.drop(1)) {
            val f = line.split(' ')
            out += when (f[0]) {
                "m" -> "dist ${f[1]} ${d(Calories.activeKcalFromDistance(toDouble(f[2]), profile))}"
                "n" -> f[2].toInt().let { n -> "steps ${f[1]} ${d(DistanceEstimate.meters(n))} ${d(Calories.activeKcalFromSteps(n, profile))}" }
                "w" -> "keytel ${f[1]} ${d(Calories.workoutActiveKcal(avgHR = f[2].toInt(), durationSeconds = toDouble(f[3]), profile = profile))}"
                "r" -> {
                    val rhr = inDouble(f[2])
                    val base = inDouble(f[3])
                    "basal ${f[1]} ${d(Calories.restingEnergyScale(rhr, base))} ${d(Calories.basalKcalPerHour(profile, rhr, base))}"
                }
                "prior" -> "baseline ${f[1]} ${optD(Calories.restingBaselineBpm(f.drop(3).map { toDouble(it) }, minDays = f[2].toInt()))}"
                else -> error("bad energy input line in ${c.id}: $line")
            }
        }
        return out
    }

    private fun milli(t: String): Instant = Instant.ofEpochMilli(t.toLong())

    private fun renderDay(c: VCase): List<String> {
        val p = tokens(c, "profile")
        val profile = UserProfile(
            age = p[0].toInt(), weightKg = toDouble(p[1]), heightCm = toDouble(p[2]),
            sex = when (p[3]) { "male" -> BiologicalSex.MALE; "female" -> BiologicalSex.FEMALE; else -> error("bad sex ${p[3]}") },
        )
        val zone = ZoneId.of(tokens(c, "zone").single())
        val day = milli(tokens(c, "day").single())
        val steps = tokens(c, "steps").single().toInt()
        val hr = tokens(c, "s").chunked(3).map { (bpm, start, end) -> HRSample(bpm.toInt(), milli(start), milli(end)) }
        val windows = tokens(c, "w").chunked(3).map { (start, end, delta) -> StepWindow(milli(start), milli(end), delta.toInt()) }
        val sleepWindow = tokens(c, "sw").let { if (it == listOf("-")) null else DateInterval(milli(it[0]), milli(it[1])) }
        val segments = tokens(c, "seg").chunked(3).map { (start, end, stage) ->
            SleepSegment(milli(start), milli(end), SleepStage.entries.single { it.rawValue == stage })
        }
        val widths = tokens(c, "bw").map { toDouble(it) }
        val maxHR = maxOf(220 - profile.age, 1)

        val daily = RestingHR.dailyValues(hr, segments, zone = zone)
        val baseline = ExerciseMinutes.restingBaseline(hr)
        val pieces = ExerciseMinutes.elevatedPieces(hr, maxHR, sleepWindow)
        val legacy = Calories.legacyDailyEstimate(hr, steps, profile, sleepWindow)
        val out = mutableListOf(
            "rhr ${optD(RestingHR.value(hr, segments))}",
            "rhrdaily ${daily.size}" + daily.joinToString("") { " ${it.day.toEpochMilli()} ${d(it.bpm)}" },
            "rb ${optD(baseline)}",
            "thr $maxHR ${ExerciseMinutes.threshold(maxHR)} ${ExerciseMinutes.threshold(maxHR, baseline)}",
            "pieces ${pieces.size}" + pieces.joinToString("") { " ${it.start.toEpochMilli()} ${it.end.toEpochMilli()} ${it.bpm}" },
            "minutes ${d(ExerciseMinutes.estimate(hr, maxHR, sleepWindow))} " +
                d(ExerciseMinutes.estimate(hr, maxHR, sleepWindow, deriveRestingHR = true)),
            "legacy ${d(legacy.activeKcal)} ${d(legacy.elevatedMinutes)}",
        )
        for ((k, w) in widths.withIndex()) {
            val e = Calories.dailyEstimate(hr, steps, profile, sleepWindow, windows, dayStart = day, bucketSeconds = w)
            out += "est $k ${d(e.activeKcal)} ${d(e.elevatedMinutes)} ${e.buckets.size}" + e.buckets.joinToString("") {
                " ${it.start.toEpochMilli()} ${it.end.toEpochMilli()} ${d(it.hrKcal)} ${d(it.stepKcal)} ${d(it.elevatedMinutes)}"
            }
        }
        out += renderLedger(c, profile, day, steps, hr, windows, sleepWindow)
        return out
    }

    private fun planLine(tag: String, k: Int, p: ActiveEnergyLedger.Plan): String {
        val nonZero = p.watermarks.withIndex().filter { it.value != 0.0 }
        return "$tag $k ${p.writes.size}" + p.writes.joinToString("") { " ${it.start.toEpochMilli()} ${it.end.toEpochMilli()} ${d(it.kcal)}" } +
            " ${p.watermarks.size} ${nonZero.size}" + nonZero.joinToString("") { " ${it.index} ${d(it.value)}" } +
            " ${d(p.carryRemaining)} ${d(p.workoutConsumed)} ${d(p.totalKcal)}"
    }

    /** The state a caller commits after each plan: marks, carry, the saved total and the workout energy credited. */
    private class LedgerState(var marks: List<Double> = emptyList(), var carry: Double = 0.0, var saved: Double = 0.0, var credited: Double = 0.0)

    /** The day's write-ledger lines: two flush replays, seeding, write windows and (hostile days) unreadable states. */
    private fun renderLedger(
        c: VCase,
        profile: UserProfile,
        day: Instant,
        steps: Int,
        hr: List<HRSample>,
        windows: List<StepWindow>,
        sleepWindow: DateInterval?,
    ): List<String> {
        fun estimate(h: List<HRSample>, w: List<StepWindow>, st: Int) =
            Calories.dailyEstimate(h, st, profile, sleepWindow, w, dayStart = day, bucketSeconds = 900.0)
        fun placed(w: List<StepWindow>): Int = w.filter { it.delta > 0 }.sumOf { it.delta }
        val out = mutableListOf<String>()
        fun flush(tag: String, k: Int, e: Calories.DailyEstimate, now: Instant, workout: Double, s: LedgerState) {
            val p = ActiveEnergyLedger.plan(e.buckets, s.marks, day, now, s.carry, workout - s.credited, s.saved)
            out += planLine(tag, k, p)
            s.marks = p.watermarks
            s.carry = p.carryRemaining
            s.saved += p.totalKcal
            s.credited += p.workoutConsumed
        }

        val cuts = tokens(c, "lp").map { milli(it) }
        val workouts = tokens(c, "lw").map { toDouble(it) }
        val inOrder = LedgerState()
        for ((k, cut) in cuts.withIndex()) {
            val h = hr.filter { it.start < cut }
            val w = windows.filter { it.end <= cut }
            flush("lplan", k, estimate(h, w, if (k >= cuts.size - 2) steps else placed(w)), cut, workouts[k], inOrder)
        }

        val arrivals = tokens(c, "la").map { milli(it) }
        fun batches(tag: String, n: Int): List<Int> = tokens(c, tag).single().let { t -> if (t == "-") emptyList() else t.map { it - '0' } }
            .also { check(it.size == n) { "${c.id}: $tag has ${it.size} batches for $n items" } }
        val hrBatch = batches("lah", hr.size)
        val windowBatch = batches("law", windows.size)
        val outOfOrder = LedgerState()
        for ((k, now) in arrivals.withIndex()) {
            val h = hr.filterIndexed { i, _ -> hrBatch[i] <= k }
            val w = windows.filterIndexed { i, _ -> windowBatch[i] <= k }
            flush("aplan", k, estimate(h, w, if (k == arrivals.size - 1) steps else placed(w)), now, 0.0, outOfOrder)
        }

        val whole = estimate(hr, windows, steps)
        for ((k, legacy) in tokens(c, "ls").map { toDouble(it) }.withIndex()) {
            val s = ActiveEnergyLedger.seed(whole.buckets, legacy, day)
            val nonZero = s.watermarks.withIndex().filter { it.value != 0.0 }
            out += "seed $k ${s.watermarks.size} ${nonZero.size}" + nonZero.joinToString("") { " ${it.index} ${d(it.value)}" } + " ${d(s.carry)}"
            out += planLine("splan", k, ActiveEnergyLedger.plan(whole.buckets, s.watermarks, day, day.plusSeconds(26 * 3600L), s.carry))
        }

        fun optMilli(t: String): Instant? = if (t == "-") null else milli(t)
        out += "win" + tokens(c, "rq").chunked(4).joinToString("") { (anchor, notBefore, now, kcal) ->
            ActiveEnergyWindow.resolve(optMilli(anchor), optMilli(notBefore), milli(now), day, toDouble(kcal))
                ?.let { " ${it.start.toEpochMilli()} ${it.end.toEpochMilli()}" } ?: " - -"
        }

        if (c.shape == "hostile") {
            val bad = listOf(
                listOf(Double.NaN) to Triple(0.0, 0.0, 0.0), listOf(-40.0) to Triple(0.0, 0.0, 0.0),
                emptyList<Double>() to Triple(Double.NaN, 0.0, 0.0), emptyList<Double>() to Triple(0.0, Double.NaN, 0.0),
                emptyList<Double>() to Triple(0.0, 0.0, Double.NaN),
            )
            for ((k, b) in bad.withIndex()) {
                val (marks, scalars) = b
                out += planLine("bad", k, ActiveEnergyLedger.plan(whole.buckets, marks, day, day.plusSeconds(26 * 3600L), scalars.first, scalars.second, scalars.third))
            }
        }
        return out
    }

    // --- comparison ---

    /**
     * Lines whose port deliberately differs from upstream's Swift answer (which stays exactly as the
     * generator wrote it): each allowed divergence is reported, an unlisted one fails, and a listed
     * one that no longer diverges fails as stale.
     */
    private val DELIBERATE_DIVERGENCES: Map<String, Set<Divergence>> = mapOf(
        // PORTING D-71: a NaN or infinite resting HR or baseline reads as missing (scale 1.0), where
        // upstream clamps it to 0.8 or 1.2. The four unreadable basal queries of every "nonfinite"
        // energy case (NaN, +Inf and -Inf resting HR against a 60 bpm baseline; +Inf baseline).
        "an unreadable resting-HR reading is missing" to (20..29).flatMap { k ->
            (10..13).map { q -> Divergence("energy-%03d".format(k), "basal $q") }
        }.toSet(),
        // PORTING D-74: a bucket width below one second or above one billion seconds is not
        // attributed (the legacy estimate, no buckets), where upstream attributes it (half-second
        // buckets; one 2e9-second bucket). Widths 3 (0.5 s) and 4 (2e9 s) of every "hostile" day.
        "a bucket width outside one second to one billion seconds is not attributed" to (44..47).flatMap { k ->
            listOf(3, 4).map { w -> Divergence("day-%03d".format(k), "est $w") }
        }.toSet(),
        // PORTING D-79: the energy ledger writes nothing from a stored state it cannot read (a NaN or
        // negative watermark, a NaN carry, workout credit or saved total), where upstream writes the day.
        // All five unreadable states of every "hostile" day.
        "an unreadable stored ledger state writes nothing" to (44..47).flatMap { k ->
            (0..4).map { b -> Divergence("day-%03d".format(k), "bad $b") }
        }.toSet(),
    )

    /** The tokens that identify a golden line among its case's lines: the first two for indexed kinds, else the first. */
    private fun lineKind(line: String): String {
        val t = line.split(' ')
        val n = if (t[0] in setOf(
                "roll", "sum", "trimp", "strain", "trimphr", "kcal", "dist", "steps", "keytel", "basal", "baseline", "est",
                "lplan", "aplan", "seed", "splan", "bad",
            )
        ) {
            2
        } else {
            1
        }
        return t.take(n).joinToString(" ")
    }

    private class Report {
        val mismatches = mutableListOf<String>()
        val nonIdentical = mutableListOf<String>()
        val allowed = mutableMapOf<String, MutableList<String>>()
        val allowedSeen = mutableSetOf<Divergence>()
        var doubles = 0
        var lines = 0
    }

    private fun allowedBy(divergences: Map<String, Set<Divergence>>, id: String, kind: String): String? =
        divergences.entries.firstOrNull { Divergence(id, kind) in it.value }?.key

    /** Compare one case's lines: whole lines, token by token, exact except tolerated doubles and listed divergences. */
    private fun compare(
        id: String,
        expected: List<String>,
        actual: List<String>,
        report: Report,
        divergences: Map<String, Set<Divergence>> = DELIBERATE_DIVERGENCES,
    ) {
        report.lines += expected.size
        if (expected.size != actual.size) {
            report.mismatches += "$id: ${expected.size} golden lines vs ${actual.size} Kotlin lines"
            return
        }
        for (k in expected.indices) {
            val e = expected[k]
            val a = actual[k]
            val kind = lineKind(e)
            val improvement = allowedBy(divergences, id, kind)
            if (improvement != null && e != a && lineKind(a) == kind) {
                report.allowed.getOrPut(improvement) { mutableListOf() } += "$id: golden '$e' kotlin '$a'"
                report.allowedSeen += Divergence(id, kind)
                continue
            }
            val et = e.split(' ')
            val at = a.split(' ')
            if (et.size != at.size) {
                report.mismatches += "$id line ${k + 1}: golden '$e' vs kotlin '$a'"
                continue
            }
            for (j in et.indices) {
                if (isDouble(et[j]) && isDouble(at[j])) {
                    report.doubles++
                    if (et[j] == at[j]) continue
                    val ed = toDouble(et[j])
                    val ad = toDouble(at[j])
                    if ((ed.isNaN() && ad.isNaN()) || abs(ed - ad) <= 1e-9) {
                        report.nonIdentical += "$id ${et[0]}: golden $ed (${et[j]}) kotlin $ad (${at[j]}) |Δ| ${abs(ed - ad)}"
                    } else {
                        report.mismatches += "$id line ${k + 1}: golden '$e' ($ed) vs kotlin '$a' ($ad)"
                        break
                    }
                } else if (et[j] != at[j]) {
                    report.mismatches += "$id line ${k + 1}: golden '$e' vs kotlin '$a'"
                    break
                }
            }
        }
    }

    private fun staleEntries(report: Report, divergences: Map<String, Set<Divergence>> = DELIBERATE_DIVERGENCES): List<String> =
        divergences.flatMap { (improvement, entries) -> entries.filter { it !in report.allowedSeen }.map { "$improvement: $it" } }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun kotlinMatchesTheSwiftGoldensForEveryCase() {
        val inputs = inputs()
        val goldens = goldens()
        assertEquals(goldens.keys.toList(), inputs.map { it.id }, "inputs and goldens list the same cases in the same order")
        val report = Report()
        for (c in inputs) compare(c.id, goldens.getValue(c.id), render(c), report)

        println(
            "vitals differential: ${inputs.size} cases, ${report.lines} golden lines, ${report.doubles} doubles, " +
                "${report.nonIdentical.size} not bit-identical (tolerated within 1e-9), ${report.mismatches.size} mismatches",
        )
        report.nonIdentical.forEach { println("  not bit-identical: $it") }
        for ((improvement, entries) in DELIBERATE_DIVERGENCES) {
            val seen = report.allowed[improvement].orEmpty()
            println("  deliberate divergence '$improvement': ${seen.size} golden line(s) in ${entries.size} listed case line kind(s)")
            seen.forEach { println("    $it") }
        }
        assertTrue(report.mismatches.isEmpty(), "${report.mismatches.size} mismatch(es) with upstream:\n" + report.mismatches.joinToString("\n"))
        val stale = staleEntries(report)
        assertTrue(stale.isEmpty(), "${stale.size} listed deliberate divergence(s) no longer diverge — remove them:\n" + stale.joinToString("\n"))
    }

    @Test
    fun goldenSetCoversEveryShapeAndBranchWithinItsSizeBound() {
        val inputLines = lines("inputs.txt").size
        val goldenLines = lines("goldens.txt").size
        assertTrue(inputLines + goldenLines < 20_000, "inputs + goldens must stay under 20 000 lines, got ${inputLines + goldenLines}")

        val shapes = inputs().groupingBy { "${it.kind}/${it.shape}" }.eachCount()
        val expected = listOf(
            "rr/rest", "rr/exercise", "rr/artifact", "rr/duplicated", "rr/constant", "rr/short", "rr/tiny", "rr/extreme", "rr/tied",
            "bpm/rest", "bpm/workout", "bpm/mixed", "bpm/short", "bpm/exact", "bpm/duplicated", "bpm/extreme",
            "hrs/day", "hrs/spring-forward", "hrs/fall-back", "hrs/reversed", "hrs/duplicated", "hrs/quarter-second", "hrs/sparse", "hrs/point",
            "energy/typical", "energy/edge-profile", "energy/nonfinite",
            "day/workday", "day/spring-forward", "day/fall-back", "day/duplicated", "day/unsorted", "day/steps-only",
            "day/hr-only", "day/straddle", "day/sparse", "day/sleep-heavy", "day/spans", "day/hostile",
        )
        for (s in expected) assertTrue((shapes[s] ?: 0) >= 3, "shape $s has ${shapes[s] ?: 0} cases")

        // Every named branch was reached by at least one case (counted by the generator from upstream's answers).
        val coverage = coverage()
        for (branch in listOf(
            "hrv-rmssd-none", "hrv-rolling-empty", "hrv-summary-none", "hrv-clean-dropped", "hrv-duplicated-groups", "hrv-64-bit",
            "stress-degenerate", "stress-capped", "stress-scored", "stress-negative-input",
            "strain-none-few", "strain-none-params", "strain-zero", "strain-above-21", "strain-scored", "strain-duplicated-series",
            "trimp-nan", "trimp-interval-fallback",
            "kcal-zero", "kcal-positive", "hrs-clock-change-day", "hrs-duration-fallback", "hrs-fractional-duration", "hrs-duplicated-samples",
            "bmr-negative", "dist-nonpositive", "keytel-zero", "keytel-positive",
            "scale-neutral", "scale-clamped-high", "scale-clamped-low", "scale-linear", "scale-nonfinite-input",
            "baseline-none", "baseline-plain-mean", "baseline-trimmed", "baseline-nonfinite",
            "day-rhr-sleep-mean", "day-rhr-isolated", "day-rhr-multi-day", "day-clock-change", "day-no-midnight",
            "day-baseline-derived", "day-baseline-none", "day-sleep-excluded", "day-pieces-none", "day-span-samples",
            "day-attributed", "day-legacy-fallback", "day-netted-bucket", "day-residual-steps", "day-straddling-window",
            "day-duplicated-samples", "day-subsecond-width", "day-wide-width",
            "ledger-write", "ledger-nothing-new", "ledger-late-bucket", "ledger-fall-netted", "ledger-workout-consumed",
            "ledger-clamped-to-now", "ledger-unreadable-state-written", "seed-carry", "seed-then-write",
            "win-none", "win-day-floor", "win-widened",
        )) {
            assertTrue((coverage[branch] ?: 0) >= 1, "branch $branch never reached (${coverage[branch] ?: 0})")
        }
    }

    @Test
    fun comparatorFailsOnEveryKindOfDifferenceAndOnAStaleEntry() {
        val golden = listOf("rmssd 100", "stress ${d(0.25)}", "basal 3 ${d(1.0)} ${d(74.0)}")
        fun run(actual: List<String>, divergences: Map<String, Set<Divergence>> = emptyMap()) =
            Report().also { compare("x", golden, actual, it, divergences) }

        assertTrue(run(golden).let { it.mismatches.isEmpty() && it.nonIdentical.isEmpty() }, "identical lines pass silently")
        assertEquals(1, run(listOf("rmssd 101", golden[1], golden[2])).mismatches.size, "a changed integer fails")
        assertEquals(1, run(listOf(golden[0], "stress ${d(0.25 + 1e-6)}", golden[2])).mismatches.size, "a double off by more than 1e-9 fails")
        val near = run(listOf(golden[0], "stress ${d(Math.nextUp(0.25))}", golden[2]))
        assertTrue(near.mismatches.isEmpty() && near.nonIdentical.size == 1, "a double within 1e-9 is tolerated and listed")
        assertEquals(1, run(golden.dropLast(1)).mismatches.size, "a missing line fails")
        val listed = mapOf("probe" to setOf(Divergence("x", "basal 3")))
        val moved = run(listOf(golden[0], golden[1], "basal 3 ${d(0.8)} ${d(59.2)}"), listed)
        assertTrue(moved.mismatches.isEmpty() && staleEntries(moved, listed).isEmpty(), "a listed line may differ")
        assertEquals(1, staleEntries(run(golden, listed), listed).size, "a listed line that no longer differs is stale")
        assertEquals(1, run(listOf(golden[0], golden[1], "basal 3 ${d(0.8)} ${d(59.2)}")).mismatches.size, "an unlisted line may not differ")
    }
}
