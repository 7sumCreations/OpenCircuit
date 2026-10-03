package io.github.opencircuit.store

import io.github.opencircuit.ringkit.MetricKind
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The store's reads, each with its window edges. Port of upstream's read descriptors and methods
 * (ios/OpenCircuit/Store/LocalStore.swift @ b1c2fdd): `latestSample(kind:before:)` (:813, strictly
 * before), `earliestSample(kind:after:)` (:831, strictly after), `earliestSample(kind:)` (:842),
 * `recentSamples` (:1184, `start >= since && value > 0`), `daytimeTemperatures` (:795, `[from, to)`),
 * `stepSamples` (:803, `[from, to)`), `latestDaily` (:3105), `recentDailies` (:3114, newest first,
 * bounded), `dailies` (:3119, `[from, to)`). Upstream has no test of these reads.
 *
 * Fixture rows are inserted through the DAOs (the raw stored form), not through the ingest path.
 */
class LocalStoreReadsTest {

    private val t0 = Instant.parse("2026-10-01T00:00:00Z")

    private fun at(minutes: Long) = t0.plusSeconds(minutes * 60)

    private fun hr(minutes: Long, value: Double) =
        StoredSampleEntity(kindRaw = MetricKind.HEART_RATE.rawValue, start = at(minutes), end = at(minutes), value = value)

    private suspend fun seedHeartRates(db: StoreDatabase) {
        db.sampleDao().insertSamples(
            listOf(
                hr(10, 60.0),
                hr(20, 0.0),
                hr(30, 62.0),
                StoredSampleEntity(kindRaw = MetricKind.SPO2.rawValue, start = at(25), end = at(25), value = 0.97),
            ),
        )
    }

    @Test
    fun latestSampleBeforeIsStrictlyBefore() = runBlocking {
        withInMemoryStore { db ->
            seedHeartRates(db)
            val store = LocalStore(db)

            assertEquals(at(20), store.latestSample(MetricKind.HEART_RATE, before = at(30))?.start)
            assertEquals(at(30), store.latestSample(MetricKind.HEART_RATE, before = at(31))?.start)
            assertNull(store.latestSample(MetricKind.HEART_RATE, before = at(10)))
        }
    }

    @Test
    fun earliestSampleAfterIsStrictlyAfterAndEarliestOverallIsTheOldestOfTheKind() = runBlocking {
        withInMemoryStore { db ->
            seedHeartRates(db)
            val store = LocalStore(db)

            assertEquals(at(20), store.earliestSample(MetricKind.HEART_RATE, after = at(10))?.start)
            assertNull(store.earliestSample(MetricKind.HEART_RATE, after = at(30)))
            assertEquals(at(10), store.earliestSample(MetricKind.HEART_RATE)?.start)
            assertEquals(at(25), store.earliestSample(MetricKind.SPO2)?.start)
            assertNull(store.earliestSample(MetricKind.STEPS))
        }
    }

    @Test
    fun recentSamplesIncludeTheSinceInstantAndSkipZeroValuesAndOtherKinds() = runBlocking {
        withInMemoryStore { db ->
            seedHeartRates(db)
            val store = LocalStore(db)

            assertEquals(listOf(60.0, 62.0), store.recentSamples(MetricKind.HEART_RATE, since = at(10)).map { it.value })
            assertEquals(listOf(62.0), store.recentSamples(MetricKind.HEART_RATE, since = at(11)).map { it.value })
        }
    }

    @Test
    fun daytimeTemperaturesAreTheHalfOpenWindowOldestFirst() = runBlocking {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.recordDaytimeTemperature(33.1, at = at(20))
            store.recordDaytimeTemperature(33.0, at = at(10))
            store.recordDaytimeTemperature(33.2, at = at(30))

            assertEquals(
                listOf(DaytimeTemperature(at(10), 33.0), DaytimeTemperature(at(20), 33.1)),
                store.daytimeTemperatures(from = at(10), to = at(30)),
            )
        }
    }

    /**
     * Kotlin-only: SQLite binds NaN as NULL (the NOT NULL column then fails with an
     * `SQLiteException`) and stores ±∞, which is no temperature. Either is refused before the
     * database with an `IllegalArgumentException`, and nothing is written (as I-46 for ingest).
     */
    @Test
    fun aDaytimeTemperatureThatIsNotFiniteIsRefusedBeforeTheDatabase() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            for (v in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                assertFailsWith<IllegalArgumentException>("celsius $v") { store.recordDaytimeTemperature(v, at = at(10)) }
            }
            assertEquals(emptyList(), store.daytimeTemperatures(from = at(0), to = at(100)))
        }
    }

    @Test
    fun stepSamplesAreTheHalfOpenWindowOnStart() = runBlocking {
        withInMemoryStore { db ->
            listOf(10L, 20L, 30L).forEach { m ->
                db.dailyDao().insertStepSample(StoredStepSampleEntity(start = at(m), end = at(m + 5), delta = m))
            }

            assertEquals(listOf(10L, 20L), LocalStore(db).stepSamples(from = at(10), to = at(30)).map { it.delta })
        }
    }

    @Test
    fun dailyReadsWindowOrderAndBound() = runBlocking {
        withInMemoryStore { db ->
            val days = (0L until 4L).map { t0.plusSeconds(it * 86_400) }
            days.forEachIndexed { i, day -> db.dailyDao().insertDaily(StoredDailyEntity(day = day, steps = 100L + i, updatedAt = day)) }
            val store = LocalStore(db)

            assertEquals(DailySteps(days[3], 103, days[3]), store.latestDaily())
            assertEquals(listOf(103L, 102L), store.recentDailies(limit = 2).map { it.steps })
            assertEquals(listOf(101L, 102L), store.dailies(from = days[1], to = days[3]).map { it.steps })
        }
    }
}
