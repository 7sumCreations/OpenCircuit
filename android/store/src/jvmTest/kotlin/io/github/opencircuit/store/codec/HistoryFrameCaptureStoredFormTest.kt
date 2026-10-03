package io.github.opencircuit.store.codec

import io.github.opencircuit.ringkit.HistoryFrameCapture
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The diagnostic frame capture survives its stored form.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/HistoryFrameCaptureTests.swift
 * (@ b1c2fdd) `testCodableRoundTrip` (`:85`); the file's other tests are in `:ringkit`'s
 * `HistoryFrameCaptureTest`. Upstream stamps each frame with `Date()`; the Kotlin capture takes
 * the instant, and the test passes whole-millisecond instants because the store keeps instants as
 * whole milliseconds.
 */
class HistoryFrameCaptureStoredFormTest {

    @Test
    fun codableRoundTrip() {
        val cap = HistoryFrameCapture()
        cap.recordIfRelevant(byteArrayOf(0x4c, 0x00, 0xde.toByte(), 0xad.toByte()), Instant.ofEpochMilli(1_759_000_000_123L))
        cap.recordIfRelevant(byteArrayOf(0x50, 0x00), Instant.ofEpochMilli(1_759_000_000_456L))
        val restored = readable(HistoryFrameCaptureCodec.decode(HistoryFrameCaptureCodec.encode(cap)))
        assertEquals(cap, restored)
        assertEquals(2, restored.count)
    }
}
