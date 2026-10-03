package io.github.opencircuit.store

import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId

// The store housekeeping run once per app launch. Port of upstream ios/OpenCircuit/App.swift
// (@ b1c2fdd): the launch tasks at :15-43 and their wrappers `pruneExpiredSamplesAtLaunch` (:617),
// `purgeImplausibleHeartRateOnce` (:626), `purgeImplausibleTimestampsOnce` (:640),
// `rekeySleepNightsOnce` (:658-663), `backfillSleepProvenanceOnce` (:674-679),
// `healWithheldSleepScores` (:698-711) and `repairFutureSyncCursorsAtLaunch` (:722).
//
// Upstream runs each step as its own launch task and swallows its failure (`try?` / do-catch);
// here every step still runs whatever happened to the one before it, and each outcome is returned
// to the caller instead of being dropped. The two purges' "once" latches live in the store, written
// in the purge's own transaction (upstream sets a UserDefaults flag after the purge saved), as the
// night move's latch lives in the move's transaction (PORTING D-170).

/** The store's launch-time repairs, in upstream's order. */
object LaunchRepairs {

    /**
     * What each step did. A purge's `null` means it had already run on this store (its latch was
     * set); a number is how many rows it deleted, or how many cursors the repair changed. The night
     * move succeeds once stored nights are on their wake day (now or on an earlier launch) and fails
     * with why it could not move them; the backfill's number is the nights it filled; the heal's list
     * is the nights whose score it restored, newest first.
     */
    data class Report(
        val prune: Result<Unit>,
        val heartRatePurge: Result<Int?>,
        val timestampPurge: Result<Int?>,
        val nightRekey: Result<Unit>,
        val provenanceBackfill: Result<Int>,
        val scoreHeal: Result<List<Instant>>,
        val cursorRepair: Result<Int>,
    )

    /**
     * Runs, in order: the 30-day prune, the one-time heart-rate purge, the one-time timestamp
     * purge, the three sleep-history repairs of [sleep] — the one-time move of stored nights onto
     * the day they ended ([SleepStore.ensureNightKeyMigrated]), the provenance backfill
     * ([SleepStore.backfillSleepProvenance]) and the heal of withheld scores
     * ([SleepStore.healWithheldSleepScores]) — then the cursor repair, last, so its "newest stored
     * sample" lookup sees the cleaned table. A failing step is reported and the next one runs.
     * Cancellation is not a step failure: it stops the run.
     */
    suspend fun run(store: LocalStore, sleep: SleepStore, now: Instant, zone: ZoneId): Report {
        val prune = attempt { store.pruneExpiredSamples(now, zone) }
        val heartRatePurge = attempt { store.purgeImplausibleHeartRateOnce(now) }
        val timestampPurge = attempt { store.purgeImplausibleTimestampsOnce(now) }
        val nightRekey = attempt { sleep.ensureNightKeyMigratedOrThrow(zone, now) }
        val provenanceBackfill = attempt { sleep.backfillSleepProvenance() }
        val scoreHeal = attempt { sleep.healWithheldSleepScores(now) }
        val cursorRepair = attempt { store.repairFutureSyncCursors(now) }
        return Report(prune, heartRatePurge, timestampPurge, nightRekey, provenanceBackfill, scoreHeal, cursorRepair)
    }

    private inline fun <T> attempt(step: () -> T): Result<T> =
        try {
            Result.success(step())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
}
