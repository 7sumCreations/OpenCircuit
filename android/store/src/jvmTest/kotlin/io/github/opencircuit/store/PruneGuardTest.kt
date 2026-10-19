package io.github.opencircuit.store

import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The 30-day retention never deletes what Health Connect has not received (Kotlin-only, PORTING
 * D-300): a stored sample of a kind written to Health Connect from the store (heart rate, HRV,
 * SpO2, breathing rate, skin temperature) is kept past 30 days while it is still pending — above
 * zero and after its kind's mark, or with no mark yet — and a step delta while it is not marked
 * written. Everything else ages out as before (upstream LocalStore.swift:1058-1070): a written
 * sample, a zero reading (never written), a kind written elsewhere, a written step delta, every
 * daytime reading. The cutoff is unchanged: `start < now - 30 calendar days`.
 */
class PruneGuardTest {
    private val now = Instant.parse("2026-10-17T12:00:00Z")
    private val utc = ZoneOffset.UTC
    private val cutoff = Instant.parse("2026-09-17T12:00:00Z")
    private val old = cutoff.minusSeconds(86_400) // 31 days old

    private fun row(kind: MetricKind, at: Instant, value: Double) =
        StoredSampleEntity(kindRaw = kind.rawValue, start = at, end = at, value = value)

    private suspend fun StoreDatabase.samplesOf(kind: MetricKind) =
        sampleDao().allSamples().filter { it.kindRaw == kind.rawValue }.map { it.start }.sorted()

    @Test
    fun anUnwrittenSampleOlderThanThirtyDaysIsKeptAndAWrittenOneIsPruned() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            db.sampleDao().insertSamples(listOf(row(MetricKind.HEART_RATE, old, 61.0), row(MetricKind.HEART_RATE, old.plusSeconds(60), 62.0)))
            // Health Connect accepted the first only.
            store.markHealthWritten(listOf(QuantitySample(MetricKind.HEART_RATE, old, value = 61.0)))

            store.pruneExpiredSamples(now, utc)

            assertEquals(listOf(old.plusSeconds(60)), db.samplesOf(MetricKind.HEART_RATE))
        }
    }

    @Test
    fun aKindWithNoMarkYetKeepsEveryPositiveReading() = runBlocking<Unit> {
        withInMemoryStore { db ->
            // Health Connect never connected: nothing of the mirrored kinds was received.
            val kinds = LocalStore.HEALTH_MIRRORED_KINDS
            db.sampleDao().insertSamples(kinds.map { row(it, old, 0.5) })

            LocalStore(db).pruneExpiredSamples(now, utc)

            for (kind in kinds) assertEquals(listOf(old), db.samplesOf(kind), "$kind")
        }
    }

    @Test
    fun aZeroReadingAndAKindNotWrittenFromTheStoreAgeOutAsBefore() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.sampleDao().insertSamples(
                listOf(row(MetricKind.HEART_RATE, old, 0.0), row(MetricKind.RESTING_HEART_RATE, old, 52.0), row(MetricKind.HEART_RATE, cutoff, 0.0)),
            )

            LocalStore(db).pruneExpiredSamples(now, utc)

            assertEquals(listOf(cutoff), db.sampleDao().allSamples().map { it.start }, "a zero is never written; resting heart rate is not written from samples")
        }
    }

    @Test
    fun theMarkIsInclusiveAndTheCutoffUnchanged() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            val atCutoff = cutoff
            val justBefore = cutoff.minusMillis(1)
            db.sampleDao().insertSamples(listOf(row(MetricKind.SPO2, justBefore, 0.97), row(MetricKind.SPO2, atCutoff, 0.96), row(MetricKind.SPO2, old, 0.95)))
            // The mark sits exactly on the sample 1 ms before the cutoff: that one and the older are written.
            store.markHealthWritten(listOf(QuantitySample(MetricKind.SPO2, justBefore, value = 0.97)))

            store.pruneExpiredSamples(now, utc)

            assertEquals(listOf(atCutoff), db.samplesOf(MetricKind.SPO2), "written and older than 30 days: pruned; at the cutoff: kept as before")
        }
    }

    @Test
    fun aSampleBehindAMarkThatWasPulledBackIsKeptUntilWrittenAgain() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            db.sampleDao().insertSamples(listOf(row(MetricKind.RESPIRATORY_RATE, old, 14.0), row(MetricKind.RESPIRATORY_RATE, old.plusSeconds(60), 15.0)))
            store.markHealthWritten(listOf(QuantitySample(MetricKind.RESPIRATORY_RATE, old, value = 14.0)))
            // The mark is a cursor row: moved back (as a drain behind a live reading does), it re-opens the samples after it.
            db.sampleDao().upsertCursors(listOf(StoredCursorEntity("hk:" + MetricKind.RESPIRATORY_RATE.rawValue, old.minusMillis(1))))

            store.pruneExpiredSamples(now, utc)

            assertEquals(listOf(old, old.plusSeconds(60)), db.samplesOf(MetricKind.RESPIRATORY_RATE))
        }
    }

    @Test
    fun anUnwrittenStepDeltaIsKeptAndAWrittenOneIsPruned() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            db.dailyDao().insertStepSample(StoredStepSampleEntity(start = old, end = old.plusSeconds(900), delta = 40))
            db.dailyDao().insertStepSample(StoredStepSampleEntity(start = old.plusSeconds(900), end = old.plusSeconds(1800), delta = 25))
            store.markStepSamplesWritten(store.pendingStepSampleRows().take(1).map { it.id })

            store.pruneExpiredSamples(now, utc)

            assertEquals(listOf(old.plusSeconds(900)), db.dailyDao().stepSamples(Instant.EPOCH, now).map { it.start })
        }
    }

    @Test
    fun daytimeReadingsAreNotWrittenToHealthConnectAndAgeOutAsBefore() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.dailyDao().insertDaytimeTemp(StoredDaytimeTempEntity(time = old, celsius = 33.0))
            db.dailyDao().insertDaytimeTemp(StoredDaytimeTempEntity(time = cutoff, celsius = 33.1))

            LocalStore(db).pruneExpiredSamples(now, utc)

            assertEquals(listOf(cutoff), db.dailyDao().daytimeTemps(Instant.EPOCH, now).map { it.time })
        }
    }
}
