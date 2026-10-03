package io.github.opencircuit.store

import io.github.opencircuit.ringkit.SleepEdit
import io.github.opencircuit.store.codec.Decoded
import io.github.opencircuit.store.codec.PriorTimesCodec
import io.github.opencircuit.store.codec.instant
import io.github.opencircuit.store.codec.readStored
import java.time.Instant

// The per-night values a wearer's edit keeps beside its row, in `store_kv`. Port of upstream
// ios/OpenCircuit/Store/LocalStore.swift (@ b1c2fdd) `SleepEditPriorTimesOverlay` (:400-452, its key,
// `stack` and `push`) and `SleepEditOnsetOverlay` (:457-480, its key, `load` and `save`), which keep
// them in `UserDefaults`.
//
// Differences, each deliberate (PORTING.md D-164, D-165):
// - The key's day is the stored row's own night key, written as whole seconds and `.0`: upstream's
//   text (`"\(Double)"` prints `1749938400.0`, where Kotlin's `Double.toString` prints `1.7499384E9`),
//   without recomputing the start of the day in the device's current zone, which upstream does and
//   which loses the value when the zone has changed since the edit.
// - A stack this build cannot read is kept as stored and the push is skipped; upstream reads it as
//   empty and the push writes over it.
// - The onset is whole epoch milliseconds in text (upstream: a property-list date); anything else
//   reads as absent, as upstream's `as? Date`.
// - Every write joins the caller's transaction, so a value commits or rolls back with its row.
// The renames a re-keyed night needs, and the stack's `pop`, which nothing calls upstream, are not
// here.

/** A stored night's edit values, read and written inside the caller's transaction. */
internal class NightOverlays(private val kv: KvDao) {

    /** The onset saved with the wearer's edit of [night] (a stored row's key); null when none or unreadable. */
    suspend fun onset(night: Instant): Instant? =
        kv.get(key(ONSET, night))?.value?.let { raw -> readStored(raw) { it.instant() }.valueOrNull() }

    suspend fun saveOnset(night: Instant, onset: Instant, now: Instant) =
        kv.upsert(StoreKvEntity(key(ONSET, night), onset.toStoredMillis().toEpochMilli().toString(), now))

    /**
     * Pushes [replaced] — the times an edit is about to replace — onto [night]'s undo stack. Skipped
     * when the times have no usable window (an unknown or reversed one has nothing to restore to),
     * when they are the stack's top to the stored millisecond, or when the stored stack cannot be
     * read. Past [MAX_PRIOR_DEPTH] the oldest entry after the first is dropped, so the ring's own
     * window always survives.
     */
    suspend fun pushPriorTimes(night: Instant, replaced: SleepEdit.Times, now: Instant) {
        val snapshot = SleepEdit.Times(
            replaced.inBedStart.toStoredMillis(), replaced.sleepOnset.toStoredMillis(), replaced.sleepWake.toStoredMillis(),
        )
        if (!(snapshot.sleepWake > snapshot.inBedStart && snapshot.inBedStart > SleepEdit.DISTANT_PAST)) return
        val key = key(PRIOR_TIMES, night)
        val stored = kv.get(key)?.value
        val stack = when (val d = stored?.let(PriorTimesCodec::decode)) {
            null -> emptyList()
            is Decoded.Readable -> d.value
            is Decoded.Unreadable -> return // never written over
        }
        if (stack.lastOrNull() == snapshot) return
        val pushed = stack + snapshot
        val kept = if (pushed.size > MAX_PRIOR_DEPTH) listOf(pushed.first()) + pushed.takeLast(MAX_PRIOR_DEPTH - 1) else pushed
        kv.upsert(StoreKvEntity(key, PriorTimesCodec.encode(kept), now))
    }

    companion object {
        const val PRIOR_TIMES = "sleep.edit.priorTimes"
        const val ONSET = "sleep.edit.onset"

        /** Upstream `SleepEditPriorTimesOverlay.maxDepth` (:407). */
        const val MAX_PRIOR_DEPTH = 20

        /** `<prefix>.<night in whole epoch seconds>.0` — [night] is a stored row's key. */
        fun key(prefix: String, night: Instant): String = "$prefix.${night.epochSecond}.0"
    }
}
