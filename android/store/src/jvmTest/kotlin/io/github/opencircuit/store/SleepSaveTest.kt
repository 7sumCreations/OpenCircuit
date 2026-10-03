package io.github.opencircuit.store

import io.github.opencircuit.ringkit.SleepEdit
import io.github.opencircuit.ringkit.SleepPersistOutcome
import io.github.opencircuit.ringkit.SleepProvenanceBreakdown
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import io.github.opencircuit.ringkit.SleepStaging
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The night save's branches and its extras rules, read from upstream's source
 * (ios/OpenCircuit/Store/LocalStore.swift:1582-1802 and `applyExtras` :1855-1910 @ b1c2fdd), which
 * has no store test of them: which outcome each branch reports, what a replacing save takes and
 * what it keeps, how a row is found (by span first, then by day), and the one-epoch tolerance of a
 * same-coverage re-classification.
 */
class SleepSaveTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val bed = Instant.parse("2025-06-14T22:00:00Z")
    private val now1 = Instant.parse("2025-06-15T08:00:00Z")
    private val now2 = Instant.parse("2025-06-15T10:00:00Z")
    private val now3 = Instant.parse("2025-06-15T12:00:00Z")

    private fun at(hours: Double): Instant = bed.plusMillis((hours * 3_600_000).toLong())

    /** The night key of every night here: the start of the day they end on. */
    private val key = Instant.parse("2025-06-15T00:00:00Z")

    private fun summary(inBedMin: Long, asleepMin: Long) = SleepStaging.Summary(
        inBed = Duration.ofMinutes(inBedMin), awake = Duration.ofMinutes(inBedMin - asleepMin),
        light = Duration.ofMinutes(asleepMin), deep = Duration.ZERO, rem = Duration.ZERO,
    )

    private suspend fun SleepStore.save(
        inBedMin: Long,
        asleepMin: Long,
        start: Instant = bed,
        end: Instant = start.plusSeconds(inBedMin * 60),
        onset: Instant = SleepEdit.DISTANT_PAST,
        wake: Instant = SleepEdit.DISTANT_PAST,
        extras: SleepNightExtras = SleepNightExtras(),
        now: Instant = now1,
    ): SleepPersistOutcome = saveSleepSummary(
        summary(inBedMin, asleepMin), night = end, inBedStart = start, inBedEnd = end, sleepOnset = onset, sleepWake = wake,
        extras = extras, now = now, zone = utc,
    )

    @Test
    fun eachSaveReportsWhichBranchRanAndOnlyAWriteStampsItsTime() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)

            assertEquals(SleepPersistOutcome.INSERTED, store.save(480, 420, now = now1))
            assertEquals(now1, store.sleepSummary(key, utc)?.updatedAt)

            assertEquals(SleepPersistOutcome.UPDATED, store.save(540, 480, now = now2))
            val replaced = assertNotNull(store.sleepSummary(key, utc))
            assertEquals(now2, replaced.updatedAt)
            assertEquals(480, replaced.asleepMin)

            assertEquals(SleepPersistOutcome.KEPT_FULLER_STORED_NIGHT, store.save(120, 100, start = at(5.0), now = now3))
            assertEquals(replaced, store.sleepSummary(key, utc), "a kept night is left exactly as stored")
        }
    }

    @Test
    fun aReplacingSaveKeepsWhatThisPassDidNotComputeAndTakesTheRest() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            val first = listOf(SleepSegment(at(0.0), at(8.0), SleepStage.IN_BED), SleepSegment(at(0.5), at(7.5), SleepStage.ASLEEP_CORE))
            val fuller = listOf(
                SleepSegment(at(-0.5), at(8.5), SleepStage.IN_BED),
                SleepSegment(at(0.0), at(4.0), SleepStage.ASLEEP_CORE),
                SleepSegment(at(4.0), at(6.0), SleepStage.ASLEEP_DEEP),
                SleepSegment(at(6.0), at(8.0), SleepStage.ASLEEP_REM),
            )
            store.save(
                480, 420,
                extras = SleepNightExtras(
                    skinTempC = 33.5, sleepScore = 80, stressScore = 20, movementLevels = listOf(1, 2, 3), hypnogram = first,
                    hrByStage = mapOf(SleepStage.ASLEEP_DEEP to 50, SleepStage.ASLEEP_CORE to 55, SleepStage.ASLEEP_REM to 58, SleepStage.AWAKE to 65),
                ),
            )

            // This pass computed no temperature, scores or movement, and a deep-sleep heart rate of 0.
            store.save(
                540, 480, start = at(-0.5), now = now2,
                extras = SleepNightExtras(hrByStage = mapOf(SleepStage.ASLEEP_DEEP to 0), hypnogram = fuller),
            )

            val n = assertNotNull(store.sleepSummary(key, utc))
            assertEquals(33.5, n.skinTempC)
            assertEquals(80, n.sleepScore)
            assertEquals(20, n.stressScore)
            assertEquals(listOf(1, 2, 3), n.movementLevels)
            // A heart rate is taken for every stage present, zero included; absent stages keep theirs.
            assertEquals(listOf(0, 55, 58, 65), listOf(n.hrDeep, n.hrLight, n.hrRem, n.hrAwake))
            // The timeline is always the one the minutes came from, and the ring's own reading too.
            assertEquals(fuller, n.hypnogram)
            assertEquals(fuller, n.recordedHypnogram)
            val b = SleepProvenanceBreakdown(fuller)
            assertEquals(
                listOf(b.measuredAsleep, b.assertedAsleep, b.coverageFraction, b.longestUnmeasuredGap, b.efficiency ?: -1.0),
                listOf(n.measuredAsleepSeconds, n.assertedAsleepSeconds, n.coverageFraction, n.longestGapSeconds, n.measuredEfficiency),
            )
            assertEquals(SleepBasis.MEASURED_ONLY, n.sleepBasis)
        }
    }

    @Test
    fun aWithheldTemperatureClearsAStoredOneWhileAZeroKeepsIt() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.save(480, 400, extras = SleepNightExtras(skinTempC = 33.5))

            store.save(480, 410)
            assertEquals(33.5, store.sleepSummary(key, utc)?.skinTempC, "zero means not computed: the stored value stays")

            store.save(480, 420, extras = SleepNightExtras(skinTempC = 34.0, skinTempWithheld = true))
            assertEquals(34.0, store.sleepSummary(key, utc)?.skinTempC, "a measured value wins over the withheld flag")

            store.save(480, 430, extras = SleepNightExtras(skinTempWithheld = true))
            assertEquals(0.0, store.sleepSummary(key, utc)?.skinTempC, "judged and rejected: the stored value is cleared")
        }
    }

    @Test
    fun aReplacingSaveLeavesFeelOsaAndTheEditColumnsAsStored() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.save(480, 420)
            val edited = at(-1.0).toEpochMilli()
            db.execRaw(
                "UPDATE stored_sleep_summary SET feel_score = 7, osa_avg_spo2 = 95.5, osa_min_spo2 = 88.0, osa_time_below_90_sec = 120.0, " +
                    "osa_odi = 3.5, osa_valid_windows = 40, edited_in_bed_start = $edited, edited_in_bed_end = $edited, " +
                    "widened_recorded_in_bed_start = $edited, widened_recorded_in_bed_end = $edited, widened_recorded_onset = $edited, " +
                    "widened_recorded_wake = $edited",
            )
            val before = assertNotNull(store.sleepSummary(key, utc))

            assertEquals(SleepPersistOutcome.UPDATED, store.save(540, 480, now = now2))

            assertEquals(
                before.copy(asleepMin = 480, lightMin = 480, awakeMin = 60, efficiency = 480.0 / 540, inBedEnd = bed.plusSeconds(540 * 60), updatedAt = now2),
                store.sleepSummary(key, utc),
            )
        }
    }

    @Test
    fun aReplacingSaveOverwritesTheOnsetAndWakeEvenWithTheDefaults() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.save(480, 420, onset = at(0.5), wake = at(7.5))
            assertEquals(at(0.5), store.sleepSummary(key, utc)?.sleepOnset)

            store.save(540, 480)

            val n = assertNotNull(store.sleepSummary(key, utc))
            assertEquals(SleepEdit.DISTANT_PAST, n.sleepOnset)
            assertEquals(SleepEdit.DISTANT_PAST, n.sleepWake)
        }
    }

    /**
     * An evening bout that finishes before midnight is keyed to that day; the completed night, hours
     * later, is keyed to the next. Found by its span, the completed night grows the bout's row
     * instead of filing a second night (:1594-1602).
     */
    @Test
    fun aNightIsFoundByItsSpanBeforeItsDaySoItGrowsInsteadOfDoubling() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            val boutStart = Instant.parse("2025-06-14T21:00:00Z")
            assertEquals(SleepPersistOutcome.INSERTED, store.save(150, 120, start = boutStart)) // ends 23:30, keyed 2025-06-14

            val outcome = store.save(600, 540, start = boutStart) // ends 07:00 the next day, keyed 2025-06-15

            assertEquals(SleepPersistOutcome.UPDATED, outcome)
            assertEquals(listOf(540), db.sleepDao().allSummaries().map { it.asleepMin })
        }
    }

    /**
     * The span decides before the day does: with another block already keyed to the completed
     * night's day, the completed night still grows the evening bout it overlaps and leaves that
     * block alone.
     */
    @Test
    fun aSpanMatchWinsOverAnotherNightKeyedToTheSameDay() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            val boutStart = Instant.parse("2025-06-14T21:00:00Z")
            store.save(150, 120, start = boutStart) // ends 23:30, keyed 2025-06-14
            store.save(60, 50, start = Instant.parse("2025-06-15T15:00:00Z")) // an afternoon block, keyed 2025-06-15

            assertEquals(SleepPersistOutcome.UPDATED, store.save(600, 540, start = boutStart)) // ends 07:00, keyed 2025-06-15

            assertEquals(
                listOf(Instant.parse("2025-06-14T00:00:00Z") to 540, key to 50),
                db.sleepDao().allSummaries().map { it.night to it.asleepMin },
            )
        }
    }

    @Test
    fun aNightWithNoKnownWindowIsMatchedByItsDay() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            val unknown = SleepEdit.DISTANT_PAST
            val night = Instant.parse("2025-06-15T06:00:00Z")
            store.saveSleepSummary(summary(300, 300), night = night, inBedStart = unknown, inBedEnd = unknown, now = now1, zone = utc)

            val outcome = store.saveSleepSummary(
                summary(400, 400), night = night.plusSeconds(3_600), inBedStart = unknown, inBedEnd = unknown, now = now2, zone = utc,
            )

            assertEquals(SleepPersistOutcome.UPDATED, outcome)
            assertEquals(listOf(key to 400), db.sleepDao().allSummaries().map { it.night to it.asleepMin })
        }
    }

    /**
     * Both edges within one ring epoch (150 s) of the stored ones is the same coverage, so a
     * re-classification may lower the minutes; 151 s off is a different capture, and the fuller
     * stored night stays (:1700-1703).
     */
    @Test
    fun aSameCoverageReclassificationIsBothEdgesWithinOneRingEpoch() = runBlocking<Unit> {
        for ((shiftSeconds, expected) in listOf(150L to 380, 151L to 470)) {
            withInMemoryStore { db ->
                val store = SleepStore(db)
                store.save(480, 470)

                store.save(480, 380, start = bed.plusSeconds(shiftSeconds))

                assertEquals(expected, store.sleepSummary(key, utc)?.asleepMin, "both edges $shiftSeconds s off")
            }
        }
    }
}
