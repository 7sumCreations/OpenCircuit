package io.github.opencircuit.store.codec

import io.github.opencircuit.ringkit.RingActivityEventLedger
import io.github.opencircuit.ringkit.RingEventLog
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The activity-event ledger survives its stored form.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/RingEventLogTests.swift
 * (@ b1c2fdd) `testLedgerKeepsOnlyActivityMarkersDedupesAndPrunes`, its stored-form lines
 * `:102-103`; the rest of that test and of the file is in `:ringkit`'s `RingEventLogTest`. The
 * ledger is built the same way, from the ring's own `0x50` frames in upstream's 2026-09-27
 * diagnostics bundle (event markers only, no health values), decoded from their raw bytes.
 */
class RingEventLogStoredFormTest {

    private fun bytes(vararg b: Int): ByteArray = ByteArray(b.size) { b[it].toByte() }

    /** Upstream `:10-13`. */
    private val walkFrame = bytes(
        0x50, 0x00, 0x00,
        0x10, 0x0f, 0x0c, 0xad, 0xf5, 0x55,
        0x10, 0x0a, 0x0c, 0xad, 0xfc, 0xcb,
    )

    /** Upstream `:14-21`. */
    private val eveningFrame = bytes(
        0x50, 0x00, 0x00,
        0x15, 0x21, 0x0c, 0xac, 0xe3, 0x0a,
        0x15, 0x12, 0x0c, 0xac, 0xe3, 0x46,
        0x10, 0x0f, 0x0c, 0xac, 0xea, 0x53,
        0x10, 0x0a, 0x0c, 0xac, 0xed, 0x8c,
        0x10, 0x0f, 0x0c, 0xac, 0xf5, 0x27,
        0x10, 0x0a, 0x0c, 0xac, 0xf8, 0x42,
    )

    @Test
    fun ledgerKeepsOnlyActivityMarkersDedupesAndPrunes() {
        val ledger = RingActivityEventLedger()
        val now = Instant.parse("2026-09-27T15:30:00Z")
        val walk = assertNotNull(RingEventLog.decodeFrame(walkFrame))
        ledger.merge(walk, ring = "A", now = now)
        ledger.merge(walk, ring = "A", now = now)
        ledger.merge(assertNotNull(RingEventLog.decodeFrame(eveningFrame)), ring = "A", now = now)
        assertEquals(6, ledger.events["A"]?.size)
        ledger.merge(
            RingEventLog.Frame(hiddenCount = 0, events = emptyList()),
            ring = "A",
            now = now + RingActivityEventLedger.RETENTION - Duration.ofHours(1),
        )
        assertEquals(2, ledger.events["A"]?.size)
        val blob = RingActivityEventLedgerCodec.encode(ledger)
        assertEquals(ledger, readable(RingActivityEventLedgerCodec.decode(blob)))
    }
}
