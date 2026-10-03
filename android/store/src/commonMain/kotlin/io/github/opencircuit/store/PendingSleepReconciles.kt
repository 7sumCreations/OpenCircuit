package io.github.opencircuit.store

import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.store.codec.Decoded
import io.github.opencircuit.store.codec.PendingSleepReconcileCodec
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId

// The queue of edited nights whose Health write is still to run, in `store_kv`. Port of upstream
// ios/OpenCircuit/Store/LocalStore.swift (@ b1c2fdd) `PendingSleepReconcile` (:566-572) and
// `PendingSleepReconcileStore` (:574-641), which keeps it in `UserDefaults`.
//
// Differences, each deliberate (PORTING D-171, D-172):
// - Only this build's key is read. Upstream also deletes the `…v1` key it abandoned; this build never
//   wrote that key, so nothing here touches it.
// - A queue this build cannot read is reported, never read as empty, and never written over: an
//   upsert or a clear throws, `canRekey` is false (so the night it belongs to is not moved), and
//   `rekey` writes nothing. Upstream reads it as empty and its next write replaces it.
// - Days are compared in the zone given; upstream uses `Calendar.current`.
// - Every write joins the caller's transaction.

/** One edited night waiting for its Health write: the edit's times and the night's segments. */
internal data class PendingSleepReconcile(
    val night: Instant,
    val inBedStart: Instant,
    val sleepOnset: Instant,
    val sleepWake: Instant,
    val segments: List<SleepSegment>,
)

/** The queue under one `store_kv` key, at most one item per day; read and written inside the caller's transaction. */
internal class PendingSleepReconciles(private val kv: KvDao) {

    /** The queue, oldest item first: empty when none is stored, or the stored text when this build cannot read it. */
    suspend fun all(): Decoded<List<PendingSleepReconcile>> =
        kv.get(KEY)?.value?.let(PendingSleepReconcileCodec::decode) ?: Decoded.Readable(emptyList())

    /** Replaces the item of [item]'s day in [zone], or adds it. Throws, writing nothing, when the queue cannot be read. */
    suspend fun upsert(item: PendingSleepReconcile, zone: ZoneId, now: Instant) {
        val items = readable().filterNot { isSameDay(it.night, item.night, zone) }
        write(items + item, now)
    }

    /** Removes the item of [night]'s day in [zone]. Throws, writing nothing, when the queue cannot be read. */
    suspend fun clear(night: Instant, zone: ZoneId, now: Instant) {
        write(readable().filterNot { isSameDay(it.night, night, zone) }, now)
    }

    /**
     * Whether [rekey] keeps one item per day: false when both days hold an item, since the two would
     * then be drained against one night. False too when the queue cannot be read, which nothing may
     * write over.
     */
    suspend fun canRekey(from: Instant, to: Instant, zone: ZoneId): Boolean {
        val items = all().valueOrNull() ?: return false
        val hasOld = items.any { isSameDay(it.night, from, zone) }
        val hasNew = items.any { isSameDay(it.night, to, zone) }
        return !(hasOld && hasNew)
    }

    /** Re-dates the items of [from]'s day in [zone] to [to]; writes nothing when there are none or the queue cannot be read. */
    suspend fun rekey(from: Instant, to: Instant, zone: ZoneId, now: Instant) {
        val items = all().valueOrNull() ?: return
        if (items.none { isSameDay(it.night, from, zone) }) return
        write(items.map { if (isSameDay(it.night, from, zone)) it.copy(night = to) else it }, now)
    }

    private suspend fun readable(): List<PendingSleepReconcile> = when (val d = all()) {
        is Decoded.Readable -> d.value
        is Decoded.Unreadable -> throw SleepStoreException.UnreadablePendingReconcile()
    }

    private suspend fun write(items: List<PendingSleepReconcile>, now: Instant) =
        kv.upsert(StoreKvEntity(KEY, PendingSleepReconcileCodec.encode(items), now))

    companion object {
        /** Upstream's key (:590), its version suffix included. */
        const val KEY = "sleep.edit.pending-reconcile.v2"
    }
}

/** Foundation's `isDate(_:inSameDayAs:)` in [zone]; false when either instant cannot be placed in it. */
internal fun isSameDay(a: Instant, b: Instant, zone: ZoneId): Boolean =
    try {
        a.atZone(zone).toLocalDate() == b.atZone(zone).toLocalDate()
    } catch (_: DateTimeException) {
        false
    }
