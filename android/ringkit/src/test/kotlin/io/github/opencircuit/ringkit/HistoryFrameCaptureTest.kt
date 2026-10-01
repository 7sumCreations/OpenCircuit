package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The raw history-frame recorder: opcode filter, bounded buffer, summary and text report.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/HistoryFrameCaptureTests.swift
 * (@ b1c2fdd), 10 of 11 tests. `testCodableRoundTrip` (`:85`) waits for the stored form. Upstream
 * records with the wall-clock default `at: Date()`; here every frame is recorded at a fixed
 * instant, because the Kotlin API has no clock default.
 */
class HistoryFrameCaptureTest {

    private val t0: Instant = Instant.parse("2026-08-04T12:56:16Z")

    // MARK: - Opcode filtering

    // :8
    @Test
    fun capturesHistoryAndDescriptorOpcodes() {
        for (op in listOf(0x47, 0x4c, 0x4d, 0x50, 0x82, 0x10, 0x87, 0x4e)) {
            assertTrue(
                HistoryFrameCapture.shouldCapture(bytes(op, 0x00, 0x01)),
                "expected 0x${op.toString(16)} to be captured",
            )
        }
    }

    // :15
    @Test
    fun skipsLiveAndHeartbeatOpcodes() {
        assertFalse(HistoryFrameCapture.shouldCapture(bytes(0x15, 0x00))) // live sample
        assertFalse(HistoryFrameCapture.shouldCapture(bytes(0x11, 0x00))) // heartbeat
        assertFalse(HistoryFrameCapture.shouldCapture(bytes(0x81, 0x00))) // auth challenge
        assertFalse(HistoryFrameCapture.shouldCapture(bytes())) // empty
    }

    // :22
    @Test
    fun recordIfRelevantReturnsWhetherRecorded() {
        val cap = HistoryFrameCapture()
        assertTrue(cap.recordIfRelevant(bytes(0x4c, 0x00, 0xab), at = t0))
        assertEquals(1, cap.count)
        assertFalse(cap.recordIfRelevant(bytes(0x15, 0x00), at = t0)) // skipped opcode → not recorded
        assertEquals(1, cap.count)
    }

    // MARK: - Frame encoding

    // :32
    @Test
    fun frameHexAndOpcode() {
        val f = CapturedFrame(date = Instant.EPOCH, bytes = bytes(0x4c, 0x00, 0x0f, 0xff))
        assertEquals(0x4c, f.opcode)
        assertEquals(4, f.byteCount)
        assertEquals("4c 00 0f ff", f.hex)
    }

    // MARK: - Bounded buffer

    // :41
    @Test
    fun trimsToCapKeepingNewest() {
        val cap = HistoryFrameCapture()
        val overflow = HistoryFrameCapture.CAP + 50
        for (i in 0 until overflow) {
            // Vary the descriptor payload so the survivors can be identified.
            cap.recordIfRelevant(bytes(0x10, i and 0xff, (i shr 8) and 0xff), at = t0)
        }
        assertEquals(HistoryFrameCapture.CAP, cap.count)
        // The very first frame must have been dropped; the last must survive.
        val lastIndex = overflow - 1
        val expectedLastHex = String.format(java.util.Locale.ROOT, "10 %02x %02x", lastIndex and 0xff, (lastIndex shr 8) and 0xff)
        assertEquals(expectedLastHex, cap.frames.last().hex)
    }

    // :55
    @Test
    fun initTrimsOversizedInput() {
        val frames = (0 until HistoryFrameCapture.CAP + 10).map {
            CapturedFrame(date = Instant.ofEpochSecond(it.toLong()), bytes = bytes(0x4c, it and 0xff))
        }
        val cap = HistoryFrameCapture(frames = frames)
        assertEquals(HistoryFrameCapture.CAP, cap.count)
    }

    // :63
    @Test
    fun clearEmptiesBuffer() {
        val cap = HistoryFrameCapture()
        cap.recordIfRelevant(bytes(0x4c, 0x00), at = t0)
        cap.clear()
        assertEquals(0, cap.count)
    }

    // MARK: - Summary

    // :72
    @Test
    fun countsByOpcodeSortedAscending() {
        val cap = HistoryFrameCapture()
        cap.recordIfRelevant(bytes(0x4c, 0x01), at = t0)
        cap.recordIfRelevant(bytes(0x4c, 0x02), at = t0)
        cap.recordIfRelevant(bytes(0x47, 0x01), at = t0)
        cap.recordIfRelevant(bytes(0x50, 0x00), at = t0)
        val counts = cap.countsByOpcode()
        assertEquals(listOf(0x47, 0x4c, 0x50), counts.map { it.opcode })
        assertEquals(2, counts.firstOrNull { it.opcode == 0x4c }?.count)
    }

    // MARK: - Report

    // :96 — upstream's placeholder MAC, verbatim.
    @Test
    fun reportIncludesFirmwareGenerationAndFrames() {
        val cap = HistoryFrameCapture()
        cap.recordIfRelevant(bytes(0x4c, 0x00, 0xab, 0xcd), at = t0)
        val fw = FirmwareInfo(
            version = "FR05.001", modelName = "RingConn Gen 3",
            manufacturer = "RingConn", hardwareRevision = "3.0",
            mac = "AA:BB:CC:DD:EE:FF",
        )
        val report = cap.report(firmware = fw, generatedAt = t0)
        assertTrue(report.contains("FR05.001"))
        assertTrue(report.contains("Gen 3")) // FR05 prefix → GEN3 (recognized)
        assertTrue(report.contains(FirmwareInfo.PINNED_VERSION))
        assertTrue(report.contains("4c 00 ab cd")) // the raw frame hex
        assertTrue(report.contains("Frames captured: 1"))
    }

    // :110
    @Test
    fun reportHandlesEmptyCapture() {
        val report = HistoryFrameCapture().report(firmware = FirmwareInfo(), generatedAt = t0)
        assertTrue(report.contains("(none"))
        assertTrue(report.contains("(unread)")) // no DIS read yet
    }
}
