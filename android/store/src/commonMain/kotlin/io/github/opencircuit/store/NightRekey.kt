package io.github.opencircuit.store

import io.github.opencircuit.ringkit.SleepNightRekeyPlan
import java.time.Instant
import java.time.ZoneId

// Moving a stored night to another key, with everything kept under its key. Port of upstream
// ios/OpenCircuit/Store/LocalStore.swift (@ b1c2fdd): `canRenameNightScopedOverlays` (:2474-2484),
// `renameNightScopedOverlays` (:2489-2505) with `moveNightScopedDefault` / `canMoveNightScopedDefault`
// (:353-372), the two edit watermark keys (:2454-2460), `renameCursor` / `canRenameCursor`
// (:2558-2571), `renameFrozenHeadacheNightKey` (:2542-2545) and
// `advanceExportWatermarkIfItNamesAMovedNight` (:2527-2534).
//
// Differences, each deliberate (PORTING D-170, D-172, D-173, D-174):
// - Nothing here commits: every check and write runs inside the caller's transaction, so a failure
//   anywhere rolls back the rows, the values, the watermarks and the headache keys together.
//   Upstream moves its `UserDefaults` values at once and reverses them by hand if the save fails.
// - A failing read fails the check; upstream reads a failing watermark read as "cannot move".
// - The keys moved from are the stored row's own key, as every value was written under it.
// - A move is refused when risk rows already name both nights, so two frozen scores never claim one
//   night; upstream renames them all.
// - A pending-reconcile queue this build cannot read refuses the move (see PendingSleepReconciles).
// - The morning notification's last-notified night (:2514-2520) has no key on Android yet.

/** Checks and moves a stored night's values, watermarks, risk rows and queued item, inside the caller's transaction. */
internal class NightRekey(private val sleepDao: SleepDao, private val kv: KvDao) {

    private val pending = PendingSleepReconciles(kv)

    /**
     * Whether the night keyed [from] (a stored row's key) can move to [to] with everything kept
     * under its key: no value or watermark already under the new key (a watermark only when the
     * night has its own to move), no risk rows naming both nights, and the queue able to re-date its
     * item. Throws when a read fails.
     */
    suspend fun canRename(from: Instant, to: Instant, zone: ZoneId): Boolean {
        // The destination is what matters, not the source: a night with nothing of its own would
        // otherwise inherit an orphan (upstream :362-372).
        for (prefix in VALUE_PREFIXES) if (kv.get(NightOverlays.key(prefix, to)) != null) return false
        if (!pending.canRekey(from, to, zone)) return false
        for (prefix in WATERMARK_PREFIXES) if (!canRenameCursor(watermarkKey(prefix, from), watermarkKey(prefix, to))) return false
        if (sleepDao.riskRowsForNight(from) > 0 && sleepDao.riskRowsForNight(to) > 0) return false
        return true
    }

    /**
     * Moves everything kept under [from] to [to]: the values as stored, the two edit watermarks, the
     * risk rows naming the night and the queued item. Check [canRename] first; a destination already
     * holding a value is left alone. Writes nothing for a night with nothing kept.
     */
    suspend fun rename(from: Instant, to: Instant, zone: ZoneId, now: Instant) {
        for (prefix in WATERMARK_PREFIXES) renameCursor(watermarkKey(prefix, from), watermarkKey(prefix, to))
        sleepDao.renameHeadacheNightKey(from, to)
        for (prefix in VALUE_PREFIXES) moveValue(NightOverlays.key(prefix, from), NightOverlays.key(prefix, to))
        pending.rekey(from, to, zone, now)
    }

    /**
     * The export watermark names an exported night by its key: when one of [moves] moved that
     * night, the watermark follows it. Nothing is created when there is no watermark.
     */
    suspend fun advanceExportWatermark(moves: List<SleepNightRekeyPlan.Move>) {
        val watermark = sleepDao.cursorAt(EXPORT_SESSIONS) ?: return
        val move = moves.firstOrNull { it.from == watermark.last } ?: return
        sleepDao.updateCursor(watermark.copy(last = move.to))
    }

    /** Upstream `moveNightScopedDefault`: nothing to move, or an occupied destination, writes nothing. */
    private suspend fun moveValue(fromKey: String, toKey: String) {
        val value = kv.get(fromKey) ?: return
        if (kv.get(toKey) != null) return
        kv.upsert(value.copy(key = toKey))
        kv.delete(fromKey)
    }

    /** A watermark is keyed by its row's primary key: moved by a delete and an insert, never onto another. */
    private suspend fun renameCursor(fromKey: String, toKey: String) {
        val source = sleepDao.cursorAt(fromKey) ?: return
        if (!canRenameCursor(fromKey, toKey)) return
        sleepDao.deleteCursorAt(fromKey)
        sleepDao.insertCursor(source.copy(kindRaw = toKey))
    }

    private suspend fun canRenameCursor(fromKey: String, toKey: String): Boolean =
        sleepDao.cursorAt(fromKey) == null || sleepDao.cursorAt(toKey) == null

    companion object {
        /** The per-night values (upstream's onset, Health sample ids, undo stack and Health mirror), moved as stored. */
        val VALUE_PREFIXES = listOf(NightOverlays.ONSET, "sleep.edit.hkuuids", NightOverlays.PRIOR_TIMES, "sleep.mirror.night")

        /** The two leading watermarks of an edited night's Health write (upstream :2454-2460). */
        val WATERMARK_PREFIXES = listOf("hk:sleep-edit-leading:", "hk:sleep-edit-leading-asleep:")

        /** The export night watermark (upstream `exportSessionsCursorKey`, :1389). */
        const val EXPORT_SESSIONS = "export:sleepSessions"

        fun watermarkKey(prefix: String, night: Instant): String = prefix + NightOverlays.dayText(night)
    }
}
