package io.github.opencircuit.app

import io.github.opencircuit.app.live.StoreLiveReadings
import io.github.opencircuit.app.session.RingSessionController
import io.github.opencircuit.app.sync.HistoryStore
import io.github.opencircuit.app.sync.SessionHistory
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.health.Distance
import io.github.opencircuit.health.RecordType
import io.github.opencircuit.health.Steps
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.store.LocalStore
import io.github.opencircuit.store.StoreDatabase
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole live-steps path, as the app wires it: a real descriptor through the session's
 * dispatcher → the step fold → the real store → the writer over the store's adapter → Health
 * Connect (the in-house fake with the platform's rules). Steps and then their distance land; the
 * rows are marked, so a relaunch (a second writer over the same database) writes them no more.
 */
class LiveStepsToHealthConnectTest {
    private val wallStart = Instant.parse("2026-10-16T10:00:00Z")

    /** The real worn `0x10` descriptor (81 steps), its `[4:6]` set on the raw byte path. */
    private fun descriptor(steps: Int): ByteArray = hex("105402000051011f012500000000105400ff96").also {
        it[4] = (steps ushr 8).toByte()
        it[5] = (steps and 0xff).toByte()
    }

    private fun TestScope.session(db: StoreDatabase): TimedRingLink {
        val wall = { wallStart.plusMillis(testScheduler.currentTime) }
        val link = timedLink()
        RingSessionController(
            link, backgroundScope, monotonicMillis = { testScheduler.currentTime }, log = {},
            history = SessionHistory(
                HistoryStore.NONE, wallClock = wall, disconnectAfterSync = { false }, zone = { ZoneOffset.UTC },
                live = StoreLiveReadings({ db }, wall, { ZoneOffset.UTC }),
            ),
        ).start()
        testScheduler.runCurrent()
        link.fake.setState(LinkState.Authenticated)
        testScheduler.runCurrent()
        return link
    }

    @Test
    fun liveStepsAreWrittenThenTheirDistanceAndNotAgainAfterARelaunch() = runTest {
        val world = healthWorld(wallStart = wallStart, activity = true)
        val ring = session(world.db)
        advanceTo(7 * 60_000L)
        ring.fake.emitFrame(descriptor(81))
        testScheduler.runCurrent()
        advanceTo(8 * 60_000L)
        ring.fake.emitFrame(descriptor(120))
        testScheduler.runCurrent()

        world.flush.request()
        testScheduler.runCurrent()

        val steps = world.hc.records(RecordType.STEPS).map { it as Steps }.sortedBy { it.start }
        assertEquals(listOf(81L, 39L), steps.map { it.count })
        assertEquals(listOf(Instant.parse("2026-10-16T10:00:00Z"), Instant.parse("2026-10-16T10:07:00Z")), steps.map { it.start })
        assertEquals(steps.map { it.start }, world.hc.records(RecordType.DISTANCE).map { (it as Distance).start }.sorted())
        assertTrue(LocalStore(world.db).pendingStepSampleRows().isEmpty(), "the rows are marked")

        // A relaunch: a second writer over the same database.
        val again = healthWorld(wallStart = wallStart, activity = true, reuseDb = world.db)
        again.flush.request()
        testScheduler.runCurrent()
        assertTrue(again.hc.calls.none { it.type == RecordType.STEPS || it.type == RecordType.DISTANCE }, "nothing to write again")
    }

    /**
     * Energy, resting heart rate and basal rate over the real store: written once, and what each
     * keeps (the buckets' kilocalories, the day marks, in the store's key-value table) survives a
     * relaunch, so a second writer over the same database writes none of them again.
     */
    @Test
    fun energyAndTheDailyRecordsAreWrittenOnceAcrossARelaunch() = runTest {
        val afternoon = Instant.parse("2026-10-16T13:00:00Z")
        val world = healthWorld(wallStart = afternoon, activity = true)
        // A morning walk the history drain stored: 130 bpm, one reading per 150 s epoch, 09:00-09:30.
        val walk = (0 until 12).map { QuantitySample(MetricKind.HEART_RATE, Instant.parse("2026-10-16T09:00:00Z").plusSeconds(it * 150L), value = 130.0) }
        LocalStore(world.db).ingest(walk, afternoon, ZoneOffset.UTC)

        world.flush.request()
        testScheduler.runCurrent()

        val daily = setOf(RecordType.ACTIVE_CALORIES_BURNED, RecordType.RESTING_HEART_RATE, RecordType.BASAL_METABOLIC_RATE)
        assertEquals(mapOf(RecordType.ACTIVE_CALORIES_BURNED to 2, RecordType.RESTING_HEART_RATE to 1, RecordType.BASAL_METABOLIC_RATE to 1), world.flush.state.value.last?.written?.filterKeys { it in daily })

        val again = healthWorld(wallStart = afternoon, activity = true, reuseDb = world.db)
        again.flush.request()
        testScheduler.runCurrent()
        assertTrue(again.hc.calls.none { it.type in daily }, "${again.hc.calls}")
    }
}
