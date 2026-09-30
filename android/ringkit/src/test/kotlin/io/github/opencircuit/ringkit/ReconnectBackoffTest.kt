package io.github.opencircuit.ringkit

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Auto-reconnect backoff: grows the gap between consecutive failed reconnects so a ring left on the
 * charger isn't hammered, and flips to the calm "unreachable" state after a few tries. Asserts the
 * schedule and thresholds so a regression (e.g. reverting to immediate retry) is caught.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/ReconnectBackoffTests.swift
 * (@ b1c2fdd): all 4 tests. Upstream's seconds are `Duration`s here; the expected values are the
 * literals typed from the upstream test, never read back from the Kotlin constants.
 */
class ReconnectBackoffTest {

    private fun s(seconds: Long): Duration = Duration.ofSeconds(seconds)

    // :10
    @Test
    fun scheduleGrowsThenCaps() {
        assertEquals(s(1), ReconnectBackoff.delay(attempt = 1))
        assertEquals(s(5), ReconnectBackoff.delay(attempt = 2))
        assertEquals(s(30), ReconnectBackoff.delay(attempt = 3))
        assertEquals(s(30), ReconnectBackoff.delay(attempt = 4))   // capped
        assertEquals(s(30), ReconnectBackoff.delay(attempt = 99))  // stays capped
    }

    // :18
    @Test
    fun zeroOrNegativeAttemptIsImmediate() {
        assertEquals(Duration.ZERO, ReconnectBackoff.delay(attempt = 0))
        assertEquals(Duration.ZERO, ReconnectBackoff.delay(attempt = -3))
    }

    // :23
    @Test
    fun delayIsMonotonicNonDecreasing() {
        var prev = ReconnectBackoff.delay(attempt = 0)
        for (attempt in 1..12) {
            val d = ReconnectBackoff.delay(attempt = attempt)
            assertTrue(d >= prev, "attempt $attempt: $d < $prev")
            prev = d
        }
    }

    // :32
    @Test
    fun calmStateOnlyAfterThreshold() {
        assertFalse(ReconnectBackoff.shouldSurfaceCalmState(attempts = 0))
        assertFalse(ReconnectBackoff.shouldSurfaceCalmState(attempts = 2))
        assertTrue(ReconnectBackoff.shouldSurfaceCalmState(attempts = 3))
        assertTrue(ReconnectBackoff.shouldSurfaceCalmState(attempts = 10))
    }
}
