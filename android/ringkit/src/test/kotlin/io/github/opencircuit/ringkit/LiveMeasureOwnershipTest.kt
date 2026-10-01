package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.LiveMeasureOwnership.Action
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Ownership / takeover state machine for the single live-measure link. Locks the "don't fight the
 * current owner" contract: a user tap promotes an auto read but never wrests an active workout, an
 * auto refresh never disturbs a user's converging read, and an in-flight auto-measure stands down the
 * moment a higher-priority owner takes over.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/LiveMeasureOwnershipTests.swift
 * (@ b1c2fdd): all 17 tests.
 */
class LiveMeasureOwnershipTest {

    private fun decide(monitoring: Boolean, userInitiated: Boolean, userMeasuring: Boolean, workoutHolding: Boolean, sameMode: Boolean) =
        LiveMeasureOwnership.decide(
            monitoring = monitoring,
            userInitiated = userInitiated,
            userMeasuring = userMeasuring,
            workoutHolding = workoutHolding,
            sameMode = sameMode,
        )

    // MARK: nothing live → fresh start

    // :14 — a user read clears the stale value up front so an old lock can't look live while warming.
    @Test
    fun idleUserTapStartsWithStaleCleared() {
        assertEquals(Action.Start(clearStale = true), decide(false, true, false, false, true))
    }

    // :22 — an auto/background refresh leaves the prior value on screen until the new one locks.
    @Test
    fun idleAutoStartKeepsLastValue() {
        assertEquals(Action.Start(clearStale = false), decide(false, false, false, false, true))
    }

    // :30 — a workout starts a fresh cycle (not user-initiated) — no stale clear.
    @Test
    fun idleWorkoutStartKeepsLastValue() {
        assertEquals(Action.Start(clearStale = false), decide(false, false, false, true, true))
    }

    // MARK: takeover

    // :40
    @Test
    fun userTapTakesOverAnAutoRead() {
        assertEquals(Action.Takeover, decide(true, true, false, false, true))
    }

    // :47 — takeover is checked before the same/different-mode split.
    @Test
    fun userTapTakesOverAnAutoReadEvenInADifferentMode() {
        assertEquals(Action.Takeover, decide(true, true, false, false, false))
    }

    // MARK: workout hold is inviolable

    // :58 — a workout owns HR; a Measure tap must NOT take over in the state machine.
    @Test
    fun userTapDoesNotWrestAnActiveWorkout() {
        val action = decide(true, true, false, true, true)
        assertNotEquals<Action>(Action.Takeover, action)
        assertEquals(Action.Rearm, action, "same-mode user tap during a workout re-polls, never takes over")
    }

    // :68
    @Test
    fun userTapDuringWorkoutInOtherModeSwitchesRatherThanTakesOver() {
        val action = decide(true, true, false, true, false)
        assertNotEquals<Action>(Action.Takeover, action)
        assertEquals(Action.SwitchMode(armDeadline = true), action)
    }

    // MARK: same-mode re-tap vs auto no-op

    // :78
    @Test
    fun sameModeUserRetapRearms() {
        assertEquals(Action.Rearm, decide(true, true, true, false, true))
    }

    // :85 — a periodic auto-measure must never disturb a user's converging read.
    @Test
    fun sameModeAutoRefreshIsIgnored() {
        assertEquals(Action.Ignore, decide(true, false, true, false, true))
    }

    // MARK: mode switch

    // :95
    @Test
    fun userModeSwitchArmsDeadline() {
        assertEquals(Action.SwitchMode(armDeadline = true), decide(true, true, true, false, false))
    }

    // :102 — armDeadline false also means "keep the prior value on screen".
    @Test
    fun autoModeSwitchDoesNotArmDeadline() {
        assertEquals(Action.SwitchMode(armDeadline = false), decide(true, false, false, false, false))
    }

    // :111 — a user SpO₂ read, then toggling HR and back to SpO₂, must never let the prior value
    // masquerade as live: every user-owned entry into a mode signals a stale clear.
    @Test
    fun userModeToggleSignalsStaleClearAtEveryStep() {
        // 1. idle → user taps SpO₂
        assertEquals(Action.Start(clearStale = true), decide(false, true, false, false, true))
        // 2. live SpO₂ (user-owned) → user taps HR
        assertEquals(Action.SwitchMode(armDeadline = true), decide(true, true, true, false, false))
        // 3. live HR (user-owned) → user taps SpO₂ again — the toggle that regressed upstream
        assertEquals(Action.SwitchMode(armDeadline = true), decide(true, true, true, false, false))
    }

    // MARK: stop-ownership — auto-measure stands down for a higher-priority owner

    // :137
    @Test
    fun autoHoldsWhileItStillOwnsTheLink() {
        assertFalse(LiveMeasureOwnership.autoShouldStandDown(autoMeasuring = true, monitoring = true, modeMatches = true, calibrationCapturing = false))
    }

    // :142 — a user takeover clears `autoMeasuring`.
    @Test
    fun autoStandsDownWhenUserTookOwnership() {
        assertTrue(LiveMeasureOwnership.autoShouldStandDown(autoMeasuring = false, monitoring = true, modeMatches = true, calibrationCapturing = false))
    }

    // :148
    @Test
    fun autoStandsDownWhenMonitoringStopped() {
        assertTrue(LiveMeasureOwnership.autoShouldStandDown(autoMeasuring = true, monitoring = false, modeMatches = true, calibrationCapturing = false))
    }

    // :153
    @Test
    fun autoStandsDownWhenModeSwitchedOutFromUnderIt() {
        assertTrue(LiveMeasureOwnership.autoShouldStandDown(autoMeasuring = true, monitoring = true, modeMatches = false, calibrationCapturing = false))
    }

    // :158
    @Test
    fun autoStandsDownWhenCalibrationGrabsTheSensor() {
        assertTrue(LiveMeasureOwnership.autoShouldStandDown(autoMeasuring = true, monitoring = true, modeMatches = true, calibrationCapturing = true))
    }
}
