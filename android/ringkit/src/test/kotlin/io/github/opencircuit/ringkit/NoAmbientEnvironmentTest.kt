package io.github.opencircuit.ringkit

import java.io.File
import java.time.Instant
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Source audit over the WHOLE main source set: no file of this module reads an ambient clock, time
 * zone, locale, preferences store or the process environment. Upstream defaults to the device's clock
 * (`Date()`), calendar (`Calendar.current`), locale (`Locale.current`) and preferences store
 * (`UserDefaults`); this port takes each as a required parameter, so the same inputs give the same
 * answer on every machine, in every zone and language. A default creeping back in — a `now()` call, the
 * system zone, the default locale (also through `String.format` or a literal's `.format` without
 * `Locale.ROOT`, a no-argument case mapping or a one-argument date pattern), the current time, a
 * preferences store or an environment variable — fails here.
 *
 * The file set is listed from the source directory (recursively), never kept by hand, so a new file is
 * audited the moment it exists. A hit that is not an ambient read is excused by an explicit entry with
 * its reason; an entry that no longer matches exactly one hit fails (stale or ambiguous), so the list
 * cannot outlive the code it excuses or quietly cover a second, new hit.
 *
 * The audit reads each source from disk with its comments stripped (prose may name what the code must
 * not do), and matches across line breaks (a `String.format(` whose `Locale.ROOT` sits on the next line
 * is clean). It is a tripwire, not a proof: a call reached through reflection or another module is
 * invisible to it. The environment-read calls are assembled from pieces, because the corpus-gate audit
 * forbids that text in test sources.
 */
class NoAmbientEnvironmentTest {

    private val system = "System" + "."
    private val forbidden: List<Pair<String, Regex>> = listOf(
        "a now() call reads the clock" to Regex("""\b[A-Z]\w*\.now\("""),
        "the system clock" to Regex("""\bClock\.system"""),
        "the system time zone" to Regex("""\bsystemDefault\(|\bTimeZone\.getDefault\("""),
        "the default locale" to Regex("""\bLocale\.getDefault\("""),
        "the current time" to Regex("""\b""" + Regex.escape(system) + """(currentTimeMillis|nanoTime)\("""),
        "the process environment" to Regex("""\b""" + Regex.escape(system) + """(getenv|getProperty|getProperties)\b"""),
        "a calendar or date at the current instant" to Regex("""\bCalendar\.getInstance\(|\bDate\(\s*\)"""),
        "formatting with the default locale" to Regex("""\bString\.format\((?!\s*Locale\.ROOT\b)|"\s*\.format\((?!\s*Locale\.ROOT\b)"""),
        "case mapping with the default locale" to Regex("""\.to(Upper|Lower)Case\(\s*\)"""),
        "a date pattern in the default locale" to Regex("""\bofPattern\(\s*"[^"]*"\s*\)"""),
        "a preferences store" to Regex(
            """\bjava\.util\.prefs\b|\bPreferences\.(userRoot|systemRoot|userNodeForPackage|systemNodeForPackage)\(|""" +
                """\b(SharedPreferences|UserDefaults|DataStore)\b|\bgetSharedPreferences\(""",
        ),
    )

    private data class Hit(val file: String, val line: Int, val why: String, val code: String) {
        override fun toString() = "$file:$line: $why — $code"
    }

    /** A hit that is not an ambient read: the file, a piece of the (comment-stripped) line it is on, and why. */
    private data class Excused(val file: String, val code: String, val reason: String)

    private data class Report(val unexcused: List<Hit>, val stale: List<Excused>, val ambiguous: List<Pair<Excused, List<Hit>>>)

    // Lowercase `%x` and `%s` are never localized by java.util.Formatter (no digit substitution, no
    // grouping; only `%X` upper-cases with the locale): the text is the same in every default locale,
    // which `excusedFormatsPrintTheSameInEveryDefaultLocale` pins on each excused site.
    private val hexOnly = "debug text of lowercase %x / %s only, never localized (pinned below)"
    private val excused = listOf(
        Excused("BulkRecord.kt", "\"%02x\".format(it.toInt() and 0xFF) }})", hexOnly),
        Excused("EpochRecord.kt", "subtype=%02x, rawPayload=[%s])\".format(subtype, payload.hexString())", hexOnly),
        Excused("EpochRecord.kt", "fun ByteArray.hexString(): String", hexOnly),
        Excused("Frame.kt", "\"Frame.Parsed(opcode=%02x, body=[%s], trailer=%02x)\".format(", hexOnly),
        Excused("Frame.kt", "{ \"%02x\".format(it.toInt() and 0xFF) }, trailer,", hexOnly),
    )

    private fun hits(name: String, text: String): List<Hit> {
        val src = StrippedSource(name, text)
        return forbidden.flatMap { (why, rx) ->
            rx.findAll(src.flat).map { m ->
                val line = src.line(m.range.first)
                Hit(name, line, why, src.lines[line - 1].trim())
            }.toList()
        }.sortedWith(compareBy({ it.line }, { it.why }))
    }

    private fun audit(sources: Map<String, String>, excused: List<Excused>): Report {
        val all = sources.flatMap { (name, text) -> hits(name, text) }
        val matched = excused.associateWith { e -> all.filter { it.file == e.file && e.code in it.code } }
        val covered = matched.filterValues { it.size == 1 }.values.flatten().toSet()
        return Report(
            unexcused = all.filter { it !in covered },
            stale = matched.filterValues { it.isEmpty() }.keys.toList(),
            ambiguous = matched.filterValues { it.size > 1 }.toList(),
        )
    }

    private fun androidRoot(): File =
        File(assertNotNull(System.getProperty("opencircuit.androidRoot"), "system property opencircuit.androidRoot is not set — see ringkit/build.gradle.kts"))

    private fun mainDir(): File = File(androidRoot(), "ringkit/src/main/kotlin/io/github/opencircuit/ringkit")

    /** Every main source, listed from the directory (recursively), keyed by its path under it. */
    private fun mainSources(): Map<String, String> {
        val dir = mainDir()
        assertTrue(dir.isDirectory, "missing main source directory $dir — FIX THE AUDIT")
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(dir).invariantSeparatorsPath to it.readText() }
            .toSortedMap()
    }

    @Test
    fun noMainSourceReadsTheClockAZoneALocaleAPreferencesStoreOrTheEnvironment() {
        val sources = mainSources()
        val report = audit(sources, excused)
        println("ambient audit: ${sources.size} main sources, ${excused.size} excused hits, ${report.unexcused.size} unexcused")
        assertEquals(emptyList(), report.unexcused, "ambient environment read in a main source:\n" + report.unexcused.joinToString("\n"))
        assertEquals(emptyList(), report.stale, "stale exception — its file no longer has that hit; delete the entry")
        assertEquals(emptyList(), report.ambiguous, "an exception matches more than one hit — make it name exactly one")
    }

    @Test
    fun theAuditReadsEveryMainSourceAndEveryFileThePortingLedgerNames() {
        // The file set comes from the directory; the ledger is the independent check that it is the
        // right directory and that no ported file lives outside it. Every epic's rows count.
        val audited = mainSources().keys
        assertTrue(audited.size >= 100, "the audit found only ${audited.size} main sources — FIX THE AUDIT")
        val rows = File(androidRoot(), "PORTING.md").readLines().filter { it.startsWith("| ") }
        val named = rows.flatMap { row ->
            Regex("""`main/([\w/]+\.kt)`""").findAll(row.split('|').getOrElse(3) { "" }).map { it.groupValues[1] }.toList()
        }.toSet()
        assertTrue(named.size >= 100, "the ledger named only ${named.size} main files — FIX THE AUDIT")
        assertEquals(emptySet(), named - audited, "main files the ledger names that the audit does not read")
        // Every excused file exists and is audited.
        assertEquals(emptySet(), excused.map { it.file }.toSet() - audited, "an exception names a file the audit does not read")
    }

    @Test
    fun aStaleOrAmbiguousExceptionFailsAndAnUnexcusedHitIsReported() {
        val src = mapOf(
            "Clean.kt" to "fun f() = 1\n",
            "Hex.kt" to "fun h(b: Int) = \"%02x\".format(b)\nfun g(b: Int) = \"%02x\".format(b)\n",
            "Clock.kt" to "fun n() = Instant.now()\n",
        )
        val report = audit(
            src,
            listOf(
                Excused("Clean.kt", "fun f()", "stale: no hit there"),
                Excused("Hex.kt", "\"%02x\".format(b)", "ambiguous: two hits"),
            ),
        )
        assertEquals(listOf("Clean.kt"), report.stale.map { it.file })
        assertEquals(listOf(2), report.ambiguous.map { it.second.size })
        // An ambiguous entry excuses neither hit; the clock read is never excused.
        assertEquals(listOf("Clock.kt:1", "Hex.kt:1", "Hex.kt:2"), report.unexcused.map { "${it.file}:${it.line}" }.sorted())
        // An exact entry excuses exactly its hit.
        val exact = audit(src, listOf(Excused("Hex.kt", "fun g(", "one hit")))
        assertEquals(emptyList(), exact.stale)
        assertEquals(listOf("Clock.kt:1", "Hex.kt:1"), exact.unexcused.map { "${it.file}:${it.line}" }.sorted())
    }

    @Test
    fun excusedFormatsPrintTheSameInEveryDefaultLocale() {
        // The excused sites, exercised: the same text under machine locales with non-ASCII digits and
        // Turkish case rules as under Locale.ROOT, and plain ASCII.
        assertEquals(setOf("BulkRecord.kt", "EpochRecord.kt", "Frame.kt"), excused.map { it.file }.toSet(), "every excused file is exercised below")
        fun texts(): List<String> = listOf(
            BulkRecord.of(ByteArray(BulkRecord.LENGTH) { (it * 37 + 0xA5).toByte() })!!.toString(),
            EpochRecord.ActivityRecord(Instant.ofEpochSecond(1_786_400_000), 0xAB, bytes(0x01, 0xEF, 0xFF, 0x9A)).toString(),
            Frame.Parsed(opcode = 0xC7, body = bytes(0x00, 0xB0, 0xFE), trailer = 0x9D).toString(),
        )
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ROOT)
            val reference = texts()
            for (t in reference) assertTrue(t.all { it.code < 0x80 }, "ASCII: $t")
            for (tag in listOf("ar-EG-u-nu-arab", "hi-IN-u-nu-deva", "th-TH-u-nu-thai", "fa-IR", "tr-TR")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals(reference, texts(), tag)
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun everyRuleBitesAndCommentsAreNotCode() {
        val probes = listOf(
            "val t = Instant.now()", "val d = LocalDate.now(zone)", "val c = Clock.systemUTC()",
            "val z = ZoneId.systemDefault()", "val tz = TimeZone.getDefault()", "val l = Locale.getDefault()",
            "val ms = ${system}currentTimeMillis()", "val v = ${system}getenv(\"X\")", "val p = ${system}getProperty(\"x\")",
            "val k = Calendar.getInstance()", "val d = Date()", "val s = String.format(\"%.1f\", x)",
            "val s = \"%.1f\".format(x)", "val u = s.toUpperCase()", "val f = DateTimeFormatter.ofPattern(\"HH:mm\")",
            "val s = String.format(\n        \"%d\", n)", "val s = String.format(Locale.US, \"%d\", n)",
            "val p = Preferences.userRoot()", "val s: SharedPreferences = store", "val d = UserDefaults.standard",
        )
        for (probe in probes) {
            assertEquals(1, hits("Probe.kt", "fun f() {\n    $probe\n}\n").size, "the audit catches: $probe")
        }
        assertEquals(1, hits("Probe.kt", "import java.util.prefs.Preferences\n").size, "a preferences import")
        // What the port legitimately does is not flagged.
        val clean = listOf(
            "val f = DateTimeFormatter.ofPattern(\"MM-dd HH:mm\", Locale.ROOT).withZone(zone)", "val s = fmt.format(r.date(epoch))",
            "val u = override.take(2).uppercase(Locale.ROOT)", "fun localeDefault(locale: Locale): DistanceUnit = METRIC",
            "val day = CalendarDay.startOfDay(now, zone)", "val dep = addingSeconds(now, tte)",
            "val s = String.format(Locale.ROOT, \"0x%02x\", b)", "val s = String.format(\n        Locale.ROOT,\n        \"%d\", n)",
            "val s = \"%02x\".format(Locale.ROOT, b)", "val unit = UnitPreferences.resolve(locale)",
        )
        for (line in clean) assertEquals(emptyList(), hits("Clean.kt", "fun f() {\n    $line\n}\n"), line)
        // A comment may name the forbidden call; only code is audited.
        assertEquals(emptyList(), hits("Doc.kt", "// upstream reads Instant.now()\n/** Locale.getDefault() is never read */\nfun f() = 1\n"))
        // A hit reports the line it is on.
        assertEquals(listOf(3), hits("Line.kt", "fun f() {\n    val a = 1\n    val t = Instant.now()\n}\n").map { it.line })
    }
}
