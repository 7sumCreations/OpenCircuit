package io.github.opencircuit.store

import io.github.opencircuit.ringkit.HistoryChannelExitReason
import io.github.opencircuit.ringkit.HistoryChannelOutcome
import io.github.opencircuit.ringkit.SyncMeasurement
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The sync log: one entry per finished sync, the last 50 kept per ring in `store_kv` (keys
 * `sync.log/<ring id>/<seq, 16 digits>`), so "Last synced", the last result and the measurements
 * survive a relaunch. Damaged rows are planted on the raw path (SQL), never through an append.
 */
class SyncLogTest {

    private val t0 = Instant.parse("2026-10-08T09:00:00Z")

    private fun entry(i: Int, outcome: String = "COMPLETE") = SyncLogEntry(
        startedAt = t0.plusSeconds(600L * i),
        finishedAt = t0.plusSeconds(600L * i + 42),
        outcome = outcome,
    )

    /** Every field set, nothing left at its default. */
    private val full = SyncLogEntry(
        startedAt = t0,
        finishedAt = t0.plusMillis(95_250),
        outcome = "PARTIAL",
        paused = true,
        firmware = "FR02.018",
        channels = listOf(
            SyncLogChannel(
                label = "sleep",
                channel = 0x00,
                verdict = HistoryChannelOutcome.COMPLETE,
                syncAcks = listOf("82000082"),
                openFallback = true,
                fallbackHelped = true,
                firstCountdown = 717,
                lastCountdown = 0,
                pages4c = 120,
                pages47 = 3,
                pages4d = 1,
                records = 718,
                firstCounter = 214_000_000L,
                lastCounter = 214_107_550L,
                exitReason = HistoryChannelExitReason.END_MARKER,
                endSeen = true,
                rounds = 2,
                durationMillis = 61_500,
                pageGapP50Millis = 1_200,
                pageGapMaxMillis = 3_100,
                ackLatencyP50Millis = 12,
                ackLatencyP95Millis = 31,
                reoffers = 1,
                statusReplies = 1,
                drainedThrough = t0.plusSeconds(61),
                continuity = SyncMeasurement.Continuity(SyncMeasurement.ContinuityKind.OVERLAP, -150),
            ),
            SyncLogChannel(label = "all-day", channel = 0x03, statusReplies = 2),
        ),
        recordsStored = 718,
        heldBack = 24,
        heldBackBy = listOf("all-day"),
        nightsStaged = 3,
        nightsWaiting = 1,
        droppedAfterBound = 2,
        pagesUnacknowledged = 1,
        undeliveredFrames = 4,
        oldestRecord = t0.minus(Duration.ofDays(3)),
        newestRecord = t0.minusSeconds(150),
        recordsPerDay = mapOf("2026-10-05" to 100, "2026-10-06" to 576),
        chargeMarkers = listOf(t0.minus(Duration.ofDays(2))),
        capacity = SyncMeasurement.CapacityVerdict(SyncMeasurement.CapacityKind.LOWER_BOUND, Duration.ofSeconds(259_050), null),
    )

    @Test
    fun anEntryWithEveryFieldReadsBackEqual() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val log = SyncLog(db)
            log.append("ring-A", full, t0)
            val read = log.read("ring-A")
            assertEquals(listOf(full), read.entries)
            assertEquals(0, read.unreadable)
        }
    }

    @Test
    fun anEntryWithEverythingAbsentReadsBackEqual() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val bare = SyncLogEntry(
                startedAt = t0,
                finishedAt = t0,
                outcome = "NOT_CONNECTED",
                channels = listOf(SyncLogChannel(label = "sleep", channel = 0)),
                capacity = SyncMeasurement.CapacityVerdict(SyncMeasurement.CapacityKind.INCONCLUSIVE, null, SyncMeasurement.InconclusiveReason.NO_RECORDS),
            )
            val log = SyncLog(db)
            log.append("ring-A", bare, t0)
            assertEquals(listOf(bare), log.read("ring-A").entries)
        }
    }

    @Test
    fun theLast50AreKeptOldestFirstAndNumbersAreNeverReused() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val log = SyncLog(db)
            val seqs = (1..51).map { log.append("ring-A", entry(it), t0) }
            assertEquals((1L..51L).toList(), seqs)

            val read = log.read("ring-A")
            assertEquals(50, read.entries.size)
            assertEquals((2..51).map { entry(it) }, read.entries)

            // The 52nd still gets a new number, and the oldest kept one goes.
            assertEquals(52L, log.append("ring-A", entry(52), t0))
            assertEquals((3..52).map { entry(it) }, log.read("ring-A").entries)
        }
    }

    @Test
    fun eachRingHasItsOwnLog() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val log = SyncLog(db)
            log.append("ring-B", entry(0, outcome = "PARTIAL"), t0)
            (1..60).forEach { log.append("ring-A", entry(it), t0) }

            assertEquals(listOf(entry(0, outcome = "PARTIAL")), log.read("ring-B").entries)
            assertEquals(50, log.read("ring-A").entries.size)
            assertEquals(emptyList(), log.read("ring-C").entries)
        }
    }

    @Test
    fun aDamagedRowIsCountedAndTheOthersStillRead() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val log = SyncLog(db)
            log.append("ring-A", entry(1), t0)
            db.execRaw("INSERT INTO store_kv(`key`, value, updated_at) VALUES ('sync.log/ring-A/0000000000000002', '{\"outcome\":7}', 1)")
            db.execRaw("INSERT INTO store_kv(`key`, value, updated_at) VALUES ('sync.log/ring-A/0000000000000003', 'not json', 1)")
            log.append("ring-A", entry(4), t0)

            val read = log.read("ring-A")
            assertEquals(listOf(entry(1), entry(4)), read.entries)
            assertEquals(2, read.unreadable)
        }
    }

    @Test
    fun anUnknownOutcomeWordIsKeptAsWritten() = runBlocking<Unit> {
        // The outcome is the app's word; the store keeps whatever it was given.
        withInMemoryStore { db ->
            val log = SyncLog(db)
            log.append("ring-A", entry(1, outcome = "SOMETHING_NEW"), t0)
            assertEquals("SOMETHING_NEW", log.read("ring-A").entries.single().outcome)
        }
    }

    @Test
    fun aRingIdMustBeNonEmptyWithNoSlash() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val log = SyncLog(db)
            assertFailsWith<IllegalArgumentException> { log.append("", entry(1), t0) }
            assertFailsWith<IllegalArgumentException> { log.read("a/b") }
        }
    }
}
