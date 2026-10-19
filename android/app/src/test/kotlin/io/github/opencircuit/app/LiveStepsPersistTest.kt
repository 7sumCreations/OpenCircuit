package io.github.opencircuit.app

import io.github.opencircuit.app.live.LiveReadingStore
import io.github.opencircuit.app.live.LiveReadings
import io.github.opencircuit.app.live.StoreLiveReadings
import io.github.opencircuit.app.session.RingSessionController
import io.github.opencircuit.app.sync.HistoryStore
import io.github.opencircuit.app.sync.SessionHistory
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.store.BlobStore
import io.github.opencircuit.store.LocalStore
import io.github.opencircuit.store.StepSample
import io.github.opencircuit.store.StoreDatabase
import io.github.opencircuit.store.StoreFactory
import io.github.opencircuit.store.openInMemory
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The ring's step bucket, as the descriptor brings it, kept in the store: a real `0x10` / `0x87`
 * descriptor goes through the session's own frame dispatcher (the route every descriptor takes),
 * is folded by `StepAccumulator` against the stored baseline, and lands as a step delta with its
 * window (`addDailySteps`) — upstream `ios/OpenCircuit/BLE/RingSession.swift:4905-4986` @ b1c2fdd.
 * A relaunch is a second session over the same database: the baseline is read back, so the bucket
 * is never credited twice.
 */
class LiveStepsPersistTest {
    private val wallStart = Instant.parse("2026-10-16T10:00:00Z")
    private val utc: ZoneId = ZoneOffset.UTC

    /** A real worn `0x10` descriptor carrying 81 steps (`RingKitVerifyTest.descriptorSteps`). */
    private val descriptor81 = "105402000051011f012500000000105400ff96"

    /** The same real descriptor with `[4:6]` set to [steps], big-endian, on the raw byte path. */
    private fun descriptor(steps: Int): ByteArray = hex(descriptor81).also {
        it[4] = (steps ushr 8).toByte()
        it[5] = (steps and 0xff).toByte()
    }

    private class World(val db: StoreDatabase, val link: TimedRingLink, val session: RingSessionController)

    private suspend fun TestScope.world(db: StoreDatabase? = null, zone: ZoneId = utc): World {
        val store = db ?: StoreFactory.openInMemory(StandardTestDispatcher(testScheduler))
        val wall = { wallStart.plusMillis(testScheduler.currentTime) }
        val link = timedLink()
        val session = RingSessionController(
            link, backgroundScope, monotonicMillis = { testScheduler.currentTime }, log = {},
            history = SessionHistory(
                HistoryStore.NONE, wallClock = wall, disconnectAfterSync = { false }, zone = { zone },
                live = StoreLiveReadings({ store }, wall, { zone }),
            ),
        )
        session.start()
        testScheduler.runCurrent()
        link.fake.setState(LinkState.Authenticated)
        testScheduler.runCurrent()
        return World(store, link, session)
    }

    private fun TestScope.ringSends(world: World, frame: ByteArray, atMillis: Long) {
        advanceTo(atMillis)
        world.link.fake.emitFrame(frame)
        testScheduler.runCurrent()
    }

    private suspend fun World.steps(): List<StepSample> = LocalStore(db).stepSamples(Instant.EPOCH, Instant.parse("2100-01-01T00:00:00Z"))

    private fun at(millis: Long): Instant = wallStart.plusMillis(millis)

    @Test
    fun aRealDescriptorsStepBucketIsStoredFromTheStartOfItsQuarterHour() = runTest {
        val world = world()
        ringSends(world, hex(descriptor81), atMillis = 7 * 60_000L) // 10:07

        // No baseline yet: the bucket so far is credited whole, over the window since 10:00 (10:05 lagged, floored).
        assertEquals(listOf(StepSample(Instant.parse("2026-10-16T10:00:00Z"), at(7 * 60_000L), 81, false)), world.steps())
        assertEquals(81L, LocalStore(world.db).todaySteps(at(0), utc))
    }

    @Test
    fun aClimbingBucketAddsOnlyTheIncrementOverTheWindowSinceTheLastReading() = runTest {
        val world = world()
        ringSends(world, hex(descriptor81), atMillis = 7 * 60_000L)
        ringSends(world, descriptor(120), atMillis = 8 * 60_000L)
        ringSends(world, descriptor(120), atMillis = 9 * 60_000L) // no change: nothing stored

        assertEquals(listOf(81L, 39L), world.steps().map { it.delta })
        assertEquals(at(7 * 60_000L), world.steps()[1].start, "the window starts at the previous reading")
        assertEquals(120L, LocalStore(world.db).todaySteps(at(0), utc))
    }

    /** The bucket rolled at 10:15: the new bucket's count is credited whole. The `0x87` answer counts too. */
    @Test
    fun aBucketThatRolledIsCreditedWholeAndTheDescriptorAnswerCounts() = runTest {
        val world = world()
        ringSends(world, hex(descriptor81), atMillis = 7 * 60_000L)
        ringSends(world, hex("8754030000510138013a00000000105402ff3a").also { it[5] = 12 }, atMillis = 17 * 60_000L)

        assertEquals(listOf(81L, 12L), world.steps().map { it.delta })
        assertEquals(93L, LocalStore(world.db).todaySteps(at(0), utc))
    }

    @Test
    fun aRelaunchFoldsAgainstTheStoredBaselineInsteadOfCreditingTheBucketAgain() = runTest {
        val first = world()
        ringSends(first, hex(descriptor81), atMillis = 7 * 60_000L)

        // A new process: a second session and recorder over the same database, nothing in memory.
        val second = world(db = first.db)
        ringSends(second, descriptor(100), atMillis = 9 * 60_000L)

        assertEquals(listOf(81L, 19L), second.steps().map { it.delta })
        assertEquals(100L, LocalStore(second.db).todaySteps(at(0), utc))
        assertEquals(100, BlobStore(second.db).loadStepBaseline()?.raw)
    }

    /** Local midnight: the first reading of the new day is credited whole to that day, its window from midnight. */
    @Test
    fun theFirstReadingOfANewLocalDayStartsThatDay() = runTest {
        val world = world()
        ringSends(world, hex(descriptor81), atMillis = (13 * 60 + 58) * 60_000L) // 23:58
        ringSends(world, descriptor(12), atMillis = (14 * 60 + 1) * 60_000L) // 00:01 the next day

        val (yesterday, today) = world.steps()
        assertEquals(81L, yesterday.delta)
        assertEquals(12L, today.delta)
        assertEquals(Instant.parse("2026-10-17T00:00:00Z"), today.start)
        assertEquals(12L, LocalStore(world.db).todaySteps(Instant.parse("2026-10-17T00:01:00Z"), utc))
        assertEquals(81L, LocalStore(world.db).todaySteps(Instant.parse("2026-10-16T23:00:00Z"), utc))
    }

    /**
     * A store write that fails is logged by its kind and the next reading is still kept: the
     * recorder runs for the whole session, and one failure must not end it. The failed reading's
     * steps are not lost — the baseline stays at the last reading kept, so the next one folds them in.
     */
    @Test
    fun aFailedWriteIsLoggedAndTheNextReadingIsStillKept() = runTest {
        val db = StoreFactory.openInMemory(StandardTestDispatcher(testScheduler))
        val wall = { wallStart.plusMillis(testScheduler.currentTime) }
        var failNext = false
        val real = StoreLiveReadings({ db }, wall, { utc })
        val flaky = object : LiveReadingStore by real {
            override suspend fun addSteps(delta: Long, at: Instant, windowStart: Instant) {
                if (failNext) {
                    failNext = false
                    throw IllegalStateException("disk full")
                }
                real.addSteps(delta, at, windowStart)
            }
        }
        val logs = mutableListOf<String>()
        val readings = LiveReadings(flaky, backgroundScope, wall, { utc }, log = { logs += it })

        advanceTo(7 * 60_000L)
        readings.onDescriptor(hex(descriptor81))
        testScheduler.runCurrent()
        failNext = true
        advanceTo(8 * 60_000L)
        readings.onDescriptor(descriptor(100))
        testScheduler.runCurrent()
        advanceTo(9 * 60_000L)
        readings.onDescriptor(descriptor(120))
        testScheduler.runCurrent()

        assertEquals(listOf(81L, 39L), LocalStore(db).stepSamples(Instant.EPOCH, Instant.parse("2100-01-01T00:00:00Z")).map { it.delta })
        assertEquals(listOf("live readings: a reading could not be kept (IllegalStateException)"), logs, "the kind only, never a value")
    }

    /**
     * The delta was stored but the baseline's save failed: the next reading folds against the
     * reading whose steps were stored, never crediting the same steps twice.
     */
    @Test
    fun aFailedBaselineSaveNeverCountsTheSameStepsTwice() = runTest {
        val db = StoreFactory.openInMemory(StandardTestDispatcher(testScheduler))
        val wall = { wallStart.plusMillis(testScheduler.currentTime) }
        var failNext = false
        val real = StoreLiveReadings({ db }, wall, { utc })
        val flaky = object : LiveReadingStore by real {
            override suspend fun saveStepBaseline(baseline: io.github.opencircuit.store.StepBaseline) {
                if (failNext) {
                    failNext = false
                    throw IllegalStateException("disk full")
                }
                real.saveStepBaseline(baseline)
            }
        }
        val readings = LiveReadings(flaky, backgroundScope, wall, { utc }, log = {})

        advanceTo(7 * 60_000L)
        readings.onDescriptor(hex(descriptor81))
        testScheduler.runCurrent()
        failNext = true
        advanceTo(8 * 60_000L)
        readings.onDescriptor(descriptor(100))
        testScheduler.runCurrent()
        advanceTo(9 * 60_000L)
        readings.onDescriptor(descriptor(120))
        testScheduler.runCurrent()

        assertEquals(listOf(81L, 19L, 20L), LocalStore(db).stepSamples(Instant.EPOCH, Instant.parse("2100-01-01T00:00:00Z")).map { it.delta })
    }

    /** A frame that is not a descriptor stores nothing. */
    @Test
    fun onlyDescriptorsAreFolded() = runTest {
        val world = world()
        ringSends(world, TestFrames.liveHeartRate, atMillis = 60_000L)
        ringSends(world, TestFrames.heartbeat, atMillis = 61_000L)

        assertEquals(emptyList(), world.steps())
    }
}
