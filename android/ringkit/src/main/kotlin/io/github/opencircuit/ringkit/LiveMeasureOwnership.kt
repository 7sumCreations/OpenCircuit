package io.github.opencircuit.ringkit

// Who owns the single live-measure link when a live read is requested, and when an in-flight
// auto-measure must stand down for a higher-priority owner (a user tap, a workout, calibration).
// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/LiveMeasureOwnership.swift:15-67
// (@ b1c2fdd).
//
// WHY. The ring measures ONE metric at a time over ONE live link, so a Measure tap, a periodic
// auto/background refresh and a workout all contend for it. Upstream's ownership rules used to live
// inline as nested conditionals that were easy to get subtly wrong (a user tap wresting the link from
// an active workout, or a takeover firing while a measurement the user already started was
// mid-flight). As a pure decision the "don't fight the current owner" contract is locked by tests.
// Stateless: the caller holds the link state and passes it in on every call.

object LiveMeasureOwnership {

    /** What a live-read request should do, given the current link state. */
    sealed interface Action {
        /**
         * Nothing is live → begin a fresh cycle. [clearStale] drops the prior value up front for a
         * user read so an old lock can't masquerade as live while the new one warms up.
         */
        data class Start(val clearStale: Boolean) : Action

        /**
         * An auto/background read is live and the user tapped Measure → promote it to an explicit
         * user measurement (stop the foreign cycle, then start a fresh user-owned one).
         */
        data object Takeover : Action

        /** Already live in the SAME mode on a user-owned read and the user tapped again → re-poll. */
        data object Rearm : Action

        /** Already live in the same mode on an auto read → leave the converging read alone. */
        data object Ignore : Action

        /**
         * Already live in a DIFFERENT mode → switch the mode in place. [armDeadline] when the switch
         * was user-initiated (the user now owns the timeout UX for the new mode).
         */
        data class SwitchMode(val armDeadline: Boolean) : Action
    }

    /**
     * Decide the ownership action for one live-read request.
     *
     * @param monitoring is a live cycle currently running?
     * @param userInitiated is this a real Measure tap (vs an auto/background refresh)?
     * @param userMeasuring is the CURRENT cycle already a user-owned measurement?
     * @param workoutHolding does an active workout hold the HR link? A user tap must NOT wrest it.
     * @param sameMode does the requested mode match the mode currently being measured?
     */
    fun decide(
        monitoring: Boolean,
        userInitiated: Boolean,
        userMeasuring: Boolean,
        workoutHolding: Boolean,
        sameMode: Boolean,
    ): Action {
        if (!monitoring) return Action.Start(clearStale = userInitiated)
        // Promote an auto/background read into a user measurement — but never take the link from an
        // active workout, and never re-takeover a measurement the user already started.
        if (userInitiated && !userMeasuring && !workoutHolding) return Action.Takeover
        if (sameMode) return if (userInitiated) Action.Rearm else Action.Ignore
        return Action.SwitchMode(armDeadline = userInitiated)
    }

    /**
     * Whether an in-flight auto-measure cycle should abandon its wait because a higher-priority owner
     * took the link (a user takeover clears [autoMeasuring]; a mode switch or a stop clears the
     * match / [monitoring]; calibration grabs the sensor). An aborted cycle must never be counted
     * toward the not-worn inference in [AutoMeasureGate].
     */
    fun autoShouldStandDown(
        autoMeasuring: Boolean,
        monitoring: Boolean,
        modeMatches: Boolean,
        calibrationCapturing: Boolean,
    ): Boolean = !autoMeasuring || !monitoring || !modeMatches || calibrationCapturing
}
