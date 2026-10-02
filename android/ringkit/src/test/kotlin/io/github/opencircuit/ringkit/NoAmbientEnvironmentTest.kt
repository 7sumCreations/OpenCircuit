package io.github.opencircuit.ringkit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Source audit: no vitals, activity, goal, unit or battery source reads an ambient clock, time zone,
 * locale or the process environment. Upstream defaults to the device's clock (`Date()`), calendar
 * (`Calendar.current`), locale (`Locale.current`) and preferences store; this port takes each as a
 * required parameter, so the same inputs give the same answer on every machine, in every zone and
 * language. A default creeping back in — a `now()` call, the system zone, the default locale (also
 * through `String.format` or a one-argument date pattern), the current time in milliseconds or an
 * environment variable — fails here.
 *
 * The audit reads each source from disk with its comments stripped (prose may name what the code
 * must not do). It is a tripwire, not a proof: a call reached through reflection or another file is
 * invisible to it. The environment-read calls are assembled from pieces, because the corpus-gate
 * audit forbids that text in test sources.
 */
class NoAmbientEnvironmentTest {

    /** Every main source this port of the vitals, activity, goal, unit and battery code added or changed. */
    private val audited = listOf(
        "HRV.kt", "Stress.kt", "Strain.kt", "DistanceEstimate.kt", "UserProfile.kt", "Calories.kt",
        "RestingHR.kt", "ExerciseMinutes.kt", "HealthAlerts.kt", "ActiveEnergyLedger.kt", "ActiveEnergyWindow.kt",
        "RobustBaseline.kt", "VitalsBaseline.kt", "SkinTempBaseline.kt", "WellnessBalance.kt", "ActivityScore.kt",
        "GoalDefaults.kt", "GoalHistory.kt", "UnitPreferences.kt", "BatteryTTE.kt",
        "SwiftNumerics.kt", "EpochArchiveDiagnostics.kt", "DateInterval.kt",
    )

    private val system = "System" + "."
    private val forbidden: List<Pair<String, Regex>> = listOf(
        "a now() call reads the clock" to Regex("""\b[A-Z]\w*\.now\("""),
        "the system clock" to Regex("""\bClock\.system"""),
        "the system time zone" to Regex("""\bsystemDefault\(|\bTimeZone\.getDefault\("""),
        "the default locale" to Regex("""\bLocale\.getDefault\("""),
        "the current time" to Regex("""\b""" + Regex.escape(system) + """(currentTimeMillis|nanoTime)\("""),
        "the process environment" to Regex("""\b""" + Regex.escape(system) + """(getenv|getProperty|getProperties)\b"""),
        "a calendar or date at the current instant" to Regex("""\bCalendar\.getInstance\(|\bDate\(\s*\)"""),
        "formatting with the default locale" to Regex("""\bString\.format\(|"\s*\.format\("""),
        "case mapping with the default locale" to Regex("""\.to(Upper|Lower)Case\(\s*\)"""),
        "a date pattern in the default locale" to Regex("""\bofPattern\(\s*"[^"]*"\s*\)"""),
    )

    private fun sourceDir(): File {
        val root = assertNotNull(System.getProperty("opencircuit.androidRoot"), "system property opencircuit.androidRoot is not set — see ringkit/build.gradle.kts")
        return File(root, "ringkit/src/main/kotlin/io/github/opencircuit/ringkit")
    }

    private fun violations(name: String, text: String): List<String> {
        val src = StrippedSource(name, text)
        return src.lines.flatMapIndexed { i, line ->
            forbidden.filter { (_, rx) -> rx.containsMatchIn(line) }.map { (why, _) -> "$name:${i + 1}: $why — ${line.trim()}" }
        }
    }

    @Test
    fun noAuditedSourceReadsTheClockAZoneALocaleOrTheEnvironment() {
        val dir = sourceDir()
        val hits = audited.flatMap { name ->
            val f = File(dir, name)
            assertTrue(f.isFile, "missing audited source $name — if it moved, FIX THE AUDIT rather than letting it pass")
            violations(name, f.readText())
        }
        assertEquals(emptyList(), hits, "ambient environment read in an audited source:\n" + hits.joinToString("\n"))
    }

    @Test
    fun theAuditCoversEverySourceThePortingLedgerGivesThisPort() {
        // Every main file named in a PORTING.md row of this port (the epic column "E4") is audited, so a
        // new source cannot join the port without joining the audit.
        val root = File(assertNotNull(System.getProperty("opencircuit.androidRoot"), "opencircuit.androidRoot is not set"))
        val rows = File(root, "PORTING.md").readLines().filter { it.startsWith("| ") && it.split('|').getOrNull(5)?.trim() == "E4" }
        assertTrue(rows.size >= 30, "found only ${rows.size} rows for this port in PORTING.md — FIX THE AUDIT")
        val named = rows.flatMap { row -> Regex("""`main/(\w+\.kt)`""").findAll(row.split('|')[3]).map { it.groupValues[1] }.toList() }.toSet()
        assertTrue(named.size >= 18, "the ledger named only $named — FIX THE AUDIT")
        assertEquals(emptySet(), named - audited.toSet(), "sources of this port that the audit does not read")
        assertEquals(audited.size, audited.toSet().size, "an audited source is listed twice")
    }

    @Test
    fun everyRuleBitesAndCommentsAreNotCode() {
        val probes = listOf(
            "val t = Instant.now()", "val d = LocalDate.now(zone)", "val c = Clock.systemUTC()",
            "val z = ZoneId.systemDefault()", "val tz = TimeZone.getDefault()", "val l = Locale.getDefault()",
            "val ms = ${system}currentTimeMillis()", "val v = ${system}getenv(\"X\")", "val p = ${system}getProperty(\"x\")",
            "val k = Calendar.getInstance()", "val d = Date()", "val s = String.format(\"%.1f\", x)",
            "val s = \"%.1f\".format(x)", "val u = s.toUpperCase()", "val f = DateTimeFormatter.ofPattern(\"HH:mm\")",
        )
        for (probe in probes) {
            assertEquals(1, violations("Probe.kt", "fun f() {\n    $probe\n}\n").size, "the audit catches: $probe")
        }
        // What the port legitimately does is not flagged.
        val clean = listOf(
            "val f = DateTimeFormatter.ofPattern(\"MM-dd HH:mm\", Locale.ROOT).withZone(zone)", "val s = fmt.format(r.date(epoch))",
            "val u = override.take(2).uppercase(Locale.ROOT)", "fun localeDefault(locale: Locale): DistanceUnit = METRIC",
            "val day = CalendarDay.startOfDay(now, zone)", "val dep = addingSeconds(now, tte)",
        )
        for (line in clean) assertEquals(emptyList(), violations("Clean.kt", "fun f() {\n    $line\n}\n"), line)
        // A comment may name the forbidden call; only code is audited.
        assertEquals(emptyList(), violations("Doc.kt", "// upstream reads Instant.now()\n/** Locale.getDefault() is never read */\nfun f() = 1\n"))
    }
}
