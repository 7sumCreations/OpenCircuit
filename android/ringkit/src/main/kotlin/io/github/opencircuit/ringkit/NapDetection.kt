package io.github.opencircuit.ringkit

// PARTIAL port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/NapDetection.swift
// (@ b1c2fdd) `:17`: only the nap floor, which night selection (`BulkSleep.latestNightRecords`)
// already reads. The detector itself is not ported yet; this file grows into it, so the constant
// keeps one home.

import java.time.Duration

/** Automatic nap detection (the nap floor only, for now). */
object NapDetection {

    /** Minimum length for a stillness block to count as a nap: 15 min, the ring app's own auto-nap floor. */
    val MIN_NAP_DURATION: Duration = Duration.ofMinutes(15)
}
