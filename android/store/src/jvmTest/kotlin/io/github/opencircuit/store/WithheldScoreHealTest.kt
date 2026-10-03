package io.github.opencircuit.store

import io.github.opencircuit.ringkit.SleepHypnogramCodec
import io.github.opencircuit.ringkit.SleepProvenance
import io.github.opencircuit.ringkit.SleepScoreHeal
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The launch-time repair of a sleep score an earlier build zeroed on the wearer's own edit: upstream
 * `healWithheldSleepScores` (ios/OpenCircuit/Store/LocalStore.swift:2405-2446 @ b1c2fdd). An edited
 * night with a zero score and a known basis gets the score rebuilt from its stored timeline
 * (`SleepScoreHeal.healedScore`), and its update time; nothing else on the row changes. Upstream
 * returns the healed nights newest first. One transaction: a failed write throws, changing nothing.
 */
class WithheldScoreHealTest {

    private val t = Instant.parse("2025-06-14T22:00:00Z")
    private val earlier = Instant.parse("2025-06-15T08:00:00Z")
    private val launch = Instant.parse("2025-06-20T08:00:00Z")

    private fun at(hours: Double): Instant = t.plusMillis((hours * 3_600_000).toLong())

    /** In bed 22:00–06:00: awake 45 min, then light, deep and REM; one asserted stretch at the end. */
    private val timeline = listOf(
        SleepSegment(at(0.0), at(8.0), SleepStage.IN_BED),
        SleepSegment(at(0.0), at(0.75), SleepStage.AWAKE),
        SleepSegment(at(0.75), at(4.0), SleepStage.ASLEEP_CORE),
        SleepSegment(at(4.0), at(5.5), SleepStage.ASLEEP_DEEP),
        SleepSegment(at(5.5), at(7.0), SleepStage.ASLEEP_REM),
        SleepSegment(at(7.0), at(8.0), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED),
    )

    private fun night(
        day: Int,
        score: Int = 0,
        basis: SleepBasis = SleepBasis.MEASURED_ONLY,
        edited: Boolean = true,
        hypnogram: ByteArray = SleepHypnogramCodec.encode(timeline),
    ) = StoredSleepSummaryEntity(
        night = Instant.parse("2025-06-15T00:00:00Z").plusSeconds((day - 15) * 86_400L),
        asleepMin = 435, efficiency = 0.9, inBedStart = at(0.0), inBedEnd = at(8.0), updatedAt = earlier, sleepScore = score,
        isManuallyEdited = edited, editedInBedStart = at(0.0), editedInBedEnd = at(8.0), sleepBasis = basis.rawValue,
        hypnogramData = hypnogram, measuredAsleepSeconds = 22_500.0, assertedAsleepSeconds = 3_600.0, coverageFraction = 0.875,
    )

    /** Every stored night, each cell with its storage class. */
    private suspend fun rows(db: StoreDatabase) = TableSnapshot.of(db).getValue("stored_sleep_summary")

    @Test
    fun anEditedNightWithAZeroScoreAndAKnownBasisGetsTheScoreItsTimelineGivesAndANewUpdateTime() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.sleepDao().insertSummary(night(15))
            db.sleepDao().insertSummary(night(16, basis = SleepBasis.ASSERTED_TAGGED))
            val before = db.sleepDao().allSummaries()
            val expected = assertNotNull(SleepScoreHeal.healedScore(timeline))
            assertTrue(expected > 0)

            val healed = SleepStore(db).healWithheldSleepScores(launch)

            assertEquals(listOf(before[1].night, before[0].night), healed)
            val after = db.sleepDao().allSummaries()
            assertEquals(listOf(expected, expected), after.map { it.sleepScore })
            assertEquals(listOf(launch, launch), after.map { it.updatedAt })
            // Nothing else on the row moved: the score and the update time put back give the stored row.
            // The byte columns are compared below by content (a ByteArray's `==` is identity).
            val none = ByteArray(0)
            assertEquals(
                before.map { it.copy(hypnogramData = none, recordedHypnogramData = none) },
                after.map { it.copy(sleepScore = 0, updatedAt = earlier, hypnogramData = none, recordedHypnogramData = none) },
            )
            assertEquals(before.map { it.hypnogramData.toList() }, after.map { it.hypnogramData.toList() })
        }
    }

    @Test
    fun anUneditedNightAScoredNightAnUnknownBasisAndATimelineWithNoAsleepTimeAreLeftAsStored() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val inBedOnly = SleepHypnogramCodec.encode(listOf(SleepSegment(at(0.0), at(8.0), SleepStage.IN_BED)))
            assertNull(SleepScoreHeal.healedScore(SleepHypnogramCodec.decode(inBedOnly)))
            db.sleepDao().insertSummary(night(15, edited = false))
            db.sleepDao().insertSummary(night(16, score = 55))
            db.sleepDao().insertSummary(night(17, basis = SleepBasis.UNKNOWN))
            db.sleepDao().insertSummary(night(18, hypnogram = inBedOnly))
            val before = rows(db)

            assertEquals(emptyList(), SleepStore(db).healWithheldSleepScores(launch))

            assertEquals(before, rows(db))
        }
    }

    @Test
    fun anEditedNightWithAKnownBasisAZeroScoreAndNoSegmentsIsLeftAsStored() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.sleepDao().insertSummary(night(15, hypnogram = "[]".encodeToByteArray()))
            db.sleepDao().insertSummary(night(16, hypnogram = "not a timeline".encodeToByteArray()))
            db.sleepDao().insertSummary(night(17, hypnogram = ByteArray(0)))
            val before = rows(db)

            assertEquals(emptyList(), SleepStore(db).healWithheldSleepScores(launch))

            assertEquals(before, rows(db))
        }
    }

    @Test
    fun healedNightsAreReturnedNewestFirstAndASecondRunHealsNothing() = runBlocking<Unit> {
        withInMemoryStore { db ->
            listOf(15, 17, 16).forEach { db.sleepDao().insertSummary(night(it)) }
            val sleep = SleepStore(db)

            val healed = sleep.healWithheldSleepScores(launch)

            assertEquals(db.sleepDao().allSummaries().map { it.night }.reversed(), healed)
            val after = rows(db)
            assertEquals(emptyList(), sleep.healWithheldSleepScores(launch.plusSeconds(60)))
            assertEquals(after, rows(db))
        }
    }

    @Test
    fun aFailedWriteThrowsAndLeavesEveryScoreAsStored() = runBlocking<Unit> {
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
            val before = rows(db)

            assertFails { SleepStore(db, failing).healWithheldSleepScores(launch) }

            assertEquals(2, writes, "the first night was written before the failure")
            assertEquals(before, rows(db))
        }
    }
}
