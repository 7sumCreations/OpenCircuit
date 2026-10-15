package io.github.opencircuit.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The activity and the Ring screen are Android code that never runs on the JVM, so the few lines
 * that tie them to the sync's lifecycle rule ([io.github.opencircuit.app.sync.SyncLifecycle],
 * tested on its own) are checked on the source: the activity's stop and start reach the current
 * session's sync — the stop with whether it is only a configuration change — and the Ring screen
 * keeps the screen on exactly while a sync runs, letting go when it leaves the screen.
 */
class LifecycleGlueSourceTest {

    private val activity = File("src/main/kotlin/io/github/opencircuit/app/MainActivity.kt").readText()
    private val ringScreen = File("src/main/kotlin/io/github/opencircuit/app/ring/RingScreen.kt").readText()

    /** The body of `override fun <name>() { … }` (a body with no nested braces). */
    private fun body(name: String): String {
        val match = Regex("""override fun $name\(\) \{([^{}]*)\}""").find(activity)
        assertTrue(match != null, "MainActivity has no $name()")
        return match.groupValues[1]
    }

    @Test
    fun theActivitysStopAndStartReachTheSessionsSync() {
        assertTrue("container.ringSessions.current.value?.sync?.onAppStopped(isChangingConfigurations)" in body("onStop"))
        assertTrue("container.ringSessions.current.value?.sync?.onAppStarted()" in body("onStart"))
        assertEquals(1, Regex("""onAppStopped\(""").findAll(activity).count(), "one place pauses")
        assertEquals(1, Regex("""onAppStarted\(""").findAll(activity).count(), "one place resumes")
    }

    @Test
    fun theRingScreenKeepsTheScreenOnWhileASyncRuns() {
        assertTrue("val keepOn = SyncLifecycle.keepScreenOn(state.ringData.syncing)" in ringScreen)
        assertTrue("view.keepScreenOn = keepOn" in ringScreen)
        assertTrue("onDispose { view.keepScreenOn = false }" in ringScreen)
        assertEquals(2, Regex("""keepScreenOn = """).findAll(ringScreen).count(), "set from the rule, cleared on dispose, nowhere else")
    }
}
