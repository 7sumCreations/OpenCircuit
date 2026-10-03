package io.github.opencircuit.store

import io.github.opencircuit.ringkit.DateInterval
import io.github.opencircuit.ringkit.MeasuredCoverage
import io.github.opencircuit.ringkit.SleepHypnogramCodec
import io.github.opencircuit.ringkit.SleepProvenance
import io.github.opencircuit.ringkit.SleepProvenance.ASSERTED
import io.github.opencircuit.ringkit.SleepProvenance.ASSERTED_OVER_MEASURED
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import io.github.opencircuit.ringkit.SleepStage.ASLEEP_CORE
import io.github.opencircuit.ringkit.SleepStage.AWAKE
import io.github.opencircuit.ringkit.SleepStage.IN_BED
import io.github.opencircuit.store.codec.Decoded
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * The re-labelling of an edited night's proven holes the record archive has since filled: upstream
 * `rederiveEditedNightProvenance` (ios/OpenCircuit/Store/LocalStore.swift:2293-2365 @ b1c2fdd). Only
 * the timeline labels, the six provenance columns and the update time change; the night's Health
 * rewrite is queued with its current edges and the upgraded timeline.
 *
 * Upstream saves the rows, then writes the queue to `UserDefaults` after the save. Here the rows and
 * the queue are one transaction, and a queue this build cannot read fails the call with nothing
 * changed (PORTING D-176), where upstream reads it as empty and writes over it.
 */
class ProvenanceRederiveTest {

    private val utc = ZoneOffset.UTC
    private val t = Instant.parse("2025-06-14T22:00:00Z")
    private val night = Instant.parse("2025-06-15T00:00:00Z")
    private val earlier = Instant.parse("2025-06-15T09:00:00Z")
    private val launch = Instant.parse("2025-06-15T12:00:00Z")
    private val queueKey = "sleep.edit.pending-reconcile.v2"
    private val none = ByteArray(0)

    private fun at(hours: Double): Instant = t.plusMillis((hours * 3_600_000).toLong())

    private fun seg(from: Double, to: Double, stage: SleepStage, p: SleepProvenance = SleepProvenance.MEASURED) =
        SleepSegment(at(from), at(to), stage, p)

    /**
     * The wearer moved her wake from 06:00 to 07:00 when the newest record was 06:00, so the last hour
     * was scored a proven hole.
     */
    private val edited = listOf(
        seg(0.0, 8.0, IN_BED), seg(8.0, 9.0, IN_BED, ASSERTED),
        seg(0.0, 1.0, AWAKE), seg(1.0, 8.0, ASLEEP_CORE), seg(8.0, 9.0, ASLEEP_CORE, ASSERTED),
    )

    /** Records arrived for 06:00–06:30. */
    private val grown = MeasuredCoverage(listOf(DateInterval(at(-2.0), at(8.5))))

    /** [edited] with 06:00–06:30 re-labelled asserted-over-measured, split where the records end. */
    private val upgraded = listOf(
        seg(0.0, 8.0, IN_BED), seg(8.0, 8.5, IN_BED, ASSERTED_OVER_MEASURED), seg(8.5, 9.0, IN_BED, ASSERTED),
        seg(0.0, 1.0, AWAKE), seg(1.0, 8.0, ASLEEP_CORE), seg(8.0, 8.5, ASLEEP_CORE, ASSERTED_OVER_MEASURED), seg(8.5, 9.0, ASLEEP_CORE, ASSERTED),
    )

    private val onset = at(0.75)

    private fun editedNight(key: Instant = night, isEdited: Boolean = true) = StoredSleepSummaryEntity(
        night = key, asleepMin = 480, lightMin = 480, awakeMin = 60, efficiency = 0.8889, sleepScore = 81,
        inBedStart = at(0.0), inBedEnd = at(8.0), sleepOnset = at(1.0), sleepWake = at(8.0), updatedAt = earlier,
        isManuallyEdited = isEdited, editedInBedStart = at(0.0), editedInBedEnd = at(9.0),
        hypnogramData = SleepHypnogramCodec.encode(edited), sleepBasis = SleepBasis.ASSERTED_TAGGED.rawValue,
        measuredAsleepSeconds = 25_200.0, assertedAsleepSeconds = 3_600.0, coverageFraction = 8.0 / 9.0, longestGapSeconds = 3_600.0,
        measuredEfficiency = 0.875,
    )

    private suspend fun seed(db: StoreDatabase, row: StoredSleepSummaryEntity = editedNight()) {
        db.sleepDao().insertSummary(row)
        db.kvDao().upsert(StoreKvEntity("sleep.edit.onset.${row.night.epochSecond}.0", onset.toEpochMilli().toString(), earlier))
    }

    private suspend fun queue(db: StoreDatabase) =
        assertIs<Decoded.Readable<List<PendingSleepReconcile>>>(PendingSleepReconciles(db.kvDao()).all()).value

    @Test
    fun anEditedNightsFilledHoleIsRelabelledItsSplitRecomputedAndItsHealthRewriteQueuedWithItsCurrentEdges() = runBlocking<Unit> {
        withInMemoryStore { db ->
            seed(db)

            val changed = SleepStore(db).rederiveEditedNightProvenance(grown, launch, utc)

            assertEquals(listOf(RederivedNight(night, 1_800.0)), changed)
            val row = db.sleepDao().allSummaries().single()
            assertEquals(upgraded, SleepHypnogramCodec.decode(row.hypnogramData))
            assertEquals(27_000.0, row.measuredAsleepSeconds)
            assertEquals(1_800.0, row.assertedAsleepSeconds)
            assertEquals(8.5 / 9.0, row.coverageFraction)
            assertEquals(1_800.0, row.longestGapSeconds)
            assertEquals(27_000.0 / 30_600.0, row.measuredEfficiency)
            assertEquals("assertedTagged", row.sleepBasis)
            assertEquals(launch, row.updatedAt)
            // The edit stays authoritative: its window, minutes, efficiency and score are as saved.
            assertEquals(
                editedNight().copy(id = row.id, hypnogramData = none, recordedHypnogramData = none),
                row.copy(
                    hypnogramData = none, recordedHypnogramData = none, measuredAsleepSeconds = 25_200.0, assertedAsleepSeconds = 3_600.0,
                    coverageFraction = 8.0 / 9.0, longestGapSeconds = 3_600.0, measuredEfficiency = 0.875, updatedAt = earlier,
                ),
            )
            assertEquals(listOf(PendingSleepReconcile(night, at(0.0), onset, at(9.0), upgraded)), queue(db))
        }
    }

    @Test
    fun anEmptyArchiveChangesNothingAndDoesNotReadTheQueue() = runBlocking<Unit> {
        withInMemoryStore { db ->
            seed(db)
            db.kvDao().upsert(StoreKvEntity(queueKey, "not a queue", earlier))
            val before = TableSnapshot.of(db)

            assertEquals(emptyList(), SleepStore(db).rederiveEditedNightProvenance(MeasuredCoverage.EMPTY, launch, utc))

            assertEquals(before, TableSnapshot.of(db))
        }
    }

    @Test
    fun anUneditedNightAHoleStillWithoutRecordsAndATimelineWithNoSegmentsAreLeftAsStoredAndNothingQueued() = runBlocking<Unit> {
        withInMemoryStore { db ->
            seed(db, editedNight(isEdited = false))
            seed(db, editedNight(key = night.plusSeconds(86_400)).copy(hypnogramData = "[]".encodeToByteArray()))
            seed(db, editedNight(key = night.plusSeconds(2 * 86_400L)).copy(hypnogramData = "not a timeline".encodeToByteArray()))
            val before = TableSnapshot.of(db)
            val elsewhere = MeasuredCoverage(listOf(DateInterval(at(-6.0), at(-5.0))))

            val sleep = SleepStore(db)
            assertEquals(emptyList(), sleep.rederiveEditedNightProvenance(grown, launch, utc))
            assertEquals(emptyList(), sleep.rederiveEditedNightProvenance(elsewhere, launch, utc))

            assertEquals(before, TableSnapshot.of(db))
        }
    }

    @Test
    fun aHoleStillWithoutRecordsLeavesAnEditedNightAsStored() = runBlocking<Unit> {
        withInMemoryStore { db ->
            seed(db)
            val before = TableSnapshot.of(db)

            assertEquals(emptyList(), SleepStore(db).rederiveEditedNightProvenance(MeasuredCoverage(listOf(DateInterval(at(-6.0), at(-5.0)))), launch, utc))

            assertEquals(before, TableSnapshot.of(db))
        }
    }

    @Test
    fun aSecondRunOverTheSameArchiveChangesNothing() = runBlocking<Unit> {
        withInMemoryStore { db ->
            seed(db)
            val sleep = SleepStore(db)
            sleep.rederiveEditedNightProvenance(grown, launch, utc)
            val after = TableSnapshot.of(db)

            assertEquals(emptyList(), sleep.rederiveEditedNightProvenance(grown, launch.plusSeconds(60), utc))

            assertEquals(after, TableSnapshot.of(db))
        }
    }

    @Test
    fun aQueueThisBuildCannotReadFailsTheCallAndLeavesTheNightAndTheQueueAsStored() = runBlocking<Unit> {
        withInMemoryStore { db ->
            seed(db)
            db.kvDao().upsert(StoreKvEntity(queueKey, "{\"from\":\"a newer build\"}", earlier))
            val before = TableSnapshot.of(db)

            assertFailsWith<SleepStoreException.UnreadablePendingReconcile> {
                SleepStore(db).rederiveEditedNightProvenance(grown, launch, utc)
            }

            assertEquals(before, TableSnapshot.of(db))
            assertEquals("{\"from\":\"a newer build\"}", db.kvDao().get(queueKey)?.value)
        }
    }

    @Test
    fun theQueuedItemReplacesTheOneQueuedForThatDayAndKeepsTheOtherDays() = runBlocking<Unit> {
        withInMemoryStore { db ->
            seed(db)
            val queued = PendingSleepReconciles(db.kvDao())
            val other = PendingSleepReconcile(night.minusSeconds(86_400), at(-24.0), at(-23.0), at(-16.0), emptyList())
            queued.upsert(other, utc, earlier)
            queued.upsert(PendingSleepReconcile(night, at(0.0), onset, at(9.0), edited), utc, earlier)

            SleepStore(db).rederiveEditedNightProvenance(grown, launch, utc)

            assertEquals(listOf(other, PendingSleepReconcile(night, at(0.0), onset, at(9.0), upgraded)), queue(db))
        }
    }

    @Test
    fun theQueuesDayIsTheZonesDay() = runBlocking<Unit> {
        withInMemoryStore { db ->
            // Paris: the night is keyed 22:00Z on the 14th, its Save queued at 05:00Z on the 15th — two
            // UTC days, one Paris day.
            val paris = ZoneId.of("Europe/Paris")
            val parisNight = Instant.parse("2025-06-14T22:00:00Z")
            seed(db, editedNight(key = parisNight))
            PendingSleepReconciles(db.kvDao()).upsert(
                PendingSleepReconcile(Instant.parse("2025-06-15T05:00:00Z"), at(0.0), onset, at(9.0), edited), paris, earlier,
            )

            SleepStore(db).rederiveEditedNightProvenance(grown, launch, paris)

            assertEquals(listOf(PendingSleepReconcile(parisNight, at(0.0), onset, at(9.0), upgraded)), queue(db))
        }
    }

    @Test
    fun aFailedQueueWriteLeavesTheNightAsStoredAndAFailedRowWriteQueuesNothing() = runBlocking<Unit> {
        withInMemoryStore { db ->
            seed(db)
            val before = TableSnapshot.of(db)
            val realKv = db.kvDao()
            val failingKv = object : KvDao by realKv {
                override suspend fun upsert(entry: StoreKvEntity) {
                    if (entry.key == queueKey) error("injected failure")
                    realKv.upsert(entry)
                }
            }
            val realSleep = db.sleepDao()
            val failingSleep = object : SleepDao by realSleep {
                override suspend fun updateSummary(row: StoredSleepSummaryEntity) = error("injected failure")
            }

            assertFails { SleepStore(db, realSleep, failingKv).rederiveEditedNightProvenance(grown, launch, utc) }
            assertEquals(before, TableSnapshot.of(db))

            assertFails { SleepStore(db, failingSleep).rederiveEditedNightProvenance(grown, launch, utc) }
            assertEquals(before, TableSnapshot.of(db))
            assertContentEquals(SleepHypnogramCodec.encode(edited), db.sleepDao().allSummaries().single().hypnogramData)
        }
    }
}
