package io.github.opencircuit.ringkit

// The Android history drain's pure rules. No upstream file: upstream's drain loop
// (ios/OpenCircuit/BLE/RingSession.swift:3908-4105 @ b1c2fdd) counts 1 s ticks on the main actor,
// and its own bundles measured a tick at 1.28–2.08 s of wall time (RingSession.swift:533-536), so
// its tick counts are not seconds. The Android drain times on a monotonic clock, in the units
// below (PORTING.md D-265). Upstream's pure policies (`DrainBudget`, `DrainContinuation`) are used
// unchanged; only the numbers fed to them are Android's.

import java.time.Duration

/**
 * The Android drain's timing (PORTING.md D-265). Android-tuned from upstream's measured tick
 * inflation — re-check every value against the first real-ring drain trace.
 */
object AndroidDrainTiming {
    /**
     * Nothing from the ring for this long after a page ends a round (or earns a fetch nudge).
     * Twice the widest page gap seen (Gen 2 Air, 1–3 s), inside the 3.8–6.2 s wall time iOS's
     * 3-tick quiet exit really took.
     */
    val QUIET: Duration = Duration.ofSeconds(6)

    /** A channel that answered its open but sent no page and no end signal is cut this long after the open (iOS: 12 ticks ≈ 15–25 s). */
    val EMPTY_NO_PAGES: Duration = Duration.ofSeconds(20)

    /** The first budget of a round, from its open (iOS: 45 ticks). Extended only while pages flow ([DrainBudget.shouldExtend]). */
    val NOMINAL_CAP: Duration = Duration.ofSeconds(45)

    /** One extension of the budget. */
    val EXTEND_STEP: Duration = Duration.ofSeconds(45)

    /**
     * The most one round may run, even while pages flow (iOS: 180 ticks ≈ 0.25–1.4 days of a Gen 2
     * backlog at its page rate — a week's backlog would have taken ~5 separate partial syncs).
     */
    val CEILING: Duration = Duration.ofSeconds(3_600)

    /** The whole sync, all channels and rounds: past it the sync commits what it has and ends partial. */
    val WHOLE_SYNC: Duration = Duration.ofMinutes(90)

    /** No `0x82` and no page this long after a channel's open → the one re-auth and re-open of the sync. */
    val OPEN_ANSWER: Duration = Duration.ofSeconds(5)

    /** Still no `0x82` and no page this long after the channel's first open → the ring did not answer. */
    val NO_ANSWER: Duration = Duration.ofSeconds(20)

    /** Before the first open: the longest wait for the answer to an outstanding `d0 00 00` status query. */
    val STATUS_ANSWER_WAIT: Duration = Duration.ofSeconds(2)

    /** From a channel's open to its `07 00 00` (upstream sleeps 300 ms after each write, RingSession.swift:3939-3941). */
    val OPEN_TO_FETCH: Duration = Duration.ofMillis(300)
}

/**
 * The verdict on one channel of one sync, over its reopen rounds (each a [HistoryChannelTrace],
 * oldest first). D-43 is kept: pages followed by quiet with no `0x50` is PARTIAL, so a channel is
 * COMPLETE only when its last round ended on the ring's end report. A reopen only follows a round
 * that delivered records and then went quiet without one ([DrainContinuation.shouldReopen]), so a
 * later round answered with nothing (empty or silent) leaves the channel PARTIAL, never EMPTY:
 * the ring never said it was done.
 */
object HistoryChannelVerdict {

    /** The channel's verdict, or null when it has no round. */
    fun of(rounds: List<HistoryChannelTrace>): HistoryChannelOutcome? {
        val last = rounds.lastOrNull() ?: return null
        if (rounds.size == 1 || last.outcome == HistoryChannelOutcome.COMPLETE) return last.outcome
        return if (rounds.any { it.page4CCount > 0 }) HistoryChannelOutcome.PARTIAL else last.outcome
    }
}

/**
 * How far one channel's drain has come, from the 16-bit countdown each `0x4c` page carries
 * (bytes 1–2, PORTING.md D-259): [records] received so far, [expected] = those plus the latest
 * page's countdown (on the first page: its own records plus its countdown), and [etaSeconds] at
 * the record rate measured since the first page (null until a second page gives a rate).
 */
data class DrainProgress(val records: Int, val expected: Int?, val etaSeconds: Long?) {
    companion object {
        /**
         * @param records records received on the channel so far.
         * @param lastCountdown the latest page's countdown, or null when no page carried one.
         * @param firstPageRecords the records the channel's first page held.
         * @param firstPageAtMillis when that page arrived (monotonic).
         * @param lastPageAtMillis when the latest page arrived (monotonic).
         */
        fun of(records: Int, lastCountdown: Int?, firstPageRecords: Int, firstPageAtMillis: Long, lastPageAtMillis: Long): DrainProgress {
            require(records >= 0 && firstPageRecords >= 0) { "record counts cannot be negative" }
            require(lastCountdown == null || lastCountdown in 0..0xFFFF) { "a countdown is 16 bits: $lastCountdown" }
            if (lastCountdown == null) return DrainProgress(records, expected = null, etaSeconds = null)
            if (lastCountdown == 0) return DrainProgress(records, expected = records, etaSeconds = 0)
            val since = records - firstPageRecords
            val elapsed = lastPageAtMillis - firstPageAtMillis
            val eta = if (since > 0 && elapsed > 0) {
                // ceil(remaining / (since / elapsed s)), in whole seconds, in integers.
                (lastCountdown.toLong() * elapsed + since.toLong() * 1_000 - 1) / (since.toLong() * 1_000)
            } else {
                null
            }
            return DrainProgress(records, expected = records + lastCountdown, etaSeconds = eta)
        }
    }
}
