package io.github.opencircuit.ringkit

// Is the ring still WRITING history, or only still TALKING? (the stranded-sport-mode detector)
// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/EpochRecordingHealth.swift:58-129
// (@ b1c2fdd).
//
// Upstream proved on a real ring (2026-08-16) that an app crash mid-workout can leave the ring in
// sport mode, recording ZERO `0x4c` epochs for ~20 h while the live descriptor keeps flowing — so
// the connection looks perfectly healthy while nothing is recorded. "No epochs WHILE the
// descriptor is live" looks like a detector.
//
// ⚠️ NOT WIRED UPSTREAM, AND IT MUST NOT BE WIRED HERE AS-IS. Review found two proven false-alarm
// modes: (1) an unworn ring beside the phone keeps the descriptor fresh while its epochs advance
// no heart-rate watermark; (2) overnight drains are suppressed by design, so every normal morning
// looks stalled before the wake drain lands. The tests pin both as today's behaviour. The rebuild
// upstream describes classifies from DRAIN OUTCOMES (every drain completing with zero `0x4c`
// pages), gated on not charging. What ships instead is recovery on the next connect.
//
// `now` is always passed in — there is no wall-clock default.

import java.time.Duration
import java.time.Instant

object EpochRecordingHealth {

    /**
     * How stale the newest epoch must be before recording counts as stalled. It must clear the
     * widest LEGITIMATE gap between drains, not the 150 s epoch interval. iOS-tuned — re-check
     * against the Android drain cadence.
     */
    val STALE_AFTER: Duration = Duration.ofHours(3)

    /** How recently the live descriptor must have been heard for the ring to count as "talking". */
    val DESCRIPTOR_FRESH_WITHIN: Duration = Duration.ofMinutes(15)

    sealed interface Status {
        /** Epochs are current, or there is no evidence of a problem. */
        data object Recording : Status

        /** Not enough information to judge (no descriptor, or no epoch history yet). */
        data object Unknown : Status

        /**
         * The ring is connected and answering, but has written no epoch history since [since].
         * Everything built on `0x4c` — HR, SpO2, HRV, respiratory rate and SLEEP — is being lost.
         */
        data class Stalled(val since: Instant) : Status

        val isStalled: Boolean get() = this is Stalled
    }

    /**
     * Classify from the two timestamps that matter: [newestEpochAt] (device time of the newest
     * `0x4c` record held, or null) and [newestDescriptorAt] (phone time the live descriptor was
     * last heard, or null). Returns [Status.Unknown] rather than stalled whenever either is
     * missing.
     */
    fun classify(
        newestEpochAt: Instant?,
        newestDescriptorAt: Instant?,
        now: Instant,
        staleAfter: Duration = STALE_AFTER,
        descriptorFreshWithin: Duration = DESCRIPTOR_FRESH_WITHIN,
    ): Status {
        val epoch = newestEpochAt ?: return Status.Unknown
        val descriptor = newestDescriptorAt ?: return Status.Unknown
        // The ring must be demonstrably present RIGHT NOW, or every ring left in a drawer warns.
        if (clampedSince(descriptor, now) > descriptorFreshWithin) return Status.Unknown
        // A future-dated epoch (ring clock drift) is clamped rather than treated as infinitely fresh.
        if (clampedSince(epoch, now) < staleAfter) return Status.Recording
        // Dead under the shipped constants and compares two different clocks (device vs phone);
        // kept only to match upstream, which flags it the same way.
        if (!descriptor.isAfter(epoch)) return Status.Unknown
        return Status.Stalled(since = epoch)
    }

    /** Whole hours since recording stopped (never 0 — copy must not read "0 hours"); null unless stalled. */
    fun stalledHours(status: Status, now: Instant): Int? {
        if (status !is Status.Stalled) return null
        val d = Duration.between(status.since, now)
        val hours = (d.seconds + d.nano / 1e9) / 3600.0
        return maxOf(1, hours.toInt()) // toInt truncates toward zero, as Swift's Int(Double) does
    }

    /** `max(0, now - t)`. */
    private fun clampedSince(t: Instant, now: Instant): Duration {
        val d = Duration.between(t, now)
        return if (d.isNegative) Duration.ZERO else d
    }
}
