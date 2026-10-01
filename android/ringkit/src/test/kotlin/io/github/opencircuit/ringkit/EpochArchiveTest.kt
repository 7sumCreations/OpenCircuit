package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rolling epoch archive: merge (dedup by counter, sort, prune by retention) and the raw
 * concatenated-bytes blob form.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/EpochArchiveTests.swift
 * (@ b1c2fdd), all 8 tests.
 */
class EpochArchiveTest {

    /**
     * :6-16 — a 23-byte record with a big-endian counter and a marker in `[4]` (the HR slot), so
     * the dedup winner is observable. Built on the raw byte path.
     */
    private fun rec(counter: Long, marker: Int = 0): BulkRecord {
        val b = ByteArray(BulkRecord.LENGTH)
        b[0] = ((counter ushr 24) and 0xFF).toByte()
        b[1] = ((counter ushr 16) and 0xFF).toByte()
        b[2] = ((counter ushr 8) and 0xFF).toByte()
        b[3] = (counter and 0xFF).toByte()
        b[4] = marker.toByte()
        return BulkRecord.of(b)!!
    }

    // :18
    @Test
    fun mergeDedupsByCounterIncomingWins() {
        val merged = EpochArchive.merge(existing = listOf(rec(100, marker = 1)), incoming = listOf(rec(100, marker = 2)))
        assertEquals(1, merged.size)
        assertEquals(2, merged.first().raw.u8(4)) // the fresher drain's copy wins
    }

    // :25
    @Test
    fun mergeSortsByCounter() {
        val merged = EpochArchive.merge(existing = listOf(rec(300)), incoming = listOf(rec(100), rec(200)))
        assertEquals(listOf(100L, 200L, 300L), merged.map { it.counter })
    }

    // :30
    @Test
    fun mergePrunesBeyondRetention() {
        // retention 1000 s; newest = 5000 → cutoff 4000. Anything below 4000 is dropped.
        val merged = EpochArchive.merge(
            existing = listOf(rec(0), rec(3999), rec(4000)),
            incoming = listOf(rec(5000)),
            retention = Duration.ofSeconds(1000),
        )
        assertEquals(listOf(4000L, 5000L), merged.map { it.counter })
    }

    // :38
    @Test
    fun retentionUnderflowGuardKeepsAll() {
        // newest (20) < retention (huge) → cutoff clamps to 0, nothing pruned.
        val merged = EpochArchive.merge(
            existing = listOf(rec(10), rec(20)),
            incoming = emptyList(),
            retention = Duration.ofHours(30),
        )
        assertEquals(listOf(10L, 20L), merged.map { it.counter })
    }

    // :45
    @Test
    fun encodeDecodeRoundTrip() {
        val records = listOf(rec(100, marker = 7), rec(250, marker = 9))
        val decoded = EpochArchive.decode(EpochArchive.encode(records))
        assertEquals(listOf(100L, 250L), decoded.map { it.counter })
        assertEquals(listOf(7, 9), decoded.map { it.raw.u8(4) })
    }

    // :52
    @Test
    fun decodeDropsTrailingPartialChunk() {
        val blob = EpochArchive.encode(listOf(rec(100), rec(200))) + bytes(0xde, 0xad, 0xbe, 0xef, 0x01) // 5 stray bytes
        assertEquals(listOf(100L, 200L), EpochArchive.decode(blob).map { it.counter })
    }

    // :58
    @Test
    fun emptyInputs() {
        assertTrue(EpochArchive.merge(existing = emptyList(), incoming = emptyList()).isEmpty())
        assertTrue(EpochArchive.encode(emptyList()).isEmpty())
        assertTrue(EpochArchive.decode(ByteArray(0)).isEmpty())
    }

    // :65 — stitching shape: a night drained in two disjoint slices reassembles into one ordered series.
    @Test
    fun twoDisjointSlicesReassemble() {
        val early = listOf(rec(0), rec(150), rec(300))
        val late = listOf(rec(450), rec(600))
        val night = EpochArchive.merge(existing = early, incoming = late, retention = Duration.ofHours(30))
        assertEquals(listOf(0L, 150L, 300L, 450L, 600L), night.map { it.counter })
    }

    // Kotlin-only (D-44): upstream anchors the retention window on the single largest counter, so one
    // garbage record with a far-future counter (a 1-byte XOR trailer lets ~1 in 256 garbage frames
    // through) prunes every genuine record. `notAfter` bounds the anchor.
    @Test
    fun aFarFutureRecordCannotPruneGenuineHistoryWhenBounded() {
        val night = 400_000_000L
        val genuine = listOf(rec(night), rec(night + 60), rec(night + 120))
        val bogus = rec(0xF000_0000L)
        val notAfter = Command.SYNC_EPOCH.let { Instant.ofEpochSecond(it + night + 3_600) }

        val bounded = EpochArchive.merge(existing = genuine, incoming = listOf(bogus), notAfter = notAfter)
        assertEquals(genuine.map { it.counter }, bounded.map { it.counter })

        // The unbounded default is upstream's behaviour: the bogus counter wins the anchor.
        val unbounded = EpochArchive.merge(existing = genuine, incoming = listOf(bogus))
        assertEquals(listOf(0xF000_0000L), unbounded.map { it.counter })
    }

    @Test
    fun notAfterKeepsARecordExactlyAtTheBound() {
        val at = 400_000_000L
        val bound = Instant.ofEpochSecond(Command.SYNC_EPOCH + at)
        assertEquals(listOf(at), EpochArchive.merge(listOf(rec(at)), emptyList(), notAfter = bound).map { it.counter })
        assertTrue(EpochArchive.merge(listOf(rec(at + 1)), emptyList(), notAfter = bound).isEmpty())
    }
}
