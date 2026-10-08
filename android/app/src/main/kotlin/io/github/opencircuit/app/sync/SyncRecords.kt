package io.github.opencircuit.app.sync

import io.github.opencircuit.store.SyncLog
import io.github.opencircuit.store.SyncLogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

/**
 * A ring session's record of its syncs, kept across launches (PORTING.md D-273): the sync log
 * ([entries], oldest first, the last 50) and what the store holds ([stored]) — what the Ring data
 * card and Connection details show when no sync runs, before and after a relaunch.
 *
 * [load] reads both from the store once (the session's start; [record] makes sure it ran). [record]
 * turns a finished sync's report into its log entry ([SyncLogEntries.of]: continuity against the
 * earlier entries, the capacity verdict, what held records back), keeps it in memory first — so
 * the card shows it even when the store cannot be written — then writes it, and reads what is
 * stored again. A failed read or write is logged, never thrown.
 */
class SyncRecords(
    private val store: HistoryStore,
    private val zone: () -> ZoneId,
    /** The firmware version the ring reported on this connection, or null when not read. */
    private val firmware: () -> String?,
    private val log: (String) -> Unit,
) {
    private val entriesFlow = MutableStateFlow<List<SyncLogEntry>>(emptyList())
    private val storedFlow = MutableStateFlow<StoredData?>(null)
    private val loadLock = Mutex()
    private var loaded = false

    /** The sync log, oldest entry first. */
    val entries: StateFlow<List<SyncLogEntry>> = entriesFlow.asStateFlow()

    /** What the store holds; null when nothing is (or it could not be read). */
    val stored: StateFlow<StoredData?> = storedFlow.asStateFlow()

    /** Reads the log and what is stored, once; later calls return at once. */
    suspend fun load() {
        loadLock.withLock {
            if (loaded) return
            loaded = true
            val kept = try {
                store.syncLog()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("The sync log could not be read: ${e::class.java.simpleName}")
                emptyList()
            }
            // An entry recorded while the log was being read stays, after the stored ones.
            entriesFlow.update { recorded -> (kept + recorded).distinct().takeLast(SyncLog.KEPT) }
        }
        refreshStored()
    }

    /** Keeps [report] as the newest log entry: in memory at once, then in the store; then reads what is stored again. */
    suspend fun record(report: SyncReport) {
        load()
        val entry = SyncLogEntries.of(report, entriesFlow.value, zone(), firmware())
        entriesFlow.update { (it + entry).takeLast(SyncLog.KEPT) }
        try {
            store.appendSyncLog(entry, report.finishedAt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Shown from memory until the app stops; the sync's data is stored either way.
            log("The sync log could not be written: ${e::class.java.simpleName}")
        }
        refreshStored()
    }

    private suspend fun refreshStored() {
        storedFlow.value = try {
            store.storedData()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("What is stored could not be read: ${e::class.java.simpleName}")
            storedFlow.value
        }
    }
}
