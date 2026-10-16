package io.github.opencircuit.app

import io.github.opencircuit.app.live.LiveMode
import io.github.opencircuit.app.live.StoreLiveReadings
import io.github.opencircuit.app.session.RingSessionController
import io.github.opencircuit.app.sync.HistoryStore
import io.github.opencircuit.app.sync.SessionHistory
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.store.DaytimeTemperature
import io.github.opencircuit.store.LocalStore
import io.github.opencircuit.store.StoreDatabase
import io.github.opencircuit.store.StoreFactory
import io.github.opencircuit.store.openInMemory
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the ring measures live and the app now keeps (upstream `stopLiveMonitoring` RS:3430-3477 and
 * the descriptor's skin temperature RS:4986-5032 @ b1c2fdd; PORTING.md D-234, D-293): the settled
 * heart rate and the last SpO₂ when a measure stops, stamped when they were measured; a worn skin
 * temperature inside the night window as a nightly reading, a worn one by day in the daytime table,
 * a cold one nowhere. Real frames go through the session's dispatcher; the readings are read back
 * through a second store over the same database (what a relaunch finds). None moves the sync
 * cursor, so a later drain keeps every older sample.
 */
class LiveReadingsPersistTest {
    private val utc = ZoneOffset.UTC

    /** The real morning descriptor (`RingKitVerifyTest`): channels 35.5 and 35.7 °C. */
    private val morningDescriptor = "104e030000000163016500000000101902ffaf"
    private val morningCelsius = (35.5 + 35.7) / 2

    private class World(val db: StoreDatabase, val link: TimedRingLink, val session: RingSessionController)

    private suspend fun TestScope.world(wallStart: Instant): World {
        val db = StoreFactory.openInMemory(StandardTestDispatcher(testScheduler))
        val wall = { wallStart.plusMillis(testScheduler.currentTime) }
        val link = timedLink()
        val session = RingSessionController(
            link, backgroundScope, monotonicMillis = { testScheduler.currentTime }, log = {},
            history = SessionHistory(
                HistoryStore.NONE, wallClock = wall, disconnectAfterSync = { false }, zone = { utc },
                live = StoreLiveReadings({ db }, wall, { utc }),
            ),
        )
        session.start()
        testScheduler.runCurrent()
        link.fake.setState(LinkState.Authenticated)
        testScheduler.runCurrent()
        return World(db, link, session)
    }

    private fun TestScope.ringSends(world: World, frames: List<ByteArray>) {
        frames.forEach(world.link.fake::emitFrame)
        testScheduler.runCurrent()
    }

    /** Read through a second store over the same database: what a relaunch finds. */
    private suspend fun StoreDatabase.stored(kind: MetricKind): List<QuantitySample> =
        LocalStore(this).samples(kind, Instant.EPOCH, Instant.parse("2100-01-01T00:00:00Z"))

    @Test
    fun theSettledHeartRateIsKeptWhenTheMeasureStopsStampedWhenItWasMeasured() = runTest {
        val world = world(Instant.parse("2026-10-16T14:00:00Z"))
        world.session.liveMeasure.start(LiveMode.HEART_RATE)
        advanceTo(2_750)
        ringSends(world, TestFrames.realHeartRateRead) // warm-up, then 82 84 88 90 91 66 61: settled 88
        advanceTo(20_000)

        world.session.liveMeasure.stop()
        testScheduler.runCurrent()

        assertEquals(
            listOf(QuantitySample(MetricKind.HEART_RATE, Instant.parse("2026-10-16T14:00:02.750Z"), value = 88.0)),
            world.db.stored(MetricKind.HEART_RATE),
        )
        assertTrue(MetricKind.entries.all { LocalStore(world.db).loadCursor().last(it) == null }, "no cursor moved")
    }

    @Test
    fun theLastSpO2IsKeptAsAFraction() = runTest {
        val world = world(Instant.parse("2026-10-16T14:00:00Z"))
        world.session.liveMeasure.start(LiveMode.SPO2)
        advanceTo(2_750)
        ringSends(world, TestFrames.realSpO2Read) // 96 96 97

        advanceTo(60_000) // the 45 s budget ends the measure

        assertEquals(listOf(QuantitySample(MetricKind.SPO2, Instant.parse("2026-10-16T14:00:02.750Z"), value = 0.97)), world.db.stored(MetricKind.SPO2))
    }

    /** Four locked frames are not a reading (no settled value): nothing is kept, as nothing was shown. */
    @Test
    fun aMeasureThatNeverSettledKeepsNothing() = runTest {
        val world = world(Instant.parse("2026-10-16T14:00:00Z"))
        world.session.liveMeasure.start(LiveMode.HEART_RATE)
        advanceTo(2_750)
        ringSends(world, TestFrames.realHeartRateRead.take(5))

        world.session.liveMeasure.onLinkLost()
        testScheduler.runCurrent()

        assertEquals(emptyList(), world.db.stored(MetricKind.HEART_RATE))
    }

    /** The guard: a reading kept live at 14:00, then the ring's older backlog arrives through ingest. */
    @Test
    fun aLaterDrainKeepsTheOlderSamplesTheRingStillHeld() = runTest {
        val world = world(Instant.parse("2026-10-16T14:00:00Z"))
        world.session.liveMeasure.start(LiveMode.HEART_RATE)
        advanceTo(2_750)
        ringSends(world, TestFrames.realHeartRateRead)
        world.session.liveMeasure.stop()
        testScheduler.runCurrent()

        val backlog = listOf(
            QuantitySample(MetricKind.HEART_RATE, Instant.parse("2026-10-16T08:00:00Z"), value = 55.0),
            QuantitySample(MetricKind.HEART_RATE, Instant.parse("2026-10-16T13:59:00Z"), value = 61.0),
        )
        LocalStore(world.db).ingest(backlog, Instant.parse("2026-10-16T14:05:00Z"), utc)

        assertEquals(listOf(55.0, 61.0, 88.0), world.db.stored(MetricKind.HEART_RATE).map { it.value })
    }

    /** 02:00 is inside the 21:30-10:00 night window (no learned nights yet): a worn reading is a nightly one. */
    @Test
    fun aWornSkinTemperatureAtNightIsKeptAsANightlyReading() = runTest {
        val world = world(Instant.parse("2026-10-16T02:00:00Z"))
        ringSends(world, listOf(hex(morningDescriptor)))

        assertEquals(listOf(QuantitySample(MetricKind.TEMPERATURE, Instant.parse("2026-10-16T02:00:00Z"), value = morningCelsius)), world.db.stored(MetricKind.TEMPERATURE))
        assertEquals(emptyList(), LocalStore(world.db).daytimeTemperatures(Instant.EPOCH, Instant.parse("2100-01-01T00:00:00Z")))
    }

    @Test
    fun aWornSkinTemperatureByDayGoesToTheDaytimeTableOnly() = runTest {
        val world = world(Instant.parse("2026-10-16T13:00:00Z"))
        ringSends(world, listOf(hex(morningDescriptor)))

        assertEquals(emptyList(), world.db.stored(MetricKind.TEMPERATURE))
        assertEquals(
            listOf(DaytimeTemperature(Instant.parse("2026-10-16T13:00:00Z"), morningCelsius)),
            LocalStore(world.db).daytimeTemperatures(Instant.EPOCH, Instant.parse("2100-01-01T00:00:00Z")),
        )
    }

    /** A ring on its charger reads cold (26.8 / 26.2 °C): not worn, kept nowhere, night or day. */
    @Test
    fun aColdReadingIsKeptNowhere() = runTest {
        val world = world(Instant.parse("2026-10-16T02:00:00Z"))
        ringSends(world, listOf(TestFrames.chargingDescriptor))

        assertEquals(emptyList(), world.db.stored(MetricKind.TEMPERATURE))
        assertEquals(emptyList(), LocalStore(world.db).daytimeTemperatures(Instant.EPOCH, Instant.parse("2100-01-01T00:00:00Z")))
    }
}
