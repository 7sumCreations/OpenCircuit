package io.github.opencircuit.ringkit

// Whether a per-channel history drain that has run out of budget should EXTEND rather than cut.
// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/DrainBudget.swift:26-53 (@ b1c2fdd).
//
// WHY. A fixed tick budget expired even while the ring was mid-handoff; falling through it yields
// HARD_TIMEOUT → PARTIAL → no sleep commit, so the night is banked but never staged. 🟢 Upstream
// measured a whole-night catch-up whose `0x50` landed ~55 s after the open; a 10 h night is ~88 s.
//
// ⚠️ THE OFF-BY-ONE THIS PINS. A channel only reaches the budget check if it did NOT take the
// quiet exit, so `quietTicks` is 1 or 2 — and at the measured 2.26 s mean inter-page gap against
// 1 s ticks it is 2 about half the time. "Still streaming" means "pages arrived AND the quiet exit
// has not fired", i.e. `quietTicks < quietExitThreshold` — never `quietTicks <= 1`.

object DrainBudget {

    /**
     * The drain loop's own quiet-exit threshold, in ticks. iOS-tuned — re-check in E9/E11.
     * Ported verbatim from upstream's parameter default (`:42`).
     */
    const val DEFAULT_QUIET_EXIT_THRESHOLD = 3

    /**
     * Should an exhausted budget be extended?
     *
     * @param tick iterations completed so far.
     * @param cap the current budget, in ticks.
     * @param ceiling absolute bound; never extend past it.
     * @param sawPages this channel has received at least one page.
     * @param quietTicks ticks since the last page (the frame handler zeroes it on every page).
     * @param quietExitThreshold the loop's own quiet-exit threshold, so the two can never drift apart.
     */
    fun shouldExtend(
        tick: Int,
        cap: Int,
        ceiling: Int,
        sawPages: Boolean,
        quietTicks: Int,
        quietExitThreshold: Int = DEFAULT_QUIET_EXIT_THRESHOLD,
    ): Boolean {
        if (tick < cap) return false                          // budget not spent yet
        if (!sawPages) return false                           // an idle channel is not "streaming"
        if (quietTicks >= quietExitThreshold) return false    // the quiet exit owns this case
        return cap < ceiling
    }

    /** The extended budget: one more [step], clamped to [ceiling]. */
    fun extendedCap(cap: Int, step: Int, ceiling: Int): Int = minOf(cap + step, ceiling)
}
