package io.github.opencircuit.ringkit

// PARTIAL port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/ExportEngine.swift (@ b1c2fdd):
// only `SleepEdgeProvenanceRow` (`:354-427`), the per-night edge-provenance row, which the sleep
// confidence tests already exercise (the threshold has to travel from the assessment into the row).
// The export engine itself — CSV / JSON serialization, the other row types, schema v3 — is ported by
// the export epic, which grows this object; see PORTING.md.

import java.time.Instant
import java.util.Collections

object ExportEngine {

    /**
     * The acquisition verdict on ONE night's two edges, in a form a tester bundle can carry. It is the
     * CLASSIFIER'S reason list, not "what the card showed" — a card applies its own render guards, so
     * this list is an upper bound on what a wearer saw. Measured at the RECORDED (detector) edges, not
     * the edited ones.
     *
     * [windowStart], [windowEnd]: the window the verdicts were measured against. [bedtimeVerdict] /
     * [wakeVerdict]: the wire names (`SleepConfidence.exportName`). [bedtimeGapSeconds] /
     * [wakeGapSeconds]: seconds of silence before / after the window, or null when the verdict carries
     * none — ABSENT rather than 0, because `witnessed` and `unknown` are different claims.
     * [reasons]: the wire name of every reason the classifier produced, in its order (`[]` = nothing to
     * say, the common case). [materialGapSeconds]: the threshold in force when those reasons were
     * produced. [durationBasis]: which night's totals the duration half of [reasons] was computed from —
     * [DURATION_BASIS_RECORDED] or [DURATION_BASIS_EDITED].
     *
     * Compares as Swift's synthesized `Equatable`: `Double` fields by IEEE `==`.
     */
    class SleepEdgeProvenanceRow(
        val windowStart: Instant,
        val windowEnd: Instant,
        val bedtimeVerdict: String,
        val bedtimeGapSeconds: Double?,
        val wakeVerdict: String,
        val wakeGapSeconds: Double?,
        reasons: List<String>,
        val materialGapSeconds: Double,
        val durationBasis: String = DURATION_BASIS_RECORDED,
    ) {
        /** A read-only copy of the list passed in. */
        val reasons: List<String> = Collections.unmodifiableList(ArrayList(reasons))

        /**
         * Build the row from an assessment measured over `[windowStart, windowEnd]`. Takes the
         * assessment rather than re-deriving the verdicts, and takes the threshold OFF the assessment,
         * so a caller sweeping the cut can never export the default beside reasons produced at another.
         */
        constructor(
            windowStart: Instant,
            windowEnd: Instant,
            assessment: SleepConfidence.Assessment,
            durationBasis: String = DURATION_BASIS_RECORDED,
        ) : this(
            windowStart = windowStart,
            windowEnd = windowEnd,
            bedtimeVerdict = SleepConfidence.exportName(assessment.bedtime),
            bedtimeGapSeconds = SleepConfidence.gapSeconds(assessment.bedtime),
            wakeVerdict = SleepConfidence.exportName(assessment.wake),
            wakeGapSeconds = SleepConfidence.gapSeconds(assessment.wake),
            reasons = assessment.reasons.map { SleepConfidence.exportName(it) },
            materialGapSeconds = assessment.materialGapSeconds,
            durationBasis = durationBasis,
        )

        override fun equals(other: Any?): Boolean =
            other is SleepEdgeProvenanceRow && windowStart == other.windowStart && windowEnd == other.windowEnd &&
                bedtimeVerdict == other.bedtimeVerdict && ieeeEquals(bedtimeGapSeconds, other.bedtimeGapSeconds) &&
                wakeVerdict == other.wakeVerdict && ieeeEquals(wakeGapSeconds, other.wakeGapSeconds) &&
                reasons == other.reasons && materialGapSeconds == other.materialGapSeconds && durationBasis == other.durationBasis

        override fun hashCode(): Int =
            listOf(
                windowStart, windowEnd, bedtimeVerdict, bedtimeGapSeconds?.let(::ieeeHash), wakeVerdict,
                wakeGapSeconds?.let(::ieeeHash), reasons, ieeeHash(materialGapSeconds), durationBasis,
            ).hashCode()

        override fun toString(): String =
            "SleepEdgeProvenanceRow(windowStart=$windowStart, windowEnd=$windowEnd, bedtimeVerdict=$bedtimeVerdict, " +
                "bedtimeGapSeconds=$bedtimeGapSeconds, wakeVerdict=$wakeVerdict, wakeGapSeconds=$wakeGapSeconds, " +
                "reasons=$reasons, materialGapSeconds=$materialGapSeconds, durationBasis=$durationBasis)"

        companion object {
            /** [durationBasis] for a verdict computed on the RECORDED night's totals — the edges' own frame. */
            const val DURATION_BASIS_RECORDED: String = "recorded"

            /** [durationBasis] for a verdict computed on POST-EDIT totals, emitted only where the recorded timeline is missing. */
            const val DURATION_BASIS_EDITED: String = "edited"

            private fun ieeeEquals(a: Double?, b: Double?): Boolean =
                if (a == null || b == null) a == null && b == null else a == b
        }
    }
}
