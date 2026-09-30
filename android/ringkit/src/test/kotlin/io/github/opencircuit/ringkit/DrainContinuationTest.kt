package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The half-night-sync rule: a channel that goes quiet WITHOUT the ring's `0x50` is re-asked while
 * it keeps yielding, and never otherwise.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/DrainContinuationTests.swift
 * (@ b1c2fdd): all 11 tests. The New York calendar of `:70-75` is an explicit
 * `ZoneId.of("America/New_York")`.
 */
class DrainContinuationTest {

    private fun nudge(
        pages: Boolean = true, end: Boolean = false, noProgress: Int = 0, thisRound: Int = 0,
        tick: Int = 10, ceiling: Int = 180, bg: Boolean = false, left: Duration = Duration.ZERO,
        allowed: Boolean = true,
    ): Boolean = DrainContinuation.shouldNudge(
        sawPages = pages, sawEndMarker = end, nudgesWithoutProgress = noProgress,
        nudgesThisRound = thisRound, tick = tick, ceiling = ceiling,
        inBackground = bg, backgroundTimeRemaining = left, allowed = allowed,
    )

    private fun reopen(
        exitReason: HistoryChannelExitReason?, recordsAdded: Int, round: Int,
        inBackground: Boolean, left: Duration, allowed: Boolean = true,
    ): Boolean = DrainContinuation.shouldReopen(
        exitReason = exitReason, recordsAdded = recordsAdded, round = round,
        inBackground = inBackground, backgroundTimeRemaining = left, allowed = allowed,
    )

    // :16
    @Test
    fun nudgeOnlyAfterPagesWithoutEndMarker() {
        assertTrue(nudge())
        assertFalse(nudge(end = true), "the ring said it is done")
        assertFalse(nudge(pages = false), "an empty channel keeps its own exits")
        assertFalse(nudge(noProgress = 1), "a nudge that brought nothing is not repeated")
        assertFalse(nudge(allowed = false), "sport channel / workout-start prime")
    }

    // :27 — a ring that answers EVERY ask with one page must not hold a round open to the ceiling.
    @Test
    fun onePagePerAskRingCannotRunARoundIntoTheCeiling() {
        val ceiling = 180
        var tick = 5
        var noProgress = 0
        var thisRound = 0
        while (tick < ceiling) {
            tick += 3                                              // quiet exit reached
            if (!nudge(noProgress = noProgress, thisRound = thisRound, tick = tick, ceiling = ceiling)) break
            thisRound += 1; noProgress = 1
            tick += 2; noProgress = 0                              // one page answers the ask
        }
        assertTrue(tick < ceiling, "the round ends on its quiet exit, not the ceiling")
        assertEquals(DrainContinuation.MAX_NUDGES_PER_ROUND, thisRound)
    }

    // :40
    @Test
    fun noNudgeWithoutHeadroomBeforeTheCeiling() {
        assertFalse(nudge(tick = 180 - DrainContinuation.NUDGE_HEADROOM_TICKS, ceiling = 180))
        assertTrue(nudge(tick = 180 - DrainContinuation.NUDGE_HEADROOM_TICKS - 1, ceiling = 180))
    }

    // :46 — the nudge honours the background window exactly like a reopen.
    @Test
    fun backgroundNudgeNeedsWindowLeft() {
        assertFalse(nudge(bg = true, left = Duration.ofSeconds(10)))
        assertTrue(nudge(bg = true, left = Duration.ofSeconds(25)))
    }

    // :51
    @Test
    fun reopenIsNotAllowedForThePrimeOrSport() {
        assertFalse(reopen(HistoryChannelExitReason.QUIET_AFTER_PAGES, 9, 0, inBackground = false, left = Duration.ZERO, allowed = false))
    }

    // :58 — the night arrived on the ALL-DAY channel while sleep came back `.empty`.
    @Test
    fun nightOnTheAllDayChannelRestagesFromTheArchive() {
        assertEquals(
            HistoryCommitGate.Decision.RESTAGE_FROM_ARCHIVE,
            HistoryCommitGate.decide(HistoryChannelOutcome.EMPTY, recordsAdded = 0, adoptedRecordCount = 0, nightRecordsOnOtherChannels = 8),
        )
        assertEquals(
            HistoryCommitGate.Decision.SKIP,
            HistoryCommitGate.decide(HistoryChannelOutcome.EMPTY, recordsAdded = 0, adoptedRecordCount = 0),
            "unchanged without night records",
        )
        assertEquals(
            HistoryCommitGate.Decision.STAGE,
            HistoryCommitGate.decide(HistoryChannelOutcome.COMPLETE, recordsAdded = 3, adoptedRecordCount = 0, nightRecordsOnOtherChannels = 8),
            "a complete sleep channel still stages its own slice",
        )
    }

    // :69 — only night records earn the restage, not the all-day channel's daytime SpO₂.
    @Test
    fun onlyNightRecordsCountAsNightOnAnotherChannel() {
        val newYork = ZoneId.of("America/New_York")
        fun at(d: Int, h: Int, m: Int = 0): Instant = ZonedDateTime.of(2026, 9, d, h, m, 0, 0, newYork).toInstant()

        val lastNight = DateInterval(at(27, 22, 30), at(28, 9, 30))
        val tonight = DateInterval(at(28, 22, 30), at(29, 9, 30))
        for (window in listOf(lastNight, tonight)) {                   // whichever the app has cached
            assertTrue(HistoryCommitGate.isNightRecord(at(28, 3, 11), window), "the tester's 03:11 record")
            assertTrue(HistoryCommitGate.isNightRecord(at(28, 11, 0), window), "a late wake inside the margin")
            assertFalse(HistoryCommitGate.isNightRecord(at(28, 14, 0), window), "daytime SpO₂")
        }
        assertTrue(HistoryCommitGate.isNightRecord(at(28, 14, 0), null), "no window: cannot rule out")
    }

    // :88 — the tester's four quiet-after-pages drains would each have been reopened.
    @Test
    fun tricklingRoundsReopenInForeground() {
        for (added in listOf(9, 3, 3, 8)) {
            assertTrue(reopen(HistoryChannelExitReason.QUIET_AFTER_PAGES, added, 0, inBackground = false, left = Duration.ZERO))
        }
    }

    // :96
    @Test
    fun neverReopenWhenTheRingSaysDoneOrGivesNothing() {
        val stop: List<Pair<HistoryChannelExitReason?, Int>> = listOf(
            HistoryChannelExitReason.END_MARKER to 122,        // the healthy ring: 22 pages, then 0x50
            HistoryChannelExitReason.QUIET_AFTER_PAGES to 0,   // re-ask answered with nothing
            HistoryChannelExitReason.QUIET_NO_PAGES to 0, HistoryChannelExitReason.HARD_TIMEOUT to 5,
            HistoryChannelExitReason.CANCELLED to 5, HistoryChannelExitReason.LINK_UNUSABLE to 0, null to 5,
        )
        for ((reason, added) in stop) {
            assertFalse(reopen(reason, added, 0, inBackground = false, left = Duration.ZERO), "$reason / $added")
        }
    }

    // :109
    @Test
    fun roundsAreBounded() {
        val fg = DrainContinuation.MAX_REOPEN_ROUNDS_FOREGROUND
        assertTrue(reopen(HistoryChannelExitReason.QUIET_AFTER_PAGES, 4, fg - 1, inBackground = false, left = Duration.ZERO))
        assertFalse(reopen(HistoryChannelExitReason.QUIET_AFTER_PAGES, 4, fg, inBackground = false, left = Duration.ZERO))
    }

    // :117
    @Test
    fun backgroundNeedsTimeAndAFewerRounds() {
        val bg = DrainContinuation.MAX_REOPEN_ROUNDS_BACKGROUND
        assertTrue(reopen(HistoryChannelExitReason.QUIET_AFTER_PAGES, 4, 0, inBackground = true, left = Duration.ofSeconds(25)))
        assertFalse(
            reopen(HistoryChannelExitReason.QUIET_AFTER_PAGES, 4, 0, inBackground = true, left = Duration.ofSeconds(10)),
            "not enough window left — the next wake resumes it",
        )
        assertFalse(reopen(HistoryChannelExitReason.QUIET_AFTER_PAGES, 4, bg, inBackground = true, left = Duration.ofSeconds(25)))
    }
}
