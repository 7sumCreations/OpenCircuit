package io.github.opencircuit.app

import io.github.opencircuit.app.ring.DeviceStatusModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The device-status model keeps the ring's battery from the `0x10` / `0x87` descriptors. */
class DeviceStatusModelTest {

    private val model = DeviceStatusModel()

    @Test
    fun noBatteryIsKnownBeforeTheFirstDescriptor() {
        assertNull(model.state.value.batteryPercent)
    }

    @Test
    fun theBatteryComesFromByteOneOfEachDescriptor() {
        model.onDescriptor(TestFrames.wornDescriptor)
        assertEquals(66, model.state.value.batteryPercent)

        model.onDescriptor(TestFrames.chargingResponseDescriptor)
        assertEquals(71, model.state.value.batteryPercent)
    }

    @Test
    fun aDescriptorWithAnOutOfRangeBatteryKeepsTheLastGoodValue() {
        model.onDescriptor(TestFrames.wornDescriptor)

        val zero = TestFrames.chargingDescriptor.also { it[1] = 0 }
        val over = TestFrames.chargingDescriptor.also { it[1] = 101 }
        model.onDescriptor(zero)
        model.onDescriptor(over)

        assertEquals(66, model.state.value.batteryPercent)
    }

    @Test
    fun aShortOrForeignFrameChangesNothing() {
        model.onDescriptor(TestFrames.wornDescriptor.copyOf(18))
        model.onDescriptor(TestFrames.liveHeartRate)

        assertNull(model.state.value.batteryPercent)
    }
}
