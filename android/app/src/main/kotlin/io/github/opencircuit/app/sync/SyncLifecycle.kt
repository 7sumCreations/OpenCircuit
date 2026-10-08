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
}
