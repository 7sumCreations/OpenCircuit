package io.github.opencircuit.store

import io.github.opencircuit.ringkit.SleepEdit
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import io.github.opencircuit.ringkit.SleepStaging
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Port of upstream ios/OpenCircuitTests/SleepHypnogramStoreTests.swift (@ b1c2fdd): the stored
 * per-night hypnogram. The invariant they pin: a night's segments and its stage minutes always come
 * from the same capture, so the timeline is written only by the branch that writes the minutes and
 * inherits that branch's merge protection — and a wearer's edit states its own timeline or leaves
 * the stored one alone.
 *
 * Upstream ran in the simulator's zone; here the zone is UTC, in which no fixture window crosses
 * its key day. Each save names a fixed `now` where upstream stamps the wall clock.
 */
class SleepHypnogramStoreTest {

    private val ref = Instant.ofEpochSecond(1_750_000_000)
    private val zone = ZoneOffset.UTC
    private val now = Instant.parse("2025-06-16T09:00:00Z")

    private fun at(hours: Double): Instant = ref.plusMillis((hours * 3_600_000).toLong())

    private val night get() = ref

    /** 8 h in bed, 7 h asleep. */
    private val fullNight = listOf(
        SleepSegment(at(0.0), at(8.0), SleepStage.IN_BED),
        SleepSegment(at(0.0), at(0.5), SleepStage.AWAKE),
        SleepSegment(at(0.5), at(3.0), SleepStage.ASLEEP_CORE),
        SleepSegment(at(3.0), at(4.5), SleepStage.ASLEEP_DEEP),
        SleepSegment(at(4.5), at(6.0), SleepStage.ASLEEP_REM),
        SleepSegment(at(6.0), at(7.5), SleepStage.ASLEEP_CORE),
        SleepSegment(at(7.5), at(8.0), SleepStage.AWAKE),
    )

    /** A later 2 h fragment: the ring hands a night off in slices, so this arrives after [fullNight]. */
    private val shortSlice = listOf(
        SleepSegment(at(5.0), at(7.0), SleepStage.IN_BED),
        SleepSegment(at(5.0), at(7.0), SleepStage.ASLEEP_CORE),
    )

    /** 9 h in bed, 8 h asleep: a genuinely fuller capture that should supersede [fullNight]. */
    private val fullerNight = listOf(
        SleepSegment(at(-0.5), at(8.5), SleepStage.IN_BED),
        SleepSegment(at(-0.5), at(0.0), SleepStage.AWAKE),
        SleepSegment(at(0.0), at(4.0), SleepStage.ASLEEP_CORE),
        SleepSegment(at(4.0), at(6.0), SleepStage.ASLEEP_DEEP),
        SleepSegment(at(6.0), at(8.0), SleepStage.ASLEEP_REM),
        SleepSegment(at(8.0), at(8.5), SleepStage.AWAKE),
    )

    /** Saves [segments] as the drain does: summary, window and hypnogram from one segment list. */
    private suspend fun save(segments: List<SleepSegment>, store: SleepStore) {
        val window = SleepStaging.sleepWindow(segments)
        store.saveSleepSummary(
            SleepStaging.summary(segments), night = night,
            inBedStart = segments.minOf { it.start }, inBedEnd = segments.maxOf { it.end },
            sleepOnset = window?.onset ?: SleepEdit.DISTANT_PAST, sleepWake = window?.wake ?: SleepEdit.DISTANT_PAST,
            extras = SleepNightExtras(hypnogram = segments), now = now, zone = zone,
        )
    }

    /** The core invariant: re-rolling the stored segments reproduces the stored minutes exactly. */
    private suspend fun assertTimelineAgreesWithMinutes(store: SleepStore, message: String) {
        val row = assertNotNull(store.sleepSummary(night, zone), message)
        val stored = store.hypnogram(night, zone)
        assertFalse(stored.isEmpty(), message)
        val m = SleepStaging.summary(stored).minutes
        assertEquals(m.asleep, row.asleepMin.toLong(), message)
        assertEquals(m.deep, row.deepMin.toLong(), message)
        assertEquals(m.light, row.lightMin.toLong(), message)
        assertEquals(m.rem, row.remMin.toLong(), message)
        assertEquals(m.awake, row.awakeMin.toLong(), message)
    }

    /** Upstream `testSavedNightRoundTripsItsHypnogram` (`:99`). */
    @Test
    fun savedNightRoundTripsItsHypnogram() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            save(fullNight, store)
            assertEquals(fullNight, store.hypnogram(night, zone))
            assertTimelineAgreesWithMinutes(store, "a freshly saved night")
        }
    }

    /** Upstream `testNightSavedWithNoSegmentsStoresNotRecordedAndKeepsTheRestOfTheRow` (`:114`). */
    @Test
    fun nightSavedWithNoSegmentsStoresNotRecordedAndKeepsTheRestOfTheRow() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.saveSleepSummary(SleepStaging.summary(fullNight), night = night, inBedStart = at(0.0), inBedEnd = at(8.0), now = now, zone = zone)
            assertEquals(emptyList(), store.hypnogram(night, zone))
            assertTrue(assertNotNull(store.sleepSummary(night, zone)).asleepMin > 0, "the rest of the row must still be readable")
        }
    }

    /** Upstream `testHypnogramOfUnknownNightIsEmpty` (`:123`). */
    @Test
    fun hypnogramOfUnknownNightIsEmpty() = runBlocking<Unit> {
        withInMemoryStore { db ->
            assertEquals(emptyList(), SleepStore(db).hypnogram(night, zone))
        }
    }

    /**
     * Upstream `testUnreadableBlobReadsBackEmptyRatherThanFailingTheNight` (`:128`). Upstream mutates
     * the fetched model object; a stored night here is a value, so the unreadable blob is written to
     * the row directly and then read back.
     */
    @Test
    fun unreadableBlobReadsBackEmptyRatherThanFailingTheNight() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            save(fullNight, store)
            db.execRaw("UPDATE stored_sleep_summary SET hypnogram_data = CAST('not json' AS BLOB)")
            assertEquals(emptyList(), store.hypnogram(night, zone))
            assertEquals(420, assertNotNull(store.sleepSummary(night, zone)).asleepMin, "the rest of the night must still be intact")
        }
    }

    /** Upstream `testShorterLaterSliceDoesNotReplaceAFullerNightsHypnogram` (`:139`). */
    @Test
    fun shorterLaterSliceDoesNotReplaceAFullerNightsHypnogram() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            save(fullNight, store)
            save(shortSlice, store)
            assertEquals(fullNight, store.hypnogram(night, zone), "merge protection must keep the fuller night's timeline, not the 2 h fragment")
            assertTimelineAgreesWithMinutes(store, "after a rejected shorter slice")
        }
    }

    /** Upstream `testGenuinelyFullerCaptureReplacesBothTimelineAndMinutesTogether` (`:149`). */
    @Test
    fun genuinelyFullerCaptureReplacesBothTimelineAndMinutesTogether() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            save(fullNight, store)
            save(fullerNight, store)
            assertEquals(fullerNight, store.hypnogram(night, zone))
            assertTimelineAgreesWithMinutes(store, "after a fuller capture superseded the stored night")
        }
    }

    /** Upstream `testTimelineAndMinutesCannotDivergeAcrossSaveMergeSave` (`:160`). */
    @Test
    fun timelineAndMinutesCannotDivergeAcrossSaveMergeSave() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            save(fullNight, store)
            assertTimelineAgreesWithMinutes(store, "after the first capture")
            save(shortSlice, store) // rejected by merge protection
            assertTimelineAgreesWithMinutes(store, "after a rejected fragment")
            save(fullerNight, store) // accepted
            assertTimelineAgreesWithMinutes(store, "after an accepted fuller capture")
            save(shortSlice, store) // rejected again
            assertTimelineAgreesWithMinutes(store, "after a second rejected fragment")
            assertEquals(fullerNight, store.hypnogram(night, zone))
        }
    }

    // An omitted hypnogram argument must never erase a stored one: an edit with no timeline to
    // state leaves the stored timeline alone, and only an explicit empty list clears it.

    /** Upstream `testAnEditWithNoHypnogramArgumentLeavesTheStoredTimelineIntact` (`:205`). */
    @Test
    fun anEditWithNoHypnogramArgumentLeavesTheStoredTimelineIntact() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            save(fullNight, store)

            // A real edit (not the unchanged-times early return, which never reaches the write).
            val times = SleepEdit.Times(inBedStart = at(-1.0), sleepOnset = at(-0.5), sleepWake = at(8.0))
            val edited = SleepEdit.recompute(baseSegments = fullNight, times = times)
            assertFalse(edited.isEmpty())
            assertTrue(store.applySleepEdit(night, times, SleepStaging.summary(edited), now = now, zone = zone))

            val row = assertNotNull(store.sleepSummary(night, zone))
            assertTrue(row.isManuallyEdited, "the edit really was applied — not an early return")
            assertEquals(fullNight, store.hypnogram(night, zone), "an omitted timeline must leave the recorded one alone, never wipe it")
        }
    }

    /** Upstream `testAnEditThatExplicitlyPassesAnEmptyHypnogramStillClearsIt` (`:223`). */
    @Test
    fun anEditThatExplicitlyPassesAnEmptyHypnogramStillClearsIt() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            save(fullNight, store)

            val times = SleepEdit.Times(inBedStart = at(-1.0), sleepOnset = at(-0.5), sleepWake = at(8.0))
            assertTrue(
                store.applySleepEdit(
                    night, times, SleepStaging.summary(SleepEdit.recompute(baseSegments = fullNight, times = times)),
                    hypnogram = emptyList(), now = now, zone = zone,
                ),
            )
            assertEquals(emptyList(), store.hypnogram(night, zone), "an EXPLICIT empty list is a stated intent and must still clear the column")
        }
    }

    /** Upstream `testTheTwoEdgeOverloadWithNoHypnogramArgumentAlsoLeavesTheTimelineIntact` (`:237`). */
    @Test
    fun theTwoEdgeOverloadWithNoHypnogramArgumentAlsoLeavesTheTimelineIntact() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            save(fullNight, store)

            val window = SleepEdit.Window(inBedStart = at(-1.0), inBedEnd = at(8.0))
            val times = SleepEdit.Times(inBedStart = at(-1.0), sleepOnset = at(-0.5), sleepWake = at(8.0))
            val edited = SleepEdit.recompute(baseSegments = fullNight, times = times)
            assertTrue(
                store.applySleepEdit(
                    night, editedWindow = window, summary = SleepStaging.summary(edited),
                    sleepOnset = at(-0.5), sleepWake = at(8.0), now = now, zone = zone,
                ),
            )
            assertEquals(fullNight, store.hypnogram(night, zone))
        }
    }

    /**
     * Upstream `testUnchangedEditIsANoOpAndLeavesTheRecordedTimelineIntact` (`:250`). Upstream asserts
     * on the model object it fetched; the night is read again here.
     */
    @Test
    fun unchangedEditIsANoOpAndLeavesTheRecordedTimelineIntact() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            save(fullNight, store)
            val row = assertNotNull(store.sleepSummary(night, zone))
            // Submitting the recorded times unchanged must not manufacture an edit — and must not
            // clear the recorded timeline on its way through.
            val unchanged = SleepEdit.Times(row.currentInBedStart, row.currentOnset, row.currentWake)

            assertTrue(store.applySleepEdit(night, unchanged, row.asSummary, now = now, zone = zone))

            assertFalse(assertNotNull(store.sleepSummary(night, zone)).isManuallyEdited)
            assertEquals(fullNight, store.hypnogram(night, zone))
        }
    }
}
