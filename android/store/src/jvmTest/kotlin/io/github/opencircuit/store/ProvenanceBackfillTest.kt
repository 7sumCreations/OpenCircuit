package io.github.opencircuit.store

import io.github.opencircuit.ringkit.SleepHypnogramCodec
import io.github.opencircuit.ringkit.SleepProvenance
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * The launch-time fill of a stored night's provenance split: upstream `backfillSleepProvenance`
 * (ios/OpenCircuit/Store/LocalStore.swift:1926-1952 @ b1c2fdd). Only an unknown-basis, never-edited
 * night with a timeline is filled; the ring's own reading is copied only when empty; the update time
 * is not touched (upstream does not bump it). Upstream swallows a failed save and still returns its
 * count; here the fill is one transaction that throws, changing nothing.
 */
class ProvenanceBackfillTest {

    private val t = Instant.parse("2025-06-14T22:00:00Z")
    private val earlier = Instant.parse("2025-06-15T08:00:00Z")

    private fun at(hours: Double): Instant = t.plusMillis((hours * 3_600_000).toLong())

    /** In bed 22:00–06:00, awake until 23:00, asleep until 05:00 measured and until 05:30 asserted. */
    private val timeline = listOf(
        SleepSegment(at(0.0), at(8.0), SleepStage.IN_BED),
        SleepSegment(at(0.0), at(1.0), SleepStage.AWAKE),
        SleepSegment(at(1.0), at(7.0), SleepStage.ASLEEP_CORE),
        SleepSegment(at(7.0), at(7.5), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED),
    )
    private val timelineBytes = SleepHypnogramCodec.encode(timeline)

    private fun night(day: Int, basis: SleepBasis = SleepBasis.UNKNOWN, edited: Boolean = false, recorded: ByteArray = ByteArray(0)) =
        StoredSleepSummaryEntity(
            night = Instant.parse("2025-06-15T00:00:00Z").plusSeconds((day - 15) * 86_400L),
            asleepMin = 390, inBedStart = at(0.0), inBedEnd = at(8.0), updatedAt = earlier,
            isManuallyEdited = edited, sleepBasis = basis.rawValue, hypnogramData = timelineBytes, recordedHypnogramData = recorded,
        )

    @Test
    fun anUnknownNeverEditedNightIsFilledFromItsTimelineAndItsReadingCopiedWithoutTouchingItsUpdateTime() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.sleepDao().insertSummary(night(15))

            assertEquals(1, SleepStore(db).backfillSleepProvenance())

            val row = db.sleepDao().allSummaries().single()
            assertEquals(6 * 3_600.0, row.measuredAsleepSeconds)
            assertEquals(1_800.0, row.assertedAsleepSeconds)
            assertEquals(1.0, row.coverageFraction)
            assertEquals(1_800.0, row.longestGapSeconds)
            assertEquals(0.75, row.measuredEfficiency)
            assertEquals("measuredOnly", row.sleepBasis)
            assertContentEquals(timelineBytes, row.recordedHypnogramData)
            assertContentEquals(timelineBytes, row.hypnogramData)
            assertEquals(earlier, row.updatedAt)
            assertEquals(390, row.asleepMin)
        }
    }

    @Test
    fun aRingReadingAlreadyKeptIsNotOverwritten() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val kept = SleepHypnogramCodec.encode(timeline.take(2))
            db.sleepDao().insertSummary(night(15, recorded = kept))

            assertEquals(1, SleepStore(db).backfillSleepProvenance())

            assertContentEquals(kept, db.sleepDao().allSummaries().single().recordedHypnogramData)
        }
    }

    @Test
    fun anEditedNightAndANightWithAKnownBasisAreLeftExactlyAsStored() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.sleepDao().insertSummary(night(15, edited = true))
            db.sleepDao().insertSummary(night(16, basis = SleepBasis.MEASURED_ONLY))
            db.sleepDao().insertSummary(night(17, basis = SleepBasis.ASSERTED_TAGGED))
            val before = TableSnapshot.of(db)

            assertEquals(0, SleepStore(db).backfillSleepProvenance())

            assertEquals(before, TableSnapshot.of(db))
        }
    }

    @Test
    fun aSecondRunFillsNothingAndChangesNothing() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.sleepDao().insertSummary(night(15))
            db.sleepDao().insertSummary(night(16))
            val sleep = SleepStore(db)
            assertEquals(2, sleep.backfillSleepProvenance())
            val filled = TableSnapshot.of(db)

            assertEquals(0, sleep.backfillSleepProvenance())

            assertEquals(filled, TableSnapshot.of(db))
        }
    }

    @Test
    fun aFailedWriteThrowsAndLeavesEveryNightUnfilled() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.sleepDao().insertSummary(night(15))
            db.sleepDao().insertSummary(night(16))
            val real = db.sleepDao()
            var writes = 0
            val failing = object : SleepDao by real {
                override suspend fun updateSummary(row: StoredSleepSummaryEntity) {
                    if (++writes == 2) error("injected failure")
                    real.updateSummary(row)
                }
            }
            val before = TableSnapshot.of(db)

            assertFails { SleepStore(db, failing).backfillSleepProvenance() }

            assertEquals(2, writes, "the first night was written before the failure")
            assertEquals(before, TableSnapshot.of(db))
        }
    }
}
