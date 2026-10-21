package io.github.opencircuit.store

import androidx.room3.ColumnInfo
import androidx.room3.withReadTransaction
import java.time.Instant

// What the store holds, as counts only, for Connection details' Storage line: every ring reading is
// kept on the phone for good, so this is where its growth is seen. Android-only (upstream has no
// such read).

/** One sample kind's stored row count (a `GROUP BY kind_raw` row). */
internal data class KindCount(
    @ColumnInfo(name = "kind_raw") val kindRaw: String,
    @ColumnInfo(name = "n") val n: Long,
)

/**
 * Rows per reading table (keyed by table name, every table present), samples per kind (keyed by
 * the stored kind name; a kind with none is absent), and the first and last stored sample's start
 * (null when no sample is stored). Counts and times only, never a reading's value.
 */
data class StoreFootprint(
    val rowsPerTable: Map<String, Long>,
    val samplesPerKind: Map<String, Long>,
    val firstSample: Instant?,
    val lastSample: Instant?,
)

/** Reads a [StoreFootprint] from one database, in one read transaction so the counts agree. */
class StoreFootprints internal constructor(
    private val db: StoreDatabase,
    private val sampleDao: SampleDao,
    private val dailyDao: DailyDao,
    private val sleepDao: SleepDao,
) {
    constructor(db: StoreDatabase) : this(db, db.sampleDao(), db.dailyDao(), db.sleepDao())

    /** Counts every reading table in full: meant for a diagnostics screen, never a per-frame path. */
    suspend fun read(): StoreFootprint = db.withReadTransaction {
        // One pass over the samples: their total is the sum of the per-kind counts.
        val perKind = sampleDao.countSamplesByKind().associate { it.kindRaw to it.n }
        StoreFootprint(
            rowsPerTable = linkedMapOf(
                "stored_sample" to perKind.values.sum(),
                "stored_daily" to dailyDao.countDailies(),
                "stored_step_sample" to dailyDao.countStepSamples(),
                "stored_daytime_temp" to dailyDao.countDaytimeTemps(),
                "stored_sleep_summary" to sleepDao.countSummaries(Instant.EPOCH, Instant.ofEpochMilli(Long.MAX_VALUE)),
                "stored_nap" to sleepDao.countNaps(),
            ),
            samplesPerKind = perKind,
            firstSample = sampleDao.firstSampleStart(),
            lastSample = sampleDao.lastSampleStart(),
        )
    }
}
