package io.github.opencircuit.ringkit

// The timeline sample types of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/
// SleepDetection.swift:28-58 (@ b1c2fdd). PARTIAL port: the motion timeline and the history
// decoder build these today; the detector that consumes them (and the gravity sample type) ports
// with sleep detection.

import java.time.Instant

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
