package io.github.opencircuit.ringkit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guard for the public surface of `android/`: every file that can be pushed to GitHub must be
 * readable on its own. The owner keeps a private workflow kit beside the code (its folders and
 * notes are excluded from git), and a public comment that cites one of its internal labels, or a
 * local path, points readers at something they can never open. A one-off manual grep only runs
 * when someone remembers it; this test runs on every suite.
 *
 * Scope: every file git would publish or could be asked to add, i.e. tracked files plus untracked
 * files git does not ignore (`git ls-files --cached --others --exclude-standard`), minus the
 * private kit paths, build and tool output, binaries, and this file (which has to spell the
 * patterns out). A git-ignored file such as `keystore.properties` holds machine-local paths by
 * design and can never be pushed, so it is not scanned; a new file nobody has ignored yet is.
 * Without git (a source archive) the whole tree on disk is scanned instead.
 */
class PublicSurfaceTest {

    @Test
    fun publicTreeCarriesNoPrivateReferences() {
        val rootPath = assertNotNull(
            System.getProperty("opencircuit.androidRoot"),
            "system property opencircuit.androidRoot is not set — see ringkit/build.gradle.kts",
        )
        val root = File(rootPath)
        assertTrue(root.isDirectory, "android root not found at $rootPath")

        val (scanned, hits) = PublicSurface.scanTree(root)

        assertTrue(scanned.any { it == "PORTING.md" }, "scan never reached PORTING.md — skip rules are too wide")
        assertTrue(scanned.any { it.startsWith("ringkit/src/main/") }, "scan never reached ringkit sources")
        assertTrue(
            hits.isEmpty(),
            "${hits.size} private reference(s) in public files:\n" + hits.joinToString("\n"),
        )
    }

    // --- which files the tree scan reads, on a throwaway git repository ---

    @Test
    fun theTreeScanSkipsGitIgnoredFilesButReadsUntrackedOnes() {
        val repo = kotlin.io.path.createTempDirectory("public-surface").toFile()
        try {
            val leak = "/" + "Users" + "/someone/release.jks"
            git(repo, "init", "-q")
            File(repo, ".gitignore").writeText("machine.properties\n")
            File(repo, "machine.properties").writeText("storeFile=$leak\n") // ignored: never published
            File(repo, "notes.txt").writeText("key at $leak\n") // untracked, not ignored: one `git add` from public
            File(repo, "src").mkdirs()
            File(repo, "src/Tracked.kt").writeText("// key at $leak\n")
            git(repo, "add", "src/Tracked.kt")

            val (scanned, hits) = PublicSurface.scanTree(repo)

            assertEquals(listOf(".gitignore", "notes.txt", "src/Tracked.kt"), scanned.sorted())
            assertEquals(
                listOf("notes.txt:1 [personal-data] '/" + "Users" + "/'", "src/Tracked.kt:1 [personal-data] '/" + "Users" + "/'"),
                hits.sorted(),
            )
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun withoutGitTheTreeScanReadsEveryFileOnDisk() {
        val dir = kotlin.io.path.createTempDirectory("public-surface").toFile()
        try {
            File(dir, ".gitignore").writeText("machine.properties\n")
            File(dir, "machine.properties").writeText("storeFile=/" + "Users" + "/someone/release.jks\n")

            assertEquals(null, PublicSurface.gitListing(dir), "not a git repository")
            val (scanned, hits) = PublicSurface.scanTree(dir)

            assertEquals(listOf(".gitignore", "machine.properties"), scanned.sorted())
            assertEquals(1, hits.size, hits.joinToString())
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun git(dir: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git", *args)).directory(dir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), "git ${args.joinToString(" ")}: $output")
    }

    // --- the matcher itself, independent of the tree ---

    private fun rulesHit(text: String): Set<String> = PublicSurface.scan(text).map { it.second }.toSet()

    @Test
    fun lessonIdRuleBites() {
        assertEquals(setOf("lesson-id"), rulesHit("see " + "PL" + "-2026-09-30-a"))
        assertEquals(emptySet(), rulesHit("a PL-26 part number, 2026-09-30"))
    }

    @Test
    fun antiPatternIdRuleBites() {
        assertEquals(setOf("anti-pattern-id"), rulesHit("a signed read slips (" + "A" + "5)"))
        assertEquals(setOf("anti-pattern-id"), rulesHit("fresh arrays, " + "I" + "4"))
        assertEquals(setOf("anti-pattern-id"), rulesHit("G" + "12: rule"))
        // Hex, firmware prefixes, model names and identifiers must not trip it.
        assertEquals(emptySet(), rulesHit("0xA5 0xa1 FR02 FR05.011 Gen 3 sportStart(0x100) A123 BA5 A5x"))
    }

    @Test
    fun designRecordRuleBites() {
        assertEquals(setOf("design-record"), rulesHit("per " + "AD" + "R E1"))
        assertEquals(setOf("design-record"), rulesHit("the " + "PR" + "D says"))
        assertEquals(setOf("design-record"), rulesHit("Do" + "D 3"))
        assertEquals(setOf("design-record"), rulesHit("Do" + "D1 (E0)"))
        assertEquals(emptySet(), rulesHit("ADDRESS PRDX dodge LADR"))
    }

    @Test
    fun planStepRuleBites() {
        assertEquals(setOf("plan-step"), rulesHit("not ported (E1 1.3 " + "Option 1)"))
        assertEquals(setOf("plan-step"), rulesHit("added in E1-" + "S4-T6"))
        assertEquals(emptySet(), rulesHit("E1 slice 4, Phase 3.5 review, option 2, FR02.018, S/Opcodes.swift:34"))
    }

    @Test
    fun kitDocNameRuleBites() {
        assertEquals(setOf("kit-doc"), rulesHit("Miss" + "_Ledger.md"))
        assertEquals(setOf("kit-doc"), rulesHit("see " + "test-runs" + "/2026.md"))
        assertEquals(emptySet(), rulesHit("the test runs below; epics and users"))
    }

    @Test
    fun personalDataRuleBites() {
        assertEquals(setOf("personal-data"), rulesHit("/" + "Users" + "/someone/x"))
        assertEquals(setOf("personal-data"), rulesHit("me@" + "tutanota" + ".com"))
        assertEquals(setOf("personal-data"), rulesHit("ring at 12:34:56:78:9A:BC"))
        // Upstream's already-public test MAC and obvious placeholder MACs are allowed (any case).
        assertEquals(emptySet(), rulesHit("F8:79:99:F7:03:AD f8:79:99:f7:03:ad AA:BB:CC:DD:EE:FF AA:BB:CC:DD:EE:00"))
        assertEquals(emptySet(), rulesHit("/usr/local/users/ 12:34:56"))
    }

    @Test
    fun skipRulesCoverThePrivatePathsOnly() {
        listOf("docs", "test-runs", ".claude", ".opencode", ".gradle", ".kotlin", ".idea", "build", "ringkit/build")
            .forEach { assertTrue(PublicSurface.isSkippedDir(it), "$it should be skipped") }
        listOf("ringkit", "ringkit/src", "scripts", "gradle", "gradle/wrapper")
            .forEach { assertTrue(!PublicSurface.isSkippedDir(it), "$it must be scanned") }
        listOf("CLAUDE.md", "AGENTS.md", "epics-and-users.md", "local.properties", "gradle/wrapper/gradle-wrapper.jar")
            .forEach { assertTrue(PublicSurface.isSkippedFile(it), "$it should be skipped") }
        listOf("PORTING.md", "README.md", "ringkit/build.gradle.kts", "scripts/upstream-diff.sh")
            .forEach { assertTrue(!PublicSurface.isSkippedFile(it), "$it must be scanned") }
        assertTrue(PublicSurface.looksBinary(byteArrayOf(0x50, 0x4b, 0x00, 0x03)))
        assertTrue(!PublicSurface.looksBinary("plain text".toByteArray()))
    }
}

/** The rules behind [PublicSurfaceTest]; paths are relative to `android/`, `/`-separated. */
private object PublicSurface {
    private const val SELF = "ringkit/src/test/kotlin/io/github/opencircuit/ringkit/PublicSurfaceTest.kt"

    /** Private kit folders (excluded from git) plus build and tool output. */
    private val skippedTopDirs = setOf("docs", "test-runs", ".claude", ".opencode", ".gradle", ".kotlin", ".idea", "captures")

    /** Private kit files at the root, plus the machine-local SDK path file (gitignored). */
    private val skippedTopFiles = setOf("CLAUDE.md", "AGENTS.md", "epics-and-users.md", "local.properties")

    private val binaryExtensions = setOf("jar", "apk", "aab", "jks", "keystore", "png", "jpg", "webp", "class")

    fun isSkippedDir(rel: String): Boolean {
        val parts = rel.split('/')
        return parts.first() in skippedTopDirs || "build" in parts
    }

    fun isSkippedFile(rel: String): Boolean =
        rel == SELF || rel in skippedTopFiles || rel.substringAfterLast('.', "") in binaryExtensions

    fun looksBinary(bytes: ByteArray): Boolean = bytes.take(8192).any { it == 0.toByte() }

    /**
     * Scans the files under [root] that could be published, minus the skip rules; returns the
     * relative paths read and every hit as "path:line [rule] 'match'".
     */
    fun scanTree(root: File): Pair<List<String>, List<String>> {
        val candidates = gitListing(root)?.map { File(root, it) }
            ?: root.walkTopDown()
                .onEnter { dir -> dir == root || !isSkippedDir(dir.relativeTo(root).invariantSeparatorsPath) }
                .toList()
        val scanned = mutableListOf<String>()
        val hits = mutableListOf<String>()
        candidates.filter { it.isFile }.forEach { file ->
            val rel = file.relativeTo(root).invariantSeparatorsPath
            if (isSkippedDir(rel.substringBeforeLast('/', "")) || isSkippedFile(rel)) return@forEach
            val bytes = file.readBytes()
            if (looksBinary(bytes)) return@forEach
            scanned += rel
            scan(String(bytes, Charsets.UTF_8)).forEach { (line, rule, match) -> hits += "$rel:$line [$rule] '$match'" }
        }
        return scanned to hits
    }

    /**
     * The files under [root] that git tracks or could be asked to add (untracked and not ignored),
     * relative to [root]; null when [root] is not in a git work tree or git cannot be run.
     */
    fun gitListing(root: File): List<String>? = try {
        val inside = ProcessBuilder("git", "rev-parse", "--is-inside-work-tree")
            .directory(root).redirectErrorStream(true).start()
        val isRepo = inside.inputStream.bufferedReader().readText().trim() == "true"
        if (inside.waitFor() != 0 || !isRepo) {
            null
        } else {
            val ls = ProcessBuilder("git", "ls-files", "-z", "--cached", "--others", "--exclude-standard")
                .directory(root).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val out = ls.inputStream.bufferedReader().readText()
            if (ls.waitFor() == 0) out.split('\u0000').filter { it.isNotEmpty() }.distinct() else null
        }
    } catch (_: java.io.IOException) {
        null // git is not installed
    }

    /**
     * The kit's anti-pattern labels are one capital A, I or G followed by one or two digits, as a
     * whole word. The word boundary is what keeps this from biting legit content: in hex such as
     * `0xA5` the letter follows `x` (no boundary), firmware prefixes like `FR02` have letters
     * before the digits, and model names like `Gen 3` have no letter-digit pair. Checked against
     * the whole tree when this guard was added: no legit content matched.
     */
    private val rules: List<Pair<String, Regex>> = listOf(
        "lesson-id" to Regex("""PL-\d{4}-\d{2}-\d{2}"""),
        "anti-pattern-id" to Regex("""\b[AIG]\d{1,2}\b"""),
        // A trailing digit counts too: a numbered label glued to the word is still a citation.
        "design-record" to Regex("""\b(?:ADR|PRD|DoD)(?:\d|\b)"""),
        // Private planning labels: a numbered question's option ("1.3 Option 1") or a task ID ("E1-S4-T6").
        "plan-step" to Regex("""\b\d+\.\d+ Option\b|\bE\d+-S\d+-T\d+\b"""),
        "kit-doc" to Regex("""Miss_Ledger|Unit_Test_Writing_Guide|Implementation_Patterns|epics-and-users|test-runs/|KIT_DEVIATIONS"""),
        "personal-data" to Regex("""/Users/|tutanota"""),
    )

    private val mac = Regex("""\b(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\b""")

    /** Upstream's already-public test MAC, and synthetic placeholders that identify no device. */
    private val allowedMacs = setOf("F8:79:99:F7:03:AD", "AA:BB:CC:DD:EE:FF", "AA:BB:CC:DD:EE:00")

    /** Every hit as (1-based line, rule name, matched text). */
    fun scan(text: String): List<Triple<Int, String, String>> =
        text.lines().flatMapIndexed { i, line ->
            val named = rules.flatMap { (name, re) -> re.findAll(line).map { Triple(i + 1, name, it.value) } }
            val macs = mac.findAll(line)
                .filter { it.value.uppercase() !in allowedMacs }
                .map { Triple(i + 1, "personal-data", it.value) }
            named + macs
        }
}
