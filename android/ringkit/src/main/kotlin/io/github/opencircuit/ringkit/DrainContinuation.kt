package io.github.opencircuit.ringkit

// Whether a history channel that went QUIET without the ring's `0x50` end-of-history is really
// finished — the "half-night sync". Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/DrainContinuation.swift:27-92 (@ b1c2fdd).
//
// WHAT HAPPENED UPSTREAM. A Gen 3 FR05.011 ring held a whole night, yet each of four morning drains
// ended a channel quiet-after-pages after 1–2 `0x4c` pages and ZERO `0x50`s, delivering a few
// records each whose timestamps marched forward. So "quiet for 3 s" is not "done" on every ring —
// only the `0x50` is a ring-side statement (and even that is not proof the ring is empty).
//
// THE RULE. Keep asking while the ring keeps giving; stop the moment it says it is done (`0x50`) or
// answers a re-ask with nothing:
//   1. NUDGE — at the quiet exit, a channel that streamed pages but sent no `0x50` gets one more
//      `07 00 00` fetch and another quiet window. Pages resuming resets the allowance.
//   2. REOPEN — a channel that still ends quiet-without-`0x50` but ADDED records is reopened in the
//      same sync.
// Both only ever LENGTHEN a drain that is still yielding data; neither changes an `0x50` exit, an
// empty channel, or the commit gate.

import java.time.Duration

object DrainContinuation {

    /**
     * Fetch nudges allowed in a row with NO new page in between: 1.
     * iOS-tuned — re-check in E9/E11. Ported verbatim from upstream (`:31`).
     */
    const val MAX_NUDGES_WITHOUT_PROGRESS = 1

    /**
     * Nudges per ROUND, progress or not: 2. Small on purpose — a ring answering every ask with one
     * page would otherwise hold a round open until the tick ceiling (→ HARD_TIMEOUT → PARTIAL → not
     * staged). Sustained continuation belongs to the reopen loop, which gets a fresh budget.
     * iOS-tuned — re-check in E9/E11. Ported verbatim from upstream (`:36`).
     */
    const val MAX_NUDGES_PER_ROUND = 2

    /**
     * Ticks that must remain before the ceiling for a nudge to be allowed: 8 (the 3-tick quiet exit
     * it re-arms plus ~2 s page latency plus margin).
     * iOS-tuned — re-check in E9/E11. Ported verbatim from upstream (`:40`).
     */
    const val NUDGE_HEADROOM_TICKS = 8

    /**
     * Reopen rounds per channel per sync in the foreground: 12. A policy bound, not a measurement.
     * iOS-tuned — re-check in E9/E11. Ported verbatim from upstream (`:47`).
     */
    const val MAX_REOPEN_ROUNDS_FOREGROUND = 12

    /**
     * Reopen rounds per channel per sync in the background: 2 (iOS gives a background wake ~30 s).
     * iOS-tuned — re-check in E9/E11. Ported verbatim from upstream (`:48`).
     */
    const val MAX_REOPEN_ROUNDS_BACKGROUND = 2

    /**
     * Background time that must remain for a nudge or reopen: 15 s. Upstream: `minBackgroundSecondsForReopen`.
     * iOS-tuned — re-check in E9/E11. Ported verbatim from upstream (`:49`).
     */
    val MIN_BACKGROUND_TIME_FOR_REOPEN: Duration = Duration.ofSeconds(15)

    /**
     * At the quiet exit: send another fetch instead of ending the channel? [allowed] is false for
     * the sport channel and for the workout-start prime.
     */
    fun shouldNudge(
        sawPages: Boolean,
        sawEndMarker: Boolean,
        nudgesWithoutProgress: Int,
        nudgesThisRound: Int,
        tick: Int,
        ceiling: Int,
        inBackground: Boolean,
        backgroundTimeRemaining: Duration,
        allowed: Boolean = true,
    ): Boolean {
        if (!allowed || !sawPages || sawEndMarker) return false
        if (nudgesWithoutProgress >= MAX_NUDGES_WITHOUT_PROGRESS ||
            nudgesThisRound >= MAX_NUDGES_PER_ROUND ||
            tick + NUDGE_HEADROOM_TICKS >= ceiling
        ) {
            return false
        }
        if (inBackground) return backgroundTimeRemaining >= MIN_BACKGROUND_TIME_FOR_REOPEN
        return true
    }

    /**
     * After a channel returns: open the SAME channel again in this sync?
     *
     * @param exitReason how the round ended. Only [HistoryChannelExitReason.QUIET_AFTER_PAGES]
     *   qualifies — an `0x50` is the ring saying it is done, and every other reason means nothing
     *   came or the link/task is going away.
     * @param recordsAdded records THIS round added. Zero means the ring had nothing more.
     * @param round reopen rounds already performed for this channel in this sync.
     */
    fun shouldReopen(
        exitReason: HistoryChannelExitReason?,
        recordsAdded: Int,
        round: Int,
        inBackground: Boolean,
        backgroundTimeRemaining: Duration,
        allowed: Boolean = true,
    ): Boolean {
        if (!allowed || exitReason != HistoryChannelExitReason.QUIET_AFTER_PAGES || recordsAdded <= 0) return false
        if (inBackground) {
            return round < MAX_REOPEN_ROUNDS_BACKGROUND && backgroundTimeRemaining >= MIN_BACKGROUND_TIME_FOR_REOPEN
        }
        return round < MAX_REOPEN_ROUNDS_FOREGROUND
    }
}
