package io.github.opencircuit.store

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * No source or build script anywhere in `android/` may ask Room to drop and recreate the database
 * when a migration is missing. Upstream lost every user's raw history to a store that was
 * recreated on upgrade; here a missing migration fails the open instead, and this guard keeps it
 * that way for every module, not only `:store`.
 *
 * The scan reads the tree (every `*.kt` and `*.kts` outside build output), so a new module is
 * covered without editing this test.
 */
class NoDestructiveFallbackTest {

    @Test
    fun noSourceOrBuildScriptInTheTreeUsesADestructiveMigrationFallback() {
        val rootPath = assertNotNull(System.getProperty("opencircuit.androidRoot"), "opencircuit.androidRoot is not set — see store/build.gradle.kts")
        val scan = DestructiveFallbackScan.scan(File(rootPath))

        // Anchors: the scan must have reached the files that would hold such a call.
        listOf(
            "store/build.gradle.kts",
            "store/src/commonMain/kotlin/io/github/opencircuit/store/StoreFactory.kt",
            "store/src/androidMain/kotlin/io/github/opencircuit/store/StoreFactoryAndroid.kt",
            "store/src/jvmMain/kotlin/io/github/opencircuit/store/StoreFactoryJvm.kt",
            "ringkit/build.gradle.kts",
            "settings.gradle.kts",
        ).forEach { assertTrue(it in scan.files, "the scan never read $it") }
        assertTrue(scan.files.size >= 100, "the scan read only ${scan.files.size} files")

        assertEquals(emptyList(), scan.hits, "destructive migration fallback found")
    }

    @Test
    fun aFallbackPlantedInACopyOfTheTreeIsFoundAndBuildOutputIsSkipped() {
        val root = createTempDirectory("fallback-scan").toFile()
        try {
            val call = "        .fallbackTo" + "DestructiveMigration(dropAllTables = true)"
            root.resolve("app/src/main/kotlin/Db.kt").write("package app\n$call\n")
            root.resolve("app/build.gradle.kts").write("// fallbackTo" + "DestructiveMigrationOnDowngrade\n")
            root.resolve("app/src/main/kotlin/Clean.kt").write("package app\nfun f() = 1\n")
            // Generated code and non-Kotlin files are not sources.
            root.resolve("app/build/generated/Gen.kt").write(call)
            root.resolve("notes/upgrade.md").write(call)

            val scan = DestructiveFallbackScan.scan(root)

            assertEquals(listOf("app/build.gradle.kts", "app/src/main/kotlin/Clean.kt", "app/src/main/kotlin/Db.kt"), scan.files)
            assertEquals(listOf("app/build.gradle.kts:1", "app/src/main/kotlin/Db.kt:2"), scan.hits)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun File.write(text: String) {
        parentFile.mkdirs()
        writeText(text)
    }
}

/** Finds Room's destructive-migration fallbacks (every variant shares this prefix) in a tree. */
internal object DestructiveFallbackScan {
    /** Built in two halves so this file does not contain the name it looks for. */
    private val NEEDLE = "fallbackTo" + "DestructiveMigration"

    /** Directories that hold build or tool output, never sources. */
    private val SKIPPED_DIRS = setOf("build", ".gradle", ".kotlin", ".idea")

    class Result(val files: List<String>, val hits: List<String>)

    /** Every `*.kt` / `*.kts` under [root] (paths relative, `/`-separated, sorted) and each `file:line` hit. */
    fun scan(root: File): Result {
        val files = root.walkTopDown()
            .onEnter { it == root || it.name !in SKIPPED_DIRS }
            .filter { it.isFile && (it.extension == "kt" || it.extension == "kts") }
            .map { it.relativeTo(root).invariantSeparatorsPath to it }
            .sortedBy { it.first }
            .toList()
        val hits = files.flatMap { (rel, file) ->
            file.readLines().mapIndexedNotNull { i, line -> if (NEEDLE in line) "$rel:${i + 1}" else null }
        }
        return Result(files.map { it.first }, hits)
    }
}
