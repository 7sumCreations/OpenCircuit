package io.github.opencircuit.store

import io.github.opencircuit.ringkit.SleepHypnogramCodec
import io.github.opencircuit.ringkit.SleepScoreHeal
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneOffset
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The three sleep-history repairs upstream runs at launch between the timestamp purge and the cursor
 * repair (ios/OpenCircuit/App.swift:28-38 @ b1c2fdd): the one-time move of stored nights onto their
 * wake day (`rekeySleepNightsOnce`, :658-663), the provenance backfill (:674-679) and the heal of
 * withheld scores (:698-711). Upstream runs each as its own launch task and logs or discards its
 * outcome; here each outcome is reported, a failing step does not stop the next, and cancellation
 * stops the run.
 */
class LaunchRepairsSleepTest {

    private val utc = ZoneOffset.UTC
    private val now = Instant.parse("2025-06-20T08:00:00Z")
    private val earlier = Instant.parse("2025-06-19T08:00:00Z")
    private val latchKey = "store.rekeyedSleepNightsToWakeDay.v1"
    private val tenYears = 10L * 365 * 24 * 3600

    /** Midnight UTC of June [n], 2025. */
    private fun day(n: Int): Instant = Instant.parse("2025-06-01T00:00:00Z").plusSeconds((n - 1) * 86_400L)

    private fun timeline(start: Instant) = listOf(
        SleepSegment(start, start.plusSeconds(8 * 3_600L), SleepStage.IN_BED),
        SleepSegment(start, start.plusSeconds(1_800), SleepStage.AWAKE),
        SleepSegment(start.plusSeconds(1_800), start.plusSeconds(4 * 3_600L), SleepStage.ASLEEP_CORE),
        SleepSegment(start.plusSeconds(4 * 3_600L), start.plusSeconds(6 * 3_600L), SleepStage.ASLEEP_DEEP),
        SleepSegment(start.plusSeconds(6 * 3_600L), start.plusSeconds(8 * 3_600L), SleepStage.ASLEEP_REM),
    )

    /** Never edited, no basis yet, stored under the day it started: in bed 22:00 June [n] → 06:00. */
    private fun bedtimeKeyedUnfilled(n: Int): StoredSleepSummaryEntity {
        val start = day(n).plusSeconds(22 * 3_600L)
        return StoredSleepSummaryEntity(
            night = day(n), asleepMin = 450, inBedStart = start, inBedEnd = start.plusSeconds(8 * 3_600L), updatedAt = earlier,
            hypnogramData = SleepHypnogramCodec.encode(timeline(start)),
        )
    }

    /** Edited, a known basis, its score zeroed, under its wake day: in bed 23:00 the day before → 07:00 June [n]. */
    private fun scarred(n: Int): StoredSleepSummaryEntity {
        val start = day(n).minusSeconds(3_600)
        return StoredSleepSummaryEntity(
            night = day(n), asleepMin = 450, inBedStart = start, inBedEnd = start.plusSeconds(8 * 3_600L), updatedAt = earlier,
            isManuallyEdited = true, editedInBedStart = start, editedInBedEnd = start.plusSeconds(8 * 3_600L),
            sleepBasis = SleepBasis.MEASURED_ONLY.rawValue, hypnogramData = SleepHypnogramCodec.encode(timeline(start)),
        )
    }

    @Test
    fun aLaunchMovesFillsAndHealsTheStoredNightsAndReportsEachStep() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.sleepDao().insertSummary(bedtimeKeyedUnfilled(10))
            db.sleepDao().insertSummary(scarred(14))
            val sleep = SleepStore(db)

            val report = LaunchRepairs.run(LocalStore(db), sleep, now, utc)

            assertEquals(Result.success(Unit), report.nightRekey)
            assertEquals(Result.success(1), report.provenanceBackfill)
            assertEquals(Result.success(listOf(day(14))), report.scoreHeal)
            assertEquals("true", db.kvDao().get(latchKey)?.value)
            val moved = assertNotNull(sleep.sleepSummary(day(11), utc), "the night moved onto the day it ended")
            assertEquals(SleepBasis.MEASURED_ONLY, moved.sleepBasis)
            assertEquals(SleepScoreHeal.healedScore(timeline(day(14).minusSeconds(3_600))), sleep.sleepSummary(day(14), utc)?.sleepScore)
        }
    }

    @Test
    fun theSleepStepsRunAfterThePurgesAndBeforeTheCursorRepairAndTheBackfillBeforeTheHeal() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val steps = mutableListOf<String>()
            val sampleDao = db.sampleDao()
            val recordingSamples = object : SampleDao by sampleDao {
                override suspend fun deleteSamplesBefore(cutoff: Instant): Int = sampleDao.deleteSamplesBefore(cutoff).also { steps += "prune" }
                override suspend fun deleteHeartRatesOutside(kindRaw: String, lowest: Double, highest: Double): Int =
                    sampleDao.deleteHeartRatesOutside(kindRaw, lowest, highest).also { steps += "heart-rate purge" }
                override suspend fun deleteSamplesOutside(floor: Instant, ceiling: Instant): Int =
                    sampleDao.deleteSamplesOutside(floor, ceiling).also { steps += "timestamp purge" }
                override suspend fun allCursors(): List<StoredCursorEntity> = sampleDao.allCursors().also { steps += "cursor repair" }
            }
            val kvDao = db.kvDao()
            val recordingKv = object : KvDao by kvDao {
                override suspend fun get(key: String): StoreKvEntity? = kvDao.get(key).also { if (key == latchKey) steps += "night move" }
            }
            val sleepDao = db.sleepDao()
            var reads = 0
            val recordingSleep = object : SleepDao by sleepDao {
                override suspend fun allSummaries(): List<StoredSleepSummaryEntity> {
                    // The move is latched, so the first full read is the backfill's and the second the heal's;
                    // failing the first proves the order: the backfill fails, the heal still runs.
                    steps += if (++reads == 1) "backfill" else "heal"
                    if (reads == 1) error("injected failure")
                    return sleepDao.allSummaries()
                }
            }
            kvDao.upsert(StoreKvEntity(latchKey, "true", earlier))
            sleepDao.insertSummary(scarred(14))

            val report = LaunchRepairs.run(LocalStore(db, sampleDao = recordingSamples), SleepStore(db, recordingSleep, recordingKv), now, utc)

            assertEquals(
                listOf("prune", "heart-rate purge", "timestamp purge", "night move", "backfill", "heal", "cursor repair"),
                steps,
            )
            assertTrue(report.provenanceBackfill.isFailure)
            assertEquals(Result.success(listOf(day(14))), report.scoreHeal)
        }
    }

    @Test
    fun aFailingNightMoveIsReportedWithWhyAndTheStepsAfterItStillRun() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val injected = IllegalStateException("injected failure")
            val kvDao = db.kvDao()
            val failingKv = object : KvDao by kvDao {
                override suspend fun get(key: String): StoreKvEntity? = if (key == latchKey) throw injected else kvDao.get(key)
            }
            db.sleepDao().insertSummary(bedtimeKeyedUnfilled(10))
            db.sleepDao().insertSummary(scarred(14))
            db.sampleDao().upsertCursors(listOf(StoredCursorEntity("heartRate", now.plusSeconds(tenYears))))

            val report = LaunchRepairs.run(LocalStore(db), SleepStore(db, db.sleepDao(), failingKv), now, utc)

            // Coroutine stack-trace recovery may hand back a copy of the exception, so type and message.
            val why = assertIs<IllegalStateException>(report.nightRekey.exceptionOrNull())
            assertEquals(injected.message, why.message)
            assertEquals(Result.success(1), report.provenanceBackfill)
            assertEquals(Result.success(listOf(day(14))), report.scoreHeal)
            assertEquals(Result.success(1), report.cursorRepair)
            assertEquals(listOf(day(10), day(14)), db.sleepDao().allSummaries().map { it.night }, "the night was not moved")
        }
    }

    @Test
    fun cancellationInASleepStepStopsTheLaunch() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val sleepDao = db.sleepDao()
            val cancelling = object : SleepDao by sleepDao {
                override suspend fun allSummaries(): List<StoredSleepSummaryEntity> = throw CancellationException("launch cancelled")
            }
            db.kvDao().upsert(StoreKvEntity(latchKey, "true", earlier))
            val future = StoredCursorEntity("heartRate", now.plusSeconds(tenYears))
            db.sampleDao().upsertCursors(listOf(future))

            assertFailsWith<CancellationException> { LaunchRepairs.run(LocalStore(db), SleepStore(db, cancelling), now, utc) }

            assertEquals(listOf(future), db.sampleDao().allCursors(), "the cursor repair did not run")
        }
    }
}
