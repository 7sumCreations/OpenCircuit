package io.github.opencircuit.app

import io.github.opencircuit.app.data.LaunchHousekeeping
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.store.LocalStore
import io.github.opencircuit.store.StoreFactory
import io.github.opencircuit.store.openInMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The store's launch repairs (`LaunchRepairs.run`: the 30-day prune, the two one-time purges, the
 * night move, the provenance backfill, the score heal, the cursor repair) run once when the app
 * starts, on a background dispatcher, after the store opened (upstream runs them as launch tasks,
 * ios/OpenCircuit/App.swift:15-43). Before this the app never called them.
 */
class LaunchHousekeepingTest {

    private val now = Instant.parse("2026-10-17T12:00:00Z")
    private val old = Instant.parse("2026-09-10T12:00:00Z") // 37 days old

    @Test
    fun theRepairsRunOnTheGivenDispatcherAfterTheStoreOpensAndKeepWhatHealthConnectLacks() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val db = StoreFactory.openInMemory(io)
        val logs = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val store = LocalStore(db)
            store.insertLiveSamples(
                listOf(QuantitySample(MetricKind.HEART_RATE, old, value = 61.0), QuantitySample(MetricKind.HEART_RATE, old.plusSeconds(60), value = 62.0)),
                now = old,
            )
            store.markHealthWritten(listOf(QuantitySample(MetricKind.HEART_RATE, old, value = 61.0)))

            val job = LaunchHousekeeping({ db }, { now }, { ZoneOffset.UTC }, logs::add, io).start(scope)
            assertTrue(logs.isEmpty(), "started, not run inline on the caller")
            testScheduler.advanceUntilIdle()

            assertTrue(job.isCompleted)
            assertEquals(listOf(old.plusSeconds(60)), store.samples(MetricKind.HEART_RATE, Instant.EPOCH, now).map { it.start }, "the written one pruned, the unwritten one kept")
            val line = logs.single()
            assertTrue(line.startsWith("Launch repairs:"), line)
            assertTrue("prune ok" in line && "night move ok" in line && "cursor repair 0" in line, line)
            assertTrue(Regex("""\d{2,}\.\d""").find(line) == null, "no reading in the log: $line")
        } finally {
            scope.cancel()
            db.close()
        }
    }

    @Test
    fun aStoreThatCannotOpenRunsNoRepairAndSaysSo() = runTest {
        val logs = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            LaunchHousekeeping({ error("the file is not a database") }, { now }, { ZoneOffset.UTC }, logs::add, StandardTestDispatcher(testScheduler)).start(scope)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("Launch repairs: the store did not open (IllegalStateException); none ran"), logs)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun theAppStartsThemOnceOnItsProcessScopeOffTheMainThread() {
        val container = File("src/main/kotlin/io/github/opencircuit/app/AppContainer.kt").readText()
        assertEquals(1, Regex("""LaunchHousekeeping\(""").findAll(container).count(), "built once")
        assertTrue("LaunchHousekeeping({ database.await() }, wallNow, ZoneId::systemDefault, log).start(appScope)" in container)
        val housekeeping = File("src/main/kotlin/io/github/opencircuit/app/data/LaunchHousekeeping.kt").readText()
        assertTrue("background: CoroutineContext = Dispatchers.IO" in housekeeping, "the default is the IO dispatcher")
    }
}
