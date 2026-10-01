package io.github.opencircuit.ringkit

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Recovering epochs from a diagnostics export's raw-frame capture.
 *
 * The capture taps the inbound stream ABOVE the retention gate, so a page that was acked and then
 * discarded is still in the exported text while its epoch never reached the archive. The ring's
 * resume pointer advanced on the ack, so that file is the only remaining copy.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/DiagnosticsFrameImportTests.swift
 * (@ b1c2fdd), all 12 tests, with its redacted `··:··:··:··:··:AD` MAC strings verbatim. Shifted
 * pages are re-sealed with a test-side XOR (upstream calls the production `Frame.xorTrailer`).
 */
class DiagnosticsFrameImportTest {

    /** :21-24 — real, XOR-valid `0x4c` page (2026-06-13 overnight sync, FR02.018), 6 × 23-byte records. */
    private val realPage = "4c00260c22a16b55210a7d120a010101010100000402400400000c22a20155000300" +
        "120a010101010100003c00000d01200c22a297540001005f0a010101010100001101b00f" +
        "00440c22a32d6027077b120a010101010100402501c02235a00c22a3c351260577120b01" +
        "0101010108a01000000401300c22a459502d0378120a01010101010160200000040ff0cc"

    /** :27-30 — render bytes the way `HistoryFrameCapture` writes a line into the export. */
    private fun captureLine(bytes: ByteArray, stamp: String = "2026-08-04T12:56:16Z"): String {
        val body = bytes.joinToString(" ") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xFF) }
        return "$stamp  0x${String.format(Locale.ROOT, "%02x", bytes[0].toInt() and 0xFF)}  ${bytes.size}b  $body"
    }

    /** :32-44 */
    private fun report(frameLines: List<String>): String = (
        listOf(
            "OpenCircuit — diagnostics bundle",
            "Generated: 2026-08-04 08:58 (America/New_York)",
            "",
            "# Epoch archive (drained 0x4c sleep/activity history)",
            "Epochs: 308   span: 08-03 02:53 → 08-04 08:53 (UTC-4)",
            "Gaps > 6 min (a hole = history NEVER drained — the key sleep-loss signal):",
            "  08-04 00:03 ──7.5h──> 08-04 07:30",
            "",
            "# Frames (oldest → newest)",
        ) + frameLines
        ).joinToString("\n")

    private fun testXor(b: ByteArray): Int = b.fold(0) { acc, x -> acc xor (x.toInt() and 0xFF) }

    // MARK: - the recovery

    // :48
    @Test
    fun recoversRecordsFromACaptureLine() {
        val r = DiagnosticsFrameImport.recordsFromDiagnosticsText(report(listOf(captureLine(hex(realPage)))))
        assertEquals(1, r.pagesSeen)
        assertEquals(0, r.pagesRejected)
        assertEquals(6, r.records.size, "a 142 B page carries 6 × 23-byte records")
        assertNotNull(r.coverage)
    }

    // :56 — 0x47 / 0x50 / descriptor lines carry no epoch records, and the surrounding prose (which
    // contains "0x4c" in a heading) must not parse as a frame.
    @Test
    fun ignoresEverythingThatIsNotA4cFrame() {
        val noise = listOf(
            "2026-08-04T12:56:04Z  0x47  239b  47 00 00 0c 65 86 3a 02 9f 00 30 3c",
            "2026-08-04T12:57:02Z  0x50  171b  50 00 00 17 04 b9 04 b8 00 15 31 0c",
            "2026-08-04T12:56:03Z  0x10  19b  10 4f 03 00 00 37 01 4e 01 49 00 00 00 00 10 1f 1b ff b5",
            "# Epoch archive (drained 0x4c sleep/activity history)",
        )
        val r = DiagnosticsFrameImport.recordsFromDiagnosticsText(report(noise))
        assertEquals(0, r.pagesSeen)
        assertTrue(r.isEmpty)
    }

    // :70 — the ring re-sends a page whose ack it missed; the same epoch must not import twice.
    @Test
    fun recordsAreDedupedByCounter() {
        val line = captureLine(hex(realPage))
        val r = DiagnosticsFrameImport.recordsFromDiagnosticsText(report(listOf(line, line)))
        assertEquals(2, r.pagesSeen)
        assertEquals(6, r.records.size, "second copy adds nothing")
        assertEquals(6, r.duplicateRecords)
    }

    /** :82-95 — shift EVERY record's 4-byte BE counter by [delta] and re-seal the page. */
    private fun shiftingCounters(page: ByteArray, delta: Long): ByteArray {
        val out = page.copyOf()
        val count = (page.size - 4) / BulkRecord.LENGTH
        for (i in 0 until count) {
            val o = 3 + i * BulkRecord.LENGTH
            val c = ((out[o].toLong() and 0xFF) shl 24) or ((out[o + 1].toLong() and 0xFF) shl 16) or
                ((out[o + 2].toLong() and 0xFF) shl 8) or (out[o + 3].toLong() and 0xFF)
            val n = (c + delta) and 0xFFFF_FFFFL
            out[o] = (n ushr 24).toByte(); out[o + 1] = (n ushr 16).toByte()
            out[o + 2] = (n ushr 8).toByte(); out[o + 3] = n.toByte()
        }
        out[out.size - 1] = testXor(out.copyOf(out.size - 1)).toByte()
        return out
    }

    // :97 — deliberately fed NEWEST first: import order must not leak into the result.
    @Test
    fun recordsComeBackAscendingByCounter() {
        val page = hex(realPage)
        val later = shiftingCounters(page, 86_400) // a day forward — no counter collides
        val r = DiagnosticsFrameImport.recordsFromDiagnosticsText(report(listOf(captureLine(later), captureLine(page))))
        assertEquals(12, r.records.size, "two disjoint pages, nothing deduped")
        assertEquals(0, r.duplicateRecords)
        assertEquals(r.records.map { it.counter }.sorted(), r.records.map { it.counter })
    }

    // MARK: - it must not import garbage

    // :110
    @Test
    fun corruptPageIsRejectedNotImported() {
        val b = hex(realPage)
        b[10] = (b[10].toInt() xor 0xFF).toByte() // break the XOR trailer
        val r = DiagnosticsFrameImport.recordsFromDiagnosticsText(report(listOf(captureLine(b))))
        assertEquals(1, r.pagesSeen)
        assertEquals(1, r.pagesRejected)
        assertTrue(r.isEmpty, "decoding goes through the same XOR check as the live BLE path")
    }

    // :119 — a copy-paste that lost trailing bytes would otherwise decode a short body as if whole.
    @Test
    fun truncatedLineIsRejectedByTheDeclaredLength() {
        val full = captureLine(hex(realPage))
        val parts = full.split(" ")
        val truncated = parts.dropLast(3).joinToString(" ")
        val r = DiagnosticsFrameImport.recordsFromDiagnosticsText(report(listOf(truncated)))
        assertEquals(0, r.pagesSeen, "declared length no longer matches — not treated as a frame")
    }

    // :132 — a CRLF-converted export (mailed, Windows round-trip) must still parse.
    @Test
    fun crlfExportStillParses() {
        val lf = report(listOf(captureLine(hex(realPage))))
        val crlf = lf.replace("\n", "\r\n")
        val r = DiagnosticsFrameImport.recordsFromDiagnosticsText(crlf)
        assertEquals(1, r.pagesSeen)
        assertEquals(6, r.records.size)
    }

    // MARK: - ring identity (the archive is per-ring; a foreign merge is silent and irreversible)

    // :142
    @Test
    fun readsTheSourceRingFromTheDeviceHeader() {
        val text = listOf(
            "# Device",
            "Firmware:     FR02.018",
            "Generation:   Gen 2",
            "Model:        RingConn Gen2-03AD",
            "MAC:          ··:··:··:··:··:AD",
            "# Frames (oldest → newest)",
        ).joinToString("\n")
        val ring = DiagnosticsFrameImport.sourceRingFromDiagnosticsText(text)
        assertEquals("FR02.018", ring.firmware)
        assertEquals("RingConn Gen2-03AD", ring.model)
        assertEquals("AD", ring.macSuffix, "redaction keeps the last octet, which still identifies")
    }

    // :161 — TRAP: the redacted MAC's padding character IS the `·` field separator, so a parser
    // that splits on `·` before reading MAC shreds it.
    @Test
    fun combinedOneLineHeaderAndRedactedMacBothParse() {
        val text = listOf(
            "OpenCircuit — diagnostics bundle",
            "App: 1.0 (build 36)",
            "Firmware: FR02.018 · Generation: Gen 2 · Model: RingConn Gen2-03AD",
            "MAC: ··:··:··:··:··:AD",
            "# Frames (oldest → newest)",
        ).joinToString("\n")
        val ring = DiagnosticsFrameImport.sourceRingFromDiagnosticsText(text)
        assertEquals("FR02.018", ring.firmware, "must not swallow the rest of the line")
        assertEquals("RingConn Gen2-03AD", ring.model)
        assertEquals("AD", ring.macSuffix, "the `·` split must not shred the redacted MAC")
        assertTrue(ring.matches(ring), "a ring must match itself, or every import prompts")
    }

    // :176
    @Test
    fun differentRingsDoNotMatch() {
        val a = DiagnosticsFrameImport.SourceRing(model = "RingConn Gen2-03AD", firmware = "FR02.018", macSuffix = "AD")
        val b = DiagnosticsFrameImport.SourceRing(model = "RingConn Gen2 Air-2F9F", firmware = "FR04.009", macSuffix = "9F")
        assertFalse(a.matches(b))
        assertTrue(a.matches(a))
    }

    // :185 — conservative on purpose: an export with no device header must PROMPT, not merge blind.
    @Test
    fun unknownIdentityIsNotAMatch() {
        val known = DiagnosticsFrameImport.SourceRing(model = "RingConn Gen2-03AD", firmware = "FR02.018", macSuffix = "AD")
        assertFalse(known.matches(DiagnosticsFrameImport.SourceRing()))
        assertFalse(DiagnosticsFrameImport.SourceRing().matches(known))
    }

    // :193
    @Test
    fun emptyReportRecoversNothing() {
        val r = DiagnosticsFrameImport.recordsFromDiagnosticsText(report(emptyList()))
        assertTrue(r.isEmpty)
        assertEquals(0, r.pagesSeen)
    }
}
