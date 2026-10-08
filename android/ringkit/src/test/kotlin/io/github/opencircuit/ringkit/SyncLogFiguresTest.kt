package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The small figures a sync log line is made of: nearest-rank percentiles of the page gaps and the
 * acknowledgement latencies, and the charge markers in the ring's `0x50` event log (type `0x15`,
 * value `0x31`: the ring went onto its charger). Frames assembled byte by byte.
 */
class SyncLogFiguresTest {

    @Test
    fun percentilesAreNearestRank() {
        val values = listOf(40L, 10L, 30L, 20L)
        assertEquals(20L, SyncMeasurement.percentile(values, 50))
        assertEquals(40L, SyncMeasurement.percentile(values, 95))
        assertEquals(10L, SyncMeasurement.percentile(values, 1))
        assertEquals(40L, SyncMeasurement.percentile(values, 100))
        assertEquals(7L, SyncMeasurement.percentile(listOf(7L), 50))
        assertNull(SyncMeasurement.percentile(emptyList(), 50))
    }

    @Test
    fun theChargeMarkersOfAnEndReportAreItsChargingEntriesInTimeOrder() {
        // 50 00 00 | 15 31 <t2> | 10 0f <t1> | 15 31 <t1> | 15 12 <t2+600>, no trailer.
        val t1 = 0x0C22AAE4L
        val t2 = 0x0C22ACB5L
        val frame = byteArrayOf(0x50, 0x00, 0x00) +
            entry(0x15, 0x31, t2) + entry(0x10, 0x0f, t1) + entry(0x15, 0x31, t1) + entry(0x15, 0x12, t2 + 600)
        assertEquals(
            listOf(Instant.ofEpochSecond(Command.SYNC_EPOCH + t1), Instant.ofEpochSecond(Command.SYNC_EPOCH + t2)),
            SyncMeasurement.chargeMarkers(frame),
        )
    }

    @Test
    fun anEndReportWithNoChargeEntryOrThatCannotBeReadHasNone() {
        assertEquals(emptyList(), SyncMeasurement.chargeMarkers(byteArrayOf(0x50, 0x00, 0x00) + entry(0x10, 0x0f, 1000)))
        // Not a whole number of entries, and not a 0x50 frame at all.
        assertEquals(emptyList(), SyncMeasurement.chargeMarkers(byteArrayOf(0x50, 0x00, 0x00, 0x15, 0x31)))
        assertEquals(emptyList(), SyncMeasurement.chargeMarkers(byteArrayOf(0x4C, 0x00, 0x00) + entry(0x15, 0x31, 1000)))
    }

    private fun entry(type: Int, value: Int, cursor: Long): ByteArray = byteArrayOf(
        type.toByte(), value.toByte(),
        (cursor ushr 24).toByte(), (cursor ushr 16).toByte(), (cursor ushr 8).toByte(), cursor.toByte(),
    )
}
