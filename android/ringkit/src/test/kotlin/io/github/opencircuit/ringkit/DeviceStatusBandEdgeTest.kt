package io.github.opencircuit.ringkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Kotlin-only additions for `DeviceStatus`: the plausibility-band edges of each descriptor decoder
 * (A3). Upstream's `DeviceStatusTests.swift` never tests them (see `DeviceStatusTest`, the 30-test
 * port). They are kept in a separate class so the ported class keeps upstream's count.
 *
 * Bands are from upstream `S/DeviceStatus.swift` (@ b1c2fdd): battery 1…100 (:45), skin temp
 * 150…500 (0.1 °C) on BOTH channels (:57), voltage 2500…4600 mV (:96), case % ≤ 100 (:120).
 * Frames are built on the RAW byte path (PL-2026-09-30-m).
 */
class DeviceStatusBandEdgeTest {

    /** A 19-byte 0x10 descriptor, zero-filled, with each `index to value` pair written as an unsigned byte. */
    private fun descriptor(vararg set: Pair<Int, Int>): ByteArray {
        val f = ByteArray(19)
        f[0] = 0x10
        for ((i, v) in set) f[i] = bytes(v)[0]
        return f
    }

    /** A descriptor whose two temperature channels are [a] and [b] (0.1 °C units, 16-bit BE). */
    private fun temps(a: Int, b: Int) =
        descriptor(6 to (a shr 8), 7 to (a and 0xFF), 8 to (b shr 8), 9 to (b and 0xFF))

    /** A descriptor whose voltage field `[14:16]` is [mv]. */
    private fun volts(mv: Int) = descriptor(14 to (mv shr 8), 15 to (mv and 0xFF))

    @Test
    fun descriptorMustBeAtLeast19Bytes() {
        val full = descriptor(1 to 50)
        assertEquals(50, DeviceStatus.battery(full))
        assertNull(DeviceStatus.battery(full.copyOf(18)))
    }

    @Test
    fun batteryBandIsOneToHundred() {
        assertNull(DeviceStatus.battery(descriptor(1 to 0)))
        assertEquals(1, DeviceStatus.battery(descriptor(1 to 1)))
        assertEquals(100, DeviceStatus.battery(descriptor(1 to 100)))
        assertNull(DeviceStatus.battery(descriptor(1 to 101)))
        assertNull(DeviceStatus.battery(descriptor(1 to 0xff))) // unsigned read: 255, not -1
    }

    @Test
    fun skinTemperatureBandIsFifteenToFiftyOnBothChannels() {
        assertNull(DeviceStatus.skinTemperature(temps(149, 300)))
        assertEquals(15.0, DeviceStatus.skinTemperature(temps(150, 150))?.celsius)
        assertEquals(50.0, DeviceStatus.skinTemperature(temps(500, 500))?.celsius)
        assertNull(DeviceStatus.skinTemperature(temps(300, 501)))
        // One plausible channel is not enough — both must be in band.
        assertNull(DeviceStatus.skinTemperature(temps(300, 149)))
        assertNull(DeviceStatus.skinTemperature(temps(501, 300)))
    }

    @Test
    fun skinTemperatureCelsiusIsChannelMeanAndFahrenheitConverts() {
        val t = DeviceStatus.skinTemperature(temps(360, 370))
        assertEquals(SkinTemperature(channelA = 36.0, channelB = 37.0), t)
        assertEquals(36.5, t!!.celsius, 1e-9)
        assertEquals(97.7, t.fahrenheit, 1e-9)
    }

    @Test
    fun voltageBandIs2500To4600() {
        assertNull(DeviceStatus.batteryVoltageMillivolts(volts(2499)))
        assertEquals(2500, DeviceStatus.batteryVoltageMillivolts(volts(2500)))
        assertEquals(4600, DeviceStatus.batteryVoltageMillivolts(volts(4600)))
        assertNull(DeviceStatus.batteryVoltageMillivolts(volts(4601)))
    }

    @Test
    fun caseBatteryPercentAbove100IsRejected() {
        assertEquals(DeviceStatus.CaseBattery(0, false), DeviceStatus.caseBattery(descriptor(17 to 0x00)))
        assertEquals(DeviceStatus.CaseBattery(100, false), DeviceStatus.caseBattery(descriptor(17 to 0x64)))
        assertEquals(DeviceStatus.CaseBattery(100, true), DeviceStatus.caseBattery(descriptor(17 to 0xe4)))
        assertNull(DeviceStatus.caseBattery(descriptor(17 to 0x65))) // 101 %
        assertNull(DeviceStatus.caseBattery(descriptor(17 to 0xe5))) // charging bit + 101 %
        assertNull(DeviceStatus.caseBattery(descriptor(17 to 0x7f))) // 127 %
    }

    @Test
    fun stepsReadsTheFullUnsigned16BitRange() {
        assertEquals(0, DeviceStatus.steps(descriptor()))
        assertEquals(81, DeviceStatus.steps(descriptor(5 to 0x51)))
        assertEquals(65535, DeviceStatus.steps(descriptor(4 to 0xff, 5 to 0xff)))
        assertNull(DeviceStatus.steps(descriptor().also { it[0] = 0x15 }))
    }
}
