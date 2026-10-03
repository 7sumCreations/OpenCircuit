package io.github.opencircuit.store.codec

import io.github.opencircuit.ringkit.RingAlarm
import io.github.opencircuit.ringkit.VibrationPattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The ring alarm survives its stored form.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/RingAlarmTests.swift (@ b1c2fdd)
 * `roundTripsThroughJSON` (`:174`, a Swift Testing `@Test`); the file's other tests are in
 * `:ringkit`'s `RingAlarmTest`.
 */
class RingAlarmStoredFormTest {

    @Test
    fun roundTripsThroughJson() {
        // A decode failure would silently reset a user's alarm, which they would discover by oversleeping.
        val alarm = RingAlarm(
            isEnabled = true, hour = 6, minute = 45, weekdays = setOf(2, 3, 4, 5, 6),
            pattern = VibrationPattern.LONG, burstCount = 4, burstSpacing = 6.0,
            backupNotification = false,
        )
        val data = assertNotNull(RingAlarmCodec.encode(alarm))
        assertEquals(alarm, readable(RingAlarmCodec.decode(data)))
    }
}
