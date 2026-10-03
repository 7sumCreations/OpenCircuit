package io.github.opencircuit.store

import io.github.opencircuit.ringkit.SleepEdit
import io.github.opencircuit.ringkit.SleepHypnogramCodec
import io.github.opencircuit.ringkit.SleepProvenance
import io.github.opencircuit.ringkit.SleepScore
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import io.github.opencircuit.ringkit.SleepStaging
import io.github.opencircuit.store.codec.PriorTimesCodec
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A wearer's edit of a stored night, read from upstream's source (ios/OpenCircuit/Store/
 * LocalStore.swift `applySleepEdit` :2133-2257 @ b1c2fdd), which its tests leave unchecked: what an
 * edit writes and what it leaves, the undo stack and the onset it keeps beside the row, the ring's
 * timeline kept before a first edit replaces it, and its refusals.
 */
class SleepEditTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val ref = Instant.ofEpochSecond(1_750_000_000)
    private val now = Instant.parse("2025-06-16T09:00:00Z")
    private val later = Instant.parse("2025-06-16T10:00:00Z")

    private fun at(hours: Double): Instant = ref.plusMillis((hours * 3_600_000).toLong())

    /** 8 h in bed, 7 h asleep, every segment measured. */
    private val fullNight = listOf(
        SleepSegment(at(0.0), at(8.0), SleepStage.IN_BED),
        SleepSegment(at(0.0), at(0.5), SleepStage.AWAKE),
        SleepSegment(at(0.5), at(3.0), SleepStage.ASLEEP_CORE),
        SleepSegment(at(3.0), at(4.5), SleepStage.ASLEEP_DEEP),
        SleepSegment(at(4.5), at(6.0), SleepStage.ASLEEP_REM),
        SleepSegment(at(6.0), at(7.5), SleepStage.ASLEEP_CORE),
        SleepSegment(at(7.5), at(8.0), SleepStage.AWAKE),
    )

    /** [fullNight] with an hour the wearer added before it, over ground the ring recorded nothing on. */
    private val withAssertedHour = listOf(
        SleepSegment(at(-1.0), at(0.0), SleepStage.IN_BED, SleepProvenance.ASSERTED),
        SleepSegment(at(-1.0), at(0.0), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED),
    ) + fullNight

    private val extras = SleepNightExtras(
        skinTempC = 33.5, sleepScore = 70, stressScore = 20,
        hrByStage = mapOf(SleepStage.ASLEEP_DEEP to 52, SleepStage.ASLEEP_CORE to 55, SleepStage.ASLEEP_REM to 58, SleepStage.AWAKE to 64),
        movementLevels = listOf(1, 2, 3), hypnogram = fullNight,
    )

    private suspend fun seed(store: SleepStore) {
        store.saveSleepSummary(
            SleepStaging.summary(fullNight), night = at(0.0), inBedStart = at(0.0), inBedEnd = at(8.0),
            sleepOnset = at(0.5), sleepWake = at(7.5), extras = extras, now = now, zone = utc,
        )
    }

    private fun asleepFor(hours: Long) = SleepStaging.Summary(
        inBed = Duration.ofHours(hours), awake = Duration.ZERO, light = Duration.ofHours(hours), deep = Duration.ZERO, rem = Duration.ZERO,
    )

    private suspend fun StoreDatabase.snapshot(): List<String> =
        queryRaw("SELECT * FROM stored_sleep_summary ORDER BY id") + queryRaw("SELECT * FROM store_kv ORDER BY `key`")

    private suspend fun StoreDatabase.stack(night: Instant): List<SleepEdit.Times>? =
        kvDao().get(NightOverlays.key(NightOverlays.PRIOR_TIMES, night))?.value?.let { PriorTimesCodec.decode(it).valueOrNull() }

    @Test
    fun anEditOfADayWithNoStoredNightReturnsFalseAndWritesNothing() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            seed(store)
            val before = db.snapshot()

            assertFalse(store.applySleepEdit(at(48.0), SleepEdit.Times(at(47.0), at(47.5), at(56.0)), asleepFor(9), now = later, zone = utc))

            assertEquals(before, db.snapshot())
        }
    }

    /** Defense in depth behind the editor's own dirty check (:2141-2145): the same minutes write nothing. */
    @Test
    fun resubmittingAnEditedNightsMinutesWritesNothingNotEvenItsUpdateTime() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            seed(store)
            val edit = SleepEdit.Times(at(-1.0), at(1.0), at(8.5)) // each 40 s into its minute
            store.applySleepEdit(at(0.0), edit, asleepFor(9), now = now, zone = utc)
            val before = db.snapshot()

            val sameMinutes = SleepEdit.Times(edit.inBedStart.plusSeconds(15), edit.sleepOnset.minusSeconds(40), edit.sleepWake.plusSeconds(19))
            assertTrue(store.applySleepEdit(at(0.0), sameMinutes, asleepFor(3), now = later, zone = utc))

            assertEquals(before, db.snapshot())
        }
    }

    /**
     * Each edit pushes the edges it replaces — the ring's on the first edit, the previous edit's on
     * the next — and saves its own onset, which the night then shows (not the recorded one clamped).
     */
    @Test
    fun eachEditStacksTheEdgesItReplacesAndTheNightShowsTheOnsetItSaved() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            seed(store)
            val first = SleepEdit.Times(at(-1.0), at(1.0), at(8.5))
            val second = SleepEdit.Times(at(-2.0), at(0.25), at(9.0))

            store.applySleepEdit(at(0.0), first, asleepFor(9), now = now, zone = utc)
            val afterFirst = assertNotNull(store.sleepSummary(at(0.0), utc))
            assertEquals(at(1.0), afterFirst.currentOnset)
            assertEquals(listOf(SleepEdit.Times(at(0.0), at(0.5), at(7.5))), db.stack(afterFirst.night))

            store.applySleepEdit(at(0.0), second, asleepFor(11), now = later, zone = utc)
            val afterSecond = assertNotNull(store.sleepSummary(at(0.0), utc))
            assertEquals(listOf(at(-2.0), at(0.25), at(9.0)), listOf(afterSecond.currentInBedStart, afterSecond.currentOnset, afterSecond.currentWake))
            assertEquals(listOf(SleepEdit.Times(at(0.0), at(0.5), at(7.5)), first), db.stack(afterSecond.night))
        }
    }

    /**
     * Upstream's edit writes the minutes, efficiency, timeline, provenance, score, edited window and
     * flag (:2146-2240) and nothing else: the recorded window and timeline, the widened clamp, the
     * apnea summary, feel, temperature, heart rates, stress and movement stay as stored. The score is
     * the composite of the summary's own seconds, and asserted time tags the night.
     */
    @Test
    fun anEditWritesOnlyItsOwnColumns() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            seed(store)
            db.execRaw(
                "UPDATE stored_sleep_summary SET feel_score = 6, osa_avg_spo2 = 95.5, osa_min_spo2 = 88.0, osa_time_below_90_sec = 120.0, " +
                    "osa_odi = 3.5, osa_valid_windows = 40, widened_recorded_in_bed_start = ${at(-0.5).toEpochMilli()}, " +
                    "widened_recorded_in_bed_end = ${at(8.5).toEpochMilli()}, widened_recorded_onset = ${at(0.25).toEpochMilli()}, " +
                    "widened_recorded_wake = ${at(8.25).toEpochMilli()}",
            )
            val before = assertNotNull(store.sleepSummary(at(0.0), utc))
            val summary = SleepStaging.summary(withAssertedHour)

            assertTrue(store.applySleepEdit(at(0.0), SleepEdit.Times(at(-1.0), at(-1.0), at(8.0)), summary, withAssertedHour, now = later, zone = utc))

            val after = assertNotNull(store.sleepSummary(at(0.0), utc))
            val m = summary.minutes
            val score = SleepScore.composite(
                SleepScore.CompositeInput(
                    totalAsleep = summary.totalAsleep.seconds.toDouble(), timeAwake = summary.awake.seconds.toDouble(),
                    efficiency = summary.efficiency, deep = summary.deep.seconds.toDouble(), light = summary.light.seconds.toDouble(),
                    rem = summary.rem.seconds.toDouble(),
                ),
            ).score
            assertEquals(
                before.copy(
                    asleepMin = m.asleep.toInt(), deepMin = m.deep.toInt(), lightMin = m.light.toInt(), remMin = m.rem.toInt(),
                    awakeMin = m.awake.toInt(), efficiency = summary.efficiency, hypnogram = withAssertedHour, sleepScore = score,
                    editedInBedStart = at(-1.0), editedInBedEnd = at(8.0), isManuallyEdited = true, updatedAt = later,
                    measuredAsleepSeconds = 25_200.0, assertedAsleepSeconds = 3_600.0, coverageFraction = 8.0 / 9.0,
                    longestGapSeconds = 3_600.0, measuredEfficiency = 0.875, sleepBasis = SleepBasis.ASSERTED_TAGGED, editedOnset = at(-1.0),
                ),
                after,
            )
            assertEquals(fullNight, after.recordedHypnogram, "the ring's own timeline is kept")
        }
    }

    /**
     * SQLite binds NaN as NULL, which the NOT NULL columns refuse. Every REAL column an edit writes
     * comes from a ratio guarded against a zero denominator — even for an explicit empty timeline
     * and a summary with no time in bed — and reads back as a number.
     */
    @Test
    fun everyRealColumnAnEditWritesReadsBackAsANumberEvenForNoTimeAtAll() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            seed(store)
            val nothing = SleepStaging.Summary(Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO)

            store.applySleepEdit(at(0.0), SleepEdit.Times(at(-1.0), at(-0.5), at(8.5)), nothing, emptyList(), now = later, zone = utc)

            assertEquals(
                listOf("real|real|real|real|real|real|0.0|0.0|0.0|-1.0|measuredOnly"),
                db.queryRaw(
                    "SELECT typeof(efficiency), typeof(measured_asleep_seconds), typeof(asserted_asleep_seconds), typeof(coverage_fraction), " +
                        "typeof(longest_gap_seconds), typeof(measured_efficiency), efficiency, measured_asleep_seconds, coverage_fraction, " +
                        "measured_efficiency, sleep_basis FROM stored_sleep_summary",
                ),
            )
        }
    }

    /**
     * Reversibility (:2154-2156): a night stored before the ring's timeline had its own column has
     * it copied there before the first edit replaces the timeline. An already edited night's timeline
     * is edit output and is never copied in as a recording.
     */
    @Test
    fun theFirstEditOfANightWithNoRecordedTimelineKeepsItsTimelineAsTheRingsReading() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val legacy = StoredSleepSummaryEntity(
                night = Instant.parse("2025-06-15T00:00:00Z"), asleepMin = 420, inBedStart = at(0.0), inBedEnd = at(8.0),
                sleepOnset = at(0.5), sleepWake = at(7.5), hypnogramData = SleepHypnogramCodec.encode(fullNight),
            )
            db.sleepDao().insertSummary(legacy)
            db.sleepDao().insertSummary(legacy.copy(night = legacy.night.plusSeconds(86_400 * 2), inBedStart = at(48.0), inBedEnd = at(56.0), isManuallyEdited = true))
            val store = SleepStore(db)

            store.applySleepEdit(at(0.0), SleepEdit.Times(at(-1.0), at(-1.0), at(8.0)), SleepStaging.summary(withAssertedHour), withAssertedHour, now = later, zone = utc)
            store.applySleepEdit(at(48.0), SleepEdit.Times(at(47.0), at(47.0), at(56.0)), SleepStaging.summary(withAssertedHour), withAssertedHour, now = later, zone = utc)

            val first = assertNotNull(store.sleepSummary(at(0.0), utc))
            assertEquals(fullNight, first.recordedHypnogram)
            assertEquals(withAssertedHour, first.hypnogram)
            assertEquals(emptyList(), assertNotNull(store.sleepSummary(at(48.0), utc)).recordedHypnogram, "edit output is never a recording")
        }
    }

    /**
     * Upstream reads an unreadable undo stack as empty and its next push writes over it. Here the
     * stored text is kept as it is and only the push is skipped: the edit itself, and its onset,
     * still land.
     */
    @Test
    fun anUnreadableUndoStackIsKeptAsStoredAndTheEditStillApplies() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            seed(store)
            val night = assertNotNull(store.sleepSummary(at(0.0), utc)).night
            val key = NightOverlays.key(NightOverlays.PRIOR_TIMES, night)
            val raw = "[{\"inBedStart\":\"yesterday\"}]"
            db.kvDao().upsert(StoreKvEntity(key, raw, now))

            assertTrue(store.applySleepEdit(at(0.0), SleepEdit.Times(at(-1.0), at(1.0), at(8.5)), asleepFor(9), now = later, zone = utc))

            val n = assertNotNull(store.sleepSummary(at(0.0), utc))
            assertTrue(n.isManuallyEdited)
            assertEquals(at(1.0), n.currentOnset)
            assertEquals(StoreKvEntity(key, raw, now), db.kvDao().get(key))
        }
    }

    /** Upstream's `try?` reads a failing lookup as "no night" and returns false (:2137). Here it fails the edit. */
    @Test
    fun aFailingLookupFailsTheEditAndWritesNothing() = runBlocking<Unit> {
        withInMemoryStore { db ->
            seed(SleepStore(db))
            val before = db.snapshot()
            val real = db.sleepDao()
            val failing = object : SleepDao by real {
                override suspend fun summaryAt(night: Instant) = error("injected failure")
            }

            assertFails {
                SleepStore(db, failing).applySleepEdit(at(0.0), SleepEdit.Times(at(-1.0), at(1.0), at(8.5)), asleepFor(9), now = later, zone = utc)
            }

            assertEquals(before, db.snapshot())
            assertNull(db.kvDao().get(NightOverlays.key(NightOverlays.ONSET, Instant.parse("2025-06-15T00:00:00Z"))))
        }
    }
}
