package io.github.opencircuit.app

import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.health.HealthPermission
import io.github.opencircuit.health.RecordType
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.store.LaunchRepairs
import io.github.opencircuit.store.LocalStore
import io.github.opencircuit.store.SleepStore
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What each launch repair does to a store the app itself filled, measured before the repairs run at
 * every launch: the kept seven-night backlog drained by Sync now through the real store (the nights
 * are in June, the phone's clock in October, so every sample is months old), then written to a
 * Health Connect that allows everything but blood oxygen. One run of [LaunchRepairs.run] on that
 * store, step by step: what it deleted, moved, filled, healed and repaired, and what it left —
 * every reading, written to Health Connect or not.
 */
class LaunchRepairsRealStoreHazardTest {

    private val everything = Instant.parse("2026-06-01T00:00:00Z") to Instant.parse("2026-07-01T00:00:00Z")

    @Test
    fun eachRepairOnAStoreTheAppFilled() = runTest {
        val h = healthWorld()
        h.hc.revoke(HealthPermission.OXYGEN_SATURATION)
        val w = syncWorld(reuseDb = h.db, afterCommit = { h.flush.request() }, makeRing = ringWith(BacklogPages.pages()))
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(3_000_000)
            testScheduler.advanceUntilIdle()

            val store = LocalStore(h.db)
            val sleep = SleepStore(h.db)
            val kinds = listOf(MetricKind.HEART_RATE, MetricKind.HRV_SDNN, MetricKind.SPO2, MetricKind.RESPIRATORY_RATE, MetricKind.TEMPERATURE)
            suspend fun counts() = kinds.associateWith { store.samples(it, everything.first, everything.second).size }
            suspend fun pending() = kinds.associateWith { store.pendingHealthSamples(it).size }
            val before = counts()
            val pendingBefore = pending()
            val nightsBefore = sleep.sleepSummaries(everything.first, everything.second)
            val cursorBefore = store.loadCursor()
            assertEquals(7, nightsBefore.size)
            assertTrue(h.hc.records(RecordType.HEART_RATE).isNotEmpty(), "heart rate reached Health Connect")
            assertTrue(pendingBefore.getValue(MetricKind.SPO2) > 0, "blood oxygen is not allowed: still pending")

            val now = SYNC_TEST_EPOCH.plus(Duration.ofDays(1))
            val report = LaunchRepairs.run(store, sleep, now, ZoneOffset.UTC)

            // 1. Everything is months old and most of it is in Health Connect: every reading is kept.
            val after = counts()
            assertEquals(before, after, "every reading of each kind is kept")
            assertTrue(kinds.any { before.getValue(it) > pendingBefore.getValue(it) }, "some kept readings were already written")
            assertEquals(pendingBefore, pending(), "nothing pending was lost")
            // 2-3. The purges find nothing the drain stored: ingest's own checks already kept it out.
            assertEquals(Result.success(0), report.heartRatePurge)
            assertEquals(Result.success(0), report.timestampPurge)
            // 4-6. The sleep history: nights already on their wake day, nothing to fill or heal.
            assertEquals(Result.success(Unit), report.nightRekey)
            assertEquals(Result.success(0), report.provenanceBackfill)
            assertEquals(Result.success(emptyList()), report.scoreHeal)
            val nightsAfter = sleep.sleepSummaries(everything.first, everything.second)
            assertEquals(nightsBefore, nightsAfter, "every night as it was")
            // 7. No cursor in the future: nothing repaired, the ingest cursor unchanged.
            assertEquals(Result.success(0), report.cursorRepair)
            assertEquals(cursorBefore, store.loadCursor())

            // A second launch: the purges are latched; the rest finds nothing more to do.
            val again = LaunchRepairs.run(store, sleep, now, ZoneOffset.UTC)
            assertEquals(Result.success(null), again.heartRatePurge)
            assertEquals(Result.success(null), again.timestampPurge)
            assertEquals(after, counts())
            assertEquals(nightsBefore, sleep.sleepSummaries(everything.first, everything.second))
        } finally {
            h.appScope.cancel()
            w.db.close()
        }
    }
}
