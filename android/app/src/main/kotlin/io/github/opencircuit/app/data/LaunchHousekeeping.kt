package io.github.opencircuit.app.data

import io.github.opencircuit.store.LaunchRepairs
import io.github.opencircuit.store.LocalStore
import io.github.opencircuit.store.SleepStore
import io.github.opencircuit.store.StoreDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * The store's launch repairs, run once when the app starts (upstream runs them as launch tasks,
 * ios/OpenCircuit/App.swift:15-43): [LaunchRepairs.run] — the two one-time purges, the move of
 * stored nights onto their wake day, the provenance backfill, the heal of withheld scores and the
 * cursor repair — on [background], after the store opened. None deletes a reading for its age: every
 * reading is kept. A store that cannot open runs none; each step's outcome is logged, counts and
 * error kinds only, never a reading.
 */
class LaunchHousekeeping(
    private val database: suspend () -> StoreDatabase,
    private val now: () -> Instant,
    private val zone: () -> ZoneId,
    private val log: (String) -> Unit,
    private val background: CoroutineContext = Dispatchers.IO,
) {
    /** Starts the repairs on [scope] and returns at once. */
    fun start(scope: CoroutineScope): Job = scope.launch(background) {
        val db = try {
            database()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Launch repairs: the store did not open (${e::class.simpleName}); none ran")
            return@launch
        }
        log(summary(LaunchRepairs.run(LocalStore(db), SleepStore(db), now(), zone())))
    }

    internal companion object {
        /** One line naming each step's outcome: "ok", a count, or the error's kind. */
        fun summary(r: LaunchRepairs.Report): String = listOf(
            "heart-rate purge" to r.heartRatePurge.map { it?.toString() ?: "done before" },
            "timestamp purge" to r.timestampPurge.map { it?.toString() ?: "done before" },
            "night move" to r.nightRekey.map { "ok" },
            "provenance backfill" to r.provenanceBackfill.map { it.toString() },
            "score heal" to r.scoreHeal.map { it.size.toString() },
            "cursor repair" to r.cursorRepair.map { it.toString() },
        ).joinToString(prefix = "Launch repairs: ", separator = ", ") { (step, outcome) ->
            "$step " + outcome.getOrElse { "failed (${it::class.simpleName})" }
        }
    }
}
