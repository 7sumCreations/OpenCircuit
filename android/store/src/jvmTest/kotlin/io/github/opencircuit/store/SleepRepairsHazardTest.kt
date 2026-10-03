package io.github.opencircuit.store

import io.github.opencircuit.ringkit.DateInterval
import io.github.opencircuit.ringkit.MeasuredCoverage
import io.github.opencircuit.ringkit.SleepHypnogramCodec
import io.github.opencircuit.ringkit.SleepProvenanceRederivation
import io.github.opencircuit.ringkit.SleepScoreHeal
import io.github.opencircuit.ringkit.SleepStaging
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the three launch-time sleep repairs do with a night that has no timeline. Upstream
 * (ios/OpenCircuit/Store/LocalStore.swift @ b1c2fdd) guards each of them on the stored BYTES being
 * non-empty (:1938, :2304, :2427), but a night saved without a timeline stores the codec's two-byte
 * `[]`, so the guard passes: the provenance backfill (:1926-1952) then stamps such a night "measured
 * only" with zero measured sleep. Here a timeline that decodes to no segments is no timeline for all
 * three (PORTING D-175). The heal and the re-derivation already leave it alone, because their
 * `:ringkit` functions return null for no segments — measured below, and end to end in
 * [WithheldScoreHealTest] and [ProvenanceRederiveTest].
 */
class SleepRepairsHazardTest {

    private val utc = ZoneOffset.UTC
    private val night = Instant.parse("2025-06-15T00:00:00Z")
    private val saved = Instant.parse("2025-06-15T08:00:00Z")

    /** Raw coverage over the whole night. */
    private val coverage = MeasuredCoverage(listOf(DateInterval(night.minusSeconds(6 * 3_600L), night.plusSeconds(12 * 3_600L))))

    @Test
    fun aTimelineWithNoSegmentsHasNoHealedScoreAndNoUpgrade() {
        assertEquals(emptyList(), SleepHypnogramCodec.decode("[]".encodeToByteArray()))
        assertNull(SleepScoreHeal.healedScore(emptyList()))
        assertNull(SleepProvenanceRederivation.upgraded(emptyList(), coverage))
    }

    @Test
    fun aNightSavedWithoutATimelineKeepsAnUnknownBasisThroughTheLaunchBackfill() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val sleep = SleepStore(db)
            sleep.saveSleepSummary(
                SleepStaging.Summary(inBed = Duration.ofHours(8), awake = Duration.ofMinutes(30), light = Duration.ofMinutes(450), deep = Duration.ZERO, rem = Duration.ZERO),
                night = night, inBedStart = night.minusSeconds(3_600), inBedEnd = night.plusSeconds(7 * 3_600L), now = saved, zone = utc,
            )
            val before = TableSnapshot.of(db)

            assertEquals(0, sleep.backfillSleepProvenance())

            assertEquals(before, TableSnapshot.of(db))
            assertEquals(SleepBasis.UNKNOWN, sleep.sleepSummary(night, utc)?.sleepBasis)
        }
    }

    @Test
    fun aNightWhoseTimelineCannotBeReadKeepsAnUnknownBasisThroughTheLaunchBackfill() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.sleepDao().insertSummary(
                StoredSleepSummaryEntity(
                    night = night, asleepMin = 420, inBedStart = night.minusSeconds(3_600), inBedEnd = night.plusSeconds(7 * 3_600L),
                    hypnogramData = "not a timeline".encodeToByteArray(),
                ),
            )
            val before = TableSnapshot.of(db)

            assertEquals(0, SleepStore(db).backfillSleepProvenance())

            assertEquals(before, TableSnapshot.of(db))
        }
    }
}
