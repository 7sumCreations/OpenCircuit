package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 10-bit big-endian unpacking of a `0x47` record payload into the optical trend samples
 * (../docs/PROTOCOL.md §5.2). Hand-built vectors; the bit-width proof itself lives upstream in the
 * desktop analysis scripts against real captures.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/PPGTrendTests.swift (@ b1c2fdd),
 * all 5 tests.
 */
class PPGTrendTest {

    // :9
    @Test
    fun allZeroPayloadDecodesToZeroSamples() {
        val samples = PPGTrend.samples(ByteArray(38))
        assertEquals(PPGTrend.EXPECTED_SAMPLES_PER_RECORD, samples.size)
        assertTrue(samples.all { it == 0 })
    }

    // :16 — first 10 bits of ff c0 are all 1 → 1023, the max 10-bit value.
    @Test
    fun knownFirstSample() {
        val b = ByteArray(38)
        b[0] = 0xFF.toByte()
        b[1] = 0xC0.toByte()
        assertEquals(1023, PPGTrend.samples(b).first())
    }

    // :26 — 00000001 00000000 10000000: sample0 = 4, sample1 = 8; samples straddle bytes.
    @Test
    fun consecutiveSamplesAreNotByteAligned() {
        val b = ByteArray(38)
        b[0] = 0b00000001
        b[1] = 0b00000000
        b[2] = 0b10000000.toByte()
        val samples = PPGTrend.samples(b)
        assertEquals(4, samples[0])
        assertEquals(8, samples[1])
    }

    // :41 — 38 bytes = 304 bits → 30 full samples, 4 bits dropped.
    @Test
    fun recordCountFromRealisticPayloadSize() {
        val payload = ByteArray(38) { (it * 7 % 256).toByte() }
        assertEquals(30, PPGTrend.samples(payload).size)
    }

    // :47
    @Test
    fun samplesFromRecordsPairsTimestamp() {
        val t = Instant.ofEpochSecond(1_700_000_000L)
        val record = EpochRecord.PPGRecord(timestamp = t, rawPayload = ByteArray(38))
        val paired = PPGTrend.samples(listOf(record))
        assertEquals(1, paired.size)
        assertEquals(t, paired[0].timestamp)
        assertEquals(30, paired[0].samples.size)
    }
}
