package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The PREDICTED decoder for the never-captured "history activity response" record
 * (../docs/PROTOCOL.md §5.3.1). Synthetic fixtures only — these pin the byte-layout math so it is
 * ready the moment a real capture lands; they do NOT prove the layout itself.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/ActivityRecordPredictedTests.swift
 * (@ b1c2fdd), all 5 tests.
 */
class ActivityRecordPredictedTest {

    // :19-23 — counter 0c22d999, steps=1234 (LE d2 04), deviceState=01, powerLevel=75 (4b),
    // temp1..4 = 358/360/359/357 (LE), item5p0=(2,3,4), activeSeconds=90 (LE 5a 00),
    // dailyActiveFlag=01, trailer=00.
    private val syntheticActivityRecord = "0c22d999d204014b66016801670165010203045a000100"

    // :25
    @Test
    fun decodePredictedLayout() {
        val r = assertNotNull(ActivityRecordPredicted.decode(hex(syntheticActivityRecord)))
        assertEquals(1234, r.steps)
        assertEquals(0x01, r.deviceState)
        assertEquals(75, r.powerLevel)
        assertEquals(358, r.temp1)
        assertEquals(360, r.temp2)
        assertEquals(359, r.temp3)
        assertEquals(357, r.temp4)
        assertEquals(listOf(2, 3, 4), r.item5p0)
        assertEquals(90, r.activeSeconds)
        assertEquals(0x01, r.dailyActiveFlag)
    }

    // :39
    @Test
    fun counterToWallClock() {
        val r = assertNotNull(ActivityRecordPredicted.decode(hex(syntheticActivityRecord)))
        assertEquals(Instant.ofEpochSecond(0x0c22d999L + Command.SYNC_EPOCH), r.date)
    }

    // :45
    @Test
    fun wrongLengthReturnsNull() {
        assertNull(ActivityRecordPredicted.decode(hex("0c22d999")))
    }

    // :49
    @Test
    fun syntheticActivityRecordIsPlausible() {
        val r = assertNotNull(ActivityRecordPredicted.decode(hex(syntheticActivityRecord)))
        assertTrue(r.isPlausible)
    }

    // :57 — a REAL measurement record decoded via the predicted ACTIVITY layout is implausible:
    // proof these are different record classes, not a layout bug.
    @Test
    fun measurementRecordFailsActivityPlausibilityCheck() {
        val measurementRecord = "0c22d5bf444d057a620a01010101012aa0000090000004"
        val r = assertNotNull(ActivityRecordPredicted.decode(hex(measurementRecord)))
        assertFalse(r.isPlausible, "a MEASUREMENT record decoded as ACTIVITY should violate the sanity bounds")
    }
}
