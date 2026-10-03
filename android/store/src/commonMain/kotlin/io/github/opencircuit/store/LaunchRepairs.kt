package io.github.opencircuit.store

import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId

// The store housekeeping run once per app launch. Port of upstream ios/OpenCircuit/App.swift
// (@ b1c2fdd): the launch tasks at :15-43 and their wrappers `pruneExpiredSamplesAtLaunch` (:617),
// `purgeImplausibleHeartRateOnce` (:626), `purgeImplausibleTimestampsOnce` (:640) and
// `repairFutureSyncCursorsAtLaunch` (:722).
//
// Upstream runs each step as its own launch task and swallows its failure (`try?` / do-catch);
// here every step still runs whatever happened to the one before it, and each outcome is returned
// to the caller instead of being dropped. The two purges' "once" latches live in the store, written
// in the purge's own transaction (upstream sets a UserDefaults flag after the purge saved).

/** The store's launch-time repairs, in upstream's order. */
object LaunchRepairs {

    /**
     * What each step did. A purge's `null` means it had already run on this store (its latch was
     * set); a number is how many rows it deleted, or how many cursors the repair changed.
     */
    data class Report(
        val prune: Result<Unit>,
        val heartRatePurge: Result<Int?>,
        val timestampPurge: Result<Int?>,
        val cursorRepair: Result<Int>,
    )

    /**
     * Runs, in order: the 30-day prune, the one-time heart-rate purge, the one-time timestamp
     * purge, then the cursor repair — last, so its "newest stored sample" lookup sees the cleaned
     * table. A failing step is reported and the next one runs. Cancellation is not a step failure:
     * it stops the run.
     */
    suspend fun run(store: LocalStore, now: Instant, zone: ZoneId): Report {
        val prune = attempt { store.pruneExpiredSamples(now, zone) }
        val heartRatePurge = attempt { store.purgeImplausibleHeartRateOnce(now) }
        val timestampPurge = attempt { store.purgeImplausibleTimestampsOnce(now) }
        // Upstream runs three sleep-history repairs here (moving each night onto the day it ended,
        // backfilling the provenance columns, restoring withheld sleep scores); they arrive with the
        // sleep write flow.
        val cursorRepair = attempt { store.repairFutureSyncCursors(now) }
        return Report(prune, heartRatePurge, timestampPurge, cursorRepair)
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
