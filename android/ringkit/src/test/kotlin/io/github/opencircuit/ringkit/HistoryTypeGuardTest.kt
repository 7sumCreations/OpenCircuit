package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kotlin-only checks that the history-decode port keeps the guarantees Swift's value types gave
 * for free. Swift `struct`s and `Data` copy on assignment and `UInt8` / `UInt32` cannot go out of
 * range; the Kotlin stand-ins (`ByteArray`, classes, `Int` / `Long`) need these spelled out.
 * Kept out of the upstream-port test classes so their counts stay exact.
 */
class HistoryTypeGuardTest {

    private val t = Instant.ofEpochSecond(1_700_000_000L)

    // EpochRecord / EpochSyncSession

    @Test
    fun epochRecordsHoldAPrivateCopyOfTheirPayloadAndCompareByContent() {
        val src = bytes(1, 2, 0xB0)
        val a = EpochRecord.ActivityRecord(timestamp = t, subtype = 0x13, rawPayload = src)
        val p = EpochRecord.PPGRecord(timestamp = t, rawPayload = src)
        val hashBefore = a.hashCode()

        src[0] = 0x55 // the caller keeps writing to its own array
        a.rawPayload[1] = 0x09 // a reader writes to the array it was handed
        p.rawPayload[1] = 0x09

        assertContentEquals(bytes(1, 2, 0xB0), a.rawPayload)
        assertContentEquals(bytes(1, 2, 0xB0), p.rawPayload)
        assertEquals(hashBefore, a.hashCode())
        assertEquals(EpochRecord.ActivityRecord(t, 0x13, bytes(1, 2, 0xB0)), a)
        assertEquals(EpochRecord.PPGRecord(t, bytes(1, 2, 0xB0)), p)
        assertTrue(EpochRecord.ActivityRecord(t, 0x12, bytes(1, 2, 0xB0)) != a, "subtype is part of equality")
    }

    @Test
    fun epochRecordFieldsStayInsideTheirUnsignedRanges() {
        assertFailsWith<IllegalArgumentException> { EpochRecord.ActivityRecord(t, 0x100, ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { EpochRecord.ActivityRecord(t, -1, ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { EpochRecord.EndOfHistoryFrame(0x100, 0L, 0L) }
        assertFailsWith<IllegalArgumentException> { EpochRecord.EndOfHistoryFrame(0x12, -1L, 0L) }
        assertFailsWith<IllegalArgumentException> { EpochRecord.EndOfHistoryFrame(0x12, 0L, 0x1_0000_0000L) }
        assertFailsWith<IllegalArgumentException> { EpochSyncSession(syncOpenCursor = -1L) }
        assertFailsWith<IllegalArgumentException> { EpochSyncSession(syncOpenCursor = 0x1_0000_0000L) }
        // Both ends are accepted, and the top byte of a full-range cursor reads unsigned.
        assertEquals(0xFF, EpochRecord.EndOfHistoryFrame(0xFF, 0L, 0xFFFF_FFFFL).streamHighByte)
    }

    @Test
    fun sessionTakesItsHighByteFromTheSyncOpenCursorExceptTheAllOnesSentinel() {
        assertEquals(0xF1, EpochSyncSession(syncOpenCursor = 0xF122_3344L).streamHighByte)
        assertEquals(0, EpochSyncSession(syncOpenCursor = 0xFFFF_FFFFL).streamHighByte)
        assertEquals(0, EpochSyncSession().streamHighByte)
    }

    @Test
    fun endOfHistoryRejectsEveryUndocumentedLength() {
        assertNull(EpochRecord.parseEndOfHistory(hex("5000001200000000000000"))) // 11 bytes
        assertNull(EpochRecord.parseEndOfHistory(hex("50000012000000"))) // 7 bytes
        assertNull(EpochRecord.parseEndOfHistory(hex("500000140c223344ff"))) // 9 bytes, byte 3 not 0x15
        assertNull(EpochRecord.parseEndOfHistory(hex("510000120c2233440c223344"))) // wrong opcode
        assertEquals(0x0c223344L, EpochRecord.parseEndOfHistory(hex("5000001512" + "0c223344"))?.cursorTo)
        assertEquals(0xfc223344L, EpochRecord.parseEndOfHistory(hex("50000012fc223344"))?.cursorFrom)
    }

    @Test
    fun aCopiedSessionIsIndependentOfTheOriginal() {
        val original = EpochSyncSession()
        val copy = original.copy()
        original.complete(hex("500000120c2233440c223344"))

        assertTrue(original.isComplete)
        assertEquals(false, copy.isComplete, "a copy must not see the original's later mutations")
        assertEquals(0, copy.streamHighByte)
        assertTrue(copy != original)
    }

    // SyncCursor

    private fun hr(at: Instant) = QuantitySample(kind = MetricKind.HEART_RATE, start = at, value = 60.0)

    @Test
    fun aCopiedCursorDoesNotMoveWhenTheOriginalAdvances() {
        val original = SyncCursor()
        val copy = original.copy()
        original.advance(MetricKind.HEART_RATE, to = t)
        original.selectNew(listOf(QuantitySample(kind = MetricKind.SPO2, start = t, value = 0.97)))

        assertEquals(t, original.last(MetricKind.HEART_RATE))
        assertNull(copy.last(MetricKind.HEART_RATE), "a copy must not move when the original advances")
        assertNull(copy.last(MetricKind.SPO2))
        assertTrue(copy != original)
        assertEquals(SyncCursor(), copy)
    }

    @Test
    fun cursorDoesNotAliasTheMapItWasBuiltFrom() {
        val seed = mutableMapOf(MetricKind.STEPS.rawValue to t)
        val c = SyncCursor(seed)
        seed[MetricKind.STEPS.rawValue] = t.plusSeconds(60)
        seed[MetricKind.SPO2.rawValue] = t

        assertEquals(t, c.last(MetricKind.STEPS))
        assertNull(c.last(MetricKind.SPO2))
    }

    @Test
    fun stagedCursorSharesNoStateWithTheOriginal() {
        val c = SyncCursor()
        val (fresh, advanced) = c.selectNewStaged(listOf(hr(t)))
        advanced.advance(MetricKind.SPO2, to = t) // later work on the staged cursor

        assertEquals(1, fresh.size)
        assertNull(c.last(MetricKind.HEART_RATE))
        assertNull(c.last(MetricKind.SPO2), "the staged cursor must not share state with the original")
        assertEquals(listOf(MetricKind.HEART_RATE, MetricKind.SPO2), advanced.advancedKinds(since = c))
    }

    @Test
    fun theCursorMovesOnlyThroughAdvanceAndSelectNew() {
        val c = SyncCursor()
        c.isNew(MetricKind.HEART_RATE, t)
        c.last(MetricKind.HEART_RATE)
        c.advancedKinds(since = SyncCursor())
        c.selectNewStaged(listOf(hr(t)))
        c.copy().advance(MetricKind.HEART_RATE, to = t)
        assertEquals(SyncCursor(), c, "queries, staging and a copy's advance must leave the cursor unmoved")

        c.selectNew(listOf(hr(t)))
        assertEquals(t, c.last(MetricKind.HEART_RATE))
        c.selectNew(emptyList())
        assertEquals(t, c.last(MetricKind.HEART_RATE), "an empty batch is not a move")
    }

    // CumulativeMetrics

    @Test
    fun onlyStepsAndActiveEnergyAreCumulativeCounters() {
        assertEquals(
            setOf(MetricKind.STEPS, MetricKind.ACTIVE_ENERGY),
            MetricKind.entries.filter { it.isCumulativeCounter }.toSet(),
        )
    }

    @Test
    fun accumulatedSampleKeepsKindAndSpanAndLeavesTheInputStateAlone() {
        val state = CumulativeMetricState(previousRawValue = 10.0, dailyTotal = 10.0)
        val sample = QuantitySample(MetricKind.ACTIVE_ENERGY, start = t, end = t.plusSeconds(60), value = 25.0)

        val r = CumulativeMetricAccumulator.accumulate(sample, state)

        assertEquals(QuantitySample(MetricKind.ACTIVE_ENERGY, start = t, end = t.plusSeconds(60), value = 25.0), r.sample)
        assertEquals(25.0, r.rawValue)
        assertEquals(15.0, r.deltaValue)
        assertEquals(CumulativeMetricState(previousRawValue = 10.0, dailyTotal = 10.0), state)
        // An equal reading is a zero delta, not a rollover.
        assertEquals(0.0, CumulativeMetricAccumulator.accumulate(QuantitySample(MetricKind.STEPS, t, value = 10.0), state).deltaValue)
    }
}
