package io.github.opencircuit.health

import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How the settled-night step reads the nights' marks: one batched read of the candidate nights per
 * flush, never a mirror read and an id read for each stored night — which a long-lived install pays
 * on every flush for every night it has ever stored. What the step writes and marks is unchanged,
 * including that an unreadable id list is not the night's own id.
 */
class SettledNightBatchReadTest {
    private val zone = SevenNights.zone
    private var now = Instant.parse("2026-06-20T00:00:00Z")
    private val hc = FakeHealthConnect({ now }).apply { grantAll() }
    private val sleep = InMemoryHealthSleep()
    private val generations = InMemoryWriteGenerations() // kept across flushes, as the app's store keeps it

    private val marks = MarkReads(sleep)
    private fun writer(seam: HealthSleepStore = marks) = HealthWriter(
        hc, InMemoryHealthSamples(), WriteGeneration(generations), InMemoryHealthFailures(), { now }, { zone }, { RingDevice("Gen 2") },
        sleep = seam,
    )

    /** The seam store the writer runs against: it counts how the nights' marks were read. */
    private class MarkReads(private val backing: InMemoryHealthSleep) : HealthSleepStore by backing {
        /** Every batched mark read, as the range the writer asked for. */
        val batches = mutableListOf<Pair<Instant, Instant>>()

        /** Single-night mirror reads. The settled step must make none: the batch answers them all. */
        var singleMirrorReads = 0

        override suspend fun nightMarks(from: Instant, until: Instant): Map<Instant, NightMarks> {
            synchronized(this) { batches += from to until }
            return backing.nightMarks(from, until)
        }

        override suspend fun mirror(night: Instant): NightMirror? {
            synchronized(this) { singleMirrorReads++ }
            return backing.mirror(night)
        }
    }

    /**
     * The same seam with the per-night mark reads taken away: only the batch can answer. A settled
     * step that still asked a night for its own mirror or ids fails here instead of passing on a read
     * the batch was supposed to replace.
     */
    private class BatchOnly(private val backing: InMemoryHealthSleep) : HealthSleepStore by backing {
        /** Every batched mark read, as the range the writer asked for. */
        val batches = mutableListOf<Pair<Instant, Instant>>()

        override suspend fun nightMarks(from: Instant, until: Instant): Map<Instant, NightMarks> {
            synchronized(this) { batches += from to until }
            return backing.nightMarks(from, until)
        }

        override suspend fun mirror(night: Instant): NightMirror? =
            throw AssertionError("the settled write read one night's mirror instead of the batch's")

        override suspend fun recordIds(night: Instant): List<String>? =
            throw AssertionError("the settled write read one night's ids instead of the batch's")
    }

    private fun keyOf(segments: List<SleepSegment>): Instant =
        segments.maxOf { it.end }.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()

    private fun text(night: Instant) = "${night.epochSecond}.0"

    private fun stored(segments: List<SleepSegment>, edited: Boolean = false, night: Instant = keyOf(segments)) = StoredSleepNight(
        text(night), night, edited, segments,
        segments.filter { it.stage == SleepStage.IN_BED }.maxOfOrNull { it.end } ?: segments.maxOf { it.end },
    )

    private fun sleepCalls() = hc.calls.filter { it.type == RecordType.SLEEP_SESSION }

    @Test
    fun everySettledNightOfABacklogReadsItsMarksOnceNotTwiceEach() = runBlocking<Unit> {
        SevenNights.nights.forEach { sleep.putNight(stored(it.staged)) }

        val result = writer().flush()

        val keys = SevenNights.nights.map { keyOf(it.staged) }
        assertEquals(1, marks.batches.size, "seven nights, one batched read")
        assertEquals(0, marks.singleMirrorReads, "no night's mirror is read on its own")
        assertTrue(
            marks.batches.single().first <= keys.min() && marks.batches.single().second > keys.max(),
            "the batch covers every night the step checks",
        )
        assertEquals(7, result.written[RecordType.SLEEP_SESSION], "the same seven nights are written")
        keys.forEach { k -> assertEquals(listOf("oc:sleep:${text(k)}"), sleep.idsOf(k), "and tracked the same way") }
        keys.zip(SevenNights.nights).forEach { (k, n) -> assertEquals(SleepSignature.of(n.staged), sleep.mirrorOf(k)?.signature) }
    }

    @Test
    fun aSecondFlushReadsTheBatchAgainAndStillWritesNothing() = runBlocking<Unit> {
        SevenNights.nights.forEach { sleep.putNight(stored(it.staged)) }
        writer().flush()
        val calls = sleepCalls().size

        val again = writer().flush()

        assertEquals(2, marks.batches.size, "the marks are read again, in one batch")
        assertEquals(0, marks.singleMirrorReads, "never one night at a time")
        assertEquals(calls, sleepCalls().size, "the batched answer still skips every unchanged night")
        assertEquals(null, again.written[RecordType.SLEEP_SESSION])
    }

    /**
     * The batched answer is the one the step decides from: run the same flush against a seam that
     * cannot answer a night on its own, and the nights the batch says are already written are still
     * skipped, with no per-night read attempted.
     */
    @Test
    fun theSettledStepDecidesFromTheBatchAndNeverFromAPerNightRead() = runBlocking<Unit> {
        SevenNights.nights.forEach { sleep.putNight(stored(it.staged)) }
        writer().flush()
        val calls = sleepCalls().size

        val batchOnly = BatchOnly(sleep)
        val again = writer(batchOnly).flush()

        assertEquals(1, batchOnly.batches.size, "the marks are read once, in the batch")
        assertEquals(calls, sleepCalls().size, "the batch's answer still skips every unchanged night")
        assertNull(again.written[RecordType.SLEEP_SESSION])
    }

    @Test
    fun aFlushWithNoSettledNightToCheckReadsNoMarksAtAll() = runBlocking<Unit> {
        SevenNights.nights.forEach { sleep.putNight(stored(it.staged)) }
        now = SevenNights.nights.first().staged.maxOf { it.end } // every night still inside the settle margin

        writer().flush()

        assertEquals(emptyList(), marks.batches, "nothing to check, so no marks read")
        assertEquals(0, marks.singleMirrorReads)
        assertTrue(sleepCalls().isEmpty())
    }

    @Test
    fun aNightWhoseStoredIdsCannotBeReadIsNotTakenForItsOwnIdAndIsWrittenAgain() = runBlocking<Unit> {
        val night = SevenNights.nights[2].staged
        sleep.putNight(stored(night))
        writer().flush()
        val calls = sleepCalls().size
        sleep.makeIdsUnreadable(keyOf(night))

        writer().flush()

        assertEquals(calls + 1, sleepCalls().size, "an unreadable id list is not the night's own id: the night is written again")
        assertEquals(listOf("oc:sleep:${text(keyOf(night))}"), sleep.idsOf(keyOf(night)), "and its ids are tracked again from then on")
    }
}
