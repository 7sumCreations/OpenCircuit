package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The retention buffer for `0x4c` pages that arrive with no drain open. The invariant: a page we
 * ACK is a page we KEEP — pages that arrive before a drain opens union with the drain's own pages
 * into a hole-free night.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/UnattributedPageBufferTests.swift
 * (@ b1c2fdd): the 6 tests of `UnattributedPageBufferTests`. The same upstream file also holds
 * `OpenedOntoLiveStreamTests` (2 tests of `HistoryChannelTrace.openedOntoLiveStream`, `:208-229`);
 * they are [OpenedOntoLiveStreamTest] at the end of this file.
 *
 * The night is built from REAL record bodies (the 2026-06-13 FR02.018 page) with only the 4-byte
 * counters re-stamped onto the tester's timeline, and every page is sealed with a test-side XOR,
 * never the production `Frame.xorTrailer`.
 */
class UnattributedPageBufferTest {

    // :44-47 — a real, XOR-valid 0x4c page: 6 × 23-byte records.
    private val realPage = "4c00260c22a16b55210a7d120a010101010100000402400400000c22a20155000300" +
        "120a010101010100003c00000d01200c22a297540001005f0a010101010100001101b00f" +
        "00440c22a32d6027077b120a010101010100402501c02235a00c22a3c351260577120b01" +
        "0101010108a01000000401300c22a459502d0378120a01010101010160200000040ff0cc"

    private val epochSeconds = BulkRecord.EPOCH_SECONDS.toLong()

    // :49-51
    private val realRecordBodies: List<ByteArray> get() = BulkSleep.recordsFromPage(hex(realPage)).map { it.raw }

    private fun testXor(b: ByteArray): Int = b.fold(0) { acc, x -> acc xor (x.toInt() and 0xFF) }

    /** :54-61 — re-stamp a real record body with [counter] (big-endian, bytes [0:4]). */
    private fun record(template: ByteArray, counter: Long): ByteArray {
        val r = template.copyOf()
        r[0] = ((counter ushr 24) and 0xFF).toByte()
        r[1] = ((counter ushr 16) and 0xFF).toByte()
        r[2] = ((counter ushr 8) and 0xFF).toByte()
        r[3] = (counter and 0xFF).toByte()
        return r
    }

    /** :65-70 — `[0x4c][remaining hi][remaining lo]` + N × 23 + XOR trailer. */
    private fun page(records: List<ByteArray>, remaining: Int): ByteArray {
        var f = bytes(0x4C, (remaining ushr 8) and 0xFF, remaining and 0xFF)
        for (r in records) f += r
        return f + bytes(testXor(f))
    }

    /**
     * :74-92 — the Gen 2 night as the ring handed it over: 34 six-record pages + a 4-record
     * terminal page = 208 records at 150 s, first epoch 2026-08-04 00:15 ET (04:15 UTC).
     */
    private fun nightPages(): List<ByteArray> {
        val first = Instant.parse("2026-08-04T04:15:00Z")
        val base = first.epochSecond - Command.SYNC_EPOCH
        val bodies = realRecordBodies
        val all = (0 until 208).map { i -> record(bodies[i % bodies.size], base + i * epochSeconds) }
        val pages = mutableListOf<ByteArray>()
        var i = 0
        var remaining = 202
        while (i < 204) {
            pages += page(all.subList(i, i + 6), remaining)
            i += 6
            remaining -= 6
        }
        pages += page(all.subList(204, 208), 0) // terminal, 96 B
        return pages
    }

    // :96 — fixture sanity: a fixture that doesn't match the wire proves nothing.
    @Test
    fun fixtureMatchesTheWire() {
        val pages = nightPages()
        assertEquals(35, pages.size, "35 pages, as the ring sent")
        assertEquals(34, pages.count { it.size == 142 }, "34 × 142 B six-record pages")
        assertEquals(96, pages.last().size, "terminal page is 96 B = 4 records")
        val decoded = pages.flatMap { BulkSleep.recordsFromPage(it) }
        assertEquals(208, decoded.size, "every page really parses through Frame.parse's XOR check")

        // Give the trailer check teeth: corrupt a byte and require the real decoder to reject it.
        val corrupt = pages.first().copyOf()
        corrupt[10] = (corrupt[10].toInt() xor 0xFF).toByte()
        assertFalse(Frame.isValid(corrupt))
        assertTrue(
            BulkSleep.recordsFromPage(corrupt).isEmpty(),
            "a corrupted page must decode to nothing, not to garbage epochs",
        )
    }

    // :116 — THE regression: 29 pages arrive with no drain open, then a drain opens mid-stream.
    @Test
    fun streamThatBeganBeforeTheDrainIsFullyRetained() {
        val pages = nightPages()
        val buffer = UnattributedPageBuffer()
        val drainRecords = mutableListOf<BulkRecord>()

        for (p in pages.take(29)) buffer.retain(BulkSleep.recordsFromPage(p))
        assertEquals(174, buffer.count, "the 174 records the shipped code lost")
        assertEquals(29, buffer.pages)

        val adopted = buffer.drain()
        assertEquals(174, adopted.size)
        assertTrue(buffer.isEmpty, "drain() resets the buffer")
        drainRecords += adopted

        for (p in pages.takeLast(6)) drainRecords += BulkSleep.recordsFromPage(p)

        // 1. Nothing was dropped.
        assertEquals(208, drainRecords.size, "all 208 epochs retained, not the 34 the shipped app kept")

        // 2. The archive has NO hole where the drain boundary was.
        val archive = EpochArchive.merge(existing = emptyList(), incoming = drainRecords)
        assertEquals(208, archive.size)
        val gaps = archive.zipWithNext { a, b -> b.counter - a.counter }
        assertEquals(epochSeconds, gaps.max(), "contiguous at exactly one epoch (150 s) across the drain boundary")

        // 3. The night still spans the full 8.62 h the ring measured.
        val span = archive.last().counter - archive.first().counter
        assertEquals(207 * epochSeconds, span)
        assertEquals(8.625, span / 3600.0, 0.001)
    }

    // :160 — the Gen 2 Air shape at the record level: 204 orphans + a 4-record drain = one night.
    @Test
    fun adoptedOnlyNightIsStillAWholeNight() {
        val pages = nightPages()
        val buffer = UnattributedPageBuffer()
        for (p in pages.take(34)) buffer.retain(BulkSleep.recordsFromPage(p))

        val adopted = buffer.drain()
        val ownWireRecords = BulkSleep.recordsFromPage(pages[34]) // the drain's own haul: 4
        assertEquals(4, ownWireRecords.size)

        val archive = EpochArchive.merge(existing = emptyList(), incoming = adopted + ownWireRecords)
        assertEquals(208, archive.size, "204 adopted + 4 pulled = the whole night")
        assertEquals(epochSeconds, archive.zipWithNext { a, b -> b.counter - a.counter }.max())
    }

    // :177
    @Test
    fun emptyPageIsANoOpAndNeverTripsTheCap() {
        val buffer = UnattributedPageBuffer(cap = 1)
        assertFalse(buffer.retain(emptyList()), "an empty page must not trip the cap")
        assertTrue(buffer.isEmpty)
        assertEquals(0, buffer.pages)
    }

    // :184
    @Test
    fun capSignalsBankNowAndNeverDrops() {
        val pages = nightPages()
        val buffer = UnattributedPageBuffer(cap = 12)
        var hitCap = false
        var retained = 0
        for (p in pages.take(3)) {
            val recs = BulkSleep.recordsFromPage(p)
            retained += recs.size
            if (buffer.retain(recs)) {
                hitCap = true
                break
            }
        }
        assertTrue(hitCap, "12-record cap trips on the second 6-record page")
        assertEquals(retained, buffer.count, "hitting the cap means BANK NOW — it must never discard what it holds")
        assertEquals(retained, buffer.drain().size)
    }

    // :200
    @Test
    fun drainIsIdempotentOnAnEmptyBuffer() {
        val buffer = UnattributedPageBuffer()
        assertTrue(buffer.drain().isEmpty())
        assertTrue(buffer.drain().isEmpty())
    }
}

/**
 * The diagnostic tell that identified both testers' bundles: a drain whose first observed frame is
 * a `0x4c` data page opened onto a stream already in flight.
 *
 * Port of upstream `OpenedOntoLiveStreamTests`, the second class in
 * ios/OpenCircuitKit/Tests/OpenCircuitKitTests/UnattributedPageBufferTests.swift:208-229
 * (@ b1c2fdd): both tests. Kept in this file, as upstream keeps it beside the buffer's tests.
 */
class OpenedOntoLiveStreamTest {

    // :211-215
    private fun trace(firstOpcode: Int?): HistoryChannelTrace {
        val t = HistoryChannelTrace(label = "sleep", channel = 0x00, startedAt = Instant.parse("2026-08-04T04:15:00Z"))
        t.firstOpcode = firstOpcode
        return t
    }

    // :217 — the drain's first frame was a DATA page: pages were in flight before the trace existed.
    @Test
    fun firstOpcode4CMeansWeOpenedOntoALiveStream() {
        assertTrue(trace(firstOpcode = 0x4C).openedOntoLiveStream)
    }

    // :223
    @Test
    fun healthyHandshakesAreNotFlagged() {
        assertFalse(trace(firstOpcode = 0x81).openedOntoLiveStream, "own auth challenge")
        assertFalse(trace(firstOpcode = 0x82).openedOntoLiveStream, "own sync-open ACK")
        assertFalse(trace(firstOpcode = 0x50).openedOntoLiveStream, "end marker")
        assertFalse(trace(firstOpcode = null).openedOntoLiveStream, "no frame seen at all")
    }
}
