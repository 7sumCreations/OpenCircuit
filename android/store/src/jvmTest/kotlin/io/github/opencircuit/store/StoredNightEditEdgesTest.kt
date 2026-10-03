package io.github.opencircuit.store

import io.github.opencircuit.ringkit.SleepEdit
import io.github.opencircuit.ringkit.SleepStaging
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The edges a stored night shows the editor and the clamp it is edited within, ported from
 * upstream's model accessors (ios/OpenCircuit/Store/LocalStore.swift:281-335 @ b1c2fdd):
 * `asSummary`, `sleepEditRecorded*`, `sleepEditClampWindow` and `sleepEditCurrent*`. Upstream's
 * current onset reads `UserDefaults` from inside the model getter; here every read of a night
 * resolves the onset overlay, in the same read transaction as the row.
 */
class StoredNightEditEdgesTest {

    private val utc = ZoneOffset.UTC
    private val night = Instant.parse("2025-06-15T00:00:00Z")
    private val now = Instant.parse("2025-06-16T09:00:00Z")

    private fun at(hours: Double): Instant = Instant.parse("2025-06-14T23:00:00Z").plusMillis((hours * 3_600_000).toLong())

    private val recordedRow = StoredSleepSummaryEntity(
        night = night, asleepMin = 420, deepMin = 90, lightMin = 270, remMin = 60, awakeMin = 30, efficiency = 0.875,
        inBedStart = at(0.0), inBedEnd = at(8.0), sleepOnset = at(0.5), sleepWake = at(7.75), updatedAt = now,
    )

    private fun edited(start: Instant = at(-1.0), end: Instant = at(9.0)) =
        recordedRow.copy(editedInBedStart = start, editedInBedEnd = end, isManuallyEdited = true)

    @Test
    fun anUneditedNightsCurrentEdgesAreItsRecordedOnes() {
        val n = recordedRow.toStoredNight(editedOnset = null)
        assertEquals(listOf(at(0.0), at(8.0), at(0.5), at(7.75)), listOf(n.currentInBedStart, n.currentInBedEnd, n.currentOnset, n.currentWake))
    }

    /** The edited end is the wake: an edit stores one edited end, used for both (:318-335). */
    @Test
    fun anEditedNightsCurrentEdgesAreItsEditedOnesWithTheEditedEndAsTheWake() {
        val n = edited().toStoredNight(editedOnset = at(-0.5))
        assertEquals(listOf(at(-1.0), at(9.0), at(-0.5), at(9.0)), listOf(n.currentInBedStart, n.currentInBedEnd, n.currentOnset, n.currentWake))
        assertEquals(at(0.5), n.sleepOnset, "the ring's onset stays as recorded")
    }

    /**
     * With no onset saved for the edit, the recorded onset clamped into the edited window (:324-332)
     * — never the bedtime, which would read as falling asleep at once; an unknown recorded onset is
     * the edited bedtime.
     */
    @Test
    fun anEditedNightWithNoSavedOnsetShowsTheRecordedOnsetClampedIntoTheEditedWindow() {
        assertEquals(at(0.5), edited().toStoredNight(null).currentOnset, "inside the window: as recorded")
        assertEquals(at(1.0), edited(start = at(1.0)).toStoredNight(null).currentOnset, "before the window: its start")
        assertEquals(at(0.25), edited(start = at(0.0), end = at(0.25)).toStoredNight(null).currentOnset, "after the window: its end")
        assertEquals(
            at(-1.0),
            edited().copy(sleepOnset = SleepEdit.DISTANT_PAST).toStoredNight(null).currentOnset,
            "an unknown recorded onset: the edited bedtime",
        )
    }

    @Test
    fun theClampWindowIsTheRecordedWindowWidenedByAnyFullerStagingSinceTheEdit() {
        val recorded = SleepEdit.RecordedWindow(at(0.0), at(8.0), at(0.5), at(7.75))
        assertEquals(recorded, edited().toStoredNight(null).recordedWindow)
        assertEquals(recorded, edited().toStoredNight(null).clampWindow, "nothing widened: the recorded window")

        val widened = edited().copy(
            widenedRecordedInBedStart = at(-0.5), widenedRecordedInBedEnd = at(8.5),
            widenedRecordedOnset = at(0.25), widenedRecordedWake = at(8.25),
        ).toStoredNight(null)

        assertEquals(SleepEdit.RecordedWindow(at(-0.5), at(8.5), at(0.25), at(8.25)), widened.clampWindow)
        assertEquals(recorded, widened.recordedWindow, "the recorded window itself never moves")
    }

    /** Upstream `asSummary` (:281-289): in bed recovered from the efficiency, or asleep + awake without one. */
    @Test
    fun theSummaryRecoversInBedFromTheEfficiencyOrFromAsleepPlusAwakeWithoutOne() {
        assertEquals(
            SleepStaging.Summary(
                inBed = Duration.ofMinutes(480), awake = Duration.ofMinutes(30), light = Duration.ofMinutes(270),
                deep = Duration.ofMinutes(90), rem = Duration.ofMinutes(60),
            ),
            recordedRow.toStoredNight(null).asSummary,
        )
        assertEquals(Duration.ofMinutes(450), recordedRow.copy(efficiency = 0.0).toStoredNight(null).asSummary.inBed)
        assertEquals(Duration.ofMinutes(450), recordedRow.copy(efficiency = -0.5).toStoredNight(null).asSummary.inBed)
    }

    /**
     * Swift's `TimeInterval` cannot overflow where a `Duration` of nanoseconds can: an efficiency so
     * small that asleep / efficiency passes ~292 years still gives a summary, its in-bed time capped.
     */
    @Test
    fun aVanishinglySmallStoredEfficiencyStillGivesASummary() {
        val s = recordedRow.copy(efficiency = 1e-300).toStoredNight(null).asSummary
        assertEquals(Duration.ofNanos(Long.MAX_VALUE), s.inBed)
        assertEquals(Duration.ofMinutes(420), s.totalAsleep)
    }

    @Test
    fun everyReadOfAnEditedNightResolvesItsSavedOnsetAndAnUneditedNightIgnoresOne() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val later = night.plusSeconds(86_400)
            db.sleepDao().insertSummary(edited())
            db.sleepDao().insertSummary(recordedRow.copy(night = later, inBedStart = at(24.0), inBedEnd = at(32.0)))
            val kv = db.kvDao()
            kv.upsert(StoreKvEntity(NightOverlays.key(NightOverlays.ONSET, night), at(-0.5).toEpochMilli().toString(), now))
            kv.upsert(StoreKvEntity(NightOverlays.key(NightOverlays.ONSET, later), at(25.0).toEpochMilli().toString(), now))
            val store = SleepStore(db)

            val reads = listOf(
                assertNotNull(store.sleepSummary(night, utc)),
                assertNotNull(store.recentSleepSummaries().last()),
                store.sleepSummaries(night, later).single(),
                assertNotNull(store.sleepSummaryOverlapping(at(1.0), at(2.0))),
            )
            for (n in reads) {
                assertEquals(at(-0.5), n.editedOnset)
                assertEquals(at(-0.5), n.currentOnset)
            }
            val unedited = assertNotNull(store.latestSleepSummary())
            assertNull(unedited.editedOnset, "an unedited night has no edited onset")
            assertEquals(at(0.5), unedited.currentOnset)
        }
    }

    @Test
    fun anEditedNightWhoseSavedOnsetCannotBeReadShowsTheClampedRecordedOnset() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.sleepDao().insertSummary(edited(start = at(1.0)))
            db.kvDao().upsert(StoreKvEntity(NightOverlays.key(NightOverlays.ONSET, night), "1750001800000.5", now))

            val n = assertNotNull(SleepStore(db).sleepSummary(night, utc))

            assertNull(n.editedOnset)
            assertEquals(at(1.0), n.currentOnset)
        }
    }
}
