package io.github.opencircuit.store

import androidx.room3.withWriteTransaction
import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.ringkit.CumulativeMetricAccumulator
import io.github.opencircuit.ringkit.CumulativeMetricState
import io.github.opencircuit.ringkit.LiveHR
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.ringkit.SyncCursor
import io.github.opencircuit.ringkit.isCumulativeCounter
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// The store's ingest path: ring samples and the per-metric sync cursor, committed together.
// Port of upstream ios/OpenCircuit/Store/LocalStore.swift (@ b1c2fdd): `IngestPreview` (:878),
// `storeCursorRows` (:891), `previewIngest` (:899), `loadCursor` (:929), `ingest` (:941-1028),
// `isPlausible` (:1039), `samples` (:1177), `latestSample(kind:)` (:1192), `upsertCursor` (:3126)
// and `cumulativeState` (:3136).
//
// Differences, each deliberate:
// - `now` and `zone` are parameters. Upstream reads the wall clock and `Calendar.current`; the
//   result is the same for the same clock and zone.
// - Every sample's times are cut to whole milliseconds before the cursor sees them, because that
//   is what the store keeps; otherwise a sub-millisecond sample would look newer than its own
//   stored cursor and be stored again on every re-sync.
// - A heart rate that is NaN or infinite is dropped as implausible; upstream's `Int(value)` traps.

/**
 * The on-device store over one [StoreDatabase]. Every write is one transaction: it commits whole
 * or not at all.
 */
class LocalStore internal constructor(
    private val db: StoreDatabase,
    private val sampleDao: SampleDao,
) {
    constructor(db: StoreDatabase) : this(db, db.sampleDao())

    /** What [ingest] would do with a batch, without writing (upstream `IngestPreview`). */
    data class IngestPreview(
        val inputCount: Int = 0,
        val plausibleCount: Int = 0,
        val freshCount: Int = 0,
        val duplicateCount: Int = 0,
        val invalidTimestampCount: Int = 0,
        val invalidHeartRateCount: Int = 0,
    )

    /**
     * Dry run of [ingest] for logging: how many samples are implausible, new or already stored.
     * Writes nothing.
     */
    suspend fun previewIngest(samples: List<QuantitySample>, now: Instant): IngestPreview {
        val cursor = cursorOf(storeCursorRows())
        var invalidTimestamp = 0
        var invalidHeartRate = 0
        val plausible = ArrayList<QuantitySample>(samples.size)
        for (s in samples.map(::toStoredPrecision)) {
            when (plausibility(s, now)) {
                Plausibility.BAD_TIMESTAMP -> invalidTimestamp++
                Plausibility.BAD_HEART_RATE -> invalidHeartRate++
                Plausibility.PLAUSIBLE -> plausible += s
            }
        }
        val fresh = cursor.selectNewStaged(plausible).fresh.size
        return IngestPreview(
            inputCount = samples.size,
            plausibleCount = plausible.size,
            freshCount = fresh,
            duplicateCount = maxOf(plausible.size - fresh, 0),
            invalidTimestampCount = invalidTimestamp,
            invalidHeartRateCount = invalidHeartRate,
        )
    }

    /** The ingest cursor, rebuilt from the stored rows (health and export watermarks excluded). */
    suspend fun loadCursor(): SyncCursor = cursorOf(storeCursorRows())

    /**
     * Stores the plausible samples newer than the cursor and advances the cursor past them, in one
     * transaction: if anything fails, neither the samples nor the cursor move, and the same
     * samples are taken again on the next ingest.
     *
     * Cumulative counters (steps, active energy) are stored as per-epoch deltas with their raw
     * reading and the running day total; the day total restarts at local midnight in [zone].
     * Returns what was stored — for a cumulative counter, the delta sample.
     */
    suspend fun ingest(samples: List<QuantitySample>, now: Instant, zone: ZoneId): List<QuantitySample> =
        db.withWriteTransaction {
            val rows = storeCursorRows()
            val cursor = cursorOf(rows)

            // Plausibility before the cursor sees anything: a cursor only moves forward, so one
            // corrupt far-future sample would block every later real sample of its kind.
            val plausible = samples.map(::toStoredPrecision).filter { plausibility(it, now) == Plausibility.PLAUSIBLE }

            // Stage the advance; the cursor rows are written in this same transaction.
            val staged = cursor.selectNewStaged(plausible)
            if (staged.fresh.isEmpty()) return@withWriteTransaction emptyList()

            val toInsert = ArrayList<StoredSampleEntity>(staged.fresh.size)
            val ingested = ArrayList<QuantitySample>(staged.fresh.size)
            // One stored-state lookup per cumulative kind per batch; later samples of that kind in
            // the batch carry the state forward, restarting the day total at a new local day.
            val states = HashMap<MetricKind, CumulativeMetricState>()
            val stateDays = HashMap<MetricKind, Instant>()

            for (s in staged.fresh) {
                if (!s.kind.isCumulativeCounter) {
                    toInsert += StoredSampleEntity.of(s)
                    ingested += s
                    continue
                }
                val dayStart = startOfDay(s.start, zone)
                val existing = states[s.kind]
                val state = when {
                    existing == null -> cumulativeState(s.kind, before = s.start, zone = zone)
                    stateDays[s.kind] == dayStart -> existing
                    else -> CumulativeMetricState(previousRawValue = existing.previousRawValue, dailyTotal = 0.0)
                }
                val result = CumulativeMetricAccumulator.accumulate(s, state)
                val delta = s.copy(value = result.deltaValue)
                toInsert += StoredSampleEntity.of(delta, rawValue = result.rawValue, isDelta = true, dailyTotal = result.dailyTotal)
                states[s.kind] = CumulativeMetricState(previousRawValue = result.rawValue, dailyTotal = result.dailyTotal)
                stateDays[s.kind] = dayStart
                // The delta, not the running total: the health store sums cumulative types.
                ingested += delta
            }
            sampleDao.insertSamples(toInsert)

            // Only the kinds whose cursor moved.
            val advanced = staged.advanced
            sampleDao.upsertCursors(
                advanced.advancedKinds(since = cursor).mapNotNull { kind ->
                    advanced.last(kind)?.let { StoredCursorEntity(kindRaw = kind.rawValue, last = it) }
                },
            )
            ingested
        }

    /** Stored samples of [kind] with `from <= start < to`, oldest first. */
    suspend fun samples(kind: MetricKind, from: Instant, to: Instant): List<QuantitySample> =
        sampleDao.samples(kind.rawValue, from, to).mapNotNull { it.toSample() }

    /** The newest stored sample of [kind], or null (the launch screen's last known heart rate). */
    suspend fun latestSample(kind: MetricKind): QuantitySample? = sampleDao.latestSample(kind.rawValue)?.toSample()

    private suspend fun storeCursorRows(): List<StoredCursorEntity> =
        sampleDao.allCursors().filter { !it.kindRaw.startsWith(HEALTH_CURSOR_PREFIX) && !it.kindRaw.startsWith(EXPORT_CURSOR_PREFIX) }

    private fun cursorOf(rows: List<StoredCursorEntity>) = SyncCursor(rows.associate { it.kindRaw to it.last })

    /**
     * The counter state just before [before]: the previous raw reading of [kind] (any day), and the
     * day total of the newest earlier sample on the same local day (upstream `cumulativeState`).
     */
    private suspend fun cumulativeState(kind: MetricKind, before: Instant, zone: ZoneId): CumulativeMetricState {
        val previous = sampleDao.latestSampleBefore(kind.rawValue, before)
        val previousRaw = previous?.let { it.rawValue ?: it.value }
        val dayStart = startOfDay(before, zone)
        val nextDay = before.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
        val latestToday = sampleDao.latestSampleWithin(kind.rawValue, dayStart, nextDay, before)
        if (latestToday != null) {
            latestToday.dailyTotal?.let { return CumulativeMetricState(previousRawValue = previousRaw, dailyTotal = it) }
            if (!latestToday.isDelta) {
                return CumulativeMetricState(previousRawValue = previousRaw, dailyTotal = latestToday.rawValue ?: latestToday.value)
            }
        }
        return CumulativeMetricState(previousRawValue = previousRaw)
    }

    private enum class Plausibility { PLAUSIBLE, BAD_TIMESTAMP, BAD_HEART_RATE }

    /**
     * The one plausibility check behind [previewIngest] and [ingest] (upstream `isPlausible`):
     * `start` no earlier than the ring's counter epoch and no later than one day after [now]; a
     * heart rate whose truncated value is in [LiveHR.VALID_BPM]. A NaN or infinite heart rate is
     * implausible (it truncates to 0 or an `Int` bound here; upstream traps).
     */
    private fun plausibility(s: QuantitySample, now: Instant): Plausibility {
        if (s.start < SYNC_EPOCH_INSTANT || s.start > now.plusSeconds(FUTURE_TOLERANCE_SECONDS)) return Plausibility.BAD_TIMESTAMP
        if (s.kind == MetricKind.HEART_RATE && s.value.toInt() !in LiveHR.VALID_BPM) return Plausibility.BAD_HEART_RATE
        return Plausibility.PLAUSIBLE
    }

    private companion object {
        /** Cursor rows of the health-store writer share the table; they are not the ingest cursor. */
        const val HEALTH_CURSOR_PREFIX = "hk:"

        /** Cursor rows of the export watermark share the table; they are not the ingest cursor. */
        const val EXPORT_CURSOR_PREFIX = "export:"

        /** Clock-skew tolerance: a sample up to one day in the future is kept. */
        const val FUTURE_TOLERANCE_SECONDS = 86_400L

        val SYNC_EPOCH_INSTANT: Instant = Instant.ofEpochSecond(Command.SYNC_EPOCH)

        /** The sample with both times cut to the whole milliseconds the store keeps. */
        fun toStoredPrecision(s: QuantitySample): QuantitySample {
            val start = s.start.truncatedTo(ChronoUnit.MILLIS)
            val end = s.end.truncatedTo(ChronoUnit.MILLIS)
            return if (start == s.start && end == s.end) s else s.copy(start = start, end = end)
        }

        /** The first instant of [t]'s local day in [zone] (Foundation's `startOfDay(for:)`). */
        fun startOfDay(t: Instant, zone: ZoneId): Instant = t.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
    }
}
