package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.TrendsEngine.DailyPoint
import org.junit.jupiter.api.Timeout
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Differential check of the engines' named sites against upstream's own Swift code:
 * `tools/sleep-differential` (its `EngineDifferential` generator, `regenerate.sh engine`) ran the
 * pinned Swift functions over seeded synthetic inputs and wrote the inputs and canonical outputs to
 * `src/test/resources/engine-differential/`. This test runs the Kotlin port over the same inputs,
 * renders the same canonical lines and compares them.
 *
 * The sites are the ones where a line-for-line port can silently differ: the trends engine's rolling
 * averages (every metric's guard, summation order, 64-bit counts) and trend direction over typical,
 * sparse, guard-edge, unreadable, short, long, stepped, exactly-at-threshold and zero-prior day lists;
 * sleep regularity over regular, irregular, midnight-straddling, outside-the-day, opposite and short
 * bedtimes, and for every constant bedtime at three lengths (each answer 99 or 100 depending on the
 * last bits of the platform maths); and the platform's own `cos`, `sin` and `log` at the regularity's
 * arguments, compared with `StrictMath`. The headache signals index: whole assessments over typical,
 * deviant, sparse, cold-start, ring-silent, flagged, finite-extreme, unreadable, banded and capped days
 * (verdict, every contribution, and the quotient the index is rounded from), one series swept across
 * every half of the index's rounding, and the band's percentile. Its evaluation: midranks over ties,
 * signed zeros and NaN (past Swift's insertion-sort size too), the AUC and its Hanley-McNeil error,
 * the exact hypergeometric tail (`log` / `exp`, with whether each value clears the 0.01 working
 * alpha, so a last-bit difference that flipped it would show) and the Wilson bound. Cycle prediction:
 * the statistics and the predicted dates of whole-day, fractional-second, stale (rolled forward for
 * centuries, to Foundation's distant future), clock-change (New York 2026), hostile, skin-temperature
 * and edge histories — every date as Foundation holds it, a double of seconds since 2001, converted to
 * and from the port's `Instant`s by `FoundationDate`. Ring proximity: the path-loss distance (`pow`) in
 * metres and feet and its display text, at every reading from −110 to +10 dBm and at the sentinels and
 * 32-bit ends. The format is documented at the top of the generator's `main.swift`.
 *
 * Comparison rule: every line is compared WHOLE, token by token and exactly, except doubles (tokens
 * `d` + 16 hex digits), which must agree within 1e-9 — or, for a predicted date (a `pr` line), within
 * 1e-6 s; every double that is not bit-identical is listed in the report this test prints. The goldens come only from the Swift generator, never from
 * this code's output. Where the port deliberately differs from upstream, the lines that move are
 * named in [DELIBERATE_DIVERGENCES] (case + line kind, with the `PORTING.md` D-row) and reported; no
 * other line may differ, and a listed line that stops differing fails as stale.
 *
 * The branches the cases must reach are not listed here: they are read from the generator's own code
 * (every branch-counting call names its branch with a string literal), so a branch added there and
 * never reached fails here without anyone updating a list.
 */
class EngineDifferentialTest {

    private class ECase(val id: String, val kind: String, val shape: String, val lines: List<String>)

    private data class Divergence(val case: String, val kind: String)

    private fun d(x: Double): String = "d" + java.lang.Long.toHexString(java.lang.Double.doubleToRawLongBits(x)).padStart(16, '0')
    private fun optD(x: Double?): String = x?.let { d(it) } ?: "-"
    private fun isDouble(t: String): Boolean = t.length == 17 && t[0] == 'd' && t.substring(1).all { it in '0'..'9' || it in 'a'..'f' }
    private fun toDouble(t: String): Double = java.lang.Double.longBitsToDouble(java.lang.Long.parseUnsignedLong(t.substring(1), 16))
    private fun inDouble(t: String): Double? = if (t == "-") null else toDouble(t.also { require(isDouble(it)) { "bad double token $it" } })
    private fun inInt(t: String): Int? = if (t == "-") null else t.toInt()
    private fun optI(x: Int?): String = x?.toString() ?: "-"

    // --- reading the generator's files (each read once per test) ---

    private fun lines(name: String): List<String> {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream("engine-differential/$name")) { "missing test resource $name" }
        return stream.bufferedReader(Charsets.UTF_8).use { it.readLines() }.filter { !it.startsWith("#") }
    }

    private fun inputs(): List<ECase> {
        val all = lines("inputs.txt")
        val out = mutableListOf<ECase>()
        var i = 0
        while (i < all.size) {
            val head = all[i].split(' ')
            check(head.size == 4 && head[0] == "case") { "bad case header at input line ${i + 1}: ${all[i]}" }
            val body = mutableListOf<String>()
            i++
            while (all[i] != "end") body += all[i++]
            out += ECase(head[1], head[2], head[3], body)
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

    /** The generator's code lines, `//` comments removed (prose may name what the code does). */
    private fun generatorCode(): List<String> {
        val root = assertNotNull(System.getProperty("opencircuit.androidRoot"), "system property opencircuit.androidRoot is not set — see ringkit/build.gradle.kts")
        val f = File(root, "tools/sleep-differential/Sources/EngineDifferential/main.swift")
        assertTrue(f.isFile, "generator source not found at $f")
        return f.readLines().map { it.substringBefore("//") }
    }

    private val hitCall = Regex("""\bhit\(""")
    private val namedHit = Regex("""\bhit\(\s*"([^"]+)"""")

    /** Every branch the generator's code names; the one non-literal `hit(` is its declaration. */
    private fun namedBranches(code: List<String>): Set<String> {
        val unnamed = code.filter { line -> hitCall.findAll(line).count() > namedHit.findAll(line).count() }.map { it.trim() }
        assertEquals(1, unnamed.size, "every branch-counting call must name its branch with a string literal: $unnamed")
        assertTrue(unnamed.single().startsWith("func hit("), "the only unnamed hit( is the declaration: ${unnamed.single()}")
        return code.flatMap { line -> namedHit.findAll(line).map { it.groupValues[1] }.toList() }.toSet()
    }

    // --- rendering the Kotlin port's canonical lines ---

    private fun render(c: ECase): List<String> = when (c.kind) {
        "days" -> renderDays(c)
        "bed" -> renderBed(c)
        "flat" -> renderFlat(c)
        "angles" -> renderAngles(c)
        "assess" -> renderVerdict(HeadacheSignals.assess(headacheDay(c)))
        "ix" -> renderIndexSweep(c)
        "pct" -> renderPercentile(c)
        "rank" -> renderRank(c)
        "se" -> renderStandardErrors(c)
        "tail" -> renderTail(c)
        "cycle" -> renderCycle(c)
        "prox" -> renderProximity(c)
        else -> error("unknown kind ${c.kind}")
    }

    // --- ring proximity (the path-loss distance through `pow`, and its text as UTF-8 bytes) ---

    private fun utf8Token(s: String): String = "u" + s.toByteArray(Charsets.UTF_8).joinToString("") { b -> Integer.toHexString(b.toInt() and 0xff).padStart(2, '0') }

    private fun renderProximity(c: ECase): List<String> {
        val readings = c.lines.single { it.startsWith("r ") }.split(' ').drop(1).map(::inInt)
        return listOf(
            "pm" + readings.joinToString("") { " " + optD(RingProximity.approximateMeters(it)) },
            "pf" + readings.joinToString("") { " " + optD(RingProximity.approximateFeet(it)) },
            "pt" + readings.joinToString("") { " " + (RingProximity.distanceText(it)?.let(::utf8Token) ?: "-") },
        )
    }

    // --- cycle prediction (dates are doubles of seconds since 2001, Foundation's own form) ---

    private fun renderCycle(c: ECase): List<String> {
        fun date(t: String): java.time.Instant = FoundationDate.reference(toDouble(t))
        val entries = c.lines.filter { it.startsWith("e ") }.map { line ->
            val t = line.split(' ')
            CyclePredictor.PeriodEntry(start = date(t[1]), end = if (t[2] == "-") null else date(t[2]))
        }
        val nights = c.lines.filter { it.startsWith("t ") }.map { line ->
            val t = line.split(' ')
            CyclePredictor.SkinTempNight(night = date(t[1]), offsetC = toDouble(t[2]))
        }
        val stats = CyclePredictor.cycleStats(entries)
        val out = mutableListOf("st " + (stats?.let { "${d(it.avgCycleLengthDays)} ${it.sampleCount} ${optD(it.avgPeriodDurationDays)}" } ?: "- - -"))
        doubles(c, "n").forEachIndexed { k, now ->
            val p = CyclePredictor.predict(entries, nights, now = FoundationDate.reference(now))
            out += "pr $k " + (
                p?.let {
                    listOf(it.nextPeriodStart, it.nextPeriodEnd, it.fertileWindowStart, it.ovulationEstimate)
                        .joinToString(" ") { t -> d(FoundationDate.referenceSeconds(t)) } + if (it.tempCorroborated) " 1" else " 0"
                } ?: "-"
                )
        }
        return out
    }

    // --- the headache signals index and its evaluation ---

    private fun ints(c: ECase, prefix: String): List<Int> =
        c.lines.single { it == prefix || it.startsWith("$prefix ") }.split(' ').drop(1).map { it.toInt() }

    private fun doubles(c: ECase, prefix: String): List<Double> =
        c.lines.single { it == prefix || it.startsWith("$prefix ") }.split(' ').drop(1).map { toDouble(it) }

    private fun headacheDay(c: ECase): HeadacheSignals.DayInput {
        val t = c.lines.single { it.startsWith("d ") }.split(' ').drop(1)
        check(t.size == 11) { "${c.id}: bad day line" }
        fun at(s: String): Instant? = if (s == "-") null else Instant.ofEpochSecond(s.toLong())
        val series = c.lines.filter { it.startsWith("s ") }.associate { line ->
            val s = line.split(' ')
            s[1] to HeadacheSignals.Series(today = toDouble(s[2]), prior = s.drop(3).map { toDouble(it) })
        }
        return HeadacheSignals.DayInput(
            day = at(t[0])!!, now = at(t[1])!!, lastRingDataAt = at(t[2]),
            restingHR = series["rhr"], hrvSDNN = series["hrv"], sleepEfficiencyPct = series["eff"],
            sleepFragmentationMin = series["frag"], sleepDurationMin = series["dur"],
            skinTempOffsetC = inDouble(t[7]), inBedStartMinutes = inInt(t[8]), priorInBedStartMinutes = ints(c, "b"),
            dayHRPrevious = inDouble(t[9]), dayHRTwoDaysAgo = inDouble(t[10]), dayHRPrior = doubles(c, "h"),
            isPerimenstrual = if (t[6] == "-") null else t[6] == "1",
            sleepLikelyTruncated = t[3] == "1", feverSuspected = t[4] == "1", headacheAlreadyLoggedToday = t[5] == "1",
            priorIndices = ints(c, "i"),
        )
    }

    /** The quotient the index is rounded from, formed as upstream forms it from the capped contributions. */
    private fun quotient(a: HeadacheSignals.Assessment): Double {
        val total = a.contributions.fold(0.0) { acc, c -> acc + c.effectiveWeight }
        val weighted = a.contributions.fold(0.0) { acc, c -> acc + c.effectiveWeight * (c.contribution ?: 0.0) }
        return 100 * weighted / total
    }

    private fun renderVerdict(v: HeadacheSignals.Verdict): List<String> = when (v) {
        HeadacheSignals.Verdict.NotEnabled -> listOf("v notEnabled", "c -", "q -")
        is HeadacheSignals.Verdict.BuildingBaseline -> listOf("v building ${v.daysRemaining}", "c -", "q -")
        is HeadacheSignals.Verdict.Interrupted -> listOf("v interrupted " + (v.since?.epochSecond?.toString() ?: "-"), "c -", "q -")
        is HeadacheSignals.Verdict.InsufficientData -> listOf(
            "v insufficient" + HeadacheSignals.Feature.entries.mapNotNull { f -> v.missing[f]?.let { " ${f.rawValue}=${it.rawValue}" } }.joinToString(""),
            "c -",
            "q -",
        )
        is HeadacheSignals.Verdict.Scored -> {
            val a = v.assessment
            listOf(
                "v scored ${a.index} ${a.band.rawValue} ${a.ringFeatureCount} ${d(a.coverageFraction)} ${a.suppressedBy?.rawValue ?: "-"}",
                "c " + a.contributions.joinToString(" ") { k ->
                    "${k.feature.rawValue} ${optD(k.z)} ${optD(k.contribution)} ${d(k.effectiveWeight)} ${k.absentReason?.rawValue ?: "-"}"
                },
                "q ${d(quotient(a))}",
            )
        }
    }

    private fun renderIndexSweep(c: ECase): List<String> {
        val base = headacheDay(c)
        val x = c.lines.single { it.startsWith("x ") }.split(' ')
        val (name, today0, step, count) = listOf(x[1], toDouble(x[2]), toDouble(x[3]), x[4].toInt())
        val indices = mutableListOf<Int>()
        val quotients = mutableListOf<String>()
        for (k in 0 until count as Int) {
            val today = (today0 as Double) + k.toDouble() * (step as Double)
            fun moved(s: HeadacheSignals.Series?) = s?.copy(today = today)
            val day = when (name) {
                "rhr" -> base.copy(restingHR = moved(base.restingHR))
                "hrv" -> base.copy(hrvSDNN = moved(base.hrvSDNN))
                "eff" -> base.copy(sleepEfficiencyPct = moved(base.sleepEfficiencyPct))
                "frag" -> base.copy(sleepFragmentationMin = moved(base.sleepFragmentationMin))
                "dur" -> base.copy(sleepDurationMin = moved(base.sleepDurationMin))
                else -> error("${c.id}: unknown series $name")
            }
            val a = (HeadacheSignals.assess(day) as? HeadacheSignals.Verdict.Scored)?.assessment ?: error("${c.id}: sweep day $k did not score")
            indices += a.index
            quotients += d(quotient(a))
        }
        return (0 until count step 64).flatMap { k0 ->
            listOf(
                "ix $k0" + indices.subList(k0, k0 + 64).joinToString("") { " $it" },
                "iq $k0 " + quotients.subList(k0, k0 + 64).joinToString(" "),
            )
        }
    }

    private fun renderPercentile(c: ECase): List<String> {
        val sorted = doubles(c, "p")
        return listOf("pc" + doubles(c, "f").joinToString("") { " " + d(HeadacheSignals.percentile(sorted, it)) })
    }

    private fun renderRank(c: ECase): List<String> {
        val t = c.lines.single { it.startsWith("r ") }.split(' ')
        val nPos = t[1].toInt()
        val values = t.drop(2).map { toDouble(it) }
        val a = HeadacheEvaluation.auc(values.take(nPos), values.drop(nPos))
        val se = a?.let { HeadacheEvaluation.hanleyMcNeilSE(auc = it, nPos = nPos, nNeg = values.size - nPos) }
        return listOf("mr" + HeadacheEvaluation.midranks(values).joinToString("") { " " + d(it) }, "au ${optD(a)} ${optD(se)}")
    }

    private fun renderStandardErrors(c: ECase): List<String> {
        val (nPos, nNeg) = ints(c, "n")
        return listOf("se" + doubles(c, "a").joinToString("") { " " + optD(HeadacheEvaluation.hanleyMcNeilSE(auc = it, nPos = nPos, nNeg = nNeg)) })
    }

    private fun renderTail(c: ECase): List<String> {
        val tail = c.lines.filter { it.startsWith("q ") }.joinToString(" ") { line ->
            val (o, f, p, n) = line.split(' ').drop(1).map { it.toInt() }
            val v = HeadacheEvaluation.hypergeometricUpperTail(observed = o, flagged = f, positives = p, total = n)
            optD(v) + " " + (v?.let { if (it <= 0.01) "w" else "n" } ?: "-")
        }
        val wilson = c.lines.filter { it.startsWith("w ") }.joinToString("") { line ->
            val s = line.split(' ')
            " " + optD(HeadacheEvaluation.wilsonUpperBound(successes = s[1].toInt(), trials = s[2].toInt(), z = toDouble(s[3])))
        }
        return listOf("tl $tail", "wl$wilson")
    }

    private fun angle(m: Int): Double = 2.0 * Math.PI * m.toDouble() / 1440.0

    private fun renderDays(c: ECase): List<String> {
        val points = c.lines.filter { it.startsWith("p ") }.mapIndexed { i, line ->
            val t = line.split(' ').drop(1)
            check(t.size == 17) { "${c.id}: bad point $line" }
            val x = t.drop(4).map(::inDouble)
            DailyPoint(
                date = Instant.EPOCH.plusSeconds(86_400L * i), // a label: the engine never reads it
                steps = inInt(t[0]), sleepMinutes = inInt(t[1]), sleepScore = inInt(t[2]), stressScore = inInt(t[3]),
                skinTempC = x[0], dayTempC = x[1], sleepHRAvg = x[2], sleepHRVAvg = x[3], sleepSpO2Avg = x[4],
                sleepRRAvg = x[5], dayHRAvg = x[6], dayHRVAvg = x[7], daySpO2Avg = x[8], dayRRAvg = x[9],
                activeEnergyKcal = x[10], distanceM = x[11], exerciseMin = x[12],
            )
        }
        val groups = c.lines.single { it.startsWith("w ") }.removePrefix("w ").split(" / ").map { it.trim().split(' ') }
        check(groups.size == 3) { "${c.id}: bad query line" }
        val avgWindows = groups[0].map { it.toInt() }
        val trendWindows = groups[1].map { it.toInt() }
        val minDeltas = groups[2].map { toDouble(it) }
        val extractors: List<(DailyPoint) -> Double?> = listOf(
            { it.steps?.toDouble() }, { it.sleepScore?.toDouble() }, { it.skinTempC },
            { it.sleepHRAvg }, { it.dayHRVAvg }, { it.distanceM },
        )
        val out = mutableListOf<String>()
        for (w in avgWindows) {
            val r = TrendsEngine.rollingAverages(points, window = w)
            val vals = listOf(
                r.steps, r.sleepMinutes, r.sleepScore, r.stressScore, r.skinTempC, r.dayTempC, r.sleepHRAvg,
                r.sleepHRVAvg, r.sleepSpO2Avg, r.sleepRRAvg, r.dayHRAvg, r.dayHRVAvg, r.daySpO2Avg, r.dayRRAvg,
                r.activeEnergyKcal, r.distanceM, r.exerciseMin,
            )
            out += "avg $w" + vals.joinToString("") { " " + optD(it) }
        }
        for (w in trendWindows) {
            val tokens = extractors.flatMap { ex ->
                minDeltas.map { m -> TrendsEngine.trend(points, window = w, minDeltaFraction = m, extract = ex)?.rawValue ?: "-" }
            }
            out += "tr $w " + tokens.joinToString(" ")
        }
        return out
    }

    private fun renderBed(c: ECase): List<String> {
        fun ints(prefix: String): List<Int> =
            c.lines.single { it == prefix || it.startsWith("$prefix ") }.split(' ').drop(1).map { it.toInt() }
        val minutes = ints("m")
        val windows = ints("w")
        return listOf(
            "reg " + windows.joinToString(" ") { optI(TrendsEngine.sleepRegularity(minutes, window = it)) },
            "cs" + minutes.joinToString("") { " " + d(StrictMath.cos(angle(it))) + " " + d(StrictMath.sin(angle(it))) },
        )
    }

    private fun renderFlat(c: ECase): List<String> {
        val count = c.lines.single { it.startsWith("n ") }.split(' ')[1].toInt()
        return (0 until 1440 step 144).map { x0 ->
            "fl $x0 " + (x0 until x0 + 144).joinToString(" ") { x -> optI(TrendsEngine.sleepRegularity(List(count) { x })) }
        }
    }

    private fun renderAngles(c: ECase): List<String> {
        val (lo, hi) = c.lines.single { it.startsWith("range ") }.split(' ').drop(1).map { it.toInt() }
        val lx = c.lines.single { it.startsWith("lx ") }.split(' ').drop(1).map { toDouble(it) }
        val out = (lo until hi step 40).map { m0 ->
            "cs $m0" + (m0 until m0 + 40).joinToString("") { " " + d(StrictMath.cos(angle(it))) + " " + d(StrictMath.sin(angle(it))) }
        }
        return out + ("ln" + lx.joinToString("") { " " + d(StrictMath.log(it)) })
    }

    // --- comparison ---

    /**
     * Lines whose port deliberately differs from upstream's Swift answer (which stays exactly as the
     * generator wrote it): each allowed divergence is reported, an unlisted one fails, and a listed
     * one that no longer diverges fails as stale.
     */
    private val DELIBERATE_DIVERGENCES: Map<String, Set<Divergence>> = mapOf(
        // PORTING D-107: bedtimes that all name one clock minute score exactly 100, where upstream's
        // floating-point R lands within an ulp of 1 and the truncated score is 99 for 1 178 of the
        // 4 320 constant lists. Every line of the three "constant" cases holds such a 99.
        "every night at one clock minute scores exactly 100" to (0..2).flatMap { k ->
            (0 until 1440 step 144).map { x0 -> Divergence("flat-%03d".format(k), "fl $x0") }
        }.toSet(),
        // PORTING D-108: an unreadable (NaN or infinite) reading for today is a missing reading, where
        // upstream reads it as an ordinary 0. The four "unreadable" day cases each hold one, so their
        // verdict, contributions and quotient all move.
        "an unreadable reading for today is a missing reading" to (28..31).flatMap { k ->
            listOf("v", "c", "q").map { kind -> Divergence("assess-%03d".format(k), kind) }
        }.toSet(),
    )

    /**
     * How far a double may sit from upstream's: 1e-9, except a predicted date (a `pr` line), a double of
     * about 1e9 seconds whose last bit is about 1e-7 s, compared within a microsecond.
     */
    private fun tolerance(lineHead: String): Double = if (lineHead == "pr") 1e-6 else 1e-9

    /** The tokens that identify a golden line among its case's lines: the first two for indexed kinds, else the first. */
    private fun lineKind(line: String): String {
        val t = line.split(' ')
        val indexed = t[0] in setOf("avg", "tr", "fl", "ix", "iq", "pr") || (t[0] == "cs" && t.size > 1 && t[1].all { it in '0'..'9' })
        return if (indexed) t.take(2).joinToString(" ") else t[0]
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
                    if ((ed.isNaN() && ad.isNaN()) || abs(ed - ad) <= tolerance(et[0])) {
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
    @Timeout(value = 3, unit = TimeUnit.SECONDS)
    fun kotlinMatchesTheSwiftGoldensForEveryCase() {
        val inputs = inputs()
        val goldens = goldens()
        assertEquals(goldens.keys.toList(), inputs.map { it.id }, "inputs and goldens list the same cases in the same order")
        val report = Report()
        for (c in inputs) compare(c.id, goldens.getValue(c.id), render(c), report)

        println(
            "engine differential: ${inputs.size} cases, ${report.lines} golden lines, ${report.doubles} doubles, " +
                "${report.nonIdentical.size} not bit-identical (tolerated within 1e-9, a predicted date within 1e-6 s), " +
                "${report.mismatches.size} mismatches",
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
    fun everyBranchTheGeneratorNamesIsReachedWithinTheSizeBound() {
        // The Swift random draws (`SwiftRandomTest`'s golden) count toward the same bound.
        val dataLines = lines("inputs.txt").size + lines("goldens.txt").size + lines("random.txt").size
        assertTrue(dataLines < 6_000, "inputs + goldens + random draws must stay under 6 000 lines, got $dataLines")

        // Every shape the inputs hold has at least three cases.
        val shapes = inputs().groupingBy { "${it.kind}/${it.shape}" }.eachCount()
        for ((shape, n) in shapes) assertTrue(n >= 3, "shape $shape has $n cases")

        // The branch list is the generator's own: every name its code counts was reached, and the
        // coverage file names nothing the code no longer counts.
        val named = namedBranches(generatorCode())
        assertTrue(named.size >= 20, "found only ${named.size} named branches in the generator — FIX THE READER")
        val coverage = coverage()
        val unreached = named.filter { (coverage[it] ?: 0) < 1 }.sorted()
        assertEquals(emptyList(), unreached, "branches the generator names but no case reached")
        assertEquals(emptySet(), coverage.keys - named, "coverage names branches the generator no longer counts — regenerate")
    }

    @Test
    fun theBranchReaderSeesOnlyLiteralNamesInCode() {
        val code = listOf(
            "func hit(_ branch: String, _ yes: Bool = true) { if yes { coverage[branch, default: 0] += 1 } }",
            "    hit(\"a-branch\", x > 0)",
            "    case .up: hit(\"b-branch\")",
        )
        assertEquals(setOf("a-branch", "b-branch"), namedBranches(code))
        // A comment is not code: the stripping in generatorCode() removes it.
        assertEquals(setOf("a-branch", "b-branch"), namedBranches((code + "// hit(\"prose-only\")").map { it.substringBefore("//") }))
        // A branch named by a variable cannot be derived, so it fails the reader.
        assertTrue(runCatching { namedBranches(code + "    hit(name)") }.isFailure, "a non-literal branch name must fail")
    }

    @Test
    fun comparatorFailsOnEveryKindOfDifferenceAndOnAStaleEntry() {
        val golden = listOf("reg 100 - 99", "avg 7 ${d(0.25)} -", "cs 40 ${d(1.0)} ${d(0.0)}")
        fun run(actual: List<String>, divergences: Map<String, Set<Divergence>> = emptyMap()) =
            Report().also { compare("x", golden, actual, it, divergences) }

        assertTrue(run(golden).let { it.mismatches.isEmpty() && it.nonIdentical.isEmpty() }, "identical lines pass silently")
        assertEquals(1, run(listOf("reg 100 - 98", golden[1], golden[2])).mismatches.size, "a changed integer fails")
        assertEquals(1, run(listOf(golden[0], "avg 7 ${d(0.25 + 1e-6)} -", golden[2])).mismatches.size, "a double off by more than 1e-9 fails")
        val near = run(listOf(golden[0], "avg 7 ${d(Math.nextUp(0.25))} -", golden[2]))
        assertTrue(near.mismatches.isEmpty() && near.nonIdentical.size == 1, "a double within 1e-9 is tolerated and listed")
        assertEquals(1, run(listOf(golden[0], "avg 7 ${d(0.25)} ${d(1.0)}", golden[2])).mismatches.size, "an absent value that appears fails")
        assertEquals(1, run(golden.dropLast(1)).mismatches.size, "a missing line fails")
        val listed = mapOf("probe" to setOf(Divergence("x", "avg 7")))
        val moved = run(listOf(golden[0], "avg 7 ${d(0.5)} -", golden[2]), listed)
        assertTrue(moved.mismatches.isEmpty() && staleEntries(moved, listed).isEmpty(), "a listed line may differ")
        assertEquals(1, staleEntries(run(golden, listed), listed).size, "a listed line that no longer differs is stale")
        assertEquals(1, run(listOf(golden[0], "avg 7 ${d(0.5)} -", golden[2])).mismatches.size, "an unlisted line may not differ")

        // A predicted date is compared within a microsecond, and only a date line is.
        val date = 8.123456789e8
        val dates = listOf("pr 0 ${d(date)} 1", "st ${d(28.5)} 2 -")
        fun runDates(actual: List<String>) = Report().also { compare("y", dates, actual, it) }
        val closeDate = runDates(listOf("pr 0 ${d(date + 9e-7)} 1", dates[1]))
        assertTrue(closeDate.mismatches.isEmpty() && closeDate.nonIdentical.size == 1, "a date within 1e-6 s is tolerated and listed")
        assertEquals(1, runDates(listOf("pr 0 ${d(date + 2e-6)} 1", dates[1])).mismatches.size, "a date off by more than 1e-6 s fails")
        assertEquals(1, runDates(listOf("pr 0 ${d(date)} 0", dates[1])).mismatches.size, "a changed corroboration flag fails")
        assertEquals(1, runDates(listOf(dates[0], "st ${d(28.5 + 1e-7)} 2 -")).mismatches.size, "a statistic keeps the 1e-9 tolerance")
    }
}
