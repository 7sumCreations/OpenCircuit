package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

    /** A `0x4c` page holding one activity record, trailer by a test-side XOR (never `Frame.xorTrailer`). */
    private fun activityPage(counterLow: Int, subtype: Int = 0x12): ByteArray {
        val record = bytes(0x0C, 0x22, 0x98, counterLow) + ByteArray(19).also { it[4] = subtype.toByte(); it[11] = 0x07 }
        val withoutTrailer = bytes(0x4C, 0x00, 0x00) + record
        return withoutTrailer + bytes(withoutTrailer.fold(0) { acc, b -> acc xor (b.toInt() and 0xFF) })
    }

    @Test
    fun sessionBuffersAPrivateCopyOfEachPage() {
        val page = activityPage(counterLow = 0xc3)
        val session = EpochSyncSession()
        val before = session.appendActivityPage(page)
        assertEquals(1, before.size)

        page[8 + 3] = 0x13 // the caller reuses its array (subtype byte; the XOR no longer matches)
        session.complete(hex("500000120c2233440c223344"))

        val untouched = EpochSyncSession()
        untouched.appendActivityPage(activityPage(counterLow = 0xc3))
        untouched.complete(hex("500000120c2233440c223344"))
        assertEquals(untouched, session, "the buffered page must not change with the caller's array")
        assertEquals(0x12, session.activityRecords.single().subtype)
        assertEquals(0x0cL, session.activityRecords.single().timestamp.minusSeconds(Command.SYNC_EPOCH).epochSecond ushr 24)
    }

    @Test
    fun aCopyDoesNotSeePagesAppendedToTheOriginalLater() {
        val original = EpochSyncSession()
        original.appendActivityPage(activityPage(counterLow = 0xc3))
        val copy = original.copy()
        original.appendActivityPage(activityPage(counterLow = 0xc4))
        original.complete(hex("500000120c2233440c223344"))
        copy.complete(hex("500000120c2233440c223344"))

        assertEquals(2, original.activityRecords.size)
        assertEquals(1, copy.activityRecords.size, "a copy must not share the original's page buffer")
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
    fun cumulativeValueTypesExposeNoSetters() {
        // Upstream's state is a Swift struct with `var` fields (copied on assignment); a shared
        // Kotlin reference must not be changeable in place, so each type is `val`-only.
        for (type in listOf(CumulativeMetricState::class.java, CumulativeMetricResult::class.java)) {
            assertEquals(emptyList(), type.methods.filter { it.name.startsWith("set") }.map { it.name }, type.simpleName)
        }
        val next = CumulativeMetricState().copy(previousRawValue = 5.0)
        assertEquals(CumulativeMetricState(previousRawValue = 5.0, dailyTotal = 0.0), next)
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

    // BulkRecord / BulkSleep

    /** Real FR02.018 deep-sleep record (HR 68, HRV 77, SpO2 98) — upstream BulkSleepTests.swift:25. */
    private val deepSleepRec = "0c22d5bf444d057a620a01010101012aa0000090000004"

    /** Real activity epoch with its [15:20] tail zeroed — upstream BulkSleepTests.swift:113. */
    private val quietActivityRec = "0c22a16b55210a7d120a01010101010000000000040000"

    private fun bulk(h: String, edit: (ByteArray) -> Unit = {}): BulkRecord =
        assertNotNull(BulkRecord.of(hex(h).also(edit)))

    @Test
    fun bulkRecordExistsOnlyForExactlyTwentyThreeBytes() {
        assertNull(BulkRecord.of(ByteArray(0)))
        assertNull(BulkRecord.of(ByteArray(22)))
        assertNull(BulkRecord.of(ByteArray(24)))
        assertNotNull(BulkRecord.of(ByteArray(23)))
    }

    @Test
    fun bulkRecordHoldsAPrivateCopyAndComparesByContent() {
        val src = hex(deepSleepRec)
        val r = assertNotNull(BulkRecord.of(src))
        val hashBefore = r.hashCode()

        src[4] = 0x10 // the caller keeps writing to its own array
        r.raw[4] = 0x10 // readers write to the arrays they were handed
        r.motion[0] = 0x7f
        r.activityCounts[0] = 0x7f
        r.motionIntensityTail[0] = 0x7f

        assertContentEquals(hex(deepSleepRec), r.raw)
        assertEquals(68, r.heartRate)
        assertContentEquals(bytes(1, 1, 1, 1, 1), r.motion)
        assertContentEquals(bytes(0x2a, 0xa0, 0x00, 0x00, 0x90), r.motionIntensityTail)
        assertEquals(hashBefore, r.hashCode())
        assertEquals(bulk(deepSleepRec), r)
        assertTrue(bulk(deepSleepRec) { it[22] = 0x05 } != r, "every byte is part of equality")
    }

    @Test
    fun bulkRecordReadsEveryFieldUnsigned() {
        // Bytes ≥ 0x80 in each numeric field: a signed read would go negative and fail every guard.
        val r = bulk(deepSleepRec) {
            it[0] = 0xF0.toByte() // counter high byte
            it[4] = 0xC8.toByte() // HR 200
            it[5] = 0x96.toByte() // HRV 150
            it[7] = 0xF0.toByte() // RR 240 / 8 = 30.0
        }
        assertEquals(0xF022d5bfL, r.counter)
        assertEquals(Instant.ofEpochSecond(0xF022d5bfL + 1_577_793_600L), r.date())
        assertEquals(200, r.heartRate)
        assertEquals(150, r.hrvRMSSD)
        assertEquals(150, r.measuredHRVRMSSD)
        assertEquals(30.0, r.measuredRespiratoryRate)
        assertEquals(98, r.spo2Percent)
    }

    @Test
    fun motionStillnessIsASpreadOfAtMostTwoReadUnsigned() {
        fun motion(vararg m: Int) = bulk(deepSleepRec) { b -> m.forEachIndexed { i, v -> b[10 + i] = v.toByte() } }
        assertTrue(motion(1, 1, 3, 1, 1).motionResolvesStillness, "spread 2 is still")
        assertFalse(motion(1, 1, 4, 1, 1).motionResolvesStillness, "spread 3 is movement")
        // 0x7f and 0x81 are 2 apart unsigned, but 254 apart if read as signed bytes.
        assertTrue(motion(0x7f, 0x81, 0x80, 0x7f, 0x81).motionResolvesStillness)
        val idle = bulk("0c0000000500" + "0c0001" + "0a" + "0101010101" + "00000000000000" + "00")
        assertFalse(idle.motionResolvesStillness, "the idle template never resolves stillness")
        assertEquals(28.0, ActivityPeriod.WORN_MIN_TEMPERATURE_C, "the wear-gate constant keeps resolving")
    }

    @Test
    fun hrvPoolingNeedsTwentyQuietEpochsPerSideAndSplitsAtNineMs() {
        fun activity(hrv: Int) = bulk(quietActivityRec) { it[5] = hrv.toByte() }
        fun sleep(hrv: Int) = bulk(deepSleepRec) { b -> for (i in 15 until 20) b[i] = 0; b[5] = hrv.toByte() }
        fun run(nAct: Int, act: Int, nSleep: Int, sv: Int) = List(nAct) { activity(act) } + List(nSleep) { sleep(sv) }

        assertEquals(BulkSleep.HRVPooling.AGREE, BulkSleep.hrvPooling(run(20, 50, 20, 41)), "shift 9 ms agrees")
        assertEquals(BulkSleep.HRVPooling.DISAGREE, BulkSleep.hrvPooling(run(20, 50, 20, 40)), "shift 10 ms disagrees")
        assertEquals(BulkSleep.HRVPooling.NO_EVIDENCE, BulkSleep.hrvPooling(run(19, 50, 20, 50)))
        assertEquals(BulkSleep.HRVPooling.NO_EVIDENCE, BulkSleep.hrvPooling(run(20, 50, 19, 50)))

        // The verdict gates only the recovered activity-epoch HRV; sleep-vitals HRV is never gated.
        val slice = listOf(activity(33), sleep(60))
        fun hrvValues(s: List<QuantitySample>) = s.filter { it.kind == MetricKind.HRV_SDNN }.map { it.value }
        assertEquals(listOf(33.0, 60.0), hrvValues(BulkSleep.samples(slice)), "no calibration leaves the gate inert")
        assertEquals(listOf(60.0), hrvValues(BulkSleep.samples(slice, verdict = BulkSleep.HRVPooling.DISAGREE)))
        assertEquals(listOf(60.0), hrvValues(BulkSleep.samples(slice, calibratedBy = run(20, 50, 20, 40))))
        assertEquals(listOf(33.0, 60.0), hrvValues(BulkSleep.samples(slice, calibratedBy = run(20, 50, 20, 41))))
    }
}
