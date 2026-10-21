package io.github.opencircuit.app.today

import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.store.LocalStore
import io.github.opencircuit.store.SleepStore
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
    /** The two-week window ending on the day loaded: the tiles', readiness card's and synthesis line's data. */
    val trends: TodayTrends = TodayTrends(),
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
        val db = database()
        val store = LocalStore(db)
        TodayStored(
            heartRate = store.latestSample(MetricKind.HEART_RATE),
            spo2 = store.latestSample(MetricKind.SPO2),
            trends = TodayTrends.of(window(store, SleepStore(db), day, zone), zone),
        )
    }

    /**
     * The [TodayTiles.WINDOW_DAYS] days ending on [day]: the nights keyed on them, their step totals,
     * and the readings from the evening before the first (its night's in-bed window starts there) to
     * the end of [day] — each a range read on its table's time index, so the cost is the window's,
     * never the history's.
     */
    private suspend fun window(store: LocalStore, sleep: SleepStore, day: LocalDate, zone: ZoneId): TodayWindowInputs {
        val first = day.minusDays(TodayTiles.WINDOW_DAYS - 1L).atStartOfDay(zone).toInstant()
        val readingsFrom = day.minusDays(TodayTiles.WINDOW_DAYS.toLong()).atStartOfDay(zone).toInstant()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant()
        return TodayWindowInputs(
            nights = sleep.sleepSummaries(first, end).map {
                TodayNight(it.night, it.asleepMin, it.sleepScore, it.stressScore, it.skinTempC, it.inBedStart, it.inBedEnd)
            },
            dailies = store.dailies(first, end),
            hr = store.samples(MetricKind.HEART_RATE, readingsFrom, end),
            hrv = store.samples(MetricKind.HRV_SDNN, readingsFrom, end),
            spo2 = store.samples(MetricKind.SPO2, readingsFrom, end),
            rr = store.samples(MetricKind.RESPIRATORY_RATE, readingsFrom, end),
            dayTemps = store.daytimeTemperatures(readingsFrom, end),
            stepDeltas = store.stepSamples(readingsFrom, end),
        )
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
