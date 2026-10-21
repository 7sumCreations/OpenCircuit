package io.github.opencircuit.app

import io.github.opencircuit.app.sync.ChannelProgress
import io.github.opencircuit.app.today.SyncStripPresenter
import io.github.opencircuit.app.today.TodayInput
import io.github.opencircuit.app.today.TodayPresenter
import io.github.opencircuit.app.today.TodaySession
import io.github.opencircuit.app.today.TodayStored
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.store.SyncLogEntry
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Today's sync strip: a slim bar while a sync runs — indeterminate until the first page's countdown
 * says how many records the channel holds, then "N of M records" with a determinate bar (the same
 * numbers as the Ring data card) — that collapses to "Last synced …" when the sync ends. "Last
 * synced" is on Today in every state, during a sync too.
 */
class SyncStripPresenterTest {

    private val now: Instant = Instant.parse("2026-10-21T16:00:00Z")

    private fun channel(label: String, records: Int, expected: Int?, eta: Long? = null, done: Boolean = false) =
        ChannelProgress(label = label, records = records, expected = expected, etaSeconds = eta, done = done)

    private fun entry(finishedAt: Instant) = SyncLogEntry(startedAt = finishedAt.minusSeconds(60), finishedAt = finishedAt, outcome = "COMPLETE")

    private fun today(session: TodaySession) = TodayPresenter.present(TodayInput(TodayStored(), session, now, ZoneOffset.UTC))

    @Test
    fun beforeTheFirstCountdownTheBarIsIndeterminate() {
        val none = assertNotNull(SyncStripPresenter.present(syncing = true, channels = emptyList(), firstSync = false))
        assertEquals("Syncing…", none.text)
        assertNull(none.fraction, "nothing known yet: indeterminate")

        val noCount = assertNotNull(SyncStripPresenter.present(syncing = true, channels = listOf(channel("sleep", 12, expected = null)), firstSync = false))
        assertEquals("Syncing · Sleep channel 12 records so far", noCount.text)
        assertNull(noCount.fraction)
    }

    @Test
    fun onceTheCountdownIsKnownTheBarShowsNOfMRecords() {
        val strip = assertNotNull(
            SyncStripPresenter.present(
                syncing = true,
                channels = listOf(channel("sleep", 18, 18, done = true), channel("all-day", 120, 723, eta = 330)),
                firstSync = false,
            ),
        )
        assertEquals("Syncing · All-day channel 120 of 723 records · about 6 min left", strip.text)
        assertEquals(120f / 723f, assertNotNull(strip.fraction), 0.0001f)
    }

    @Test
    fun aChannelJustDoneWaitsForTheNextOneIndeterminate() {
        val strip = assertNotNull(SyncStripPresenter.present(syncing = true, channels = listOf(channel("sleep", 1_204, 1_204, done = true)), firstSync = false))
        assertEquals("Syncing · Sleep channel done · 1,204 records", strip.text)
        assertNull(strip.fraction)
    }

    @Test
    fun aZeroCountAndAnOverCountKeepTheBarInRange() {
        assertNull(assertNotNull(SyncStripPresenter.present(true, listOf(channel("sleep", 0, 0)), firstSync = false)).fraction)
        assertEquals(1f, assertNotNull(SyncStripPresenter.present(true, listOf(channel("sleep", 30, 20)), firstSync = false)).fraction)
    }

    @Test
    fun theFirstSyncSaysSo() {
        assertEquals("First sync…", assertNotNull(SyncStripPresenter.present(syncing = true, channels = emptyList(), firstSync = true)).text)
    }

    @Test
    fun withNoSyncRunningTheStripCollapses() {
        assertNull(SyncStripPresenter.present(syncing = false, channels = listOf(channel("sleep", 18, 18, done = true)), firstSync = false))
    }

    @Test
    fun lastSyncedIsOnTodayInEveryState() {
        val log = listOf(entry(now.minusSeconds(18 * 3_600)))
        val states = listOf(
            TodaySession(),
            TodaySession(link = LinkState.Idle, ringName = "Demo ring"),
            TodaySession(link = LinkState.Authenticated, log = log),
            TodaySession(link = LinkState.Authenticated, log = log, syncing = true, channels = listOf(channel("all-day", 5, 50))),
            TodaySession(link = LinkState.Authenticated, syncing = true),
        )
        for (s in states) {
            val ui = today(s)
            assertTrue(ui.lastSynced == "Not synced yet" || ui.lastSynced.startsWith("Last synced "), "state $s shows '${ui.lastSynced}'")
        }
        val syncing = today(states[3])
        assertEquals("Last synced 18 h ago", syncing.lastSynced, "a running sync keeps the last one's time on screen")
        assertEquals("Syncing · All-day channel 5 of 50 records", assertNotNull(syncing.strip).text)
        assertNull(today(states[2]).strip, "collapsed when no sync runs")
        assertEquals("First sync…", assertNotNull(today(states[4]).strip).text)
    }
}
