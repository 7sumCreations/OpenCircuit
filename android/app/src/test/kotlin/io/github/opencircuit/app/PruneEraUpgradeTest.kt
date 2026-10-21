package io.github.opencircuit.app

import io.github.opencircuit.app.data.LaunchHousekeeping
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.store.BlobStore
import io.github.opencircuit.store.LocalStore
import io.github.opencircuit.store.StoreDatabase
import io.github.opencircuit.store.StoreFactory
import io.github.opencircuit.store.openInMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Upgrading from a release that pruned readings after 30 days loses nothing. The store is shaped as
 * such an install leaves it: Health Connect was asked for access and received every reading (the
 * per-kind marks sit past them all, the step deltas are marked written), and every reading table
 * holds rows older than 30 days. The first launch of this version — the real [LaunchHousekeeping]
 * the app starts at process start — and a second launch over the same database keep every row.
 */
class PruneEraUpgradeTest {

    private val now = Instant.parse("2026-10-21T09:00:00Z")
    private val zone = ZoneOffset.UTC
    private val ages = listOf(31L, 45L, 200L).map { now.minusSeconds(it * 86_400) }
    private val mirrored = listOf(MetricKind.HEART_RATE, MetricKind.HRV_SDNN, MetricKind.SPO2, MetricKind.RESPIRATORY_RATE, MetricKind.TEMPERATURE)

    private fun value(kind: MetricKind) = when (kind) {
        MetricKind.HEART_RATE -> 58.0
        MetricKind.HRV_SDNN -> 41.0
        MetricKind.SPO2 -> 0.97
        MetricKind.RESPIRATORY_RATE -> 14.5
        else -> 33.6
    }

    private data class Snapshot(
        val samples: Map<MetricKind, List<QuantitySample>>,
        val steps: List<String>,
        val daytime: List<String>,
        val dailies: List<String>,
    )

    private suspend fun LocalStore.snapshot(): Snapshot = Snapshot(
        samples = (mirrored + MetricKind.RESTING_HEART_RATE).associateWith { samples(it, Instant.EPOCH, now) },
        steps = stepSampleRows(Instant.EPOCH, now).map { "${it.sample.start}|${it.sample.delta}" },
        daytime = daytimeTemperatures(Instant.EPOCH, now).map { "${it.time}|${it.celsius}" },
        dailies = dailies(Instant.EPOCH, now).map { "${it.day}|${it.steps}" },
    )

    private suspend fun fillAsAPruningReleaseLeftIt(db: StoreDatabase) {
        val store = LocalStore(db)
        val readings = ages.flatMap { at -> mirrored.map { QuantitySample(it, at, value = value(it)) } } +
            ages.map { QuantitySample(MetricKind.RESTING_HEART_RATE, it, value = 52.0) } +
            ages.map { QuantitySample(MetricKind.HRV_SDNN, it.plusSeconds(60), value = 0.0) }
        store.ingest(readings, now, zone)
        store.markHealthWritten(readings.filter { it.kind in mirrored && it.value > 0 })
        for (at in ages) {
            store.addDailySteps(delta = 120, day = at.plusSeconds(900), windowStart = at, now = now, zone = zone)
            store.recordDaytimeTemperature(33.2, at)
        }
        store.markStepSamplesWritten(store.pendingStepSampleRows().map { it.id })
        BlobStore(db).saveHealthPermissionsRequested(ages.last())
    }

    private fun TestScope.launch(db: StoreDatabase, logs: MutableList<String>) {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            LaunchHousekeeping({ db }, { now }, { zone }, logs::add, StandardTestDispatcher(testScheduler)).start(scope)
            testScheduler.advanceUntilIdle()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun theFirstAndSecondLaunchAfterAPruningReleaseKeepEveryReading() = runTest {
        val db = StoreFactory.openInMemory(StandardTestDispatcher(testScheduler))
        try {
            fillAsAPruningReleaseLeftIt(db)
            val store = LocalStore(db)
            val before = store.snapshot()
            assertTrue(BlobStore(db).loadHealthPermissionsRequested(), "the install had asked Health Connect for access")
            for (kind in mirrored) assertTrue(store.pendingHealthSamples(kind).isEmpty(), "every $kind reading is behind its mark")
            assertTrue(store.pendingStepSampleRows().isEmpty(), "every step delta is marked written")
            assertEquals(3, before.samples.getValue(MetricKind.SPO2).size)
            assertEquals(6, before.samples.getValue(MetricKind.HRV_SDNN).size, "three readings and three zeros")
            assertEquals(3, before.steps.size)
            assertEquals(3, before.daytime.size)
            assertEquals(3, before.dailies.size)

            val logs = CopyOnWriteArrayList<String>()
            launch(db, logs)
            assertEquals(before, LocalStore(db).snapshot(), "the first launch of this version")
            launch(db, logs)
            assertEquals(before, LocalStore(db).snapshot(), "a second launch over the same database")

            assertEquals(2, logs.size)
            assertTrue(logs.none { "prune" in it }, "no retention step is reported: $logs")
        } finally {
            db.close()
        }
    }
}
