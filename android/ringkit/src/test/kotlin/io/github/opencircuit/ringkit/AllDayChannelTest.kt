package io.github.opencircuit.ringkit

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Port of upstream AllDayChannelTests.swift (@ b1c2fdd) — the guard rail for the all-day (`0x03`)
 * drain: its daytime epochs must reach the health store as SpO2/HR SAMPLES, but must NOT pollute
 * sleep staging — a periodic daytime SpO2 reading (which decodes with the sleep-vitals layout) can
 * never become "sleep". The overnight scope gate in `latestNightRecords` keeps the two apart.
 */
class AllDayChannelTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val base: Instant = Instant.ofEpochSecond(1_780_000_000) // ~2026, comfortably after the sync epoch

    /** One 23-byte record. [spo2Byte] at `[8]`: an SpO2 value makes it a sleep-vitals epoch; `0x12` an activity epoch. */
    private fun record(date: Instant, hr: Int, spo2Byte: Int, still: Boolean): BulkRecord {
        val counter = date.epochSecond - Command.SYNC_EPOCH
        val b = ByteArray(BulkRecord.LENGTH)
        b[0] = (counter shr 24).toByte(); b[1] = (counter shr 16).toByte()
        b[2] = (counter shr 8).toByte(); b[3] = counter.toByte()
        b[4] = hr.toByte()
        b[8] = spo2Byte.toByte()
        if (still) {
            for (k in 10 until 15) b[k] = 1 // still baseline
        } else {
            b[10] = 8; b[11] = 12; b[12] = 20; b[13] = 6; b[14] = 10 // elevated motion (moving)
        }
        return BulkRecord.of(b)!!
    }

    /** Local midnight + `hour:min` on the day containing [base]. */
    private fun at(hour: Int, min: Int = 0): Instant =
        base.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().plusSeconds((hour * 60L + min) * 60)

    @Test
    fun testDaytimeAllDaySpo2ReachesSamplesButNotSleep() {
        val union = mutableListOf<BulkRecord>()
        // Channel 0x00 — a real overnight night: ~4 h of still sleep-vitals epochs, 01:00–05:00 local.
        val sleepStart = at(1)
        for (i in 0 until 96) union += record(sleepStart.plusSeconds(i * 150L), hr = 55, spo2Byte = 0x62, still = true)
        // Channel 0x03 — awake/all-day: 14:00–15:00 activity HR (moving), with a periodic SpO2=98
        // reading every ~10 min (every 4th 2.5-min epoch), exactly as the captures show.
        val dayStart = at(14)
        for (i in 0 until 24) {
            val isSpo2Epoch = i % 4 == 0
            union += record(dayStart.plusSeconds(i * 150L), hr = 75, spo2Byte = if (isSpo2Epoch) 0x62 else 0x12, still = isSpo2Epoch)
        }

        // 1) The daytime SpO2 surfaces as SpO2 samples → it will reach the health store.
        val daySpo2 = BulkSleep.samples(union).filter { it.kind == MetricKind.SPO2 && !it.start.isBefore(dayStart) }
        assertEquals(6, daySpo2.size, "one daytime SpO2 reading per ~10 min reaches samples")
        assertTrue(daySpo2.all { it.value > 0.95 && it.value <= 1.0 }, "daytime SpO2 value decodes to ~98 %")

        // 2) Sleep staging scopes to the OVERNIGHT night only — the daytime epochs are excluded.
        val nightRecords = BulkSleep.latestNightRecords(union, zone = zone)
        assertFalse(nightRecords.isEmpty())
        assertTrue(nightRecords.all { it.date().isBefore(at(12)) }, "daytime channel-0x03 epochs must not enter the staged night")
        val segs = BulkSleep.sleepSegments(nightRecords)
        assertFalse(segs.isEmpty(), "the overnight night is still detected")
        assertTrue(segs.all { !it.end.isAfter(at(12)) }, "no sleep segment lands in the daytime window")
    }
}
