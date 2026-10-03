package io.github.opencircuit.store

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * No production source of the store reads the clock, the device's time zone or its locale. Upstream
 * reads the wall clock and `Calendar.current` inside its store; here the time and the zone are
 * parameters of every call that needs them, so the same inputs store the same rows in every zone.
 *
 * The audited files are listed by walking every main source set under `store/src` (`commonMain`,
 * `jvmMain`, `androidMain`), never kept by hand, so a new file is audited the moment it exists.
 * Comments may name the forbidden calls; code (including a `${…}` template in a string) may not.
 */
class StoreNoAmbientEnvironmentTest {

    @Test
    fun noStoreSourceReadsTheClockTheZoneOrTheLocale() {
        val storeDir = File(assertNotNull(System.getProperty("opencircuit.storeDir"), "opencircuit.storeDir is not set — see store/build.gradle.kts"))
        val audit = AmbientEnvironmentAudit.audit(File(storeDir, "src"))

        // Anchors: the walk reached the files that take `now` and `zone`, in every source set.
        listOf(
            "commonMain/kotlin/io/github/opencircuit/store/LocalStore.kt",
            "commonMain/kotlin/io/github/opencircuit/store/LaunchRepairs.kt",
            "commonMain/kotlin/io/github/opencircuit/store/BlobStore.kt",
            "commonMain/kotlin/io/github/opencircuit/store/codec/RingAlarmCodec.kt",
            "jvmMain/kotlin/io/github/opencircuit/store/StoreFactoryJvm.kt",
            "androidMain/kotlin/io/github/opencircuit/store/StoreFactoryAndroid.kt",
        ).forEach { assertTrue(it in audit.files, "the audit never read $it") }
        assertTrue(audit.files.count { it.startsWith("commonMain/") } >= 30, "the audit read only ${audit.files.size} files")

        assertEquals(emptyList(), audit.hits, "ambient environment read in a store source")
    }

    @Test
    fun everyForbiddenReadPlantedInACopyOfTheSourcesIsFound() {
        val root = createTempDirectory("ambient-audit").toFile()
        try {
            val calls = listOf(
                "Instant" + ".now()", "LocalDate" + ".now(zone)", "Clock" + ".systemUTC()", "ZoneId" + ".systemDefault()",
                "Locale" + ".getDefault()", "TimeZone" + ".getDefault()", "System" + ".currentTimeMillis()", "System" + ".nanoTime()",
            )
            root.resolve("commonMain/kotlin/Planted.kt").write(
                "package p\n" + calls.mapIndexed { i, call -> "fun f$i() = $call\n" }.joinToString("") +
                    "fun g() = \"at \${" + "Instant" + ".now()}\"\n",
            )
            root.resolve("androidMain/kotlin/Doc.kt").write(
                "package p\n// upstream reads " + "Instant" + ".now()\n/** never " + "ZoneId" + ".systemDefault() */\nfun h(now: java.time.Instant) = now\n",
            )
            // Test sources and build output are not production sources.
            root.resolve("jvmTest/kotlin/T.kt").write("fun t() = " + "Instant" + ".now()\n")
            root.resolve("commonMain/build/Gen.kt").write("fun t() = " + "Instant" + ".now()\n")

            val audit = AmbientEnvironmentAudit.audit(root)

            assertEquals(listOf("androidMain/kotlin/Doc.kt", "commonMain/kotlin/Planted.kt"), audit.files)
            assertEquals((2..10).map { "commonMain/kotlin/Planted.kt:$it" }, audit.hits.map { it.substringBefore(" ") })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun File.write(text: String) {
        parentFile.mkdirs()
        writeText(text)
    }
}

/** Finds reads of the clock, the device's zone or its locale in the main source sets under a `src` directory. */
internal object AmbientEnvironmentAudit {

    class Result(val files: List<String>, val hits: List<String>)

    /** Each pattern is built in pieces, so this file does not contain the calls it looks for. */
    private val forbidden: List<Pair<String, Regex>> = listOf(
        "a now() call reads the clock" to Regex("""\b[A-Z]\w*\.""" + "now" + """\("""),
        "the system clock" to Regex("""\bClock\.""" + "system"),
        "the device's time zone" to Regex("""\b""" + "systemDefault" + """\(|\bTimeZone\.""" + "getDefault" + """\("""),
        "the device's locale" to Regex("""\bLocale\.""" + "getDefault" + """\("""),
        "the current time" to Regex("""\bSystem\.(""" + "currentTimeMillis|nanoTime" + """)\("""),
    )

    private val skippedDirs = setOf("build", ".gradle", ".kotlin")

    /** Every `*.kt` under a `*Main` source set of [src] (relative, `/`-separated, sorted) and each `file:line why` hit. */
    fun audit(src: File): Result {
        val files = src.listFiles().orEmpty().filter { it.isDirectory && it.name.endsWith("Main") }
            .flatMap { set ->
                set.walkTopDown().onEnter { it == set || it.name !in skippedDirs }.filter { it.isFile && it.extension == "kt" }.toList()
            }
            .map { it.relativeTo(src).invariantSeparatorsPath to it }
            .sortedBy { it.first }
        val hits = files.flatMap { (rel, file) ->
            KotlinSourceText.withoutComments(file.readText()).lines().flatMapIndexed { i, line ->
                forbidden.filter { (_, rx) -> rx.containsMatchIn(line) }.map { (why, _) -> "$rel:${i + 1} $why" }
            }
        }
        return Result(files.map { it.first }, hits)
    }
}
