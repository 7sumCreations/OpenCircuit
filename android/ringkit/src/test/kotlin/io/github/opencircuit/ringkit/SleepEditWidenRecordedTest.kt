package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `SleepEdit.widenRecorded` — an edited night's recorded anchors widen outward-only from a later,
 * fuller staging; unknown (`DISTANT_PAST`) edges never clobber known ones; nothing to do is null.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepEditWidenRecordedTests.swift
 * (@ b1c2fdd) — all 7 tests. Upstream mutates a `var` copy of the incoming window; here it is
 * `copy(...)`.
 */
class SleepEditWidenRecordedTest {

    private fun d(h: Int, m: Int = 0): Instant = Instant.ofEpochSecond(1_780_000_000L + h * 3600L + m * 60L)

    private fun w(s: Instant, e: Instant, onset: Instant? = null, wake: Instant? = null) =
        SleepEdit.RecordedWindow(inBedStart = s, inBedEnd = e, sleepOnset = onset ?: s, sleepWake = wake ?: e)

    private val sentinel = SleepEdit.RecordedWindow(
        inBedStart = SleepEdit.DISTANT_PAST, inBedEnd = SleepEdit.DISTANT_PAST,
        sleepOnset = SleepEdit.DISTANT_PAST, sleepWake = SleepEdit.DISTANT_PAST,
    )

    @Test
    fun fullerStagingWidensBothEdges() {
        assertEquals(w(d(2, 30), d(9)), SleepEdit.widenRecorded(w(d(3), d(6)), w(d(2, 30), d(9))))
    }

    @Test
    fun theDeviceCaseWakeGrows() {
        // Recorded froze at 03:44 -> 06:04; the 09:13 restage must pull the wake anchor forward.
        val out = assertNotNull(SleepEdit.widenRecorded(w(d(3, 44), d(6, 4)), w(d(3, 44), d(9, 13))))
        assertEquals(d(9, 13), out.sleepWake)
        assertEquals(d(3, 44), out.inBedStart, "the untouched edge stays put")
    }

    @Test
    fun thinnerStagingIsANoOp() {
        assertNull(SleepEdit.widenRecorded(w(d(3), d(9)), w(d(4), d(8))), "widening never shrinks — a partial later slice changes nothing")
    }

    @Test
    fun identicalStagingIsANoOp() {
        assertNull(SleepEdit.widenRecorded(w(d(3), d(9)), w(d(3), d(9))))
    }

    @Test
    fun unknownStoredWindowAdoptsIncoming() {
        assertEquals(w(d(3), d(9)), SleepEdit.widenRecorded(sentinel, w(d(3), d(9))))
    }

    @Test
    fun unknownIncomingWindowIsANoOp() {
        assertNull(SleepEdit.widenRecorded(w(d(3), d(9)), sentinel))
    }

    @Test
    fun unknownIncomingSleepWindowLeavesStoredSleepAnchors() {
        // In-bed grows but the incoming staging found no asleep block: the stored onset/wake must not
        // be clobbered by sentinels.
        val stored = w(d(3), d(6), onset = d(3, 10), wake = d(5, 50))
        val incoming = w(d(3), d(9)).copy(sleepOnset = SleepEdit.DISTANT_PAST, sleepWake = SleepEdit.DISTANT_PAST)
        val out = assertNotNull(SleepEdit.widenRecorded(stored, incoming))
        assertEquals(d(9), out.inBedEnd)
        assertEquals(d(3, 10), out.sleepOnset)
        assertEquals(d(5, 50), out.sleepWake)
    }
}
