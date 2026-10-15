package io.github.opencircuit.app

import io.github.opencircuit.app.data.PrefsSyncMarks
import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.ringkit.Command
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Night records the ring hands to the all-day channel earn the commit's restage only when they
 * fall in the night window (upstream counts with `HistoryCommitGate.isNightRecord` against its
 * cached window, `ios/OpenCircuit/BLE/RingSession.swift:3757-3765` @ b1c2fdd): the window resolved
 * at the sync's start, or the one a day before it, plus the gate's 2 h late margin. Without a
 * window (a session with no automatic syncs) every one counts, as `isNightRecord` with none.
 *
 * Fixtures: the real overnight page's records on the all-day channel, moved whole epochs — at
 * 2026-06-14 23:29Z (a night) or 2026-06-14 13:59Z (an afternoon). UTC, no stored night: the
 * fallback window 21:30 → 10:00.
 */
class NightRecordsInWindowTest {

    private val nightPage = HistoryTestPages.page(0, queuedAfter = 0, firstEpoch = 600) // 2026-06-14 23:28:59Z..
    private val afternoonPage = HistoryTestPages.page(0, queuedAfter = 0, firstEpoch = 372) // 2026-06-14 13:58:59Z..

    /** Sync now on a ring holding [page] on the all-day channel only; the night records the commit was told of. */
    private suspend fun TestScope.nightRecordsCounted(page: ByteArray, triggers: Boolean, wallStart: Instant): Int {
        val kv = InMemoryKeyValues()
        // A complete sync a minute before: the link-up does not sync on its own.
        PrefsSyncMarks(kv, TEST_RING_ID).setLastCompleteSync(wallStart.minusSeconds(60))
        val w = syncWorld(triggers = triggers, keyValues = kv, wallStart = wallStart.minusMillis(testScheduler.currentTime)) { scope, now ->
            RingFake(scope, now, mapOf(Command.SYNC_CHANNEL_SLEEP to emptyList(), Command.SYNC_CHANNEL_ALL_DAY to listOf(page)))
        }
        try {
            advanceTo(testScheduler.currentTime + 1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(testScheduler.currentTime + 120_000)
            assertEquals(1, w.store.evidence.size, "one sync")
            return w.store.evidence.single().nightRecordsOnOtherChannels
        } finally {
            w.db.close()
        }
    }

    @Test
    fun nightRecordsInsideTheWindowCountAsWithoutOne() = runTest {
        val withoutWindow = nightRecordsCounted(nightPage, triggers = false, wallStart = Instant.parse("2026-06-15T02:00:00Z"))
        assertTrue(withoutWindow > 0, "the page holds sleep-vitals records")
        assertEquals(withoutWindow, nightRecordsCounted(nightPage, triggers = true, wallStart = Instant.parse("2026-06-15T02:00:00Z")))
    }

    @Test
    fun anAfternoonsSleepVitalsRecordsOnTheAllDayChannelNoLongerCount() = runTest {
        val withoutWindow = nightRecordsCounted(afternoonPage, triggers = false, wallStart = Instant.parse("2026-06-14T16:00:00Z"))
        assertTrue(withoutWindow > 0, "with no window the afternoon's records count")
        assertEquals(0, nightRecordsCounted(afternoonPage, triggers = true, wallStart = Instant.parse("2026-06-14T16:00:00Z")))
    }

    @Test
    fun lastNightsRecordsSyncedTheNextAfternoonStillCount() = runTest {
        // At 14:00 the window is tonight's; the one a day before it is last night's.
        val withoutWindow = nightRecordsCounted(nightPage, triggers = false, wallStart = Instant.parse("2026-06-15T14:00:00Z"))
        assertTrue(withoutWindow > 0)
        assertEquals(withoutWindow, nightRecordsCounted(nightPage, triggers = true, wallStart = Instant.parse("2026-06-15T14:00:00Z")))
    }
}
