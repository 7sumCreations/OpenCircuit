package io.github.opencircuit.ringkit

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Kotlin-only, end to end: real ring frames → [HistoryFrameCapture] → its text report →
 * [DiagnosticsFrameImport] → the same records the live decoder produces. And the text itself,
 * compared WHOLE against strings typed from upstream's source lines and its test vectors, so an
 * export written by either app reads back in the other. A contains-check would be blind to a
 * padding or rounding slip; every comparison here is the full text or the full line.
 *
 * Frames are real, all @ b1c2fdd: the `0x82` sync-open ACK (upstream RingKitVerify/main.swift:38),
 * the 2026-06-13 overnight `0x4c` page (RingKitVerify/main.swift:307-310) and the no-XOR `0x50`
 * cursor report (RingKitVerify/main.swift:141).
 */
class DiagnosticsRoundTripTest {

    private val syncAck = "82000082"
    private val realPage = "4c00260c22a16b55210a7d120a010101010100000402400400000c22a20155000300" +
        "120a010101010100003c00000d01200c22a297540001005f0a010101010100001101b00f" +
        "00440c22a32d6027077b120a010101010100402501c02235a00c22a3c351260577120b01" +
        "0101010108a01000000401300c22a459502d0378120a01010101010160200000040ff0cc"
    private val cursorReport = "500000120c22aae40c22acb5"
    private val livePoll = "15000102030405"

    /** Hex as upstream writes it, rendered test-side from the fixture string: "4c 00 26 …". */
    private fun spaced(h: String): String = h.chunked(2).joinToString(" ")

    private val firmware = FirmwareInfo(
        version = "FR02.018",
        modelName = "RingConn Gen2",
        manufacturer = "RingConn",
        hardwareRevision = null,
        mac = "AA:BB:CC:DD:EE:FF",
    )

    private fun capturedNight(): HistoryFrameCapture {
        val cap = HistoryFrameCapture()
        cap.recordIfRelevant(hex(syncAck), at = Instant.parse("2026-06-13T07:00:00Z"))
        cap.recordIfRelevant(hex(livePoll), at = Instant.parse("2026-06-13T07:00:00.500Z")) // not captured
        cap.recordIfRelevant(hex(realPage), at = Instant.parse("2026-06-13T07:00:01.250Z"))
        cap.recordIfRelevant(hex(cursorReport), at = Instant.parse("2026-06-13T07:00:02Z"))
        return cap
    }

    /**
     * The whole report, typed line by line from upstream S/Diagnostics/HistoryFrameCapture.swift
     * :102-135. `%3d` pads the byte counts to `  4b`, `142b`, ` 12b`; times drop their fraction.
     */
    @Test
    fun reportTextIsByteIdenticalToUpstreamsFormat() {
        val expected = listOf(
            "OpenCircuit — RingConn history-frame diagnostic capture",
            "Generated: 2026-06-13T07:05:00Z",
            "",
            "# Device",
            "Firmware:     FR02.018",
            "Generation:   Gen 2",
            "Pinned build: FR02.018",
            "Model:        RingConn Gen2",
            "Manufacturer: RingConn",
            "HW revision:  (unread)",
            "MAC:          AA:BB:CC:DD:EE:FF",
            "",
            "# Privacy",
            "These frames include the ring's overnight HR / HRV / SpO₂ history bytes and its",
            "MAC. They are not encrypted health records, but treat this file as personal data",
            "and only share it with someone you trust to decode it.",
            "",
            "# Summary",
            "Frames captured: 3 (cap 1500)",
            "  0x4c: 1",
            "  0x50: 1",
            "  0x82: 1",
            "",
            "# Frames (oldest → newest)",
            "2026-06-13T07:00:00Z  0x82    4b  82 00 00 82",
            "2026-06-13T07:00:01Z  0x4c  142b  " + spaced(realPage),
            "2026-06-13T07:00:02Z  0x50   12b  50 00 00 12 0c 22 aa e4 0c 22 ac b5",
        ).joinToString("\n")
        val report = capturedNight().report(firmware, generatedAt = Instant.parse("2026-06-13T07:05:00.750Z"))
        assertEquals(expected, report)
    }

    /** The empty report, whole: every unread field and the empty-capture line, as upstream writes them. */
    @Test
    fun emptyReportTextIsByteIdentical() {
        val expected = listOf(
            "OpenCircuit — RingConn history-frame diagnostic capture",
            "Generated: 2026-06-13T07:05:00Z",
            "",
            "# Device",
            "Firmware:     (unread)",
            "Generation:   Unknown",
            "Pinned build: FR02.018",
            "Model:        (unread)",
            "Manufacturer: (unread)",
            "HW revision:  (unread)",
            "MAC:          (unread)",
            "",
            "# Privacy",
            "These frames include the ring's overnight HR / HRV / SpO₂ history bytes and its",
            "MAC. They are not encrypted health records, but treat this file as personal data",
            "and only share it with someone you trust to decode it.",
            "",
            "# Summary",
            "Frames captured: 0 (cap 1500)",
            "",
            "# Frames (oldest → newest)",
            "(none — enable capture, then do an overnight wear + morning sync)",
        ).joinToString("\n")
        assertEquals(expected, HistoryFrameCapture().report(FirmwareInfo(), generatedAt = Instant.parse("2026-06-13T07:05:00Z")))
    }

    /**
     * A capture line is exactly what upstream's own import test renders for the same page
     * (T/DiagnosticsFrameImportTests.swift:27-30, `"\(stamp)  0x4c  142b  \(body)"`).
     */
    @Test
    fun captureLineMatchesUpstreamsImportVector() {
        val cap = HistoryFrameCapture()
        cap.recordIfRelevant(hex(realPage), at = Instant.parse("2026-08-04T12:56:16Z"))
        val line = cap.report(FirmwareInfo(), Instant.EPOCH).lines().last()
        assertEquals("2026-08-04T12:56:16Z  0x4c  142b  " + spaced(realPage), line)
    }

    /** Whole seconds, rounded down — Swift's formatter printed these on the pinned toolchain. */
    @Test
    fun timestampsDropTheFractionRoundingDown() {
        assertEquals("2026-08-12T12:56:16Z", HistoryFrameCapture.isoUtc(Instant.parse("2026-08-12T12:56:16.999Z")))
        assertEquals("1969-12-31T23:59:59Z", HistoryFrameCapture.isoUtc(Instant.ofEpochMilli(-500)))
    }

    /** The slice end: captured frames → report → import gives back exactly the live decoder's records. */
    @Test
    fun captureReportImportRoundTripsTheRecords() {
        val text = capturedNight().report(firmware, generatedAt = Instant.parse("2026-06-13T07:05:00Z"))
        val result = DiagnosticsFrameImport.recordsFromDiagnosticsText(text)
        val live = BulkSleep.recordsFromPage(hex(realPage))
        assertEquals(6, live.size)
        assertEquals(live.sortedBy { it.counter }, result.records)
        assertEquals(1, result.pagesSeen)
        assertEquals(0, result.pagesRejected)
        assertEquals(0, result.duplicateRecords)
        assertEquals(live.minOf { it.date() }..live.maxOf { it.date() }, result.coverage)
        assertEquals(DiagnosticsFrameImport.SourceRing(model = "RingConn Gen2", firmware = "FR02.018", macSuffix = "FF"), result.sourceRing)
        assertTrue(result.sourceRing.matches(result.sourceRing))
    }

    /** A sleep-vitals record (HR, HRV, RR×8, SpO2 present) at the instant [at]. */
    private fun recAt(at: Instant): BulkRecord {
        val counter = at.epochSecond - Command.SYNC_EPOCH
        val b = ByteArray(23)
        b[0] = (counter ushr 24).toByte(); b[1] = (counter ushr 16).toByte()
        b[2] = (counter ushr 8).toByte(); b[3] = counter.toByte()
        b[4] = 60; b[5] = 50; b[7] = 120; b[8] = 97
        return BulkRecord.of(b)!!
    }

    /**
     * The archive section, whole, in New York summer time. The span line's shape and the gap line
     * `  08-04 00:03 ──7.5h──> 08-04 07:30` are upstream's own vectors
     * (T/DiagnosticsFrameImportTests.swift:38-40); 7 h 27 min is 7.45 h, which prints as 7.5.
     */
    @Test
    fun archiveReportTextIsByteIdenticalInALocalZone() {
        val ny = ZoneId.of("America/New_York")
        val recs = listOf(
            recAt(Instant.parse("2026-08-04T04:00:00Z")), // 00:00 EDT
            recAt(Instant.parse("2026-08-04T04:03:00Z")), // 00:03 EDT
            recAt(Instant.parse("2026-08-04T11:30:00Z")), // 07:30 EDT
        )
        val expected = listOf(
            "# Epoch archive (drained 0x4c sleep/activity history)",
            "Epochs: 3   span: 08-04 00:00 → 08-04 07:30 (UTC-4)",
            "Layout: sleepV 3 · activity 0 · idle 0",
            "Vitals coverage: HR 3 · HRV 3 · SpO2 3",
            "Measured (#185): HRV 3 · RR 3",
            "",
            "Gaps > 6 min (a hole = history NEVER drained — the key sleep-loss signal):",
            "  08-04 00:03 ──7.5h──> 08-04 07:30",
        ).joinToString("\n")
        assertEquals(expected, EpochArchiveDiagnostics.report(recs.reversed(), zone = ny))
    }

    /**
     * Rounding: one decimal from the exact binary value, ties to even, as Swift's
     * `String(format: "%.1f")` printed 0.15 → 0.1, 0.25 → 0.2, 0.75 → 0.8. Java's `String.format`
     * gives 0.2 and 0.3 for the first two.
     */
    @Test
    fun gapHoursRoundLikeUpstream() {
        val e = Instant.ofEpochSecond(Command.SYNC_EPOCH) // 2019-12-31 12:00 UTC
        val recs = listOf(0L, 540L, 1440L, 4140L).map { recAt(e.plusSeconds(it)) } // gaps 0.15 h, 0.25 h, 0.75 h
        val gapLines = EpochArchiveDiagnostics.report(recs, zone = ZoneOffset.UTC).lines().takeLastWhile { it.startsWith("  ") }
        assertEquals(
            listOf(
                "  12-31 12:00 ──0.1h──> 12-31 12:09",
                "  12-31 12:09 ──0.2h──> 12-31 12:24",
                "  12-31 12:24 ──0.8h──> 12-31 13:09",
            ),
            gapLines,
        )
    }
}
