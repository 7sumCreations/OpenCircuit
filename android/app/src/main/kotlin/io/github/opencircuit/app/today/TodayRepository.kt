package io.github.opencircuit.app.today

import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.store.LocalStore
import io.github.opencircuit.store.StoreDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** The stored readings Today shows: the newest heart rate and blood oxygen the phone holds. */
data class TodayStored(
    val heartRate: QuantitySample? = null,
    val spo2: QuantitySample? = null,
)

/** Where Today's stored readings come from, and when they changed. */
interface TodaySource {
    /** Revision 0 at once, then a higher one after each settled burst of committed writes to the readings. */
    fun revisions(): Flow<Long>

    /**
     * What Today shows for [day] in [zone], read off the main thread. The hero's readings are the
     * NEWEST stored, whatever their day: the screen prints when each was taken ("as of Mon 19 Oct ·
     * 2 days ago"), so an old reading never reads as today's. The day bounds the per-day reads.
     */
    suspend fun load(day: LocalDate, zone: ZoneId): TodayStored
}

/**
 * Today's reads over the app's one database. Reads are bounded (the newest row of a kind, by its
 * `(kind, start)` index), so their cost does not grow with the history kept.
 *
 * Refreshing follows the store, not a timer: [revisions] turns the store's change announcements
 * ([LocalStore.changes], which covers every writer — the drain, live readings, launch repairs) into
 * revisions, the first at once and then one per burst once writes have been quiet for [settle].
 */
class TodayRepository(
    private val database: suspend () -> StoreDatabase,
    private val io: CoroutineContext = Dispatchers.IO,
    private val settle: Duration = SETTLE,
) : TodaySource {

    override fun revisions(): Flow<Long> = revisions(storeChanges(database), settle)

    override suspend fun load(day: LocalDate, zone: ZoneId): TodayStored = withContext(io) {
        val store = LocalStore(database())
        TodayStored(heartRate = store.latestSample(MetricKind.HEART_RATE), spo2 = store.latestSample(MetricKind.SPO2))
    }

    companion object {
        /** How long the store must be quiet before a burst of writes becomes one revision. */
        val SETTLE: Duration = 300.milliseconds

        /**
         * The store's change announcements ([LocalStore.changes]) over the database [database]
         * opens — the one watch every reader of the store's growth shares.
         */
        fun storeChanges(database: suspend () -> StoreDatabase): Flow<Set<String>> =
            flow { LocalStore(database()).changes().collect { emit(it) } }

        /**
         * [changes] as revisions 0, 1, 2…: the first announcement (the store's own, when watching
         * starts) passes at once; every later one waits until no other has come for [settle].
         */
        @OptIn(FlowPreview::class)
        internal fun revisions(changes: Flow<*>, settle: Duration): Flow<Long> = flow {
            var revision = 0L
            changes.conflate().withIndex()
                .debounce { if (it.index == 0) Duration.ZERO else settle }
                .collect { emit(revision++) }
        }
    }
}
