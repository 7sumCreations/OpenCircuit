package io.github.opencircuit.app

import io.github.opencircuit.app.demo.DemoRingLink
import io.github.opencircuit.app.live.LiveMode
import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.ring.RingViewModel
import io.github.opencircuit.app.session.RingSessionController
import io.github.opencircuit.app.sync.SessionHistory
import io.github.opencircuit.app.sync.StoreHistory
import io.github.opencircuit.store.BlobStore
import io.github.opencircuit.store.StoreFactory
import io.github.opencircuit.store.openInMemory
import kotlinx.coroutines.test.StandardTestDispatcher
import java.time.ZoneOffset
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ble.LinkTeardown
import io.github.opencircuit.ble.RefusalReason
import io.github.opencircuit.ble.SendResult
import io.github.opencircuit.ringkit.HistoryDrainPlan.TeardownReason
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The debug build's demo link: a ring the emulator can "connect" to. It keeps the real link's
 * contract where the app relies on it (a second collection fails, refusals as values) so the app
 * code above it runs unchanged.
 */
class DemoRingLinkTest {

    private val logLines = mutableListOf<String>()

    @Test
    fun connectingAuthenticatesAndSendsOneDescriptorThroughTheSession() = runTest {
        val link = DemoRingLink(backgroundScope)
        val controller = RingSessionController(link, backgroundScope, monotonicMillis = { testScheduler.currentTime }, log = { logLines += it })
        assertEquals(LinkState.Idle, link.state.value)
        assertEquals("Demo ring", link.ring.name)

        controller.connect()
        runCurrent()

        assertEquals(LinkState.Authenticated, link.state.value)
        assertEquals(72, controller.deviceStatus.state.value.batteryPercent)
        assertEquals(emptyList(), logLines, "the demo descriptor is a well-formed 0x10 frame")
    }

    /**
     * Sync now on the demo ring (the emulator's walkthrough): it answers the open like a ring —
     * `82 00 00 82`, then three pages of six made-up records from the last hour and a half, each
     * released only once the previous one is acknowledged, then `0x50` — and the app stores all
     * 18 records and disconnects. The demo has nothing new for a second sync.
     */
    @Test
    fun syncNowOnTheDemoRingStoresItsSmallBacklogAndASecondSyncFindsNothingNew() = runTest {
        val db = StoreFactory.openInMemory(StandardTestDispatcher(testScheduler))
        try {
            val wall = { SYNC_TEST_EPOCH.plusMillis(testScheduler.currentTime) }
            val link = DemoRingLink(backgroundScope, wallClock = { wall().toEpochMilli() })
            val session = RingSessionController(
                link, backgroundScope, monotonicMillis = { testScheduler.currentTime }, log = { logLines += it },
                history = SessionHistory(StoreHistory({ db }, "AA:BB:CC:DD:EE:00", { ZoneOffset.UTC }), wall, disconnectAfterSync = { true }),
            )
            val viewModel = RingViewModel(sessionsOf(session), "Ring", backgroundScope, wallClock = wall)
            runCurrent()

            viewModel.onAction(RingAction.SyncNow)
            advanceTo(60_000)

            val records = BlobStore(db).loadEpochArchive("AA:BB:CC:DD:EE:00").records
            assertEquals(18, records.size)
            assertTrue(records.all { it.date() in SYNC_TEST_EPOCH.minusSeconds(5_400)..SYNC_TEST_EPOCH }, "dated the last hour and a half")
            assertEquals(LinkState.Idle, link.state.value, "disconnected after the commit")
            assertEquals("18 records · complete", viewModel.uiState.value.ringData.lastSync)

            viewModel.onAction(RingAction.SyncNow)
            advanceTo(120_000)

            assertEquals(18, BlobStore(db).loadEpochArchive("AA:BB:CC:DD:EE:00").records.size)
            assertEquals("Up to date", viewModel.uiState.value.ringData.lastSync)
        } finally {
            db.close()
        }
    }

    @Test
    fun connectingAgainWhileConnectedSendsNoMoreFrames() = runTest {
        val link = DemoRingLink(backgroundScope)
        val received = mutableListOf<ByteArray>()
        backgroundScope.launch { link.frames.collect { received += it } }

        link.connect()
        link.connect()
        runCurrent()

        assertEquals(LinkState.Authenticated, link.state.value)
        assertEquals(1, received.size)
        assertEquals(0x10, received[0][0].toInt() and 0xFF)
    }

    @Test
    fun sendIsRefusedBeforeConnectingAndForTheReservedAuthCommands() = runTest {
        val link = DemoRingLink(backgroundScope)

        assertEquals(SendResult.Refused(RefusalReason.NOT_AUTHENTICATED), link.send(hex("d00000")))
        link.connect()
        assertEquals(SendResult.Sent, link.send(hex("d00000")))
        assertEquals(SendResult.Refused(RefusalReason.AUTH_COMMAND_RESERVED), link.send(hex("010000")))
        assertEquals(SendResult.Refused(RefusalReason.AUTH_COMMAND_RESERVED), link.send(hex("0101aabbcc")))
    }

    @Test
    fun disconnectingTearsDownTheConnectionOnceAndGoesIdle() = runTest {
        val link = DemoRingLink(backgroundScope)
        val controller = RingSessionController(link, backgroundScope, monotonicMillis = { testScheduler.currentTime }, log = { logLines += it })
        controller.connect()
        runCurrent()

        link.disconnect()
        link.disconnect()
        runCurrent()

        assertEquals(LinkState.Idle, link.state.value)
        assertEquals(1, controller.teardowns.value.count)
        assertEquals(LinkTeardown(TeardownReason.USER_DISCONNECTED, 0), controller.teardowns.value.last)
        assertEquals(SendResult.Refused(RefusalReason.NOT_AUTHENTICATED), link.send(hex("d00000")))
    }

    @Test
    fun aHeartRateMeasureOnTheDemoRingWarmsUpThenLocksAndSettles() = runTest {
        val link = DemoRingLink(backgroundScope)
        val controller = RingSessionController(link, backgroundScope, monotonicMillis = { testScheduler.currentTime }, log = { logLines += it })
        controller.connect()
        runCurrent()

        controller.liveMeasure.start(LiveMode.HEART_RATE)
        advanceTo(2_750 + 2_000) // two polls answered: still warming up
        assertNull(controller.liveMeasure.state.value.newest)
        assertTrue(controller.liveMeasure.state.value.warmingUp)

        advanceTo(2_750 + 8 * 2_000) // nine polls: two warm-up frames, then seven locked
        val state = controller.liveMeasure.state.value
        val bpm = assertNotNull(state.newest)
        assertTrue(bpm in 55..80, "a resting heart rate: $bpm")
        assertNotNull(state.settledHeartRate)
        assertEquals(7, state.session.points.size)
        assertEquals(0, state.framesNotUsed, "every demo frame has a correct XOR trailer")
    }

    @Test
    fun anSpo2MeasureOnTheDemoRingReadsAnEstimate() = runTest {
        val link = DemoRingLink(backgroundScope)
        val controller = RingSessionController(link, backgroundScope, monotonicMillis = { testScheduler.currentTime }, log = { logLines += it })
        controller.connect()
        runCurrent()

        controller.liveMeasure.start(LiveMode.SPO2)
        advanceTo(2_750 + 4 * 2_000)

        val spo2 = assertNotNull(controller.liveMeasure.state.value.newest)
        assertTrue(spo2 in 94..99, "a plausible SpO₂: $spo2")
        assertEquals(0, controller.liveMeasure.state.value.framesNotUsed)
    }

    @Test
    fun theDemoRingStartsWarmingUpAgainAfterEachEntryAndIgnoresAPollWithNoMode() = runTest {
        val link = DemoRingLink(backgroundScope)
        val received = mutableListOf<ByteArray>()
        backgroundScope.launch { link.frames.collect { received += it } }
        link.connect()
        runCurrent()
        received.clear()

        link.send(hex("950000")) // no mode chosen yet
        runCurrent()
        assertEquals(0, received.size)

        for (cmd in listOf("d00000", "060100", "070000", "950000", "950000", "950000")) link.send(hex(cmd))
        for (cmd in listOf("d00000", "060100", "070000", "950000")) link.send(hex(cmd))
        runCurrent()

        val heartRates = received.filter { it[0] == 0x15.toByte() }.map { it[2].toInt() and 0xFF }
        assertEquals(listOf(8, 8, heartRates[2], 8), heartRates, "re-entry restarts the warm-up")
        assertTrue(heartRates[2] in 55..80)
    }

    @Test
    fun whileConnectedTheDemoRingSendsADescriptorEvery30Seconds() = runTest {
        val link = DemoRingLink(backgroundScope)
        val received = mutableListOf<ByteArray>()
        backgroundScope.launch { link.frames.collect { received += it } }
        fun descriptors() = received.count { it[0] == 0x10.toByte() }

        link.connect()
        runCurrent()
        assertEquals(1, descriptors(), "one as it connects")
        advanceTo(29_999)
        assertEquals(1, descriptors())
        advanceTo(30_000)
        assertEquals(2, descriptors())
        advanceTo(90_000)
        assertEquals(4, descriptors())

        link.disconnect()
        advanceTo(300_000)
        assertEquals(4, descriptors(), "none after a disconnect")
        link.connect()
        advanceTo(330_000)
        assertEquals(6, descriptors(), "again after a reconnect: one at once, one 30 s later")
    }

    @Test
    fun theDemoRingAnswersAStatusQueryWithADescriptorAsTheRingDoes() = runTest {
        val link = DemoRingLink(backgroundScope)
        val received = mutableListOf<ByteArray>()
        backgroundScope.launch { link.frames.collect { received += it } }
        link.connect()
        runCurrent()
        received.clear()

        link.send(hex("d00000"))
        runCurrent()

        assertEquals(listOf(0x10), received.map { it[0].toInt() and 0xFF })
    }

    @Test
    fun throughTheSessionTheDemoBatteryNeverGoesOutOfDate() = runTest {
        val link = DemoRingLink(backgroundScope)
        val controller = RingSessionController(link, backgroundScope, monotonicMillis = { testScheduler.currentTime }, log = { logLines += it })
        controller.connect()
        advanceTo(10 * 60_000)

        assertNull(controller.deviceStatus.state.value.batteryAgeMillis)
        assertEquals(72, controller.deviceStatus.state.value.batteryPercent)
        assertNull(controller.keepalive.problem.value)
    }

    @Test
    fun eachFlowTakesOneCollection() = runTest {
        val link = DemoRingLink(backgroundScope)
        RingSessionController(link, backgroundScope, monotonicMillis = { testScheduler.currentTime }, log = { logLines += it }).start()
        runCurrent()

        assertFailsWith<IllegalStateException> { withTimeout(1_000) { link.frames.collect() } }
        assertFailsWith<IllegalStateException> { withTimeout(1_000) { link.teardowns.collect() } }
    }

    @Test
    fun aReconnectedDemoRingAnswersNoPollUntilAModeIsChosenAgain() = runTest {
        val link = DemoRingLink(backgroundScope)
        val received = mutableListOf<ByteArray>()
        backgroundScope.launch { link.frames.collect { received += it } }
        link.connect()
        for (cmd in listOf("d00000", "060100", "070000", "950000")) link.send(hex(cmd))
        runCurrent()
        assertEquals(1, received.count { it[0] == 0x15.toByte() })

        link.disconnect()
        link.connect()
        runCurrent()
        received.clear()
        link.send(hex("950000"))
        runCurrent()

        assertEquals(0, received.count { it[0] == 0x15.toByte() }, "a new connection starts with no mode, as a real ring's does")
    }
}
