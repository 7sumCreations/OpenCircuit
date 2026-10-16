package io.github.opencircuit.health

import io.github.opencircuit.ringkit.SleepProvenance
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The queued-edit step of a flush (upstream `reconcileEditedNightSleepLocked`, HKW:1981-2091, over
 * Health Connect ids): the edited picture is written first under the night's id, replacing what was
 * there; only then is an id the night no longer has deleted, by its clientRecordId, and the naps the
 * edit absorbed removed (their record first, the row after); the marks move from what was written;
 * the queued item is cleared only if it is still the one written.
 */
class EditedNightReplaceTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private var now = Instant.parse("2026-06-20T09:00:00Z")
    private val hc = FakeHealthConnect({ now }).apply { grantAll() }
    private val sleep = InMemoryHealthSleep()
    private var port: HealthConnectPort = hc
    private val generations = InMemoryWriteGenerations() // kept across flushes, as the app's store keeps it
    private fun writer() = HealthWriter(
        port, InMemoryHealthSamples(), WriteGeneration(generations), InMemoryHealthFailures(), { now }, { zone }, { RingDevice("Gen 2") },
        sleep = sleep,
    )

    private val night = Instant.parse("2026-06-19T00:00:00Z")
    private val key = "${night.epochSecond}.0"
    private val id = "oc:sleep:$key"
    private fun at(h: Double): Instant = night.plusMillis((h * 3_600_000).toLong())

    private val recorded = listOf(
        SleepSegment(at(-1.0), at(7.0), SleepStage.IN_BED),
        SleepSegment(at(-0.5), at(3.0), SleepStage.ASLEEP_CORE),
        SleepSegment(at(3.0), at(4.0), SleepStage.ASLEEP_DEEP),
        SleepSegment(at(4.0), at(6.5), SleepStage.ASLEEP_REM),
    )

    private fun edit(wake: Double = 8.0) = QueuedNightEdit(
        night = night,
        inBedStart = at(-2.0),
        sleepOnset = at(-2.0),
        sleepWake = at(wake),
        segments = listOf(
            SleepSegment(at(-2.0), at(-1.0), SleepStage.IN_BED, SleepProvenance.ASSERTED),
            SleepSegment(at(-2.0), at(-0.5), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED),
        ) + recorded.drop(1) + listOf(
            SleepSegment(at(-1.0), at(wake), SleepStage.IN_BED),
            SleepSegment(at(6.5), at(wake), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED_OVER_MEASURED),
        ),
    )

    private fun sessions() = hc.records(RecordType.SLEEP_SESSION).map { it as SleepSession }.sortedBy { it.start }
    private fun sleepCalls() = hc.calls.filter { it.type == RecordType.SLEEP_SESSION }

    private suspend fun writtenUnedited() {
        sleep.putNight(StoredSleepNight(key, night, edited = false, timeline = recorded, recordedEnd = at(7.0)))
        writer().flush()
        assertEquals(listOf(id), sessions().map { it.meta.clientRecordId })
        sleep.putNight(StoredSleepNight(key, night, edited = true, timeline = recorded, recordedEnd = at(7.0)))
    }

    @Test
    fun anEditedNightReplacesItsRecordAndIsMarkedFromWhatWasWritten() = runBlocking<Unit> {
        writtenUnedited()
        sleep.queue(edit())

        val result = writer().flush()

        val s = sessions().single()
        assertEquals(id, s.meta.clientRecordId, "replaced under the same id: no second night")
        assertEquals(at(-2.0), s.start)
        assertEquals(at(8.0), s.end)
        assertEquals("Times you entered: 22:00-23:30", s.notes)
        assertEquals(1, result.written[RecordType.SLEEP_SESSION])
        assertTrue(sleep.queued().isEmpty(), "the edit is done")
        assertEquals(listOf(night to edit().segments), sleep.editsMarked)
        assertEquals(at(8.0), sleep.sleepMark)
        assertEquals(listOf(id), sleep.idsOf(night))
    }

    @Test
    fun anEditedNightIsWrittenOncePerFlush() = runBlocking<Unit> {
        sleep.putNight(StoredSleepNight(key, night, edited = true, timeline = recorded, recordedEnd = at(7.0)))
        sleep.queue(edit())

        writer().flush()

        assertEquals(1, sleepCalls().count { id in it.ids }, "the mirror leaves an edited night to its edit")
    }

    @Test
    fun aMovedEditedNightDeletesItsOldIdOnlyAfterTheNewOneWasAccepted() = runBlocking<Unit> {
        val oldNight = night.minus(Duration.ofDays(1))
        val oldId = "oc:sleep:${oldNight.epochSecond}.0"
        sleep.putNight(StoredSleepNight("${oldNight.epochSecond}.0", oldNight, edited = false, timeline = recorded, recordedEnd = at(7.0)))
        writer().flush()
        sleep.rekey(oldNight, night, key)
        sleep.putNight(StoredSleepNight(key, night, edited = true, timeline = recorded, recordedEnd = at(7.0)))
        sleep.queue(edit())

        writer().flush()

        assertEquals(listOf(id), sessions().map { it.meta.clientRecordId })
        val after = sleepCalls().drop(1)
        assertEquals(listOf(FakeHealthConnect.Call.Kind.INSERT to listOf(id), FakeHealthConnect.Call.Kind.DELETE to listOf(oldId)), after.map { it.kind to it.ids })
        assertEquals(listOf(id), sleep.idsOf(night))
    }

    @Test
    fun aFailedDeleteNeverLeavesHealthEmptierAndKeepsTheOldIdNamed() = runBlocking<Unit> {
        val oldNight = night.minus(Duration.ofDays(1))
        val oldId = "oc:sleep:${oldNight.epochSecond}.0"
        sleep.putNight(StoredSleepNight("${oldNight.epochSecond}.0", oldNight, edited = false, timeline = recorded, recordedEnd = at(7.0)))
        writer().flush()
        sleep.rekey(oldNight, night, key)
        sleep.putNight(StoredSleepNight(key, night, edited = true, timeline = recorded, recordedEnd = at(7.0)))
        sleep.queue(edit())
        hc.failNext(FakeHealthConnect.Call.Kind.DELETE, RecordType.SLEEP_SESSION, HealthCallFailedException("disk"))

        writer().flush()

        assertEquals(listOf(id, oldId).sorted(), sessions().map { it.meta.clientRecordId }.sorted())
        assertEquals(listOf(id, oldId), sleep.idsOf(night))
        assertTrue(sleep.queued().isEmpty(), "the edit itself reached Health Connect")
    }

    @Test
    fun aRefusedEditStaysQueuedAndIsAFailure() = runBlocking<Unit> {
        writtenUnedited()
        sleep.queue(edit())
        hc.failNext(RecordType.SLEEP_SESSION, HealthCallFailedException("disk"))

        val result = writer().flush()

        assertEquals(setOf(RecordType.SLEEP_SESSION), result.failures.keys)
        assertEquals(listOf(edit()), sleep.queued())
        assertEquals(recorded.minOf { it.start }, sessions().single().start, "the night as it was, untouched")
        assertTrue(sleep.editsMarked.isEmpty())
    }

    /** A rule the edit breaks for good can never be written: it is dropped and counted, not retried every flush. */
    @Test
    fun anEditThePlatformRefusesIsDroppedAndCountedOnce() = runBlocking<Unit> {
        writtenUnedited()
        sleep.queue(edit())
        hc.failNext(RecordType.SLEEP_SESSION, HealthInvalidRecordException("stages overlap"))

        val first = writer().flush()
        assertEquals(1, first.rejected[RecordType.SLEEP_SESSION])
        assertTrue(sleep.queued().isEmpty(), "dropped, not queued for ever")

        val second = writer().flush()
        assertEquals(null, second.rejected[RecordType.SLEEP_SESSION])
        assertEquals(recorded.minOf { it.start }, sessions().single().start, "the night as it was, untouched")
        assertTrue(sleep.editsMarked.isEmpty())
    }

    /** Only an edit starting ahead of the clock waits: it is written once the clock passes it. */
    @Test
    fun anEditStartingAheadOfTheClockStaysQueued() = runBlocking<Unit> {
        sleep.putNight(StoredSleepNight(key, night, edited = true, timeline = recorded, recordedEnd = at(7.0)))
        sleep.queue(edit())
        now = at(-2.5)

        writer().flush()

        assertEquals(listOf(edit()), sleep.queued())
        now = Instant.parse("2026-06-20T09:00:00Z")
        writer().flush()
        assertTrue(sleep.queued().isEmpty())
        assertEquals(at(-2.0), sessions().single().start)
    }

    @Test
    fun withoutTheSleepGrantTheEditWaitsQueued() = runBlocking<Unit> {
        sleep.putNight(StoredSleepNight(key, night, edited = true, timeline = recorded, recordedEnd = at(7.0)))
        sleep.queue(edit())
        hc.revoke(HealthPermission.SLEEP)

        val result = writer().flush()

        assertEquals(setOf(RecordType.SLEEP_SESSION), result.notGranted)
        assertEquals(listOf(edit()), sleep.queued(), "kept until it can be written (upstream dropped it when sleep was denied)")
    }

    @Test
    fun aNewerEditOfTheSameNightQueuedWhileTheWriteRanIsKeptForTheNextFlush() = runBlocking<Unit> {
        sleep.putNight(StoredSleepNight(key, night, edited = true, timeline = recorded, recordedEnd = at(7.0)))
        sleep.queue(edit())
        port = object : HealthConnectPort by hc {
            override suspend fun insert(type: RecordType, records: List<HealthRecord>): List<String> {
                val ids = hc.insert(type, records)
                if (type == RecordType.SLEEP_SESSION) sleep.queue(edit(wake = 9.0)) // the wearer saves again meanwhile
                return ids
            }
        }

        writer().flush()

        assertEquals(listOf(edit(wake = 9.0)), sleep.queued(), "the lost-update guard: the newer edit is not cleared")
        port = hc
        writer().flush()
        assertEquals(at(9.0), sessions().single().end)
        assertTrue(sleep.queued().isEmpty())
    }

    @Test
    fun anUnreadableQueueIsAFailureAndTheRestOfTheFlushStillRuns() = runBlocking<Unit> {
        sleep.queueUnreadable = true
        val earlier = night.minus(Duration.ofDays(3))
        sleep.putNight(
            StoredSleepNight(
                "${earlier.epochSecond}.0", earlier, edited = false,
                timeline = recorded.map { it.copy(start = it.start.minus(Duration.ofDays(3)), end = it.end.minus(Duration.ofDays(3))) },
                recordedEnd = at(7.0).minus(Duration.ofDays(3)),
            ),
        )
        sleep.putNap(StoredNapToWrite(at(13.0), at(13.0), at(14.0), null, manuallyAdded = false, healthWritten = false))

        val result = writer().flush()

        assertEquals("UnreadableEditQueueException", result.failures[RecordType.SLEEP_SESSION])
        assertEquals(2, sessions().size, "the settled night and the nap are still written")
    }

    @Test
    fun anEditOfANightNoLongerStoredIsDroppedWithNothingWritten() = runBlocking<Unit> {
        sleep.queue(edit())

        writer().flush()

        assertTrue(sessions().isEmpty())
        assertTrue(sleep.queued().isEmpty())
    }

    @Test
    fun theNapsAnEditAbsorbedAreRemovedRecordFirstAndTheWearersOwnNapStays() = runBlocking<Unit> {
        sleep.putNight(StoredSleepNight(key, night, edited = true, timeline = recorded, recordedEnd = at(7.0)))
        sleep.putNap(StoredNapToWrite(at(7.25), at(7.25), at(7.75), null, manuallyAdded = false, healthWritten = false))
        writer().flush() // the nap reaches Health Connect
        val napId = "oc:nap:${at(7.25).toEpochMilli()}"
        assertTrue(hc.records(RecordType.SLEEP_SESSION).any { it.meta.clientRecordId == napId })
        val unwritten = StoredNapToWrite(at(-1.8), at(-1.8), at(-1.6), null, manuallyAdded = false, healthWritten = false)
        val manual = StoredNapToWrite(at(7.8), at(7.8), at(7.95), null, manuallyAdded = true, healthWritten = true)
        sleep.putNap(unwritten)
        sleep.putNap(manual)
        sleep.queue(edit())

        writer().flush()

        assertEquals(listOf(manual), sleep.naps(), "the absorbed naps are gone, hers is kept")
        assertTrue(hc.records(RecordType.SLEEP_SESSION).none { it.meta.clientRecordId == napId }, "its record went with it")
        val calls = sleepCalls().map { it.kind to it.ids }
        val insertNight = calls.indexOfLast { it == FakeHealthConnect.Call.Kind.INSERT to listOf(id) }
        val deleteNap = calls.indexOf(FakeHealthConnect.Call.Kind.DELETE to listOf(napId))
        assertTrue(insertNight in 0 until deleteNap, "the night is accepted before the nap's record is deleted: $calls")
        assertNull(
            calls.firstOrNull { it.first == FakeHealthConnect.Call.Kind.DELETE && "oc:nap:${at(-1.8).toEpochMilli()}" in it.second },
            "a nap never written needs no delete",
        )
    }

    @Test
    fun anAbsorbedNapWhoseRecordCannotBeDeletedKeepsItsRow() = runBlocking<Unit> {
        sleep.putNight(StoredSleepNight(key, night, edited = true, timeline = recorded, recordedEnd = at(7.0)))
        val nap = StoredNapToWrite(at(7.25), at(7.25), at(7.75), null, manuallyAdded = false, healthWritten = false)
        sleep.putNap(nap)
        writer().flush()
        sleep.queue(edit())
        hc.failNext(FakeHealthConnect.Call.Kind.DELETE, RecordType.SLEEP_SESSION, HealthCallFailedException("disk"))

        writer().flush()

        assertEquals(listOf(nap.start), sleep.naps().map { it.start }, "kept: its record is still in Health Connect, so the row still names it")
    }
}
