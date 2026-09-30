package io.github.opencircuit.ringkit

// Aggregate-pattern decode-sanity check (firmware-drift risk: protocol observations are pinned to
// one FW version). Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/DecodeAnomaly.swift:15-55
// (@ b1c2fdd).
//
// Per-field clamps (e.g. LiveHR.VALID_BPM) already catch single implausible values; this is about
// PATTERNS across a whole drain/session that a single-value clamp can't see — what a firmware
// update silently changing a byte's meaning would produce. Deliberately conservative: each check
// requires a STRUCTURAL signal (a SUSTAINED run of bad readings), never a single bad sample.
// Pure; the app layer decides what to do with the result.
//
// NOT PORTED: `detect(records:minWornEpochs:)` (:31-35) and its 4 tests need `BulkRecord`, which
// is E2's history decoder — they arrive with it (PORTING.md D-7).

/** A pattern-level decode anomaly. [rawValue] is upstream's stable string id. */
enum class DecodeAnomaly(val rawValue: String) {
    /**
     * A drained night/session had enough WORN epochs to expect heart rate, but every one decoded
     * HR as null. Detected by `detect(records:)` — arrives in E2.
     */
    ALL_ZERO_HR_WHILE_WORN("allZeroHRWhileWorn"),

    /**
     * A SUSTAINED run of live skin-temperature readings outside a wide physical sanity band — not
     * a single spike (donning/charger transients are real and expected), but enough consecutive
     * readings to suggest the byte offset itself has shifted.
     */
    SKIN_TEMP_OUT_OF_PHYSICAL_RANGE("skinTempOutOfPhysicalRange");

    companion object {
        /**
         * True when [readingsC] (°C, live descriptor readings in order) holds [sustainedRun]
         * consecutive readings outside [minC]…[maxC]. The band ends themselves are plausible. A
         * single in-band reading resets the run, so a normal transient never fires this.
         */
        fun hasSustainedTemperatureAnomaly(
            readingsC: List<Double>,
            minC: Double = 15.0,
            maxC: Double = 45.0,
            sustainedRun: Int = 5,
        ): Boolean {
            var run = 0
            for (r in readingsC) {
                if (r < minC || r > maxC) {
                    run += 1
                    if (run >= sustainedRun) return true
                } else {
                    run = 0
                }
            }
            return false
        }
    }
}
