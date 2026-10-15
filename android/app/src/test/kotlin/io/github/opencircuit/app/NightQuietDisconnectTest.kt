package io.github.opencircuit.app

import io.github.opencircuit.app.data.PrefsSyncMarks
import io.github.opencircuit.app.sync.AutoSyncSkip
import io.github.opencircuit.app.sync.AutoSyncSkipReason
import io.github.opencircuit.app.sync.AutoSyncTrigger
import io.github.opencircuit.app.sync.SyncOutcome
import io.github.opencircuit.ble.LinkState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * "Disconnect after syncing" ON holds no idle link through the night (PORTING.md D-275): an
 * AUTOMATIC link-up the night's quiet holds back closes the link at once, the skip says so
 * (NIGHT_QUIET, link closed), and the catch-up it owes connects and syncs once when the quiet
 * ends. A link-up the user asked for is kept, and its catch-up runs on it. Switch OFF: the link is
 * held all night, as before.
 *
 * America/New_York, the remembered ring linking up at 02:00 EDT (virtual 500 ms after 06:00:00Z),
 * no learned night: the fallback window ends at 10:00 local.
 */
class NightQuietDisconnectTest {

    private val newYork: ZoneId = ZoneId.of("America/New_York")
    private val twoAm = Instant.parse("2026-10-08T06:00:00Z") // 02:00 EDT

    /** Virtual milliseconds at local [hh]:[mm] on the morning of 2026-10-08 (EDT, UTC−4). */
    private fun local(hh: Int, mm: Int = 0): Long = ((hh - 2) * 60L + mm) * 60_000L

    private suspend fun TestScope.night(): SyncWorld {
        val kv = InMemoryKeyValues()
        // A complete sync twelve hours ago: the throttle plays no part, the night does.
        PrefsSyncMarks(kv, TEST_RING_ID).setLastCompleteSync(twoAm.minusSeconds(12 * 3600))
        return syncWorld(triggers = true, keyValues = kv, zone = newYork, wallStart = twoAm, makeRing = ringWith(HistoryTestPages.backlog(3)))
    }

    private fun SyncWorld.disconnects(): List<Long> = ring.events.filter { it.what == "disconnect" }.map { it.atMillis }

    private fun SyncWorld.connects(): List<Long> = ring.events.filter { it.what == "connect" }.map { it.atMillis }

    private fun SyncWorld.skips(): List<AutoSyncSkip> = session.triggers!!.skips.value

    @Test
    fun anAutomaticLinkUpInsideTheNightClosesTheLinkAndTheCatchUpConnectsOnceAtTheEnd() = runTest {
        val w = night()
        try {
            advanceTo(60_000)
            assertEquals(LinkState.Idle, w.session.state.value, "no idle link is held in sync-and-disconnect mode")
            assertEquals(listOf(500L), w.disconnects(), "closed at its link-up")
            assertEquals(
                listOf(AutoSyncSkip(twoAm.plusMillis(500), AutoSyncTrigger.LINK_UP, AutoSyncSkipReason.NIGHT_QUIET, disconnected = true)),
                w.skips(),
            )
            assertEquals(emptyList(), w.sleepOpens())

            advanceTo(local(10) - 1)
            assertEquals(emptyList(), w.sleepOpens(), "quiet until 10:00")
            assertEquals(1, w.connects().size, "nothing connects inside the night")

            advanceTo(local(10) + 3_000)
            assertEquals(2, w.connects().size, "the owed catch-up connects at the window's end")
            assertEquals(local(10), w.connects()[1])
            assertEquals(1, w.sleepOpens().size, "and syncs")
            advanceTo(local(11))
            assertEquals(1, w.sleepOpens().size, "exactly once")
            assertEquals(SyncOutcome.COMPLETE, w.session.sync.state.value.last?.outcome)
            assertEquals(LinkState.Idle, w.session.state.value, "closed again after the catch-up")
            assertEquals(
                listOf(AutoSyncSkip(twoAm.plusMillis(500), AutoSyncTrigger.LINK_UP, AutoSyncSkipReason.NIGHT_QUIET, disconnected = true)),
                w.skips(),
                "the catch-up's own link-up is not a skip",
            )
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aUserLinkUpInsideTheNightIsKeptAndItsCatchUpRunsOnIt() = runTest {
        val w = night()
        try {
            advanceTo(60_000)
            assertEquals(LinkState.Idle, w.session.state.value)
            // The user's Scan & connect at 60 s.
            sessionsOf(w.session).connect(w.session.link.ring)
            advanceTo(local(9))
            assertEquals(LinkState.Authenticated, w.session.state.value, "the user's link-up is never closed by the night")
            assertEquals(listOf(500L), w.disconnects())
            assertEquals(
                AutoSyncSkip(twoAm.plusMillis(60_500), AutoSyncTrigger.LINK_UP, AutoSyncSkipReason.NIGHT_QUIET, disconnected = false),
                w.skips().last(),
            )
            assertEquals(emptyList(), w.sleepOpens())

            advanceTo(local(10) + 3_000)
            assertEquals(1, w.sleepOpens().size, "the catch-up runs on the held link")
            assertEquals(2, w.connects().size, "no new connect for it")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun withTheSwitchOffTheAutomaticLinkUpIsHeldAllNightAsBefore() = runTest {
        val w = night()
        try {
            w.prefs.setDisconnectAfterSync(false)
            advanceTo(local(9))
            assertEquals(LinkState.Authenticated, w.session.state.value, "held")
            assertEquals(emptyList(), w.disconnects())
            assertEquals(
                listOf(AutoSyncSkip(twoAm.plusMillis(500), AutoSyncTrigger.LINK_UP, AutoSyncSkipReason.NIGHT_QUIET, disconnected = false)),
                w.skips(),
            )
            advanceTo(local(10) + 3_000)
            assertEquals(1, w.sleepOpens().size, "the catch-up runs on the held link")
            assertEquals(1, w.connects().size)
        } finally {
            w.db.close()
        }
    }
}
