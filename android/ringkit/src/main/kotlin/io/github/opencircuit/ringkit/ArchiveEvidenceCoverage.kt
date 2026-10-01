package io.github.opencircuit.ringkit

// Does the union of a diagnostics export's per-drain raw-record blobs actually cover the epochs
// the app HOLDS? Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/ArchiveEvidenceCoverage.swift:23-74 (@ b1c2fdd).
//
// The check this replaces compared two numbers built from the same array, so it could never
// disagree; and the evidence list is a bounded ring buffer, so epochs from a drain whose row has
// been dropped appear in no blob at all. Upstream measured the consequence on a Gen 3 tester's
// export: an apparent 35-minute "hole" that was in the diagnostic, not in the data. This lets an
// export state its own completeness instead of leaving the reader to assume it.

object ArchiveEvidenceCoverage {

    data class Report(
        /** Distinct epochs in the app's own rolling archive — what staging actually ran on. */
        val archiveRecordCount: Int,
        /** Distinct epochs recoverable from the union of the evidence blobs. */
        val evidenceRecordCount: Int,
        /**
         * Archive epoch counters (unsigned 32-bit, in a `Long`) that NO evidence blob carries —
         * exactly what a blob-only replay would be blind to. Ascending.
         */
        val missingFromEvidence: List<Long>,
        /** Longest run of consecutive missing epochs, in seconds. 0 when nothing is missing. */
        val longestMissingRunSeconds: Int,
    ) {
        /** The blobs cover everything the app holds, so a replay from this export is faithful. */
        val isComplete: Boolean get() = missingFromEvidence.isEmpty()
    }

    /**
     * Compare what the app holds against what the export's blobs carry. Both sides are deduped by
     * epoch counter first — the blobs overlap whenever a drain re-hydrates banked records.
     */
    fun report(archive: List<BulkRecord>, evidence: List<BulkRecord>): Report {
        val archiveCounters = archive.mapTo(HashSet()) { it.counter }
        val evidenceCounters = evidence.mapTo(HashSet()) { it.counter }
        val missing = (archiveCounters - evidenceCounters).sorted()
        return Report(
            archiveRecordCount = archiveCounters.size,
            evidenceRecordCount = evidenceCounters.size,
            missingFromEvidence = missing,
            longestMissingRunSeconds = longestRunSeconds(missing),
        )
    }

    /**
     * Longest consecutive run in [counters] (ascending), in seconds. "Consecutive" means one epoch
     * apart with a tolerance, because the ring's cadence drifts a second or two (152 s is common
     * on Gen 3).
     */
    private fun longestRunSeconds(counters: List<Long>): Int {
        val first = counters.firstOrNull() ?: return 0
        val tolerance = 10
        val epoch = BulkRecord.EPOCH_SECONDS.toLong()
        var best = epoch
        var runStart = first
        var previous = first
        for (c in counters.drop(1)) {
            if (c - previous <= epoch + tolerance) {
                best = maxOf(best, c - runStart + epoch)
            } else {
                runStart = c
            }
            previous = c
        }
        return best.toInt()
    }
}
