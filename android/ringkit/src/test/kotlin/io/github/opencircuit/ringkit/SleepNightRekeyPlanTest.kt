package io.github.opencircuit.ringkit

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The DECISION half of the one-shot night-key migration. It rewrites a uniquely-indexed primary key
 * on the user's only copy of their sleep history, behind a one-way latch — and upstream's first
 * version of it shipped a defect (year-0 relocation of legacy rows) that its whole suite did not
 * catch, because nothing exercised this path.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepNightRekeyPlanTests.swift
 * (@ b1c2fdd) — all 12 tests, in America/New_York (passed to every call).
 */
class SleepNightRekeyPlanTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Instant = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant()
    private fun day(y: Int, mo: Int, d: Int): Instant = at(y, mo, d, 0, 0)
    private fun move(from: Instant, to: Instant) = SleepNightRekeyPlan.Move(from = from, to = to)

    /** A row keyed the OLD way (start of day of bedtime), which is what a pre-migration store holds. */
    private fun legacyRow(bed: Instant, wake: Instant) =
        SleepNightRekeyPlan.Row(night = bed.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant(), inBedStart = bed, inBedEnd = wake)

    // MARK: the degenerate-row blocker

    /**
     * The in-bed edges DEFAULT to the distant past for rows written before those columns existed.
     * Judging such a row sends it to the distant past's start of day — year 0 — where it falls
     * outside every date-ranged query, permanently. It must not move.
     */
    @Test
    fun legacyRowWithNoInBedTimesIsNeverMoved() {
        val rows = listOf(SleepNightRekeyPlan.Row(night = day(2026, 3, 1), inBedStart = SleepEdit.DISTANT_PAST, inBedEnd = SleepEdit.DISTANT_PAST))
        val plan = SleepNightRekeyPlan.plan(rows, zone)
        assertTrue(plan.moves.isEmpty(), "a row with no in-bed window must keep the key it has")
        assertTrue(plan.refused.isEmpty(), "and must not be reported as a collision either")
    }

    /**
     * Several legacy rows must ALL stay put — the original defect moved the first one to year 0 and
     * then reported every subsequent one as "destination occupied".
     */
    @Test
    fun manyLegacyRowsAllStayPut() {
        val rows = (1..4).map {
            SleepNightRekeyPlan.Row(night = day(2026, 3, it), inBedStart = SleepEdit.DISTANT_PAST, inBedEnd = SleepEdit.DISTANT_PAST)
        }
        val plan = SleepNightRekeyPlan.plan(rows, zone)
        assertTrue(plan.moves.isEmpty())
        assertTrue(plan.refused.isEmpty())
    }

    @Test
    fun invertedWindowIsNeverMoved() {
        val rows = listOf(SleepNightRekeyPlan.Row(night = day(2026, 8, 7), inBedStart = at(2026, 8, 7, 23, 56), inBedEnd = at(2026, 8, 7, 20, 0)))
        assertTrue(SleepNightRekeyPlan.plan(rows, zone).moves.isEmpty())
    }

    // MARK: ordering

    /**
     * The ordering claim. Every move is +1 day, so a run of consecutive pre-midnight-bedtime nights
     * forms a chain where each row wants the slot of the row above it. Newest-first frees each
     * destination in time; oldest-first would refuse all but the last.
     */
    @Test
    fun chainOfConsecutiveNightsAllMove() {
        val rows = listOf(
            legacyRow(bed = at(2026, 8, 4, 22, 26), wake = at(2026, 8, 5, 8, 59)), // 08-04 -> 08-05
            legacyRow(bed = at(2026, 8, 5, 23, 39), wake = at(2026, 8, 6, 9, 13)), // 08-05 -> 08-06
            legacyRow(bed = at(2026, 8, 6, 22, 10), wake = at(2026, 8, 7, 7, 5)), // 08-06 -> 08-07
        )
        val plan = SleepNightRekeyPlan.plan(rows, zone)
        assertTrue(plan.refused.isEmpty(), "a consecutive chain must not report false collisions")
        assertEquals(
            listOf(
                move(day(2026, 8, 6), day(2026, 8, 7)),
                move(day(2026, 8, 5), day(2026, 8, 6)),
                move(day(2026, 8, 4), day(2026, 8, 5)),
            ),
            plan.moves,
            "moves must be emitted newest-first so each destination is free when applied",
        )
    }

    /** Input order must not matter — the plan sorts internally. */
    @Test
    fun planIsIndependentOfInputOrder() {
        val a = legacyRow(bed = at(2026, 8, 4, 22, 26), wake = at(2026, 8, 5, 8, 59))
        val b = legacyRow(bed = at(2026, 8, 5, 23, 39), wake = at(2026, 8, 6, 9, 13))
        assertEquals(SleepNightRekeyPlan.plan(listOf(a, b), zone), SleepNightRekeyPlan.plan(listOf(b, a), zone))
    }

    // MARK: refusal

    /**
     * Two sleeps ending on the SAME calendar day (biphasic sleep). The one that would have to move is
     * refused, not forced — the night key is uniquely indexed, and a stale key beats a deleted night.
     */
    @Test
    fun collisionWithANonMovingRowIsRefusedNotForced() {
        val rows = listOf(
            // ends 08-08 02:00, keyed 08-07 the old way -> wants 08-08
            legacyRow(bed = at(2026, 8, 7, 22, 0), wake = at(2026, 8, 8, 2, 0)),
            // bed 03:00 wake 08:00 both on 08-08 -> already correct, does not move, holds 08-08
            legacyRow(bed = at(2026, 8, 8, 3, 0), wake = at(2026, 8, 8, 8, 0)),
        )
        val plan = SleepNightRekeyPlan.plan(rows, zone)
        assertTrue(plan.moves.isEmpty())
        assertEquals(listOf(move(day(2026, 8, 7), day(2026, 8, 8))), plan.refused)
    }

    // MARK: idempotence

    /**
     * The caller latches a done-flag, but a failed save retries next launch — so a second pass over
     * ALREADY-MIGRATED rows must plan nothing.
     */
    @Test
    fun secondPassOverMigratedRowsPlansNothing() {
        val rows = listOf(
            SleepNightRekeyPlan.Row(night = day(2026, 8, 5), inBedStart = at(2026, 8, 4, 22, 26), inBedEnd = at(2026, 8, 5, 8, 59)),
            SleepNightRekeyPlan.Row(night = day(2026, 8, 6), inBedStart = at(2026, 8, 5, 23, 39), inBedEnd = at(2026, 8, 6, 9, 13)),
        )
        val plan = SleepNightRekeyPlan.plan(rows, zone)
        assertTrue(plan.moves.isEmpty())
        assertTrue(plan.refused.isEmpty())
    }

    /**
     * A PARTIALLY applied migration (save threw after some rows were mutated) must complete on the
     * retry, not deadlock on the rows that already moved.
     */
    @Test
    fun partiallyMigratedStoreCompletesOnRetry() {
        val rows = listOf(
            SleepNightRekeyPlan.Row(night = day(2026, 8, 7), inBedStart = at(2026, 8, 6, 22, 10), inBedEnd = at(2026, 8, 7, 7, 5)), // already moved
            legacyRow(bed = at(2026, 8, 5, 23, 39), wake = at(2026, 8, 6, 9, 13)), // still 08-05
        )
        val plan = SleepNightRekeyPlan.plan(rows, zone)
        assertEquals(listOf(move(day(2026, 8, 5), day(2026, 8, 6))), plan.moves)
        assertTrue(plan.refused.isEmpty())
    }

    // MARK: mixed / no-op

    @Test
    fun rowsAlreadyCorrectAreLeftAlone() {
        val rows = listOf(legacyRow(bed = at(2026, 8, 3, 1, 34), wake = at(2026, 8, 3, 8, 50)))
        assertTrue(SleepNightRekeyPlan.plan(rows, zone).moves.isEmpty())
    }

    @Test
    fun emptyStorePlansNothing() {
        val plan = SleepNightRekeyPlan.plan(emptyList(), zone)
        assertTrue(plan.moves.isEmpty())
        assertTrue(plan.refused.isEmpty())
    }

    /**
     * DST: the zone observes it. A night spanning the spring-forward transition still moves exactly
     * one calendar day, because the start of day is calendar arithmetic, not ±86 400 s.
     */
    @Test
    fun springForwardNightStillMovesOneCalendarDay() {
        // US spring-forward 2026: 2026-03-08. Bed 23:00 on 03-07, wake 07:00 on 03-08 (23 h day).
        val rows = listOf(legacyRow(bed = at(2026, 3, 7, 23, 0), wake = at(2026, 3, 8, 7, 0)))
        assertEquals(listOf(move(day(2026, 3, 7), day(2026, 3, 8))), SleepNightRekeyPlan.plan(rows, zone).moves)
    }

    /** Fall-back (25 h day) likewise. */
    @Test
    fun fallBackNightStillMovesOneCalendarDay() {
        // US fall-back 2026: 2026-11-01.
        val rows = listOf(legacyRow(bed = at(2026, 10, 31, 23, 0), wake = at(2026, 11, 1, 7, 0)))
        assertEquals(listOf(move(day(2026, 10, 31), day(2026, 11, 1))), SleepNightRekeyPlan.plan(rows, zone).moves)
    }
}
