package io.github.opencircuit.ringkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/WorkoutBufferedSportFillTests.swift
 * (@ b1c2fdd), 5 of 5, each test named after upstream's with its line.
 *
 * A manual workout's live-HR gaps are filled from the ring's own buffered `0x4d` records, never
 * double-counted, and the workout's window suppresses auto-detection (tester report 2026-09-27). The
 * frame is the already-committed fixture from `AutomaticWorkoutDetectionTests` — 9 records, 10 s apart,
 * HR 85–89 — decoded from its raw bytes. Cursors (Swift `UInt32`) are `Long`.
 */
class WorkoutBufferedSportFillTest {

    private val frameHex = "4d 00 12 0c 47 47 fb 57 00 03 6a c7 08 00 0c 47 48 05 59 00 02 1e c7 09 00 0c 47 48 0f 58 00 03 1d c7 06 00 0c 47 48 19 55 00 00 79 c6 04 00 0c 47 48 23 58 00 02 55 bf 04 00 0c 47 48 2d 55 00 01 07 bf 03 00 0c 47 48 37 59 00 01 c4 c7 05 00 0c 47 48 41 59 00 02 9d c7 00 00 0c 47 48 4b 58 00 02 b7 c7 00 00 b5"

    private fun records(): List<HistoricalSportFrame.Sample> {
        val bytes = frameHex.split(" ").map { it.toInt(16).toByte() }.toByteArray()
        return assertNotNull(HistoricalSportFrame.decode(bytes))
    }

    private fun window(r: List<HistoricalSportFrame.Sample>, pad: Long = 60): DateInterval =
        DateInterval(r.first().endDate.minusSeconds(10 + pad), r.last().endDate.plusSeconds(pad))

    /** The phone was away the whole time: every buffered record becomes a workout HR sample. */
    @Test
    fun testNoLiveDataUsesEveryBufferedRecord() { // :22
        val r = records()
        val fill = WorkoutBufferedSportFill.fill(captured = emptyList(), buffered = r, window = window(r))
        assertEquals(r.size, fill.hrSamples.size)
        assertEquals(r.mapNotNull { it.heartRate }, fill.hrSamples.map { it.bpm })
        assertEquals(10.0, secondsBetween(fill.hrSamples.first().start, fill.hrSamples.first().end))
        assertEquals(r.size, fill.cursors.size)
    }

    /** Live data covering a record's interval wins — no double count. */
    @Test
    fun testLiveCoverageWins() { // :32
        val r = records()
        val live = HRSample(bpm = 140, start = r[3].endDate.minusSeconds(6), end = r[3].endDate.minusSeconds(4))
        val fill = WorkoutBufferedSportFill.fill(captured = listOf(live), buffered = r, window = window(r))
        assertEquals(r.size - 1, fill.hrSamples.size)
        assertFalse(fill.cursors.contains(r[3].cursor))
    }

    /** Review 2026-09-28: live `0x4e` frames that produced NO HR sample (warm-up, dropout, frames the
     *  2-s poll missed) still had their steps summed live — their buffered records must be skipped
     *  entirely, or those steps count twice. Coverage is by the ring's own cursor, any phase offset. */
    @Test
    fun testLiveFramesWithoutHRStillCoverTheirRecords() { // :43
        val r = records()
        for (phase in listOf(0L, 3L, 9L)) { // 0x4e cursor offset within the 10 s
            val liveCursors = r.slice(2..5).map { it.cursor - phase }.toSet()
            val fill = WorkoutBufferedSportFill.fill(captured = emptyList(), buffered = r, window = window(r), liveFrameCursors = liveCursors)
            assertEquals(r.size - 4, fill.cursors.size, "phase $phase")
            for (i in 2..5) assertFalse(fill.cursors.contains(r[i].cursor), "phase $phase record $i")
        }
        // A frame just outside a record's interval does not cover it.
        val outside = WorkoutBufferedSportFill.fill(captured = emptyList(), buffered = r, window = window(r), liveFrameCursors = setOf(r[4].cursor + 1))
        assertTrue(outside.cursors.contains(r[4].cursor))
    }

    /** Records outside the workout window never enter it; a second merge adds nothing new. */
    @Test
    fun testWindowAndIdempotence() { // :59
        val r = records()
        val half = DateInterval(r[0].endDate.minusSeconds(10), r[4].endDate)
        val first = WorkoutBufferedSportFill.fill(captured = emptyList(), buffered = r, window = half)
        assertEquals(5, first.hrSamples.size)
        val again = WorkoutBufferedSportFill.fill(captured = emptyList(), buffered = r, window = half, alreadyMerged = first.cursors)
        assertTrue(again.hrSamples.isEmpty())
        assertEquals(0L, again.steps)
    }

    /** The manual workout's window overlaps every candidate built from its own records, so
     *  resolving it suppresses the "detected walk". */
    @Test
    fun testWorkoutWindowSpanCoversItsOwnRecords() { // :71
        val r = records()
        val span = CursorSpan(window = window(r, pad = 0))
        for (rec in r) assertTrue(span.overlaps(CursorSpan(point = rec.cursor)))
        assertFalse(span.overlaps(CursorSpan(point = r.last().cursor + 3600)))
    }
}
