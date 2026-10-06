package io.github.opencircuit.app

import io.github.opencircuit.app.ring.DeviceStatusModel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The battery reads as out of date once no descriptor with a battery has arrived for 120 s
 * (`ios/OpenCircuit/BLE/RingSession.swift:178-187` @ b1c2fdd), and its age keeps counting while
 * no new one comes. The 120 s is typed here as a literal, never read back from the model.
 */
class BatteryStaleTest {

    private fun TestScope.model() = DeviceStatusModel(backgroundScope, monotonicMillis = { testScheduler.currentTime })

    @Test
    fun theBatteryIsFreshAt119999MillisecondsAndStaleAt120Seconds() = runTest {
        val model = model()
        advanceTo(5_000)
        model.onDescriptor(TestFrames.wornDescriptor)

        advanceTo(5_000 + 119_999)
        assertNull(model.state.value.batteryAgeMillis, "still fresh 1 ms before 120 s")

        advanceTo(5_000 + 120_000)
        assertEquals(120_000, model.state.value.batteryAgeMillis)
    }

    @Test
    fun theAgeKeepsCountingEveryMinute() = runTest {
        val model = model()
        model.onDescriptor(TestFrames.wornDescriptor)

        advanceTo(180_000)
        assertEquals(180_000, model.state.value.batteryAgeMillis)
        advanceTo(10 * 60_000)
        assertEquals(10 * 60_000L, model.state.value.batteryAgeMillis)
    }

    @Test
    fun aNewDescriptorMakesItFreshAgainAndRestartsThe120Seconds() = runTest {
        val model = model()
        model.onDescriptor(TestFrames.wornDescriptor)
        advanceTo(200_000)
        assertEquals(180_000, model.state.value.batteryAgeMillis, "stale at 120 s, then counted each minute")

        model.onDescriptor(TestFrames.wornDescriptor)
        assertNull(model.state.value.batteryAgeMillis)

        advanceTo(200_000 + 119_999)
        assertNull(model.state.value.batteryAgeMillis)
        advanceTo(200_000 + 120_000)
        assertEquals(120_000, model.state.value.batteryAgeMillis)
    }

    @Test
    fun aDescriptorWithoutAValidBatteryDoesNotRefreshIt() = runTest {
        val model = model()
        model.onDescriptor(TestFrames.wornDescriptor)
        advanceTo(100_000)
        model.onDescriptor(descriptor(battery = 0))

        advanceTo(120_000)
        assertEquals(120_000, model.state.value.batteryAgeMillis)
    }

    @Test
    fun nothingIsStaleBeforeAnyBatteryWasRead() = runTest {
        val model = model()
        advanceTo(600_000)

        assertNull(model.state.value.batteryAgeMillis)
    }
}
