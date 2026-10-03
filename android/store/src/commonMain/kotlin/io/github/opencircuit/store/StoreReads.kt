package io.github.opencircuit.store

import java.time.Instant

// What the store hands back for its rollup tables (upstream returns its SwiftData model objects;
// here the rows stay internal and callers get plain values).

/** One day's step total: [day] is the start of that local day. */
data class DailySteps(val day: Instant, val steps: Long, val updatedAt: Instant)

/** One step delta and the window `[start, end]` it was counted over. */
data class StepSample(val start: Instant, val end: Instant, val delta: Long, val healthWritten: Boolean)

/** One daytime skin-temperature reading, in degrees Celsius. */
data class DaytimeTemperature(val time: Instant, val celsius: Double)

internal fun StoredDailyEntity.toDailySteps() = DailySteps(day = day, steps = steps, updatedAt = updatedAt)

internal fun StoredStepSampleEntity.toStepSample() = StepSample(start = start, end = end, delta = delta, healthWritten = healthWritten)

internal fun StoredDaytimeTempEntity.toDaytimeTemperature() = DaytimeTemperature(time = time, celsius = celsius)
