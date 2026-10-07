package io.github.opencircuit.ble

import io.github.opencircuit.ble.Fixtures.hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A cold connection to a bonded ring, through every layer: `RingLink` → the event loop → the
 * operation queue → the GATT port (a scripted fake ring) → GATT events back → `RingAuth` → state.
 * The fake fails the test if two GATT operations are ever outstanding at once.
 */
class BringUpTest {

    /** The writes, in order, of a cold bring-up to a ring that accepts challenge `0xb0`. */
    private val coldBringUpLog = listOf(
        "connect autoConnect=false",
        "discoverServices",
        "requestMtu 517",
        "setNotifications 8327ad97 on",
        "writeDescriptor 8327ad97/00002902 01 00",
        "read 00002a23",
        "read 00002a26",
        "read 00002a29",
        "read 00002a27",
        "write 8327ad98 01 00 00",
        "write 8327ad98 01 01 31 82 67 00",
        "write 8327ad98 d0 00 00", // asks for the first data frame (PORTING.md D-257)
    )

    private fun TestScope.statesOf(link: RingLink): List<LinkState> {
        val seen = mutableListOf<LinkState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { link.state.collect { seen += it } }
        return seen
    }

    private fun TestScope.framesOf(link: RingLink): List<String> {
        val seen = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { link.frames.collect { seen += it.toHexString() } }
        return seen
    }

    @Test
    fun aColdBringUpWritesEveryStepInOrderAndAuthenticatesOnTheFirstDataFrame() = runTest {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        val link = RingLink(Fixtures.ring, ring, backgroundScope)
        val states = statesOf(link)

        link.connect()
        runCurrent()

        assertEquals(coldBringUpLog, ring.log)
        assertEquals(
            listOf(
                LinkState.Idle, LinkState.Connecting, LinkState.Discovering, LinkState.Preparing,
                LinkState.Authenticating, LinkState.Authenticated,
            ),
            states,
        )
        assertEquals(emptyList(), ring.violations)
    }

    @Test
    fun theLinkInfoHoldsWhatThisConnectionRead() = runTest {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        val link = RingLink(Fixtures.ring, ring, backgroundScope)

        link.connect()
        runCurrent()

        val info = link.info.value
        assertEquals("FR02.018", info.firmware.version)
        assertEquals("RingConn", info.firmware.manufacturer)
        assertEquals("V2.0", info.firmware.hardwareRevision)
        // The model name is the ring's advertised name (upstream RingSession.swift:930).
        assertEquals("RingConn Gen2-03AD", info.firmware.modelName)
        assertEquals("F8:79:99:F7:03:AD", info.firmware.mac)
        assertEquals("F8:79:99:F7:03:AD", info.mac)
        assertEquals(247, info.attMtu)
    }

    @Test
    fun challengeFramesAloneNeverAuthenticate() = runTest {
        // A ring that does not accept the reply keeps answering with 0x81 frames only.
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing().copy(acceptedAuthReply = null))
        val link = RingLink(Fixtures.ring, ring, backgroundScope)
        val frames = framesOf(link)

        link.connect()
        runCurrent()
        repeat(3) { ring.notify(Fixtures.challengeFrame) }
        runCurrent()

        assertEquals(LinkState.Authenticating, link.state.value)
        assertEquals(emptyList(), frames, "an 81 00 challenge is the link's own and is never delivered")
    }

    @Test
    fun theFirstDataFrameIsDeliveredAndTheChallengeIsNot() = runTest {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        val link = RingLink(Fixtures.ring, ring, backgroundScope)
        val frames = framesOf(link)

        link.connect()
        runCurrent()

        assertEquals(listOf("15 00 08 0a b0 a7"), frames)
    }

    /** PORTING.md D-183: upstream may write `01 00 00` before notifications are confirmed (RingSession.swift:4815-4822). */
    @Test
    fun authStartsOnlyOnceTheNotificationDescriptorWriteIsConfirmed() = runTest {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        ring.hold(FakeGatt.Operation.WRITE_DESCRIPTOR)
        val link = RingLink(Fixtures.ring, ring, backgroundScope)

        link.connect()
        runCurrent()

        assertEquals("writeDescriptor 8327ad97/00002902 01 00", ring.log.last(), "nothing after the unconfirmed descriptor write")
        assertEquals(LinkState.Preparing, link.state.value)

        ring.release(FakeGatt.Operation.WRITE_DESCRIPTOR)
        runCurrent()

        assertEquals(coldBringUpLog, ring.log)
        assertEquals(LinkState.Authenticated, link.state.value)
    }

    /** PORTING.md D-183: a descriptor write the ring rejected is not a confirmation. */
    @Test
    fun aRejectedNotificationDescriptorWriteNeverStartsAuth() = runTest {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        ring.failWith(FakeGatt.Operation.WRITE_DESCRIPTOR, status = 133)
        val link = RingLink(Fixtures.ring, ring, backgroundScope)

        link.connect()
        runCurrent()

        assertFalse(ring.log.any { it.startsWith("write ") }, "no command written: ${ring.log}")
        assertEquals("close", ring.log.last())
        assertTrue(link.state.value != LinkState.Authenticating && link.state.value != LinkState.Authenticated)
    }

    @Test
    fun withoutASystemIdTheReplyIsComputedFromTheDeviceAddress() = runTest {
        val noSystemId = Fixtures.deviceInformation - GattPort.SYSTEM_ID
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing(deviceInformation = noSystemId))
        val link = RingLink(Fixtures.ring, ring, backgroundScope)

        link.connect()
        runCurrent()

        assertEquals(listOf("write 8327ad98 01 01 31 82 67 00", "write 8327ad98 d0 00 00"), ring.log.takeLast(2))
        assertFalse(ring.log.contains("read 00002a23"), "a characteristic the ring does not have is not read")
        assertEquals(LinkState.Authenticated, link.state.value)
        assertNull(link.info.value.firmware.mac, "no System ID was read")
        assertEquals("F8:79:99:F7:03:AD", link.info.value.mac)
    }

    @Test
    fun aDeviceInformationStringThatIsNotUtf8LeavesItsFieldEmpty() = runTest {
        // Upstream decodes with String(bytes:encoding:.utf8), which yields nil on invalid UTF-8
        // and leaves the field unset; Kotlin's String(bytes) would put U+FFFD in it instead.
        val badVersion = Fixtures.deviceInformation + (GattPort.FIRMWARE_REVISION to hex("4652ff3032"))
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing(deviceInformation = badVersion))
        val link = RingLink(Fixtures.ring, ring, backgroundScope)

        link.connect()
        runCurrent()

        assertEquals("", link.info.value.firmware.version)
        assertEquals("RingConn", link.info.value.firmware.manufacturer)
    }

    @Test
    fun aSendBeforeAuthenticationIsRefusedAndWritesNothing() = runTest {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        val link = RingLink(Fixtures.ring, ring, backgroundScope)

        assertEquals(SendResult.Refused(RefusalReason.NOT_AUTHENTICATED), link.send(hex("950000")))
        assertEquals(emptyList(), ring.log)
    }

    @Test
    fun aSendOnceAuthenticatedIsWrittenWithResponseAndReportsSent() = runTest {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        val link = RingLink(Fixtures.ring, ring, backgroundScope)
        link.connect()
        runCurrent()

        val command = hex("950000")
        val result = link.send(command)
        command[0] = 0 // the link must have taken its own copy

        assertEquals(SendResult.Sent, result)
        assertEquals("write 8327ad98 95 00 00", ring.log.last())
        assertEquals(emptyList(), ring.violations)
    }

    @Test
    fun disconnectClosesTheConnectionAndReturnsToIdle() = runTest {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        val link = RingLink(Fixtures.ring, ring, backgroundScope)
        link.connect()
        runCurrent()

        link.disconnect()
        runCurrent()

        assertEquals("close", ring.log.last())
        assertEquals(LinkState.Idle, link.state.value)
    }

    @Test
    fun aSendAfterTheLinkScopeEndedFailsWithLinkLostInsteadOfHanging() = runTest {
        val linkScope = CoroutineScope(coroutineContext + Job())
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        val link = RingLink(Fixtures.ring, ring, linkScope)
        link.connect()
        runCurrent()

        linkScope.cancel()
        runCurrent()

        assertEquals(SendResult.Failed(SendFailure.LINK_LOST), link.send(hex("950000")))
    }

    private fun ByteArray.toHexString(): String =
        joinToString(" ") { b -> "0123456789abcdef".let { d -> "${d[(b.toInt() shr 4) and 0xF]}${d[b.toInt() and 0xF]}" } }
}
