package io.github.opencircuit.ringkit

// Which instants the export's `coverageFraction` and the night's edge verdicts are allowed to count.
// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/ExportCoverageWitness.swift (@ b1c2fdd),
// whole.
//
// THE DEFECT THIS EXISTS FOR (measured upstream on a tester export). The witness used to be the
// persisted heart-rate STORE ROWS alone. The store is not the record set staging runs on — it is that
// set after the forward-only sync cursor, so one live auto-measure sample stamped late stranded every
// earlier epoch the ring delivered AFTERWARDS, and the file published `coverageFraction 0.7333, 4 gaps,
// longest 6851 s` while the 30 h epoch archive held 205 heart-rate epochs across the window with no hole.
// The number was a statement about OUR CURSOR wearing the costume of a statement about the ring.
//
// The archive alone is not the answer either: `EpochArchive.RETENTION` is ~30 h, so a night two days
// old is not in it, and an archive-only witness reports the whole night as a hole (retention read as
// absence). SO THE WITNESS IS THE UNION of the two — every instant in it is one we genuinely hold, so
// it can never invent coverage, and it is monotone against the store-only view: coverage can only rise
// and gaps only shrink, so no real hole is papered over.
//
// The same union answers the three EDGE probes (last measurement before the bedtime, first after the
// wake, oldest held), which read the same cursor-filtered store rows: `max` for the last-before and
// `min` for the first-after, so each reported edge gap can only shrink. The run of instants after the
// wake is NOT monotone (one extra record can open a walked gap) — see `Edges.measurementsAfterEnd`.
//
// Heart rate on both sides: `BulkRecord.heartRate` is one byte per worn epoch and excludes the idle
// (unworn / charging) template, so one HR instant IS one epoch we hold and a docked ring witnesses
// nothing. Archives are per ring and NEVER merged: the one with the most in-window epochs wins, ties
// keep the first.
//
// Shape notes: `widening` is `Double` seconds (Swift `TimeInterval`), defaulting to the archive's own
// retention; `epoch` is the sync epoch the record counters count from. Every list handed back is a
// read-only copy. `Edges` is built only by `edges`, as upstream's memberwise initializer is internal
// to its module; its count is a `Long` (Swift `Int`).

import java.time.Instant
import java.util.Collections
import java.util.TreeSet

object ExportCoverageWitness {

    /**
     * The instants `ExportCoverage.assess` should measure `[from, to]` over: the heart-rate epochs the
     * single most-covering ring archive holds inside the window (inclusive at both ends), followed by
     * [storedHeartRateTimes] — in that order, neither sorted nor deduplicated (`assess` dedupes
     * adjacent instants itself). A window that is not STRICTLY positive returns the store witness
     * alone. [archives] holds one entry per ring whose archive is still on disk.
     */
    fun sampleTimes(
        archives: List<List<BulkRecord>>,
        storedHeartRateTimes: List<Instant>,
        from: Instant,
        to: Instant,
        epoch: Long = Command.SYNC_EPOCH,
    ): List<Instant> {
        if (!(to > from)) return readOnlyCopy(storedHeartRateTimes)
        return readOnlyCopy(bestArchiveTimes(archives, Bound.At(from), Bound.At(to), epoch) + storedHeartRateTimes)
    }

    /**
     * The heart-rate instants the single most-covering ring archive holds inside `[from, to]`, in the
     * archive's own order. Strictly more in-window epochs wins, so ties keep the first archive. One
     * rule for both `sampleTimes` and `edges`, so the two cannot drift into two ideas of "the archive".
     */
    private fun bestArchiveTimes(archives: List<List<BulkRecord>>, from: Bound, to: Bound, epoch: Long): List<Instant> {
        var best: List<Instant> = emptyList()
        for (archive in archives) {
            val inWindow = archive.filter { it.heartRate != null }.map { it.date(epoch) }.filter { from.atOrBefore(it) && to.atOrAfter(it) }
            if (inWindow.size > best.size) best = inWindow
        }
        return best
    }

    /**
     * A window bound as a Swift `Date` holds it. [Unordered] is the `Date` of a NaN interval: Swift's
     * `>=` and `<=` on `Date` are the `Comparable` defaults `!(a < b)`, which are TRUE against NaN
     * (measured), so an unordered bound admits every instant.
     */
    private sealed interface Bound {
        data class At(val instant: Instant) : Bound
        data object Unordered : Bound

        /** `t >= this` in Swift. */
        fun atOrBefore(t: Instant): Boolean = this !is At || t >= instant

        /** `t <= this` in Swift. */
        fun atOrAfter(t: Instant): Boolean = this !is At || t <= instant
    }

    /** Swift's `t.addingTimeInterval(seconds)` as a bound: NaN is [Bound.Unordered]; past `Instant`'s range saturates. */
    private fun boundAt(t: Instant, seconds: Double): Bound = addingSeconds(t, seconds)?.let { Bound.At(it) } ?: Bound.Unordered

    /** The three acquisition instants a night's edge verdicts are computed from, plus which witness produced them. */
    class Edges internal constructor(
        /** The window these instants were probed around — carried so [coverage] cannot be built against a different pair. */
        val inBedStart: Instant,
        val inBedEnd: Instant,
        /** Latest heart-rate instant strictly BEFORE [inBedStart], from store ∪ archive. */
        val lastMeasurementBeforeStart: Instant?,
        /** Earliest heart-rate instant after [inBedEnd], from store ∪ archive (the store's own answer competes as given). */
        val firstMeasurementAfterEnd: Instant?,
        measurementsAfterEnd: List<Instant>,
        /** Oldest heart-rate instant we hold at all, from store ∪ archive — feeds the retention guards. */
        val earliestRetainedMeasurement: Instant?,
        /**
         * Heart-rate archive epochs inside the probed window (the night plus one widening each side),
         * from the one ring archive that had the most. 0 means no archive could speak here and the
         * instants above ARE the store's own answers.
         */
        val archiveEpochsInReach: Long,
        /** True when the archive supplied an instant the store did not — without it a gap would read wider, or no verdict at all. */
        val archiveMovedAnEdge: Boolean,
    ) {
        /**
         * EVERY heart-rate instant strictly after [inBedEnd] this probe could reach, ascending and
         * distinct, so the wake verdict can walk the run. NOT MONOTONE: one extra real record can open a
         * walked gap (`[+30]` is witnessed, `[+30, +400]` stopped-then-resumed), and a poorer archive
         * can be the louder one. A read-only copy.
         */
        val measurementsAfterEnd: List<Instant> = readOnlyCopy(measurementsAfterEnd)

        /** The `SleepConfidence.Coverage` these instants describe. */
        val coverage: SleepConfidence.Coverage
            get() = SleepConfidence.Coverage(
                inBedStart = inBedStart,
                inBedEnd = inBedEnd,
                lastMeasurementBeforeStart = lastMeasurementBeforeStart,
                firstMeasurementAfterEnd = firstMeasurementAfterEnd,
                measurementsAfterEnd = measurementsAfterEnd,
                earliestRetainedMeasurement = earliestRetainedMeasurement,
            )

        /**
         * One greppable token naming the witness ACTUALLY used, for a diagnostics line: `store`, or
         * `store+archive(N)` / `store+archive(N,moved)`. The failure it reports is silent — with no
         * archive loaded every probe degenerates to the store-only behaviour — so `store` on a night
         * the archive should still cover is the tell.
         */
        val witnessDescription: String
            get() = if (archiveEpochsInReach > 0) "store+archive($archiveEpochsInReach${if (archiveMovedAnEdge) ",moved" else ""})" else "store"

        // Swift's synthesized `Equatable`.
        override fun equals(other: Any?): Boolean =
            other is Edges && inBedStart == other.inBedStart && inBedEnd == other.inBedEnd &&
                lastMeasurementBeforeStart == other.lastMeasurementBeforeStart &&
                firstMeasurementAfterEnd == other.firstMeasurementAfterEnd &&
                measurementsAfterEnd == other.measurementsAfterEnd &&
                earliestRetainedMeasurement == other.earliestRetainedMeasurement &&
                archiveEpochsInReach == other.archiveEpochsInReach && archiveMovedAnEdge == other.archiveMovedAnEdge

        override fun hashCode(): Int =
            listOf(
                inBedStart, inBedEnd, lastMeasurementBeforeStart, firstMeasurementAfterEnd, measurementsAfterEnd,
                earliestRetainedMeasurement, archiveEpochsInReach, archiveMovedAnEdge,
            ).hashCode()

        override fun toString(): String =
            "Edges(inBedStart=$inBedStart, inBedEnd=$inBedEnd, lastMeasurementBeforeStart=$lastMeasurementBeforeStart, " +
                "firstMeasurementAfterEnd=$firstMeasurementAfterEnd, measurementsAfterEnd=$measurementsAfterEnd, " +
                "earliestRetainedMeasurement=$earliestRetainedMeasurement, archiveEpochsInReach=$archiveEpochsInReach, " +
                "archiveMovedAnEdge=$archiveMovedAnEdge)"
    }

    /**
     * Resolve a night's three edge instants from the store's answers UNIONED with the epoch archive.
     * [storedLastBeforeStart] / [storedFirstAfterEnd] / [storedEarliestRetained] are the store's
     * latest heart-rate sample before the bedtime, earliest after the wake, and earliest at all.
     *
     * The archive is consulted over the night widened by [widening] seconds on each side (normalised,
     * so an inverted or degenerate window still probes a real span). The widening is the archive's own
     * retention horizon, NOT a number chosen here: a record further from an edge than that is the
     * archive's pruning boundary, not a neighbour of the edge, and reading it as "the ring resumed
     * here" would report our retention as a recording gap. The retention guards are preserved: the
     * earliest is unioned with `min`, so it reaches further back only when the archive really holds a
     * record that old. Per-ring tie-break as [sampleTimes]; never merged across rings.
     */
    fun edges(
        archives: List<List<BulkRecord>>,
        storedLastBeforeStart: Instant?,
        storedFirstAfterEnd: Instant?,
        storedEarliestRetained: Instant?,
        inBedStart: Instant,
        inBedEnd: Instant,
        widening: Double = EpochArchive.RETENTION.seconds.toDouble(),
        epoch: Long = Command.SYNC_EPOCH,
    ): Edges {
        val low = boundAt(minOf(inBedStart, inBedEnd), -widening)
        val high = boundAt(maxOf(inBedStart, inBedEnd), widening)
        val inReach = bestArchiveTimes(archives, low, high, epoch)

        // `max` / `min` over the candidates that exist: each reported gap can only shrink, and a nil
        // store answer never wins. The store's first-after-end competes as given (upstream does not
        // filter it here), but joins the run below only when strictly after the edge.
        val last = listOfNotNull(storedLastBeforeStart, inReach.filter { it < inBedStart }.maxOrNull()).maxOrNull()
        val first = listOfNotNull(storedFirstAfterEnd, inReach.filter { it > inBedEnd }.minOrNull()).minOrNull()
        val earliest = listOfNotNull(storedEarliestRetained, inReach.minOrNull()).minOrNull()

        // Upstream's `Set(…).sorted()`: distinct instants, ascending.
        val afterEnd = TreeSet<Instant>().apply {
            inReach.filterTo(this) { it > inBedEnd }
            if (storedFirstAfterEnd != null && storedFirstAfterEnd > inBedEnd) add(storedFirstAfterEnd)
        }

        return Edges(
            inBedStart = inBedStart,
            inBedEnd = inBedEnd,
            lastMeasurementBeforeStart = last,
            firstMeasurementAfterEnd = first,
            measurementsAfterEnd = afterEnd.toList(),
            earliestRetainedMeasurement = earliest,
            archiveEpochsInReach = inReach.size.toLong(),
            archiveMovedAnEdge = last != storedLastBeforeStart || first != storedFirstAfterEnd || earliest != storedEarliestRetained,
        )
    }

    private fun <T> readOnlyCopy(xs: List<T>): List<T> = Collections.unmodifiableList(ArrayList(xs))
}
