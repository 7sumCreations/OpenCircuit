package io.github.opencircuit.ringkit

// WHICH MINUTES OF A NIGHT THE RING ACTUALLY RECORDED. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/MeasuredCoverage.swift:29-302 (@ b1c2fdd).
//
// The set M of instants covered by at least one epoch record: for every record at time t, the
// half-open span [t, t + epochSeconds). Merged, sorted, and queryable.
//
// WHY THIS EXISTS. Upstream's sleep-edit recompute took no record timestamps, so it could not tell
// a window extension over dense data from one over four hours of nothing, and treated both as
// sleep. This type is the missing input — a pure value with no dependency on the store, the
// archive or Health Connect, so the app, a test built from raw bytes and a replay harness all agree
// by construction.
//
// EPOCH LENGTH. 🟢 Measured upstream on two tester nights: the median inter-record delta is exactly
// 150.0 s. [DEFAULT_EPOCH_SECONDS] mirrors `BulkRecord.EPOCH_SECONDS`.
//
// THE GENEROSITY RULE. Coverage is always computed from the WIDEST record set available, not the
// night's slice, so every minute called unmeasured is unmeasured under the app's own best case.
//
// ⚠️ AND THAT IS NOT ENOUGH ON ITS OWN — see [trusted]. The record set is a RETAINED set (the
// archive keeps ~30 h). Absence inside it is evidence of absence only where the set could have held
// the records; elsewhere the answer is UNKNOWN, and this type says so.
//
// Shape notes: Swift's `Range<Date>` is the half-open [DateInterval]; `.distantPast` is
// `Instant.MIN`; the two extra Swift initializers are the factories [ofRecordDates] / [ofRecords];
// `partition`'s `(range:, ground:)` tuple is [Piece]; `TimeInterval` results are `Duration`.

import java.time.Duration
import java.time.Instant

/** The set of instants a ring's epoch records cover, as merged half-open intervals. */
class MeasuredCoverage(
    intervals: List<DateInterval>,
    /**
     * The earliest instant this coverage set is ENTITLED TO CALL UNMEASURED. Everything before it is
     * [Ground.UNKNOWN]: our records do not reach back that far, so their silence there proves
     * nothing. [Instant.MIN] (the default) means "no horizon established" — what a raw, un-[trusted]
     * set carries, and why production callers must go through [trusted].
     */
    val provenFrom: Instant = Instant.MIN,
) {
    /** Merged, ascending, non-overlapping, non-touching `[start, end)` spans. */
    val intervals: List<DateInterval> = merge(intervals)

    companion object {
        /** The production epoch cadence — one `0x4c` record every 150 s. */
        val DEFAULT_EPOCH_SECONDS: Duration = Duration.ofSeconds(BulkRecord.EPOCH_SECONDS.toLong())

        /**
         * Nothing was recorded anywhere. Distinct from a null coverage at a call site, which means
         * "do not perform a coverage test at all" (the kill switch).
         */
        val EMPTY = MeasuredCoverage(emptyList())

        /** Build from record timestamps: each record covers `[t, t + epochSeconds)`. */
        fun ofRecordDates(recordDates: List<Instant>, epochSeconds: Duration = DEFAULT_EPOCH_SECONDS): MeasuredCoverage {
            if (epochSeconds.isNegative || epochSeconds.isZero) return MeasuredCoverage(emptyList())
            return MeasuredCoverage(recordDates.map { DateInterval(it, it.plus(epochSeconds)) })
        }

        /**
         * Build from decoded epoch records — the one-liner every production caller wants. [epoch] is
         * the sync epoch the counters are relative to, the same default `BulkSleep` uses.
         */
        fun ofRecords(
            records: List<BulkRecord>,
            epoch: Long = Command.SYNC_EPOCH,
            epochSeconds: Duration = DEFAULT_EPOCH_SECONDS,
        ): MeasuredCoverage = ofRecordDates(records.map { it.date(epoch) }, epochSeconds)

        /**
         * Recover the coverage decision already recorded in a stored hypnogram: everything the
         * hypnogram LABELS as having measurement underneath it.
         *
         * ⚠️ Named for its INPUT on purpose: this reads back provenance LABELS some earlier call
         * derived from records. A coverage built from records can discover a hole; one built from
         * labels can only repeat one — never quote a hole statistic derived from this.
         *
         * ⚠️ It returns [ProvenanceLabelCoverage], which has NO [trusted] — applied to labels, the
         * retention guard would re-read every leading hole the records had PROVEN empty as UNKNOWN
         * and publish it as sleep (upstream measured exactly that). The wrong call cannot compile.
         *
         * Null when the hypnogram carries NO proven-unmeasured span at all — "fully covered" and
         * "written before provenance existed" look the same, so the caller keeps its old behaviour.
         */
        fun fromProvenanceLabels(segments: List<SleepSegment>): ProvenanceLabelCoverage? {
            if (segments.none { it.provenance.isProvenUnmeasured }) return null
            val covered = segments
                .filter { !it.provenance.isProvenUnmeasured && it.end.isAfter(it.start) }
                .map { DateInterval(it.start, it.end) }
            return ProvenanceLabelCoverage(covered)
        }

        /**
         * Sort and coalesce. Touching spans (`a.end == b.start`) merge too: consecutive 150 s epochs
         * are exactly touching, and leaving them separate would make [partition] emit hundreds of
         * zero-length "gaps" for a perfectly continuous night.
         */
        private fun merge(raw: List<DateInterval>): List<DateInterval> {
            val sorted = raw.filter { it.end.isAfter(it.start) }.sortedBy { it.start }
            val out = ArrayList<DateInterval>(sorted.size)
            for (iv in sorted) {
                val last = out.lastOrNull()
                if (last != null && !iv.start.isAfter(last.end)) {
                    out[out.size - 1] = DateInterval(last.start, maxOf(last.end, iv.end))
                } else {
                    out += iv
                }
            }
            return out
        }
    }

    val isEmpty: Boolean get() = intervals.isEmpty()

    // --- The retention guard ---

    /** The first instant any record covers, or null when nothing is covered. */
    val earliestCovered: Instant? get() = intervals.firstOrNull()?.start

    /** The last instant any record covers, or null when nothing is covered. */
    val latestCovered: Instant? get() = intervals.lastOrNull()?.end

    /**
     * WHETHER THIS RECORD SET IS ENTITLED TO CALL ANY PART OF [window] UNMEASURED — the guard that
     * stops RETENTION being read as ABSENCE. (Upstream measured the defect it closes: editing a
     * fully-recorded night after its records aged out of the archive published 0.0 asleep minutes
     * where the shipped build published 403.0.)
     *
     * 1. AN ALL-EMPTY WINDOW IS UNKNOWN (null): a night we hold no records for and a night the ring
     *    slept through are the same bytes.
     * 2. GROUND OLDER THAN OUR OLDEST RECORD IS UNKNOWN ([provenFrom]): retention prunes oldest
     *    first, so a record at t proves nothing about what came before the first record we hold.
     *
     * The TRAILING edge is deliberately NOT guarded: retention never removes the newest records,
     * and a wake time dragged hours past where recording stopped is the archetypal invented-sleep
     * case.
     *
     * Returns a copy carrying the proof horizon, or null meaning UNKNOWN — the caller must then
     * behave exactly as it did before provenance existed (the same value the kill switch passes).
     */
    fun trusted(window: DateInterval): MeasuredCoverage? {
        if (!window.end.isAfter(window.start)) return null
        if (measuredPortions(window).isEmpty()) return null // rule 1
        val earliest = earliestCovered ?: return null
        return MeasuredCoverage(intervals, provenFrom = earliest) // rule 2
    }

    /** What we can say about one stretch of ground. */
    enum class Ground {
        /** Records cover it. */
        MEASURED,

        /** No record covers it AND our record set reaches back past it — a proven hole. */
        UNMEASURED,

        /** No record covers it and our record set cannot reach back that far. Absence of evidence. */
        UNKNOWN,
    }

    /** One tile of [partition]: a span and what we can say about it. */
    data class Piece(val range: DateInterval, val ground: Ground)

    /** Total measured time inside [range]. */
    fun measuredDuration(range: DateInterval): Duration =
        measuredPortions(range).fold(Duration.ZERO) { acc, p -> acc.plus(p.duration) }

    /**
     * Fraction of [range] that is measured, 0…1. Returns 0 for an empty range — callers that must
     * tell "no coverage" from "no range" should check the range first.
     */
    fun fraction(range: DateInterval): Double {
        val total = range.duration.secondsDouble()
        if (total <= 0) return 0.0
        return measuredDuration(range).secondsDouble() / total
    }

    /** The measured sub-spans of [range], ascending. */
    fun measuredPortions(range: DateInterval): List<DateInterval> {
        if (!range.end.isAfter(range.start)) return emptyList()
        val out = ArrayList<DateInterval>()
        for (iv in intervals) {
            if (!iv.end.isAfter(range.start)) continue
            if (!iv.start.isBefore(range.end)) break
            val lo = maxOf(iv.start, range.start)
            val hi = minOf(iv.end, range.end)
            if (hi.isAfter(lo)) out += DateInterval(lo, hi)
        }
        return out
    }

    /** The UNMEASURED sub-spans of [range], ascending — the complement of [measuredPortions]. */
    fun unmeasuredPortions(range: DateInterval): List<DateInterval> {
        if (!range.end.isAfter(range.start)) return emptyList()
        val out = ArrayList<DateInterval>()
        var cursor = range.start
        for (m in measuredPortions(range)) {
            if (m.start.isAfter(cursor)) out += DateInterval(cursor, m.start)
            cursor = maxOf(cursor, m.end)
        }
        if (cursor.isBefore(range.end)) out += DateInterval(cursor, range.end)
        return out
    }

    /** The longest single unmeasured run inside [range]. Zero when fully covered. */
    fun longestGap(range: DateInterval): Duration =
        unmeasuredPortions(range).maxOfOrNull { it.duration } ?: Duration.ZERO

    /**
     * Cut [range] into ascending, contiguous, non-empty pieces each tagged with what we can say
     * about it. The pieces always tile [range] exactly — any hole in the tiling would silently drop
     * time off a night.
     *
     * A gap earlier than [provenFrom] is [Ground.UNKNOWN], and a gap that STRADDLES the horizon is
     * split at it: the part we can vouch for is UNMEASURED, the part we cannot is UNKNOWN. With the
     * default [provenFrom] nothing is unknown.
     */
    fun partition(range: DateInterval): List<Piece> {
        if (!range.end.isAfter(range.start)) return emptyList()
        val out = ArrayList<Piece>()
        fun addGap(lo: Instant, hi: Instant) {
            if (!hi.isAfter(lo)) return
            if (provenFrom.isAfter(lo)) {
                val cut = minOf(provenFrom, hi)
                out += Piece(DateInterval(lo, cut), Ground.UNKNOWN)
                if (hi.isAfter(cut)) out += Piece(DateInterval(cut, hi), Ground.UNMEASURED)
            } else {
                out += Piece(DateInterval(lo, hi), Ground.UNMEASURED)
            }
        }
        var cursor = range.start
        for (m in measuredPortions(range)) {
            addGap(cursor, m.start)
            out += Piece(m, Ground.MEASURED)
            cursor = m.end
        }
        addGap(cursor, range.end)
        return out
    }

    override fun equals(other: Any?): Boolean =
        other is MeasuredCoverage && intervals == other.intervals && provenFrom == other.provenFrom

    override fun hashCode(): Int = 31 * intervals.hashCode() + provenFrom.hashCode()

    override fun toString(): String = "MeasuredCoverage(intervals=$intervals, provenFrom=$provenFrom)"
}

/**
 * A coverage decision RECOVERED FROM STORED PROVENANCE LABELS — deliberately NOT a
 * [MeasuredCoverage], because it is not built from records and must never be treated as if it were.
 *
 * ⚠️ IT HAS NO `trusted`, AND THAT IS THE ENTIRE REASON THIS TYPE EXISTS: applied to labels, the
 * retention guard's "oldest record" silently becomes "the first non-hole label", which sits AFTER
 * any hole at the start of a night — so a proven-empty hour would be re-read as UNKNOWN and
 * published as sleep. The wrong value can no longer be expressed. Build one only through
 * [MeasuredCoverage.fromProvenanceLabels].
 */
class ProvenanceLabelCoverage internal constructor(covered: List<DateInterval>) {

    // provenFrom stays Instant.MIN: a label-derived set has no proof horizon, so partition reports
    // every gap as UNMEASURED — repeating the verdict the records-based call already reached.
    private val coverage = MeasuredCoverage(covered)

    /** Cut [range] into ascending, contiguous pieces tagged with what the LABELS say. Never UNKNOWN. */
    fun partition(range: DateInterval): List<MeasuredCoverage.Piece> = coverage.partition(range)
}

/** A duration in (fractional) seconds, as Swift's `TimeInterval`. */
private fun Duration.secondsDouble(): Double = seconds + nano / 1e9
