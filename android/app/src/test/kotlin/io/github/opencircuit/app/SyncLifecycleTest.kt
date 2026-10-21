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

    @Test
    fun disconnectAfterSyncingActsOnlyOnceTheAppIsNotInFront() {
        assertEquals(true, SyncLifecycle.disconnectAfterSync(switchOn = true, inForeground = false))
        assertEquals(false, SyncLifecycle.disconnectAfterSync(switchOn = true, inForeground = true), "in front the link stays up for live heart rate")
        assertEquals(false, SyncLifecycle.disconnectAfterSync(switchOn = false, inForeground = false))
        assertEquals(false, SyncLifecycle.disconnectAfterSync(switchOn = false, inForeground = true))
    }

    @Test
    fun leavingTheAppClosesAnIdleHeldLinkOnlyWithTheSwitchOn() {
        assertEquals(true, SyncLifecycle.disconnectOnStop(switchOn = true, linkUp = true, syncing = false, changingConfigurations = false))
        assertEquals(false, SyncLifecycle.disconnectOnStop(switchOn = false, linkUp = true, syncing = false, changingConfigurations = false), "switch off: the link is held")
        assertEquals(false, SyncLifecycle.disconnectOnStop(switchOn = true, linkUp = false, syncing = false, changingConfigurations = false), "no link to close")
        assertEquals(false, SyncLifecycle.disconnectOnStop(switchOn = true, linkUp = true, syncing = true, changingConfigurations = false), "a running sync pauses; its own end closes the link")
        assertEquals(false, SyncLifecycle.disconnectOnStop(switchOn = true, linkUp = true, syncing = false, changingConfigurations = true), "a rotation is not leaving")
    }
}
