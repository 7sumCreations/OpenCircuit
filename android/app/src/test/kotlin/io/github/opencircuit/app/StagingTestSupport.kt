package io.github.opencircuit.app

import io.github.opencircuit.app.sync.NightWriter
import io.github.opencircuit.app.sync.StoreHistory
import io.github.opencircuit.app.sync.StoreNightWriter
import io.github.opencircuit.app.sync.SyncEvidence
import io.github.opencircuit.ringkit.HistoryChannelOutcome
import io.github.opencircuit.ringkit.MeasuredCoverage
import io.github.opencircuit.ringkit.SleepPersistOutcome
import io.github.opencircuit.ringkit.SleepStaging
import io.github.opencircuit.store.RederivedNight
import io.github.opencircuit.store.SleepNightExtras
import io.github.opencircuit.store.SleepStore
import io.github.opencircuit.store.SleepStoreException
import io.github.opencircuit.store.StoreDatabase
import java.time.Instant
import java.time.ZoneId

/**
 * The real store's sleep half, counting the commit's calls; with [savesBeforeDeferral] set, every
 * save after that many throws the store's own `NightKeyMigrationPending`, writing nothing — the
 * store's state while its one-time move of stored nights cannot complete (only reachable from inside
 * the store, so it is simulated here at the store's own boundary).
 */
internal class CountingNightWriter(private val real: NightWriter) : NightWriter by real {
    constructor(db: StoreDatabase) : this(StoreNightWriter(SleepStore(db)))

    var savesBeforeDeferral: Int? = null
    var saves = 0
        private set
    var deferred = 0
        private set
    var rederiveCalls = 0
        private set

    override suspend fun saveSleepSummary(
        summary: SleepStaging.Summary,
        night: Instant,
        inBedStart: Instant,
        inBedEnd: Instant,
        sleepOnset: Instant,
        sleepWake: Instant,
        extras: SleepNightExtras,
        now: Instant,
        zone: ZoneId,
    ): SleepPersistOutcome {
        savesBeforeDeferral?.let { allowed ->
            if (saves >= allowed) {
                deferred++
                throw SleepStoreException.NightKeyMigrationPending()
            }
        }
        saves++
        return real.saveSleepSummary(summary, night, inBedStart, inBedEnd, sleepOnset, sleepWake, extras, now, zone)
    }

    override suspend fun rederiveEditedNightProvenance(coverage: MeasuredCoverage, now: Instant, zone: ZoneId): List<RederivedNight> {
        rederiveCalls++
        return real.rederiveEditedNightProvenance(coverage, now, zone)
    }
}

/** What a sync whose sleep channel drained the whole backlog tells the commit. */
internal val SLEEP_COMPLETE = SyncEvidence(HistoryChannelOutcome.COMPLETE, sleepRecordsAdded = 3654)

/** The commit's time in these tests: months after the backlog, so every channel's end is long past. */
internal val STAGING_NOW: Instant = Instant.parse("2026-10-08T09:00:00Z")

/** Journals every page in drain 1, as the history routes do. */
internal suspend fun StoreHistory.journal(pages: List<ByteArray>, at: Instant = STAGING_NOW) {
    for (page in pages) append(page, at, drainId = 1)
}

/** [count] records after the backlog's last one, 150 s apart: its last record's bytes with the counter moved (raw path). */
internal fun recordsAfterTheBacklog(count: Int): List<ByteArray> {
    val last = BacklogPages.records.last()
    val base = BacklogPages.counter(last)
    return (1..count).map { k ->
        val c = base + 150L * k
        last.copyOf().also { r ->
            r[0] = (c ushr 24).toByte(); r[1] = (c ushr 16).toByte(); r[2] = (c ushr 8).toByte(); r[3] = c.toByte()
        }
    }
}
