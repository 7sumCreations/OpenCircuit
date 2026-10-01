package io.github.opencircuit.ringkit

import org.opentest4j.TestAbortedException
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * THE GATE ON THE GATE.
 *
 * The corpus-gated replay tests back claims like "no staged sleep number moved", so the failure
 * that matters most is not a wrong number: it is a run that measured NOTHING and said "passed".
 * Upstream shipped exactly that: every corpus entry point opened with an optional directory lookup
 * and an early `return` when the variable was unset, and XCTest scores an early return as a PASS.
 * JUnit does the same. So on any machine without a corpus the fidelity proof printed a green tick
 * backed by zero assertions.
 *
 * This file is the standing guard. Three parts:
 *  1. BEHAVIOUR — `SleepReplay.requireCorpus` aborts the test through a JUnit assumption when the
 *     variable is unset or empty (JUnit then reports SKIPPED, which is not a pass) and FAILS when
 *     the variable is set to a missing path or a regular file.
 *  2. SOURCE AUDIT — over every Kotlin test source of this module, comments stripped: no file may
 *     name a corpus variable except `SleepReplay.kt`, and there only as an entry of its corpus
 *     enum; no file but `SleepReplay.kt` may read the process environment; no helper may hand a
 *     corpus directory back as a nullable `File`/`Path`; the raw lookup stays private; the known
 *     entry points keep EXACTLY their gate counts; and the whole set of `OC_…` names the tests
 *     contain is declared here, in one place.
 *  3. PINNED GOLDEN — the corpus fingerprint and the scoreboard hash upstream's campaign quotes live
 *     in tracked source and in upstream's harness doc, and this keeps them in agreement.
 *
 * WHAT THIS DOES NOT GUARANTEE (upstream's own list, still true here): it is a TRIPWIRE, not a
 * proof. A variable name assembled at run time is invisible to a text audit, and so is a variable
 * outside the `OC_…` convention; a declaration split so its return type and its modifiers never
 * share the scanned window slips the shape rule; a test that catches the skip and returns slips
 * everything. The audit stops the patterns that actually occurred and the near-misses that were
 * actually tried; it does not stop a determined author.
 *
 * All three parts run with no corpus present, on any machine.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/CorpusGateLoudnessTests.swift
 * (@ b1c2fdd) — its 13 tests, the audit retargeted from the Swift test target to these Kotlin
 * sources. Kotlin-side differences (`PORTING.md` records them): corpus tests name a corpus by the
 * enum `SleepReplay.Corpus`, so the variable names live in one file; the environment-read rule is
 * new; the motion-channel corpus test is not ported, so its gate and its corpus name are not pinned.
 */
class CorpusGateLoudnessTest {

    // MARK: - 1. Behaviour of the gate itself

    /** The defect, inverted: an unset corpus variable must abort the test, never return a value. */
    @Test
    fun unsetCorpusVariableIsReportedAsSkipped() {
        try {
            SleepReplay.requireCorpus("OC_SLEEP_GATE_SELFTEST", purpose = "the gate self-test", environment = emptyMap())
            fail(
                "requireCorpus returned for an UNSET variable — a corpus-gated test would now run on " +
                    "nothing and JUnit would report it as passed. This is the exact defect this file exists to prevent.",
            )
        } catch (skip: TestAbortedException) {
            // TestAbortedException is what makes JUnit report "skipped" instead of "passed".
            val reason = skip.message ?: ""
            assertTrue(reason.contains("OC_SLEEP_GATE_SELFTEST"), "the skip reason must name the variable that was unset; got: $reason")
            assertTrue(reason.contains("NOTHING WAS MEASURED"), "the skip reason must say plainly that nothing was measured; got: $reason")
        }
    }

    /** An exported-but-empty variable is the same as unset. */
    @Test
    fun emptyCorpusVariableIsReportedAsSkipped() {
        try {
            SleepReplay.requireCorpus("OC_SLEEP_GATE_SELFTEST", purpose = "the gate self-test", environment = mapOf("OC_SLEEP_GATE_SELFTEST" to ""))
            fail("an empty corpus variable must skip, not resolve to a directory")
        } catch (_: TestAbortedException) {
        }
    }

    /** Set to a real directory: the gate gets out of the way. Without this the "fix" could be "always skip". */
    @Test
    fun setVariableResolvesToTheDirectory() {
        val tmp = Files.createTempDirectory("oc-corpus-gate-").toFile()
        try {
            val got = SleepReplay.requireCorpus("OC_SLEEP_GATE_SELFTEST", purpose = "the gate self-test", environment = mapOf("OC_SLEEP_GATE_SELFTEST" to tmp.path))
            assertEquals(tmp.canonicalPath, got.canonicalPath)
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** A typo'd path must FAIL, not skip: the variable being set means someone intended to measure. */
    @Test
    fun setButMissingDirectoryFailsRatherThanSkipping() {
        val missing = File(System.getProperty("java.io.tmpdir"), "oc-corpus-gate-does-not-exist-${UUID.randomUUID()}").path
        try {
            SleepReplay.requireCorpus("OC_SLEEP_GATE_SELFTEST", purpose = "the gate self-test", environment = mapOf("OC_SLEEP_GATE_SELFTEST" to missing))
            fail("a corpus variable pointing at a nonexistent path must fail the test")
        } catch (_: TestAbortedException) {
            fail("a SET-but-wrong path was treated as 'no corpus present' and skipped — a typo in the path would then read as a clean run")
        } catch (e: SleepReplay.ReplayError) {
            assertTrue("$e".contains("OC_SLEEP_GATE_SELFTEST"), "$e")
        }
    }

    /** A file is not a corpus. */
    @Test
    fun corpusVariablePointingAtAFileFails() {
        val f = Files.createTempFile("oc-corpus-gate-", ".txt").toFile()
        try {
            f.writeText("not a corpus")
            val thrown = runCatching {
                SleepReplay.requireCorpus("OC_SLEEP_GATE_SELFTEST", purpose = "the gate self-test", environment = mapOf("OC_SLEEP_GATE_SELFTEST" to f.path))
            }.exceptionOrNull()
            assertNotNull(thrown, "pointing the corpus variable at a FILE must fail")
            assertFalse(thrown is TestAbortedException, "pointing the corpus variable at a FILE must fail, not skip")
        } finally {
            f.delete()
        }
    }

    // MARK: - 2. Source audit — the tripwire

    /**
     * Every `.kt` under this module's test sources, read from disk and reduced to the text the
     * compiler runs. **Nothing is excluded** — including `SleepReplay.kt` and this file.
     */
    private fun auditSources(): List<StrippedSource> {
        val root = assertNotNull(
            System.getProperty("opencircuit.androidRoot"),
            "system property opencircuit.androidRoot is not set — see ringkit/build.gradle.kts",
        )
        val dir = File(root, "ringkit/src/test/kotlin")
        val files = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.sortedBy { it.path }.toList()
        assertTrue(
            files.size > 50,
            "found only ${files.size} sources under $dir — this audit reads the test sources through the androidRoot " +
                "property; if the files moved, FIX THE AUDIT, do not let it pass vacuously",
        )
        return files.map { StrippedSource(it.name, it.readText()) }
    }

    /**
     * THE REGRESSION GUARD. A corpus variable name may appear in executable text in exactly one
     * place: as an entry of `SleepReplay.Corpus`, in `SleepReplay.kt`. Any other route — a raw
     * environment read, a name held in a constant, a literal handed to the string overload — ends in
     * a value somebody can test for null and `return` on, which JUnit reports as PASSED. The needle
     * is any `OC_`-prefixed name ending in CORPUS (upstream's narrower needle once missed a corpus
     * named for its own feature). The process environment itself may be read only in `SleepReplay.kt`
     * (Kotlin-side addition: one choke point for every `OC_…` read, corpus-shaped or not).
     */
    @Test
    fun aCorpusVariableIsOnlyEverNamedInsideTheLoudGate() {
        // Assembled from pieces so this file contains no text its own rules would match.
        val rawLookup = "SleepReplay" + ".dir("
        val envRead = "System" + ".getenv"
        val corpusToken = "OC_" + "[A-Z0-9_]*" + "CORPUS"
        val corpusLiteral = Regex("\"" + corpusToken + "\"")
        val enumEntry = Regex("\\b[A-Z][A-Z0-9_]*\\(\\s*\"" + corpusToken + "\"\\s*\\)")

        val violations = mutableListOf<String>()
        val sources = auditSources()
        for (src in sources) {
            // (a) the raw lookup, wherever it appears
            src.lines.forEachIndexed { i, line ->
                if (line.contains(rawLookup)) {
                    violations += "${src.name}:${i + 1} calls the raw variable lookup instead of the loud gate. Open a corpus " +
                        "through SleepReplay.requireCorpus, which aborts (SKIPPED) instead of returning null."
                }
            }
            // (b) a corpus name anywhere but an entry of the gate file's corpus enum
            val allowed = if (src.name == GATE_FILE) enumEntry.findAll(src.flat).map { it.range }.toList() else emptyList()
            for (m in corpusLiteral.findAll(src.flat)) {
                if (allowed.any { m.range.first >= it.first && m.range.last <= it.last }) continue
                violations += "${src.name}:${src.line(m.range.first)} names a corpus variable (${m.value}) outside " +
                    "SleepReplay.Corpus. Every other route ends in a nullable value whose null becomes a silent early " +
                    "return, which JUnit reports as PASSED."
            }
            // (c) the process environment, read anywhere but the gate file
            if (src.name != GATE_FILE) {
                var at = src.flat.indexOf(envRead)
                while (at >= 0) {
                    violations += "${src.name}:${src.line(at)} reads the process environment directly. Corpus variables go " +
                        "through SleepReplay.requireCorpus and every other OC_ setting through SleepReplay.setting."
                    at = src.flat.indexOf(envRead, at + 1)
                }
            }
        }
        assertTrue(sources.size > 50, "the audit scanned almost nothing")
        assertEquals(emptyList(), violations, "corpus gate bypassed:\n" + violations.joinToString("\n"))
    }

    /**
     * THE SHAPE RULE. The original hole was the RETURN TYPE: any helper that hands a test a nullable
     * directory invites `?: return`, which JUnit scores as a pass. So in these sources a function may
     * return `File?` or `Path?` only if it is `private`. Kotlin-side: the return type is read right
     * after the parameter list, because a Kotlin function may have an expression body and no brace
     * at all (upstream's "up to the brace" window would run into the next declaration).
     */
    @Test
    fun noCorpusHelperHandsBackANullableDirectory() {
        val funKeyword = Regex("\\bfun\\s")
        val nullableDir = Regex("^\\s*:\\s*(?:java\\.io\\.|java\\.nio\\.file\\.)?(?:File|Path)\\?")
        val violations = mutableListOf<String>()
        for (src in auditSources()) {
            val flat = src.flat
            var from = 0
            while (true) {
                val hit = funKeyword.find(flat, from) ?: break
                from = hit.range.last + 1
                // The parameter list: the first `(` after `fun`, to its matching `)` (capped at 400 chars).
                val open = flat.indexOf('(', hit.range.first)
                if (open < 0 || open - hit.range.first > 200) continue
                var close = -1
                var depth = 0
                for (k in open until minOf(flat.length, open + 400)) {
                    if (flat[k] == '(') depth++
                    if (flat[k] == ')' && --depth == 0) {
                        close = k
                        break
                    }
                }
                if (close < 0) continue
                if (!nullableDir.containsMatchIn(flat.substring(close + 1, minOf(flat.length, close + 80)))) continue
                // Modifiers sit immediately before `fun`.
                val modifiers = flat.substring(maxOf(0, hit.range.first - 60), hit.range.first)
                if (!modifiers.contains("private")) {
                    violations += "${src.name}:${src.line(hit.range.first)} declares a non-private function returning a nullable " +
                        "File/Path. A nullable corpus directory is the hole this file exists to close — make it private and " +
                        "expose it through the loud gate, which aborts instead of returning null."
                }
            }
        }
        assertEquals(emptyList(), violations, "nullable-directory corpus helper:\n" + violations.joinToString("\n"))
    }

    /** `SleepReplay.dir` must STAY private: deleting the keyword would otherwise pass both rules above. */
    @Test
    fun theRawLookupStaysPrivate() {
        val gate = assertNotNull(auditSources().firstOrNull { it.name == GATE_FILE }, "the gate file is gone — FIX THE AUDIT")
        assertTrue(
            gate.flat.contains("private fun dir("),
            "the raw lookup is no longer `private fun dir(`. Making it visible again re-opens `?: return`, which JUnit reports as a pass.",
        )
    }

    /**
     * The inverse of the ban: the known entry points must still be gated, at an EXACT count, over
     * executable text only (comments are stripped, so prose naming the gate supplies no slack). The
     * file SET is pinned too, so a new gated test file is a deliberate one-line edit here.
     */
    @Test
    fun everyKnownCorpusEntryPointIsGatedExactly() {
        val expected = mapOf(
            "CorpusGateLoudnessTest.kt" to 5, // this file's own five behaviour tests
            "SleepReplayTest.kt" to 3, // measure, fidelity, input-sensitivity
            "SleepBaselineTest.kt" to 1, // scoreboard emitter
            "SleepCoverageMeasureTest.kt" to 1, // acquisition-coverage scoreboard
            "SleepAbsorbProbeTest.kt" to 2, // backward-absorb probe + its anti-drift check
            "SleepProvenanceCorpusTest.kt" to 1, // asserted-vs-measured blast radius
            "SleepProvenanceFixtureProbe.kt" to 1, // fixture-geometry scaffold
            "SleepEditedNightNoticeCorpusTest.kt" to 1, // how often the card's notice appears
            "SleepMonotonicityTest.kt" to 1, // the growing-archive collapse sweep
        )
        val gateCall = "SleepReplay" + ".requireCorpus("
        val found = auditSources()
            .associate { it.name to (it.flat.split(gateCall).size - 1) }
            .filterValues { it > 0 }
        assertEquals(
            expected.keys.sorted(), found.keys.sorted(),
            "the set of files that open a corpus changed. If you added a corpus-gated test file, add it to `expected` " +
                "with its exact gate count. If a file DISAPPEARED from this list it lost its gate and can now run — and " +
                "report success — on no corpus at all.",
        )
        for ((file, count) in expected.toSortedMap()) {
            assertEquals(count, found[file], "$file has ${found[file] ?: 0} corpus gate(s) in executable code, pinned at $count.")
        }
    }

    /**
     * THE NO-INTENT GUARD. The rule above bans a corpus NAME outside the gate; this pins the whole
     * `OC_`-prefixed name SET the tests contain, so a variable corpus-shaped in PURPOSE but not in
     * SPELLING cannot appear silently either. A name pin only — how a name is read is the rule above.
     * The names are assembled from a shared prefix so this file's own source cannot match.
     */
    @Test
    fun everyOCEnvironmentVariableTheTestsReadIsDeclared() {
        val oc = "OC_" + "SLEEP"
        val declared = setOf(
            oc + "_CORPUS", // SleepReplayTest.measureCorpus + SleepAbsorbProbeTest
            oc + "_FIDELITY_CORPUS", // SleepReplayTest's fidelity proof + input-sensitivity sweep
            oc + "_COVERAGE_CORPUS", // SleepCoverageMeasureTest — acquisition coverage
            oc + "_BASELINE_CORPUS", // SleepBaselineTest — the pinned-golden emitter
            oc + "_PROVENANCE_CORPUS", // SleepProvenanceCorpusTest + its fixture scaffold
            oc + "_NOTICE_CORPUS", // SleepEditedNightNoticeCorpusTest
            oc + "_MONOTONICITY_CORPUS", // SleepMonotonicityTest — the growing-archive sweep
            oc + "_BASELINE_OUT", // where the emitter writes its TSV. NOT a corpus: an output path, legitimately optional
            oc + "_GATE_SELFTEST", // this file's own injected variable; never a real corpus
            oc + "_PROBE_OUT", // SleepAbsorbProbeTest's output path. NOT a corpus
            oc + "_MOTION_CUT", // SleepBaselineTest's CANDIDATE motion-channel override. NOT a corpus: unset measures the default
            oc + "_ABSORB_CUT", // SleepBaselineTest's CANDIDATE absorb cut. NOT a corpus: unset measures the default
        )
        val ocLiteral = Regex("\"" + "OC_" + "[A-Z0-9_]+" + "\"")
        val found = auditSources().flatMap { src -> ocLiteral.findAll(src.flat).map { it.value.trim('"') } }.toSet()
        assertFalse(found.isEmpty(), "the scan found no OC_ environment names at all — FIX THE AUDIT rather than letting this pass vacuously.")
        assertEquals(
            emptySet(), found - declared,
            "undeclared OC_ environment variable(s) in the tests. If one gates a MEASUREMENT it must be opened through " +
                "SleepReplay.requireCorpus, which skips loudly instead of returning null. Declare it here with a note saying which it is.",
        )
        assertEquals(
            emptySet(), declared - found,
            "declared but no longer present. If an entry point was deleted, delete its line here too — a stale allowance is a hole waiting for someone to reuse the name.",
        )
    }

    /**
     * The fidelity proof's own tripwire: a corpus whose rows declare no fidelity target would walk
     * every night, assert nothing and pass. The gate stops a MISSING corpus; this stops an EMPTY one.
     */
    @Test
    fun fidelityProofKeepsItsZeroAssertionTripwire() {
        val text = assertNotNull(auditSources().firstOrNull { it.name == "SleepReplayTest.kt" }).flat
        assertTrue(
            text.contains("assertTrue(asserted > 0,"),
            "the fidelity proof lost its `assertTrue(asserted > 0, …)` check. Without it a corpus whose rows declare no " +
                "fidelity targets produces a green parity claim backed by zero assertions.",
        )
        assertTrue(
            text.contains("assertFalse(results.isEmpty(),"),
            "the measurement table lost its 'the corpus produced no measurable nights' check — an empty corpus would print nothing and pass.",
        )
    }

    /** The baseline emitter must refuse to declare success without having replayed something. */
    @Test
    fun baselineEmitterKeepsItsNonEmptyChecks() {
        val text = assertNotNull(auditSources().firstOrNull { it.name == "SleepBaselineTest.kt" }).flat
        assertTrue(
            text.contains("assertTrue(replayed > 0,"),
            "SleepBaselineTest lost its 'nothing was replayed at all' check — it would write a header-only TSV and hash it.",
        )
        assertTrue(
            text.contains("assertTrue(failures.isEmpty(),"),
            "SleepBaselineTest lost its load-failure check — the scoreboard could silently omit the nights that failed to load.",
        )
    }

    // MARK: - 3. The pinned golden

    /**
     * The scoreboard hash the campaign quotes and the fingerprint of the corpus it was measured on
     * live in tracked source (`SleepBaselineGolden`) and in upstream's harness doc. This keeps the
     * copies in agreement, so `git grep <hash>` keeps answering "which corpus produced this number?".
     */
    @Test
    fun goldenBaselineHashIsPinnedInTrackedSourceAndDocs() {
        val hex = Regex("^[0-9a-f]{64}$")
        for ((label, value) in listOf(
            "baselineSHA256" to SleepBaselineGolden.BASELINE_SHA256,
            "corpusManifestSHA256" to SleepBaselineGolden.CORPUS_MANIFEST_SHA256,
        )) {
            assertTrue(hex.matches(value), "SleepBaselineGolden.$label is not a lowercase 64-char sha256: '$value'")
        }
        val root = assertNotNull(System.getProperty("opencircuit.androidRoot"), "opencircuit.androidRoot is not set")
        val doc = File(File(root).parentFile, "docs/SLEEP_REPLAY_HARNESS.md")
        assertTrue(doc.isFile, "could not read $doc — the audit locates upstream's doc from the android root; if the layout moved, FIX THE AUDIT")
        val text = doc.readText()
        assertTrue(
            text.contains(SleepBaselineGolden.BASELINE_SHA256),
            "docs/SLEEP_REPLAY_HARNESS.md no longer quotes the pinned baseline sha256 ${SleepBaselineGolden.BASELINE_SHA256}.",
        )
        assertTrue(
            text.contains(SleepBaselineGolden.CORPUS_MANIFEST_SHA256),
            "docs/SLEEP_REPLAY_HARNESS.md no longer quotes the pinned corpus manifest fingerprint.",
        )
    }

    private companion object {
        const val GATE_FILE = "SleepReplay.kt"
    }
}

/**
 * A Kotlin source reduced to what the compiler runs: `//` line comments and (nested) block comments
 * removed, string literals (including raw `"""` strings) and character literals kept verbatim, one
 * output line per input line so violations still report a usable line number. A `${…}` template
 * holding its own quote can confuse it for the rest of that line — an accepted limit, as upstream's.
 */
internal class StrippedSource(val name: String, text: String) {
    val lines: List<String> = strip(text)
    val flat: String
    private val lineStarts: IntArray

    init {
        val sb = StringBuilder()
        lineStarts = IntArray(lines.size)
        lines.forEachIndexed { i, line ->
            lineStarts[i] = sb.length
            sb.append(line).append(' ')
        }
        flat = sb.toString()
    }

    /** 1-based line holding [offset] into [flat]. */
    fun line(offset: Int): Int {
        var lo = 0
        var hi = lineStarts.size - 1
        var best = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lineStarts[mid] <= offset) {
                best = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return best + 1
    }

    private enum class State { CODE, LINE_COMMENT, BLOCK, STRING, RAW_STRING, CHAR }

    private companion object {
        fun strip(text: String): List<String> {
            val out = ArrayList<String>()
            val cur = StringBuilder()
            var state = State.CODE
            var depth = 0
            var i = 0
            fun at(k: Int): Char? = text.getOrNull(i + k)
            fun endLine() {
                out += cur.toString()
                cur.setLength(0)
            }
            while (i < text.length) {
                val ch = text[i]
                when (state) {
                    State.CODE -> when {
                        ch == '/' && at(1) == '/' -> { state = State.LINE_COMMENT; i += 2 }
                        ch == '/' && at(1) == '*' -> { state = State.BLOCK; depth = 1; i += 2 }
                        ch == '"' && at(1) == '"' && at(2) == '"' -> { state = State.RAW_STRING; cur.append("\"\"\""); i += 3 }
                        ch == '"' -> { state = State.STRING; cur.append(ch); i += 1 }
                        ch == '\'' -> { state = State.CHAR; cur.append(ch); i += 1 }
                        ch == '\n' -> { endLine(); i += 1 }
                        else -> { cur.append(ch); i += 1 }
                    }
                    State.LINE_COMMENT -> {
                        if (ch == '\n') { endLine(); state = State.CODE }
                        i += 1
                    }
                    State.BLOCK -> when {
                        ch == '/' && at(1) == '*' -> { depth += 1; i += 2 }
                        ch == '*' && at(1) == '/' -> { depth -= 1; if (depth == 0) state = State.CODE; i += 2 }
                        else -> { if (ch == '\n') endLine(); i += 1 }
                    }
                    State.STRING, State.CHAR -> {
                        val close = if (state == State.STRING) '"' else '\''
                        when {
                            ch == '\\' -> { cur.append(ch); at(1)?.let { cur.append(it) }; i += 2 }
                            ch == '\n' -> { endLine(); state = State.CODE; i += 1 } // unterminated: recover
                            else -> { cur.append(ch); if (ch == close) state = State.CODE; i += 1 }
                        }
                    }
                    State.RAW_STRING -> when {
                        ch == '"' && at(1) == '"' && at(2) == '"' -> {
                            // A raw string may end in extra quotes: they belong to its content.
                            var k = 3
                            while (at(k) == '"') k += 1
                            repeat(k) { cur.append('"') }
                            state = State.CODE
                            i += k
                        }
                        ch == '\n' -> { endLine(); i += 1 }
                        else -> { cur.append(ch); i += 1 }
                    }
                }
            }
            out += cur.toString()
            return out
        }
    }
}
