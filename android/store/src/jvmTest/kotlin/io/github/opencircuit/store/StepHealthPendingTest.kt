package io.github.opencircuit.store

import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The step rows still to be written to Health Connect and the mark that takes them out, read from
 * upstream's source (ios/OpenCircuit/Store/LocalStore.swift `pendingStepSamples` :3083-3088,
 * `markStepSamplesWritten` :3092-3096 @ b1c2fdd), which no upstream test exercises. Rows carry their
 * row id here: a mark names the rows Health Connect accepted, never "everything before a time".
 */
class StepHealthPendingTest {
    private val utc = ZoneOffset.UTC
    private val now = Instant.parse("2026-10-16T18:00:00Z")
    private fun at(text: String): Instant = Instant.parse("2026-10-16T${text}Z")

    private suspend fun LocalStore.threeReadings() {
        addDailySteps(40, day = at("10:02:00"), windowStart = at("10:00:00"), now = now, zone = utc)
        addDailySteps(25, day = at("09:31:00"), windowStart = at("09:30:00"), now = now, zone = utc)
        addDailySteps(70, day = at("10:05:00"), windowStart = at("10:02:00"), now = now, zone = utc)
    }

    @Test
    fun pendingRowsAreTheUnwrittenOnesOldestFirstWithTheirIds() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.threeReadings()

            val pending = store.pendingStepSampleRows()

            assertEquals(listOf(at("09:30:00"), at("10:00:00"), at("10:02:00")), pending.map { it.sample.start })
            assertEquals(listOf(25L, 40L, 70L), pending.map { it.sample.delta })
            assertEquals(3, pending.map { it.id }.toSet().size, "each row its own id")
            assertTrue(pending.none { it.sample.healthWritten })
        }
    }

    @Test
    fun markingTakesOutOnlyTheRowsNamed() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.threeReadings()
            val (first, second, third) = store.pendingStepSampleRows()

            store.markStepSamplesWritten(listOf(first.id, third.id))

            assertEquals(listOf(second), store.pendingStepSampleRows())
            assertEquals(listOf(true, false, true), store.stepSamples(at("00:00:00"), at("23:00:00")).map { it.healthWritten })
        }
    }

    @Test
    fun markingNothingOrARowNobodyStoredChangesNothing() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.threeReadings()
            val before = db.queryRaw("SELECT * FROM stored_step_sample ORDER BY id")

            store.markStepSamplesWritten(emptyList())
            store.markStepSamplesWritten(listOf(9_999L))

            assertEquals(before, db.queryRaw("SELECT * FROM stored_step_sample ORDER BY id"))
        }
    }

    /** Rows of every day stay pending until written (upstream: "unbounded by today"). */
    @Test
    fun anEarlierDaysRowStaysPending() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.addDailySteps(30, day = Instant.parse("2026-10-14T20:00:00Z"), windowStart = Instant.parse("2026-10-14T19:58:00Z"), now = now, zone = utc)
            store.threeReadings()

            assertEquals(Instant.parse("2026-10-14T19:58:00Z"), store.pendingStepSampleRows().first().sample.start)
        }
    }
}
