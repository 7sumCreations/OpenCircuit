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
    fun theActivityTellsTheAppWhenItIsInFrontAndClosesAHeldLinkWhenTheUserLeaves() {
        val start = body("onStart")
        val stop = body("onStop")
        assertTrue("container.appInForeground.value = true" in start)
        assertTrue("if (!isChangingConfigurations) container.appInForeground.value = false" in stop, "a rotation is not leaving")
        assertTrue("container.ringSessions.current.value?.onAppLeft(container.appPrefs.disconnectAfterSync, isChangingConfigurations)" in stop)
        // Out of front before the sync's pause: the paused sync's end then closes the link.
        assertTrue(stop.indexOf("appInForeground.value = false") < stop.indexOf("onAppStopped("))
        assertEquals(2, Regex("""appInForeground\.value = """).findAll(activity).count(), "set at start and stop, nowhere else")
    }

    @Test
    fun todayIsVisibleExactlyFromItsResumeToItsPause() {
        val today = activity.substringAfter("Destination.Today -> {").substringBefore("Destination.Ring ->")
        assertTrue("LifecycleResumeEffect(Unit) {" in today, "only the Today branch sets it")
        assertTrue("container.todayVisible.value = true" in today)
        // A rotation is not leaving Today: the running live measure is kept through it.
        assertTrue("onPauseOrDispose { if (!isChangingConfigurations) container.todayVisible.value = false }" in today)
        assertEquals(2, Regex("""todayVisible\.value = """).findAll(activity).count(), "set on resume, cleared on pause or leaving, nowhere else")
    }

    @Test
    fun theRingScreenKeepsTheScreenOnWhileASyncRuns() {
        assertTrue("val keepOn = SyncLifecycle.keepScreenOn(state.ringData.syncing)" in ringScreen)
        assertTrue("view.keepScreenOn = keepOn" in ringScreen)
        assertTrue("onDispose { view.keepScreenOn = false }" in ringScreen)
        assertEquals(2, Regex("""keepScreenOn = """).findAll(ringScreen).count(), "set from the rule, cleared on dispose, nowhere else")
    }
}
