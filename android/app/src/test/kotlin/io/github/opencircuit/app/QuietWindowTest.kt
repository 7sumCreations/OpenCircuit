package io.github.opencircuit.app

import io.github.opencircuit.app.data.PrefsSyncMarks
import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.ble.LinkState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * No automatic sync inside the night, one catch-up after it, Sync now whenever asked — and, until
 * the first complete sync ever, the link-up sync ignores the night (there is no learned night to
 * protect and the backlog is days long). On virtual time in an explicit zone: America/New_York,
 * the ring linking up at 02:00 EDT (virtual 500 ms after 06:00:00Z).
 *
 * Learned window from three stored nights asleep 23:00 → 07:00 local: 22:00 → 08:30, the learned
 * wake 07:00, the 6 h ceiling 13:00.
 */
class QuietWindowTest {

    private val newYork: ZoneId = ZoneId.of("America/New_York")
    private val twoAm = Instant.parse("2026-10-08T06:00:00Z") // 02:00 EDT

    /** Virtual milliseconds at local [hh]:[mm] on the morning of 2026-10-08 (EDT, UTC−4). */
    private fun local(hh: Int, mm: Int = 0): Long = ((hh - 2) * 60L + mm) * 60_000L

    private suspend fun TestScope.night(completeSyncBefore: Boolean, learned: Boolean, zone: ZoneId = newYork, wallStart: Instant = twoAm): SyncWorld {
        val kv = InMemoryKeyValues()
        // Twelve hours earlier: a complete sync long past the 300 s throttle.
        if (completeSyncBefore) PrefsSyncMarks(kv, TEST_RING_ID).setLastCompleteSync(wallStart.minusSeconds(12 * 3600))
        val w = syncWorld(triggers = true, keyValues = kv, zone = zone, wallStart = wallStart, makeRing = ringWith(HistoryTestPages.backlog(3)))
        if (learned) {
            w.storeNights(
                zone,
                Instant.parse("2026-10-05T03:00:00Z") to Instant.parse("2026-10-05T11:00:00Z"),
                Instant.parse("2026-10-06T03:00:00Z") to Instant.parse("2026-10-06T11:00:00Z"),
                Instant.parse("2026-10-07T03:00:00Z") to Instant.parse("2026-10-07T11:00:00Z"),
            )
        }
        return w
    }

    @Test
    fun linkUpAtTwoWithNoCompleteSyncEverDrains() = runTest {
        val w = night(completeSyncBefore = false, learned = false)
        try {
            advanceTo(60_000)
            assertEquals(1, w.sleepOpens().size, "the first sync is not held by the night")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun linkUpAtTwoWithACompleteSyncAndALearnedWindowDoesNotDrainUntilTheWearerWalksPastTheLearnedWake() = runTest {
        val w = night(completeSyncBefore = true, learned = true)
        try {
            advanceTo(local(6, 30))
            assertEquals(LinkState.Authenticated, w.session.state.value, "the link is held all night")
            assertEquals(emptyList(), w.sleepOpens(), "no drain inside the window")
            // A walk to the bathroom before the learned wake is still the night.
            w.ring.sendDescriptor(walkingDescriptor)
            advanceTo(local(7, 30) - 1)
            assertEquals(emptyList(), w.sleepOpens(), "past the learned wake, still quiet with no walk seen")

            advanceTo(local(7, 30))
            w.ring.sendDescriptor(walkingDescriptor)
            advanceTo(local(7, 30) + 3_000)
            assertEquals(1, w.sleepOpens().size, "the walk at 07:30 ends the quiet: one catch-up drain")
            assertTrue(w.sleepOpens().single() >= local(7, 30), "${w.sleepOpens()}")
            advanceTo(local(9))
            assertEquals(1, w.sleepOpens().size, "exactly one")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun withNoWalkSeenTheCatchUpDrainsAtTheSixHourCeilingOnce() = runTest {
        val w = night(completeSyncBefore = true, learned = true)
        try {
            advanceTo(local(13) - 1)
            assertEquals(emptyList(), w.sleepOpens())
            advanceTo(local(13) + 3_000)
            assertEquals(1, w.sleepOpens().size, "one catch-up at 13:00")
            advanceTo(local(14, 30))
            assertEquals(1, w.sleepOpens().size)
        } finally {
            w.db.close()
        }
    }

    @Test
    fun syncNowAtTwoDrainsWhateverTheWindow() = runTest {
        val w = night(completeSyncBefore = true, learned = true)
        try {
            advanceTo(1_000)
            assertEquals(emptyList(), w.sleepOpens())
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(2_000)
            assertEquals(listOf(1_000L), w.sleepOpens())
        } finally {
            w.db.close()
        }
    }

    @Test
    fun withNoLearnedNightTheFallbackWindowEndsAt1000WithOneCatchUp() = runTest {
        val w = night(completeSyncBefore = true, learned = false)
        try {
            advanceTo(local(10) - 1)
            assertEquals(emptyList(), w.sleepOpens(), "quiet until 10:00")
            advanceTo(local(10) + 3_000)
            assertEquals(1, w.sleepOpens().size)
            advanceTo(local(11))
            assertEquals(1, w.sleepOpens().size)
        } finally {
            w.db.close()
        }
    }

    @Test
    fun withTheSwitchOffTheHeldLinkGetsExactlyOneCatchUpThenTheHourlyCadence() = runTest {
        val w = night(completeSyncBefore = true, learned = false)
        try {
            w.prefs.setDisconnectAfterSync(false)
            advanceTo(local(10) - 1)
            assertEquals(emptyList(), w.sleepOpens())
            advanceTo(local(10, 59))
            assertEquals(1, w.sleepOpens().size, "one catch-up, and the link stays up after it")
            assertEquals(LinkState.Authenticated, w.session.state.value)
            advanceTo(local(11, 5))
            assertEquals(2, w.sleepOpens().size, "then the hourly cadence")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun onTheFallBackNightInLondonTheFallbackCatchUpIsAt0900Gmt() = runTest {
        // 2026-10-25: 02:00 BST → 01:00 GMT. Link-up at 02:00 GMT; the fallback window ends ten
        // hours after local midnight (23:00Z the day before): 09:00Z, seven hours on.
        val w = night(completeSyncBefore = true, learned = false, zone = ZoneId.of("Europe/London"), wallStart = Instant.parse("2026-10-25T02:00:00Z"))
        try {
            advanceTo(7 * 3_600_000L - 1)
            assertEquals(emptyList(), w.sleepOpens())
            advanceTo(7 * 3_600_000L + 3_000)
            assertEquals(1, w.sleepOpens().size)
        } finally {
            w.db.close()
        }
    }
}
