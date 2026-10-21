package io.github.opencircuit.app.sync

/**
 * What the app's visibility does to a sync, as a pure decision (PORTING.md D-268): this version
 * has no foreground service, and Android freezes a cached app about 10 s after it leaves the
 * screen, so a sync must not be left running behind the user's back. The activity reports its
 * stop and start; the controller acts ([HistoryDrainController.pause] / [HistoryDrainController.resume]).
 */
object SyncLifecycle {

    /** What to do with the sync. */
    enum class Action { PAUSE, RESUME, NONE }

    /**
     * The activity stopped. A running sync is paused — unless the activity only stops to be
     * recreated for a configuration change (a rotation), which is not the user leaving.
     */
    fun onStop(syncing: Boolean, changingConfigurations: Boolean): Action =
        if (syncing && !changingConfigurations) Action.PAUSE else Action.NONE

    /** The activity started: a sync left [unfinished] by a pause resumes. */
    fun onStart(unfinished: Boolean): Action = if (unfinished) Action.RESUME else Action.NONE

    /** The screen is kept on while a sync runs, so the app stays on top and is not frozen. */
    fun keepScreenOn(syncing: Boolean): Boolean = syncing

    /**
     * Whether "Disconnect after syncing" closes the link now: only with the switch on AND the app
     * not on screen. While the app is in front the link stays up (live heart rate on Today); it
     * closes when the user leaves, after any running sync. A held link also runs the hourly
     * cadence sync while the app is open.
     */
    fun disconnectAfterSync(switchOn: Boolean, inForeground: Boolean): Boolean = switchOn && !inForeground

    /**
     * The activity stopped (not for a rotation): close an idle held link when the switch says so.
     * A running sync is not cut here; it pauses, and its own end closes the link.
     */
    fun disconnectOnStop(switchOn: Boolean, linkUp: Boolean, syncing: Boolean, changingConfigurations: Boolean): Boolean =
        switchOn && linkUp && !syncing && !changingConfigurations
}
