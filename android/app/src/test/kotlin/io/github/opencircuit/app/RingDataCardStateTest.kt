package io.github.opencircuit.app

import io.github.opencircuit.app.ring.RingDataInput
import io.github.opencircuit.app.ring.RingDataPresenter
import io.github.opencircuit.app.sync.ChannelProgress
import io.github.opencircuit.app.sync.StoredData
import io.github.opencircuit.app.sync.StoredLastNight
import io.github.opencircuit.app.sync.SyncOutcome
import io.github.opencircuit.app.sync.SyncState
import io.github.opencircuit.ringkit.HistoryChannelOutcome
import io.github.opencircuit.ringkit.SyncMeasurement.OverdueLevel
import io.github.opencircuit.store.SyncLogChannel
import io.github.opencircuit.store.SyncLogEntry
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every state of the Ring data card, from the sync log and what is stored — the same input after a
 * relaunch as in the session that synced. Copy typed from the mockup (`Last synced …`, `Stored on
 * this phone`, `Nights`, `Last night`, `… records · complete`, the overdue warning) and the result
 * list (complete · up to date · partial · no data received · lost frames · nights waiting), plus
 * the records a sync held back and the channel that held them.
 */
class RingDataCardStateTest {

    private val now: Instant = Instant.parse("2026-10-08T09:00:00Z")
    private val day: Duration = Duration.ofDays(1)

    private fun entry(
        outcome: SyncOutcome = SyncOutcome.COMPLETE,
        finishedAt: Instant = now.minus(Duration.ofMinutes(5)),
        stored: Int = 0,
        channels: List<SyncLogChannel> = listOf(complete("sleep", finishedAt), complete("all-day", finishedAt)),
        build: (SyncLogEntry) -> SyncLogEntry = { it },
    ) = build(SyncLogEntry(startedAt = finishedAt.minusSeconds(60), finishedAt = finishedAt, outcome = outcome.name, recordsStored = stored, channels = channels))

    private fun complete(label: String, at: Instant) =
        SyncLogChannel(label = label, channel = if (label == "sleep") 0 else 3, verdict = HistoryChannelOutcome.COMPLETE, drainedThrough = at)

    private fun card(
        log: List<SyncLogEntry> = emptyList(),
        stored: StoredData? = null,
        sync: SyncState? = SyncState(),
        liveFirmware: String? = null,
        zone: ZoneId = ZoneOffset.UTC,
        measuring: Boolean = false,
    ) = RingDataPresenter.present(
        RingDataInput(sync = sync, log = log, stored = stored, liveFirmware = liveFirmware, disconnectAfterSync = true, now = now, zone = zone, measuring = measuring),
    )

    // ---- Results ----

    @Test
    fun neverSyncedSaysSoAndShowsNothingStored() {
        val ui = card()
        assertEquals("Not synced yet", ui.headline)
        assertNull(ui.lastSync)
        assertNull(ui.storedRange)
        assertNull(ui.nights)
        assertNull(ui.lastNight)
        assertNull(ui.overdue)
        assertEquals(emptyList(), ui.notices)
        assertTrue(ui.syncEnabled)
    }

    @Test
    fun aCompleteSyncShowsWhenAndHowManyRecords() {
        val ui = card(log = listOf(entry(stored = 4_312, finishedAt = now.minus(day.multipliedBy(2)))))
        assertEquals("Last synced 2 days ago", ui.headline)
        assertEquals("4,312 records · complete", ui.lastSync)
        assertNull(ui.problem)
    }

    @Test
    fun aCompleteSyncWithNothingNewIsUpToDate() {
        assertEquals("Up to date", card(log = listOf(entry(stored = 0))).lastSync)
    }

    @Test
    fun onlyTheNewestEntryIsTheLastSync() {
        val ui = card(log = listOf(entry(stored = 9, finishedAt = now.minus(day)), entry(stored = 1)))
        assertEquals("Last synced 5 min ago", ui.headline)
        assertEquals("1 record · complete", ui.lastSync)
    }

    @Test
    fun aPartialSyncKeepsWhatCameAndWillRetry() {
        val ui = card(log = listOf(entry(SyncOutcome.PARTIAL, stored = 12)))
        assertEquals("12 records · partial — data kept, will retry", ui.lastSync)
        assertNull(ui.problem)
    }

    @Test
    fun aSyncThatGotNothingSaysNoDataReceivedWithItsReason() {
        val ui = card(log = listOf(entry(SyncOutcome.NO_ACK, channels = emptyList())))
        assertEquals("No data received", ui.lastSync)
        assertEquals("The ring didn't answer the sync request", ui.problem)

        val notConnected = card(log = listOf(entry(SyncOutcome.NOT_CONNECTED, channels = emptyList())))
        assertEquals("No data received", notConnected.lastSync)
        assertEquals("Couldn't reach the ring — try again", notConnected.problem)
    }

    @Test
    fun lostFramesAreCounted() {
        val ui = card(log = listOf(entry(stored = 18) { it.copy(undeliveredFrames = 3) }))
        assertEquals("3 frames from the ring weren't read — sync again", ui.problem)
        assertEquals("1 frame from the ring wasn't read — sync again", card(log = listOf(entry { it.copy(undeliveredFrames = 1) })).problem)
    }

    @Test
    fun nightsWaitingForTheStoresUpdateAreSaid() {
        val ui = card(log = listOf(entry(stored = 700) { it.copy(nightsStaged = 1, nightsWaiting = 2) }))
        assertEquals(listOf("2 nights waiting — saved by a later sync"), ui.notices)
        assertEquals(listOf("1 night waiting — saved by a later sync"), card(log = listOf(entry { it.copy(nightsWaiting = 1) })).notices)
    }

    @Test
    fun aSyncThatFailedToSaveSaysSo() {
        val ui = card(log = listOf(entry(SyncOutcome.SAVE_FAILED, stored = 6)))
        assertEquals("6 records · partial — data kept, will retry", ui.lastSync)
        assertEquals("Couldn't save — will retry", ui.problem)
    }

    @Test
    fun aPausedSyncSaysOpenTheAppToFinish() {
        val ui = card(log = listOf(entry(SyncOutcome.PARTIAL, stored = 6) { it.copy(paused = true) }))
        assertEquals("Sync paused — open OpenCircuit to finish", ui.headline)
    }

    /** Every way a sync can end has a "Last sync" row: the list is checked against the enum, not typed. */
    @Test
    fun everyOutcomeHasALastSyncRow() {
        for (outcome in SyncOutcome.entries) {
            for (stored in listOf(0, 5)) {
                val row = card(log = listOf(entry(outcome, stored = stored))).lastSync
                assertTrue(!row.isNullOrBlank(), "$outcome with $stored records has no row")
            }
        }
    }

    // ---- Held back ----

    @Test
    fun recordsHeldBackNameTheChannelThatDidNotFinish() {
        val allDay = SyncLogChannel(label = "all-day", channel = 3, verdict = HistoryChannelOutcome.PARTIAL, records = 12, firstCounter = 1L, lastCounter = 1_651L)
        val ui = card(log = listOf(entry(SyncOutcome.PARTIAL, stored = 0, channels = listOf(complete("sleep", now), allDay)) { it.copy(heldBack = 12, heldBackBy = listOf("all-day")) }))
        assertEquals(listOf("12 records waiting — the all-day channel didn't finish"), ui.notices)
    }

    @Test
    fun recordsHeldBackNameTheChannelThatDidNotAnswer() {
        val allDay = SyncLogChannel(label = "all-day", channel = 3, verdict = HistoryChannelOutcome.NO_ACK)
        val ui = card(log = listOf(entry(SyncOutcome.PARTIAL, stored = 0, channels = listOf(complete("sleep", now), allDay)) { it.copy(heldBack = 1_204, heldBackBy = listOf("all-day")) }))
        assertEquals(listOf("1,204 records waiting — the all-day channel didn't answer"), ui.notices)
    }

    @Test
    fun recordsHeldBackNameTheChannelTheSyncNeverReached() {
        val sleep = SyncLogChannel(label = "sleep", channel = 0, verdict = HistoryChannelOutcome.PARTIAL, records = 6, firstCounter = 1L, lastCounter = 751L)
        val ui = card(log = listOf(entry(SyncOutcome.PARTIAL, channels = listOf(sleep)) { it.copy(heldBack = 6, heldBackBy = listOf("all-day")) }))
        assertEquals(listOf("6 records waiting — the all-day channel wasn't reached"), ui.notices)
    }

    @Test
    fun twoChannelsHoldingBackAreBothNamed() {
        val sleep = SyncLogChannel(label = "sleep", channel = 0, verdict = HistoryChannelOutcome.NO_ACK)
        val ui = card(log = listOf(entry(SyncOutcome.NO_ACK, channels = listOf(sleep)) { it.copy(heldBack = 30, heldBackBy = listOf("sleep", "all-day")) }))
        assertEquals(listOf("30 records waiting — the sleep channel didn't answer and the all-day channel wasn't reached"), ui.notices)
    }

    // ---- What is stored ----

    @Test
    fun whatIsStoredShowsTheRangeTheNightsAndLastNight() {
        val zone = ZoneId.of("Europe/London")
        val stored = StoredData(
            oldest = Instant.parse("2026-09-12T06:00:00Z"),
            newest = Instant.parse("2026-10-08T07:30:00Z"),
            nights = 21,
            lastNight = StoredLastNight(Instant.parse("2026-10-07T22:41:00Z"), Instant.parse("2026-10-08T06:02:00Z"), asleepMinutes = 441),
        )
        val ui = card(log = listOf(entry(stored = 10)), stored = stored, zone = zone)
        assertEquals("12 Sep – 8 Oct", ui.storedRange)
        assertEquals("21", ui.nights)
        // 22:41Z / 06:02Z are 23:41 / 07:02 in London (BST).
        assertEquals("23:41 → 07:02 · 7 h 21 m", ui.lastNight)
    }

    @Test
    fun aRangeAcrossTheYearShowsBothYearsAndOneDayShowsOnce() {
        val across = StoredData(Instant.parse("2025-12-28T10:00:00Z"), Instant.parse("2026-01-03T10:00:00Z"), nights = 0, lastNight = null)
        val ui = card(stored = across)
        assertEquals("28 Dec 2025 – 3 Jan 2026", ui.storedRange)
        assertEquals("0", ui.nights)
        assertNull(ui.lastNight)

        val oneDay = StoredData(Instant.parse("2026-10-08T01:00:00Z"), Instant.parse("2026-10-08T08:00:00Z"), nights = 0, lastNight = null)
        assertEquals("8 Oct", card(stored = oneDay).storedRange)
    }

    // ---- Overdue ----

    private fun drainedAt(agoDays: Double, firmware: String? = null): SyncLogEntry {
        val at = now.minusMillis((agoDays * 86_400_000).toLong())
        return entry(finishedAt = at, channels = listOf(complete("sleep", at), complete("all-day", at))) { it.copy(firmware = firmware) }
    }

    @Test
    fun aGen2RingIsAmberAtFourDaysAndRedAtFive() {
        assertNull(card(log = listOf(drainedAt(3.999, "FR02.018"))).overdue)
        val amber = card(log = listOf(drainedAt(4.0, "FR02.018")))
        assertEquals("Your ring keeps about 7 days of data. Sync soon so nothing is lost.", amber.overdue)
        assertEquals(OverdueLevel.AMBER, amber.overdueLevel)
        val red = card(log = listOf(drainedAt(5.0, "FR02.018")))
        assertEquals("Your ring keeps about 7 days of data. Sync now so nothing is lost.", red.overdue)
        assertEquals(OverdueLevel.RED, red.overdueLevel)
    }

    @Test
    fun aGen3RingFromTheLogsFirmwareIsAmberAtSevenAndRedAtNine() {
        assertNull(card(log = listOf(drainedAt(6.999, "FR05.011"))).overdue)
        val amber = card(log = listOf(drainedAt(7.0, "FR05.011")))
        assertEquals("Your ring keeps about 10 days of data. Sync soon so nothing is lost.", amber.overdue)
        assertEquals(OverdueLevel.RED, card(log = listOf(drainedAt(9.0, "FR05.011"))).overdueLevel)
    }

    @Test
    fun theLiveFirmwareWinsOverTheLoggedOneAndAnUnknownRingGetsGen2Numbers() {
        // Logged as a Gen 2, five days is red; the ring now reports Gen 3, for which five days is fine.
        assertEquals(OverdueLevel.RED, card(log = listOf(drainedAt(5.0, "FR02.018"))).overdueLevel)
        assertNull(card(log = listOf(drainedAt(5.0, "FR02.018")), liveFirmware = "FR05.011").overdue)
        assertEquals(OverdueLevel.AMBER, card(log = listOf(drainedAt(4.0, firmware = null))).overdueLevel)
    }

    @Test
    fun overdueIsMeasuredFromTheLeastDrainedChannelNotTheLastTap() {
        // A sync an hour ago drained the sleep channel; the all-day channel told it nothing, and was
        // last drained 4.5 days ago: the ring may hold records that old.
        val old = drainedAt(4.5)
        val partial = entry(
            SyncOutcome.PARTIAL,
            finishedAt = now.minus(Duration.ofHours(1)),
            channels = listOf(complete("sleep", now.minus(Duration.ofHours(1))), SyncLogChannel(label = "all-day", channel = 3, verdict = HistoryChannelOutcome.NO_ACK)),
        )
        val ui = card(log = listOf(old, partial))
        assertEquals("Last synced 1 h ago", ui.headline)
        assertEquals(OverdueLevel.AMBER, ui.overdueLevel)
    }

    @Test
    fun aChannelNeverDrainedInTheKeptLogCountsFromTheOldestKeptSync() {
        val sleepOnly = { agoDays: Long ->
            entry(
                SyncOutcome.PARTIAL,
                finishedAt = now.minus(day.multipliedBy(agoDays)),
                channels = listOf(complete("sleep", now.minus(day.multipliedBy(agoDays)))),
            )
        }
        val ui = card(log = listOf(sleepOnly(6), sleepOnly(0)))
        assertEquals(OverdueLevel.RED, ui.overdueLevel)
    }

    @Test
    fun noWarningWhileSyncing() {
        val ui = card(log = listOf(drainedAt(8.0)), sync = SyncState(syncing = true, channels = listOf(ChannelProgress("sleep", 6, 30, null, false))))
        assertEquals("Syncing…", ui.headline)
        assertNull(ui.overdue)
        assertEquals(listOf("Sleep channel 6 of 30 records"), ui.progress)
        assertFalse(ui.syncEnabled)
    }

    @Test
    fun whileMeasuringSyncNowIsOffAndSaysWhy() {
        val ui = card(log = listOf(entry(stored = 3)), measuring = true)
        assertFalse(ui.syncEnabled)
        assertEquals("Stop measuring to sync", ui.syncBlockedBy)
    }

    @Test
    fun noSessionStillShowsTheLogAfterARelaunch() {
        val ui = card(log = listOf(entry(stored = 18, finishedAt = now.minus(Duration.ofHours(3)))), sync = null)
        assertEquals("Last synced 3 h ago", ui.headline)
        assertEquals("18 records · complete", ui.lastSync)
        assertFalse(ui.syncEnabled, "no ring to sync")
    }
}
