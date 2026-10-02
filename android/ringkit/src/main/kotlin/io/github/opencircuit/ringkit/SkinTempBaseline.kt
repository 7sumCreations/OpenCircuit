package io.github.opencircuit.ringkit

// PARTIAL port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/SkinTempBaseline.swift
// (@ b1c2fdd) `:31`: only the normal-deviation band, which the composite sleep score reads. The
// nightly mean, rolling baseline and anomaly flags (the rest of the file) are not ported yet; this
// file grows into them, so the constant keeps one home.

/** Sleeping skin-temperature baseline (the normal band only, for now). */
object SkinTempBaseline {

    /**
     * A deviation within ± this many °C of the baseline is "normal" (the ring app's own copy: 1 °C).
     * Beyond it is an abnormal rise or drop. A signed, symmetric band — never a hardcoded temperature.
     */
    const val NORMAL_DEVIATION_C: Double = 1.0
}
