package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/UserProfile.swift (@ b1c2fdd), whole,
// as values only: upstream's `Codable` conformance waits for the stored form (the storage epic
// decides it; `rawValue` is the name upstream stores).

/** Biological sex for the energy formulas. [rawValue] is upstream's stored case name. */
enum class BiologicalSex(val rawValue: String) {
    MALE("male"),
    FEMALE("female"),
}

/**
 * The wearer's profile for the energy formulas. An immutable value, as upstream's struct; nothing
 * here range-checks it (upstream does not either) — the profile's entry point and the health writer
 * do (see `PORTING.md`).
 */
data class UserProfile(
    val age: Int,
    val weightKg: Double,
    val heightCm: Double,
    val sex: BiologicalSex,
)
