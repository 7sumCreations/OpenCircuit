package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Kotlin-only checks for the sleep-window date math and the duration score: zones with a DST
 * change on the reference day (both 2026 transitions, two zones, plus a half-hour zone), hostile
 * clock values, and instants at the edge of what `java.time` can place in a zone.
 *
 * The DST expectations were produced by upstream's Swift `SleepWindow` at the pinned commit with a
 * Gregorian calendar in each zone; Kotlin must land on the same instants.
 */
class SleepWindowHazardTest {

    private fun iso(s: String): Instant = Instant.parse(s)

    private data class DstCase(
        val zone: String,
        val ref: String,
        val interval: Pair<Long, Long>,
        val habitual: Pair<Long, Long>,
        val overnight: Boolean,
    )

    // bed 22:30 -> wake 06:30; habitual nights at ref-15h/-39h/-63h onsets, ref-7h/-31h/-55h wakes.
    private val dstCases = listOf(
        DstCase("America/New_York", "2026-03-08T18:00:00Z", 1772940600L to 1772969400L, 1772935200L to 1772973000L, false),
        DstCase("America/New_York", "2026-11-01T18:00:00Z", 1793500200L to 1793529000L, 1793498400L to 1793536200L, true),
        DstCase("Europe/London", "2026-03-29T14:00:00Z", 1774737000L to 1774765800L, 1774735200L to 1774773000L, false),
        DstCase("Europe/London", "2026-10-25T14:00:00Z", 1792877400L to 1792906200L, 1792879200L to 1792917000L, false),
        DstCase("Asia/Kolkata", "2026-06-15T10:00:00Z", 1781456400L to 1781485200L, 1781460000L to 1781497800L, false),
    )

    @Test
    fun dstTransitionDaysMatchUpstreamInstants() {
        for (c in dstCases) {
            val zone = ZoneId.of(c.zone)
            val ref = iso(c.ref)
            val w = assertNotNull(SleepWindow.interval(bedMinutes = 1350, wakeMinutes = 390, nightEndingNear = ref, zone = zone))
            assertEquals(c.interval, w.start.epochSecond to w.end.epochSecond, "interval ${c.zone} ${c.ref}")
            val hw = assertNotNull(
                SleepWindow.habitualInterval(
                    onsets = listOf(15L, 39L, 63L).map { ref.minus(Duration.ofHours(it)) },
                    wakes = listOf(7L, 31L, 55L).map { ref.minus(Duration.ofHours(it)) },
                    nightEndingNear = ref,
                    zone = zone,
                ),
            )
            assertEquals(c.habitual, hw.start.epochSecond to hw.end.epochSecond, "habitual ${c.zone} ${c.ref}")
            assertEquals(
                c.overnight,
                SleepWindow.isOvernightBlock(start = ref.minus(Duration.ofHours(8)), end = ref.minus(Duration.ofHours(1)), zone = zone),
                "overnight ${c.zone} ${c.ref}",
            )
        }
    }

    @Test
    fun minutesHelperKeepsUpstreamRemainderAndNeverWraps() {
        assertEquals(-360, SleepWindow.minutes(hour = -30, minute = 0), "upstream returns a negative remainder here")
        assertEquals(60, SleepWindow.minutes(hour = 25, minute = 0))
        // Upstream's 64-bit arithmetic: (2147483647 * 60 + 1440) % 1440 = 420. A 32-bit wrap gives 1380.
        assertEquals(420, SleepWindow.minutes(hour = Int.MAX_VALUE, minute = 0))
    }

    @Test
    fun instantsBeyondTheZonedRangeAreRejectedNotThrown() {
        // The last year of Instant's range cannot be placed in a zone. Upstream's Foundation
        // calendar returned a wrapped, wrong year there; here the window is refused instead.
        val edge = Instant.MAX
        val utc = ZoneOffset.UTC
        assertNull(SleepWindow.interval(bedMinutes = 1350, wakeMinutes = 390, nightEndingNear = edge, zone = utc))
        assertFalse(SleepWindow.isOvernightBlock(start = edge, end = edge, zone = utc))
        assertFalse(SleepWindow.isOvernightBlock(start = edge, end = edge, onsetIsUnobserved = true, zone = utc))
        assertNull(SleepWindow.habitualInterval(listOf(edge, edge, edge), listOf(edge, edge, edge), nightEndingNear = iso("2026-06-15T14:00:00Z"), zone = utc))
        assertNull(SleepWindow.interval(bedMinutes = 1350, wakeMinutes = 390, nightEndingNear = Instant.MIN, zone = utc))
    }

    @Test
    fun habitualIntervalWithNoNightsAndNoMinimumUsesMidnight() {
        // minNights = 0 lets an empty history through: both medians are 0 (midnight), as upstream.
        val w = assertNotNull(
            SleepWindow.habitualInterval(emptyList(), emptyList(), nightEndingNear = Instant.ofEpochSecond(1_700_000_000), minNights = 0, zone = ZoneOffset.UTC),
        )
        assertEquals(iso("2023-11-14T23:00:00Z") to iso("2023-11-15T01:30:00Z"), w.start to w.end)
    }

    @Test
    fun durationScoreClampsHostileSpans() {
        assertEquals(0.0, SleepScore.score(durationSeconds = -100))
        assertEquals(100.0, SleepScore.score(durationSeconds = Long.MAX_VALUE))
        val t = Instant.ofEpochSecond(1_700_000_000)
        assertEquals(0.0, SleepScore.score(start = t.plusSeconds(100), end = t), "end before start scores 0")
    }
}
