package io.github.opencircuit.ringkit

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for `RingVibration` / `VibrationPattern` / `Command.vibrate` — port of upstream
 * ios/OpenCircuitKit/Tests/OpenCircuitKitTests/RingAlarmTests.swift:24-39 (@ b1c2fdd) ONLY.
 * The rest of RingAlarmTests (alarm scheduling, `RingAlarm`) is in `RingAlarmTest`.
 *
 * The expected frames are typed from the capture list in
 * `S/RingVibration.swift:12-15` (🟢 12/12 buzzes on a Gen 3 ring), never from `Opcodes.kt`.
 */
class RingVibrationTest {

    @Test
    fun vibrateFrameMatchesTheConfirmedCapture() { // RingAlarmTests.swift:24-29
        // The exact frames the tester's Gen 3 buzzed on, 12/12. If this ever changes, the change
        // is wrong until a new capture says otherwise.
        assertContentEquals(hex("0b03016400"), Command.vibrate(VibrationPattern.NOTIFICATION))
        assertContentEquals(hex("0b03026400"), Command.vibrate(VibrationPattern.LONG))
    }

    @Test
    fun onlyGen3ExposesTheMotor() { // RingAlarmTests.swift:31-39
        assertTrue(RingVibration.isSupported(RingGeneration.GEN3))
        assertFalse(RingVibration.isSupported(RingGeneration.GEN2))
        assertFalse(RingVibration.isSupported(RingGeneration.GEN2_AIR))
        assertFalse(RingVibration.isSupported(RingGeneration.GEN1))
        // Fails CLOSED before the DIS firmware read lands, so the UI can't flash a control we
        // don't yet know is safe to offer.
        assertFalse(RingVibration.isSupported(RingGeneration.UNKNOWN))
    }

    // Kotlin-only additions — upstream leaves these untested.

    @Test
    fun patternsAndIntensityMatchUpstream() {
        // RingVibration.swift:45-50, :73. Exactly two patterns — "There is no third".
        assertEquals(listOf(0x01, 0x02), VibrationPattern.entries.map { it.rawValue })
        assertEquals(0x64, RingVibration.INTENSITY_BYTE)
    }

    @Test
    fun displayStringsAreVerbatim() {
        // RingVibration.swift:53-66.
        assertEquals("Triple pulse", VibrationPattern.NOTIFICATION.displayName)
        assertEquals("Single long buzz", VibrationPattern.LONG.displayName)
        assertEquals(
            "Short, short, long — the pattern RingConn uses for reminders.",
            VibrationPattern.NOTIFICATION.displayDetail,
        )
        assertEquals(
            "One sustained buzz — RingConn's workout-start signal.",
            VibrationPattern.LONG.displayDetail,
        )
    }

    @Test
    fun vibrateReturnsAFreshArrayEachCall() {
        // Fresh array per call: a caller mutating its frame must not change the next one.
        val first = Command.vibrate(VibrationPattern.NOTIFICATION)
        first[2] = 0x02
        assertContentEquals(hex("0b03016400"), Command.vibrate(VibrationPattern.NOTIFICATION))
    }
}
