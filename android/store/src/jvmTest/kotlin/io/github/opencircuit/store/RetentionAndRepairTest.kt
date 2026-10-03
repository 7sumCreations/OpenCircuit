package io.github.opencircuit.store

import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Retention and launch repairs at their edges, beyond the ported upstream tests. Upstream outcomes
 * read from ios/OpenCircuit/Store/LocalStore.swift @ b1c2fdd: the prune cuts 30 CALENDAR days in
 * the user's zone and deletes `start < cutoff` (:1058-1070; Foundation's day arithmetic measured
 * on Swift 6.3.2 against java.time across DST changes — identical); the heart-rate purge compares
 * the stored Double, `value < 30 || value > 220` (:1090-1102); the timestamp purge deletes outside
 * ingest's window (:1110-1122); the cursor repair resets `last > now + 1 day` (:1144-1160).
 * Upstream latches its two purges in UserDefaults after the purge saved (ios/OpenCircuit/App.swift:624,
 * :638); here the latch commits in the purge's own transaction, so it is set exactly when the purge
 * committed. Each launch step runs even when an earlier one fails, as upstream's separate launch tasks.
 */
class RetentionAndRepairTest {

    private val now = Instant.parse("2026-10-03T12:00:00Z")
    private val utc = ZoneOffset.UTC
    private val cutoff = Instant.parse("2026-09-03T12:00:00Z")
    private val tenYears = 10L * 365 * 24 * 3600

    private fun row(kind: MetricKind, at: Instant, value: Double) =
        StoredSampleEntity(kindRaw = kind.rawValue, start = at, end = at, value = value)

    private suspend fun StoreDatabase.sampleStarts() = sampleDao().allSamples().map { it.start }

    private suspend fun StoreDatabase.heartRates() =
        sampleDao().allSamples().filter { it.kindRaw == MetricKind.HEART_RATE.rawValue }.map { it.value }

    private suspend fun StoreDatabase.latch(key: String) = kvDao().get(key)

    @Test
    fun pruneKeepsRowsExactlyAtTheCutoffAndDeletesOlderOnesButNeverRollupsOrCursors() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val older = cutoff.minusMillis(1)
            db.sampleDao().insertSamples(listOf(row(MetricKind.HEART_RATE, cutoff, 60.0), row(MetricKind.HEART_RATE, older, 61.0)))
            db.dailyDao().insertDaytimeTemp(StoredDaytimeTempEntity(time = cutoff, celsius = 33.0))
            db.dailyDao().insertDaytimeTemp(StoredDaytimeTempEntity(time = older, celsius = 33.1))
            db.dailyDao().insertStepSample(StoredStepSampleEntity(start = cutoff, end = now, delta = 5))
            db.dailyDao().insertStepSample(StoredStepSampleEntity(start = older, end = now, delta = 6))
            db.dailyDao().insertDaily(StoredDailyEntity(day = cutoff.minusSeconds(40L * 86_400), steps = 9, updatedAt = cutoff))
            db.sampleDao().upsertCursors(listOf(StoredCursorEntity("heartRate", older)))

            LocalStore(db).pruneExpiredSamples(now, utc)

            assertEquals(listOf(cutoff), db.sampleStarts())
            assertEquals(listOf(cutoff), db.dailyDao().daytimeTemps(Instant.EPOCH, now).map { it.time })
            assertEquals(listOf(cutoff), db.dailyDao().stepSamples(Instant.EPOCH, now.plusSeconds(1)).map { it.start })
            assertEquals(1, db.dailyDao().allDailies().size)
            assertEquals(listOf(StoredCursorEntity("heartRate", older)), db.sampleDao().allCursors())
        }
    }

    @Test
    fun pruneCountsThirtyCalendarDaysInTheGivenZoneAcrossAClockChange() = runBlocking<Unit> {
        withInMemoryStore { db ->
            // London springs forward on 2026-03-29. Foundation and java.time both put 30 days
            // before 2026-04-28T00:30Z at 2026-03-29T01:30Z (measured); 30 x 24 h would be 00:30Z.
            val london = ZoneId.of("Europe/London")
            val londonNow = Instant.parse("2026-04-28T00:30:00Z")
            val kept = Instant.parse("2026-03-29T01:30:00Z")
            db.sampleDao().insertSamples(
                listOf(
                    row(MetricKind.SPO2, kept, 0.97),
                    row(MetricKind.SPO2, kept.minusMillis(1), 0.97),
                    row(MetricKind.SPO2, Instant.parse("2026-03-29T01:00:00Z"), 0.97),
                ),
            )

            LocalStore(db).pruneExpiredSamples(londonNow, london)

            assertEquals(listOf(kept), db.sampleStarts())
        }
    }

    @Test
    fun heartRatePurgeComparesTheStoredValueDeletesOnlyHeartRatesAndRunsOnce() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val t = now.minusSeconds(3600)
            // 220.5 passes ingest (whole bpm 220) but the purge compares the Double, as upstream.
            db.sampleDao().insertSamples(listOf(4.0, 29.5, 30.0, 220.0, 220.5).map { row(MetricKind.HEART_RATE, t, it) } + row(MetricKind.SPO2, t, 0.97))
            val store = LocalStore(db)

            assertEquals(3, store.purgeImplausibleHeartRateOnce(now))
            assertEquals(listOf(30.0, 220.0), db.heartRates().sorted())
            assertEquals(1, db.sampleDao().allSamples().count { it.kindRaw == MetricKind.SPO2.rawValue })
            assertNotNull(db.latch(HR_LATCH))

            db.sampleDao().insertSamples(listOf(row(MetricKind.HEART_RATE, t, 4.0)))
            assertNull(store.purgeImplausibleHeartRateOnce(now))
            assertTrue(4.0 in db.heartRates())
        }
    }

    @Test
    fun timestampPurgeDeletesOutsideTheIngestWindowOfEveryKindAndRunsOnce() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val floor = Instant.ofEpochSecond(Command.SYNC_EPOCH)
            val ceiling = now.plusSeconds(86_400)
            db.sampleDao().insertSamples(
                listOf(
                    row(MetricKind.STEPS, floor.minusMillis(1), 10.0),
                    row(MetricKind.HEART_RATE, floor, 60.0),
                    row(MetricKind.HEART_RATE, ceiling, 61.0),
                    row(MetricKind.TEMPERATURE, ceiling.plusMillis(1), 33.0),
                ),
            )
            val store = LocalStore(db)

            assertEquals(2, store.purgeImplausibleTimestampsOnce(now))
            assertEquals(listOf(floor, ceiling), db.sampleStarts())
            assertNotNull(db.latch(TIMESTAMP_LATCH))

            db.sampleDao().insertSamples(listOf(row(MetricKind.STEPS, floor.minusMillis(1), 10.0)))
            assertNull(store.purgeImplausibleTimestampsOnce(now))
            assertEquals(3, db.sampleStarts().size)
        }
    }

    @Test
    fun whenTheHeartRateLatchWriteFailsThePurgeIsRolledBackAndRunsAgainNextLaunch() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.sampleDao().insertSamples(listOf(row(MetricKind.HEART_RATE, now.minusSeconds(60), 4.0)))
            val real = db.kvDao()
            val failing = object : KvDao by real {
                override suspend fun upsert(entry: StoreKvEntity) {
                    error("injected failure")
                }
            }

            assertFails { LocalStore(db, kvDao = failing).purgeImplausibleHeartRateOnce(now) }

            assertEquals(listOf(4.0), db.heartRates())
            assertNull(db.latch(HR_LATCH))

            assertEquals(1, LocalStore(db).purgeImplausibleHeartRateOnce(now))
            assertEquals(emptyList(), db.heartRates())
            assertNotNull(db.latch(HR_LATCH))
        }
    }

    @Test
    fun whenTheTimestampLatchWriteFailsThePurgeIsRolledBackAndRunsAgainNextLaunch() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.sampleDao().insertSamples(listOf(row(MetricKind.HEART_RATE, now.plusSeconds(tenYears), 60.0)))
            val real = db.kvDao()
            val failing = object : KvDao by real {
                override suspend fun upsert(entry: StoreKvEntity) {
                    error("injected failure")
                }
            }

            assertFails { LocalStore(db, kvDao = failing).purgeImplausibleTimestampsOnce(now) }

            assertEquals(1, db.sampleStarts().size)
            assertNull(db.latch(TIMESTAMP_LATCH))

            assertEquals(1, LocalStore(db).purgeImplausibleTimestampsOnce(now))
            assertNotNull(db.latch(TIMESTAMP_LATCH))
        }
    }

    @Test
    fun whenThePurgeDeleteFailsNoLatchIsWritten() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.sampleDao().insertSamples(listOf(row(MetricKind.HEART_RATE, now.minusSeconds(60), 4.0)))
            val real = db.sampleDao()
            val failing = object : SampleDao by real {
                override suspend fun deleteHeartRatesOutside(kindRaw: String, lowest: Double, highest: Double): Int = error("injected failure")
                override suspend fun deleteSamplesOutside(floor: Instant, ceiling: Instant): Int = error("injected failure")
            }
            val store = LocalStore(db, sampleDao = failing)

            assertFails { store.purgeImplausibleHeartRateOnce(now) }
            assertFails { store.purgeImplausibleTimestampsOnce(now) }

            assertNull(db.latch(HR_LATCH))
            assertNull(db.latch(TIMESTAMP_LATCH))
            assertEquals(listOf(4.0), db.heartRates())
        }
    }

    @Test
    fun repairLeavesACursorExactlyOneDayAheadAndRepairsOneMillisecondLater() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val ceiling = now.plusSeconds(86_400)
            db.sampleDao().upsertCursors(listOf(StoredCursorEntity("heartRate", ceiling), StoredCursorEntity("spo2", ceiling.plusMillis(1))))
            // The reset target is the newest sample at or before now; a later one is ignored.
            db.sampleDao().insertSamples(listOf(row(MetricKind.SPO2, now, 0.97), row(MetricKind.SPO2, now.plusSeconds(1), 0.96)))

            assertEquals(1, LocalStore(db).repairFutureSyncCursors(now))

            assertEquals(
                listOf(StoredCursorEntity("heartRate", ceiling), StoredCursorEntity("spo2", now)),
                db.sampleDao().allCursors().sortedBy { it.kindRaw },
            )
        }
    }

    @Test
    fun aPoisonedExportWatermarkIsDeletedAsUpstreamDoes() = runBlocking<Unit> {
        withInMemoryStore { db ->
            // Upstream strips only `hk:` before looking for samples, so an `export:` row finds none.
            db.sampleDao().upsertCursors(listOf(StoredCursorEntity("export:samples", now.plusSeconds(tenYears))))
            db.sampleDao().insertSamples(listOf(row(MetricKind.HEART_RATE, now, 60.0)))

            assertEquals(1, LocalStore(db).repairFutureSyncCursors(now))

            assertEquals(emptyList(), db.sampleDao().allCursors())
        }
    }

    @Test
    fun launchRepairsPruneAndPurgeBeforeTheCursorRepairSoItSeesTheCleanedTable() = runBlocking<Unit> {
        withInMemoryStore { db ->
            // Either sample would become the repaired cursor if the repair ran first.
            db.sampleDao().insertSamples(
                listOf(
                    row(MetricKind.HEART_RATE, now.minusSeconds(40L * 86_400), 60.0),
                    row(MetricKind.HEART_RATE, now.minusSeconds(3600), 4.0),
                ),
            )
            db.sampleDao().upsertCursors(listOf(StoredCursorEntity("heartRate", now.plusSeconds(tenYears))))

            val report = LaunchRepairs.run(LocalStore(db), now, utc)

            assertEquals(emptyList(), db.sampleDao().allSamples())
            assertEquals(emptyList(), db.sampleDao().allCursors())
            assertEquals(Result.success(Unit), report.prune)
            assertEquals(Result.success(1), report.heartRatePurge)
            assertEquals(Result.success(0), report.timestampPurge)
            assertEquals(Result.success(1), report.cursorRepair)
        }
    }

    @Test
    fun aSecondLaunchSkipsTheLatchedPurgesButStillPrunesAndRepairs() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            LaunchRepairs.run(store, now, utc)
            val implausible = row(MetricKind.HEART_RATE, now.minusSeconds(3600), 4.0)
            db.sampleDao().insertSamples(listOf(implausible, row(MetricKind.SPO2, now.minusSeconds(40L * 86_400), 0.97)))
            db.sampleDao().upsertCursors(listOf(StoredCursorEntity("spo2", now.plusSeconds(tenYears))))

            val second = LaunchRepairs.run(store, now, utc)

            assertEquals(listOf(4.0), db.heartRates())
            assertEquals(1, db.sampleDao().allSamples().size)
            assertEquals(emptyList(), db.sampleDao().allCursors())
            assertEquals(Result.success(null), second.heartRatePurge)
            assertEquals(Result.success(null), second.timestampPurge)
            assertEquals(Result.success(1), second.cursorRepair)
        }
    }

    @Test
    fun aFailingLaunchStepIsReportedAndTheStepsAfterItStillRun() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val real = db.sampleDao()
            val failing = object : SampleDao by real {
                override suspend fun deleteSamplesBefore(cutoff: Instant): Int = error("injected failure")
            }
            db.sampleDao().upsertCursors(listOf(StoredCursorEntity("heartRate", now.plusSeconds(tenYears))))

            val report = LaunchRepairs.run(LocalStore(db, sampleDao = failing), now, utc)

            assertTrue(report.prune.isFailure)
            assertEquals(Result.success(0), report.heartRatePurge)
            assertEquals(Result.success(0), report.timestampPurge)
            assertEquals(Result.success(1), report.cursorRepair)
            assertEquals(emptyList(), db.sampleDao().allCursors())
        }
    }

    @Test
    fun anOutOfBandHeartRateLaterThanTheRealOneDoesNotPoisonTheCursor() = runBlocking<Unit> {
        withInMemoryStore { db ->
            // Upstream's out-of-band test puts the bad value BEFORE the real one, so a cursor that
            // moved past it would still admit the real sample; here it is after.
            val store = LocalStore(db)
            store.ingest(listOf(QuantitySample(MetricKind.HEART_RATE, start = now, value = 0.0)), now, utc)

            val ingested = store.ingest(listOf(QuantitySample(MetricKind.HEART_RATE, start = now.minusSeconds(60), value = 72.0)), now, utc)

            assertEquals(listOf(72.0), ingested.map { it.value })
        }
    }

    private companion object {
        const val HR_LATCH = "store.purgedImplausibleHR.v1"
        const val TIMESTAMP_LATCH = "store.purgedImplausibleTimestamps.v1"
    }
}
