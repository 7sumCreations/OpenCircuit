package io.github.opencircuit.app.details

import io.github.opencircuit.app.today.TodayRepository
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.store.StoreDatabase
import io.github.opencircuit.store.StoreFootprint
import io.github.opencircuit.store.StoreFootprints
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
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
 * Connection details' Storage line: read at once, then again after readings are written — at most
 * once per [every], however steadily they keep coming (a sync's commits, a live measure's
 * readings), so the line moves on during the growth it exists to show while the whole-table counts
 * never run back to back. A read that fails is logged (its kind only) and skipped; the line keeps
 * its last value and the next change tries again. A watch that fails ends quietly: the Ring screen
 * it feeds must never stop for a diagnostics line.
 */
class StorageSource(
    private val database: suspend () -> StoreDatabase,
    private val fileBytes: () -> Long?,
    private val io: CoroutineContext = Dispatchers.IO,
    private val every: Duration = EVERY,
    private val log: (String) -> Unit = {},
) {
    fun reports(): Flow<StorageReport> = flow {
        TodayRepository.storeChanges(database).conflate().collect {
            read()?.let { emit(it) }
            delay(every)
        }
    }.catch { e -> log("Storage line: the store could not be watched (${e::class.java.simpleName})") }

    private suspend fun read(): StorageReport? = try {
        withContext(io) { StorageReport(fileBytes(), StoreFootprints(database()).read()) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("Storage line: the store could not be counted (${e::class.java.simpleName})")
        null
    }

    companion object {
        val EVERY: Duration = 2.seconds
    }
}

/** Words the Storage line: bytes, the samples' days, rows per table and samples per kind; counts only, never a value. */
internal object StorageLine {

    fun value(report: StorageReport, zone: ZoneId): String {
        val f = report.footprint
        val rows = f.rowsPerTable
        val kinds = f.samplesPerKind.entries.joinToString(", ") { "${kindName(it.key)} ${count(it.value)}" }
        val samples = "samples ${count(rows["stored_sample"] ?: 0)}" + if (kinds.isEmpty()) "" else " ($kinds)"
        return listOf(
            report.fileBytes?.let { "${bytes(it)} on disk (database + log)" } ?: "size not read",
            sampleDays(f.firstSample, f.lastSample, zone),
            samples,
            "day totals ${count(rows["stored_daily"] ?: 0)}",
            "step deltas ${count(rows["stored_step_sample"] ?: 0)}",
            "daytime temperatures ${count(rows["stored_daytime_temp"] ?: 0)}",
            "nights ${count(rows["stored_sleep_summary"] ?: 0)}",
            "naps ${count(rows["stored_nap"] ?: 0)}",
        ).joinToString(" · ")
    }

    /** A known kind in words ("heart rate", "hrv sdnn"); an unknown stored name as stored. */
    private fun kindName(raw: String): String =
        MetricKind.entries.firstOrNull { it.rawValue == raw }?.name?.lowercase(Locale.ROOT)?.replace('_', ' ') ?: raw

    private fun sampleDays(first: Instant?, last: Instant?, zone: ZoneId): String =
        if (first == null || last == null) "no samples stored" else "samples from ${DAY.format(first.atZone(zone))} to ${DAY.format(last.atZone(zone))}"

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
