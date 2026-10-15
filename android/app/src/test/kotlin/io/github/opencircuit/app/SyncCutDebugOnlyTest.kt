package io.github.opencircuit.app

import io.github.opencircuit.app.debug.SyncCutArming
import io.github.opencircuit.app.debug.armedText
import io.github.opencircuit.app.sync.HistoryStore
import io.github.opencircuit.app.sync.SessionHistory
import io.github.opencircuit.app.sync.SyncFaultInjector
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The sync test action ("Cut the next sync after N pages") exists in the debug build only. The
 * release APK is built from `src/main` + `src/release`, which never run here, so this is checked
 * on the source: neither names the action, its state or its UI; the release `VariantLinks` hands
 * every session the never-cutting injector and no Connection details tools; the main code's only
 * route to it is `VariantLinks.syncFaults` in the container and `VariantLinks.detailsTools` on the
 * Ring screen. The debug state itself is tested here too.
 */
class SyncCutDebugOnlyTest {

    private fun sources(set: String): Map<File, String> =
        File("src/$set").walkTopDown().filter { it.isFile && it.extension == "kt" }.associateWith { it.readText() }

    private val debugOnly = listOf("Cut the next sync", "Next sync will be cut", "SyncCutArming", "SyncCutControls", "opencircuit.app.debug")

    @Test
    fun neitherTheMainNorTheReleaseSourceSetNamesTheAction() {
        for (set in listOf("main", "release")) {
            val files = sources(set)
            assertTrue(files.isNotEmpty(), "src/$set has Kotlin files")
            for ((file, text) in files) {
                for (token in debugOnly) assertTrue(token !in text, "$file names \"$token\": the release build would carry the test action")
            }
        }
        // Not a vacuous scan: the debug source set does carry them.
        val debug = sources("debug").values.joinToString("\n")
        for (token in debugOnly.dropLast(1)) assertTrue(token in debug, "src/debug carries \"$token\"")
    }

    @Test
    fun theReleaseVariantNeverCutsAndDrawsNoTools() {
        val release = File("src/release/kotlin/io/github/opencircuit/app/VariantLinks.kt").readText()
        assertTrue("val syncFaults: SyncFaultInjector = SyncFaultInjector.NONE" in release, release)
        assertTrue("val detailsTools: (@Composable () -> Unit)? = null" in release, release)
        assertNull(SyncFaultInjector.NONE.takeCutAfterPages())
        val bare = SessionHistory(HistoryStore.NONE, wallClock = { Instant.EPOCH }, disconnectAfterSync = { false })
        assertSame(SyncFaultInjector.NONE, bare.faults, "a session built without one never cuts")
    }

    @Test
    fun theMainCodeReachesItOnlyThroughVariantLinks() {
        val main = sources("main")
        val container = main.entries.single { it.key.name == "AppContainer.kt" }.value
        assertTrue("faults = VariantLinks.syncFaults," in container)
        val activity = main.entries.single { it.key.name == "MainActivity.kt" }.value
        assertTrue("detailsTools = VariantLinks.detailsTools" in activity)
        val users = main.filterValues { "VariantLinks.syncFaults" in it || "VariantLinks.detailsTools" in it }.keys.map { it.name }.sorted()
        assertEquals(listOf("AppContainer.kt", "MainActivity.kt"), users)
    }

    @Test
    fun armingIsTakenOnceByTheNextSync() {
        val arming = SyncCutArming()
        assertNull(arming.takeCutAfterPages(), "not armed: no cut")
        arming.arm(20)
        assertEquals(20, arming.armed.value)
        assertEquals("Next sync will be cut after 20 pages", armedText(20))
        assertEquals(20, arming.takeCutAfterPages())
        assertNull(arming.armed.value, "taking it disarms it")
        assertNull(arming.takeCutAfterPages(), "the sync after is not cut")
        arming.arm(5)
        arming.disarm()
        assertNull(arming.takeCutAfterPages())
        assertFailsWith<IllegalArgumentException> { arming.arm(0) }
        assertEquals(listOf(5, 20, 50), SyncCutArming.PRESETS)
    }
}
