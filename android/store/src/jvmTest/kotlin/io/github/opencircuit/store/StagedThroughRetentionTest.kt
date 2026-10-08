package io.github.opencircuit.store

import io.github.opencircuit.ringkit.BulkRecord
import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.store.codec.Decoded
import io.github.opencircuit.store.codec.EpochArchiveCodec
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The archive's `stagedThrough` mark — the end of the newest night saved with every earlier night
 * saved too — and the retention the store's merge keeps with it (Kotlin-only, PORTING D-270): from
 * 30 h before it, at least 30 h, at most 14 days; nothing staged yet keeps the 14 days, so a
 * multi-day backlog is still whole when its nights are staged. A stored form without the mark (every
 * archive written before it) reads as nothing staged.
 */
class StagedThroughRetentionTest {

    private val ring = "ring-A"
    private val newest = Instant.parse("2026-10-07T06:00:00Z")
    private val now = newest.plus(Duration.ofHours(1))
    private val bound = now.plus(Duration.ofDays(1))

    /** One idle record every 150 s for [days] days up to [newest] (raw path). */
    private fun backlog(days: Int): List<BulkRecord> {
        val start = newest.minus(Duration.ofDays(days.toLong()))
        return (0..days * 576).map { record(start.plusSeconds(it * 150L)) }
    }

    @Test
    fun theMarkIsStoredReadBackAndAbsentInEveryOlderStoredForm() {
        val staged = Instant.ofEpochMilli(1_790_000_000_123)
        val archive = StoredEpochArchive(emptyList(), EpochArchiveMarks(stagedThrough = staged))

        assertEquals("""{"records":"","stagedThrough":1790000000123,"unmovedDrains":0}""", EpochArchiveCodec.encode(archive))
        assertEquals(archive, (EpochArchiveCodec.decode(EpochArchiveCodec.encode(archive)) as Decoded.Readable).value)
        // Written before the mark existed: nothing staged.
        assertNull((EpochArchiveCodec.decode("""{"records":"","unmovedDrains":0}""") as Decoded.Readable).value.marks.stagedThrough)
        // A mark this build cannot read is no mark, and never costs the records.
        val odd = EpochArchiveCodec.decode("""{"records":"","stagedThrough":"soon","unmovedDrains":0}""")
        assertNull((odd as Decoded.Readable).value.marks.stagedThrough)
        assertFailsWith<IllegalArgumentException> { EpochArchiveMarks(stagedThrough = Instant.EPOCH) }
    }

    @Test
    fun withNothingStagedAWeekLongBacklogStaysWhole() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val week = backlog(7)
            val merged = BlobStore(db).mergeEpochArchive(ring, week, notAfter = bound, now = now)

            assertEquals(week, merged.records, "the flat 30 h would keep one day and a quarter")
        }
    }

    @Test
    fun onceStagedTheArchiveKeepsThirtyHoursBeforeTheMarkAndEverythingAfter() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val blobs = BlobStore(db)
            val week = backlog(7)
            blobs.mergeEpochArchive(ring, week, notAfter = bound, now = now)
            val staged = newest.minus(Duration.ofDays(2))
            blobs.saveEpochArchiveMarks(ring, EpochArchiveMarks(stagedThrough = staged), now)

            val merged = blobs.mergeEpochArchive(ring, emptyList(), notAfter = bound, now = now)

            assertEquals(week.filter { !it.date().isBefore(staged.minus(Duration.ofHours(30))) }, merged.records)
            assertEquals(staged, blobs.loadEpochArchive(ring).marks.stagedThrough, "the merge keeps the mark")
        }
    }

    @Test
    fun aBacklogOlderThanFourteenDaysKeepsItsNewestFourteen() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val merged = BlobStore(db).mergeEpochArchive(ring, backlog(20), notAfter = bound, now = now)

            assertEquals(newest.minus(Duration.ofDays(14)), merged.records.first().date())
            assertEquals(newest, merged.records.last().date())
        }
    }

    private fun record(at: Instant): BulkRecord {
        val counter = at.epochSecond - Command.SYNC_EPOCH
        val b = ByteArray(BulkRecord.LENGTH)
        b[0] = (counter shr 24).toByte(); b[1] = (counter shr 16).toByte()
        b[2] = (counter shr 8).toByte(); b[3] = counter.toByte()
        return BulkRecord.of(b)!!
    }
}
