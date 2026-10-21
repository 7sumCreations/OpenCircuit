package io.github.opencircuit.store

import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every ring reading is kept on the phone for good: the launch repairs delete no reading for its
 * age (the 30-day retention and its Health Connect guard are gone; PORTING.md records it).
 * Upstream ages raw samples, step deltas and daytime readings out after 30 calendar days
 * (ios/OpenCircuit/Store/LocalStore.swift:1051-1070 @ b1c2fdd); this app keeps them all.
 *
 * Each case runs the real launch path, [LaunchRepairs.run], twice over one database (a second
 * launch is a new store object over the same database), and compares every reading table row for
 * row. The ages straddle the old cutoff — exactly 30 days, 31 days, a year — and the rows cover
 * what the old retention treated differently: a reading above zero of a kind written to Health
 * Connect, behind its mark and with no mark; a zero reading; a kind never written from the
 * samples; written and unwritten step deltas; daytime readings; a day total.
 */
class PruneGuardTest {
    private val now = Instant.parse("2026-10-17T12:00:00Z")
    private val day = 86_400L
    private val thirtyDays = now.minusSeconds(30 * day)
    private val thirtyOneDays = now.minusSeconds(31 * day)
    private val aYear = now.minusSeconds(365 * day)
    private val ages = listOf(thirtyDays, thirtyOneDays, aYear)

    private fun row(kind: MetricKind, at: Instant, value: Double) =
        StoredSampleEntity(kindRaw = kind.rawValue, start = at, end = at, value = value)

    private data class Tables(
        val samples: List<String>,
        val steps: List<String>,
        val daytime: List<String>,
        val dailies: List<String>,
    )

    private suspend fun StoreDatabase.tables() = Tables(
        samples = queryRaw("SELECT kind_raw, start, value FROM stored_sample ORDER BY kind_raw, start, value"),
        steps = queryRaw("SELECT start, delta, health_written FROM stored_step_sample ORDER BY start, delta"),
        daytime = queryRaw("SELECT time, celsius FROM stored_daytime_temp ORDER BY time"),
        dailies = queryRaw("SELECT day, steps FROM stored_daily ORDER BY day"),
    )

    private suspend fun launchTwice(db: StoreDatabase, zone: ZoneId) {
        LaunchRepairs.run(LocalStore(db), SleepStore(db), now, zone)
        LaunchRepairs.run(LocalStore(db), SleepStore(db), now, zone)
    }

    private suspend fun fill(db: StoreDatabase) {
        val store = LocalStore(db)
        db.sampleDao().insertSamples(
            ages.flatMap { at ->
                listOf(
                    row(MetricKind.HEART_RATE, at, 61.0),
                    row(MetricKind.SPO2, at, 0.96),
                    row(MetricKind.HRV_SDNN, at, 0.0),
                    row(MetricKind.RESTING_HEART_RATE, at, 52.0),
                    row(MetricKind.TEMPERATURE, at, 33.4),
                )
            },
        )
        // Health Connect received every heart rate (its mark sits past them all); blood oxygen never.
        store.markHealthWritten(ages.map { QuantitySample(MetricKind.HEART_RATE, it, value = 61.0) })
        for (at in ages) {
            db.dailyDao().insertStepSample(StoredStepSampleEntity(start = at, end = at.plusSeconds(900), delta = 40, healthWritten = true))
            db.dailyDao().insertStepSample(StoredStepSampleEntity(start = at.plusSeconds(900), end = at.plusSeconds(1800), delta = 25))
            store.recordDaytimeTemperature(33.0, at)
        }
        db.dailyDao().insertDaily(StoredDailyEntity(day = aYear, steps = 9_000, updatedAt = aYear))
    }

    @Test
    fun noReadingIsDeletedForItsAgeAtLaunchWrittenToHealthConnectOrNot() = runBlocking<Unit> {
        withInMemoryStore { db ->
            fill(db)
            val before = db.tables()
            assertEquals(15, before.samples.size, "the fixture holds five rows at each of three ages")
            assertEquals(6, before.steps.size)
            assertEquals(3, before.daytime.size)

            launchTwice(db, ZoneOffset.UTC)

            assertEquals(before, db.tables())
        }
    }

    @Test
    fun aHeartRatePastItsHealthConnectMarkIsKeptAtEveryAge() = runBlocking<Unit> {
        withInMemoryStore { db ->
            fill(db)
            assertTrue(LocalStore(db).pendingHealthSamples(MetricKind.HEART_RATE).isEmpty(), "every heart rate is behind its mark")

            launchTwice(db, ZoneOffset.UTC)

            assertEquals(ages.sorted(), LocalStore(db).samples(MetricKind.HEART_RATE, Instant.EPOCH, now).map { it.start })
        }
    }

    @Test
    fun aZoneWithAClockChangeDeletesNothingEither() = runBlocking<Unit> {
        withInMemoryStore { db ->
            fill(db)
            val before = db.tables()

            launchTwice(db, ZoneId.of("Europe/London"))

            assertEquals(before, db.tables())
        }
    }
}
