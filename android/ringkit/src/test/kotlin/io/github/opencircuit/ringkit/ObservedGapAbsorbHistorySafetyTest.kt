package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Does enabling the observed-gap guard rewrite ALREADY-STORED nights? The guard moves a night's
 * in-bed START later by ~2 h on the nights it fires; every stored night is upserted by its night key
 * and the merge policy, so the release either heals history retroactively or leaves it alone. This
 * pins the answer through the two pure decisions that gate the write: WHICH ROW is found (the night
 * key) and WHETHER IT IS OVERWRITTEN (the merge, fed a same-coverage flag computed by the store as
 * "BOTH in-bed edges within one 150 s epoch" — restated here, as upstream does, because it lives in
 * the app's store).
 *
 * The numbers are the measured before/after for the two corpus nights the guard actually moves.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/ObservedGapAbsorbHistorySafetyTests.swift
 * (@ b1c2fdd) — all 3 tests.
 */
class ObservedGapAbsorbHistorySafetyTest {

    /** The store's same-coverage rule, restated: both edges within one ring epoch. */
    private fun sameCoverageAsTheStoreComputesIt(storedStart: Instant, storedEnd: Instant, newStart: Instant, newEnd: Instant): Boolean {
        val storedSpan = if (storedEnd > storedStart) Duration.between(storedStart, storedEnd) else Duration.ZERO
        val newSpan = if (newEnd > newStart) Duration.between(newStart, newEnd) else Duration.ZERO
        val epochTolerance = Duration.ofSeconds(BulkRecord.EPOCH_SECONDS.toLong())
        return storedSpan > Duration.ZERO && newSpan > Duration.ZERO &&
            Duration.between(newStart, storedStart).abs() <= epochTolerance &&
            Duration.between(newEnd, storedEnd).abs() <= epochTolerance
    }

    private fun date(iso: String): Instant = OffsetDateTime.parse(iso).toInstant()

    /** One measured night, guard off vs on. */
    private class Move(
        val id: String,
        val storedStart: Instant,
        val storedEnd: Instant,
        val storedAsleepMin: Int,
        val newStart: Instant,
        val newEnd: Instant,
        val newAsleepMin: Int,
    )

    private val measuredMoves: List<Move>
        get() = listOf(
            // The owner's night: in-bed start 20:24:34 -> 22:18:36, end unchanged.
            Move(
                id = "R3_2026-08-19",
                storedStart = date("2026-08-18T20:24:34-04:00"),
                storedEnd = date("2026-08-19T09:12:41-04:00"),
                storedAsleepMin = 713,
                newStart = date("2026-08-18T22:18:36-04:00"),
                newEnd = date("2026-08-19T09:12:41-04:00"),
                newAsleepMin = 648,
            ),
            // In-bed start 22:51:56 -> 01:17:50 — this one CROSSES MIDNIGHT, exactly the shape that
            // used to alias the upsert key.
            Move(
                id = "R3_2026-08-12",
                storedStart = date("2026-08-11T22:51:56-04:00"),
                storedEnd = date("2026-08-12T09:23:20-04:00"),
                storedAsleepMin = 540,
                newStart = date("2026-08-12T01:17:50-04:00"),
                newEnd = date("2026-08-12T09:23:20-04:00"),
                newAsleepMin = 468,
            ),
        )

    private fun minutes(m: Int): Duration = Duration.ofMinutes(m.toLong())

    /**
     * THE KEY IS INVARIANT. The night key anchors on the in-bed END, and this guard only ever moves
     * the start — so a re-drain lands on the SAME stored row and cannot insert a duplicate night, even
     * when the corrected start crosses midnight.
     */
    @Test
    fun guardNeverChangesTheNightKey() {
        val zone = ZoneId.of("America/New_York")
        for (m in measuredMoves) {
            val before = SleepNightKey.night(inBedStart = m.storedStart, inBedEnd = m.storedEnd, zone = zone)
            val after = SleepNightKey.night(inBedStart = m.newStart, inBedEnd = m.newEnd, zone = zone)
            assertEquals(
                before,
                after,
                "${m.id}: the guard moved the upsert key — a corrected night would be INSERTED alongside the stored one instead of merging with it",
            )
        }
    }

    /**
     * THE STORED NIGHT IS KEPT. Same coverage is false (the start moved far more than one epoch), so
     * the merge judges on time asleep — and the guard only reduces it.
     */
    @Test
    fun alreadyStoredNightsAreMergeProtectedAndKeepTheirValues() {
        for (m in measuredMoves) {
            val sameCoverage = sameCoverageAsTheStoreComputesIt(m.storedStart, m.storedEnd, m.newStart, m.newEnd)
            assertFalse(sameCoverage, "${m.id}: if this were true, the merge would short-circuit to REPLACE and the release WOULD rewrite stored history")

            val replace = SleepSummaryMerge.shouldReplace(
                storedInBed = Duration.between(m.storedStart, m.storedEnd),
                newInBed = Duration.between(m.newStart, m.newEnd),
                storedAsleep = minutes(m.storedAsleepMin),
                newAsleep = minutes(m.newAsleepMin),
                sameCoverage = sameCoverage,
            )
            assertFalse(replace, "${m.id}: the stored night must be KEPT (kept fuller stored night); the fix applies to nights staged from now on, it does not rewrite history")
        }
    }

    /**
     * THE LOAD-BEARING PRECONDITION, STATED AS AN ASSERTION. The protection above holds only because
     * the guard reduces asleep time. If a future change made it ADD sleep, the merge would flip to
     * REPLACE and stored nights WOULD be rewritten. Measured on the corpus: 2 of 2 moving nights
     * reduce asleep, 0 increase it.
     */
    @Test
    fun protectionDependsOnTheGuardOnlyEverReducingAsleep() {
        for (m in measuredMoves) {
            assertTrue(m.newAsleepMin < m.storedAsleepMin, "${m.id}: the guard increased asleep time — re-check the merge policy, because that case is NOT merge-protected")
        }
        // Demonstrate the flip explicitly, so the dependency is visible rather than implied.
        val m = measuredMoves[0]
        assertTrue(
            SleepSummaryMerge.shouldReplace(
                storedInBed = Duration.between(m.storedStart, m.storedEnd),
                newInBed = Duration.between(m.newStart, m.newEnd),
                storedAsleep = minutes(m.storedAsleepMin),
                newAsleep = minutes(m.storedAsleepMin + 1),
                sameCoverage = false,
            ),
            "sanity: MORE asleep does replace — which is why the reduction above is load-bearing",
        )
    }
}
