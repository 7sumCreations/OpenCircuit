package io.github.opencircuit.app.health

import io.github.opencircuit.health.HealthPeriodStore
import io.github.opencircuit.health.PeriodToWrite
import io.github.opencircuit.store.LocalStore
import io.github.opencircuit.store.StoreDatabase
import java.time.Instant
import java.time.ZoneId

/**
 * The logged periods for the Health Connect writer, over the app's store: the periods to look at
 * (`LocalStore.periodEntriesNeedingHealthMirror`) and the record ids written for each, kept in the
 * period row (`LocalStore.recordPeriodEntryHealthIds`).
 */
class StoreHealthPeriods(private val database: suspend () -> StoreDatabase) : HealthPeriodStore {
    override suspend fun periodsNeedingMirror(today: Instant, zone: ZoneId): List<PeriodToWrite> =
        LocalStore(database()).periodEntriesNeedingHealthMirror(today, zone).map {
            PeriodToWrite(it.start, it.end, it.flowLevelRaw, it.healthWritten, it.hkSampleUUIDs)
        }

    override suspend fun recordWrittenIds(entry: PeriodToWrite, ids: List<String>, finalized: Boolean) =
        LocalStore(database()).recordPeriodEntryHealthIds(entry.start, ids, finalized, writtenEnd = entry.end, writtenFlowLevelRaw = entry.flowLevelRaw)
}
