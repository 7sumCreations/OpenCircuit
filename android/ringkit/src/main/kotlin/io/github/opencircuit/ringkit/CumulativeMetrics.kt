package io.github.opencircuit.ringkit

// Cumulative ring counters → running daily totals. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/CumulativeMetrics.swift:3-62 (@ b1c2fdd).
//
// Upstream's `CumulativeMetricState` is a struct with `var` fields; here it is an immutable data
// class (derive the next state with `copy`), so a state handed to [CumulativeMetricAccumulator]
// can never be changed by anyone else.

/**
 * Metrics emitted by the ring as monotonic counters rather than per-epoch values. Exhaustive on
 * purpose: a new [MetricKind] must decide here.
 */
val MetricKind.isCumulativeCounter: Boolean
    get() = when (this) {
        MetricKind.STEPS, MetricKind.ACTIVE_ENERGY -> true
        MetricKind.HEART_RATE, MetricKind.RESTING_HEART_RATE, MetricKind.HRV_SDNN, MetricKind.SPO2,
        MetricKind.TEMPERATURE, MetricKind.RESPIRATORY_RATE, MetricKind.SLEEP,
        MetricKind.DISTANCE, MetricKind.EXERCISE_MINUTES,
        -> false // distance + exercise minutes are derived values written directly, never raw ring counters
    }

/** The counter's last raw reading (null before the first) and the running total for the day. */
data class CumulativeMetricState(
    val previousRawValue: Double? = null,
    val dailyTotal: Double = 0.0,
)

/** One accumulated reading: [sample] carries the running [dailyTotal] as its value. */
data class CumulativeMetricResult(
    val sample: QuantitySample,
    val rawValue: Double,
    val deltaValue: Double,
    val dailyTotal: Double,
)

object CumulativeMetricAccumulator {
    /**
     * Delta = `raw − previous`; a reading below the previous one is a counter rollover, so the raw
     * value itself is the delta. The first reading (no previous) is its own delta.
     */
    fun accumulate(sample: QuantitySample, state: CumulativeMetricState): CumulativeMetricResult {
        val raw = sample.value
        val previous = state.previousRawValue
        val delta = if (previous == null) raw else if (raw >= previous) raw - previous else raw
        val dailyTotal = state.dailyTotal + delta
        return CumulativeMetricResult(
            sample = sample.copy(value = dailyTotal),
            rawValue = raw,
            deltaValue = delta,
            dailyTotal = dailyTotal,
        )
    }
}
