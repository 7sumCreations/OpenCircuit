package io.github.opencircuit.app

import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.sync.SyncOutcome
import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.store.LocalStore
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The forward-cursor hazard (PORTING.md D-267). The store keeps a sample only when it is newer
 * than its kind's cursor, so a commit must never move the cursor past a record a channel still
 * holds. Here the all-day channel (`0x03`) holds OLDER records than the sleep channel (`0x00`);
 * the sleep channel drains completely, the all-day one is cut by a lost link after two of its four
 * pages, and a second sync drains the rest. Every all-day record's heart rate must be in the store
 * afterwards — committing the sleep records with the first sync would have dropped the last two
 * all-day pages as "older than what is stored".
 *
 * Pages are built on the raw path ([HistoryTestPages.page]): the real overnight page's records,
 * moved whole epochs; the sleep pages start 600 epochs after the all-day ones.
 */
class HoldBackInterruptedChannelTest {

    private val sleepPages = (0 until 2).map { HistoryTestPages.page(it, queuedAfter = (1 - it) * 6, firstEpoch = SLEEP_FIRST_EPOCH) }
    private val allDayPages = (0 until 4).map { HistoryTestPages.page(it, queuedAfter = (3 - it) * 6, firstEpoch = 0) }

    private fun allDayDates(pages: IntRange): List<Instant> =
        pages.flatMap { HistoryTestPages.counters(it) }.map { Instant.ofEpochSecond(it + Command.SYNC_EPOCH) }

    private fun sleepDates(): List<Instant> =
        (0 until 2).flatMap { HistoryTestPages.counters(it) }.map { Instant.ofEpochSecond(it + SLEEP_FIRST_EPOCH * 150L + Command.SYNC_EPOCH) }

    @Test
    fun aCompleteSleepChannelIsHeldBackUntilTheInterruptedAllDayChannelHasCaughtUp() = runTest {
        val w = syncWorld { scope, now ->
            RingFake(scope, now, mapOf(Command.SYNC_CHANNEL_SLEEP to sleepPages, Command.SYNC_CHANNEL_ALL_DAY to allDayPages))
        }
        try {
            val samples = LocalStore(w.db)
            suspend fun heartRateAt(dates: List<Instant>) =
                samples.samples(MetricKind.HEART_RATE, dates.first(), dates.last().plusSeconds(1)).map { it.start }

            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            // Sleep: pages at 2 700 and 5 300, its 0x50 at 7 100. All-day: open 7 100, pages at 8 800 and 11 400; the third is due at 13 200.
            advanceTo(12_000)
            w.ring.dropLink()
            advanceTo(20_000)
            assertEquals(SyncOutcome.PARTIAL, w.session.sync.state.value.last!!.outcome)

            w.viewModel.onAction(RingAction.SyncNow) // reconnects; the ring offers all-day pages 3 and 4
            runCurrent()
            advanceTo(120_000)

            assertEquals(SyncOutcome.COMPLETE, w.session.sync.state.value.last!!.outcome)
            assertEquals(allDayDates(0..3), heartRateAt(allDayDates(0..3)), "no all-day sample dropped")
            assertEquals(sleepDates(), heartRateAt(sleepDates()), "every sleep sample stored")
            assertEquals(emptyList(), w.journal.read(TEST_RING_ID).entries, "every page consumed")
        } finally {
            w.db.close()
        }
    }

    private companion object {
        const val SLEEP_FIRST_EPOCH = 600
    }
}
