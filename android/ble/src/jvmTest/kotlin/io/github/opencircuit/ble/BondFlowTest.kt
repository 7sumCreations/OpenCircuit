package io.github.opencircuit.ble

import io.github.opencircuit.ble.FakeGatt.Operation
import io.github.opencircuit.ble.GattPort.BondState
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The bond step of the bring-up, right after service discovery and before the ATT MTU exchange.
 * A bonded ring goes straight on; a ring with no bond is asked to bond once (`createBond`); a
 * bond already being made (the ring asked to pair, or one left over) is waited for and never
 * asked again. While the bond is being made the link shows `PairingNeeded`, since Android shows
 * the user a pairing prompt; once it is made the bring-up continues with exactly the steps and
 * bytes of a ring that was bonded from the start. CoreBluetooth bonds on its own, so upstream
 * has no such step.
 */
class BondFlowTest {

    /** Every step after discovery of a cold bring-up to a bonded ring that accepts challenge `0xb0`. */
    private val afterDiscovery = listOf(
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

    @Test
    fun anUnbondedRingIsAskedToBondOnceAfterDiscoveryAndNothingElseIsDoneWhileItWaits() = runTest {
        val (ring, link) = linkTo(unbondedRing())
        val states = recordStates(link)

        link.connect()
        runCurrent()
        advance(30_000)

        assertEquals(listOf("connect autoConnect=false", "discoverServices", "createBond"), ring.log)
        assertEquals(1, ring.createBondCalls)
        assertEquals(listOf(LinkState.Idle, LinkState.Connecting, LinkState.Discovering, LinkState.PairingNeeded), states)
        assertFalse(link.info.value.bonded)
        assertEquals(emptyList(), ring.violations)
    }

    @Test
    fun onceTheBondIsMadeTheBringUpContinuesWithTheSameStepsAndBytesAsABondedRing() = runTest {
        val (bondedRing, bondedLink) = linkTo()
        bondedLink.connect()
        runCurrent()
        val (ring, link) = linkTo(unbondedRing())
        val states = recordStates(link)

        link.connect()
        runCurrent()
        ring.changeBondState(BondState.BONDING)
        runCurrent()
        assertEquals(LinkState.PairingNeeded, link.state.value, "still waiting while the bond is being made")
        ring.changeBondState(BondState.BONDED)
        runCurrent()

        assertEquals(listOf("connect autoConnect=false", "discoverServices", "createBond") + afterDiscovery, ring.log)
        assertEquals(bondedRing.log, ring.log.filterNot { it == "createBond" }, "the same steps and bytes as a bonded ring")
        assertEquals(
            listOf(
                LinkState.Idle, LinkState.Connecting, LinkState.Discovering, LinkState.PairingNeeded,
                LinkState.Preparing, LinkState.Authenticating, LinkState.Authenticated,
            ),
            states,
        )
        assertTrue(link.info.value.bonded)
        assertEquals(bondedLink.info.value, link.info.value)
        assertEquals(emptyList(), ring.violations)
    }

    /** Android sets "bonding" on its own when the ring sends a Security Request; a second request would collide with it. */
    @Test
    fun aBondAlreadyBeingMadeIsWaitedForAndNeverRequested() = runTest {
        val (ring, link) = linkTo(Fixtures.acceptingRing().copy(bondState = BondState.BONDING))

        link.connect()
        runCurrent()
        assertEquals(LinkState.PairingNeeded, link.state.value)
        ring.changeBondState(BondState.BONDED)
        runCurrent()

        assertEquals(0, ring.createBondCalls)
        assertEquals(listOf("connect autoConnect=false", "discoverServices") + afterDiscovery, ring.log)
        assertEquals(LinkState.Authenticated, link.state.value)
    }

    @Test
    fun aRingThatStartsBondingDuringDiscoveryIsNeverAskedToBond() = runTest {
        val (ring, link) = linkTo(unbondedRing())
        ring.hold(Operation.DISCOVER_SERVICES)

        link.connect()
        runCurrent()
        ring.changeBondState(BondState.BONDING) // the ring's Security Request, before the link could ask
        runCurrent()
        ring.release(Operation.DISCOVER_SERVICES)
        runCurrent()

        assertEquals(0, ring.createBondCalls)
        assertEquals(LinkState.PairingNeeded, link.state.value)
    }

    @Test
    fun aBondedRingIsNeverAskedToBond() = runTest {
        val (ring, link) = linkTo()

        link.connect()
        runCurrent()

        assertEquals(0, ring.createBondCalls)
        assertEquals(listOf("connect autoConnect=false", "discoverServices") + afterDiscovery, ring.log)
        assertTrue(link.info.value.bonded)
    }

    @Test
    fun aBondMadeOnOneConnectionIsNotRequestedAgainOnTheNext() = runTest {
        val (ring, link) = linkTo(unbondedRing())
        link.connect()
        runCurrent()
        ring.changeBondState(BondState.BONDING)
        ring.changeBondState(BondState.BONDED)
        runCurrent()
        assertEquals(LinkState.Authenticated, link.state.value)

        ring.dropConnection(8)
        runCurrent()
        advance(1_000)

        assertEquals(LinkState.Authenticated, link.state.value)
        assertEquals(1, ring.createBondCalls, "one request in all, on the first connection")
        assertTrue(link.info.value.bonded)
    }
}
