package io.github.opencircuit.ringkit

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/AnalyticsTests.swift (@ b1c2fdd),
 * in parts: so far only `testSleepScore` (:334-342), which tests the duration score the sleep
 * pipeline uses. The other 27 tests check analytics that are not ported yet and join this class
 * with them.
 */
class AnalyticsTest {

    // Sleep score (openwhoop sleep.rs)

    @Test
    fun sleepScore() { // :334-342
        // #28: graded (floating-point ratio), not a 0-or-100 step function.
        assertEquals(100.0, SleepScore.score(durationSeconds = 8 * 3600))
        assertEquals(75.0, SleepScore.score(durationSeconds = 6 * 3600))
        assertEquals(50.0, SleepScore.score(durationSeconds = 4 * 3600))
        assertEquals(0.0, SleepScore.score(durationSeconds = 0))
        assertEquals(100.0, SleepScore.score(durationSeconds = 24 * 3600)) // clamped at the ideal
    }
}
