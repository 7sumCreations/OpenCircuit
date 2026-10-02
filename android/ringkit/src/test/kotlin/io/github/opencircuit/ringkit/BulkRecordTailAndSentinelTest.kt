package io.github.opencircuit.ringkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two `0x4c` record facts settled from the wire in ../docs/PROTOCOL.md §5.3:
 *  • `[8] == 0x11` is a THIRD "no SpO2 here" sentinel (the ring's "nothing measured" block
 *    terminator), while `0x00`/`0x0a`/`0x0e` are NOT and keep falling through to sleep-vitals;
 *  • `[15:23)` is five 12-bit big-endian magnitudes, nibble-packed, plus a 4-bit `info` flag.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/BulkRecordTailAndSentinelTests.swift
 * (@ b1c2fdd) — all 10 tests. The SpO2-cadence test (`:132`) exercises sleep staging and arrived
 * with it.
 *
 * Known-answer tests over hand-built bytes: the nibbles are chosen so that field order, nibble
 * order, field width and the flag's position are each pinned by a value no other packing produces.
 */
class BulkRecordTailAndSentinelTest {

    /**
     * :17-31 — a 23-byte record with a controllable head and `[15:23)` block. `[0:4]` counter,
     * `[4:8]` HR/HRV/conf/RR, `[8]` the SpO2-or-sentinel byte, `[9]` = 0x0a, `[10:15]` motion,
     * `[15:23)` the tail block under test.
     */
    private fun record(
        counter: Long = 0x0c22a16bL,
        head: ByteArray = bytes(60, 50, 9, 120),
        spo2Byte: Int,
        motion: ByteArray = bytes(1, 1, 1, 1, 1),
        tail: ByteArray,
    ): BulkRecord {
        require(head.size == 4 && motion.size == 5 && tail.size == 8)
        val c = bytes(
            ((counter ushr 24) and 0xff).toInt(), ((counter ushr 16) and 0xff).toInt(),
            ((counter ushr 8) and 0xff).toInt(), (counter and 0xff).toInt(),
        )
        return assertNotNull(BulkRecord.of(c + head + bytes(spo2Byte, 0x0a) + motion + tail))
    }

    // [15:23) — five 12-bit big-endian magnitudes + a 4-bit info flag

    @Test
    fun activityMagnitudesAreFiveTwelveBitBigEndianFieldsWithTheFlagLast() { // :36-43
        // Nibbles 1 2 3 | 4 5 6 | 7 8 9 | A B C | D E F | 5  →  bytes 12 34 56 78 9A BC DE F5.
        val r = record(spo2Byte = 97, tail = bytes(0x12, 0x34, 0x56, 0x78, 0x9A, 0xBC, 0xDE, 0xF5))
        assertEquals(listOf(0x123, 0x456, 0x789, 0xABC, 0xDEF), r.activityMagnitudes)
        assertEquals(0x5, r.activityInfoNibble, "info is the LOW nibble of [22], not the high one")
    }

    @Test
    fun activityMagnitudesAreTwelveBitsWideNotSixteen() { // :45-51
        // All-ones tail: a 12-bit field saturates at 4095. A 16-bit read would give 65535, a byte 255.
        val r = record(spo2Byte = 97, tail = ByteArray(8) { 0xFF.toByte() })
        assertEquals(listOf(4095, 4095, 4095, 4095, 4095), r.activityMagnitudes)
        assertEquals(0xF, r.activityInfoNibble)
    }

    @Test
    fun theFlagNibbleIsNotStolenFromTheLastMagnitude() { // :53-58
        // Only [22] is set, to 0xA5. The high nibble belongs to magnitude 4, the low one is `info`.
        val r = record(spo2Byte = 97, tail = bytes(0, 0, 0, 0, 0, 0, 0, 0xA5))
        assertEquals(listOf(0, 0, 0, 0, 0x00A), r.activityMagnitudes)
        assertEquals(0x5, r.activityInfoNibble)
    }

    @Test
    fun activityMagnitudesSeeMovementTheByteAlignedTailPredicateCannot() { // :60-68
        // `[15:20]` covers magnitudes 0–2 and only the top nibble of magnitude 3, so movement in
        // magnitudes 3/4 (bytes 19-low…22-high) is invisible to the legacy predicate.
        val r = record(spo2Byte = 97, tail = bytes(0, 0, 0, 0, 0, 0x0F, 0xF0, 0x00))
        assertTrue(r.motionIntensityTailIsZero, "[15:20] really is all zero here")
        assertEquals(listOf(0, 0, 0, 0x00F, 0xF00), r.activityMagnitudes)
        assertFalse(r.activityMagnitudesAreZero, "the correct decode sees magnitudes 3 and 4")
    }

    @Test
    fun legacyTailPredicateIsNotRedefined() { // :70-80
        // Three shipped calibrations are fitted to the byte-aligned population, so the old
        // predicate must keep its exact `[15:20]` window even where the correct decode disagrees.
        val quietUnderBoth = record(spo2Byte = 97, tail = bytes(0, 0, 0, 0, 0, 0, 0, 0x04))
        assertTrue(quietUnderBoth.motionIntensityTailIsZero)
        assertTrue(quietUnderBoth.activityMagnitudesAreZero, "the info nibble is not a magnitude")

        val movingInsideTheWindow = record(spo2Byte = 97, tail = bytes(0, 0, 0x10, 0, 0, 0, 0, 0))
        assertFalse(movingInsideTheWindow.motionIntensityTailIsZero)
        assertFalse(movingInsideTheWindow.activityMagnitudesAreZero)
    }

    // [8] == 0x11, the third "no SpO2 here" sentinel

    @Test
    fun zero11IsASentinelNotAnSpO2Reading() { // :84-94
        // The corpus shape: [4] == 0x04 (the ring's "<30 = PR unmeasured"), the dead head, and a
        // motion byte ≥ 0x80.
        val r = record(
            head = bytes(0x04, 0, 0, 0), spo2Byte = 0x11,
            motion = bytes(0x8a, 0x8a, 0x8a, 0x8a, 0x8a),
            tail = bytes(0, 0, 0, 0, 0, 0, 0, 0x04),
        )
        assertEquals(BulkRecord.Layout.ACTIVITY, r.layout, "0x11 is a sentinel, not a 17 % saturation")
        assertNull(r.spo2Percent)
        assertNull(r.heartRate, "[4] == 0x04 is below LiveHR.VALID_BPM")
    }

    @Test
    fun zero11DoesNotEmitSleepVitalsHRVOrRespiratoryRate() { // :96-105
        // The one corpus 0x11 record that carries non-zero [5]/[7].
        val r = record(
            head = bytes(0x04, 0x2f, 0x03, 0x7d), spo2Byte = 0x11,
            motion = bytes(0x31, 0x2c, 0x9c, 0xa1, 0x19),
            tail = bytes(0x6b, 0xa2, 0x2c, 0x00, 0x01, 0xdf, 0x0f, 0x64),
        )
        assertEquals(BulkRecord.Layout.ACTIVITY, r.layout)
        assertNull(r.hrvRMSSD, "strict sleep-vitals HRV must not come off a sentinel epoch")
        assertNull(r.respiratoryRate)
    }

    @Test
    fun otherImpossibleSpO2BytesStaySleepVitals() { // :107-120
        // These are sleep-vitals epochs whose SpO2 byte failed — the case the structural layout
        // fall-through exists for.
        for (sentinelCandidate in listOf(0x00, 0x0a, 0x0e)) {
            val r = record(
                head = bytes(91, 27, 9, 120), spo2Byte = sentinelCandidate,
                motion = bytes(0x14, 0x14, 0x14, 0x14, 0x14),
                tail = bytes(0, 0, 0, 0, 0, 0, 0, 0x04),
            )
            val label = "0x%02x".format(sentinelCandidate)
            assertEquals(BulkRecord.Layout.SLEEP_VITALS, r.layout, "$label is NOT a sentinel")
            assertEquals(91, r.heartRate, label)
            assertEquals(27, r.hrvRMSSD, label)
            assertNull(r.spo2Percent, "$label: the impossible value is still range-guarded away")
        }
    }

    @Test
    fun genuineDesaturationBelow87PercentSurvives() { // :122-128
        // 0x50 = 80 % is a real, clinically interesting reading, not a tag.
        val r = record(head = bytes(58, 61, 9, 121), spo2Byte = 0x50, tail = bytes(0, 0, 0, 0, 0, 0, 0, 0x04))
        assertEquals(BulkRecord.Layout.SLEEP_VITALS, r.layout)
        assertEquals(80, r.spo2Percent)
        assertEquals(61, r.hrvRMSSD)
    }

    // Interaction with the SpO2-cadence wake locator

    @Test
    fun zero11BreaksTheSpO2CadenceInsteadOfExtendingIt() { // :132-147
        // The ring alternates sleepVitals/activity 1:1 while it measures sleep. A 0x11 record read as
        // sleep-vitals looks like the next SpO2 read and EXTENDS the trusted run past the point the
        // ring actually stopped; read as the sentinel it is, two consecutive no-SpO2 epochs are the
        // violation they really are. Every 0x11 in upstream's corpus follows an ACTIVITY epoch.
        val t0 = java.time.Instant.ofEpochSecond(1_700_000_000L)
        val times = (0 until 5).map { t0.plusSeconds(it * BulkRecord.EPOCH_SECONDS.toLong()) }
        val dead = record(
            head = bytes(0x04, 0, 0, 0), spo2Byte = 0x11,
            motion = bytes(0x8a, 0x8a, 0x8a, 0x8a, 0x8a), tail = bytes(0, 0, 0, 0, 0, 0, 0, 0x04),
        )
        val layouts = listOf(
            BulkRecord.Layout.SLEEP_VITALS, BulkRecord.Layout.ACTIVITY, BulkRecord.Layout.SLEEP_VITALS,
            BulkRecord.Layout.ACTIVITY, dead.layout,
        )
        val steps = SleepStaging.cadenceSteps(times, layouts)
        assertEquals(
            List(3) { SleepStaging.CadenceStep.ALTERNATING }, steps.drop(1).take(3),
        )
        assertEquals(SleepStaging.CadenceStep.VIOLATION, steps[4], "activity → 0x11 is two no-SpO2 epochs in a row")
    }
}
