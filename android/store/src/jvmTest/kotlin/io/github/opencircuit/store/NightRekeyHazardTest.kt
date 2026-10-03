package io.github.opencircuit.store

import androidx.room3.withWriteTransaction
import io.github.opencircuit.ringkit.SleepNightRekeyPlan
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * What the tables and the plan do before the move of stored nights onto their wake day relies on
 * them (upstream `rekeySleepNightsToWakeDay`, ios/OpenCircuit/Store/LocalStore.swift:2600-2709 @
 * b1c2fdd, and the cursor rename :2558-2571).
 */
class NightRekeyHazardTest {

    private val t0 = Instant.parse("2025-06-10T00:00:00Z")

    private fun day(n: Long): Instant = t0.plusSeconds(n * 86_400)

    /** A night stored on the day it started (the bedtime-day key), its window ending the next morning. */
    private fun bedtimeKeyed(n: Long) = StoredSleepSummaryEntity(
        night = day(n),
        asleepMin = 400 + n.toInt(),
        inBedStart = day(n).plusSeconds(22 * 3_600),
        inBedEnd = day(n + 1).plusSeconds(6 * 3_600),
    )

    /**
     * The cursor table is keyed by `kind_raw`: a renamed cursor is a delete and an insert, and an
     * insert onto a key that is already there must be refused (keeping the stored watermark), never
     * folded into it — the two leading watermarks a re-key moves belong to different nights.
     */
    @Test
    fun aCursorInsertedOntoAKeyAlreadyStoredIsRefusedAndTheStoredWatermarkKept() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val dao = db.sleepDao()
            val key = "hk:sleep-edit-leading:1749945600.0"
            dao.insertCursor(StoredCursorEntity(key, Instant.parse("2025-06-15T05:00:00Z")))

            assertFails { dao.insertCursor(StoredCursorEntity(key, Instant.parse("2025-06-15T09:00:00Z"))) }

            assertEquals(Instant.parse("2025-06-15T05:00:00Z"), dao.cursorAt(key)?.last)
        }
    }

    /**
     * The unique index on `night` is checked at each statement, not at the commit: two nights moving
     * one day each, the newer onto a free day and the older onto the newer's old day, only go through
     * newest first — the order the plan hands them out in. Oldest first, the first move already
     * collides, and the transaction leaves both rows as they were.
     */
    @Test
    fun twoNightsMovingADayEachOnlyGoThroughNewestFirstAsThePlanOrdersThem() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val dao = db.sleepDao()
            dao.insertSummary(bedtimeKeyed(0))
            dao.insertSummary(bedtimeKeyed(1))
            val plan = SleepNightRekeyPlan.plan(
                dao.allSummaries().map { SleepNightRekeyPlan.Row(it.night, it.inBedStart, it.inBedEnd) },
                ZoneOffset.UTC,
            )
            assertEquals(listOf(day(1) to day(2), day(0) to day(1)), plan.moves.map { it.from to it.to })

            assertFails {
                db.withWriteTransaction {
                    for (move in plan.moves.reversed()) dao.updateSummary(dao.summaryAt(move.from)!!.copy(night = move.to))
                }
            }
            assertEquals(listOf(day(0), day(1)), dao.allSummaries().map { it.night })

            db.withWriteTransaction {
                for (move in plan.moves) dao.updateSummary(dao.summaryAt(move.from)!!.copy(night = move.to))
            }
            assertEquals(listOf(day(1) to 400, day(2) to 401), dao.allSummaries().map { it.night to it.asleepMin })
        }
    }

    /**
     * São Paulo skipped its midnight on 2018-11-04 (clocks went from 00:00 to 01:00), so that day starts
     * at 01:00 local, 03:00Z. A night stored on the bedtime day before, ending that morning, moves to
     * 03:00Z on the 4th — not to the UTC midnight.
     */
    @Test
    fun aNightEndingOnADayWithoutAMidnightMovesToTheFirstInstantOfThatDayInItsZone() {
        val saoPaulo = ZoneId.of("America/Sao_Paulo")
        val stored = Instant.parse("2018-11-03T03:00:00Z") // 2018-11-03 00:00 at −03:00
        val row = SleepNightRekeyPlan.Row(stored, Instant.parse("2018-11-04T01:00:00Z"), Instant.parse("2018-11-04T09:00:00Z"))

        val plan = SleepNightRekeyPlan.plan(listOf(row), saoPaulo)

        assertEquals(listOf(SleepNightRekeyPlan.Move(stored, Instant.parse("2018-11-04T03:00:00Z"))), plan.moves)
        assertEquals(1_541_300_400L, plan.moves.single().to.epochSecond)
    }
}
