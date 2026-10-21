package io.github.opencircuit.app

import io.github.opencircuit.app.health.HealthFlush
import io.github.opencircuit.app.health.StoreHealthActivity
import io.github.opencircuit.app.health.StoreHealthFailures
import io.github.opencircuit.app.health.StoreHealthPeriods
import io.github.opencircuit.app.health.StoreHealthSamples
import io.github.opencircuit.app.health.StoreHealthSleep
import io.github.opencircuit.app.health.StoreWriteGenerations
import io.github.opencircuit.health.FakeHealthConnect
import io.github.opencircuit.health.HealthActivityStore
import io.github.opencircuit.health.HealthConnectPort
import io.github.opencircuit.health.HealthFeature
import io.github.opencircuit.health.HealthPermission
import io.github.opencircuit.health.HealthRecord
import io.github.opencircuit.health.HealthSdkStatus
import io.github.opencircuit.health.HealthWriter
import io.github.opencircuit.health.RecordType
import io.github.opencircuit.health.RingDevice
import io.github.opencircuit.health.WriteGeneration
import io.github.opencircuit.store.StoreDatabase
import io.github.opencircuit.store.StoreFactory
import io.github.opencircuit.store.openInMemory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import java.time.Instant
import java.time.ZoneOffset

/**
 * The Health Connect side of a sync test, wired as the app wires it: the writer over the real
 * store, a fake Health Connect with the platform's rules, and the flush on a scope of its own
 * (the app's process scope; never the session's). [gate] holds every insert until opened; [port]
 * is what the writer talks to, and records every call that reached Health Connect.
 */
internal class HealthWorld(
    val db: StoreDatabase,
    val hc: FakeHealthConnect,
    val gate: InsertGate,
    val appScope: CoroutineScope,
    val flush: HealthFlush,
    val port: RecordingPort,
)

/**
 * Every call made to Health Connect, by method name, in order — all six of the port's methods, so
 * "no call of any kind" can be asserted as an empty list rather than as one method not called.
 */
internal class RecordingPort(private val inner: HealthConnectPort) : HealthConnectPort {
    private val seen = mutableListOf<String>()

    val calls: List<String> get() = synchronized(seen) { seen.toList() }

    private fun note(name: String) = synchronized(seen) { seen += name }

    override suspend fun sdkStatus(): HealthSdkStatus = inner.sdkStatus().also { note("sdkStatus") }

    override suspend fun grantedWrites(): Set<HealthPermission> = inner.grantedWrites().also { note("grantedWrites") }

    override suspend fun featureAvailable(feature: HealthFeature): Boolean = inner.featureAvailable(feature).also { note("featureAvailable") }

    override suspend fun insert(type: RecordType, records: List<HealthRecord>): List<String> {
        note("insert")
        return inner.insert(type, records)
    }

    override suspend fun deleteByClientIds(type: RecordType, clientRecordIds: List<String>) {
        note("deleteByClientIds")
        inner.deleteByClientIds(type, clientRecordIds)
    }

    override suspend fun readOwn(type: RecordType, from: Instant, until: Instant): List<HealthRecord> =
        inner.readOwn(type, from, until).also { note("readOwn") }
}

/** Holds each insert at its start while closed (open by default). Records the size of each insert it saw. */
internal class InsertGate(private val hc: FakeHealthConnect) : HealthConnectPort by hc {
    @Volatile private var held: CompletableDeferred<Unit>? = null
    private val seen = mutableListOf<Int>()

    val entered: List<Int> get() = synchronized(seen) { seen.toList() }

    fun close() { held = CompletableDeferred() }

    fun open() {
        held?.complete(Unit)
        held = null
    }

    override suspend fun insert(type: RecordType, records: List<HealthRecord>): List<String> {
        synchronized(seen) { seen += records.size }
        held?.await()
        return hc.insert(type, records)
    }
}

/** The world on this test's scheduler; pass [HealthWorld.db] to `syncWorld(reuseDb = …)` and `afterCommit = { it.flush.request() }`. */
internal suspend fun TestScope.healthWorld(
    wallStart: Instant = SYNC_TEST_EPOCH,
    /** Write steps, energy and the daily records too (the app does); off for the tests that count only sync data. */
    activity: Boolean = false,
    /** The store to write from; a fresh in-memory one when null. */
    reuseDb: StoreDatabase? = null,
    /** The Settings switch as the flush reads it; ON for the tests of the writing itself. */
    enabled: () -> Boolean = { true },
): HealthWorld {
    val db = reuseDb ?: StoreFactory.openInMemory(StandardTestDispatcher(testScheduler))
    val wall = { wallStart.plusMillis(testScheduler.currentTime) }
    val hc = FakeHealthConnect(wall).apply { grantAll() }
    val gate = InsertGate(hc)
    val port = RecordingPort(gate)
    val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
    val writer = HealthWriter(
        port = port,
        store = StoreHealthSamples { db },
        generation = WriteGeneration(StoreWriteGenerations({ db }, wall)),
        failureStore = StoreHealthFailures({ db }, wall, log = {}),
        clock = wall,
        zone = { ZoneOffset.UTC },
        device = { RingDevice("Gen 2") },
        sleep = StoreHealthSleep({ db }, wall, { ZoneOffset.UTC }),
        activity = if (activity) StoreHealthActivity({ db }, wall, { ZoneOffset.UTC }) else HealthActivityStore.NONE,
        periods = StoreHealthPeriods { db },
    )
    return HealthWorld(db, hc, gate, appScope, HealthFlush(writer::flush, appScope, log = {}, enabled = enabled), port)
}
