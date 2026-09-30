package io.github.opencircuit.ringkit

// Charging-state inference from the battery % trend (upstream #60).
// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/ChargingInference.swift:13-30 (@ b1c2fdd).
// Pulled into E1 from E2 (ADR E1 D4 A) because `DeviceStatus.isCharging` delegates to it.
//
// The ring's charging state is NOT on the wire in any confirmed byte (PROTOCOL.md §5).
// The closest 🟢 proxy is the battery % in the 0x10/0x87 descriptor: if a short window of
// consecutive readings is strictly rising the ring is *inferred* to be charging. This is
// deliberately conservative — "inferred" only fires when we hold ≥ 2 readings that are ALL
// strictly rising; a single-reading window or any non-monotone pattern returns false.
//
// Rule: never claim CERTAINTY from this signal. Callers label the result "inferred".

object ChargingInference {

    /**
     * True when [trend] is a strictly rising sequence — every consecutive pair increases —
     * suggesting the ring may be charging (🟢 proxy). Requires ≥ 2 readings.
     *
     * Examples: `[]` → false · `[75]` → false · `[74, 76]` → true · `[74, 76, 78]` → true ·
     * `[75, 75]` → false (flat) · `[80, 78]` → false (falling) · `[74, 76, 75]` → false.
     */
    fun inferred(trend: List<Int>): Boolean {
        if (trend.size < 2) return false
        return trend.zipWithNext().all { (a, b) -> a < b }
    }
}
