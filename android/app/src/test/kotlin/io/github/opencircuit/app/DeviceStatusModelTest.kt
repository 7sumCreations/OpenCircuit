package io.github.opencircuit.app

import io.github.opencircuit.app.ring.DeviceStatusModel
import io.github.opencircuit.ringkit.DeviceStatus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The device-status model keeps what the ring's `0x10` / `0x87` descriptors say: battery %,
 * whether it is on the charger, the charging case's battery while docked, and the time to full
 * or the time left from the battery's own history (PROTOCOL.md §5.4). Fed real descriptor bytes,
 * changed byte by byte on the raw path.
 */
class DeviceStatusModelTest {

    private fun TestScope.model() = DeviceStatusModel(backgroundScope, monotonicMillis = { testScheduler.currentTime })

    @Test
    fun nothingIsKnownBeforeTheFirstDescriptor() = runTest {
        val state = model().state.value

        assertNull(state.batteryPercent)
        assertFalse(state.onCharger)
        assertFalse(state.towardFull)
        assertNull(state.caseBattery)
        assertNull(state.timeToFullSeconds)
        assertNull(state.timeToEmptySeconds)
        assertEquals(0, state.batteryReadings)
    }

    @Test
    fun theBatteryComesFromByteOneOfEachDescriptor() = runTest {
        val model = model()
        model.onDescriptor(TestFrames.wornDescriptor)
        assertEquals(66, model.state.value.batteryPercent)

        model.onDescriptor(TestFrames.chargingResponseDescriptor)
        assertEquals(71, model.state.value.batteryPercent)
        assertEquals(2, model.state.value.batteryReadings)
    }

    @Test
    fun aBatteryOfOneAndOfOneHundredAreReadings() = runTest {
        val model = model()
        model.onDescriptor(descriptor(battery = 1))
        assertEquals(1, model.state.value.batteryPercent)
        model.onDescriptor(descriptor(battery = 100))
        assertEquals(100, model.state.value.batteryPercent)
    }

    @Test
    fun aBatteryOfZeroOrOver100KeepsTheLastGoodValueAndIsNotAReading() = runTest {
        val model = model()
        model.onDescriptor(TestFrames.wornDescriptor)

        model.onDescriptor(descriptor(battery = 0))
        model.onDescriptor(descriptor(battery = 101))

        assertEquals(66, model.state.value.batteryPercent)
        assertEquals(1, model.state.value.batteryReadings)
    }

    @Test
    fun aShortOrForeignFrameChangesNothing() = runTest {
        val model = model()
        model.onDescriptor(TestFrames.wornDescriptor.copyOf(18))
        model.onDescriptor(TestFrames.liveHeartRate)

        assertNull(model.state.value.batteryPercent)
        assertEquals(0, model.state.value.batteryReadings)
    }

    @Test
    fun stateByteFourMeansOnTheChargerAndAnyOtherStateByteDoesNot() = runTest {
        val model = model()
        model.onDescriptor(TestFrames.chargingDescriptor) // [2] = 0x04
        assertTrue(model.state.value.onCharger)
        assertTrue(model.state.value.towardFull)

        model.onDescriptor(descriptor(battery = 71, state = 0x02))
        assertFalse(model.state.value.onCharger)
        model.onDescriptor(descriptor(battery = 71, state = 0x01))
        assertFalse(model.state.value.onCharger)
        model.onDescriptor(descriptor(battery = 71, state = 0x06))
        assertFalse(model.state.value.onCharger)
    }

    @Test
    fun theCaseBatteryIsShownOnlyWhileDockedWithItsChargingBit() = runTest {
        val model = model()
        model.onDescriptor(TestFrames.wornDescriptor) // [17] = 0xff: not in the case
        assertNull(model.state.value.caseBattery)

        model.onDescriptor(TestFrames.chargingDescriptor) // [17] = 0x46: docked, 70 %
        assertEquals(DeviceStatus.CaseBattery(percent = 70, isCharging = false), model.state.value.caseBattery)

        model.onDescriptor(descriptor(battery = 71, state = 0x04, case = 0xda)) // 0x80 | 90
        assertEquals(DeviceStatus.CaseBattery(percent = 90, isCharging = true), model.state.value.caseBattery)

        model.onDescriptor(descriptor(battery = 71, state = 0x02, case = 0xff)) // taken out of the case
        assertNull(model.state.value.caseBattery, "cleared as soon as the ring leaves the case")
    }

    @Test
    fun aCaseByteOver100IsNotACaseReading() = runTest {
        val model = model()
        model.onDescriptor(descriptor(battery = 71, state = 0x04, case = 0x7f)) // 127 %, not charging

        assertNull(model.state.value.caseBattery)
    }

    @Test
    fun aSlowDischargeGivesTheTimeLeftAndNoTimeToFull() = runTest {
        val model = model()
        model.onDescriptor(descriptor(battery = 80))
        advanceTo(HOUR)
        model.onDescriptor(descriptor(battery = 79))
        advanceTo(2 * HOUR)
        model.onDescriptor(descriptor(battery = 78))

        // 2 points in 2 hours: 1 %/h, 78 % left → 78 hours.
        assertEquals(78.0 * 3_600, model.state.value.timeToEmptySeconds)
        assertNull(model.state.value.timeToFullSeconds)
    }

    @Test
    fun oneReadingIsNotEnoughForAnyEstimate() = runTest {
        val model = model()
        model.onDescriptor(descriptor(battery = 80))

        assertNull(model.state.value.timeToEmptySeconds)
        assertNull(model.state.value.timeToFullSeconds)
    }

    @Test
    fun aChargeOnTheChargerGivesTheTimeToFull() = runTest {
        val model = model()
        model.onDescriptor(descriptor(battery = 60, state = 0x04))
        advanceTo(10 * MINUTE)
        model.onDescriptor(descriptor(battery = 62, state = 0x04))
        advanceTo(20 * MINUTE)
        model.onDescriptor(descriptor(battery = 64, state = 0x04))

        // 4 points in 20 minutes: 12 %/h, 36 % to go → 3 hours.
        assertEquals(3.0 * 3_600, model.state.value.timeToFullSeconds)
    }

    @Test
    fun leavingTheChargerDropsTheChargeHistory() = runTest {
        val model = model()
        model.onDescriptor(descriptor(battery = 60, state = 0x04))
        advanceTo(10 * MINUTE)
        model.onDescriptor(descriptor(battery = 62, state = 0x04))

        model.onDescriptor(descriptor(battery = 62, state = 0x02))

        assertNull(model.state.value.timeToFullSeconds)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}

/**
 * The real worn descriptor (`TestFrames.wornDescriptor`) with battery `[1]`, state `[2]` and case
 * `[17]` replaced byte by byte — the raw path, no frame builder.
 */
internal fun descriptor(battery: Int, state: Int = 0x02, case: Int = 0xff, opcode: Int = 0x10): ByteArray =
    TestFrames.wornDescriptor.also {
        it[0] = opcode.toByte()
        it[1] = battery.toByte()
        it[2] = state.toByte()
        it[17] = case.toByte()
    }
