package io.github.opencircuit.store

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
 * the seven store tests (`:77`, `:94`, `:100`, `:113`, `:123`, `:243`, `:255`); the seven
 * health-store tests between them (`:137-224`) belong with the health-store writer. A "relaunch" is a
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
