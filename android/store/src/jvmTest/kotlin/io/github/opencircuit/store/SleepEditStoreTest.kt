package io.github.opencircuit.store

import io.github.opencircuit.ringkit.SleepEdit
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import io.github.opencircuit.ringkit.SleepStaging
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Port of upstream ios/OpenCircuitTests/SleepEditStoreTests.swift (@ b1c2fdd), its tests that call
 * only the store's save, edit and read: a wearer's edit of a stored night. The five that also call
 * the Apple Health write bookkeeping come with that bookkeeping.
 *
 * Upstream ran in the simulator's zone; here the zone is UTC. Each call names a fixed `now`. Where
 * upstream asserts on the model object it fetched, the night is read again.
 */
class SleepEditStoreTest {

    private val ref = Instant.ofEpochSecond(1_750_000_000) // 40 s into its minute
    private val zone = ZoneOffset.UTC
    private val now = Instant.parse("2025-06-16T09:00:00Z")

    private fun at(hours: Double): Instant = ref.plusMillis((hours * 3_600_000).toLong())

    private fun summary(inBed: Duration, awake: Duration, light: Duration, deep: Duration, rem: Duration) =
        SleepStaging.Summary(inBed = inBed, awake = awake, light = light, deep = deep, rem = rem)

    private suspend fun seed(store: SleepStore) {
        store.saveSleepSummary(
            summary(Duration.ofHours(8), Duration.ofMinutes(30), Duration.ofHours(5), Duration.ofMinutes(90), Duration.ofMinutes(60)),
            night = at(0.0), inBedStart = at(0.0), inBedEnd = at(8.0), sleepOnset = at(0.5), sleepWake = at(7.75), now = now, zone = zone,
        )
    }

    /** Upstream `testFirstEditPersistsFlagOverlayAndImmutableRecordedAnchors` (`:38`). */
    @Test
    fun firstEditPersistsFlagOverlayAndImmutableRecordedAnchors() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            seed(store)
            val edited = SleepEdit.Window(inBedStart = at(-1.0), inBedEnd = at(9.0))
            val segments = SleepEdit.recompute(
                baseSegments = listOf(
                    SleepSegment(at(0.0), at(8.0), SleepStage.IN_BED),
                    SleepSegment(at(0.0), at(8.0), SleepStage.ASLEEP_CORE),
                ),
                window = edited,
            )

            assertTrue(
                store.applySleepEdit(
                    at(0.0), editedWindow = edited, summary = SleepStaging.summary(segments),
                    sleepOnset = at(-1.0), sleepWake = at(9.0), now = now, zone = zone,
                ),
            )
            val row = assertNotNull(store.sleepSummary(at(0.0), zone))
            assertTrue(row.isManuallyEdited)
            assertEquals(at(-1.0), row.editedInBedStart)
            assertEquals(at(9.0), row.editedInBedEnd)
            assertEquals(at(0.0), row.inBedStart, "recorded anchors remain immutable")
            assertEquals(at(8.0), row.inBedEnd)
            assertEquals(at(0.5), row.sleepOnset)
            assertEquals(at(7.75), row.sleepWake)
            assertEquals(10 * 60, row.asleepMin)
            assertEquals(1.0, row.efficiency, 0.0001)
            assertTrue(row.sleepScore > 0)
        }
    }

    /** Upstream `testResyncCannotOverwriteManualEditAndReeditKeepsOriginalBounds` (`:65`). */
    @Test
    fun resyncCannotOverwriteManualEditAndReeditKeepsOriginalBounds() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            seed(store)
            val first = SleepEdit.Window(inBedStart = at(-1.0), inBedEnd = at(9.0))
            val firstSummary = summary(Duration.ofHours(10), Duration.ZERO, Duration.ofHours(10), Duration.ZERO, Duration.ZERO)
            assertTrue(
                store.applySleepEdit(
                    at(0.0), editedWindow = first, summary = firstSummary, sleepOnset = at(-1.0), sleepWake = at(9.0), now = now, zone = zone,
                ),
            )

            // A later sync is ignored once the explicit persisted flag is set.
            val replacement = summary(Duration.ofHours(2), Duration.ZERO, Duration.ofHours(2), Duration.ZERO, Duration.ZERO)
            store.saveSleepSummary(replacement, night = at(0.0), inBedStart = at(3.0), inBedEnd = at(5.0), now = now, zone = zone)
            assertEquals(at(-1.0), store.sleepSummary(at(0.0), zone)?.editedInBedStart)

            val second = SleepEdit.Window(inBedStart = at(-2.0), inBedEnd = at(10.0))
            val secondSummary = summary(Duration.ofHours(12), Duration.ZERO, Duration.ofHours(12), Duration.ZERO, Duration.ZERO)
            assertTrue(
                store.applySleepEdit(
                    at(0.0), editedWindow = second, summary = secondSummary, sleepOnset = at(-2.0), sleepWake = at(10.0), now = now, zone = zone,
                ),
            )
            val row = assertNotNull(store.sleepSummary(at(0.0), zone))
            assertEquals(at(0.5), row.sleepOnset)
            assertEquals(at(7.75), row.sleepWake)
            assertEquals(at(0.0), row.inBedStart)
            assertEquals(at(8.0), row.inBedEnd)
            assertEquals(at(-2.0), row.editedInBedStart)
            assertEquals(at(10.0), row.editedInBedEnd)
        }
    }

    /**
     * Upstream `testManualFlagIsNotInferredFromUncommittedOverlayDates` (`:96`). Upstream sets the two
     * edited dates on its fetched model object without saving; a stored night here is a value, so the
     * dates are written to the row directly, without the flag, and the night read back.
     */
    @Test
    fun manualFlagIsNotInferredFromUncommittedOverlayDates() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            seed(store)
            db.execRaw(
                "UPDATE stored_sleep_summary SET edited_in_bed_start = ${at(-1.0).toEpochMilli()}, " +
                    "edited_in_bed_end = ${at(9.0).toEpochMilli()}",
            )

            val row = assertNotNull(store.sleepSummary(at(0.0), zone))
            assertFalse(row.isManuallyEdited)
            assertEquals(at(0.0), row.currentInBedStart, "dates without the flag are not an edit")
            assertEquals(at(8.0), row.currentInBedEnd)
        }
    }

    /** Upstream `testThreeTimeEditPersistsBedtimeSeparatelyFromSleepWindow` (`:105`). */
    @Test
    fun threeTimeEditPersistsBedtimeSeparatelyFromSleepWindow() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            seed(store)
            val times = SleepEdit.Times(inBedStart = at(-0.5), sleepOnset = at(0.5), sleepWake = at(8.0))
            val segments = SleepEdit.recompute(
                baseSegments = listOf(
                    SleepSegment(at(0.0), at(8.0), SleepStage.IN_BED),
                    SleepSegment(at(0.5), at(8.0), SleepStage.ASLEEP_CORE),
                ),
                times = times,
            )

            assertTrue(store.applySleepEdit(at(0.0), times, SleepStaging.summary(segments), now = now, zone = zone))
            val row = assertNotNull(store.sleepSummary(at(0.0), zone))
            assertEquals(at(-0.5), row.currentInBedStart)
            assertEquals(at(0.5), row.currentOnset)
            assertEquals(at(8.0), row.currentWake)
            assertEquals(60, row.awakeMin)
            assertEquals(450, row.asleepMin)
        }
    }

    /**
     * Upstream `testVisuallyUnchangedRecordedWindowDoesNotBecomeManualEdit` (`:124`). The picker shows
     * minutes: `ref` is 40 s into its minute, so 10 s later is still the same displayed minute.
     */
    @Test
    fun visuallyUnchangedRecordedWindowDoesNotBecomeManualEdit() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            seed(store)
            val row = assertNotNull(store.sleepSummary(at(0.0), zone))
            val unchanged = SleepEdit.Window(inBedStart = row.inBedStart.plusSeconds(10), inBedEnd = row.inBedEnd.plusSeconds(10))

            assertTrue(
                store.applySleepEdit(
                    at(0.0), editedWindow = unchanged, summary = row.asSummary,
                    sleepOnset = row.sleepOnset, sleepWake = row.sleepWake, now = now, zone = zone,
                ),
            )
            val after = assertNotNull(store.sleepSummary(at(0.0), zone))
            assertFalse(after.isManuallyEdited)
            assertEquals(SleepEdit.DISTANT_PAST, after.editedInBedStart)
            assertEquals(SleepEdit.DISTANT_PAST, after.editedInBedEnd)
        }
    }
}
