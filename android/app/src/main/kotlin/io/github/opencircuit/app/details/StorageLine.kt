package io.github.opencircuit.app.details

import io.github.opencircuit.app.today.TodayRepository
import io.github.opencircuit.store.LocalStore
import io.github.opencircuit.store.StoreDatabase
import io.github.opencircuit.store.StoreFootprint
import io.github.opencircuit.store.StoreFootprints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What the store holds and how big it is on disk; [fileBytes] is null when the size could not be read. */
data class StorageReport(val fileBytes: Long?, val footprint: StoreFootprint)

/**
 * The size of the store on disk: the database file plus its write-ahead log, which holds the
 * newest writes until a checkpoint folds them in (the shared-memory index is not data). Null when
 * the database file does not exist yet.
 */
fun storeFileBytes(database: File): Long? {
    if (!database.isFile) return null
    val log = File(database.path + "-wal")
    return database.length() + if (log.isFile) log.length() else 0L
}

/**
 * Connection details' Storage line, read again whenever a reading is written: the store's changes
 * become revisions as Today's do, but settle for [settle] — the counts read whole tables, so a
 * running sync must not recount after every commit.
 */
class StorageSource(
    private val database: suspend () -> StoreDatabase,
    private val fileBytes: () -> Long?,
    private val io: CoroutineContext = Dispatchers.IO,
    private val settle: Duration = SETTLE,
) {
    fun reports(): Flow<StorageReport> =
        TodayRepository.revisions(flow { LocalStore(database()).changes().collect { emit(it) } }, settle)
            .map { withContext(io) { StorageReport(fileBytes(), StoreFootprints(database()).read()) } }

    companion object {
        val SETTLE: Duration = 2.seconds
    }
}

/** Words the Storage line: bytes, days, rows per table and samples per kind; counts only, never a value. */
internal object StorageLine {

    fun value(report: StorageReport, zone: ZoneId): String {
        val f = report.footprint
        val rows = f.rowsPerTable
        val kinds = f.samplesPerKind.entries.joinToString(", ") { "${it.key} ${count(it.value)}" }
        val samples = "samples ${count(rows["stored_sample"] ?: 0)}" + if (kinds.isEmpty()) "" else " ($kinds)"
        return listOf(
            report.fileBytes?.let { "${bytes(it)} on disk (database + log)" } ?: "size not read",
            days(f.firstSample, f.lastSample, zone),
            samples,
            "day totals ${count(rows["stored_daily"] ?: 0)}",
            "step deltas ${count(rows["stored_step_sample"] ?: 0)}",
            "daytime temperatures ${count(rows["stored_daytime_temp"] ?: 0)}",
            "nights ${count(rows["stored_sleep_summary"] ?: 0)}",
            "naps ${count(rows["stored_nap"] ?: 0)}",
        ).joinToString(" · ")
    }

    private fun days(first: Instant?, last: Instant?, zone: ZoneId): String =
        if (first == null || last == null) "no samples stored" else "days ${DAY.format(first.atZone(zone))} – ${DAY.format(last.atZone(zone))}"

    private fun bytes(n: Long): String = when {
        n >= MIB -> String.format(Locale.ROOT, "%.1f MB", n / MIB.toDouble())
        n >= KIB -> "${n / KIB} KB"
        else -> "$n bytes"
    }

    private fun count(n: Long): String = String.format(Locale.ROOT, "%,d", n)

    private const val KIB = 1_024L
    private const val MIB = 1_024L * 1_024L
    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
}
