package io.github.opencircuit.ringkit

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Recency math shared by the Goals sleep-ring credit and the Sleep-card missed-night banner. Pure date
 * math on a fixed UTC calendar so "local midnight" is deterministic. Schedule under test: bed 22:30 →
 * wake 06:30 (an 8 h window crossing midnight).
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/MissedNightTests.swift (@ b1c2fdd) —
 * all 18 tests. Upstream's `now` defaults to the clock; here every call passes it.
 */
class MissedNightTest {

    private val utc: ZoneId = ZoneOffset.UTC

    private fun date(iso: String): Instant = Instant.parse(iso)

    private val bed = 22 * 60 + 30 // 22:30
    private val wake = 6 * 60 + 30 // 06:30

    // MARK: morningWake — stays pinned to THIS morning's wake all waking day

    @Test
    fun morningWakeMorning() {
        val w = MissedNight.morningWake(now = date("2026-06-15T08:00:00Z"), bedMinutes = bed, wakeMinutes = wake, zone = utc)
        assertEquals(date("2026-06-15T06:30:00Z"), w)
    }

    @Test
    fun morningWakeEveningStillTodaysWake() {
        // 20:00 — the NEAREST wake is tomorrow 06:30; morningWake must step back to today's 06:30 so
        // the banner doesn't vanish in the evening.
        val w = MissedNight.morningWake(now = date("2026-06-15T20:00:00Z"), bedMinutes = bed, wakeMinutes = wake, zone = utc)
        assertEquals(date("2026-06-15T06:30:00Z"), w)
    }

    @Test
    fun morningWakeLateEveningBeforeBed() {
        // 22:00, just before tonight's bed — still today's wake.
        val w = MissedNight.morningWake(now = date("2026-06-15T22:00:00Z"), bedMinutes = bed, wakeMinutes = wake, zone = utc)
        assertEquals(date("2026-06-15T06:30:00Z"), w)
    }

    @Test
    fun morningWakeMidSleepIsAheadOfNow() {
        // 03:00, mid-sleep — this morning's wake (today 06:30) is still AHEAD of now, so the
        // `now > wake` gate keeps a 3 a.m. glance from ever claiming a miss.
        val w = MissedNight.morningWake(now = date("2026-06-15T03:00:00Z"), bedMinutes = bed, wakeMinutes = wake, zone = utc)
        assertEquals(date("2026-06-15T06:30:00Z"), w)
        assertTrue(assertNotNull(w) > date("2026-06-15T03:00:00Z")) // future ⇒ gate false
    }

    @Test
    fun morningWakeDegenerateScheduleNull() {
        assertNull(MissedNight.morningWake(now = date("2026-06-15T08:00:00Z"), bedMinutes = 390, wakeMinutes = 390, zone = utc))
    }

    // MARK: endedToday — the credit gate

    @Test
    fun endedTodayNightEndedToday() {
        // Night ended this morning → credited.
        assertTrue(
            MissedNight.endedToday(
                inBedEnd = date("2026-06-15T06:24:00Z"),
                nightKey = date("2026-06-14T00:00:00Z"),
                now = date("2026-06-15T09:00:00Z"),
                zone = utc,
            ),
        )
    }

    @Test
    fun endedTwoDaysAgoNotCredited() {
        // A 2-day-old night still has positive minutes but must NOT be credited.
        assertFalse(
            MissedNight.endedToday(
                inBedEnd = date("2026-06-13T06:30:00Z"),
                nightKey = date("2026-06-12T00:00:00Z"),
                now = date("2026-06-15T09:00:00Z"),
                zone = utc,
            ),
        )
    }

    @Test
    fun legacyDistantPastEndUsesNightKeyNotCredited() {
        // Legacy rollup: in-bed end unknown (null) → falls back to the start-of-day key, which on a
        // later day is not today → not credited.
        assertFalse(
            MissedNight.endedToday(inBedEnd = null, nightKey = date("2026-06-13T00:00:00Z"), now = date("2026-06-15T09:00:00Z"), zone = utc),
        )
    }

    @Test
    fun legacyNightKeyIsTodayIsCredited() {
        // A legacy rollup whose start-of-day key IS today → credited (best available signal).
        assertTrue(
            MissedNight.endedToday(inBedEnd = null, nightKey = date("2026-06-15T00:00:00Z"), now = date("2026-06-15T09:00:00Z"), zone = utc),
        )
    }

    // MARK: status / isMissing — the banner

    private fun status(now: String, nightWake: Instant?, wakeKnown: Boolean = true, lastSyncAt: Instant?): MissedNight.Status =
        MissedNight.status(
            now = date(now),
            bedMinutes = bed,
            wakeMinutes = wake,
            nightWake = nightWake,
            wakeKnown = wakeKnown,
            lastSyncAt = lastSyncAt,
            zone = utc,
        )

    // The core acceptance case: a STALE stored night (ended 2 days ago) with a post-wake sync.
    private val staleNight = "2026-06-13T06:30:00Z"

    @Test
    fun missingInMorningAfterPostWakeSync() {
        // 08:00, sync completed at 07:30 (after this morning's 06:30 wake), stale night → missing.
        assertEquals(
            MissedNight.Status.MISSING,
            status(now = "2026-06-15T08:00:00Z", nightWake = date(staleNight), lastSyncAt = date("2026-06-15T07:30:00Z")),
        )
    }

    @Test
    fun stillMissingInEvening() {
        // 20:00 same day, same post-wake sync — must STILL be missing.
        assertEquals(
            MissedNight.Status.MISSING,
            status(now = "2026-06-15T20:00:00Z", nightWake = date(staleNight), lastSyncAt = date("2026-06-15T07:30:00Z")),
        )
    }

    @Test
    fun notSyncedYetBeforeAnyPostWakeSync() {
        // 08:00 but the last sync was YESTERDAY (before this morning's wake) → not synced yet, NOT an
        // alarming miss.
        assertEquals(
            MissedNight.Status.NOT_SYNCED_YET,
            status(now = "2026-06-15T08:00:00Z", nightWake = date(staleNight), lastSyncAt = date("2026-06-14T18:00:00Z")),
        )
    }

    @Test
    fun notSyncedYetWhenNoSyncEverRecorded() {
        assertEquals(MissedNight.Status.NOT_SYNCED_YET, status(now = "2026-06-15T08:00:00Z", nightWake = date(staleNight), lastSyncAt = null))
    }

    @Test
    fun notMissingWhenNightEndedToday() {
        // Night ended today → ok even long after wake and after a sync.
        assertEquals(
            MissedNight.Status.OK,
            status(now = "2026-06-15T20:00:00Z", nightWake = date("2026-06-15T06:24:00Z"), lastSyncAt = date("2026-06-15T07:30:00Z")),
        )
    }

    @Test
    fun notMissingMidSleep() {
        // 03:00 mid-sleep, stale stored night, a sync landed yesterday — must NOT flag a miss before
        // this morning's wake.
        assertEquals(
            MissedNight.Status.OK,
            status(now = "2026-06-15T03:00:00Z", nightWake = date(staleNight), lastSyncAt = date("2026-06-14T18:00:00Z")),
        )
    }

    @Test
    fun legacyWakeUnknownNeverMissing() {
        assertEquals(
            MissedNight.Status.OK,
            status(now = "2026-06-15T08:00:00Z", nightWake = null, wakeKnown = false, lastSyncAt = date("2026-06-15T07:30:00Z")),
        )
    }

    // The exact acceptance assertions spelled out upstream.
    @Test
    fun acceptanceIsMissingMatrix() {
        fun missing(now: String, sync: Instant?): Boolean = MissedNight.isMissing(
            now = date(now),
            bedMinutes = bed,
            wakeMinutes = wake,
            nightWake = date(staleNight),
            wakeKnown = true,
            lastSyncAt = sync,
            zone = utc,
        )
        assertTrue(missing("2026-06-15T08:00:00Z", date("2026-06-15T07:30:00Z"))) // post-wake sync
        assertTrue(missing("2026-06-15T20:00:00Z", date("2026-06-15T07:30:00Z"))) // evening, same
        assertFalse(missing("2026-06-15T08:00:00Z", date("2026-06-14T18:00:00Z"))) // no post-wake sync
    }

    // MARK: cross-check the invariant that ties the banner and the credit together

    @Test
    fun missingImpliesCreditWithheld() {
        // For every hour of the day, whenever the banner says MISSING, the credit gate must be false
        // (empty ring) — the two surfaces can never contradict.
        for (hour in 0 until 24) {
            val now = date(String.format(Locale.ROOT, "2026-06-15T%02d:00:00Z", hour))
            val st = MissedNight.status(
                now = now,
                bedMinutes = bed,
                wakeMinutes = wake,
                nightWake = date(staleNight),
                wakeKnown = true,
                lastSyncAt = date("2026-06-15T07:30:00Z"),
                zone = utc,
            )
            if (st == MissedNight.Status.MISSING) {
                assertFalse(
                    MissedNight.endedToday(inBedEnd = date(staleNight), nightKey = date("2026-06-12T00:00:00Z"), now = now, zone = utc),
                    "credit must be withheld while banner is MISSING @${hour}h",
                )
            }
        }
    }
}
