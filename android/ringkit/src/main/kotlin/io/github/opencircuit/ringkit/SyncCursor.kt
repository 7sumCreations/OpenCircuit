package io.github.opencircuit.ringkit

// Per-metric sync bookkeeping: remembers the newest record written for each metric so re-syncs
// only push newer samples. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/SyncCursor.swift:7-57 (@ b1c2fdd).
//
// WATERMARK INVARIANT. The cursor moves only through [advance] and [selectNew], and only forward.
// Every other member is a pure query. [selectNewStaged] works on a copy, so a caller can persist
// the fresh samples first and adopt the advanced cursor only once that save has succeeded; a
// failed save leaves the cursor where it was and the same samples are retried next time.
//
// OWNERSHIP. Upstream is a Swift `mutating struct` (every assignment copies). Here it is a mutable
// class with a single owner: sharing the reference shares the watermark. Take [copy] for an
// independent snapshot. The constructor copies the map it is given, so the caller's map is never
// aliased. Swift's `Codable` conformance is not ported; the stored form belongs to the
// persistence layer.

import java.time.Instant

class SyncCursor(lastByKind: Map<String, Instant> = emptyMap()) {

    /** Keyed by [MetricKind.rawValue], the stable persistence key. */
    private val lastByKind: MutableMap<String, Instant> = HashMap(lastByKind)

    /** Newest record timestamp written for [kind], or null if never synced. */
    fun last(kind: MetricKind): Instant? = lastByKind[kind.rawValue]

    /** True if [date] is strictly newer than what has been synced for [kind]. */
    fun isNew(kind: MetricKind, date: Instant): Boolean {
        val last = lastByKind[kind.rawValue] ?: return true
        return date > last
    }

    /** Move the cursor forward to [to] (never backward). */
    fun advance(kind: MetricKind, to: Instant) {
        if (isNew(kind, to)) lastByKind[kind.rawValue] = to
    }

    /**
     * Keep only samples newer than the cursor, then advance past the newest kept. Returns the
     * to-write subset, sorted by start. Moves this cursor.
     */
    fun selectNew(samples: List<QuantitySample>): List<QuantitySample> {
        val fresh = samples.filter { isNew(it.kind, it.start) }.sortedBy { it.start }
        for (s in fresh) advance(s.kind, s.start)
        return fresh
    }

    /** The result of [selectNewStaged]: the samples to write and the cursor to adopt once they are stored. */
    data class Staged(val fresh: List<QuantitySample>, val advanced: SyncCursor)

    /**
     * Non-moving [selectNew] for callers that must advance only AFTER a durable store commit:
     * returns the to-write subset with an independent cursor to adopt once the save succeeds.
     * This cursor is left unchanged, so decoded samples are never skipped by a failed save.
     */
    fun selectNewStaged(samples: List<QuantitySample>): Staged {
        val advanced = copy()
        val fresh = advanced.selectNew(samples)
        return Staged(fresh, advanced)
    }

    /**
     * Kinds whose high-water mark differs from [since] — the cursors that actually moved — in
     * [MetricKind] declaration order. Lets the store persist only the changed rows.
     */
    fun advancedKinds(since: SyncCursor): List<MetricKind> =
        MetricKind.entries.filter { last(it) != since.last(it) }

    /** An independent copy: advancing either cursor never moves the other. */
    fun copy(): SyncCursor = SyncCursor(lastByKind)

    /** Content equality (upstream `Equatable`). The hash follows the mutable state, so do not use a live cursor as a hash key. */
    override fun equals(other: Any?): Boolean = other is SyncCursor && lastByKind == other.lastByKind

    override fun hashCode(): Int = lastByKind.hashCode()

    override fun toString(): String = "SyncCursor(${lastByKind.toSortedMap()})"
}
