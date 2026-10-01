package io.github.opencircuit.ringkit

// The timeline sample types of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/
// SleepDetection.swift:15-59 (@ b1c2fdd). The detector that consumes them, and the `Activity` /
// `ActivityPeriod` types, are in `ActivityPeriod.kt`.

import java.time.Instant

/**
 * A 3-axis gravity vector, in g — upstream's `SIMD3<Float>`. A value: equal components compare
 * equal, and nothing can change a vector after construction.
 *
 * Equality is Kotlin's data-class equality, not IEEE `==`: `Gravity(NaN, …)` equals itself and
 * `0.0f` differs from `-0.0f`, where Swift's SIMD `==` says the opposite. Only equality differs;
 * the detector reads the components, never compares vectors.
 */
data class Gravity(val x: Float, val y: Float, val z: Float)

/**
 * One reading on the gravity timeline. `gravity == null` means no gravity data, which the detector
 * treats as movement (active), matching openwhoop.
 */
data class GravitySample(val time: Instant, val gravity: Gravity?)

/**
 * One reading on the motion timeline: a per-30 s movement magnitude (the `0x4c` `[10:15]` motion
 * count). Unworn / no-measurement samples carry [Float.MAX_VALUE] (upstream's
 * `.greatestFiniteMagnitude`).
 */
data class MotionSample(val time: Instant, val movement: Float)

/**
 * One skin-temperature reading on the wear-detection timeline. Skin temperature rides the
 * `0x10`/`0x87` descriptor (PROTOCOL.md §5.4), not the `0x4c` records, so callers thread the night's
 * stored temperature samples in alongside the motion timeline.
 */
data class TemperatureSample(val time: Instant, val celsius: Double)

/**
 * One heart-rate reading on the HR-gate timeline, built from the SAME `0x4c` records as the motion
 * timeline (byte `[4]`, the all-day HR). Used to reject an awake-but-still block from sleep.
 */
data class HeartRateSample(val time: Instant, val bpm: Int)
