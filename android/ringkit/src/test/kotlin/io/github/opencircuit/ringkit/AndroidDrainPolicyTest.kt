package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The Android drain's pure rules: its timing constants (in seconds on a monotonic clock, where
 * upstream counted 1 s ticks that ran 1.28–2.08 s each, `ios/OpenCircuit/BLE/RingSession.swift:533-536`
 * @ b1c2fdd), the verdict over a channel's reopen rounds, and the progress estimate read from the
 * pages' 16-bit countdown. Every expected value is a literal typed from the approved numbers,
 * never read back from the code under test.
 */
class AndroidDrainPolicyTest {

    private val t0 = Instant.parse("2026-10-08T09:00:00Z")

    // MARK: timing — PORTING.md D-265

    @Test
    fun theAndroidTimingConstantsAreTheApprovedOnes() {
        assertEquals(Duration.ofSeconds(6), AndroidDrainTiming.QUIET)
        assertEquals(Duration.ofSeconds(20), AndroidDrainTiming.EMPTY_NO_PAGES)
        assertEquals(Duration.ofSeconds(45), AndroidDrainTiming.NOMINAL_CAP)
        assertEquals(Duration.ofSeconds(45), AndroidDrainTiming.EXTEND_STEP)
        assertEquals(Duration.ofSeconds(3_600), AndroidDrainTiming.CEILING)
        assertEquals(Duration.ofMinutes(90), AndroidDrainTiming.WHOLE_SYNC)
        assertEquals(Duration.ofSeconds(5), AndroidDrainTiming.OPEN_ANSWER)
        assertEquals(Duration.ofSeconds(20), AndroidDrainTiming.NO_ANSWER)
        assertEquals(Duration.ofSeconds(2), AndroidDrainTiming.STATUS_ANSWER_WAIT)
        assertEquals(Duration.ofMillis(300), AndroidDrainTiming.OPEN_TO_FETCH)
    }

    @Test
    fun theQuietExitIsLongerThanTheWidestMeasuredPageGapAndInsideIosEffectiveWall() {
        // Gen 2 Air pages arrive 1–3 s apart; iOS's 3-tick quiet exit ran 3.8–6.2 s of wall time.
        assertEquals(true, AndroidDrainTiming.QUIET >= Duration.ofSeconds(3).multipliedBy(2))
        assertEquals(true, AndroidDrainTiming.QUIET <= Duration.ofMillis(6_200))
    }

    // MARK: the verdict over reopen rounds — D-43 unchanged

    private fun round(pages4c: Int = 0, endMarkers: Int = 0, ack: Boolean = true, round: Int = 0) =
        HistoryChannelTrace("sleep", 0x00, t0).also {
            it.page4CCount = pages4c
            it.endMarkerCount = endMarkers
            it.sawSyncAck = ack
            it.reopenRound = round
        }

    @Test
    fun noRoundHasNoVerdict() {
        assertNull(HistoryChannelVerdict.of(emptyList()))
    }

    @Test
    fun oneRoundIsJudgedByItsOwnOutcome() {
        assertEquals(HistoryChannelOutcome.COMPLETE, HistoryChannelVerdict.of(listOf(round(pages4c = 3, endMarkers = 1))))
        assertEquals(HistoryChannelOutcome.PARTIAL, HistoryChannelVerdict.of(listOf(round(pages4c = 3))))
        assertEquals(HistoryChannelOutcome.EMPTY, HistoryChannelVerdict.of(listOf(round())))
        assertEquals(HistoryChannelOutcome.NO_ACK, HistoryChannelVerdict.of(listOf(round(ack = false))))
    }

    @Test
    fun aReopenThatEndsWithTheEndReportCompletesTheChannel() {
        val rounds = listOf(round(pages4c = 3), round(pages4c = 2, endMarkers = 1, round = 1))
        assertEquals(HistoryChannelOutcome.COMPLETE, HistoryChannelVerdict.of(rounds))
    }

    @Test
    fun aReopenAnsweredWithNothingLeavesTheChannelPartialNotEmpty() {
        // The ring never said it was done: pages then quiet with no 0x50 stays PARTIAL (D-43).
        val emptyReopen = listOf(round(pages4c = 3), round(round = 1))
        assertEquals(HistoryChannelOutcome.PARTIAL, HistoryChannelVerdict.of(emptyReopen))
        val silentReopen = listOf(round(pages4c = 3), round(ack = false, round = 1))
        assertEquals(HistoryChannelOutcome.PARTIAL, HistoryChannelVerdict.of(silentReopen))
    }

    // MARK: progress from the 16-bit countdown

    @Test
    fun theFirstPageGivesTheChannelsTotalAsItsRecordsPlusItsCountdown() {
        // 717 queued after a 6-record page (bytes 1–2 = 02 cd).
        val p = DrainProgress.of(records = 6, lastCountdown = 717, firstPageRecords = 6, firstPageAtMillis = 1_000, lastPageAtMillis = 1_000)
        assertEquals(6, p.records)
        assertEquals(723, p.expected)
        assertNull(p.etaSeconds, "one page gives no rate")
    }

    @Test
    fun theEtaComesFromTheMeasuredPageRate() {
        // 6 more records in 1.3 s after the first page: 120 left at 4.615… records/s → 26 s (rounded up).
        val p = DrainProgress.of(records = 12, lastCountdown = 120, firstPageRecords = 6, firstPageAtMillis = 1_000, lastPageAtMillis = 2_300)
        assertEquals(132, p.expected)
        assertEquals(26L, p.etaSeconds)
    }

    @Test
    fun aDrainedChannelHasNoTimeLeftAndNoCountdownGivesNoTotal() {
        assertEquals(0L, DrainProgress.of(records = 18, lastCountdown = 0, firstPageRecords = 6, firstPageAtMillis = 0, lastPageAtMillis = 4_000).etaSeconds)
        val none = DrainProgress.of(records = 5, lastCountdown = null, firstPageRecords = 5, firstPageAtMillis = 0, lastPageAtMillis = 0)
        assertNull(none.expected)
        assertNull(none.etaSeconds)
    }

    @Test
    fun negativeCountsAreRejected() {
        assertFailsWith<IllegalArgumentException> { DrainProgress.of(records = -1, lastCountdown = 0, firstPageRecords = 0, firstPageAtMillis = 0, lastPageAtMillis = 0) }
        assertFailsWith<IllegalArgumentException> { DrainProgress.of(records = 1, lastCountdown = -1, firstPageRecords = 0, firstPageAtMillis = 0, lastPageAtMillis = 0) }
    }
}
