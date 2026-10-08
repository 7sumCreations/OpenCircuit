package io.github.opencircuit.app

import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.ringkit.HistoryChannelOutcome
import io.github.opencircuit.ringkit.HistoryCommitGate
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.store.LocalStore
import io.github.opencircuit.store.SleepStore
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A ring that waited a week: Sync now drains the kept seven-night backlog, and the store ends up
 * with one sleep summary per night — every complete night of the drain, oldest first, not only the
 * latest (PORTING D-269; upstream stages only the latest night, `RingSession.swift:4156` @ b1c2fdd,
 * and its 30 h archive has dropped the others by then). End to end on the real store: ring fake →
 * journal → acknowledgement → commit → archive, samples, summaries.
 */
class MultiNightCommitTest {

    private val everything = Instant.parse("2026-06-01T00:00:00Z") to Instant.parse("2026-07-01T00:00:00Z")

    @Test
    fun aWeekLongBacklogIsStoredAsOneSleepSummaryPerNight() = runTest {
        val w = syncWorld(BacklogPages.pages())
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(3_000_000)

            val report = assertNotNull(w.session.sync.state.value.last)
            assertEquals(HistoryChannelOutcome.COMPLETE, report.channels.first { it.channel == Command.SYNC_CHANNEL_SLEEP }.verdict)
            val commit = assertNotNull(report.commit)
            assertEquals(HistoryCommitGate.Decision.STAGE, commit.staging)
            assertEquals(7, commit.nightsStaged)
            assertEquals(0, commit.nightsWaiting)

            val nights = SleepStore(w.db).sleepSummaries(everything.first, everything.second)
            assertEquals(BacklogPages.WAKE_DAYS, nights.map { it.night }, "one summary per night, keyed to its wake day")
            assertTrue(nights.all { it.asleepMin > 0 && it.inBedEnd > it.inBedStart && it.hypnogram.isNotEmpty() })
            // Every night's heart rates are stored too (all days' samples, not only the last night's).
            val heartRates = LocalStore(w.db).samples(MetricKind.HEART_RATE, everything.first, everything.second)
            for (night in nights) {
                assertTrue(heartRates.any { it.start >= night.inBedStart && it.start <= night.inBedEnd }, "heart rates of the night keyed ${night.night}")
            }
            // The archive is marked staged through the last night, and still holds that night.
            val archive = w.blobs.loadEpochArchive(TEST_RING_ID)
            val stagedThrough = assertNotNull(archive.marks.stagedThrough)
            assertTrue(stagedThrough >= nights.last().inBedEnd.minus(Duration.ofMinutes(1)))
            assertTrue(archive.records.any { it.date() <= nights.last().inBedStart })
            assertEquals(emptyList(), w.journal.read(TEST_RING_ID).entries)
            // The newest night is the one published for the health-store write, both views together.
            val pending = w.blobs.loadPendingSleepSegments(TEST_RING_ID)
            assertEquals(nights.last().hypnogram, pending.staged)
            assertTrue(pending.coarse.isNotEmpty() && pending.coarse.all { it.start >= nights.last().inBedStart.minus(Duration.ofHours(1)) })
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aSleepChannelCutShortStagesNoNightAndKeepsEveryRecord() = runTest {
        // No 0x50 after the pages: the sleep channel goes quiet, PARTIAL under D-43 — it may not stage.
        val w = syncWorld(BacklogPages.pages(), endOfHistory = null)
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(3_000_000)

            val report = assertNotNull(w.session.sync.state.value.last)
            assertEquals(HistoryChannelOutcome.PARTIAL, report.channels.first { it.channel == Command.SYNC_CHANNEL_SLEEP }.verdict)
            assertEquals(HistoryCommitGate.Decision.SKIP, report.commit?.staging)
            assertEquals(emptyList(), SleepStore(w.db).sleepSummaries(everything.first, everything.second))
            assertEquals(3654, w.blobs.loadEpochArchive(TEST_RING_ID).records.size, "the whole backlog is kept for a later sync")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun nightsTheRingHandedToTheAllDayChannelAreRestagedFromTheArchive() = runTest {
        // A Gen 3 ring can hand its night to the all-day channel: the sleep channel comes back with
        // nothing new, the night records arrive on 0x03, and the gate's rescue restages them.
        val w = syncWorld { scope, now ->
            RingFake(scope, now, mapOf(Command.SYNC_CHANNEL_SLEEP to emptyList(), Command.SYNC_CHANNEL_ALL_DAY to BacklogPages.pages()))
        }
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(3_000_000)

            val report = assertNotNull(w.session.sync.state.value.last)
            assertEquals(HistoryCommitGate.Decision.RESTAGE_FROM_ARCHIVE, report.commit?.staging)
            assertTrue(w.store.evidence.last().nightRecordsOnOtherChannels > 0)
            assertEquals(BacklogPages.WAKE_DAYS, SleepStore(w.db).sleepSummaries(everything.first, everything.second).map { it.night })
        } finally {
            w.db.close()
        }
    }
}
