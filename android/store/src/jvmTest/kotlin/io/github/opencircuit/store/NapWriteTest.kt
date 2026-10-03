package io.github.opencircuit.store

import io.github.opencircuit.ringkit.SleepProvenance
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three nap writes, read from upstream's source (ios/OpenCircuit/Store/LocalStore.swift
 * `saveNap` :2865-2888, `addManualNap` :2895-2915, `editNap` :2938-2974, `overlapsStoredNight`
 * :2981-2995 @ b1c2fdd), which has no store test of any: a re-detected nap updates the nap stored at
 * its start, a wearer's nap is never replaced by the ring, no nap is stored inside a stored night,
 * and an edit that no longer covers what was written to Health marks the nap for rewriting.
 */
class NapWriteTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val now = Instant.parse("2025-06-20T12:00:00Z")
    private val later = Instant.parse("2025-06-21T12:00:00Z")

    private fun at(text: String): Instant = Instant.parse(text)

    private val start = at("2025-06-15T13:00:00Z")
    private val end = at("2025-06-15T14:30:00Z")
    private val staged = listOf(
        SleepSegment(start, end, SleepStage.IN_BED),
        SleepSegment(start, at("2025-06-15T13:40:00Z"), SleepStage.ASLEEP_CORE),
        SleepSegment(at("2025-06-15T13:40:00Z"), at("2025-06-15T14:20:00Z"), SleepStage.ASLEEP_DEEP, SleepProvenance.ASSERTED),
    )

    private fun coarse(from: Instant, to: Instant) = listOf(SleepSegment(from, to, SleepStage.IN_BED), SleepSegment(from, to, SleepStage.ASLEEP_CORE))

    private suspend fun SleepStore.night(inBedStart: Instant, inBedEnd: Instant) {
        val summary = SleepStaging.Summary(
            inBed = Duration.ofHours(8), awake = Duration.ofHours(1), light = Duration.ofHours(7), deep = Duration.ZERO, rem = Duration.ZERO,
        )
        saveSleepSummary(summary, night = inBedEnd, inBedStart = inBedStart, inBedEnd = inBedEnd, now = now, zone = utc)
    }

    private suspend fun SleepStore.onlyNap(): StoredNapRecord = naps(from = at("2000-01-01T00:00:00Z"), to = at("2100-01-01T00:00:00Z")).single()

    private suspend fun StoreDatabase.snapshot(): List<String> = queryRaw("SELECT * FROM stored_nap ORDER BY id")

    // saveNap

    @Test
    fun aNewlyDetectedNapIsStoredWithItsSegmentsAndNothingWrittenToHealthYet() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.saveNap(start, end, asleepMin = 80, isLongNap = false, segments = staged, now = now, zone = utc)

            assertEquals(
                StoredNapEntity(start = start, end = end, asleepMin = 80, updatedAt = now, napSegmentsData = napSegmentsBytes(staged))
                    .toStoredNapRecord(),
                store.onlyNap(),
            )
            assertEquals(
                listOf("80|0|0|0|0|1"),
                db.queryRaw(
                    "SELECT asleep_min, is_long_nap, health_written, is_manually_edited, is_manually_added, typeof(edited_start) = 'null' FROM stored_nap",
                ),
            )
        }
    }

    /** Upstream: `segments.isEmpty ? nil : segments` (:2880, :2884) — no segments is a coarse nap. */
    @Test
    fun aNapDetectedWithoutSegmentsIsStoredCoarse() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.saveNap(start, end, asleepMin = 80, isLongNap = true, now = now, zone = utc)

            val nap = store.onlyNap()
            assertNull(nap.segments)
            assertTrue(nap.isLongNap)
            assertEquals(listOf("null"), db.queryRaw("SELECT typeof(nap_segments_data) FROM stored_nap"))
        }
    }

    /**
     * A re-detection at the same start updates the end, minutes, long flag, segments and update time,
     * and leaves the Health state, the kept ring staging and any edit columns as stored (:2877-2881).
     */
    @Test
    fun aReDetectedNapUpdatesItsOwnFiguresAndLeavesWhatHealthAndTheEditHold() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.sleepDao().insertNap(
                StoredNapEntity(
                    start = start, end = at("2025-06-15T14:00:00Z"), asleepMin = 50, healthWritten = true, updatedAt = now,
                    napSegmentsData = napSegmentsBytes(staged), editedStart = at("2025-06-15T12:50:00Z"),
                    recordedNapSegmentsData = napSegmentsBytes(coarse(start, end)),
                    healthWrittenStart = start, healthWrittenEnd = at("2025-06-15T14:00:00Z"),
                ),
            )
            val store = SleepStore(db)
            val before = store.onlyNap()

            store.saveNap(start, end, asleepMin = 85, isLongNap = true, now = later, zone = utc)

            assertEquals(before.copy(end = end, asleepMin = 85, isLongNap = true, segments = null, updatedAt = later), store.onlyNap())
        }
    }

    /** Upstream: "A manually edited/added nap is authoritative — auto re-detection must not overwrite it" (:2875-2876). */
    @Test
    fun aNapTheWearerAddedOrEditedIsNeverReplacedByARedetection() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            assertTrue(store.addManualNap(start, end, now = now, zone = utc))
            store.saveNap(at("2025-06-16T13:00:00Z"), at("2025-06-16T14:00:00Z"), 55, false, staged, now = now, zone = utc)
            assertTrue(store.editNap(at("2025-06-16T13:00:00Z"), at("2025-06-16T12:30:00Z"), at("2025-06-16T14:00:00Z"), now = now, zone = utc))
            val before = db.snapshot()

            store.saveNap(start, at("2025-06-15T15:00:00Z"), 110, false, staged, now = later, zone = utc)
            store.saveNap(at("2025-06-16T13:00:00Z"), at("2025-06-16T15:00:00Z"), 115, false, staged, now = later, zone = utc)

            assertEquals(before, db.snapshot())
        }
    }

    /** Upstream's persistence backstop (:2872): a nap overlapping a stored night is that night's sleep counted twice. */
    @Test
    fun aDetectedNapInsideAStoredNightIsNotStored() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.night(at("2025-06-15T22:00:00Z"), at("2025-06-16T06:00:00Z"))

            store.saveNap(at("2025-06-16T05:30:00Z"), at("2025-06-16T07:00:00Z"), 80, false, staged, now = now, zone = utc)

            assertEquals(emptyList(), db.snapshot())
        }
    }

    /**
     * The edited window counts only for a night the wearer edited (:2992): edit columns on an unedited
     * night are not looked at.
     */
    @Test
    fun anUneditedNightsEditColumnsDoNotKeepANapOut() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.night(at("2025-06-15T22:00:00Z"), at("2025-06-16T06:00:00Z"))
            db.execRaw(
                "UPDATE stored_sleep_summary SET edited_in_bed_start = ${at("2025-06-16T06:00:00Z").toEpochMilli()}, " +
                    "edited_in_bed_end = ${at("2025-06-16T09:00:00Z").toEpochMilli()}",
            )

            store.saveNap(at("2025-06-16T07:00:00Z"), at("2025-06-16T08:00:00Z"), 60, false, now = now, zone = utc)

            assertEquals(at("2025-06-16T07:00:00Z"), store.onlyNap().start)
        }
    }

    /** Upstream reads a failing lookup as "no nap" (`try?`, :2874) and inserts. Here it throws, writing nothing. */
    @Test
    fun aFailingLookupFailsTheNapSave() = runBlocking<Unit> {
        withInMemoryStore { db ->
            SleepStore(db).saveNap(start, end, 80, false, now = now, zone = utc)
            val before = db.snapshot()
            val real = db.sleepDao()
            val failingNap = object : SleepDao by real {
                override suspend fun napAt(start: Instant) = error("injected failure")
            }
            val failingNights = object : SleepDao by real {
                override suspend fun summariesKeyedBetween(lo: Instant, hi: Instant) = error("injected failure")
            }

            assertFailsWith<IllegalStateException> { SleepStore(db, failingNap).saveNap(start, at("2025-06-15T15:00:00Z"), 99, true, now = later, zone = utc) }
            assertFailsWith<IllegalStateException> { SleepStore(db, failingNights).saveNap(start, at("2025-06-15T15:00:00Z"), 99, true, now = later, zone = utc) }

            assertEquals(before, db.snapshot())
        }
    }

    // addManualNap

    @Test
    fun aNapTheWearerAddsIsStoredAsAllAsleepWithCoarseSegmentsAndMarkedAdded() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)

            assertTrue(store.addManualNap(start, end, now = now, zone = utc))

            assertEquals(
                StoredNapEntity(
                    start = start, end = end, asleepMin = 90, updatedAt = now, isManuallyAdded = true,
                    napSegmentsData = napSegmentsBytes(coarse(start, end)),
                ).toStoredNapRecord(),
                store.onlyNap(),
            )
        }
    }

    @Test
    fun aManualNapIsRefusedWhenEmptyInsideANightOrAlreadyStoredAtItsStart() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.night(at("2025-06-15T22:00:00Z"), at("2025-06-16T06:00:00Z"))
            store.saveNap(start, end, 80, false, staged, now = now, zone = utc)
            val before = db.snapshot()

            assertFalse(store.addManualNap(end, start, now = later, zone = utc), "reversed")
            assertFalse(store.addManualNap(start.plusSeconds(3_600), start.plusSeconds(3_600), now = later, zone = utc), "empty")
            assertFalse(store.addManualNap(at("2025-06-16T05:00:00Z"), at("2025-06-16T07:00:00Z"), now = later, zone = utc), "inside the night")
            assertFalse(store.addManualNap(start, at("2025-06-15T16:00:00Z"), now = later, zone = utc), "a nap is stored at that start")

            assertEquals(before, db.snapshot())
        }
    }

    /** Upstream reads a failing duplicate check as "no duplicate" (`(try? …) != nil`, :2901). Here it throws, writing nothing. */
    @Test
    fun aFailingDuplicateCheckFailsTheManualNap() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val real = db.sleepDao()
            val failing = object : SleepDao by real {
                override suspend fun napAt(start: Instant) = error("injected failure")
            }

            assertFailsWith<IllegalStateException> { SleepStore(db, failing).addManualNap(start, end, now = now, zone = utc) }

            assertEquals(emptyList(), db.snapshot())
        }
    }

    // editNap

    @Test
    fun anEditKeepsTheDetectedNapAsideAndShowsTheNewWindowAllAsleep() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.saveNap(start, end, 80, false, staged, now = now, zone = utc)
            val newStart = at("2025-06-15T12:45:00Z")
            val newEnd = at("2025-06-15T14:00:00Z")

            assertTrue(store.editNap(start, newStart, newEnd, now = later, zone = utc))

            val nap = store.onlyNap()
            assertEquals(
                StoredNapEntity(
                    start = start, end = end, asleepMin = 75, updatedAt = later, isManuallyEdited = true,
                    napSegmentsData = napSegmentsBytes(coarse(newStart, newEnd)), editedStart = newStart, editedEnd = newEnd,
                    recordedNapSegmentsData = napSegmentsBytes(staged),
                ).toStoredNapRecord(),
                nap,
            )
            assertEquals(newStart to newEnd, nap.effectiveStart to nap.effectiveEnd)
            assertEquals(75, nap.durationMin)
        }
    }

    /**
     * The ring's staging is kept aside only from a nap that is still a pure recording (:2947-2949): a
     * second edit never copies the first edit's segments over it, and a coarse nap has none to keep.
     */
    @Test
    fun onlyTheFirstEditOfADetectedNapKeepsItsStagingAside() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.saveNap(start, end, 80, false, staged, now = now, zone = utc)
            store.saveNap(at("2025-06-16T13:00:00Z"), at("2025-06-16T14:00:00Z"), 55, false, now = now, zone = utc)

            assertTrue(store.editNap(start, at("2025-06-15T12:45:00Z"), end, now = now, zone = utc))
            assertTrue(store.editNap(start, at("2025-06-15T13:15:00Z"), end, now = later, zone = utc))
            assertTrue(store.editNap(at("2025-06-16T13:00:00Z"), at("2025-06-16T12:45:00Z"), at("2025-06-16T14:00:00Z"), now = now, zone = utc))
            assertTrue(store.editNap(at("2025-06-16T13:00:00Z"), at("2025-06-16T12:30:00Z"), at("2025-06-16T14:00:00Z"), now = later, zone = utc))

            val (staged15, coarse16) = store.naps(from = at("2025-06-15T00:00:00Z"), to = at("2025-06-17T00:00:00Z"))
            assertEquals(staged, staged15.recordedSegments)
            assertEquals(coarse(at("2025-06-15T13:15:00Z"), end), staged15.segments)
            assertNull(coarse16.recordedSegments)
        }
    }

    /**
     * Upstream re-arms the Health write when the window written there is not inside the new one
     * (:2967-2970): a shrink or a move marks the nap unwritten, a widening or the same window does not,
     * and a nap never written (or written without a known window) is left as it is.
     */
    @Test
    fun anEditThatNoLongerCoversWhatWasWrittenToHealthMarksTheNapUnwritten() = runBlocking<Unit> {
        val written = start to end
        val cases = listOf(
            Triple("same window", written, true),
            Triple("widened", at("2025-06-15T12:30:00Z") to at("2025-06-15T15:00:00Z"), true),
            Triple("start moved later", at("2025-06-15T13:00:00.001Z") to end, false),
            Triple("end moved earlier", start to at("2025-06-15T14:29:59.999Z"), false),
            Triple("moved", at("2025-06-15T15:00:00Z") to at("2025-06-15T16:00:00Z"), false),
        )
        for ((label, window, stillWritten) in cases) {
            withInMemoryStore { db ->
                db.sleepDao().insertNap(
                    StoredNapEntity(
                        start = start, end = end, asleepMin = 80, healthWritten = true,
                        healthWrittenStart = written.first, healthWrittenEnd = written.second,
                    ),
                )
                val store = SleepStore(db)

                assertTrue(store.editNap(start, window.first, window.second, now = later, zone = utc), label)

                val nap = store.onlyNap()
                assertEquals(stillWritten, nap.healthWritten, label)
                assertEquals(written, nap.healthWrittenStart to nap.healthWrittenEnd, "$label: the written window is Health's, never moved here")
            }
        }
        withInMemoryStore { db ->
            db.sleepDao().insertNap(StoredNapEntity(start = start, end = end, healthWritten = true)) // written, window unknown
            db.sleepDao().insertNap(
                StoredNapEntity(start = at("2025-06-16T13:00:00Z"), end = at("2025-06-16T14:00:00Z"), healthWrittenStart = start, healthWrittenEnd = end),
            )
            val store = SleepStore(db)

            assertTrue(store.editNap(start, at("2025-06-15T13:30:00Z"), end, now = later, zone = utc))
            assertTrue(store.editNap(at("2025-06-16T13:00:00Z"), at("2025-06-16T13:30:00Z"), at("2025-06-16T14:00:00Z"), now = later, zone = utc))

            assertEquals(listOf(true, false), store.naps(from = start, to = at("2025-06-17T00:00:00Z")).map { it.healthWritten })
        }
    }

    @Test
    fun anEditIsRefusedWhenEmptyInsideANightOrOfNoStoredNap() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.night(at("2025-06-15T22:00:00Z"), at("2025-06-16T06:00:00Z"))
            store.saveNap(start, end, 80, false, staged, now = now, zone = utc)
            val before = db.snapshot()

            assertFalse(store.editNap(start, end, start, now = later, zone = utc), "reversed")
            assertFalse(store.editNap(start, start, start, now = later, zone = utc), "empty")
            assertFalse(store.editNap(start, at("2025-06-15T21:00:00Z"), at("2025-06-15T22:30:00Z"), now = later, zone = utc), "into the night")
            assertFalse(store.editNap(at("2025-06-15T13:00:01Z"), start, end, now = later, zone = utc), "no nap at that start")

            assertEquals(before, db.snapshot())
        }
    }

    /** Upstream reads a failing lookup as "no nap" and returns false (`try?`, :2942), and a failed save as false (:2972). Here both throw. */
    @Test
    fun aFailingLookupOrWriteFailsTheEditAndChangesNothing() = runBlocking<Unit> {
        withInMemoryStore { db ->
            SleepStore(db).saveNap(start, end, 80, false, staged, now = now, zone = utc)
            val before = db.snapshot()
            val real = db.sleepDao()
            val failingLookup = object : SleepDao by real {
                override suspend fun napAt(start: Instant) = error("injected failure")
            }
            val failingWrite = object : SleepDao by real {
                override suspend fun updateNap(row: StoredNapEntity) = error("injected failure")
            }

            assertFailsWith<IllegalStateException> { SleepStore(db, failingLookup).editNap(start, start, end.plusSeconds(60), now = later, zone = utc) }
            assertFailsWith<IllegalStateException> { SleepStore(db, failingWrite).editNap(start, start, end.plusSeconds(60), now = later, zone = utc) }

            assertEquals(before, db.snapshot())
            assertNotNull(SleepStore(db).onlyNap().segments)
        }
    }
}
