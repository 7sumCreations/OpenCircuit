package io.github.opencircuit.app

import io.github.opencircuit.app.today.TodayAlertLevel
import io.github.opencircuit.app.today.TodayInput
import io.github.opencircuit.app.today.TodayPresenter
import io.github.opencircuit.app.today.TodaySession
import io.github.opencircuit.app.today.TodayStored
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ringkit.HistoryChannelOutcome
import io.github.opencircuit.store.SyncLogChannel
import io.github.opencircuit.store.SyncLogEntry
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Today's overdue chip: the Ring data card's own per-model warning (Gen 2 / Air amber at 4 days,
 * red at 5; Gen 3 amber at 7, red at 9 — measured from the least-drained channel) with its own
 * words, and a "never synced" state for a ring with no sync yet. No new thresholds: the numbers
 * here are typed, so moving one in the shared rule turns this red.
 */
class TodayOverdueTest {

    private val now: Instant = Instant.parse("2026-10-08T09:00:00Z")

    private fun drainedAt(agoDays: Double, firmware: String?): SyncLogEntry {
        val at = now.minusMillis((agoDays * 86_400_000).toLong())
        val channels = listOf("sleep" to 0, "all-day" to 3).map { (label, ch) ->
            SyncLogChannel(label = label, channel = ch, verdict = HistoryChannelOutcome.COMPLETE, drainedThrough = at)
        }
        return SyncLogEntry(startedAt = at.minusSeconds(60), finishedAt = at, outcome = "COMPLETE", channels = channels, firmware = firmware)
    }

    private fun today(log: List<SyncLogEntry>, liveFirmware: String? = null, syncing: Boolean = false, link: LinkState? = LinkState.Authenticated) =
        TodayPresenter.present(
            TodayInput(TodayStored(), TodaySession(link = link, log = log, syncing = syncing, liveFirmware = liveFirmware), now, ZoneOffset.UTC),
        )

    @Test
    fun aGen2RingIsAmberAtFourDaysAndRedAtFive() {
        assertNull(today(listOf(drainedAt(3.999, "FR02.018"))).overdue)
        val amber = assertNotNull(today(listOf(drainedAt(4.0, "FR02.018"))).overdue)
        assertEquals(TodayAlertLevel.AMBER, amber.level)
        assertEquals("Your ring keeps about 7 days of data. Sync soon so nothing is lost.", amber.text)
        assertTrue(amber.canSync)
        val red = assertNotNull(today(listOf(drainedAt(5.0, "FR02.018"))).overdue)
        assertEquals(TodayAlertLevel.RED, red.level)
        assertEquals("Your ring keeps about 7 days of data. Sync now so nothing is lost.", red.text)
    }

    @Test
    fun aGen2AirRingUsesTheGen2Thresholds() {
        assertNull(today(listOf(drainedAt(3.999, "FR04.003"))).overdue)
        assertEquals(TodayAlertLevel.AMBER, today(listOf(drainedAt(4.0, "FR04.003"))).overdue?.level)
        assertEquals(TodayAlertLevel.RED, today(listOf(drainedAt(5.0, "FR04.003"))).overdue?.level)
    }

    @Test
    fun aGen3RingIsAmberAtSevenDaysAndRedAtNine() {
        assertNull(today(listOf(drainedAt(6.999, "FR05.011"))).overdue)
        assertEquals(TodayAlertLevel.AMBER, today(listOf(drainedAt(7.0, "FR05.011"))).overdue?.level)
        assertEquals(TodayAlertLevel.AMBER, today(listOf(drainedAt(8.999, "FR05.011"))).overdue?.level)
        assertEquals(TodayAlertLevel.RED, today(listOf(drainedAt(9.0, "FR05.011"))).overdue?.level)
        // The firmware the ring reports now decides the model over the log's.
        assertNull(today(listOf(drainedAt(5.0, "FR02.018")), liveFirmware = "FR05.011").overdue)
    }

    @Test
    fun whileASyncRunsTheChipAsksToLetItFinishAndOffersNoSecondSync() {
        val chip = assertNotNull(today(listOf(drainedAt(5.0, "FR02.018")), syncing = true).overdue)
        assertEquals(TodayAlertLevel.RED, chip.level)
        assertEquals("Your ring keeps about 7 days of data. Keep the app open until this sync finishes so nothing is lost.", chip.text)
        assertFalse(chip.canSync)
    }

    @Test
    fun aRingNeverSyncedSaysSo() {
        val chip = assertNotNull(today(emptyList()).overdue)
        assertEquals(TodayAlertLevel.NEVER_SYNCED, chip.level)
        assertEquals("This ring hasn't synced yet. Sync to bring its history to your phone.", chip.text)
        assertTrue(chip.canSync)
        assertNull(today(emptyList(), syncing = true).overdue, "the first sync runs: the strip says so")
        assertNull(today(emptyList(), link = null).overdue, "no ring: the hero says where to pair one")
        assertNull(today(listOf(drainedAt(0.1, "FR02.018"))).overdue, "synced recently")
    }
}
