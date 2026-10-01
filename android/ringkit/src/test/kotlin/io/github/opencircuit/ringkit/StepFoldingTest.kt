package io.github.opencircuit.ringkit

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Kotlin-only, end to end: real descriptor bytes → `DeviceStatus.steps` → `StepAccumulator.update`
 * → `StepAccumulator.windowStart` → per-day step samples, run by the same thin fold a connection
 * session performs (keep the last raw counter, its time and its local day).
 *
 * Fixture: upstream's real worn/streaming descriptor (`104202…`, the 2026-06-19 capture that
 * `DeviceStatusTest` also uses) with its `[4:6]` step field overwritten byte by byte with upstream's
 * verbatim 2026-08-08 16:54 → 17:29 New York bucket series, then a late-evening bucket and the first
 * quarter of the next day. Every frame is delivered TWICE (a duplicate notify two seconds later), and
 * the fold crosses local midnight.
 */
class StepFoldingTest {

    private val zone = ZoneId.of("America/New_York")

    private fun at(date: String, hhmmss: String): Instant = LocalDateTime.parse("${date}T$hhmmss").atZone(zone).toInstant()

    /** The real worn descriptor with `[4:6]` set to [steps], big-endian — built on the raw byte path. */
    private fun descriptor(steps: Int): ByteArray =
        hex("1042020000000140013e000000000fa100ff00").also {
            it[4] = (steps ushr 8).toByte()
            it[5] = (steps and 0xff).toByte()
        }

    /** Upstream's verbatim `[4:6]` series (`T/StepAccumulatorTests.swift:106-110`). */
    private val realSeries0808 = listOf(
        11, 18, 18, 18, 37, 58,
        63, 70, 70, 70, 70, 70, 78, 78, 78, 98, 98, 98, 98, 98, 98,
        0, 0, 0, 0, 0, 9, 9, 9, 9, 9, 9, 9,
    )

    /** (arrival, frame) — the real series ~65 s apart from 16:54:09, then 23:52, 23:58:30 and past midnight. */
    private val deliveries: List<Pair<Instant, ByteArray>> =
        realSeries0808.mapIndexed { i, v -> at("2026-08-08", "16:54:09").plusSeconds(65L * i) to descriptor(v) } +
            listOf(
                at("2026-08-08", "23:52:00") to descriptor(5),  // after a 6 h gap: a new 23:45 bucket
                at("2026-08-08", "23:58:30") to descriptor(75), // climbing
                at("2026-08-09", "00:01:10") to descriptor(12), // next day, inside the clear lag of 00:00
                at("2026-08-09", "00:10:00") to descriptor(30), // climbing
            )

    private data class Sample(val day: LocalDate, val start: Instant, val end: Instant, val steps: Int)

    /** The thin session fold: the persisted baseline is (raw, arrival, local day). */
    private fun fold(stream: List<Pair<Instant, ByteArray>>): List<Sample> {
        var previousRaw: Int? = null
        var previousAt: Instant? = null
        var previousDay: LocalDate? = null
        val out = mutableListOf<Sample>()
        for ((t, frame) in stream) {
            val raw = checkNotNull(DeviceStatus.steps(frame)) { "fixture is not a descriptor" }
            val day = t.atZone(zone).toLocalDate()
            val dayChanged = previousDay != null && day != previousDay
            val u = StepAccumulator.update(previousRaw, raw, dayChanged)
            val start = StepAccumulator.windowStart(
                sampleDate = t,
                previousSampleAt = if (dayChanged) null else previousAt,
                dayStart = day.atStartOfDay(zone).toInstant(),
                zone = zone,
            )
            if (u.deltaToAdd > 0) out += Sample(day, start, t, u.deltaToAdd)
            previousRaw = raw
            previousAt = t
            previousDay = day
        }
        return out
    }

    private val withReDelivery: List<Pair<Instant, ByteArray>> =
        deliveries.flatMap { (t, frame) -> listOf(t to frame, t.plusSeconds(2) to frame.copyOf()) }

    @Test
    fun aReDeliveredDescriptorIsNeverCountedTwiceAcrossBucketsAndADayChange() {
        val once = fold(deliveries)
        val twice = fold(withReDelivery)

        val perDay = twice.groupBy { it.day }.mapValues { (_, s) -> s.sumOf { it.steps } }
        // 2026-08-08: upstream's series folds to 107 (98 + 9), then the 23:45 bucket reaches 75.
        // 2026-08-09: the day's first bucket is credited whole (12), then climbs to 30.
        assertEquals(mapOf(LocalDate.parse("2026-08-08") to 107 + 75, LocalDate.parse("2026-08-09") to 30), perDay)
        assertEquals(once.map { it.steps }, twice.map { it.steps }, "the duplicates added a sample")
        assertEquals(once.size, twice.size)
    }

    @Test
    fun noSampleIsSmearedPastItsBucketOrAcrossMidnight() {
        val samples = fold(withReDelivery)
        for (s in samples) {
            val dayStart = s.day.atStartOfDay(zone).toInstant()
            assertTrue(s.start >= dayStart, "$s starts before its own day")
            assertTrue(s.start <= s.end, "$s is inverted")
            val earliest = StepAccumulator.bucketStart(s.end.minus(StepAccumulator.CLEAR_LAG_ALLOWANCE), zone)
            assertTrue(s.start >= earliest, "$s starts before its own bucket (minus the clear lag)")
        }
        // The first reading after the 6 h gap opens at its own bucket, not at 17:29 …
        val afterGap = samples.single { it.end == at("2026-08-08", "23:52:00") }
        assertEquals(at("2026-08-08", "23:45:00"), afterGap.start)
        // … and the day's first reading, 70 s after midnight where the clear lag reaches into
        // yesterday's 23:45 bucket, clamps to local midnight.
        val firstOfDay = samples.single { it.end == at("2026-08-09", "00:01:10") }
        assertEquals(at("2026-08-09", "00:00:00"), firstOfDay.start)
        assertEquals(12, firstOfDay.steps)
    }
}
