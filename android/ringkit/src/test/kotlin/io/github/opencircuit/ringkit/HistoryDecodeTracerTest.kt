package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Kotlin-only end-to-end check of the history path: one real `0x4c` page → records → health
 * samples → the per-metric sync cursor. Each stage has its own ported tests; this one proves the
 * stages agree at their seams, and that a page the ring delivers twice (a resumed or overlapping
 * drain) never produces a duplicate sample.
 *
 * The page is upstream's real, XOR-valid page from the 2026-06-13 overnight sync
 * (RingKitVerify/main.swift:307-310 @ b1c2fdd). The expected samples were decoded by hand from its
 * bytes, per ../docs/PROTOCOL.md §5.3, not by running the code under test.
 */
class HistoryDecodeTracerTest {

    private val realPage = "4c00260c22a16b55210a7d120a010101010100000402400400000c22a20155000300" +
        "120a010101010100003c00000d01200c22a297540001005f0a010101010100001101b00f" +
        "00440c22a32d6027077b120a010101010100402501c02235a00c22a3c351260577120b01" +
        "0101010108a01000000401300c22a459502d0378120a01010101010160200000040ff0cc"

    /** A record's wall-clock time: its 32-bit counter plus the sync epoch, 2019-12-31 12:00:00 UTC. */
    private fun at(counter: Long) = Instant.ofEpochSecond(1_577_793_600L + counter)

    private fun s(kind: MetricKind, counter: Long, value: Double) = QuantitySample(kind = kind, start = at(counter), value = value)

    /**
     * The page's six epochs. Five are `0x12` activity epochs whose `[15:20]` motion tail is not
     * zero, so they carry HR and (where `[7]` > 0) RR but no HRV. The third is a sleep-vitals epoch
     * (`[8]` = 95 % SpO2) with no HRV or RR byte set.
     */
    private val expected = listOf(
        s(MetricKind.HEART_RATE, 0x0c22a16b, 85.0), s(MetricKind.RESPIRATORY_RATE, 0x0c22a16b, 125 / 8.0),
        s(MetricKind.HEART_RATE, 0x0c22a201, 85.0),
        s(MetricKind.HEART_RATE, 0x0c22a297, 84.0), s(MetricKind.SPO2, 0x0c22a297, 0.95),
        s(MetricKind.HEART_RATE, 0x0c22a32d, 96.0), s(MetricKind.RESPIRATORY_RATE, 0x0c22a32d, 123 / 8.0),
        s(MetricKind.HEART_RATE, 0x0c22a3c3, 81.0), s(MetricKind.RESPIRATORY_RATE, 0x0c22a3c3, 119 / 8.0),
        s(MetricKind.HEART_RATE, 0x0c22a459, 80.0), s(MetricKind.RESPIRATORY_RATE, 0x0c22a459, 120 / 8.0),
    )

    @Test
    fun aRealPageFlowsToFreshSamplesOnceAndARedeliveredPageAddsNothing() {
        val cursor = SyncCursor()

        val first = cursor.selectNew(BulkSleep.samples(BulkSleep.recordsFromPage(hex(realPage))))

        assertEquals(expected, first)
        assertEquals(at(0x0c22a459), cursor.last(MetricKind.HEART_RATE))
        assertEquals(at(0x0c22a459), cursor.last(MetricKind.RESPIRATORY_RATE))
        assertEquals(at(0x0c22a297), cursor.last(MetricKind.SPO2), "each metric keeps its own watermark")

        // The ring delivers the same page again: nothing is fresh, and the cursor does not move.
        val snapshot = cursor.copy()
        val second = cursor.selectNew(BulkSleep.samples(BulkSleep.recordsFromPage(hex(realPage))))

        assertTrue(second.isEmpty(), "a re-delivered page must not produce a duplicate sample: $second")
        assertEquals(snapshot, cursor)
    }

    @Test
    fun anOverlappingDeliveryYieldsOnlyTheEpochsNewerThanTheCursor() {
        val cursor = SyncCursor()
        cursor.selectNew(BulkSleep.samples(BulkSleep.recordsFromPage(hex(realPage))))

        // The last two records of that page again, followed by one later epoch: the real deep-sleep
        // record (HR 68, HRV 77, RR 15.25, SpO2 98) upstream's tests use. Raw record stream, no page.
        val overlap = "0c22a3c351260577120b010101010108a0100000040130" +
            "0c22a459502d0378120a01010101010160200000040ff0" +
            "0c22d5bf444d057a620a01010101012aa0000090000004"
        val fresh = cursor.selectNew(BulkSleep.samples(BulkSleep.recordsFromStream(hex(overlap))))

        assertEquals(
            listOf(
                s(MetricKind.HEART_RATE, 0x0c22d5bf, 68.0),
                s(MetricKind.HRV_SDNN, 0x0c22d5bf, 77.0),
                s(MetricKind.SPO2, 0x0c22d5bf, 0.98),
                s(MetricKind.RESPIRATORY_RATE, 0x0c22d5bf, 15.25),
            ),
            fresh,
        )
        assertEquals(at(0x0c22d5bf), cursor.last(MetricKind.SPO2))
        assertEquals(at(0x0c22d5bf), cursor.last(MetricKind.HRV_SDNN))
    }
}
