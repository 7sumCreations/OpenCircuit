package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How long the epoch archive keeps records once nights are staged from it (Kotlin-only, PORTING
 * D-270): from 30 h before the end of the newest night saved in sequence (`stagedThrough`), never
 * less than 30 h, never more than 14 days; with nothing staged yet, the 14 days. The values are typed
 * here as literals, not read from the constants.
 */
class EpochArchiveStagedRetentionTest {

    private val newest: Instant = Instant.parse("2026-06-14T06:07:41Z")
    private fun keep(stagedThrough: Instant?) = EpochArchive.retentionKeepingUnstaged(newest, stagedThrough)

    @Test
    fun nothingStagedYetKeepsFourteenDays() {
        assertEquals(Duration.ofDays(14), keep(null))
    }

    @Test
    fun theArchiveReachesBackThirtyHoursBeforeTheLastStagedNight() {
        assertEquals(Duration.ofHours(32), keep(newest.minus(Duration.ofHours(2))))
        assertEquals(Duration.ofHours(30 + 5 * 24), keep(newest.minus(Duration.ofDays(5))))
    }

    @Test
    fun neverLessThanThirtyHours() {
        assertEquals(Duration.ofHours(30), keep(newest))
        assertEquals(Duration.ofHours(30), keep(newest.plus(Duration.ofDays(3))))
    }

    @Test
    fun neverMoreThanFourteenDays() {
        val atCap = newest.minus(Duration.ofDays(12)).minus(Duration.ofHours(18))
        assertEquals(Duration.ofDays(14), keep(atCap))
        assertEquals(Duration.ofDays(14).minusSeconds(1), keep(atCap.plusSeconds(1)))
        assertEquals(Duration.ofDays(14), keep(atCap.minusSeconds(1)))
        assertEquals(Duration.ofDays(14), keep(newest.minus(Duration.ofDays(400))))
    }

    @Test
    fun theMergeKeepsEveryUnstagedRecordAndAFarFutureCounterCannotMoveTheAnchor() {
        // One record every 150 s for 7 days, then a far-future garbage record.
        val start = newest.minus(Duration.ofDays(7))
        val week = (0 until 7 * 576).map { record(start.plusSeconds(it * 150L)) }
        val garbage = record(Instant.parse("2090-01-01T00:00:00Z"))
        val bound = newest.plus(Duration.ofDays(1))

        // Nothing staged: all 7 days stay (the flat 30 h would keep one day and a quarter).
        assertEquals(week, EpochArchive.mergeKeepingUnstaged(emptyList(), week + garbage, stagedThrough = null, notAfter = bound))
        // Staged through day 5: everything from 30 h before it on stays, the older records go.
        val staged = start.plus(Duration.ofDays(5))
        val kept = EpochArchive.mergeKeepingUnstaged(emptyList(), week + garbage, stagedThrough = staged, notAfter = bound)
        assertEquals(week.filter { !it.date().isBefore(staged.minus(Duration.ofHours(30))) }, kept)
    }

    private fun record(at: Instant): BulkRecord {
        val counter = at.epochSecond - Command.SYNC_EPOCH
        val b = ByteArray(BulkRecord.LENGTH)
        b[0] = (counter shr 24).toByte(); b[1] = (counter shr 16).toByte()
        b[2] = (counter shr 8).toByte(); b[3] = counter.toByte()
        return BulkRecord.of(b)!!
    }
}
