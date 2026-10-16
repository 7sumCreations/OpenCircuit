package io.github.opencircuit.health

import io.github.opencircuit.ringkit.SleepProvenance
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The settled-night step of a flush: every stored, unedited night whose last segment ended at least
 * the settle margin ago is one sleep session `oc:sleep:<night key>`; an unchanged night is skipped by
 * its signature; an edited night is left to its queued edit; a night that moved to another key is
 * written under its new id and its old id deleted only after the new one was accepted.
 */
class SettledNightWriteTest {
    private val zone = SevenNights.zone
    private var now = Instant.parse("2026-06-20T00:00:00Z")
    private val hc = FakeHealthConnect({ now }).apply { grantAll() }
    private val sleep = InMemoryHealthSleep()
    private val generations = InMemoryWriteGenerations() // kept across flushes, as the app's store keeps it
    private fun writer() = HealthWriter(
        hc, InMemoryHealthSamples(), WriteGeneration(generations), InMemoryHealthFailures(), { now }, { zone }, { RingDevice("Gen 2") },
        sleep = sleep,
    )

    private fun keyOf(segments: List<SleepSegment>): Instant = segments.maxOf { it.end }.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
    private fun text(night: Instant) = "${night.epochSecond}.0"
    private fun stored(segments: List<SleepSegment>, edited: Boolean = false, night: Instant = keyOf(segments)) =
        StoredSleepNight(text(night), night, edited, segments, segments.filter { it.stage == SleepStage.IN_BED }.maxOfOrNull { it.end } ?: segments.maxOf { it.end })

    private fun sessions() = hc.records(RecordType.SLEEP_SESSION).map { it as SleepSession }.sortedBy { it.start }
    private fun sleepCalls() = hc.calls.filter { it.type == RecordType.SLEEP_SESSION }

    @Test
    fun everySettledStoredNightIsWrittenNotOnlyTheNewest() = runBlocking<Unit> {
        SevenNights.nights.forEach { sleep.putNight(stored(it.staged)) }

        val result = writer().flush()

        val keys = SevenNights.nights.map { keyOf(it.staged) }
        assertEquals(keys.map { "oc:sleep:${text(it)}" }, sessions().map { it.meta.clientRecordId })
        assertEquals(7, result.written[RecordType.SLEEP_SESSION])
        for ((night, s) in SevenNights.nights.zip(sessions())) {
            assertEquals(night.staged.minOf { it.start }, s.start, "the whole night, from its first segment")
            assertEquals(night.staged.maxOf { it.end }, s.end)
            assertTrue(s.stages.any { it.stage == SleepStageType.DEEP || it.stage == SleepStageType.REM }, "a staged night keeps its stages")
        }
        keys.forEach { k -> assertEquals(listOf("oc:sleep:${text(k)}"), sleep.idsOf(k), "the id written is tracked under the night") }
        keys.zip(SevenNights.nights).forEach { (k, n) -> assertEquals(SleepSignature.of(n.staged), sleep.mirrorOf(k)?.signature) }
        assertEquals(SevenNights.nights.last().staged.maxOf { it.end }, sleep.sleepMark, "the sleep mark sits at the newest night written")
        assertEquals(1, sleepCalls().size, "seven nights fit one call")
    }

    @Test
    fun aSecondFlushWithNothingChangedWritesNoSleep() = runBlocking<Unit> {
        SevenNights.nights.forEach { sleep.putNight(stored(it.staged)) }
        writer().flush()
        val before = hc.calls.size

        val again = writer().flush()

        assertEquals(before, hc.calls.size)
        assertNull(again.written[RecordType.SLEEP_SESSION])
    }

    @Test
    fun aRestagedNightIsRewrittenWholeUnderItsIdNeverAsItsTail() = runBlocking<Unit> {
        val night = SevenNights.nights[3].staged
        sleep.putNight(stored(night))
        writer().flush()
        // A later, fuller staging of the same night: one more hour of sleep after the old end.
        val end = night.maxOf { it.end }
        val longer = night.map { if (it.stage == SleepStage.IN_BED && it.end == end) it.copy(end = end.plusSeconds(3_600)) else it } +
            SleepSegment(end, end.plusSeconds(3_600), SleepStage.ASLEEP_CORE)
        sleep.putNight(stored(longer, night = keyOf(night)))

        writer().flush()

        val s = sessions().single()
        assertEquals(night.minOf { it.start }, s.start, "never the tail: the record still starts at the night's first segment")
        assertEquals(end.plusSeconds(3_600), s.end)
        assertEquals(2L, s.meta.version)
        assertEquals(2, sleepCalls().count { it.kind == FakeHealthConnect.Call.Kind.INSERT })
    }

    @Test
    fun aNightInsideTheSettleMarginWaitsAndIsWrittenOnceItSettled() = runBlocking<Unit> {
        val night = SevenNights.nights[0].staged
        val end = night.maxOf { it.end }
        sleep.putNight(stored(night))
        now = end.plus(Duration.ofMinutes(10))
        writer().flush()
        assertTrue(sessions().isEmpty(), "a night that may still grow is not written")
        assertNull(sleep.sleepMark)

        now = end.plus(Duration.ofMinutes(21))
        writer().flush()
        assertEquals(1, sessions().size)
    }

    @Test
    fun anEditedNightOrOneWithTheWearersOwnTimeIsLeftToItsQueuedEdit() = runBlocking<Unit> {
        val a = SevenNights.nights[0].staged
        val b = SevenNights.nights[1].staged
        sleep.putNight(stored(a, edited = true))
        val start = b.minOf { it.start }
        sleep.putNight(stored(listOf(SleepSegment(start.minusSeconds(1_800), start, SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED)) + b))

        val result = writer().flush()

        assertTrue(sessions().isEmpty())
        assertNull(result.written[RecordType.SLEEP_SESSION])
        assertTrue(sleep.nightsMarked.isEmpty())
    }

    @Test
    fun withoutTheSleepGrantNothingIsWrittenOrMarkedAndTheTypeIsNotAllowedNotFailed() = runBlocking<Unit> {
        hc.revoke(HealthPermission.SLEEP)
        sleep.putNight(stored(SevenNights.nights[0].staged))

        val result = writer().flush()

        assertEquals(setOf(RecordType.SLEEP_SESSION), result.notGranted)
        assertTrue(result.failures.isEmpty())
        assertNull(sleep.sleepMark)
        assertNull(sleep.mirrorOf(keyOf(SevenNights.nights[0].staged)))
    }

    @Test
    fun aRefusedWriteMarksNothingAndIsAFailure() = runBlocking<Unit> {
        hc.failNext(RecordType.SLEEP_SESSION, HealthCallFailedException("disk"))
        val night = SevenNights.nights[0].staged
        sleep.putNight(stored(night))

        val result = writer().flush()

        assertEquals(setOf(RecordType.SLEEP_SESSION), result.failures.keys)
        assertNull(sleep.mirrorOf(keyOf(night)))
        assertEquals(emptyList(), sleep.idsOf(keyOf(night)))
        assertNull(sleep.sleepMark)
    }

    @Test
    fun aNightThatMovedToAnotherKeyIsWrittenUnderItsNewIdBeforeItsOldIdIsDeleted() = runBlocking<Unit> {
        val night = SevenNights.nights[2].staged
        val oldKey = keyOf(night).minus(Duration.ofDays(1)) // keyed by the day it started, as before the wake-day key
        sleep.putNight(stored(night, night = oldKey))
        writer().flush()
        assertEquals(listOf("oc:sleep:${text(oldKey)}"), sessions().map { it.meta.clientRecordId })

        sleep.rekey(oldKey, keyOf(night), text(keyOf(night)))
        writer().flush()

        val newId = "oc:sleep:${text(keyOf(night))}"
        val oldId = "oc:sleep:${text(oldKey)}"
        assertEquals(listOf(newId), sessions().map { it.meta.clientRecordId }, "one night in Health Connect, under its new id")
        val calls = sleepCalls().drop(1)
        assertEquals(FakeHealthConnect.Call.Kind.INSERT, calls[0].kind)
        assertEquals(listOf(newId), calls[0].ids)
        assertEquals(FakeHealthConnect.Call.Kind.DELETE, calls[1].kind, "the old id goes only after the new one was accepted")
        assertEquals(listOf(oldId), calls[1].ids, "by its clientRecordId, nothing else")
        assertEquals(listOf(newId), sleep.idsOf(keyOf(night)))
    }

    @Test
    fun aFailedDeleteKeepsTheOldIdTrackedAndHealthNeverEmptierAndTheNextFlushFinishesIt() = runBlocking<Unit> {
        val night = SevenNights.nights[2].staged
        val oldKey = keyOf(night).minus(Duration.ofDays(1))
        sleep.putNight(stored(night, night = oldKey))
        writer().flush()
        sleep.rekey(oldKey, keyOf(night), text(keyOf(night)))
        hc.failNext(FakeHealthConnect.Call.Kind.DELETE, RecordType.SLEEP_SESSION, HealthCallFailedException("disk"))
        val newId = "oc:sleep:${text(keyOf(night))}"
        val oldId = "oc:sleep:${text(oldKey)}"

        val result = writer().flush()

        assertEquals(listOf(oldId, newId).sorted(), sessions().map { it.meta.clientRecordId }.sorted(), "never emptier: both stay until the delete succeeds")
        assertEquals(listOf(newId, oldId), sleep.idsOf(keyOf(night)), "the old id is still named for the next delete")
        assertTrue(result.failures.isEmpty(), "a failed clean-up is retried, not a write failure")

        writer().flush()

        assertEquals(listOf(newId), sessions().map { it.meta.clientRecordId })
        assertEquals(listOf(newId), sleep.idsOf(keyOf(night)))
    }

    @Test
    fun anotherAppsSleepIsNeverTouched() = runBlocking<Unit> {
        val night = SevenNights.nights[2].staged
        val other = SleepSession(
            night.minOf { it.start }, ZoneOffset.UTC, night.maxOf { it.end }, ZoneOffset.UTC, null, null, emptyList(),
            RecordMeta("theirs", 1, RecordingMethod.AUTOMATICALLY_RECORDED, null),
        )
        hc.seedOtherApp(other)
        val oldKey = keyOf(night).minus(Duration.ofDays(1))
        sleep.putNight(stored(night, night = oldKey))
        writer().flush()
        sleep.rekey(oldKey, keyOf(night), text(keyOf(night)))

        writer().flush()

        assertEquals(listOf<HealthRecord>(other), hc.otherAppRecords(RecordType.SLEEP_SESSION))
    }
}
