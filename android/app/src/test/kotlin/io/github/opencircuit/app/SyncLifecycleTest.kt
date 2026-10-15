package io.github.opencircuit.app

import io.github.opencircuit.app.sync.SyncLifecycle
import io.github.opencircuit.app.sync.SyncLifecycle.Action
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What leaving and coming back to the app does to a sync (PORTING.md D-268), decided in one pure
 * place so every case runs on the JVM; the activity only reports its stop and start. Leaving the
 * app (its activity stops) while a sync runs pauses it — unless the stop is a configuration
 * change (a rotation stops and recreates the activity at once). Coming back resumes a paused sync.
 * The screen stays on exactly while a sync runs.
 */
class SyncLifecycleTest {

    @Test
    fun leavingTheAppDuringASyncPausesIt() {
        assertEquals(Action.PAUSE, SyncLifecycle.onStop(syncing = true, changingConfigurations = false))
    }

    @Test
    fun aRotationDuringASyncDoesNotPauseIt() {
        assertEquals(Action.NONE, SyncLifecycle.onStop(syncing = true, changingConfigurations = true))
    }

    @Test
    fun leavingTheAppWithNoSyncDoesNothing() {
        assertEquals(Action.NONE, SyncLifecycle.onStop(syncing = false, changingConfigurations = false))
    }

    @Test
    fun comingBackResumesAPausedSyncAndNothingElse() {
        assertEquals(Action.RESUME, SyncLifecycle.onStart(unfinished = true))
        assertEquals(Action.NONE, SyncLifecycle.onStart(unfinished = false))
    }

    @Test
    fun theScreenStaysOnExactlyWhileASyncRuns() {
        assertEquals(true, SyncLifecycle.keepScreenOn(syncing = true))
        assertEquals(false, SyncLifecycle.keepScreenOn(syncing = false))
    }
}
