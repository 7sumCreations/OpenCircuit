package io.github.opencircuit.app

import io.github.opencircuit.app.data.PrefsSyncMarks
import io.github.opencircuit.ble.LinkState
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * While the link is held — "Disconnect after syncing" OFF — the history is drained on
 * `HistoryDrainCadence` (1 h by day; upstream's keepalive loop, `ios/OpenCircuit/BLE/RingSession.swift:1220-1313`
 * @ b1c2fdd), never inside the night, and once when the night ends. With the switch ON there is no
 * periodic drain. UTC, no stored night: the fallback window 21:30 → 10:00.
 */
class PeriodicCadenceTest {

    private val afternoon = Instant.parse("2026-10-08T14:00:00Z")

    @Test
    fun switchOffTheHeldLinkDrainsAgainExactlyOneHourAfterTheLastSyncFinished() = runTest {
        val w = syncWorld(triggers = true, wallStart = afternoon, makeRing = ringWith(HistoryTestPages.backlog(3)))
        try {
            w.prefs.setDisconnectAfterSync(false)
            advanceTo(60_000)
            assertEquals(1, w.sleepOpens().size, "the first link-up synced")
            val finished = assertNotNull(w.session.sync.state.value.last).finishedAt
            val due = Duration.between(afternoon, finished).toMillis() + 3_600_000
            assertEquals(LinkState.Authenticated, w.session.state.value, "the link is held")

            advanceTo(due - 1)
            assertEquals(1, w.sleepOpens().size)
            assertFalse(w.session.sync.isSyncing.value)
            advanceTo(due)
            assertTrue(w.session.sync.isSyncing.value, "the cadence started a sync at the hour")
            advanceTo(due + 5_000)
            assertEquals(2, w.sleepOpens().size)
            // The keepalive's 180 s status query falls on the same instant; the open waits for its
            // answer (at most 2 s) so a 0x50 reply is not taken for the end of the history.
            assertTrue(w.sleepOpens().last() in due..due + 2_000, "${w.sleepOpens()} due=$due")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun switchOnAHeldLinkIsNeverDrainedOnTheCadence() = runTest {
        val w = syncWorld(triggers = true, wallStart = afternoon, makeRing = ringWith(HistoryTestPages.backlog(3)))
        try {
            advanceTo(60_000)
            assertEquals(1, w.sleepOpens().size)
            assertEquals(LinkState.Idle, w.session.state.value, "disconnected after the sync")
            // The link comes back within the throttle and stays up (the user opened the app again).
            w.session.connect()
            advanceTo(3 * 3_600_000L)
            assertEquals(LinkState.Authenticated, w.session.state.value)
            assertEquals(1, w.sleepOpens().size, "no periodic drain with the switch on")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun switchOffTheCadenceStopsAt2130AndDrainsOnceAt1000() = runTest {
        val evening = Instant.parse("2026-10-08T20:00:00Z")
        val kv = InMemoryKeyValues()
        PrefsSyncMarks(kv, TEST_RING_ID).setLastCompleteSync(evening.minusSeconds(6 * 3600))
        val w = syncWorld(triggers = true, keyValues = kv, wallStart = evening, makeRing = ringWith(HistoryTestPages.backlog(3)))
        try {
            w.prefs.setDisconnectAfterSync(false)
            advanceTo(60_000)
            assertEquals(1, w.sleepOpens().size, "20:00: the link-up synced")
            val first = Duration.between(evening, assertNotNull(w.session.sync.state.value.last).finishedAt).toMillis()
            advanceTo(first + 3_600_000 + 60_000)
            assertEquals(2, w.sleepOpens().size, "21:00: the cadence")

            val tenAm = 14 * 3_600_000L // 10:00 the next morning
            advanceTo(tenAm - 1)
            assertEquals(2, w.sleepOpens().size, "nothing from 21:30 to 10:00")
            assertFalse(w.session.sync.isSyncing.value)
            advanceTo(tenAm)
            assertTrue(w.session.sync.isSyncing.value, "the catch-up started at 10:00")
            advanceTo(tenAm + 5_000)
            assertEquals(3, w.sleepOpens().size, "one catch-up at 10:00")
            assertTrue(w.sleepOpens().last() in tenAm..tenAm + 2_000, "${w.sleepOpens()}")
            advanceTo(tenAm + 3_000_000)
            assertEquals(3, w.sleepOpens().size, "and not again before the hour")
        } finally {
            w.db.close()
        }
    }
}
