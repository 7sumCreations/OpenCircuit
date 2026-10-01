package io.github.opencircuit.ringkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for `DeviceStatus` — the 0x10/0x87 descriptor decode and the proxy wear/charging API
 * (upstream #41, #60, #61, #89).
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/DeviceStatusTests.swift (@ b1c2fdd):
 * all 33 tests. E1 ported the 30 descriptor tests (:34-214); E3 added the 3 that run the sleep wear
 * gate through `BulkRecord` / `BulkSleep` (:217-285, with their `rec(_:motion:sub:)` helper; this
 * closes PORTING.md D-5). `steps` / `battery` / `skinTemperature` are checked by the
 * `RingKitVerify` port (E1 slice 5), as upstream does.
 *
 * Key design rule: isWorn/isCharging are CONSERVATIVE.
 *   • isWorn miss (ring cold, actually worn) → at most one unfiltered charger block in sleep
 *     detection. Never adds spurious sleep.
 *   • isCharging false-positive → at most a "likely charging" hint in the UI. Never drops a night.
 *
 * Fixtures are built on the RAW byte path; `chargingFrame` / `wornFrame` are
 * REAL frames, and they carry bytes ≥ 0x80 (0xf7, 0xa1, 0xff) that a signed read would corrupt.
 */
class DeviceStatusTest {

    // Helpers — DeviceStatusTests.swift:21-30

    /**
     * A minimal 19-byte 0x10 descriptor frame with the given temperature channels.
     * Channels are two 16-bit big-endian values at [6:8] and [8:10] in units of 0.1 °C.
     */
    private fun descriptorFrame(tempA: Int, tempB: Int, opcode: Int = 0x10): ByteArray {
        val frame = ByteArray(19)
        frame[0] = opcode.toByte()
        frame[6] = (tempA shr 8).toByte(); frame[7] = (tempA and 0xFF).toByte()
        frame[8] = (tempB shr 8).toByte(); frame[9] = (tempB and 0xFF).toByte()
        return frame
    }

    /** Temperature integer for a given Celsius value (0.1 °C units); truncates like Swift's `Int(_:)`. */
    private fun tempInt(celsius: Double): Int = (celsius * 10).toInt()

    // isWorn: non-descriptor frames

    @Test
    fun isWornNonDescriptorFrameReturnsNull() { // :34-39
        // A frame that isn't 0x10 or 0x87 → no temperature → null
        val frame = descriptorFrame(tempA = tempInt(32.0), tempB = tempInt(32.0))
        frame[0] = 0x4C // sleep-page opcode, not a descriptor
        assertNull(DeviceStatus.isWorn(frame))
    }

    @Test
    fun isWornTooShortFrameReturnsNull() { // :41-43
        assertNull(DeviceStatus.isWorn(bytes(0x10, 0x50))) // too short
    }

    @Test
    fun isWornGarbageTempReturnsNull() { // :45-49
        // Both channels out of the 15–50 °C plausible band → skinTemperature returns null
        val frame = descriptorFrame(tempA = 0, tempB = 0)
        assertNull(DeviceStatus.isWorn(frame))
    }

    // isWorn: warm (worn) readings

    @Test
    fun isWornReturnsTrueAt32C() { // :53-57
        // Typical worn-ring temp: both channels at 32.0 °C
        val frame = descriptorFrame(tempA = tempInt(32.0), tempB = tempInt(32.0))
        assertEquals(true, DeviceStatus.isWorn(frame))
    }

    @Test
    fun isWornReturnsTrueAtThresholdExactly() { // :59-64
        // Exactly at the default threshold (28 °C) → worn
        val t = tempInt(ActivityPeriod.WORN_MIN_TEMPERATURE_C)
        val frame = descriptorFrame(tempA = t, tempB = t)
        assertEquals(true, DeviceStatus.isWorn(frame))
    }

    @Test
    fun isWornReturnsTrueWithMixedChannels() { // :66-70
        // Mean of 30 + 32 = 31 °C → worn
        val frame = descriptorFrame(tempA = tempInt(30.0), tempB = tempInt(32.0))
        assertEquals(true, DeviceStatus.isWorn(frame))
    }

    // isWorn: cold (unworn/charging) readings

    @Test
    fun isWornReturnsFalseAt22C() { // :74-78
        // Typical room-ambient off-wrist temp
        val frame = descriptorFrame(tempA = tempInt(22.0), tempB = tempInt(22.0))
        assertEquals(false, DeviceStatus.isWorn(frame))
    }

    @Test
    fun isWornReturnsFalseJustBelowThreshold() { // :80-85
        // Just below threshold: threshold - 0.1 °C
        val tInt = tempInt(ActivityPeriod.WORN_MIN_TEMPERATURE_C) - 1
        val frame = descriptorFrame(tempA = tInt, tempB = tInt)
        assertEquals(false, DeviceStatus.isWorn(frame))
    }

    @Test
    fun isWornWorksFor0x87Opcode() { // :87-91
        // 0x87 is the response variant of the same descriptor
        val frame = descriptorFrame(tempA = tempInt(33.0), tempB = tempInt(33.0), opcode = 0x87)
        assertEquals(true, DeviceStatus.isWorn(frame))
    }

    @Test
    fun isWornCustomThreshold() { // :93-99
        // Override default threshold to 30 °C: a 29 °C reading is unworn under this stricter gate
        val frame = descriptorFrame(tempA = tempInt(29.0), tempB = tempInt(29.0))
        assertEquals(false, DeviceStatus.isWorn(frame, wornMinC = 30.0))
        // … but worn under the default 28 °C threshold
        assertEquals(true, DeviceStatus.isWorn(frame))
    }

    // isCharging: delegates to ChargingInference

    @Test
    fun isChargingReturnsFalseForEmptyTrend() { // :103-105
        assertFalse(DeviceStatus.isCharging(batteryTrend = emptyList()))
    }

    @Test
    fun isChargingReturnsFalseForSingleReading() { // :107-109
        assertFalse(DeviceStatus.isCharging(batteryTrend = listOf(75)))
    }

    @Test
    fun isChargingReturnsTrueForRisingTrend() { // :111-113
        assertTrue(DeviceStatus.isCharging(batteryTrend = listOf(74, 76, 78)))
    }

    @Test
    fun isChargingReturnsFalseForFlatTrend() { // :115-117
        assertFalse(DeviceStatus.isCharging(batteryTrend = listOf(75, 75)))
    }

    @Test
    fun isChargingReturnsFalseForFallingTrend() { // :119-121
        assertFalse(DeviceStatus.isCharging(batteryTrend = listOf(80, 78)))
    }

    @Test
    fun isChargingReturnsFalseForMixedTrend() { // :123-125
        assertFalse(DeviceStatus.isCharging(batteryTrend = listOf(74, 76, 75)))
    }

    // isOnCharger / batteryVoltageMillivolts (DECODED byte, #61 / #89)
    //
    // Fixtures are REAL frames from the 2026-06-19 labelled A/B capture
    // (captures/charger66b, finger → charger → off → finger). DeviceStatusTests.swift:132-139.
    // Built fresh per access: the tests mutate copies, as Swift's value-type arrays did.

    /** A real ON-CHARGER frame mid-charge: [2]=04, [17]=46, voltage [14:15]=10 f7 (4343 mV). */
    private val chargingFrame: ByteArray
        get() = hex("104704000000010c01060000000010f7024600")

    /** A real WORN/streaming frame: [2]=02, [17]=ff, voltage [14:15]=0f a1 (4001 mV). */
    private val wornFrame: ByteArray
        get() = hex("1042020000000140013e000000000fa100ff00")

    @Test
    fun isOnChargerTrueForChargingFrame() { // :141-143
        assertEquals(true, DeviceStatus.isOnCharger(chargingFrame))
    }

    @Test
    fun isOnChargerFalseForWornFrame() { // :145-147
        assertEquals(false, DeviceStatus.isOnCharger(wornFrame))
    }

    @Test
    fun isOnChargerFalseForStartupStateByte() { // :149-153
        // [2]=0x01 is the startup/settle transient, not charging.
        val f = wornFrame; f[2] = 0x01
        assertEquals(false, DeviceStatus.isOnCharger(f))
    }

    @Test
    fun isOnChargerWorksFor0x87() { // :155-158
        val f = chargingFrame; f[0] = 0x87.toByte()
        assertEquals(true, DeviceStatus.isOnCharger(f))
    }

    @Test
    fun isOnChargerNullForNonDescriptor() { // :160-164
        val f = chargingFrame; f[0] = 0x4C
        assertNull(DeviceStatus.isOnCharger(f))
        assertNull(DeviceStatus.isOnCharger(bytes(0x10, 0x04))) // too short
    }

    @Test
    fun batteryVoltageDecodesChargingPeak() { // :166-168
        assertEquals(4343, DeviceStatus.batteryVoltageMillivolts(chargingFrame))
    }

    @Test
    fun batteryVoltageDecodesWornBaseline() { // :170-172
        assertEquals(4001, DeviceStatus.batteryVoltageMillivolts(wornFrame))
    }

    @Test
    fun batteryVoltageNullForImplausibleAndNonDescriptor() { // :174-179
        val zero = wornFrame; zero[14] = 0; zero[15] = 0 // 0 mV → out of band
        assertNull(DeviceStatus.batteryVoltageMillivolts(zero))
        val notDesc = wornFrame; notDesc[0] = 0x4C
        assertNull(DeviceStatus.batteryVoltageMillivolts(notDesc))
    }

    // caseBattery ([17] = chargingCasePower | charging bit, #89)
    //
    // Fixtures are the real [17] values from the 2026-06-19 in-case capture (case89):
    // 0x46→70%, 0xc6→70%+charging, 0xda→90%+charging, 0xff→ring not docked.

    private fun frame(case17: Int): ByteArray { // :186
        val f = wornFrame; f[17] = bytes(case17)[0]; return f
    }

    @Test
    fun caseBatteryNullWhenNotDocked() { // :188-190
        assertNull(DeviceStatus.caseBattery(frame(case17 = 0xff))) // 0xff = not in case
    }

    @Test
    fun caseBatteryDecodesPercentNotCharging() { // :192-195
        val c = DeviceStatus.caseBattery(frame(case17 = 0x46))
        assertEquals(DeviceStatus.CaseBattery(percent = 70, isCharging = false), c)
    }

    @Test
    fun caseBatteryDecodesChargingBit() { // :197-200
        val c = DeviceStatus.caseBattery(frame(case17 = 0xc6)) // 0x80 | 70
        assertEquals(DeviceStatus.CaseBattery(percent = 70, isCharging = true), c)
    }

    @Test
    fun caseBatteryDecodesNinetyCharging() { // :202-205
        val c = DeviceStatus.caseBattery(frame(case17 = 0xda)) // 0x80 | 90
        assertEquals(DeviceStatus.CaseBattery(percent = 90, isCharging = true), c)
    }

    @Test
    fun caseBatteryWorksFor0x87() { // :207-210
        val f = frame(case17 = 0x5a); f[0] = 0x87.toByte()
        assertEquals(DeviceStatus.CaseBattery(percent = 90, isCharging = false), DeviceStatus.caseBattery(f))
    }

    @Test
    fun caseBatteryNullForNonDescriptor() { // :212-215
        val f = frame(case17 = 0x46); f[0] = 0x4C
        assertNull(DeviceStatus.caseBattery(f))
    }

    // Combined: still + ambient temp + battery-rising → NOT sleep (#41 core case) — :217-285
    //
    // This is the key regression guard: a ring on the charger produces a still motion timeline AND
    // cold skin temps AND a rising battery %. All three proxies fire. The sleep wear-gate must drop
    // the block — it must NOT be committed to the health store.

    /** :223-230 — a 23-byte `0x4c` record: BE counter, `[8]` sub-type, motion `[10:15]`, rest zero. */
    private fun rec(counter: Long, motion: Int, sub: Int): BulkRecord {
        val b = ByteArray(23)
        b[0] = (counter shr 24).toByte(); b[1] = ((counter shr 16) and 0xFF).toByte()
        b[2] = ((counter shr 8) and 0xFF).toByte(); b[3] = (counter and 0xFF).toByte()
        b[8] = sub.toByte()
        for (k in 0 until 5) b[10 + k] = motion.toByte()
        return assertNotNull(BulkRecord.of(b))
    }

    /** 20 active (0x14) → 216 still (0x01, sleep-vitals 0x62) → 20 active, 150 s apart. */
    private fun stillNightRecords(): List<BulkRecord> {
        val recs = mutableListOf<BulkRecord>()
        var c = 0x0c220000L
        repeat(20) { recs += rec(c, motion = 0x14, sub = 0x12); c += 150 } // active
        repeat(216) { recs += rec(c, motion = 0x01, sub = 0x62); c += 150 } // still
        repeat(20) { recs += rec(c, motion = 0x14, sub = 0x12); c += 150 } // active
        return recs
    }

    @Test
    fun chargingNightNotCommittedAsSleep() { // :232-260
        // 9 h of still, sleep-vitals epochs (ring on charger: motion=01, sub=0x62)
        val recs = stillNightRecords()

        // Motion-only: the still block looks like sleep.
        assertNotNull(BulkSleep.mainSleep(recs), "motion-only: still block reads as sleep (expected baseline)")

        // Confirm isWorn returns false for an ambient-temp descriptor frame.
        val coldFrame = descriptorFrame(tempA = tempInt(22.0), tempB = tempInt(22.0))
        assertEquals(false, DeviceStatus.isWorn(coldFrame), "22 °C frame reads as unworn")

        // Confirm isCharging returns true for a rising battery trend.
        assertTrue(DeviceStatus.isCharging(batteryTrend = listOf(70, 72, 74, 76)), "rising trend reads as charging")

        // With cold (off-wrist / charging) temperature samples covering the entire night, the sleep
        // wear-gate must reclassify the still block as active → no sleep block.
        val coldTemps = recs.map { TemperatureSample(it.date(), 22.0) }
        assertNull(BulkSleep.mainSleep(recs, temperatures = coldTemps), "ambient-temp still block must NOT be committed as a night of sleep (#41)")
        assertTrue(BulkSleep.sleepSegments(recs, temperatures = coldTemps).isEmpty(), "no sleep segments for a cold-temp still night (#41)")
    }

    /** A warm (worn) ring with the same still motion must STILL be detected as sleep. */
    @Test
    fun wornNightWithStillMotionIsKeptAsSleep() { // :263-272
        val recs = stillNightRecords()
        val warmTemps = recs.map { TemperatureSample(it.date(), 32.0) }
        assertNotNull(BulkSleep.mainSleep(recs, temperatures = warmTemps), "worn (32 °C) still night must survive the wear gate")
    }

    /** No temperature data → detection falls back to motion alone (absence ≠ unworn). */
    @Test
    fun noTemperatureSamplesLeavesDetectionUnchanged() { // :275-284
        val recs = stillNightRecords()
        assertEquals(
            BulkSleep.mainSleep(recs) != null,
            BulkSleep.mainSleep(recs, temperatures = emptyList()) != null,
            "empty temperatures → same result as motion-only (absence ≠ unworn)",
        )
    }
}
