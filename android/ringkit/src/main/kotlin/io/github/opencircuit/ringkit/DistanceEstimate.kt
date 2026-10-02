package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/DistanceEstimate.swift
// (@ b1c2fdd), whole. The step count is an `Int` (upstream's 64-bit `Int`); it is converted to a
// double before it is multiplied, as upstream does, so no count can overflow.

/**
 * Walking / running distance estimated from the decoded step count. Distance is never on the wire
 * (PROTOCOL.md §5.3.1): the official app computes it client-side as `steps × ~0.248 m`, a fixed
 * per-step constant not personalised by height or sex. An ESTIMATE, the same one the app makes.
 */
object DistanceEstimate {

    /** RingConn's own per-step distance constant (confirmed via APK decompile, PROTOCOL.md §5.3.1). */
    const val METERS_PER_STEP: Double = 0.248

    /** Estimated distance in metres (`steps × METERS_PER_STEP`); 0 for a non-positive count. */
    fun meters(steps: Int): Double {
        if (steps <= 0) return 0.0
        return steps.toDouble() * METERS_PER_STEP
    }
}
