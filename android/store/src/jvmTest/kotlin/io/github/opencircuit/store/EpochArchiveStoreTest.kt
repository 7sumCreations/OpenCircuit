package io.github.opencircuit.store

import androidx.room3.withWriteTransaction
import io.github.opencircuit.ringkit.BulkRecord
import io.github.opencircuit.ringkit.BulkSleep
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The stored epoch archive: a merge can only go through the store with a time bound, and the
 * archive, the stranded-epoch ledger and the samples commit together or not at all.
 *
 * Kotlin-only. Upstream's `EpochArchiveStore.merge` (ios/OpenCircuit/Store/EpochArchiveStore.swift
 * @ b1c2fdd) calls `EpochArchive.merge` with no bound, so one garbage record with a far-future
 * counter prunes every genuine record more than 30 h older (PORTING D-44); and it writes the
 * archive, the ledger and the samples as three separate commits.
 */
class EpochArchiveStoreTest {

    private val ring = "ring-A"
    private val now = Instant.parse("2026-10-03T00:00:00Z")

    // A real `0x4c` page (as in CaptureToStoreEndToEndTest), decoded from its raw bytes: six records.
    private val page = "4c00260c22a16b55210a7d120a010101010100000402400400000c22a20155000300" +
        "120a010101010100003c00000d01200c22a297540001005f0a010101010100001101b00f" +
        "00440c22a32d6027077b120a010101010100402501c02235a00c22a3c351260577120b01" +
        "0101010108a01000000401300c22a459502d0378120a01010101010160200000040ff0cc"
    private val genuine: List<BulkRecord> = BulkSleep.recordsFromPage(hex(page))

    /** A record whose counter reads as decades ahead: 23 raw bytes, counter 0xFFFFFF00. */
    private val farFuture: BulkRecord = assertNotNull(BulkRecord.of(hex("ffffff00" + "01".repeat(19))))

    @Test
    fun theFixturesAreSixRealPastEpochsAndOneFarFutureRecord() {
        assertEquals(6, genuine.size)
        assertTrue(genuine.all { it.date().isBefore(now) })
        assertTrue(farFuture.date().isAfter(now.plusSeconds(365L * 86_400)))
    }

    @Test
    fun aFarFutureRecordIsDroppedByTheBoundAndCannotPruneTheStoredHistory() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val blobs = BlobStore(db)
            val bound = genuine.maxOf { it.date() }.plusSeconds(3600)

            val first = blobs.mergeEpochArchive(ring, genuine.take(3), notAfter = bound, now = now)
            assertEquals(genuine.take(3), first.records)
            assertEquals(0, first.droppedAfterBound)

            val second = blobs.mergeEpochArchive(ring, genuine.drop(3) + farFuture, notAfter = bound, now = now)
            assertEquals(genuine, second.records)
            assertEquals(1, second.droppedAfterBound)
            assertEquals(genuine, blobs.loadEpochArchive(ring).records)
        }
    }

    @Test
    fun noOtherStoreCallCanWriteArchiveRecords() {
        // The bounded merge is the only public way records reach the stored archive: no method
        // takes records or a whole archive except `mergeEpochArchive`, whose bound is a non-null
        // `Instant` (the language enforces the rest).
        // A suspend function's last parameter is its continuation, which names the RETURN type: skipped.
        val writers = BlobStore::class.java.methods.filter { m ->
            m.genericParameterTypes.filterNot { it.typeName.startsWith("kotlin.coroutines.Continuation") }
                .any { t -> t.typeName.contains("BulkRecord") || t.typeName.contains("StoredEpochArchive") }
        }.map { it.name }
        assertEquals(listOf("mergeEpochArchive"), writers)
    }

    @Test
    fun theDrainFactsSurviveAMergeAndAMergeKeepsThem() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val blobs = BlobStore(db)
            val marks = EpochArchiveMarks(lastDrainAt = now, headAt = genuine.last().date(), unmovedDrains = 2, hrvPooling = BulkSleep.HRVPooling.DISAGREE)
            blobs.saveEpochArchiveMarks(ring, marks, now)
            blobs.mergeEpochArchive(ring, genuine, notAfter = now, now = now)
            assertEquals(StoredEpochArchive(genuine, marks), blobs.loadEpochArchive(ring))

            val later = marks.copy(unmovedDrains = 0)
            blobs.saveEpochArchiveMarks(ring, later, now)
            assertEquals(StoredEpochArchive(genuine, later), blobs.loadEpochArchive(ring))
        }
    }

    @Test
    fun anUnreadableStoredArchiveLoadsEmptyAndTheNextMergeReplacesIt() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.execRaw("INSERT INTO store_kv(`key`, value, updated_at) VALUES ('sleep.epochArchive/$ring', '{\"records\":\"ZZ\"}', 1)")
            val blobs = BlobStore(db)
            assertEquals(StoredEpochArchive.EMPTY, blobs.loadEpochArchive(ring))
            blobs.mergeEpochArchive(ring, genuine, notAfter = now, now = now)
            assertEquals(genuine, blobs.loadEpochArchive(ring).records)
        }
    }

    @Test
    fun blobsSavedInsideAnIngestTransactionThatThrowsAreNotThereAfterwards() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            val blobs = BlobStore(db)
            val samples = BulkSleep.samples(genuine)
            val counters = genuine.map { it.counter }.toSet()

            assertFails {
                db.withWriteTransaction {
                    blobs.mergeEpochArchive(ring, genuine, notAfter = now, now = now)
                    blobs.saveStrandedCounters(ring, counters, now)
                    store.ingest(samples, now, ZoneOffset.UTC)
                    error("injected failure after the ingest")
                }
            }

            assertEquals(StoredEpochArchive.EMPTY, blobs.loadEpochArchive(ring))
            assertEquals(emptySet(), blobs.loadStrandedCounters(ring))
            assertEquals(emptyList(), db.queryRaw("SELECT `key` FROM store_kv"))
            assertEquals(emptyList(), db.sampleDao().allSamples())

            // The same unit without the failure commits all three.
            db.withWriteTransaction {
                blobs.mergeEpochArchive(ring, genuine, notAfter = now, now = now)
                blobs.saveStrandedCounters(ring, counters, now)
                store.ingest(samples, now, ZoneOffset.UTC)
            }
            assertEquals(genuine, blobs.loadEpochArchive(ring).records)
            assertEquals(counters, blobs.loadStrandedCounters(ring))
            assertEquals(samples.size, db.sampleDao().allSamples().size)
        }
    }
}
