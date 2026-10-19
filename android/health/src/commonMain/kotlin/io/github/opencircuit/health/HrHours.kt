package io.github.opencircuit.health

import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToLong

/**
 * Heart rate is written as one series record per UTC clock hour (`oc:hr:<hourStart>`). Whenever a
 * sample of an hour is pending, the whole hour is rebuilt from every heart-rate sample the store
 * holds in it and written again under the same id with a higher version, so Health Connect
 * replaces the hour's record instead of adding a second one. A UTC hour never depends on the
 * phone's zone, so a zone change cannot split or rename an hour already written.
 *
 * Nothing after [now] is written: the store keeps a sample up to a day ahead (a clock set back),
 * and Health Connect refuses a record whose samples lie outside its span. Such a sample is left
 * out and stays pending until it is in the past.
 */
object HrHours {
    private const val MILLIS_PER_HOUR = 3_600_000L

    /** One hour's record and the stored samples it holds: the writer marks those once Health Connect accepts it. */
    class Hour(val record: HeartRateSeries, val samples: List<QuantitySample>)

    /** The start of [t]'s UTC clock hour. */
    fun hourStart(t: Instant): Instant = Instant.ofEpochMilli(Math.floorDiv(t.toEpochMilli(), MILLIS_PER_HOUR) * MILLIS_PER_HOUR)

    /** The hours holding a writable [pending] heart-rate sample at [now], oldest first, each once. */
    fun hoursToRebuild(pending: List<QuantitySample>, now: Instant): List<Instant> =
        pending.filter { writable(it, now) }.map { hourStart(it.start) }.distinct().sorted()

    /**
     * The record of the UTC hour starting at [hourStart], from the [stored] heart-rate samples in
     * `[hourStart, hourStart + 1 h)` that are above zero and not after [now]; null when there is
     * none. It ends at the hour's end, or at [now] while the hour is still running. Beats per
     * minute are rounded to the nearest whole beat (Health Connect keeps whole beats). The zone
     * offsets are [zone]'s at each end.
     */
    fun hour(
        hourStart: Instant,
        stored: List<QuantitySample>,
        now: Instant,
        zone: ZoneId,
        device: RingDevice,
        version: Long,
    ): Hour? {
        require(hourStart(hourStart) == hourStart && hourStart.nano % 1_000_000 == 0) { "$hourStart is not the start of a UTC hour" }
        val hourEnd = hourStart.plusMillis(MILLIS_PER_HOUR)
        val inHour = stored
            .filter { writable(it, now) && !it.start.isBefore(hourStart) && it.start.isBefore(hourEnd) }
            .sortedBy { it.start }
        if (inHour.isEmpty()) return null
        val end = if (now.isBefore(hourEnd)) now else hourEnd
        val record = HeartRateSeries(
            start = hourStart,
            startZoneOffset = zone.rules.getOffset(hourStart),
            end = end,
            endZoneOffset = zone.rules.getOffset(end),
            samples = inHour.map { HeartRateSample(it.start, it.value.roundToLong()) },
            meta = RecordMeta(RecordIds.heartRateHour(hourStart), version, RecordingMethod.AUTOMATICALLY_RECORDED, device),
        )
        return Hour(record, inHour)
    }

    private fun writable(s: QuantitySample, now: Instant): Boolean =
        s.kind == MetricKind.HEART_RATE && s.value > 0 && !s.start.isAfter(now)
}
