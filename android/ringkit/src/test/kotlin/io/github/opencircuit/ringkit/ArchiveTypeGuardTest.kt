package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.EpochRecordingHealth.Status
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Kotlin-only checks that the archive port keeps the guarantees Swift's value types gave for free:
 * a Swift `struct` copies on assignment, `[UInt8]` / `Data` copy too, and `UInt8` / `UInt32` cannot
 * go out of range. The Kotlin stand-ins (classes, `ByteArray`, `Int` / `Long`) need these spelled
 * out. Kept apart from the upstream-port test classes so their counts stay exact.
 */
class ArchiveTypeGuardTest {

    private fun rec(counter: Long, marker: Int = 0): BulkRecord {
        val b = ByteArray(BulkRecord.LENGTH)
        b[0] = ((counter ushr 24) and 0xFF).toByte()
        b[1] = ((counter ushr 16) and 0xFF).toByte()
        b[2] = ((counter ushr 8) and 0xFF).toByte()
        b[3] = (counter and 0xFF).toByte()
        b[4] = marker.toByte()
        return BulkRecord.of(b)!!
    }

    // UnattributedPageBuffer — upstream is a mutating struct

    @Test
    fun oneBufferServesRetainDrainRetainWithoutCarryingState() {
        val buffer = UnattributedPageBuffer()
        buffer.retain(listOf(rec(1), rec(2)))
        buffer.retain(listOf(rec(3)))
        assertEquals(listOf(1L, 2L, 3L), buffer.drain().map { it.counter })

        buffer.retain(listOf(rec(4)))
        assertEquals(1, buffer.pages, "the page count restarts after a drain")
        assertEquals(listOf(4L), buffer.drain().map { it.counter }, "only what arrived after the drain")
        assertTrue(buffer.isEmpty)
    }

    @Test
    fun bufferCopyIsIndependentAndReadsCannotReachItsStorage() {
        val a = UnattributedPageBuffer(cap = 10)
        a.retain(listOf(rec(1)))
        val b = a.copy()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())

        a.retain(listOf(rec(2)))
        assertEquals(1, b.count, "retaining into the original must not reach the copy")
        b.drain()
        assertEquals(2, a.count, "draining the copy must not reach the original")

        (a.records as MutableList<BulkRecord>).clear() // a reader writes to the list it was handed
        assertEquals(2, a.count)
        assertNotEquals(a, b)
    }

    // EpochArchive — UInt32 counters and retention, Data blob

    @Test
    fun mergeRejectsARetentionUpstreamCouldNotConvertAndAcceptsItsBounds() {
        assertFailsWith<IllegalArgumentException> { EpochArchive.merge(listOf(rec(1)), emptyList(), Duration.ofSeconds(-1)) }
        assertFailsWith<IllegalArgumentException> { EpochArchive.merge(listOf(rec(1)), emptyList(), Duration.ofSeconds(0x1_0000_0000L)) }
        val all = EpochArchive.merge(listOf(rec(0), rec(0xFFFF_FFFFL)), emptyList(), Duration.ofSeconds(0xFFFF_FFFFL))
        assertEquals(listOf(0L, 0xFFFF_FFFFL), all.map { it.counter })
        assertEquals(listOf(5L), EpochArchive.merge(listOf(rec(4), rec(5)), emptyList(), Duration.ZERO).map { it.counter })
    }

    @Test
    fun mergeOrdersCountersAboveTheSignedRangeAsUnsigned() {
        val high = listOf(rec(0xFFFF_FF00L), rec(0x8000_0000L), rec(0x7FFF_FFFFL))
        val kept = EpochArchive.merge(existing = high, incoming = emptyList(), retention = Duration.ofSeconds(0xFFFF_FFFFL))
        assertEquals(listOf(0x7FFF_FFFFL, 0x8000_0000L, 0xFFFF_FF00L), kept.map { it.counter })
        val pruned = EpochArchive.merge(existing = high, incoming = emptyList())
        assertEquals(listOf(0xFFFF_FF00L), pruned.map { it.counter }, "30 h before the newest keeps only the newest")
    }

    @Test
    fun encodeHandsOutAFreshBlobEachTime() {
        val records = listOf(rec(100, marker = 7))
        val blob = EpochArchive.encode(records)
        blob[4] = 0x55
        assertEquals(7, records[0].raw.u8(4), "writing to the blob must not reach the record")
        assertEquals(7, EpochArchive.encode(records).u8(4))
    }

    // StrandedEpochLedger — Set<UInt32>

    @Test
    fun ledgerAcceptsOnlyUnsigned32BitCountersAndNeverTouchesTheCallersSet() {
        assertFailsWith<IllegalArgumentException> { StrandedEpochLedger.mark(emptySet(), listOf(-1L)) }
        assertFailsWith<IllegalArgumentException> { StrandedEpochLedger.mark(emptySet(), listOf(0x1_0000_0000L)) }
        val callers = mutableSetOf(5L)
        val marked = StrandedEpochLedger.mark(callers, listOf(0L, 0xFFFF_FFFFL))
        assertEquals(setOf(0L, 5L, 0xFFFF_FFFFL), marked)
        assertEquals(setOf(5L), callers)
        StrandedEpochLedger.retire(marked, listOf(5L))
        assertEquals(setOf(0L, 5L, 0xFFFF_FFFFL), marked, "retire returns a new set")
    }

    @Test
    fun ledgerMarkAndRetireAcrossSuccessiveDrains() {
        var ledger = StrandedEpochLedger.mark(emptySet(), listOf(1000L, 1150L)) // drain 1 banks, dies
        ledger = StrandedEpochLedger.retire(ledger, listOf(1000L, 1150L)) // drain 2 commits them
        ledger = StrandedEpochLedger.mark(ledger, listOf(1300L)) // drain 2 banks another, dies
        assertEquals(setOf(1300L), ledger)
        val archive = listOf(rec(1000), rec(1150), rec(1300))
        assertEquals(listOf(1300L), StrandedEpochLedger.select(archive, ledger, emptySet()).map { it.counter })
    }

    // ActivityRecordPredicted — UInt8 fields, [UInt8] item5p0, failable decode

    @Test
    fun activityRecordReadsEveryByteUnsigned() {
        val r = assertNotNull(ActivityRecordPredicted.decode(ByteArray(BulkRecord.LENGTH) { 0xFF.toByte() }))
        assertEquals(Instant.ofEpochSecond(0xFFFF_FFFFL + Command.SYNC_EPOCH), r.date)
        assertEquals(65535, r.steps)
        assertEquals(255, r.deviceState)
        assertEquals(255, r.powerLevel)
        assertEquals(listOf(65535, 65535, 65535, 65535), listOf(r.temp1, r.temp2, r.temp3, r.temp4))
        assertEquals(listOf(255, 255, 255), r.item5p0)
        assertEquals(65535, r.activeSeconds)
        assertEquals(255, r.dailyActiveFlag)
        assertFalse(r.isPlausible)
        assertEquals(null, ActivityRecordPredicted.decode(ByteArray(BulkRecord.LENGTH + 1)))
    }

    @Test
    fun activityRecordKeepsItsByteRangesAndItsOwnCopyOfItem5p0() {
        val t = Instant.ofEpochSecond(1_700_000_000L)
        fun make(power: Int = 1, item: List<Int> = listOf(1, 2, 3)) =
            ActivityRecordPredicted(t, 0, 0, power, 0, 0, 0, 0, item, 0, 0)
        assertFailsWith<IllegalArgumentException> { make(power = 256) }
        assertFailsWith<IllegalArgumentException> { make(power = -1) }
        assertFailsWith<IllegalArgumentException> { make(item = listOf(1, 2)) }
        assertFailsWith<IllegalArgumentException> { make(item = listOf(1, 2, 256)) }

        val src = mutableListOf(1, 2, 3)
        val r = make(item = src)
        src[0] = 99 // the caller keeps writing to its own list
        assertEquals(listOf(1, 2, 3), r.item5p0)
        assertEquals(make(), r)
        assertEquals(make().hashCode(), r.hashCode())
    }

    // PPGTrend — Data payload

    @Test
    fun ppgTrendReadsHighBytesUnsignedAndLeavesThePayloadAlone() {
        val payload = ByteArray(38) { 0xFF.toByte() }
        assertTrue(PPGTrend.samples(payload).all { it == 1023 })
        assertContentEquals(ByteArray(38) { 0xFF.toByte() }, payload)
    }

    // ArchiveEvidenceCoverage — [UInt32] counters

    @Test
    fun evidenceCoverageHandlesCountersAboveTheSignedRange() {
        val archive = (0 until 5).map { rec(0xFFFF_F000L + it * 150L) }
        val report = ArchiveEvidenceCoverage.report(archive = archive, evidence = archive.take(2))
        assertEquals(listOf(0xFFFF_F12CL, 0xFFFF_F1C2L, 0xFFFF_F258L), report.missingFromEvidence)
        assertEquals(3 * 150, report.longestMissingRunSeconds)
    }

    // EpochRecordingHealth — Equatable enum with an associated value

    @Test
    fun recordingStatusComparesByValue() {
        val t = Instant.ofEpochSecond(1_786_900_000L)
        assertEquals(Status.Stalled(t), Status.Stalled(t))
        assertNotEquals<Status>(Status.Stalled(t), Status.Stalled(t.plusSeconds(1)))
        assertNotEquals<Status>(Status.Recording, Status.Unknown)
        assertFalse(Status.Unknown.isStalled)
    }
}
