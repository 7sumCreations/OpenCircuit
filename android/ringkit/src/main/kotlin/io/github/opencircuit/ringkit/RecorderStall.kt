package io.github.opencircuit.ringkit

// Is the RING no longer recording, as distinct from "we haven't synced it lately"? Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/RecorderStall.swift:25-78 (@ b1c2fdd).
//
// WHY THIS EXISTS. A Gen 2 Air tester's export showed the newest `0x4c` epoch four hours old while
// the ring was connected and skin temperature — live-only, not from the drainable history — was
// updating every few seconds. Home rendered a healthy-looking screen whose HR was 4 h stale and HRV
// and RR 12 h stale. Nothing in the app said the recorder had stopped. Upstream measured the stalls
// as much worse on Gen 2 Air (FR04.009: 16.2 % / 32.0 % / 88.1 % of each capture's span with no
// epochs; FR02.018: 0.0 % / 0.0 % / 11.9 %).
//
// ⚠️ THE HARD PART IS NOT DETECTION, IT IS NOT LYING. Two failure modes stay separable:
//   • we have not drained recently                        → "not synced"; say nothing about the ring;
//   • we drained, it completed, and the head did not move → the ring recorded nothing.
// ONE completed-but-empty drain does not prove the second: a drain that exited on the end marker has
// been observed handing over more epochs on each of the next two opens. Hole PERSISTENCE across
// drains is the signal; MINIMUM_UNMOVED_DRAINS is that rule expressed as a number.

import java.time.Duration
import java.time.Instant

object RecorderStall {

    /**
     * Completed drains that must each leave the archive head UNMOVED before we will tell a user the
     * ring stopped recording. 🟢 2 is the documented floor, not a tuned one: at 1 this would call a
     * stall on a ring that is merely slow to hand over.
     *
     * Ported verbatim from upstream (`:34`).
     */
    const val MINIMUM_UNMOVED_DRAINS: Int = 2

    /**
     * How stale the newest epoch must be before staleness is worth mentioning at all. 🟢 The healthy
     * recording cadence is 150 s (the median inter-epoch gap on all six captures upstream measured),
     * so two hours is ~48 missed epochs — well short of the 4 h / 7.6 h / 17 h stalls observed.
     *
     * iOS-tuned — re-check in E9/E11. Ported verbatim from upstream (`:42`).
     */
    val STALE_AFTER: Duration = Duration.ofHours(2)

    /** What the app is entitled to say about the recorder right now. */
    sealed interface Verdict {
        /** The head is fresh, or not stale enough to mention. */
        data object Recording : Verdict

        /**
         * Stale, but not proven to be the ring's fault — not drained enough times to know. The UI
         * must phrase this as OUR uncertainty, never as a ring fault.
         */
        data object UnknownNotDrained : Verdict

        /** Charging (or docked): the ring legitimately stops recording; never report it as a fault. */
        data object ExpectedWhileCharging : Verdict

        /** [MINIMUM_UNMOVED_DRAINS] completed drains in a row left the head unmoved while it went stale. */
        data class Stalled(val since: Instant) : Verdict
    }

    /**
     * Decide what may be said about a ring's recorder.
     *
     * @param newestEpochAt timestamp of the newest `0x4c` epoch held for THIS ring, null if none.
     * @param completedDrainsSinceHeadMoved drains that COMPLETED (reached the ring's own end of
     *   history — not a dropped link, not cancelled, not "no drain ran") since [newestEpochAt] last
     *   advanced. Counting attempts instead would let a flaky link masquerade as a dead recorder.
     * @param isCharging the ring stops recording on the charger; that hole is expected.
     * @param now the current instant — no wall-clock default, so a verdict never depends on when it
     *   was asked.
     */
    fun verdict(
        newestEpochAt: Instant?,
        completedDrainsSinceHeadMoved: Int,
        isCharging: Boolean,
        now: Instant,
    ): Verdict {
        val newest = newestEpochAt ?: return Verdict.UnknownNotDrained
        if (Duration.between(newest, now) < STALE_AFTER) return Verdict.Recording
        if (isCharging) return Verdict.ExpectedWhileCharging
        if (completedDrainsSinceHeadMoved < MINIMUM_UNMOVED_DRAINS) return Verdict.UnknownNotDrained
        return Verdict.Stalled(since = newest)
    }
}
