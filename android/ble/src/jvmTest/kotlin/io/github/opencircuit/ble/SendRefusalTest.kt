package io.github.opencircuit.ble

import io.github.opencircuit.ble.FakeGatt.Operation
import io.github.opencircuit.ble.Fixtures.hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `send` refuses, as a value and with nothing written, what the ring must not get (PORTING.md
 * D-191), checked in this order: anything before the link is authenticated; the link's own auth
 * commands `01 00 00` and `01 01 …` at any time; a data command (anything outside the `0x01`
 * status family) while the phone holds no bond with the ring, which ignores data from an
 * unbonded phone; and a history sync open (`0x02 …`) on a connection whose ATT MTU is below 246,
 * where a 243-byte history frame would be cut short after the link had acknowledged it.
 * Commands are the captured ones of `docs/PROTOCOL.md` §3 (`01 02 00` is a synthetic member of
 * the `0x01` family).
 */
class SendRefusalTest {

    private val poll = hex("950000")
    private val syncOpen = hex("0200ffffffff000100")
    private val unbonded = Fixtures.acceptingRing().copy(bondState = GattPort.BondState.NONE)

    private fun refused(reason: RefusalReason) = SendResult.Refused(reason)

    @Test
    fun beforeTheLinkIsAuthenticatedEveryCommandIsRefusedAndNothingIsWritten() = runTest {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        ring.hold(Operation.READ)
        val link = RingLink(Fixtures.ring, ring, backgroundScope)
        val commands = listOf(poll, syncOpen, hex("010000"), hex("010131826700"))

        val whileIdle = commands.map { link.send(it) }
        link.connect()
        runCurrent()
        assertEquals(LinkState.Preparing, link.state.value)
        val log = ring.log
        val whilePreparing = commands.map { link.send(it) }

        assertEquals(List(4) { refused(RefusalReason.NOT_AUTHENTICATED) }, whileIdle)
        assertEquals(List(4) { refused(RefusalReason.NOT_AUTHENTICATED) }, whilePreparing)
        assertEquals(log, ring.log)
    }

    @Test
    fun theLinksOwnAuthCommandsAreRefusedEvenWhenAuthenticated() = runTest {
        val (ring, link) = authenticatedLink()
        val log = ring.log

        val results = listOf(hex("010000"), hex("010131826700"), hex("01014bcce600"), hex("0101")).map { link.send(it) }

        assertEquals(List(4) { refused(RefusalReason.AUTH_COMMAND_RESERVED) }, results)
        assertEquals(log, ring.log)
    }

    @Test
    fun aDataCommandWhileTheRingIsNotBondedIsRefused() = runTest {
        val (ring, link) = authenticatedLink(unbonded)
        val log = ring.log

        val results = listOf(poll, syncOpen, hex("d00000")).map { link.send(it) }

        assertFalse(link.info.value.bonded)
        assertEquals(List(3) { refused(RefusalReason.NOT_BONDED) }, results)
        assertEquals(log, ring.log)
    }

    @Test
    fun aSyncOpenOnAnUnbondedNarrowConnectionIsRefusedForTheMissingBondFirst() = runTest {
        val (ring, link) = authenticatedLink(unbonded.copy(mtuGrant = 23))
        val log = ring.log

        val result = link.send(syncOpen)

        assertFalse(link.info.value.historySafe)
        assertEquals(refused(RefusalReason.NOT_BONDED), result)
        assertEquals(log, ring.log)
    }

    @Test
    fun aBondingRingIsNotYetBonded() = runTest {
        val (_, link) = authenticatedLink(Fixtures.acceptingRing().copy(bondState = GattPort.BondState.BONDING))

        assertFalse(link.info.value.bonded)
        assertEquals(refused(RefusalReason.NOT_BONDED), link.send(poll))
    }

    @Test
    fun aStatusFamilyCommandOtherThanTheAuthOnesIsWrittenWhileNotBonded() = runTest {
        val (ring, link) = authenticatedLink(unbonded)

        val result = link.send(hex("010200"))

        assertEquals(SendResult.Sent, result)
        assertEquals("write 8327ad98 01 02 00", ring.log.last())
    }

    @Test
    fun aSyncOpenOnAConnectionWithAnMtuBelow246IsRefusedAndOtherDataIsWritten() = runTest {
        val (ring, link) = authenticatedLink(Fixtures.acceptingRing(mtuGrant = 245))
        val log = ring.log

        val sync = link.send(syncOpen)
        val logAfterSync = ring.log
        val data = link.send(poll)

        assertEquals(245, link.info.value.attMtu)
        assertFalse(link.info.value.historySafe)
        assertEquals(refused(RefusalReason.HISTORY_UNSAFE), sync)
        assertEquals(log, logAfterSync)
        assertEquals(SendResult.Sent, data)
        assertEquals("write 8327ad98 95 00 00", ring.log.last())
    }

    @Test
    fun aSyncOpenOnAConnectionWithAnMtuOf246IsWritten() = runTest {
        val (ring, link) = authenticatedLink(Fixtures.acceptingRing(mtuGrant = 246))

        val result = link.send(syncOpen)

        assertTrue(link.info.value.historySafe)
        assertTrue(link.info.value.bonded)
        assertEquals(SendResult.Sent, result)
        assertEquals("write 8327ad98 02 00 ff ff ff ff 00 01 00", ring.log.last())
    }

    @Test
    fun anAcceptedCommandIsSentOnlyOnceTheRingAnsweredTheWrite() = runTest {
        val (ring, link) = authenticatedLink()
        ring.hold(Operation.WRITE)

        val result = backgroundScope.async { link.send(poll) }
        runCurrent()
        val beforeAnswer = result.isCompleted
        ring.release(Operation.WRITE)
        runCurrent()

        assertFalse(beforeAnswer, "not Sent before the write callback")
        assertEquals(SendResult.Sent, result.await())
    }

    @Test
    fun noCommandInAnyStateMakesSendThrow() = runTest {
        val commands = listOf(hex(""), hex("01"), hex("02"), hex("0101"), hex("010000"), poll, syncOpen, hex("ff"))
        val linkScope = CoroutineScope(coroutineContext + Job())
        val (_, unbondedLink) = authenticatedLink(unbonded)
        val (_, narrowLink) = authenticatedLink(Fixtures.acceptingRing(mtuGrant = 23))
        val idleLink = RingLink(Fixtures.ring, FakeGatt(backgroundScope, Fixtures.acceptingRing()), backgroundScope)
        val endedLink = RingLink(Fixtures.ring, FakeGatt(backgroundScope, Fixtures.acceptingRing()), linkScope)
        linkScope.cancel()
        runCurrent()

        for (link in listOf(idleLink, unbondedLink, narrowLink, endedLink)) {
            for (command in commands) {
                val result = runCatching { link.send(command) }
                assertTrue(result.isSuccess, "send threw ${result.exceptionOrNull()} for ${command.hexString()}")
                assertIs<SendResult>(result.getOrNull())
            }
        }
    }
}
