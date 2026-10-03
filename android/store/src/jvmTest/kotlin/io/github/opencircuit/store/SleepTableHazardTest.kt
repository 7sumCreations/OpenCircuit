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
import kotlin.test.assertTrue

/**
 * What the sleep-summary table and the ported fixtures do before any sleep write relies on them.
 *
 * Upstream's own measurement (ios/OpenCircuit/Store/LocalStore.swift:2575-2580 @ b1c2fdd): SwiftData's
 * `@Attribute(.unique)` does not refuse a key collision — mutating a row onto an occupied night makes
 * `save()` succeed and destroys one row. Room's unique index on `night` must instead refuse the
 * write and keep both rows; the store's explicit occupancy checks are kept on top of it, and the
 * replacing conflict strategy is never used on this table.
 */
class SleepTableHazardTest {

    private val n1 = Instant.parse("2025-06-14T00:00:00Z")
    private val n2 = Instant.parse("2025-06-15T00:00:00Z")

    private fun row(night: Instant, asleepMin: Int) = StoredSleepSummaryEntity(
        night = night,
        asleepMin = asleepMin,
        inBedStart = night.plusSeconds(3_600),
        inBedEnd = night.plusSeconds(9 * 3_600),
    )

    @Test
    fun aSecondRowForAnOccupiedNightIsRefusedAndTheFirstIsKept() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val dao = db.sleepDao()
            dao.insertSummary(row(n2, asleepMin = 420))

            assertFails { dao.insertSummary(row(n2, asleepMin = 100)) }

            val rows = dao.allSummaries()
            assertEquals(1, rows.size)
            assertEquals(420, rows.single().asleepMin)
        }
    }

    @Test
    fun movingARowOntoAnOccupiedNightIsRefusedAndBothRowsAreKept() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val dao = db.sleepDao()
            dao.insertSummary(row(n1, asleepMin = 300))
            dao.insertSummary(row(n2, asleepMin = 420))
            val second = dao.summaryAt(n2)!!

            assertFails { dao.updateSummary(second.copy(night = n1)) }

            val rows = dao.allSummaries()
            assertEquals(listOf(n1 to 300, n2 to 420), rows.map { it.night to it.asleepMin })
        }
    }

    @Test
    fun aRefusedCollisionRollsBackEverythingElseItsTransactionWrote() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val dao = db.sleepDao()
            dao.insertSummary(row(n1, asleepMin = 300))
            dao.insertSummary(row(n2, asleepMin = 420))

            assertFails {
                db.withWriteTransaction {
                    dao.updateSummary(dao.summaryAt(n1)!!.copy(asleepMin = 1))
                    dao.insertSummary(row(n2, asleepMin = 100))
                }
            }

            assertEquals(listOf(n1 to 300, n2 to 420), dao.allSummaries().map { it.night to it.asleepMin })
        }
    }

    /**
     * Upstream's store tests ran in the simulator's zone and shared one `UserDefaults`, so the wake-day
     * re-key latch was set once per process. Here each test database starts unlatched, so a second
     * save would re-key whatever the plan moves. Pinned to UTC, every recorded fixture window of the
     * ported sleep tests (`ref` = 1 750 000 000 = 2025-06-15T15:06:40Z, windows from ref − 0.5 h to
     * ref + 8.5 h, and the prior night ref − 25 h … ref − 17 h) ends on its own key day: nothing moves.
     * East of UTC the same fixtures cross midnight: at UTC+7 the night ending at ref + 8 h belongs to
     * the next day.
     */
    @Test
    fun theUpstreamSleepFixturesMoveNothingThroughTheRekeyInUtcButDoEastOfIt() {
        val ref = Instant.ofEpochSecond(1_750_000_000)
        fun at(hours: Double): Instant = ref.plusMillis((hours * 3_600_000).toLong())
        fun dayStart(t: Instant, zone: ZoneId): Instant = t.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        val windows = listOf(
            at(0.0) to at(8.0), // full night
            at(5.0) to at(7.0), // short slice
            at(-0.5) to at(8.5), // fuller night
            at(-25.0) to at(-17.0), // the prior night of the overlap tests
        )

        for ((start, end) in windows) {
            val utc = SleepNightRekeyPlan.plan(listOf(SleepNightRekeyPlan.Row(dayStart(start, ZoneOffset.UTC), start, end)), ZoneOffset.UTC)
            assertTrue(utc.moves.isEmpty() && utc.refused.isEmpty(), "UTC moved the window $start..$end: $utc")
        }

        val bangkok = ZoneId.of("Asia/Bangkok")
        val stored = dayStart(at(0.0), bangkok)
        val plan = SleepNightRekeyPlan.plan(listOf(SleepNightRekeyPlan.Row(stored, at(0.0), at(8.0))), bangkok)
        assertEquals(
            listOf(SleepNightRekeyPlan.Move(Instant.parse("2025-06-14T17:00:00Z"), Instant.parse("2025-06-15T17:00:00Z"))),
            plan.moves,
        )
    }
}
