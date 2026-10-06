package io.github.opencircuit.ble

import io.github.opencircuit.ble.FakeGatt.Operation
import io.github.opencircuit.ble.Fixtures.hex
import io.github.opencircuit.ble.GattPort.BondState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The ATT MTU gate on a ring bonded during the bring-up: a history sync open (`0x02 …`) is
 * written only on a connection whose MTU carries a 243-byte history frame whole (246 and up),
 * while the auth exchange and every other command work at any MTU. A failed MTU exchange is an
 * ordinary operation failure. `SendRefusalTest` pins the same rule on a ring bonded from the
 * start; these cases are what bonding first, a 517 grant, an error status and a second
 * connection add.
 */
class MtuGateTest {

    private val syncOpen = hex("0200ffffffff000100")

    /** A ring with no bond that grants [mtuGrant], bonded during the bring-up; returns once authenticated. */
    private fun TestScope.bondedDuringTheBringUp(mtuGrant: Int): Pair<FakeGatt, RingLink> {
        val (ring, link) = linkTo(unbondedRing(mtuGrant))
        link.connect()
        runCurrent()
        ring.changeBondState(BondState.BONDING)
        ring.changeBondState(BondState.BONDED)
        runCurrent()
        assertEquals(LinkState.Authenticated, link.state.value)
        return ring to link
    }

    @Test
    fun aRingBondedDuringTheBringUpThatGrants245IsRefusedTheSyncOpenAndTakesOtherData() = runTest {
        val (ring, link) = bondedDuringTheBringUp(mtuGrant = 245)
        val log = ring.log

        val sync = link.send(syncOpen)
        val logAfterSync = ring.log
        val poll = link.send(hex("950000"))

        assertTrue(link.info.value.bonded)
        assertFalse(link.info.value.historySafe)
        assertEquals(SendResult.Refused(RefusalReason.HISTORY_UNSAFE), sync)
        assertEquals(log, logAfterSync, "nothing written for the refused sync open")
        assertEquals(SendResult.Sent, poll)
    }

    @Test
    fun aRingBondedDuringTheBringUpThatGrants246TakesTheSyncOpen() = runTest {
        val (ring, link) = bondedDuringTheBringUp(mtuGrant = 246)

        assertEquals(SendResult.Sent, link.send(syncOpen))
        assertTrue(link.info.value.historySafe)
        assertEquals("write 8327ad98 02 00 ff ff ff ff 00 01 00", ring.log.last())
    }

    @Test
    fun theAuthExchangeCompletesAtTheAttDefaultOf23AfterBonding() = runTest {
        val (ring, link) = bondedDuringTheBringUp(mtuGrant = 23)

        assertEquals(23, link.info.value.attMtu)
        assertFalse(link.info.value.historySafe)
        assertTrue("write 8327ad98 01 01 31 82 67 00" in ring.log)
    }

    @Test
    fun aGrantOf517IsHistorySafe() = runTest {
        val (_, link) = authenticatedLink(Fixtures.acceptingRing(mtuGrant = 517))

        assertEquals(517, link.info.value.attMtu)
        assertTrue(link.info.value.historySafe)
        assertEquals(SendResult.Sent, link.send(syncOpen))
    }

    @Test
    fun anMtuExchangeAnsweredWithAnErrorClosesTheConnectionAndReconnects() = runTest {
        val (ring, link) = linkTo()
        ring.failWith(Operation.REQUEST_MTU, 133)

        link.connect()
        runCurrent()

        assertEquals(listOf("requestMtu 517", "close"), ring.log.takeLast(2))
        assertEquals(reconnecting(attempt = 1, seconds = 1), link.state.value)
        assertFalse(link.info.value.historySafe)
    }

    /** What one connection's MTU exchange granted says nothing about the next one's. */
    @Test
    fun theNextConnectionIsNotHistorySafeUntilItsOwnExchangeIsAnswered() = runTest {
        val (ring, link) = authenticatedLink(Fixtures.acceptingRing(mtuGrant = 247))
        assertTrue(link.info.value.historySafe)
        ring.hold(Operation.REQUEST_MTU)

        ring.dropConnection(8)
        runCurrent()
        advance(1_000)

        assertEquals(LinkState.Preparing, link.state.value)
        assertEquals("requestMtu 517", ring.log.last())
        assertFalse(link.info.value.historySafe)
        assertEquals(23, link.info.value.attMtu)
    }
}
