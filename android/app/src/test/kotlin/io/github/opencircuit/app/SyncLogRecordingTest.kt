package io.github.opencircuit.app

import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.ringkit.HistoryChannelExitReason
import io.github.opencircuit.ringkit.HistoryChannelOutcome
import io.github.opencircuit.ringkit.SyncMeasurement
import io.github.opencircuit.store.SyncLog
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Each finished sync is written to the store's sync log, through the real session, the
 * ring-faithful fake and the real store: what each channel saw, the measurements and the verdicts.
 * The fake's timings are the ones `TwoChannelDrainTest` documents: Sync now at 1 000, the sleep
 * channel's `0x82` at 1 500, its pages at 2 700 and 5 300 (a 2 600 ms gap), its `0x50` at 7 100.
 */
class SyncLogRecordingTest {

    /** The all-day channel's end report is an event log holding one charge marker, `15 31 <0c22aae4>`. */
    private val chargingEnd = hex("50000015310c22aae4")

    private suspend fun TestScope.world(sleep: List<ByteArray>, allDay: List<ByteArray>) = syncWorld { scope, now ->
        RingFake(
            scope, now,
            mapOf(Command.SYNC_CHANNEL_SLEEP to sleep, Command.SYNC_CHANNEL_ALL_DAY to allDay),
            endOfHistoryBy = mapOf(Command.SYNC_CHANNEL_ALL_DAY to chargingEnd),
        )
    }

    private fun date(counter: Long): Instant = Instant.ofEpochSecond(Command.SYNC_EPOCH + counter)

    @Test
    fun aFinishedSyncIsLoggedWithWhatEachChannelSaw() = runTest {
        val w = world(HistoryTestPages.backlog(2), HistoryTestPages.allDayBacklog(2))
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            advanceTo(60_000)

            val read = SyncLog(w.db).read(TEST_RING_ID)
            val entry = read.entries.single()
            assertEquals(0, read.unreadable)
            assertEquals(listOf(entry), w.session.syncRecords.entries.value, "the session shows what the store keeps")

            assertEquals(SYNC_TEST_EPOCH.plusMillis(1_000), entry.startedAt)
            assertEquals("COMPLETE", entry.outcome)
            assertEquals(24, entry.recordsStored)
            assertEquals(0, entry.heldBack)
            assertEquals(emptyList(), entry.heldBackBy)
            assertNull(entry.firmware, "the fake reports no firmware")

            val sleep = entry.channels[0]
            assertEquals(listOf("sleep", "all-day"), entry.channels.map { it.label })
            assertEquals(listOf("82000082"), sleep.syncAcks)
            assertEquals(HistoryChannelOutcome.COMPLETE, sleep.verdict)
            assertEquals(2, sleep.pages4c)
            assertEquals(12, sleep.records)
            assertEquals(HistoryTestPages.counters(0).first(), sleep.firstCounter)
            assertEquals(HistoryTestPages.counters(1).last(), sleep.lastCounter)
            assertEquals(6, sleep.firstCountdown)
            assertEquals(0, sleep.lastCountdown)
            assertEquals(HistoryChannelExitReason.END_MARKER, sleep.exitReason)
            assertEquals(true, sleep.endSeen)
            assertEquals(1, sleep.rounds)
            assertEquals(6_100L, sleep.durationMillis, "open at 1 000, end report at 7 100")
            assertEquals(2_600L, sleep.pageGapP50Millis)
            assertEquals(2_600L, sleep.pageGapMaxMillis)
            assertEquals(0L, sleep.ackLatencyP50Millis, "the in-memory store answers at once on virtual time")
            assertEquals(0, sleep.reoffers)
            assertEquals(SYNC_TEST_EPOCH.plusMillis(7_100), sleep.drainedThrough, "a complete channel is drained through its end")
            assertEquals(SyncMeasurement.ContinuityKind.FIRST_SYNC, sleep.continuity?.kind)

            val counters = (0 until 2).flatMap { HistoryTestPages.counters(it) } + (0 until 2).flatMap { HistoryTestPages.allDayCounters(it) }
            assertEquals(date(counters.min()), entry.oldestRecord)
            assertEquals(date(counters.max()), entry.newestRecord)
            assertEquals(counters.groupingBy { date(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }.eachCount(), entry.recordsPerDay)
            assertEquals(listOf(date(0x0C22AAE4L)), entry.chargeMarkers)
            // 24 records over a span of 611 epochs: far sparser than a worn ring records.
            assertEquals(SyncMeasurement.CapacityKind.INCONCLUSIVE, entry.capacity?.kind)
            assertEquals(SyncMeasurement.InconclusiveReason.SPARSE, entry.capacity?.reason)
        } finally {
            w.db.close()
        }
    }

    @Test
    fun theNextSyncJoinsTheLastOneOrSaysItIsTheFirst() = runTest {
        // Three sleep pages; the link drops after the first is acknowledged (2 700) and before the
        // second is due (5 300). The next sync gets pages 2 and 3, whose first record is the one
        // due 150 s after the first sync's last.
        val w = world(HistoryTestPages.backlog(3), HistoryTestPages.allDayBacklog(1))
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(4_000)
            w.ring.dropLink()
            advanceTo(20_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            advanceTo(120_000)

            val entries = SyncLog(w.db).read(TEST_RING_ID).entries
            assertEquals(2, entries.size)
            assertEquals(listOf("PARTIAL", "COMPLETE"), entries.map { it.outcome })
            val second = entries[1]
            val sleep = second.channels.single { it.label == "sleep" }
            assertEquals(HistoryTestPages.counters(1).first(), sleep.firstCounter)
            assertEquals(SyncMeasurement.Continuity(SyncMeasurement.ContinuityKind.CONTIGUOUS, 0), sleep.continuity)
            // The all-day channel was never reached by the first sync: this is its first.
            assertEquals(SyncMeasurement.ContinuityKind.FIRST_SYNC, second.channels.single { it.label == "all-day" }.continuity?.kind)
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aLogThatCannotBeWrittenStillShowsTheSyncAndSaysWhy() = runTest {
        val w = world(HistoryTestPages.backlog(2), emptyList())
        try {
            w.store.failLogAppends = true
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            advanceTo(60_000)

            assertEquals(emptyList(), SyncLog(w.db).read(TEST_RING_ID).entries)
            assertEquals("12 records · complete", w.viewModel.uiState.value.ringData.lastSync, "kept in memory")
            assertEquals(1, w.logs.count { it.startsWith("The sync log could not be written") })
        } finally {
            w.db.close()
        }
    }
}
