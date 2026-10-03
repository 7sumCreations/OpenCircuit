package io.github.opencircuit.ringkit

// Did we WATCH the user go to bed, or is the "bedtime" we print just where the data starts? Port of
// upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/BedtimeProvenance.swift (@ b1c2fdd).
//
// Upstream measured the night this exists for: the ring charged 22:19:38–22:35:12, records resume
// 22:36:18, and the app labelled 22:36:18 BEDTIME. The number is as good as it gets (anything earlier
// would be fabricated); what remains is a TRUST problem — a leading edge that is an artifact of when
// recording resumed was presented exactly like one we observed. This classifier answers only that
// question, from evidence already on disk.
//
// ⚠️ IT DELIBERATELY DOES NOT NAME A CAUSE. Charging, taking the ring off and a BLE dropout are
// indistinguishable from the persisted record stream. It reports THAT the stream was absent and for
// how long; the copy layer says no more than that.
//
// Shape notes: spans are `Double` seconds (Swift `TimeInterval`), so a verdict carries upstream's
// gap value and the thresholds compare exactly as upstream's do. The evidence window is compared as a
// gap rather than by subtracting it from the edge, so an edge near the start of `Instant`'s range never
// throws (a Swift `Date` never overflows).

import java.time.Instant

object BedtimeProvenance {

    /** What the record stream says about the moment the night's in-bed window opens. */
    sealed interface Verdict {
        /** The stream runs CONTINUOUSLY into the detected bedtime — an observed settle, not a data edge. */
        data object Witnessed : Verdict

        /**
         * The stream STOPS and then resumes at the detected bedtime. [seconds] is how long the ring
         * recorded nothing beforehand. The true bedtime may be anywhere in that gap.
         */
        data class ResumedAfterGap(val seconds: Double) : Verdict

        /**
         * There is no measurement at all before the detected bedtime, and retention reaches far enough
         * back that its absence is real (first sync after a new ring / a history reset).
         */
        data object NoPriorMeasurement : Verdict

        /**
         * Retention does not reach far enough back to judge. Distinct from [Witnessed] on purpose: "we
         * did not look" must never be presented as "we watched".
         */
        data object Unknown : Verdict
    }

    /**
     * How close the last measurement must sit to the bedtime (seconds) for the stream to count as
     * CONTINUOUS. One `0x4c` epoch every 150 s; two cadences absorb a single dropped or unparsed epoch
     * without calling an intact stream a gap.
     */
    const val CONTINUOUS_TOLERANCE_SECONDS: Double = 300.0

    /**
     * How far back retention must reach (seconds) before the ABSENCE of prior measurement is evidence.
     * Below this the answer is [Verdict.Unknown]: a night at the edge of the retention window has no
     * prior rows simply because they were pruned.
     */
    const val PRIOR_EVIDENCE_WINDOW_SECONDS: Double = 30.0 * 60

    /**
     * Classify the leading edge of a night's in-bed window.
     *
     * [lastMeasurementBefore]: the newest wrist measurement strictly before [inBedStart], or null. Pass
     * a HEART-RATE observation: HR is band-guarded, so a charging or pocketed ring produces none, while
     * a skin-temperature or step row keeps arriving from a docked ring and would call a charge cycle
     * "witnessed". [earliestRetainedMeasurement]: the OLDEST measurement still on disk, to tell
     * "nothing was recorded" from "nothing was retained"; null when the store is empty.
     */
    fun classify(inBedStart: Instant, lastMeasurementBefore: Instant?, earliestRetainedMeasurement: Instant?): Verdict {
        if (lastMeasurementBefore == null) {
            // No prior measurement. Only meaningful if retention reaches back far enough that we WOULD
            // have seen one: earliest <= inBedStart - window.
            val earliest = earliestRetainedMeasurement ?: return Verdict.Unknown
            if (earliest.isAfter(inBedStart) || secondsBetween(earliest, inBedStart) < PRIOR_EVIDENCE_WINDOW_SECONDS) {
                return Verdict.Unknown
            }
            return Verdict.NoPriorMeasurement
        }
        // A measurement at or after the edge is not "before" it: no usable evidence, never a negative gap.
        if (!lastMeasurementBefore.isBefore(inBedStart)) return Verdict.Unknown
        val gap = dateSecondsBetween(lastMeasurementBefore, inBedStart) // Swift's `timeIntervalSince`: the export prints it
        return if (gap <= CONTINUOUS_TOLERANCE_SECONDS) Verdict.Witnessed else Verdict.ResumedAfterGap(gap)
    }

    /**
     * Whether the UI must qualify the bedtime it prints. [Verdict.Witnessed] is the only verdict that
     * earns an unqualified clock time; [Verdict.Unknown] qualifies too — not having looked is not the
     * same as having seen.
     */
    fun needsQualification(verdict: Verdict): Boolean = verdict != Verdict.Witnessed
}
