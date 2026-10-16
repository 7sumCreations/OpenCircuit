package io.github.opencircuit.health

import io.github.opencircuit.ringkit.SleepSegment
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.github.opencircuit.ringkit.SleepStage as RingStage

/**
 * The nap step of a flush (upstream `flushNaps`, HKW:633-667, over Health Connect ids): each nap
 * not yet written is its own session `oc:nap:<stored start>` titled "Nap", its staging when it has
 * one, else asleep over its shown window; one the wearer added is a manual entry; an edited nap is
 * rewritten under the same id (the stored start an edit keeps), so it replaces, never doubles.
 */
class NapReplaceTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val now = Instant.parse("2026-06-20T18:00:00Z")
    private val hc = FakeHealthConnect({ now }).apply { grantAll() }
    private val sleep = InMemoryHealthSleep()
    private val generations = InMemoryWriteGenerations() // kept across flushes, as the app's store keeps it
    private fun writer() = HealthWriter(
        hc, InMemoryHealthSamples(), WriteGeneration(generations), InMemoryHealthFailures(), { now }, { zone }, { RingDevice("Gen 2") },
        sleep = sleep,
    )

    private fun at(text: String) = Instant.parse("2026-06-20T${text}:00Z")
    private val a = at("13:00")
    private val aId = "oc:nap:${a.toEpochMilli()}"
    private fun sessions() = hc.records(RecordType.SLEEP_SESSION).map { it as SleepSession }.sortedBy { it.start }

    @Test
    fun eachPendingNapIsOneSessionTitledNapAndMarkedAndASecondFlushWritesNothing() = runBlocking<Unit> {
        val staged = listOf(
            SleepSegment(a, at("14:00"), RingStage.IN_BED),
            SleepSegment(at("13:10"), at("13:40"), RingStage.ASLEEP_CORE),
            SleepSegment(at("13:40"), at("13:55"), RingStage.ASLEEP_DEEP),
        )
        sleep.putNap(StoredNapToWrite(a, a, at("14:00"), staged, manuallyAdded = false, healthWritten = false))
        sleep.putNap(StoredNapToWrite(at("16:00"), at("16:00"), at("16:30"), null, manuallyAdded = false, healthWritten = false))

        val result = writer().flush()

        val (first, coarse) = sessions()
        assertEquals(aId, first.meta.clientRecordId)
        assertEquals("Nap", first.title)
        assertEquals(
            listOf(
                SleepStage(a, at("13:10"), SleepStageType.AWAKE_IN_BED),
                SleepStage(at("13:10"), at("13:40"), SleepStageType.LIGHT),
                SleepStage(at("13:40"), at("13:55"), SleepStageType.DEEP),
                SleepStage(at("13:55"), at("14:00"), SleepStageType.AWAKE_IN_BED),
            ),
            first.stages,
        )
        assertEquals(listOf(SleepStage(at("16:00"), at("16:30"), SleepStageType.SLEEPING)), coarse.stages, "a coarse nap is plain sleep over its window")
        assertEquals(RecordingMethod.AUTOMATICALLY_RECORDED, coarse.meta.method)
        assertEquals(2, result.written[RecordType.SLEEP_SESSION])
        assertTrue(sleep.naps().all { it.healthWritten })

        val calls = hc.calls.size
        writer().flush()
        assertEquals(calls, hc.calls.size)
    }

    @Test
    fun aNapTheWearerAddedIsAManualEntryWithNoDevice() = runBlocking<Unit> {
        val whole = listOf(SleepSegment(a, at("13:30"), RingStage.IN_BED), SleepSegment(a, at("13:30"), RingStage.ASLEEP_CORE))
        sleep.putNap(StoredNapToWrite(a, a, at("13:30"), whole, manuallyAdded = true, healthWritten = false))

        writer().flush()

        val s = sessions().single()
        assertEquals(RecordingMethod.MANUAL_ENTRY, s.meta.method)
        assertNull(s.meta.device)
        assertNull(s.notes)
    }

    @Test
    fun anEditedNapIsRewrittenUnderItsIdAndReplacesTheOldRecordWithNoDelete() = runBlocking<Unit> {
        sleep.putNap(StoredNapToWrite(a, a, at("14:00"), null, manuallyAdded = false, healthWritten = false))
        writer().flush()
        // The wearer shrinks it to 13:20-13:50: the store re-arms its write and keeps its start.
        sleep.putNap(StoredNapToWrite(a, at("13:20"), at("13:50"), null, manuallyAdded = false, healthWritten = false), manuallyEdited = true)

        writer().flush()

        val s = sessions().single()
        assertEquals(aId, s.meta.clientRecordId)
        assertEquals(at("13:20"), s.start)
        assertEquals(at("13:50"), s.end)
        assertEquals(2L, s.meta.version)
        assertTrue(hc.calls.none { it.kind == FakeHealthConnect.Call.Kind.DELETE }, "the same id replaces it; nothing to delete")
    }

    @Test
    fun aRefusedNapWriteLeavesItPendingAndIsAFailure() = runBlocking<Unit> {
        sleep.putNap(StoredNapToWrite(a, a, at("14:00"), null, manuallyAdded = false, healthWritten = false))
        hc.failNext(RecordType.SLEEP_SESSION, HealthCallFailedException("disk"))

        val result = writer().flush()

        assertEquals(setOf(RecordType.SLEEP_SESSION), result.failures.keys)
        assertTrue(sleep.naps().none { it.healthWritten })
    }

    @Test
    fun withoutTheSleepGrantNapsWaitAndTheTypeIsNotAllowed() = runBlocking<Unit> {
        hc.revoke(HealthPermission.SLEEP)
        sleep.putNap(StoredNapToWrite(a, a, at("14:00"), null, manuallyAdded = false, healthWritten = false))

        val result = writer().flush()

        assertEquals(setOf(RecordType.SLEEP_SESSION), result.notGranted)
        assertTrue(sleep.naps().none { it.healthWritten })
    }
}
