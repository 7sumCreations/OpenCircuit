package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.SyncMeasurement.ContinuityKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Whether a channel's history joins the last sync's: the previous sync's last counter P against this
 * sync's first counter F. The ring records one epoch every 150 s, so the next record after P is due
 * at P + 150; the gap is F − P − 150 s, read with ±60 s of tolerance (epoch drift measured up to 22 s
 * over 320 epochs). Every edge below is typed from that rule, not from the code under test.
 */
class ContinuityTest {

    private val p = 200_000_000L

    @Test
    fun theNextEpochExactlyIsContiguous() {
        val c = SyncMeasurement.continuity(previousLastCounter = p, firstCounter = p + 150)
        assertEquals(ContinuityKind.CONTIGUOUS, c.kind)
        assertEquals(0L, c.gapSeconds)
    }

    @Test
    fun sixtySecondsLateIsStillContiguousSixtyOneIsAGap() {
        assertEquals(ContinuityKind.CONTIGUOUS, SyncMeasurement.continuity(p, p + 150 + 60).kind)
        val gap = SyncMeasurement.continuity(p, p + 150 + 61)
        assertEquals(ContinuityKind.GAP, gap.kind)
        assertEquals(61L, gap.gapSeconds)
    }

    @Test
    fun sixtySecondsEarlyIsStillContiguousSixtyOneIsAnOverlap() {
        assertEquals(ContinuityKind.CONTIGUOUS, SyncMeasurement.continuity(p, p + 150 - 60).kind)
        val overlap = SyncMeasurement.continuity(p, p + 150 - 61)
        assertEquals(ContinuityKind.OVERLAP, overlap.kind)
        assertEquals(-61L, overlap.gapSeconds)
    }

    @Test
    fun theSameRecordOfferedAgainIsAnOverlap() {
        // The page in flight when the link dropped was never acknowledged: the ring offers it again.
        val c = SyncMeasurement.continuity(p, p)
        assertEquals(ContinuityKind.OVERLAP, c.kind)
        assertEquals(-150L, c.gapSeconds)
    }

    @Test
    fun aDayMissingIsAGapOfADay() {
        val c = SyncMeasurement.continuity(p, p + 150 + 86_400)
        assertEquals(ContinuityKind.GAP, c.kind)
        assertEquals(86_400L, c.gapSeconds)
    }

    @Test
    fun noPreviousSyncIsTheFirstAndNoRecordsIsNoRecords() {
        val first = SyncMeasurement.continuity(previousLastCounter = null, firstCounter = p)
        assertEquals(ContinuityKind.FIRST_SYNC, first.kind)
        assertNull(first.gapSeconds)
        val none = SyncMeasurement.continuity(previousLastCounter = p, firstCounter = null)
        assertEquals(ContinuityKind.NO_RECORDS, none.kind)
        assertNull(none.gapSeconds)
        assertEquals(ContinuityKind.NO_RECORDS, SyncMeasurement.continuity(null, null).kind)
    }

    @Test
    fun countersAtTheTopOfTheirRangeDoNotOverflow() {
        val top = 0xFFFF_FFFFL
        assertEquals(ContinuityKind.CONTIGUOUS, SyncMeasurement.continuity(top - 150, top).kind)
        assertEquals(ContinuityKind.OVERLAP, SyncMeasurement.continuity(top, 0L).kind)
    }
}
