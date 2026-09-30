package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cumulative ring counters (steps, active energy) turned into a running daily total: the delta is
 * `raw − previous`, a counter that goes backwards is treated as a rollover (the raw value IS the
 * delta), and the total continues from state persisted across sessions.
 *
 * Port of upstream
 * ios/OpenCircuitKit/Tests/OpenCircuitKitTests/CumulativeMetricAccumulatorTests.swift (@ b1c2fdd)
 * — all 3 tests.
 */
class CumulativeMetricAccumulatorTest {
    private val t0 = Instant.ofEpochSecond(1_000)
    private val t1 = Instant.ofEpochSecond(2_000)
    private val t2 = Instant.ofEpochSecond(3_000)

    private fun steps(start: Instant, value: Double) = QuantitySample(kind = MetricKind.STEPS, start = start, value = value)

    @Test
    fun normalDeltaAccumulationProducesRunningTotal() { // :9-24
        val first = CumulativeMetricAccumulator.accumulate(steps(t0, 100.0), state = CumulativeMetricState())
        val second = CumulativeMetricAccumulator.accumulate(
            steps(t1, 140.0),
            state = CumulativeMetricState(previousRawValue = first.rawValue, dailyTotal = first.dailyTotal),
        )

        assertEquals(100.0, first.deltaValue)
        assertEquals(100.0, first.dailyTotal)
        assertEquals(40.0, second.deltaValue)
        assertEquals(140.0, second.dailyTotal)
        assertEquals(140.0, second.sample.value)
    }

    @Test
    fun counterRolloverTreatsRawValueAsDelta() { // :26-35
        val result = CumulativeMetricAccumulator.accumulate(
            steps(t1, 12.0),
            state = CumulativeMetricState(previousRawValue = 250.0, dailyTotal = 250.0),
        )

        assertEquals(12.0, result.deltaValue)
        assertEquals(262.0, result.dailyTotal)
        assertEquals(262.0, result.sample.value)
    }

    @Test
    fun multiSessionAccumulationContinuesFromPersistedState() { // :37-62
        val sessionOne = CumulativeMetricAccumulator.accumulate(steps(t0, 75.0), state = CumulativeMetricState())
        val persistedState = CumulativeMetricState(
            previousRawValue = sessionOne.rawValue,
            dailyTotal = sessionOne.dailyTotal,
        )
        val sessionTwoFirst = CumulativeMetricAccumulator.accumulate(steps(t1, 90.0), state = persistedState)
        val sessionTwoSecond = CumulativeMetricAccumulator.accumulate(
            steps(t2, 125.0),
            state = CumulativeMetricState(
                previousRawValue = sessionTwoFirst.rawValue,
                dailyTotal = sessionTwoFirst.dailyTotal,
            ),
        )

        assertEquals(15.0, sessionTwoFirst.deltaValue)
        assertEquals(90.0, sessionTwoFirst.dailyTotal)
        assertEquals(35.0, sessionTwoSecond.deltaValue)
        assertEquals(125.0, sessionTwoSecond.dailyTotal)
    }
}
