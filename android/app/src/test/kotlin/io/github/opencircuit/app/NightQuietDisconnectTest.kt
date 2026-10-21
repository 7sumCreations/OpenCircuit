package io.github.opencircuit.app

import io.github.opencircuit.app.data.PrefsSyncMarks
import io.github.opencircuit.app.live.LiveMode
import io.github.opencircuit.app.sync.AutoSyncSkip
import io.github.opencircuit.app.sync.AutoSyncSkipReason
import io.github.opencircuit.app.sync.AutoSyncTrigger
import io.github.opencircuit.app.sync.SyncOutcome
import io.github.opencircuit.app.sync.SyncTriggers
import io.github.opencircuit.ble.LinkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    private suspend fun TestScope.night(foreground: MutableStateFlow<Boolean> = MutableStateFlow(false)): SyncWorld {
        val kv = InMemoryKeyValues()
        // A complete sync twelve hours ago: the throttle plays no part, the night does.
        PrefsSyncMarks(kv, TEST_RING_ID).setLastCompleteSync(twoAm.minusSeconds(12 * 3600))
        return syncWorld(
            triggers = true, keyValues = kv, zone = newYork, wallStart = twoAm, appInForeground = foreground,
            makeRing = ringWith(HistoryTestPages.backlog(3)),
        )
    }

    /** The user opens the app before the night ends: the owed catch-up still connects at the night's end. */
    @Test
    fun theCatchUpStillRunsWhenTheAppIsOpenedBeforeTheNightEnds() = runTest {
        val foreground = MutableStateFlow(false)
        val w = night(foreground)
        try {
            advanceTo(60_000)
            assertEquals(LinkState.Idle, w.session.state.value, "closed at its link-up")
            advanceTo(local(9))
            foreground.value = true
            advanceTo(local(10) + 3_000)
            assertEquals(2, w.connects().size, "the owed catch-up connects at the window's end")
            assertEquals(1, w.sleepOpens().size, "and syncs")
        } finally {
            w.db.close()
        }
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
    fun aCatchUpRefusedByAMeasureStartingAtTheNightsEndStaysOwedAndRunsOnceTheMeasureEnds() = runTest {
        val w = night()
        try {
            advanceTo(60_000)
            sessionsOf(w.session).connect(w.session.link.ring)
            // The measure starts while the catch-up's evaluation reads the stored nights, after its busy check.
            var started = false
            w.duringNightsRead = {
                if (!started && testScheduler.currentTime >= local(10)) {
                    started = true
                    w.session.liveMeasure.start(LiveMode.HEART_RATE)
                }
            }
            advanceTo(local(10) + 3_000)
            assertTrue(started, "the measure started at the night's end")
            assertTrue(w.session.liveMeasure.isMeasuring.value)
            assertEquals(emptyList(), w.sleepOpens(), "no sync during the measure")
            assertEquals(
                AutoSyncSkip(twoAm.plusMillis(local(10)), AutoSyncTrigger.CATCH_UP, AutoSyncSkipReason.MEASURING),
                w.skips().last(),
            )

            advanceTo(local(10) + 30_000)
            w.session.liveMeasure.stop()
            advanceTo(local(10) + 30_000 + SyncTriggers.TICK_MILLIS + 3_000)
            assertEquals(1, w.sleepOpens().size, "the owed catch-up runs once the measure has ended")
            advanceTo(local(11))
            assertEquals(1, w.sleepOpens().size, "exactly once")
            assertEquals(SyncOutcome.COMPLETE, w.session.sync.state.value.last?.outcome)
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aCatchUpRefusedByAMeasureThatEndsBeforeTheRefusalIsReadStaysOwed() = runTest {
        val w = night()
        try {
            advanceTo(60_000)
            sessionsOf(w.session).connect(w.session.link.ring)
            // The measure starts after the catch-up's busy check and ends between the controller's
            // refusal and the trigger reading why: no sync runs, so nothing serves the catch-up.
            var started = false
            w.duringNightsRead = {
                if (!started && testScheduler.currentTime >= local(10)) {
                    started = true
                    w.session.liveMeasure.start(LiveMode.HEART_RATE)
                }
            }
            var stopped = false
            w.onLog = { line ->
                if (!stopped && line.startsWith("Sync now not started: a live measure")) {
                    stopped = true
                    w.session.liveMeasure.stop()
                }
            }
            advanceTo(local(10) + 3_000)
            assertTrue(stopped, "the controller refused the catch-up for the measure")
            assertEquals(false, w.session.liveMeasure.isMeasuring.value)
            assertEquals(false, w.session.sync.isSyncing.value, "no sync serves the catch-up")

            advanceTo(local(10) + SyncTriggers.TICK_MILLIS + 3_000)
            assertEquals(1, w.sleepOpens().size, "the owed catch-up runs a tick on")
            advanceTo(local(11))
            assertEquals(1, w.sleepOpens().size, "exactly once")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aCatchUpRefusedBecauseTheUsersSyncStartedIsServedByItAndNeverRunsAgain() = runTest {
        val w = night()
        try {
            advanceTo(60_000)
            sessionsOf(w.session).connect(w.session.link.ring)
            // The user's Sync now starts while the catch-up's evaluation reads the stored nights.
            var tapped = false
            w.duringNightsRead = {
                if (!tapped && testScheduler.currentTime >= local(10)) {
                    tapped = true
                    assertTrue(w.session.sync.syncNow(), "the user's Sync now starts")
                }
            }
            advanceTo(local(10) + 3_000)
            assertTrue(tapped)
            assertEquals(1, w.sleepOpens().size, "the user's sync runs")
            advanceTo(local(11))
            assertEquals(1, w.sleepOpens().size, "the catch-up it served never runs after it")
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
