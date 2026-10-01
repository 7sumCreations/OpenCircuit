package io.github.opencircuit.ringkit

// PARTIAL port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/SleepScore.swift
// (@ b1c2fdd) `:1-26`: the duration score adapted from openwhoop-algos `sleep.rs`. The 6-factor
// composite score (`:27-145`) is not ported yet.

import java.time.Duration
import java.time.Instant

/** Sleep score, 0…100. */
object SleepScore {

    /** Ideal sleep duration in seconds (8 h). */
    const val IDEAL_DURATION_SECONDS: Int = 60 * 60 * 8

    /**
     * Score from a sleep duration in seconds, graded linearly and clamped at the 8 h ideal:
     * 4 h → 50, 6 h → 75, 8 h and more → 100, zero or negative → 0. openwhoop divides in integer
     * units, collapsing the score to 0-or-100; this ratio is floating point (upstream #28).
     * [durationSeconds] is 64-bit, as upstream's `Int`.
     */
    fun score(durationSeconds: Long): Double {
        val ratio = durationSeconds.toDouble() / IDEAL_DURATION_SECONDS.toDouble()
        return minOf(maxOf(ratio * 100.0, 0.0), 100.0)
    }

    /** [score] for a start/end span, in whole seconds truncated toward zero (as Swift's `Int(_:)`). */
    fun score(start: Instant, end: Instant): Double = score(Duration.between(start, end).wholeSecondsTowardZero())
}
