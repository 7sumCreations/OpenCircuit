package io.github.opencircuit.store

import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pending sleep segments a committed drain publishes for the health-store write, kept per ring
 * as a pair. Port of upstream ios/OpenCircuitTests/SleepSegmentPersistenceTests.swift (@ b1c2fdd):
 * all fourteen: the seven store tests (`:77`, `:94`, `:100`, `:113`, `:123`, `:243`, `:255`) and
 * the seven health-store tests (`:137`, `:144`, `:151`, `:156`, `:170`, `:193`, `:224`) over
 * [LocalStore.pendingHealthSleep] and [LocalStore.markSleepWritten]. A "relaunch" is a
 * fresh [BlobStore] over the same database (upstream: a fresh `EpochArchiveStore` over the same
 * defaults suite). The flush's choice — staged when both are non-empty, else coarse — is upstream's
 * `ContentView.flushHealth()` selection, which its tests mirror inline; here it is
 * [BlobStore.PendingSleepSegments.preferred], so the tests assert the shipped rule.
 */
class SleepSegmentPersistenceTest {

    // 2026-06-28 22:00 UTC → 2026-06-29 06:30 UTC (upstream's fixture night).
    private val nightStart = Instant.ofEpochSecond(1_751_148_000)
    private val nightEnd = Instant.ofEpochSecond(1_751_178_600)
    private val now = Instant.parse("2026-06-29T07:00:00Z")

    /** Coarse two-segment summary the ring always emits. */
    private val coarseSegments = listOf(
        SleepSegment(nightStart, nightEnd, SleepStage.IN_BED),
        SleepSegment(nightStart, nightEnd, SleepStage.ASLEEP_CORE),
    )

    /** Staged three-segment hypnogram produced by the sleep-staging model. */
    private val stagedSegments: List<SleepSegment> = run {
        val midNight = nightStart.plusSeconds(14_400) // 02:00 UTC
        val lateNight = midNight.plusSeconds(7_200) // 04:00 UTC
        listOf(
            SleepSegment(nightStart, midNight, SleepStage.ASLEEP_DEEP),
            SleepSegment(midNight, lateNight, SleepStage.ASLEEP_REM),
            SleepSegment(lateNight, nightEnd, SleepStage.AWAKE),
        )
    }

    // EpochArchiveStore round-trip

    @Test
    fun testRoundTrip_coarseAndStagedSurviveReinstantiation() = runBlocking<Unit> { // :77
        withInMemoryStore { db ->
            BlobStore(db).savePendingSleepSegments("ring-A", coarseSegments, stagedSegments, now)

            // Fresh instance over the same database simulates app relaunch.
            val pending = BlobStore(db).loadPendingSleepSegments("ring-A")

            assertEquals(coarseSegments.size, pending.coarse.size)
            assertEquals(stagedSegments.size, pending.staged.size)
            assertEquals(coarseSegments.map { it.stage }, pending.coarse.map { it.stage })
            assertEquals(stagedSegments.map { it.stage }, pending.staged.map { it.stage })
            // Timestamps must survive the stored form (to the second at least).
            assertEquals(stagedSegments.map { it.end.epochSecond }, pending.staged.map { it.end.epochSecond })
        }
    }

    @Test
    fun testRoundTrip_emptyLoadBeforeAnySave() = runBlocking<Unit> { // :94
        withInMemoryStore { db ->
            val pending = BlobStore(db).loadPendingSleepSegments("ring-A")
            assertTrue(pending.coarse.isEmpty())
            assertTrue(pending.staged.isEmpty())
        }
    }

    @Test
    fun testRoundTrip_saveOverwritesPreviousNight() = runBlocking<Unit> { // :100
        withInMemoryStore { db ->
            val blobs = BlobStore(db)
            blobs.savePendingSleepSegments("ring-A", coarseSegments, stagedSegments, now)

            // Save a single-segment replacement (simulates a re-sync of a different night).
            val replacement = listOf(SleepSegment(nightEnd, nightEnd.plusSeconds(28_800), SleepStage.IN_BED))
            blobs.savePendingSleepSegments("ring-A", replacement, emptyList(), now)

            val pending = BlobStore(db).loadPendingSleepSegments("ring-A")
            assertEquals(1, pending.coarse.size)
            assertTrue(pending.staged.isEmpty())
        }
    }

    @Test
    fun testClear_removesPersistedSegments() = runBlocking<Unit> { // :113
        withInMemoryStore { db ->
            val blobs = BlobStore(db)
            blobs.savePendingSleepSegments("ring-A", coarseSegments, stagedSegments, now)
            blobs.clearPendingSleepSegments("ring-A")

            val pending = BlobStore(db).loadPendingSleepSegments("ring-A")
            assertTrue(pending.coarse.isEmpty())
            assertTrue(pending.staged.isEmpty())
        }
    }

    @Test
    fun testNamespace_twoRingsAreIsolated() = runBlocking<Unit> { // :123
        withInMemoryStore { db ->
            val blobs = BlobStore(db)
            blobs.savePendingSleepSegments("ring-A", coarseSegments, emptyList(), now)

            val a = blobs.loadPendingSleepSegments("ring-A")
            val b = blobs.loadPendingSleepSegments("ring-B")
            assertEquals(coarseSegments.size, a.coarse.size)
            assertTrue(b.coarse.isEmpty(), "ring-B must not inherit ring-A's data")
        }
    }

    // LocalStore cursor gate (standalone): the ingest-namespace `sleep` cursor, never an `hk:` row

    @Test
    fun testCursorGate_newNightOffersAllSegments() = runBlocking<Unit> { // :137
        withInMemoryStore { db ->
            val pending = LocalStore(db).pendingHealthSleep(stagedSegments)
            // The cursor starts with no row — every segment is new.
            assertEquals(stagedSegments.size, pending.size)
        }
    }

    @Test
    fun testCursorGate_alreadyWrittenNightIsSkipped() = runBlocking<Unit> { // :144
        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.markSleepWritten(stagedSegments)
            assertTrue(store.pendingHealthSleep(stagedSegments).isEmpty(), "cursor past the night's max-end — nothing should be re-offered")
        }
    }

    @Test
    fun testCursorGate_emptySegmentsReturnEmpty() = runBlocking<Unit> { // :151
        withInMemoryStore { db ->
            assertTrue(LocalStore(db).pendingHealthSleep(emptyList()).isEmpty())
        }
    }

    @Test
    fun testCursorGate_partialNightFiltersByEnd() = runBlocking<Unit> { // :156
        withInMemoryStore { db ->
            // Cursor sits halfway through the night — only segments ending AFTER the watermark surface.
            val store = LocalStore(db)
            val midNight = nightStart.plusSeconds(14_400)
            val firstHalf = SleepSegment(nightStart, midNight, SleepStage.ASLEEP_DEEP)
            val secondHalf = SleepSegment(midNight, nightEnd, SleepStage.ASLEEP_REM)
            store.markSleepWritten(listOf(firstHalf)) // advance cursor to midNight

            val pending = store.pendingHealthSleep(listOf(firstHalf, secondHalf))
            assertEquals(1, pending.size)
            assertEquals(SleepStage.ASLEEP_REM, pending.first().stage)
            assertEquals(midNight, pending.first().start)
        }
    }

    @Test
    fun testCursorGate_clipsCrossingSegmentInsteadOfDuplicatingPrefix() = runBlocking<Unit> { // :170
        withInMemoryStore { db ->
            val store = LocalStore(db)
            val firstEnd = nightStart.plusSeconds(3_600)
            store.markSleepWritten(listOf(SleepSegment(nightStart, firstEnd, SleepStage.ASLEEP_CORE)))

            val pending = store.pendingHealthSleep(listOf(SleepSegment(nightStart, firstEnd.plusSeconds(3_600), SleepStage.ASLEEP_CORE)))
            assertEquals(listOf(SleepSegment(firstEnd, firstEnd.plusSeconds(3_600), SleepStage.ASLEEP_CORE)), pending)
        }
    }

    // Full stranded-sleep simulation

    /**
     * The complete bug-then-fix path: a sync publishes the pair; the session is torn down (a fresh
     * [BlobStore] over the same database); the next flush offers the persisted segments through the
     * cursor gate, marks them written and clears the pair; a second flush finds nothing.
     */
    @Test
    fun testFullStrandedSleep_segmentsSurviveSessionTeardownAndWriteExactlyOnce() = runBlocking<Unit> { // :193
        withInMemoryStore { db ->
            val store = LocalStore(db)

            // Phase 1: drain committed → segments persisted.
            BlobStore(db).savePendingSleepSegments("ring-A", coarseSegments, stagedSegments, now)

            // Phase 2: session torn down — a fresh instance over the same database.
            val persisted = BlobStore(db).loadPendingSleepSegments("ring-A").staged
            assertTrue(persisted.isNotEmpty(), "staged segments must survive a simulated relaunch")
            assertEquals(stagedSegments.size, persisted.size)

            // Phase 3a: the flush's fallback — segments offered to the health store.
            val pending = store.pendingHealthSleep(persisted)
            assertTrue(pending.isNotEmpty(), "persisted segments must pass the cursor gate")

            // Phase 3b: confirmed write → advance cursor + clear the pair.
            store.markSleepWritten(pending)
            BlobStore(db).clearPendingSleepSegments("ring-A")

            // Phase 4: second flush — cursor blocks a re-write; the pair is already empty.
            assertTrue(BlobStore(db).loadPendingSleepSegments("ring-A").staged.isEmpty(), "archive must be empty after the successful write")
            assertTrue(store.pendingHealthSleep(persisted).isEmpty(), "cursor must block a second write of the same night")
        }
    }

    /** A failed health-store write (markSleepWritten never called) does not lose the night: it is offered again. */
    @Test
    fun testStrandedSleep_failedHealthKitWriteBackfillsOnRetry() = runBlocking<Unit> { // :224
        withInMemoryStore { db ->
            val store = LocalStore(db)
            BlobStore(db).savePendingSleepSegments("ring-A", coarseSegments, stagedSegments, now)
            val persisted = BlobStore(db).loadPendingSleepSegments("ring-A").staged

            // First attempt: read pending but do NOT mark written (a failed save).
            val firstAttempt = store.pendingHealthSleep(persisted)
            assertTrue(firstAttempt.isNotEmpty())

            // Second attempt: the same segments are still offered — the cursor never moved.
            val secondAttempt = store.pendingHealthSleep(persisted)
            assertEquals(firstAttempt.size, secondAttempt.size, "backfill must re-offer the same night after a failed write")
        }
    }

    // Kotlin-only: the sleep cursor's namespace and direction

    @Test
    fun theSleepMarkIsTheIngestNamespaceSleepRowAndNeverMovesBack() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.forceSleepCursorAtLeast(nightEnd)
            store.forceSleepCursorAtLeast(nightStart)

            assertEquals(nightEnd, store.loadCursor().last(MetricKind.SLEEP))
            assertEquals(listOf("sleep|${nightEnd.toEpochMilli()}"), db.queryRaw("SELECT kind_raw, last FROM stored_cursor"))
            // An `hk:sleep` row is not the mark: a night under it is still offered.
            db.execRaw("DELETE FROM stored_cursor")
            db.execRaw("INSERT INTO stored_cursor (kind_raw, last) VALUES ('hk:sleep', ${nightEnd.toEpochMilli()})")
            assertEquals(stagedSegments.size, store.pendingHealthSleep(stagedSegments).size)
        }
    }

    /** When both coarse and staged segments are non-empty, the flush prefers staged (finer hypnogram). */
    @Test
    fun testStagedPreferredOverCoarseWhenBothNonEmpty() = runBlocking<Unit> { // :243
        withInMemoryStore { db ->
            BlobStore(db).savePendingSleepSegments("ring-A", coarseSegments, stagedSegments, now)

            val selected = BlobStore(db).loadPendingSleepSegments("ring-A").preferred
            assertEquals(stagedSegments.map { it.stage }, selected.map { it.stage }, "staged segments must be selected when both arrays are non-empty")
        }
    }

    /** When staged is empty (the staging model produced no output), coarse is the fallback. */
    @Test
    fun testCoarseFallbackWhenStagedEmpty() = runBlocking<Unit> { // :255
        withInMemoryStore { db ->
            BlobStore(db).savePendingSleepSegments("ring-A", coarseSegments, emptyList(), now)

            val selected = BlobStore(db).loadPendingSleepSegments("ring-A").preferred
            assertEquals(coarseSegments.map { it.stage }, selected.map { it.stage }, "coarse must be the fallback when staged is empty")
        }
    }

    // Kotlin-only: what the upstream tests leave open

    @Test
    fun aPassThatStagedNothingLeavesThePublishedPairStanding() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val blobs = BlobStore(db)
            blobs.savePendingSleepSegments("ring-A", coarseSegments, stagedSegments, now)

            blobs.savePendingSleepSegments("ring-A", emptyList(), emptyList(), now)

            val pending = blobs.loadPendingSleepSegments("ring-A")
            assertEquals(coarseSegments, pending.coarse)
            assertEquals(stagedSegments, pending.staged)
        }
    }

    @Test
    fun anUnreadableStoredListReadsAsNoneAndTheOtherHalfIsKept() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val blobs = BlobStore(db)
            blobs.savePendingSleepSegments("ring-A", coarseSegments, stagedSegments, now)
            db.kvDao().upsert(StoreKvEntity("sleep.pendingStagedSegments/ring-A", "not json", now))

            val pending = blobs.loadPendingSleepSegments("ring-A")
            assertEquals(coarseSegments, pending.coarse)
            assertTrue(pending.staged.isEmpty())
        }
    }
}
