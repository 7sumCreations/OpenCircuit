package io.github.opencircuit.ringkit

// A NIGHT SCORED AGAINST A SHORTER ARCHIVE MUST NOT KEEP A HOLE THE ARCHIVE HAS SINCE FILLED. Port of
// upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/SleepProvenanceRederivation.swift (@ b1c2fdd).
//
// Upstream measured the defect on a tester night: she corrected her wake when the newest record was
// 02:42:47, so the edit scored the whole span after it as asserted (a proven hole, 14323 s). Nine more
// records (02:48:53 → 03:11:23, contiguous at 150 s) arrived within the hour — 1350 s of the "proven
// hole" was measurable data — and nothing re-derived the label: an edited row is never overwritten by a
// re-drain, and label-recovered coverage can only repeat a hole.
//
// WHAT THIS DOES, AND THE ONE DIRECTION IT MOVES IN. Given a stored hypnogram and a FRESHER record set,
// it re-labels PROVEN-UNMEASURED spans that now have records under them — and nothing else. It never
// moves an edge, never changes a stage, never touches a span the user's edit placed, and can only ever
// move a label toward MORE measurement. That asymmetry is the whole safety argument: this pass consumes
// PRESENCE only. Retention can delete records; it cannot invent them. So a shrink (the retention-read-
// as-absence failure) is unreachable through this code.

object SleepProvenanceRederivation {

    /**
     * Re-label a stored night's asserted spans against a record set that has GROWN since they were
     * scored. Extend-only: asserted becomes asserted-over-measured exactly where records now exist,
     * split at the coverage boundaries; every other segment is returned untouched, in input order.
     *
     * Asserted-over-measured, not measured: the STAGE is still the wearer's — the label the edit path
     * would have produced had it run with today's archive.
     *
     * [coverage]: a RAW coverage built from record timestamps. A trusted one is safe too — its UNKNOWN
     * ground leaves the stored label exactly as it was.
     *
     * Returns the upgraded segments, or null when NOTHING changed — so a caller persists (and re-writes
     * the health store) only on a real change, and a second run over the same archive is a no-op.
     */
    fun upgraded(segments: List<SleepSegment>, coverage: MeasuredCoverage): List<SleepSegment>? {
        if (coverage.isEmpty) return null
        val out = ArrayList<SleepSegment>(segments.size)
        var changed = false

        for (segment in segments) {
            if (!(segment.provenance.isProvenUnmeasured && segment.end.isAfter(segment.start))) {
                out += segment
                continue
            }
            val pieces = coverage.partition(DateInterval(segment.start, segment.end))
            if (pieces.none { it.ground == MeasuredCoverage.Ground.MEASURED }) {
                out += segment // still a hole under today's records — leave it alone
                continue
            }
            changed = true
            // `partition` tiles the span exactly, so re-emitting the pieces preserves the segment's
            // start, end and stage to the nanosecond: no minute can be dropped or duplicated.
            for (piece in pieces) {
                val provenance = when (piece.ground) {
                    MeasuredCoverage.Ground.MEASURED -> SleepProvenance.ASSERTED_OVER_MEASURED
                    MeasuredCoverage.Ground.UNMEASURED, MeasuredCoverage.Ground.UNKNOWN -> segment.provenance
                }
                out += SleepSegment(piece.range.start, piece.range.end, segment.stage, provenance)
            }
        }
        return if (changed) out else null
    }

    /**
     * Asleep seconds this upgrade moves out of the asserted bucket — the quantity to breadcrumb; 0 when
     * nothing moved. Computed from the two breakdowns, so it can never disagree with the stored numbers.
     */
    fun upgradedAsleepSeconds(before: List<SleepSegment>, after: List<SleepSegment>): Double {
        val b = SleepProvenanceBreakdown(before)
        val a = SleepProvenanceBreakdown(after)
        return swiftMax(0.0, b.assertedAsleep - a.assertedAsleep)
    }
}
