package io.github.opencircuit.app

import io.github.opencircuit.app.data.PrefsAppPrefs
import io.github.opencircuit.app.health.HealthAccess
import io.github.opencircuit.app.health.HealthAccessMemory
import io.github.opencircuit.app.health.HealthAccessState
import io.github.opencircuit.app.health.HealthFlush
import io.github.opencircuit.app.health.HealthSwitch
import io.github.opencircuit.app.health.StoreHealthAccessMemory
import io.github.opencircuit.app.health.StoreHealthFailures
import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.health.FakeHealthConnect
import io.github.opencircuit.health.FlushResult
import io.github.opencircuit.health.HealthConnectPort
import io.github.opencircuit.health.HealthPermission
import io.github.opencircuit.health.RecordType
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.store.LocalStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The "Send to Health Connect" switch gates every way the app reaches Health Connect. While it is
 * OFF — the default — a real sync's commit, Write now, the launch / resume / after-permission-screen
 * reads and the permission screen's answer make no Health Connect call of any kind (all six of the
 * port's methods are recorded, so an empty list means none), and what the sync stored stays pending
 * under its marks. Turning it ON writes that backlog through the same writer.
 */
class HealthSwitchGateTest {

    @Test
    fun whileOffASyncWriteNowAndEveryAccessReadMakeNoHealthConnectCall() = runTest {
        val values = InMemoryKeyValues()
        val switch = HealthSwitch(PrefsAppPrefs(values), log = {})
        val h = healthWorld(enabled = switch::isOn)
        val wall = { SYNC_TEST_EPOCH.plusMillis(testScheduler.currentTime) }
        val access = HealthAccess(
            h.port,
            StoreHealthAccessMemory({ h.db }, wall, StoreHealthFailures({ h.db }, wall, log = {})),
            h.appScope,
            log = {},
            enabled = switch::isOn,
        )
        val w = syncWorld(reuseDb = h.db, afterCommit = { h.flush.request() }, makeRing = ringWith(HistoryTestPages.backlog(3)))
        try {
            access.refresh() // at launch
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow) // a real sync, through the post-commit seam
            advanceTo(60_000)
            h.flush.request() // Write now
            access.refresh() // a resume; a permission screen or settings page that could not be shown
            access.permissionScreenAnswered()
            advanceTo(61_000)

            assertNotNull(w.session.sync.state.value.last?.commit, "the sync committed")
            assertEquals(emptyList(), h.port.calls, "no Health Connect call of any kind while OFF")
            assertTrue(h.hc.records(RecordType.HEART_RATE).isEmpty())
            assertFalse(h.flush.state.value.running)
            assertTrue(LocalStore(h.db).pendingHealthSamples(MetricKind.HEART_RATE).isNotEmpty(), "the synced readings stay pending")
            assertEquals(null, access.state.value.available, "Health Connect's state was never read")

            // ON: the backlog goes through the same writer, from the marks.
            assertTrue(switch.set(true))
            access.refresh()
            h.flush.request()
            advanceTo(62_000)

            assertTrue("sdkStatus" in h.port.calls)
            assertTrue("insert" in h.port.calls)
            assertTrue(h.hc.records(RecordType.HEART_RATE).isNotEmpty(), "what the sync stored while OFF is written once ON")
            assertEquals(emptyList(), LocalStore(h.db).pendingHealthSamples(MetricKind.HEART_RATE), "accepted and marked")
            assertEquals(true, access.state.value.available)
        } finally {
            h.appScope.cancel()
            w.db.close()
        }
    }

    /**
     * Turning it OFF stops a write that is already running: the call inside Health Connect at that
     * moment finishes, and not one call follows it — the rest stays pending under its marks, and
     * the stop is not a failure (no write error, no failed type).
     */
    @Test
    fun turningOffMidWriteStopsTheRunningWriteAtItsNextCall() = runTest {
        val values = InMemoryKeyValues().apply { putRaw("health.write.enabled.v1", true) }
        val switch = HealthSwitch(PrefsAppPrefs(values), log = {})
        val h = healthWorld(enabled = switch::isOn, activity = true)
        val w = syncWorld(reuseDb = h.db, makeRing = ringWith(HistoryTestPages.backlog(3)))
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(60_000)
            assertNotNull(w.session.sync.state.value.last?.commit)

            h.gate.close() // the first insert is inside Health Connect and does not answer yet
            h.flush.request()
            advanceTo(61_000)
            assertEquals(1, h.gate.entered.size)
            val before = h.port.calls.size

            assertTrue(switch.set(false))
            h.gate.open()
            advanceTo(62_000)

            assertEquals(emptyList(), h.port.calls.drop(before), "no Health Connect call after OFF")
            assertFalse(h.flush.state.value.running)
            assertEquals(null, h.flush.state.value.error, "stopping is not a failure")
            assertEquals(emptyMap(), StoreHealthFailures({ h.db }, { SYNC_TEST_EPOCH }, log = {}).load(), "no type marked failed")
        } finally {
            h.appScope.cancel()
            w.db.close()
        }
    }

    /**
     * Turning it OFF forgets what was read (a later ON shows "checking", never an old answer), and a
     * read that was running when it went OFF does not bring its answer back.
     */
    @Test
    fun turningOffForgetsTheAccessAndALateReadDoesNotComeBack() = runTest {
        var on = true
        val hc = FakeHealthConnect({ SYNC_TEST_EPOCH }).apply { grantAll() }
        val held = CompletableDeferred<Unit>()
        val slow = object : HealthConnectPort by hc {
            override suspend fun grantedWrites(): Set<HealthPermission> {
                held.await()
                return hc.grantedWrites()
            }
        }
        val memory = object : HealthAccessMemory {
            override suspend fun asked() = true
            override suspend fun markAsked() = Unit
            override suspend fun failures(): Map<RecordType, Instant> = emptyMap()
        }
        val access = HealthAccess(slow, memory, backgroundScope, log = {}, enabled = { on })
        access.refresh()
        runCurrent()
        held.complete(Unit)
        runCurrent()
        assertEquals(true, access.state.value.available)

        val second = CompletableDeferred<Unit>()
        val slower = object : HealthConnectPort by hc {
            override suspend fun grantedWrites(): Set<HealthPermission> {
                second.await()
                return hc.grantedWrites()
            }
        }
        val again = HealthAccess(slower, memory, backgroundScope, log = {}, enabled = { on })
        again.refresh()
        runCurrent() // inside grantedWrites
        on = false
        again.forget()
        access.forget()
        second.complete(Unit)
        runCurrent()

        assertEquals(HealthAccessState(), access.state.value, "forgotten")
        assertEquals(HealthAccessState(), again.state.value, "the late answer was not published")
    }

    /** Turning it OFF stops writing: a write queued behind the running one does not start. */
    @Test
    fun aWriteQueuedBehindARunningOneDoesNotStartOnceTurnedOff() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var on = true
        var runs = 0
        val release = CompletableDeferred<Unit>()
        val flush = HealthFlush({
            if (++runs == 1) release.await()
            FlushResult(Instant.EPOCH)
        }, appScope, log = {}, enabled = { on })

        flush.request()
        runCurrent()
        flush.request() // queued behind the first
        on = false
        release.complete(Unit)
        runCurrent()

        assertEquals(1, runs, "the queued write did not start")
        assertFalse(flush.state.value.running)
        appScope.cancel()
    }

    /** A request made while OFF is dropped, not kept: turning ON alone writes nothing until the next sync or Write now. */
    @Test
    fun aRequestWhileOffIsNotKeptForLater() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var on = false
        var runs = 0
        val flush = HealthFlush({ runs++; FlushResult(Instant.EPOCH) }, appScope, log = {}, enabled = { on })

        flush.request()
        runCurrent()
        assertEquals(0, runs)
        assertFalse(flush.state.value.running, "nothing shows as writing while OFF")

        on = true
        runCurrent()
        assertEquals(0, runs)
        flush.request()
        runCurrent()
        assertEquals(1, runs)
        appScope.cancel()
    }
}
