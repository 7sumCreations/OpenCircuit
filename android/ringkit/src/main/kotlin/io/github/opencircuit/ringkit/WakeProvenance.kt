package io.github.opencircuit.ringkit

// Did we WATCH the user wake up, or is the "wake" we print just where the data STOPS? Port of
// upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/WakeProvenance.swift (@ b1c2fdd) — the
// trailing-edge mirror of BedtimeProvenance.
//
// Upstream measured, over 21 staged corpus nights: the detected in-bed END sits within one 150 s
// epoch of a real record on every night, so "the night ends at a data edge" has ZERO discriminating
// power. What DOES discriminate is whether the stream RESUMES afterwards: 10 nights have a record
// within 300 s, 4 stop and resume after 7.5 / 33.0 / 241.9 / 243.6 min (a provable data edge), and 7
// have nothing usable within 12 h (unprovable). Both 246-minute errors in the corpus are in the middle
// group, with their ~4 h hole beginning exactly AT the in-bed end — invisible to any internal-hole or
// coverage test.
//
// ⚠️ IT DELIBERATELY DOES NOT NAME A CAUSE, and ⚠️ IT DOES NOT ESTIMATE THE MISSING SLEEP: the gap
// BOUNDS the error, it does not estimate it. Copy must state the measured gap, never an inferred total.
//
// Shape notes: spans and thresholds are `Double` seconds (Swift `TimeInterval`), so `0` and infinity
// keep upstream's meanings (maximally loud / kill switch) and a NaN limit or threshold behaves as
// upstream's comparisons do. `Stoppage` is a value with the verdict and the instant its silence began.

import java.time.Instant

object WakeProvenance {

    /** What the record stream says about the moment the night's in-bed window CLOSES. */
    sealed interface Verdict {
        /** The stream runs CONTINUOUSLY past the detected wake — a decision the stager made, not the end of the data. */
        data object Witnessed : Verdict

        /**
         * The stream STOPS at the detected wake and only resumes later. [seconds] is how long the ring
         * recorded nothing. Sleep may have continued anywhere in that gap.
         */
        data class StoppedThenResumed(val seconds: Double) : Verdict

        /**
         * No measurement after the detected wake at all: "the ring stopped recording" and "you synced the
         * moment you woke up" are the same picture. Must stay SILENT in the UI.
         */
        data object Unknown : Verdict
    }

    /**
     * How close the next measurement must sit to the wake (seconds) for the stream to count as
     * CONTINUOUS. Deliberately the SAME constant as the leading edge, aliased so it cannot drift.
     */
    const val CONTINUOUS_TOLERANCE_SECONDS: Double = BedtimeProvenance.CONTINUOUS_TOLERANCE_SECONDS

    /**
     * How long (seconds) the stream must be absent before the gap is worth telling the USER about.
     *
     * ⚠️ NOT FITTED. Upstream's corpus gaps after the in-bed end are bimodal with an EMPTY interval from
     * 33.0 to 241.9 min, so every cut in (33.0, 241.9] scores identically; 60 min is a ~1.8× margin
     * over the largest gap the corpus cannot adjudicate — a choice made on one night. Callers pass it
     * explicitly: `0` makes every non-witnessed edge material, infinity is the kill switch.
     */
    const val MATERIAL_GAP_SECONDS: Double = 60.0 * 60

    /**
     * How far past the in-bed end (seconds) a material hole may BEGIN and still be part of this night —
     * the bound on the run walk in [stoppage]. It IS the continuity tolerance, deliberately, not a new
     * number (upstream's corpus holds zero nights of the shape the walk acts on). `0` is the KILL
     * SWITCH: the walk never runs and the list overload reproduces the single-step one exactly.
     */
    const val RESUME_RUN_MAX_SECONDS: Double = CONTINUOUS_TOLERANCE_SECONDS

    /**
     * Classify the trailing edge of a night's in-bed window from the single next measurement.
     *
     * [firstMeasurementAfter]: the oldest wrist measurement strictly AFTER [inBedEnd], or null (pass a
     * HEART-RATE observation, as for the leading edge). [earliestRetainedMeasurement]: the OLDEST
     * measurement still on disk; null when the caller has no retention information, in which case the
     * raw verdict stands. It has NO DEFAULT on purpose: if the oldest row we hold is NEWER than the
     * night's end, everything at and after the edge was pruned and the "next" measurement is the
     * retention boundary, not the ring resuming — so the answer is [Verdict.Unknown].
     */
    fun classify(inBedEnd: Instant, firstMeasurementAfter: Instant?, earliestRetainedMeasurement: Instant?): Verdict {
        // Retention no longer reaches this night's end ⇒ whatever comes "after" it is the pruning boundary.
        if (earliestRetainedMeasurement != null && earliestRetainedMeasurement.isAfter(inBedEnd)) return Verdict.Unknown
        val next = firstMeasurementAfter ?: return Verdict.Unknown
        // A measurement at or before the edge is not "after" it: no usable evidence, never a negative gap.
        if (!next.isAfter(inBedEnd)) return Verdict.Unknown
        val gap = secondsBetween(inBedEnd, next)
        return if (gap <= CONTINUOUS_TOLERANCE_SECONDS) Verdict.Witnessed else Verdict.StoppedThenResumed(gap)
    }

    /**
     * Classify the trailing edge against the WHOLE run of measurements that follows it. Strictly
     * additive over the single-step form: it returns that verdict unchanged except when it is
     * [Verdict.Witnessed], which it may upgrade to [Verdict.StoppedThenResumed]. Order and duplicates
     * in [measurementsAfter] do not matter; entries at or before [inBedEnd] are dropped.
     */
    fun classify(
        inBedEnd: Instant,
        measurementsAfter: List<Instant>,
        earliestRetainedMeasurement: Instant?,
        resumeRunLimit: Double = RESUME_RUN_MAX_SECONDS,
    ): Verdict = stoppage(inBedEnd, measurementsAfter, earliestRetainedMeasurement, resumeRunLimit).verdict

    /**
     * A verdict together with the instant its silence BEGAN — the last measurement before the hole, or
     * [inBedEnd] when the silence starts at the edge; null unless the verdict is
     * [Verdict.StoppedThenResumed]. The walk can consume records before the hole, so the invariant the
     * copy depends on is `silenceBegan + gap == the record that resumed`, never `inBedEnd + gap`.
     */
    data class Stoppage(val verdict: Verdict, val silenceBegan: Instant?)

    /** The walk, reporting WHERE the silence began as well as how long it lasted. */
    fun stoppage(
        inBedEnd: Instant,
        measurementsAfter: List<Instant>,
        earliestRetainedMeasurement: Instant?,
        resumeRunLimit: Double = RESUME_RUN_MAX_SECONDS,
    ): Stoppage {
        val ordered = measurementsAfter.filter { it.isAfter(inBedEnd) }.sorted()
        val base = classify(inBedEnd, ordered.firstOrNull(), earliestRetainedMeasurement)
        // Only a witnessed verdict can be wrong in the direction this walk exists to fix; re-deciding a
        // stop or an unknown from the same rows could only make the verdict less honest.
        if (base != Verdict.Witnessed || !(resumeRunLimit > 0)) {
            // The single-step rule's hole, when it has one, begins at the edge by construction.
            return if (base is Verdict.StoppedThenResumed) Stoppage(base, inBedEnd) else Stoppage(base, null)
        }

        var previous = inBedEnd
        for (m in ordered) {
            val gap = secondsBetween(previous, m)
            if (gap > CONTINUOUS_TOLERANCE_SECONDS) return Stoppage(Verdict.StoppedThenResumed(gap), previous)
            previous = m
            // The run carried on well past the edge: the night ended while the ring was still measuring.
            if (secondsBetween(inBedEnd, previous) > resumeRunLimit) return Stoppage(Verdict.Witnessed, null)
        }
        // The run reached the end of what we hold without breaking — unchanged from the single step.
        return Stoppage(Verdict.Witnessed, null)
    }

    /**
     * Whether a verdict is worth putting in front of the user: only [Verdict.StoppedThenResumed] longer
     * than [threshold] seconds. A witnessed edge has nothing to report and an unknown one nothing it can
     * support.
     */
    fun isMaterial(verdict: Verdict, threshold: Double = MATERIAL_GAP_SECONDS): Boolean =
        verdict is Verdict.StoppedThenResumed && verdict.seconds > threshold
}
