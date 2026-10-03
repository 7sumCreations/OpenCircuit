package io.github.opencircuit.store

import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

/**
 * The ring's onboard step count: `addDailySteps` adds a delta to the local day's rollup and
 * appends the timestamped delta, in one transaction. Port of upstream `addDailySteps` / `todaySteps`
 * (ios/OpenCircuit/Store/LocalStore.swift:3058-3078 @ b1c2fdd), which has no upstream test.
 *
 * Kotlin-only: upstream's `Int` is 64-bit and `+=` traps on overflow, so an overflowing total
 * must fail the write, never wrap; `updatedAt` takes the clock from the caller.
 */
class DailyStepsTest {

    private val utc = ZoneOffset.UTC
    private val dayStart = Instant.parse("2026-10-02T00:00:00Z")
    private val nextDay = Instant.parse("2026-10-03T00:00:00Z")

    @Test
    fun twoAddsOnOneDaySumTheRollupAndKeepBothTimedDeltas() = runBlocking {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            val firstNow = Instant.parse("2026-10-02T10:00:05Z")
            val secondNow = Instant.parse("2026-10-02T11:00:05Z")

            store.addDailySteps(120, day = Instant.parse("2026-10-02T10:00:00Z"), windowStart = Instant.parse("2026-10-02T09:45:00Z"), now = firstNow, zone = utc)
            store.addDailySteps(30, day = Instant.parse("2026-10-02T11:00:00Z"), windowStart = null, now = secondNow, zone = utc)

            assertEquals(150, store.todaySteps(day = Instant.parse("2026-10-02T23:59:59Z"), zone = utc))
            assertEquals(listOf(DailySteps(day = dayStart, steps = 150, updatedAt = secondNow)), store.dailies(from = dayStart, to = nextDay))
            // No window start: the delta covers the day so far.
            assertEquals(
                listOf(
                    StepSample(start = dayStart, end = Instant.parse("2026-10-02T11:00:00Z"), delta = 30, healthWritten = false),
                    StepSample(start = Instant.parse("2026-10-02T09:45:00Z"), end = Instant.parse("2026-10-02T10:00:00Z"), delta = 120, healthWritten = false),
                ),
                store.stepSamples(from = dayStart, to = nextDay),
            )
        }
    }

    @Test
    fun aFailureAfterTheRollupUpdateLeavesNeitherTheTotalNorTheDeltaChanged() = runBlocking {
        withInMemoryStore { db ->
            val now = Instant.parse("2026-10-02T10:00:05Z")
            LocalStore(db).addDailySteps(100, day = Instant.parse("2026-10-02T09:00:00Z"), now = now, zone = utc)
            val real = db.dailyDao()
            // The step-sample insert runs after the rollup update: failing there is the
            // "crash between the total and its delta" case.
            val failing = object : DailyDao by real {
                override suspend fun insertStepSample(sample: StoredStepSampleEntity) = error("injected failure")
            }

            assertFails { LocalStore(db, dailyDao = failing).addDailySteps(50, day = Instant.parse("2026-10-02T10:00:00Z"), now = now, zone = utc) }

            val store = LocalStore(db)
            assertEquals(100, store.todaySteps(day = now, zone = utc))
            assertEquals(listOf(100L), store.stepSamples(from = dayStart, to = nextDay).map { it.delta })
        }
    }

    @Test
    fun aZeroOrNegativeDeltaWritesNothing() = runBlocking {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            val now = Instant.parse("2026-10-02T10:00:05Z")

            store.addDailySteps(0, day = now, now = now, zone = utc)
            store.addDailySteps(-5, day = now, now = now, zone = utc)

            assertNull(store.latestDaily())
            assertEquals(emptyList(), store.stepSamples(from = dayStart, to = nextDay))
        }
    }

    @Test
    fun theDayIsTheLocalDayOfTheReadingInTheGivenZone() = runBlocking {
        val kolkata = ZoneId.of("Asia/Kolkata")
        // Kolkata is UTC+05:30: 18:20Z is 23:50 on the 2nd, 18:40Z is 00:10 on the 3rd.
        val before = Instant.parse("2026-10-02T18:20:00Z")
        val after = Instant.parse("2026-10-02T18:40:00Z")

        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.addDailySteps(10, day = before, now = before, zone = kolkata)
            store.addDailySteps(20, day = after, now = after, zone = kolkata)

            assertEquals(
                listOf(Instant.parse("2026-10-01T18:30:00Z") to 10L, Instant.parse("2026-10-02T18:30:00Z") to 20L),
                store.recentDailies(limit = 14).reversed().map { it.day to it.steps },
            )
            assertEquals(20, store.todaySteps(day = after, zone = kolkata))
        }
        // The same two instants are one day in UTC.
        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.addDailySteps(10, day = before, now = before, zone = utc)
            store.addDailySteps(20, day = after, now = after, zone = utc)

            assertEquals(listOf(dayStart to 30L), store.recentDailies(limit = 14).map { it.day to it.steps })
        }
    }

    @Test
    fun aTotalThatWouldOverflowFailsTheWriteAndLeavesTheDayAsItWas() = runBlocking {
        withInMemoryStore { db ->
            val now = Instant.parse("2026-10-02T10:00:05Z")
            val store = LocalStore(db)
            store.addDailySteps(Long.MAX_VALUE - 1, day = now, now = now, zone = utc)

            assertFails { store.addDailySteps(5, day = now, now = now, zone = utc) }

            assertEquals(Long.MAX_VALUE - 1, store.todaySteps(day = now, zone = utc))
            assertEquals(1, store.stepSamples(from = dayStart, to = nextDay).size)
        }
    }

    @Test
    fun noRowForTheDayReadsAsZeroSteps() = runBlocking {
        withInMemoryStore { db ->
            assertEquals(0, LocalStore(db).todaySteps(day = dayStart, zone = utc))
        }
    }
}
