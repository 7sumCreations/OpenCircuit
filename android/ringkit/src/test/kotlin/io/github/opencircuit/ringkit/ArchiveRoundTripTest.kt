package io.github.opencircuit.ringkit

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Kotlin-only end-to-end check of the archive: two drains hand over OVERLAPPING slices of the same
 * night → merge → encode to the stored blob → decode → the records come back deduped, sorted by
 * counter and byte-identical, and still decode to the same health samples. Each step has its own
 * ported tests; this proves they agree at their seams.
 *
 * The records are upstream's real, XOR-valid FR02.018 page (the same page the history-decode tests
 * use): 6 records whose counters step 150 s from 0x0c22a16b.
 */
class ArchiveRoundTripTest {

    private val realPage = "4c00260c22a16b55210a7d120a010101010100000402400400000c22a20155000300" +
        "120a010101010100003c00000d01200c22a297540001005f0a010101010100001101b00f" +
        "00440c22a32d6027077b120a010101010100402501c02235a00c22a3c351260577120b01" +
        "0101010108a01000000401300c22a459502d0378120a01010101010160200000040ff0cc"

    private val night = BulkSleep.recordsFromPage(hex(realPage))

    // Hand-read from the page: the six big-endian counters.
    private val expectedCounters = listOf(0x0c22a16bL, 0x0c22a201L, 0x0c22a297L, 0x0c22a32dL, 0x0c22a3c3L, 0x0c22a459L)

    @Test
    fun overlappingDrainsMergeEncodeAndDecodeToOneDedupedSortedNight() {
        assertEquals(expectedCounters, night.map { it.counter }, "fixture sanity")
        val firstDrain = night.subList(0, 4).reversed() // records 0-3, handed over newest first
        val secondDrain = night.subList(2, 6) // records 2-5: 2 and 3 arrive twice

        val merged = EpochArchive.merge(existing = firstDrain, incoming = secondDrain)
        val blob = EpochArchive.encode(merged)
        assertEquals(6 * BulkRecord.LENGTH, blob.size, "one 23-byte record per distinct epoch, no duplicates")

        val restored = EpochArchive.decode(blob)
        assertEquals(expectedCounters, restored.map { it.counter })
        restored.zip(night).forEach { (r, o) -> assertContentEquals(o.raw, r.raw) }
        assertEquals(BulkSleep.samples(night), BulkSleep.samples(restored), "the stored night decodes to the same samples")
    }

    @Test
    fun reMergingARedeliveredSliceIntoTheRestoredArchiveChangesNothing() {
        val restored = EpochArchive.decode(EpochArchive.encode(EpochArchive.merge(existing = night, incoming = emptyList())))
        val again = EpochArchive.merge(existing = restored, incoming = night.subList(1, 5))
        assertEquals(restored, again)
        assertContentEquals(EpochArchive.encode(restored), EpochArchive.encode(again))
    }
}
