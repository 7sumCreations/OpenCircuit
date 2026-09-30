package io.github.opencircuit.ringkit

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Adaptive keepalive cadence: slow by day, tighter at night or during a live read, and stretched
 * further in battery saver. Asserts the ordering so a regression that, say, reverts to a 30 s day
 * poll is caught.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/KeepaliveCadenceTests.swift
 * (@ b1c2fdd): all 5 tests. Expected values are the literals typed from the upstream test.
 */
class KeepaliveCadenceTest {

    private fun s(seconds: Long): Duration = Duration.ofSeconds(seconds)

    // :9
    @Test
    fun daytimeIsSlow() {
        assertEquals(s(180), KeepaliveCadence.interval(isNight = false, activeMeasurement = false, batterySaver = false))
    }

    // :13
    @Test
    fun nightTightens() {
        assertEquals(s(60), KeepaliveCadence.interval(isNight = true, activeMeasurement = false, batterySaver = false))
    }

    // :17
    @Test
    fun batterySaverStretchesIdleCadences() {
        assertEquals(s(300), KeepaliveCadence.interval(isNight = false, activeMeasurement = false, batterySaver = true))
        assertEquals(s(90), KeepaliveCadence.interval(isNight = true, activeMeasurement = false, batterySaver = true))
    }

    // :22 — a live read suppresses the heartbeat but wants fast re-checks regardless of night/saver.
    @Test
    fun activeMeasurementOverridesEverything() {
        for (night in listOf(true, false)) {
            for (saver in listOf(true, false)) {
                assertEquals(s(30), KeepaliveCadence.interval(isNight = night, activeMeasurement = true, batterySaver = saver))
            }
        }
    }

    // :31
    @Test
    fun nightIsAlwaysTighterThanDay() {
        val day = KeepaliveCadence.interval(isNight = false, activeMeasurement = false, batterySaver = false)
        val night = KeepaliveCadence.interval(isNight = true, activeMeasurement = false, batterySaver = false)
        assertTrue(night < day)
    }
}
