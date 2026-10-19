package io.github.opencircuit.health

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Where Android may appear. The record rules, ids and writer are plain Kotlin tested on the JVM,
 * so `:health`'s common code imports nothing from Android; the one adapter over Jetpack Health
 * Connect lives in `:health`'s androidMain and is the only code in the project that imports
 * `androidx.health`. `:ringkit` imports nothing from Android at all. The library itself is
 * declared once, pinned, for androidMain only, and `:health` does not reach into `:store`.
 */
class HealthModulePurityTest {

    private val root: File = File(
        assertNotNull(System.getProperty("opencircuit.androidRoot"), "system property opencircuit.androidRoot is not set — see health/build.gradle.kts"),
    )

    /** The Kotlin files under [dir] (relative to the Android root) and their text. */
    private fun sources(dir: String): Map<String, String> =
        File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(root).path to it.readText() }

    @Test
    fun commonCodeImportsNothingFromAndroid() {
        val common = sources("health/src/commonMain")
        // The scan must reach the module's real code, not an empty or moved folder.
        assertTrue(common.size >= 6, "only ${common.size} files scanned in health/src/commonMain")
        assertTrue(common.keys.any { it.endsWith("HealthRanges.kt") })
        assertEquals(emptyList(), PurityScan.androidImports(common))
        val ringkit = sources("ringkit/src/main")
        assertTrue(ringkit.size >= 50, "only ${ringkit.size} files scanned in ringkit/src/main")
        assertEquals(emptyList(), PurityScan.androidImports(ringkit))
    }

    @Test
    fun onlyHealthsAndroidCodeImportsTheHealthConnectLibrary() {
        val everywhere = listOf("health/src", "app/src", "store/src", "ble/src", "ringkit/src").flatMap { sources(it).entries }
            .associate { it.key to it.value }
        assertTrue(everywhere.size >= 300, "only ${everywhere.size} files scanned")
        val offenders = PurityScan.healthLibraryImports(everywhere).filterNot { it.startsWith("health/src/androidMain/") }
        assertEquals(emptyList(), offenders)
    }

    @Test
    fun theLibraryIsDeclaredOncePinnedAndForAndroidOnly() {
        val build = File(root, "health/build.gradle.kts").readText()
        assertEquals(listOf("androidx.health.connect:connect-client:1.1.0"), PurityScan.healthLibraryCoordinates(build))
        assertTrue(PurityScan.declaredInsideBlock(build, "androidMain.dependencies", "connect-client"))
        assertTrue("project(\":store\")" !in build, ":health must not depend on :store")
        val others = listOf("app", "store", "ble", "ringkit").flatMap { PurityScan.healthLibraryCoordinates(File(root, "$it/build.gradle.kts").readText()) }
        assertEquals(emptyList(), others, "the Health Connect library is declared only in :health")
    }

    // --- the scan itself can fail ----------------------------------------------------------------

    @Test
    fun theScanCatchesEachKindOfOffence() {
        val files = mapOf(
            "a.kt" to "package x\nimport android.content.Context\n",
            "b.kt" to "package x\nimport androidx.health.connect.client.HealthConnectClient\n",
            "c.kt" to "package x\n// import android.content.Context is only a comment\nval s = \"import androidx.health\"\n",
            "d.kt" to "package x\nimport java.time.Instant\n",
        )
        assertEquals(listOf("a.kt", "b.kt"), PurityScan.androidImports(files))
        assertEquals(listOf("b.kt"), PurityScan.healthLibraryImports(files))
        val script = "kotlin {\n  sourceSets {\n    commonMain.dependencies {\n      implementation(\"androidx.health.connect:connect-client:1.2.0-alpha06\")\n    }\n  }\n}\n"
        assertEquals(listOf("androidx.health.connect:connect-client:1.2.0-alpha06"), PurityScan.healthLibraryCoordinates(script))
        assertTrue(!PurityScan.declaredInsideBlock(script, "androidMain.dependencies", "connect-client"))
    }
}

/** The text rules behind [HealthModulePurityTest]. */
object PurityScan {
    private val androidImport = Regex("""^import (android|androidx)\.""", RegexOption.MULTILINE)
    private val healthImport = Regex("""^import androidx\.health\.""", RegexOption.MULTILINE)
    private val coordinate = Regex(""""(androidx\.health\.connect:[A-Za-z0-9.-]+:[^"]+)"""")

    fun androidImports(files: Map<String, String>): List<String> = files.filterValues { androidImport.containsMatchIn(it) }.keys.sorted()

    fun healthLibraryImports(files: Map<String, String>): List<String> = files.filterValues { healthImport.containsMatchIn(it) }.keys.sorted()

    fun healthLibraryCoordinates(buildScript: String): List<String> = coordinate.findAll(buildScript).map { it.groupValues[1] }.toList()

    /** True when every line naming [needle] lies inside the `{ }` block opened by [blockName]. */
    fun declaredInsideBlock(buildScript: String, blockName: String, needle: String): Boolean {
        var depth = 0
        var blockDepth = -1
        var found = false
        for (line in buildScript.lines()) {
            if (blockDepth < 0 && line.contains("$blockName {")) blockDepth = depth
            if (needle in line) {
                if (blockDepth < 0) return false
                found = true
            }
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (blockDepth >= 0 && depth <= blockDepth) blockDepth = -1
        }
        return found
    }
}
