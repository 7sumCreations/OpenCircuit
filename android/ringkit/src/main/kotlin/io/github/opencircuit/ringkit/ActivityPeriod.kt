package io.github.opencircuit.ringkit

// PARTIAL port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/SleepDetection.swift
// (@ b1c2fdd): only the wear-gate constant at `:128` (ADR E1 D4 A).

/**
 * Minimal home for the sleep wear-gate threshold that `DeviceStatus.isWorn` defaults to.
 *
 * Only [WORN_MIN_TEMPERATURE_C] is ported in E1. The rest of upstream's
 * `struct ActivityPeriod` (`SleepDetection.swift:63`: `activity`, `start`, `end`) and the rest of
 * `SleepDetection.swift` port in E3. If E3 turns this into a `data class`, the constant moves into
 * its `companion object` and the call site `ActivityPeriod.WORN_MIN_TEMPERATURE_C` stays unchanged.
 */
object ActivityPeriod {
    /**
     * Skin temperature (°C) at or above which a descriptor reading is treated as "worn" (🟡 proxy).
     * A worn Gen-2 ring reads ~30–34 °C; off-wrist / on the charger it falls toward room ambient.
     */
    const val WORN_MIN_TEMPERATURE_C: Double = 28.0
}
