package io.github.opencircuit.store

import io.github.opencircuit.ringkit.QuantitySample
import java.time.Instant

// What the store hands back for its rollup tables (upstream returns its SwiftData model objects;
// here the rows stay internal and callers get plain values).

/** One day's step total: [day] is the start of that local day. */
data class DailySteps(val day: Instant, val steps: Long, val updatedAt: Instant)

/** One step delta and the window `[start, end]` it was counted over. */
data class StepSample(val start: Instant, val end: Instant, val delta: Long, val healthWritten: Boolean)

/**
 * The live step fold's baseline: the last raw quarter-hour counter folded ([raw], 0 to 65535), the
 * start of its local [day], and when it arrived ([sampleAt]). The next descriptor's reading is
 * folded against it (`StepAccumulator.update`, `windowStart`), across relaunches.
 */
data class StepBaseline(val raw: Int, val day: Instant, val sampleAt: Instant) {
    companion object {
        /** The descriptor's step field is 16 bits. */
        const val MAX_RAW = 65_535
    }
}

/** One step delta and its row [id], which never changes: the Health Connect mark names rows by it. */
data class StoredStepRow(val id: Long, val sample: StepSample)

/**
 * One stored sample and its row [id]. Rows of one kind may share a start; the row id orders them
 * and never changes, so it can name each one apart (the Health Connect record ids do).
 */
data class StoredSampleRow(val id: Long, val sample: QuantitySample)

/**
 * The night's key as text, as upstream writes it into every per-night key: its whole seconds since
 * 1970 printed as a Swift `Double` (`1749938400.0`). It names the night's Health Connect records,
 * so a night that moves to another key moves its records' ids with it.
 */
val StoredNight.keyText: String get() = NightOverlays.dayText(night)

/** One daytime skin-temperature reading, in degrees Celsius. */
data class DaytimeTemperature(val time: Instant, val celsius: Double)

internal fun StoredDailyEntity.toDailySteps() = DailySteps(day = day, steps = steps, updatedAt = updatedAt)

internal fun StoredStepSampleEntity.toStepSample() = StepSample(start = start, end = end, delta = delta, healthWritten = healthWritten)

internal fun StoredDaytimeTempEntity.toDaytimeTemperature() = DaytimeTemperature(time = time, celsius = celsius)
