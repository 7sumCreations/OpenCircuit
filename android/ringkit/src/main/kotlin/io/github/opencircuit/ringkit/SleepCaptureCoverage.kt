package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/SleepCaptureCoverage.swift (@ b1c2fdd): was
// last night's sleep FULLY captured, or limited by the ring's onboard memory?
//
// The ring's history buffer holds only ~4.75 h of epochs and DROPS THE OLDEST when full (PROTOCOL.md
// §5.3). If nothing drains the ring overnight, the early hours are overwritten before the morning sync.
// Duration ALONE can't separate "buffer-truncated" from "genuinely short night"; the discriminator is
// WHERE the loss is: truncation drops the FRONT of the night, so the captured onset lands well after the
// user's bedtime. So the night is flagged only with a bedtime reference AND a late captured onset —
// never on duration alone.
//
// Spans are `Duration`s here (upstream: seconds as `Double`), so NaN and infinite spans cannot reach it.

import java.time.Duration
import java.time.Instant

object SleepCaptureCoverage {

    /** The ring's onboard history-buffer span: ~114 epochs × 150 s ≈ 4.75 h. */
    val RING_BUFFER: Duration = Duration.ofSeconds(17_100)

    /** Slack above the buffer before a span stops looking buffer-limited (drains aren't instant). */
    internal val BUFFER_SLACK: Duration = Duration.ofMinutes(20)

    /** How far the captured onset must trail the scheduled bedtime before the front counts as missing. */
    internal val MIN_MISSING_ONSET: Duration = Duration.ofMinutes(90)

    enum class Coverage {
        /** The captured night looks complete (or we can't tell it isn't). */
        FULL,

        /**
         * The captured span has the buffer-limited signature — early hours were likely overwritten on the
         * ring before any drain. The fix is overnight charging (so the phone runs the drain).
         */
        LIKELY_TRUNCATED,
    }

    /**
     * Classify last night's coverage from WHERE the capture starts relative to bedtime.
     *
     * [capturedOnset] is the start of the captured in-bed window; [capturedInBed] the staged night's
     * in-bed span (zero or negative ⇒ FULL); [scheduledBedtime] the user's bedtime for this night, or
     * null when no schedule is set. LIKELY_TRUNCATED only on POSITIVE evidence: the span fits within
     * the buffer (+ slack) AND the captured onset trails bedtime by at least [MIN_MISSING_ONSET].
     * Conservative by design — a missed flag just omits a tip, a false flag would nag.
     */
    fun classify(capturedOnset: Instant, capturedInBed: Duration, scheduledBedtime: Instant?): Coverage {
        if (capturedInBed <= Duration.ZERO) return Coverage.FULL
        // Drained past the buffer ⇒ nothing was lost to overflow.
        if (capturedInBed > RING_BUFFER.plus(BUFFER_SLACK)) return Coverage.FULL
        // No bedtime reference ⇒ can't distinguish truncation from a genuinely short night.
        val bedtime = scheduledBedtime ?: return Coverage.FULL
        // Truncation loses the FRONT: the captured onset lands well after bedtime.
        return if (Duration.between(bedtime, capturedOnset) >= MIN_MISSING_ONSET) Coverage.LIKELY_TRUNCATED else Coverage.FULL
    }
}
