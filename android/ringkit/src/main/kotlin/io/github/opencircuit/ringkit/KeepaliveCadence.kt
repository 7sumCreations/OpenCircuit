package io.github.opencircuit.ringkit

// Adaptive idle-keepalive cadence. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/KeepaliveCadence.swift:9-22 (@ b1c2fdd).
//
// The `0x10`/`0x87` descriptor (steps / temperature / battery) is solicited by a `07 00 00`
// heartbeat; polling it every 30 s around the clock measurably drains both the ring and the phone.
// Freshness only matters in two windows — the nightly temperature capture and an active live
// measurement — so this picks a slow daytime cadence and tightens only when it counts.

import java.time.Duration

object KeepaliveCadence {

    /**
     * Time between idle descriptor heartbeats.
     * - [isNight]: inside the nightly sleep/temperature window (skin temperature streams then — keep
     *   it fresh).
     * - [activeMeasurement]: a live HR/SpO₂ read (or its prep) is in flight — re-check often so the
     *   keepalive resumes promptly when it ends (the heartbeat itself is suppressed meanwhile).
     * - [batterySaver]: the user opted into battery saver — stretch the idle cadences.
     *
     * iOS-tuned — re-check in E9/E11. All five values ported verbatim from upstream (`:18-20`):
     * 30 s live, 60 s (90 s saver) at night, 180 s (300 s saver) by day.
     */
    fun interval(isNight: Boolean, activeMeasurement: Boolean, batterySaver: Boolean): Duration {
        if (activeMeasurement) return Duration.ofSeconds(30)                  // a live read owns the link — re-check fast
        if (isNight) return Duration.ofSeconds(if (batterySaver) 90 else 60)  // overnight temperature matters
        return Duration.ofSeconds(if (batterySaver) 300 else 180)             // daytime idle: 3 min (5 min in saver)
    }
}
