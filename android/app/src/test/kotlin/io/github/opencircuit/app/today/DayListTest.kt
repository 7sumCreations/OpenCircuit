package io.github.opencircuit.app.today

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The days Today can browse: every local date from the day of the first stored reading to today, in
 * an explicit zone. A day is a calendar date, so a daylight-saving day is one day (23 or 25 hours
 * long, its bounds from `atStartOfDay`), and the last day changes at local midnight, not at a
 * fixed 24 hours from anything.
 */
class DayListTest {

    private val london: ZoneId = ZoneId.of("Europe/London")
    private val utc: ZoneId = ZoneOffset.UTC

    @Test
    fun theDaysRunFromTheFirstStoredReadingsLocalDayToToday() {
        // 23:30 UTC on the 18th is 00:30 on the 19th in London (summer time).
        val first = Instant.parse("2026-10-18T23:30:00Z")
        val now = Instant.parse("2026-10-21T10:00:00Z")
        val inLondon = BrowsableDays.of(first, now, london)
        assertEquals(LocalDate.of(2026, 10, 19), inLondon.first)
        assertEquals(LocalDate.of(2026, 10, 21), inLondon.last)
        assertEquals(3, inLondon.size)
        val inUtc = BrowsableDays.of(first, now, utc)
        assertEquals(LocalDate.of(2026, 10, 18), inUtc.first, "the same reading is on the 18th in UTC")
        assertEquals(4, inUtc.size)

        assertEquals(LocalDate.of(2026, 10, 19), inLondon.dateAt(0))
        assertEquals(LocalDate.of(2026, 10, 21), inLondon.dateAt(2))
        assertEquals(2, inLondon.indexOf(LocalDate.of(2026, 10, 21)))
        assertEquals(-1, inLondon.indexOf(LocalDate.of(2026, 10, 18)), "before the first stored day")
        assertEquals(-1, inLondon.indexOf(LocalDate.of(2026, 10, 22)), "after today")
        assertFalse(LocalDate.of(2026, 10, 22) in inLondon)
        assertTrue(LocalDate.of(2026, 10, 20) in inLondon)
    }

    @Test
    fun withNothingStoredOrAFirstReadingDatedAfterTodayOnlyTodayIsThere() {
        val now = Instant.parse("2026-10-21T10:00:00Z")
        val empty = BrowsableDays.of(null, now, london)
        assertEquals(1, empty.size)
        assertEquals(LocalDate.of(2026, 10, 21), empty.dateAt(0))
        // A ring with a wrong clock can date a reading in the future: the list still ends today.
        val future = BrowsableDays.of(Instant.parse("2026-11-02T08:00:00Z"), now, london)
        assertEquals(1, future.size)
        assertEquals(LocalDate.of(2026, 10, 21), future.last)
    }

    @Test
    fun aDaylightSavingDayIsOneDayWithItsOwnLength() {
        val spring = LocalDate.of(2026, 3, 29)
        val autumn = LocalDate.of(2026, 10, 25)
        val (springStart, springEnd) = BrowsableDays.bounds(spring, london)
        val (autumnStart, autumnEnd) = BrowsableDays.bounds(autumn, london)
        assertEquals(Duration.ofHours(23), Duration.between(springStart, springEnd))
        assertEquals(Duration.ofHours(25), Duration.between(autumnStart, autumnEnd))
        assertEquals(Instant.parse("2026-10-24T23:00:00Z"), autumnStart, "midnight in summer time")
        assertEquals(Instant.parse("2026-10-26T00:00:00Z"), autumnEnd, "midnight in winter time")

        // Across the change, still one entry per date: three dates, three days.
        val days = BrowsableDays.of(Instant.parse("2026-03-28T12:00:00Z"), Instant.parse("2026-03-30T12:00:00Z"), london)
        assertEquals(listOf(LocalDate.of(2026, 3, 28), spring, LocalDate.of(2026, 3, 30)), (0 until days.size).map(days::dateAt))
    }

    @Test
    fun theLastDayChangesAtLocalMidnight() {
        val first = Instant.parse("2026-10-01T09:00:00Z")
        // 22:59:59 UTC is 23:59:59 in London (summer time); one second later it is the 22nd there.
        val beforeMidnight = BrowsableDays.of(first, Instant.parse("2026-10-21T22:59:59Z"), london)
        val afterMidnight = BrowsableDays.of(first, Instant.parse("2026-10-21T23:00:00Z"), london)
        assertEquals(LocalDate.of(2026, 10, 21), beforeMidnight.last)
        assertEquals(LocalDate.of(2026, 10, 22), afterMidnight.last)
        assertEquals(beforeMidnight.size + 1, afterMidnight.size)
        // In UTC the same instant is still the 21st.
        assertEquals(LocalDate.of(2026, 10, 21), BrowsableDays.of(first, Instant.parse("2026-10-21T23:00:00Z"), utc).last)
    }

    @Test
    fun yearsOfDaysAreCountedNotListed() {
        val days = BrowsableDays.of(Instant.parse("2023-10-21T12:00:00Z"), Instant.parse("2026-10-21T12:00:00Z"), utc)
        assertEquals(1_097, days.size)
        for (i in listOf(0, 1, 500, days.size - 1)) assertEquals(i, days.indexOf(days.dateAt(i)))
    }
}
