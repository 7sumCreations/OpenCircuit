package io.github.opencircuit.ringkit

// Coverage measured against a wake the recording did not define. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/ExportReferenceCoverage.swift (@ b1c2fdd), whole.
//
// THE DEFECT THIS EXISTS FOR. `ExportCoverage.assess` is handed the night's REPORTED in-bed window.
// On every night nobody corrected, that window's right edge IS the last record, so a night that ends
// early because the records stopped has its denominator shortened by exactly the thing it should be
// reporting: `coverageFraction` comes out at ~1.0 with no gaps, and no truncation at the trailing edge
// can ever move it. This adds a SECOND measurement of the same records over a window whose right edge
// came from somewhere the recording had no vote in — the wearer's manual sleep-schedule wake — so a
// hole at the trailing edge is inside the window and the fraction can fall.
//
// It is a REFERENCE, not a truth: a scheduled wake is when the wearer INTENDS to get up, so the
// reference and its end travel with the number, the signed `beyondReportedEndSeconds` is published
// beside it, and nothing is gated on it. It never reaches past the present (`reference(…)` clamps a
// wake still ahead of the export instant to that instant), only the right edge moves (the left stays
// at the reported start, so an ordinary late bedtime is not reported as a hole), and no reference is
// invented: with no schedule there is nothing to measure, and the export says so.
//
// Shape notes: `Outcome` is a sealed type (`Measured` / `Unavailable`); the reason tokens are string
// constants on it, compared exactly. `Row` is built only by `assess`, as upstream's memberwise
// initializer is internal to its module.

import java.time.Instant

object ExportReferenceCoverage {

    /**
     * Where the wake instant came from. A closed set on purpose: every case names something a human
     * supplied or an observation made, never a value this app chose. [rawValue] is the export's token.
     */
    enum class Reference(val rawValue: String) {
        /** The wake time in the wearer's own manual sleep schedule, resolved for the night. */
        MANUAL_SCHEDULE_WAKE("manualScheduleWake"),

        /**
         * The same schedule wake, but it lay in the FUTURE, so the window was closed at the export
         * instant instead: the fraction can still see a hole that has already opened and can never
         * report one that has not.
         */
        MANUAL_SCHEDULE_WAKE_SO_FAR("manualScheduleWakeSoFar"),
        ;

        companion object {
            /** The case whose [rawValue] is exactly [rawValue] (case-sensitive, as Swift's `init(rawValue:)`), or null. */
            fun fromRawValue(rawValue: String): Reference? = entries.firstOrNull { it.rawValue == rawValue }
        }
    }

    /** One night's coverage against an external wake. Built by [assess]. */
    class Row internal constructor(
        val reference: Reference,
        /** The instant the window was closed at — the reference wake, or the export instant (see [reference]). */
        val referenceEnd: Instant,
        /**
         * `referenceEnd − reportedEnd` in seconds. POSITIVE when the reference reaches past where the
         * reported window closed — the only sign under which this row can falsify anything. Negative
         * means the fraction below is over a SHORTER span than the reported-window coverage and is not
         * comparable with it; published signed so the row is never dropped on the nights it flatters us.
         */
        val beyondReportedEndSeconds: Double,
        /** The measurement itself, over `[reportedStart, referenceEnd]`. */
        val assessment: ExportCoverage.Assessment,
    ) {
        // Swift's synthesized `Equatable`: the double compares by IEEE `==`.
        override fun equals(other: Any?): Boolean =
            other is Row && reference == other.reference && referenceEnd == other.referenceEnd &&
                beyondReportedEndSeconds == other.beyondReportedEndSeconds && assessment == other.assessment

        override fun hashCode(): Int = listOf(reference, referenceEnd, ieeeHash(beyondReportedEndSeconds), assessment).hashCode()

        override fun toString(): String =
            "Row(reference=$reference, referenceEnd=$referenceEnd, beyondReportedEndSeconds=$beyondReportedEndSeconds, assessment=$assessment)"
    }

    /**
     * What the export has to say about the second measurement for one night. [Unavailable] is
     * EMITTED, not omitted: absence would be ambiguous with an export written before the key existed,
     * and the key exists so a reader can tell "nothing is wrong" from "nothing could be checked".
     */
    sealed interface Outcome {
        data class Measured(val row: Row) : Outcome

        /** No wake reference the recording did not define was available. [reason] is a stable machine token, not display copy. */
        data class Unavailable(val reason: String) : Outcome

        companion object {
            /** The wearer has not enabled a manual sleep schedule; nothing is invented in its place. */
            const val NO_MANUAL_SLEEP_SCHEDULE = "noManualSleepSchedule"

            /**
             * A schedule exists but resolved to a wake at or before the night's reported start, so
             * there is no window to measure (also a degenerate bed == wake schedule, and a night still
             * in progress whose bedtime is already past the export instant).
             */
            const val REFERENCE_NOT_AFTER_BEDTIME = "referenceNotAfterBedtime"
        }
    }

    /** Upstream's `(end: Date, reference: Reference)` tuple. */
    data class ReferenceEnd(val end: Instant, val reference: Reference)

    /**
     * Bound a scheduled wake to an instant that has actually arrived, and name which of the two it
     * turned out to be: a wake STRICTLY after [asOf] is clamped to [asOf] ([Reference.MANUAL_SCHEDULE_WAKE_SO_FAR]);
     * one at or before it stands. THE ONLY PLACE THE FUTURE IS REFUSED. Null when there is no
     * scheduled wake at all — the caller must then say so rather than invent a denominator.
     */
    fun reference(forScheduledWake: Instant?, asOf: Instant): ReferenceEnd? {
        val wake = forScheduledWake ?: return null
        return if (wake > asOf) ReferenceEnd(asOf, Reference.MANUAL_SCHEDULE_WAKE_SO_FAR) else ReferenceEnd(wake, Reference.MANUAL_SCHEDULE_WAKE)
    }

    /**
     * Measure [sampleTimes] over `[reportedStart, referenceEnd]`. [sampleTimes] must be the SAME
     * witness the reported-window assessment counted (`ExportCoverageWitness.sampleTimes`), or this
     * reports our own forward-only sync cursor as the ring's recording. [reportedEnd] is used only for
     * [Row.beyondReportedEndSeconds]. [referenceEnd] must already be bounded to an instant that has
     * arrived (see [reference]) — this holds no clock, so that is the caller's contract.
     *
     * Null when [referenceEnd] is not STRICTLY after [reportedStart]: a non-positive window has no
     * denominator, and reporting 0 for it would state a total outage.
     */
    fun assess(
        sampleTimes: List<Instant>,
        reportedStart: Instant,
        reportedEnd: Instant,
        referenceEnd: Instant,
        reference: Reference,
    ): Row? {
        if (referenceEnd <= reportedStart) return null
        return Row(
            reference = reference,
            referenceEnd = referenceEnd,
            // Swift's `timeIntervalSince` subtracts the two dates' seconds-since-2001 doubles; the exact
            // duration differs in the last digits for millisecond-stamped instants, and the export
            // prints this number with 17 significant digits (measured: 10800.333000183105, not 10800.333).
            beyondReportedEndSeconds = FoundationText.referenceSeconds(referenceEnd) - FoundationText.referenceSeconds(reportedEnd),
            assessment = ExportCoverage.assess(sampleTimes, from = reportedStart, to = referenceEnd),
        )
    }
}
