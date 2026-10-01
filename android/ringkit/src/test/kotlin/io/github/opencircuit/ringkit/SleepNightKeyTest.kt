package io.github.opencircuit.ringkit

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The night key: the start of the day a night's in-bed window ENDS on, the single upsert key for a
 * stored night.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepNightKeyTests.swift (@ b1c2fdd) —
 * all 20 tests. Upstream fixes a Gregorian calendar in America/New_York; here that zone is passed
 * to every call.
 */
class SleepNightKeyTest {

    /** America/New_York is the device the collision was proven on (UTC-4 in August). */
    private val zone: ZoneId = ZoneId.of("America/New_York")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Instant = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant()
    private fun day(y: Int, mo: Int, d: Int): Instant = at(y, mo, d, 0, 0)
    private fun startOfDay(t: Instant): Instant = t.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()

    // MARK: the proven collision

    /**
     * The regression this file exists for. Two consecutive real nights from a device on 2026-08-08:
     * bedtime crossed midnight in OPPOSITE directions, so the old start-of-day(inBedStart) key mapped
     * BOTH onto 2026-08-07 and the second night was dropped.
     */
    @Test
    fun consecutiveNightsStraddlingMidnightGetDistinctKeys() {
        // night 08-06 -> 08-07: bed 00:13, wake 08:57 (bedtime AFTER midnight)
        val first = SleepNightKey.night(inBedStart = at(2026, 8, 7, 0, 13), inBedEnd = at(2026, 8, 7, 8, 57), zone = zone)
        // night 08-07 -> 08-08: bed 23:56, wake 09:45 (bedtime BEFORE midnight)
        val second = SleepNightKey.night(inBedStart = at(2026, 8, 7, 23, 56), inBedEnd = at(2026, 8, 8, 9, 45), zone = zone)

        assertEquals(day(2026, 8, 7), first)
        assertEquals(day(2026, 8, 8), second)
        assertNotEquals(first, second, "two consecutive nights must never share an upsert key")

        // And prove the OLD rule is what collided, so this test can't silently pass on a regression
        // that reverts the anchor.
        assertEquals(
            startOfDay(at(2026, 8, 7, 0, 13)),
            startOfDay(at(2026, 8, 7, 23, 56)),
            "start-anchored keys collided — that is the bug being fixed",
        )
    }

    // MARK: the rule

    @Test
    fun keyIsTheWakeDayForAPreMidnightBedtime() {
        assertEquals(day(2026, 8, 5), SleepNightKey.night(inBedStart = at(2026, 8, 4, 22, 26), inBedEnd = at(2026, 8, 5, 8, 59), zone = zone))
    }

    @Test
    fun keyIsUnchangedForANightFullyInsideOneDay() {
        // bed 01:34 -> wake 08:50 on the same calendar day: start- and end-anchored agree.
        assertEquals(day(2026, 8, 3), SleepNightKey.night(inBedStart = at(2026, 8, 3, 1, 34), inBedEnd = at(2026, 8, 3, 8, 50), zone = zone))
    }

    /** A wake exactly at midnight belongs to the day it ends on — the start of day of 00:00 is itself. */
    @Test
    fun wakeExactlyAtMidnight() {
        assertEquals(day(2026, 8, 8), SleepNightKey.night(inBedStart = at(2026, 8, 7, 21, 0), inBedEnd = day(2026, 8, 8), zone = zone))
    }

    // MARK: degenerate windows

    /**
     * An empty or inverted window has no end to anchor to; it must still produce a deterministic key
     * rather than trapping or returning a distant-past sentinel.
     */
    @Test
    fun invertedWindowFallsBackToTheStartDay() {
        assertEquals(day(2026, 8, 7), SleepNightKey.night(inBedStart = at(2026, 8, 7, 23, 56), inBedEnd = at(2026, 8, 7, 20, 0), zone = zone))
    }

    @Test
    fun zeroLengthWindowFallsBackToTheStartDay() {
        val t = at(2026, 8, 7, 23, 56)
        assertEquals(day(2026, 8, 7), SleepNightKey.night(inBedStart = t, inBedEnd = t, zone = zone))
    }

    // MARK: segments overload

    @Test
    fun segmentsOverloadUsesTheEnvelopeNotTheFirstSegment() {
        val segs = listOf(
            SleepSegment(start = at(2026, 8, 7, 23, 56), end = at(2026, 8, 8, 2, 0), stage = SleepStage.ASLEEP_CORE),
            SleepSegment(start = at(2026, 8, 8, 2, 0), end = at(2026, 8, 8, 9, 45), stage = SleepStage.ASLEEP_DEEP),
        )
        assertEquals(day(2026, 8, 8), SleepNightKey.night(segs, zone))
    }

    @Test
    fun segmentsOverloadIsNullWhenThereAreNoSegments() {
        assertNull(SleepNightKey.night(emptyList(), zone))
    }

    /** Out-of-order segments must key off the true envelope, not list position. */
    @Test
    fun segmentsOverloadIsOrderIndependent() {
        val a = SleepSegment(start = at(2026, 8, 8, 2, 0), end = at(2026, 8, 8, 9, 45), stage = SleepStage.ASLEEP_DEEP)
        val b = SleepSegment(start = at(2026, 8, 7, 23, 56), end = at(2026, 8, 8, 2, 0), stage = SleepStage.ASLEEP_CORE)
        assertEquals(SleepNightKey.night(listOf(a, b), zone), SleepNightKey.night(listOf(b, a), zone))
    }

    // MARK: endsInWakeWindow (breaks a same-key tie between a night and an evening fragment)

    @Test
    fun morningWakeOwnsTheKey() {
        assertTrue(SleepNightKey.endsInWakeWindow(at(2026, 8, 8, 7, 0), zone))
        assertTrue(SleepNightKey.endsInWakeWindow(at(2026, 8, 8, 9, 45), zone))
    }

    /**
     * The pre-midnight evening fragment the overnight test lets through: it keys to today, the same
     * key as the night that genuinely ended this morning, and must lose the tie.
     */
    @Test
    fun eveningBlockDoesNotOwnTheKey() {
        assertFalse(SleepNightKey.endsInWakeWindow(at(2026, 8, 8, 23, 20), zone))
        assertFalse(SleepNightKey.endsInWakeWindow(at(2026, 8, 8, 21, 30), zone))
    }

    /**
     * Both halves of a night split by a late hand-off end in the MORNING, so neither is demoted —
     * they fall through to the completeness merge, which is what recovers the fuller one.
     */
    @Test
    fun bothHalvesOfASplitNightEndInTheWakeWindow() {
        assertTrue(SleepNightKey.endsInWakeWindow(at(2026, 8, 3, 1, 0), zone))
        assertTrue(SleepNightKey.endsInWakeWindow(at(2026, 8, 3, 9, 0), zone))
    }

    @Test
    fun wakeWindowBoundaryIsNoon() {
        assertTrue(SleepNightKey.endsInWakeWindow(at(2026, 8, 8, 11, 59), zone))
        assertFalse(SleepNightKey.endsInWakeWindow(at(2026, 8, 8, 12, 0), zone))
    }

    // MARK: rekeyed (drives the one-shot migration)

    @Test
    fun rekeyedIsNullWhenTheStoredKeyIsAlreadyCorrect() {
        assertNull(SleepNightKey.rekeyed(storedNight = day(2026, 8, 3), inBedStart = at(2026, 8, 3, 1, 34), inBedEnd = at(2026, 8, 3, 8, 50), zone = zone))
    }

    @Test
    fun rekeyedMovesAPreMidnightBedtimeRowToItsWakeDay() {
        assertEquals(
            day(2026, 8, 5),
            SleepNightKey.rekeyed(storedNight = day(2026, 8, 4), inBedStart = at(2026, 8, 4, 22, 26), inBedEnd = at(2026, 8, 5, 8, 59), zone = zone),
        )
    }

    /** Idempotence: running the migration twice must move nothing the second time. */
    @Test
    fun rekeyedIsIdempotent() {
        val start = at(2026, 8, 4, 22, 26)
        val end = at(2026, 8, 5, 8, 59)
        val moved = SleepNightKey.rekeyed(storedNight = day(2026, 8, 4), inBedStart = start, inBedEnd = end, zone = zone)
        assertEquals(day(2026, 8, 5), moved)
        assertNull(
            SleepNightKey.rekeyed(storedNight = moved!!, inBedStart = start, inBedEnd = end, zone = zone),
            "a re-keyed row must not move again on a second migration pass",
        )
    }

    /**
     * `rekeyed` must REFUSE a row it has no evidence for. The in-bed edges default to the distant
     * past on rows written before those columns existed, and `night`'s start-anchored fallback maps
     * that to its start of day — year 0. Letting the migration act on it would relocate a real night
     * outside every date-ranged query, behind a one-way latch.
     */
    @Test
    fun rekeyedRefusesARowWithNoInBedWindow() {
        assertNull(SleepNightKey.rekeyed(storedNight = day(2026, 3, 1), inBedStart = SleepEdit.DISTANT_PAST, inBedEnd = SleepEdit.DISTANT_PAST, zone = zone))
    }

    @Test
    fun rekeyedRefusesAnInvertedWindow() {
        assertNull(SleepNightKey.rekeyed(storedNight = day(2026, 8, 7), inBedStart = at(2026, 8, 7, 23, 56), inBedEnd = at(2026, 8, 7, 20, 0), zone = zone))
    }

    /**
     * The unguarded `night` fallback is still start-anchored for the WRITE path — proving the guard
     * lives in `rekeyed`, not in the rule, so this pair can't drift apart unnoticed.
     */
    @Test
    fun degenerateWindowStillKeysDeterministicallyOnTheWritePath() {
        assertEquals(day(2026, 8, 7), SleepNightKey.night(inBedStart = at(2026, 8, 7, 23, 56), inBedEnd = SleepEdit.DISTANT_PAST, zone = zone))
    }

    /**
     * A stored key that isn't already midnight-aligned still compares correctly — the migration must
     * not move a row just because its stored value carries a time component.
     */
    @Test
    fun rekeyedNormalisesTheStoredKeyBeforeComparing() {
        assertNull(SleepNightKey.rekeyed(storedNight = at(2026, 8, 3, 6, 30), inBedStart = at(2026, 8, 3, 1, 34), inBedEnd = at(2026, 8, 3, 8, 50), zone = zone))
    }
}
