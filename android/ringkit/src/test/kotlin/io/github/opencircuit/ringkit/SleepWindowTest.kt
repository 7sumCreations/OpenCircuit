package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepWindowTests.swift (@ b1c2fdd):
 * all 14 tests. Window math for the sleep-schedule abstraction — pure date math in a fixed UTC zone
 * so the assertions are timezone-stable. Upstream's Gregorian UTC `Calendar` is an explicit
 * [ZoneId] here; nothing reads the machine's default zone.
 */
class SleepWindowTest {

    /** Fixed UTC zone so "local midnight" is deterministic. */
    private val utc: ZoneId = ZoneId.of("UTC")

    private fun date(iso: String): Instant = Instant.parse(iso)

    // bed 22:30 → wake 06:30: an 8 h window that crosses midnight. Reference is the afternoon AFTER
    // the night, so the chosen wake is that same morning and the bedtime lands on the PREVIOUS day.
    @Test
    fun crossMidnightWindow() { // :24-34
        val ref = date("2026-06-15T14:00:00Z") // afternoon
        val w = SleepWindow.interval(bedMinutes = 22 * 60 + 30, wakeMinutes = 6 * 60 + 30, nightEndingNear = ref, zone = utc)
        assertNotNull(w)
        assertEquals(date("2026-06-15T06:30:00Z"), w.end) // this morning's wake
        assertEquals(date("2026-06-14T22:30:00Z"), w.start) // previous evening's bed
        assertEquals(8 * 3600.0, w.duration.seconds.toDouble(), 1.0)
    }

    // bed 01:00 → wake 06:30: a same-day (no midnight cross) window.
    @Test
    fun sameDayWindow() { // :37-46
        val ref = date("2026-06-15T14:00:00Z")
        val w = SleepWindow.interval(bedMinutes = 1 * 60, wakeMinutes = 6 * 60 + 30, nightEndingNear = ref, zone = utc)
        assertEquals(date("2026-06-15T01:00:00Z"), w?.start)
        assertEquals(date("2026-06-15T06:30:00Z"), w?.end)
        assertEquals(5.5 * 3600, (w?.duration ?: Duration.ZERO).seconds.toDouble(), 1.0)
    }

    // The wake nearest the reference is chosen. At 02:00 (mid-sleep) the in-progress night's wake
    // (a few hours ahead) is nearer than the prior morning's.
    @Test
    fun picksNearestWake() { // :50-58
        val ref = date("2026-06-15T02:00:00Z")
        val w = SleepWindow.interval(bedMinutes = 22 * 60 + 30, wakeMinutes = 6 * 60 + 30, nightEndingNear = ref, zone = utc)
        assertEquals(date("2026-06-15T06:30:00Z"), w?.end)
        assertEquals(date("2026-06-14T22:30:00Z"), w?.start)
    }

    // A degenerate bed == wake schedule yields no window (rather than a 24 h or 0-length one).
    @Test
    fun degenerateScheduleIsNil() { // :61-65
        val ref = date("2026-06-15T14:00:00Z")
        assertNull(SleepWindow.interval(bedMinutes = 390, wakeMinutes = 390, nightEndingNear = ref, zone = utc))
    }

    @Test
    fun minutesHelper() { // :67-70
        assertEquals(1350, SleepWindow.minutes(hour = 22, minute = 30))
        assertEquals(0, SleepWindow.minutes(hour = 0, minute = 0))
    }

    // isOvernightBlock — gate that keeps a worn daytime block from being staged as "last night".

    // A pre-midnight onset (23:00 → 07:00) is overnight.
    @Test
    fun overnightPreMidnight() { // :76-79
        assertTrue(SleepWindow.isOvernightBlock(start = date("2026-06-14T23:00:00Z"), end = date("2026-06-15T07:00:00Z"), zone = utc))
    }

    // A post-midnight onset (01:00 → 08:00) is overnight (matches the PRIOR day's night anchor).
    @Test
    fun overnightPostMidnight() { // :82-85
        assertTrue(SleepWindow.isOvernightBlock(start = date("2026-06-15T01:00:00Z"), end = date("2026-06-15T08:00:00Z"), zone = utc))
    }

    // An afternoon nap (13:00 → 15:00) is NOT overnight — the case the gate exists to reject.
    @Test
    fun afternoonNapNotOvernight() { // :88-91
        assertFalse(SleepWindow.isOvernightBlock(start = date("2026-06-15T13:00:00Z"), end = date("2026-06-15T15:00:00Z"), zone = utc))
    }

    // A long sedentary daytime block (10:00 → 16:00, e.g. a meeting/movie marathon) is NOT overnight.
    @Test
    fun longDaytimeBlockNotOvernight() { // :94-97
        assertFalse(SleepWindow.isOvernightBlock(start = date("2026-06-15T10:00:00Z"), end = date("2026-06-15T16:00:00Z"), zone = utc))
    }

    // An early-evening onset (19:30 → 04:00) is overnight (overlaps the same-day 18:00 anchor).
    @Test
    fun earlyEveningOnsetOvernight() { // :100-103
        assertTrue(SleepWindow.isOvernightBlock(start = date("2026-06-14T19:30:00Z"), end = date("2026-06-15T04:00:00Z"), zone = utc))
    }

    // habitualInterval — the adaptive skin-temp capture window (tracks real sleep hours)

    /** A consistently LATE sleeper (onset ~00:30, wake ~09:30) must get a window that COVERS that night. */
    @Test
    fun lateSleeperWindowCoversTheNight() { // :111-124
        val onsets = listOf(date("2026-06-13T00:30:00Z"), date("2026-06-14T00:40:00Z"), date("2026-06-15T00:20:00Z"))
        val wakes = listOf(date("2026-06-13T09:30:00Z"), date("2026-06-14T09:20:00Z"), date("2026-06-15T09:40:00Z"))
        val ref = date("2026-06-15T14:00:00Z")
        val w = assertNotNull(SleepWindow.habitualInterval(onsets, wakes, nightEndingNear = ref, zone = utc))
        // The actual night (00:37 → 09:34) falls inside the learned window.
        assertTrue(!w.start.isAfter(date("2026-06-15T00:37:00Z")), "window starts before real onset")
        assertTrue(!w.end.isBefore(date("2026-06-15T09:34:00Z")), "window ends after real wake — the fix")
        // And the old fixed default (06:30) would NOT have covered the 09:34 wake.
        assertTrue(w.end.isAfter(date("2026-06-15T06:30:00Z")))
    }

    /** Onsets straddling midnight (23:50 and 00:30) must average to ~00:10, not to midday. */
    @Test
    fun onsetsAcrossMidnightAverageCorrectly() { // :128-139
        val onsets = listOf(date("2026-06-12T23:50:00Z"), date("2026-06-14T00:30:00Z"), date("2026-06-15T00:10:00Z"))
        val wakes = listOf(date("2026-06-13T07:00:00Z"), date("2026-06-14T07:10:00Z"), date("2026-06-15T06:50:00Z"))
        val ref = date("2026-06-15T14:00:00Z")
        val w = assertNotNull(
            SleepWindow.habitualInterval(onsets, wakes, nightEndingNear = ref, bedMargin = Duration.ZERO, wakeMargin = Duration.ZERO, zone = utc),
        )
        // Median onset ≈ 00:10 → window start that morning's 00:10 (not ~12:00).
        assertEquals(date("2026-06-15T00:10:00Z"), w.start)
        assertEquals(date("2026-06-15T07:00:00Z"), w.end)
    }

    /** Median (not mean) ignores a single fragmented outlier night so the window stays anchored. */
    @Test
    fun outlierNightDoesNotDragWindow() { // :143-156
        val onsets = listOf(
            date("2026-06-12T22:30:00Z"), date("2026-06-13T22:40:00Z"),
            date("2026-06-14T04:00:00Z"), // outlier
            date("2026-06-15T22:35:00Z"),
        )
        val wakes = listOf(
            date("2026-06-13T06:30:00Z"), date("2026-06-14T06:40:00Z"),
            date("2026-06-14T05:00:00Z"), // outlier
            date("2026-06-16T06:35:00Z"),
        )
        val ref = date("2026-06-16T14:00:00Z")
        val w = assertNotNull(
            SleepWindow.habitualInterval(onsets, wakes, nightEndingNear = ref, bedMargin = Duration.ZERO, wakeMargin = Duration.ZERO, zone = utc),
        )
        // Onset median ≈ 22:35 (the outlier 04:00 is unwrapped to 28:00 and sorts last → not picked).
        assertTrue(w.start.atZone(utc).hour >= 22, "habitual evening onset, not the 04:00 outlier")
    }

    /** Fewer than `minNights` usable nights ⇒ null (caller falls back to the fixed default). */
    @Test
    fun insufficientHistoryYieldsNil() { // :159-164
        val ref = date("2026-06-15T14:00:00Z")
        assertNull(
            SleepWindow.habitualInterval(listOf(date("2026-06-15T00:30:00Z")), listOf(date("2026-06-15T09:30:00Z")), nightEndingNear = ref, zone = utc),
            "one night is not enough to trust a window",
        )
    }
}
