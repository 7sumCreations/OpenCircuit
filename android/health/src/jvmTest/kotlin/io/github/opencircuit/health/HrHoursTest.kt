package io.github.opencircuit.health

import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Heart rate goes to Health Connect as one series record per UTC clock hour, rebuilt in full from
 * what the store holds each time any sample of that hour is pending, so a late sample replaces its
 * hour's record (same id, higher version) instead of adding a second one. Fixtures are plain stored
 * samples typed out here; each record built is also checked against the platform's rules.
 */
class HrHoursTest {

    private val h8 = Instant.parse("2026-10-16T08:00:00Z") // 1792137600000 ms
    private val now = Instant.parse("2026-10-16T12:00:00Z")
    private val ring = RingDevice("Gen 2")
    private val utc = ZoneOffset.UTC

    private fun hr(at: Instant, bpm: Double) = QuantitySample(MetricKind.HEART_RATE, at, value = bpm)

    private fun min(m: Long): Instant = h8.plusSeconds(m * 60)

    private fun built(hour: HrHours.Hour?): HrHours.Hour = assertNotNull(hour, "an hour record was expected")

    @Test
    fun nothingPendingRebuildsNoHour() {
        assertEquals(emptyList(), HrHours.hoursToRebuild(emptyList(), now))
    }

    @Test
    fun eachPendingSampleNamesItsUtcHourOnceOldestFirst() {
        val pending = listOf(hr(min(130), 70.0), hr(min(5), 60.0), hr(min(59), 61.0), hr(min(60), 62.0))

        assertEquals(listOf(h8, min(60), min(120)), HrHours.hoursToRebuild(pending, now))
    }

    @Test
    fun anHourIsRebuiltFromEveryStoredSampleInItNotOnlyThePendingOnes() {
        // 08:10 was written by an earlier flush; 08:40 arrived later and is the only pending one.
        val stored = listOf(hr(min(10), 61.0), hr(min(40), 66.0))

        val hour = built(HrHours.hour(h8, stored, now, utc, ring, version = 7))

        assertEquals(listOf(HeartRateSample(min(10), 61), HeartRateSample(min(40), 66)), hour.record.samples)
        assertEquals(stored, hour.samples)
        assertEquals(h8, hour.record.start)
        assertEquals(min(60), hour.record.end)
        assertEquals(RecordMeta("oc:hr:1792137600000", 7, RecordingMethod.AUTOMATICALLY_RECORDED, ring), hour.record.meta)
        assertNull(HealthRanges.violation(hour.record, now))
    }

    @Test
    fun aSampleOnTheNextHourBelongsToTheNextHourAndOtherKindsAndZerosAreLeftOut() {
        val stored = listOf(
            hr(min(0), 60.0),
            hr(min(60), 90.0),
            QuantitySample(MetricKind.SPO2, min(30), value = 0.97),
            hr(min(31), 0.0),
            hr(min(59).plusSeconds(59), 70.0),
        )

        val hour = built(HrHours.hour(h8, stored, now, utc, ring, version = 1))

        assertEquals(listOf(min(0), min(59).plusSeconds(59)), hour.record.samples.map { it.time })
        assertEquals(listOf(min(0), min(59).plusSeconds(59)), hour.samples.map { it.start })
    }

    @Test
    fun theHourOfNowEndsNowAndAFutureSampleWaitsUntilItIsPast() {
        val nowInHour = min(25)
        val stored = listOf(hr(min(10), 60.0), hr(min(25), 61.0), hr(min(26), 62.0))

        // A stored sample up to a day ahead passes ingest; Health Connect refuses one after its record's end.
        assertEquals(listOf(h8), HrHours.hoursToRebuild(stored, nowInHour))
        assertEquals(emptyList(), HrHours.hoursToRebuild(listOf(hr(min(26), 62.0), hr(min(90), 63.0)), nowInHour))
        val hour = built(HrHours.hour(h8, stored, nowInHour, utc, ring, version = 1))
        assertEquals(nowInHour, hour.record.end)
        assertEquals(listOf(min(10), min(25)), hour.samples.map { it.start }, "the future sample is not written, so it is not marked")
        assertNull(HealthRanges.violation(hour.record, nowInHour))
    }

    @Test
    fun anHourWithNothingWritableBuildsNoRecord() {
        assertNull(HrHours.hour(h8, emptyList(), now, utc, ring, version = 1))
        assertNull(HrHours.hour(h8, listOf(hr(min(10), 0.0)), now, utc, ring, version = 1))
        assertNull(HrHours.hour(h8, listOf(hr(min(10), 60.0)), min(5), utc, ring, version = 1))
    }

    @Test
    fun beatsPerMinuteAreRoundedToTheNearestWholeBeat() {
        val stored = listOf(hr(min(1), 72.4), hr(min(2), 72.5), hr(min(3), 72.6))

        val hour = built(HrHours.hour(h8, stored, now, utc, ring, version = 1))

        assertEquals(listOf(72L, 73L, 73L), hour.record.samples.map { it.beatsPerMinute })
    }

    @Test
    fun theOffsetsAreTheZonesAtEachEndOfTheHour() {
        // Berlin leaves summer time at 01:00 UTC on 2026-10-25: the hour 00:00-01:00 UTC starts at +2 and ends at +1.
        val berlin = ZoneId.of("Europe/Berlin")
        val start = Instant.parse("2026-10-25T00:00:00Z")
        val later = Instant.parse("2026-10-26T00:00:00Z")

        val hour = built(HrHours.hour(start, listOf(hr(start.plusSeconds(600), 58.0)), later, berlin, ring, version = 1))

        assertEquals(ZoneOffset.ofHours(2), hour.record.startZoneOffset)
        assertEquals(ZoneOffset.ofHours(1), hour.record.endZoneOffset)
    }

    @Test
    fun anHourStartIsTheStartOfAUtcHour() {
        assertEquals(h8, HrHours.hourStart(min(59).plusMillis(999)))
        assertEquals(min(60), HrHours.hourStart(min(60)))
        // Before 1970 the hour still rounds down, not toward zero.
        assertEquals(Instant.parse("1969-12-31T23:00:00Z"), HrHours.hourStart(Instant.parse("1969-12-31T23:30:00Z")))
        assertFailsWith<IllegalArgumentException> { HrHours.hour(min(1), listOf(hr(min(2), 60.0)), now, utc, ring, version = 1) }
    }
}
