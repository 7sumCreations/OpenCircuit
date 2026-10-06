package io.github.opencircuit.ble

import io.github.opencircuit.ble.Fixtures.hex
import io.github.opencircuit.ble.GattPort.BondState
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * No data command reaches a ring the phone is not bonded to: the ring ignores data from an
 * unbonded phone without a word (`docs/PROTOCOL.md` §0), so a command sent then would look
 * delivered and never be answered. The bring-up itself writes nothing to an unbonded ring, and
 * when the bond goes away on a live connection (removed in system Settings) every data command
 * is refused again until it is back. Commands are the captured ones of `docs/PROTOCOL.md` §3.
 */
class NoDataBeforeBondTest {

    private val dataCommands = listOf(hex("950000"), hex("0200ffffffff000100"), hex("d00000"), hex("070000"))

    /** Every command the fake ring received, i.e. every characteristic write. */
    private fun FakeGatt.writes(): List<String> = log.filter { it.startsWith("write ") }

    @Test
    fun whileTheBondIsBeingMadeNothingIsWrittenToTheRingAndEveryCommandIsRefused() = runTest {
        val (ring, link) = linkTo(unbondedRing())
        link.connect()
        runCurrent()
        ring.changeBondState(BondState.BONDING)
        runCurrent()

        val results = dataCommands.map { command -> backgroundScope.async { link.send(command) } }
        runCurrent()
        advance(39_000)

        assertEquals(LinkState.PairingNeeded, link.state.value)
        assertEquals(emptyList(), ring.writes(), "no command of any kind is written before the bond")
        results.forEach { assertEquals(SendResult.Refused(RefusalReason.NOT_AUTHENTICATED), it.getCompleted()) }
    }

    @Test
    fun aBondLostOnALiveConnectionRefusesEveryDataCommandAndWritesNone() = runTest {
        val (ring, link) = authenticatedLink()
        ring.changeBondState(BondState.NONE)
        runCurrent()
        val writesBefore = ring.writes()

        val results = dataCommands.map { link.send(it) }

        assertFalse(link.info.value.bonded)
        assertEquals(List(dataCommands.size) { SendResult.Refused(RefusalReason.NOT_BONDED) }, results)
        assertEquals(writesBefore, ring.writes(), "zero data writes while unbonded")
    }

    @Test
    fun aBondMadeAgainOnTheSameConnectionLetsDataThroughAgain() = runTest {
        val (ring, link) = authenticatedLink()
        ring.changeBondState(BondState.NONE)
        runCurrent()
        assertEquals(SendResult.Refused(RefusalReason.NOT_BONDED), link.send(hex("950000")))

        ring.changeBondState(BondState.BONDING)
        runCurrent()
        assertEquals(SendResult.Refused(RefusalReason.NOT_BONDED), link.send(hex("950000")))
        ring.changeBondState(BondState.BONDED)
        runCurrent()

        assertTrue(link.info.value.bonded)
        assertEquals(SendResult.Sent, link.send(hex("950000")))
        assertEquals("write 8327ad98 95 00 00", ring.log.last())
    }

    @Test
    fun theFirstDataCommandAfterABondMadeDuringTheBringUpIsWritten() = runTest {
        val (ring, link) = linkTo(unbondedRing())
        link.connect()
        runCurrent()
        ring.changeBondState(BondState.BONDING)
        ring.changeBondState(BondState.BONDED)
        runCurrent()

        val result = link.send(hex("950000"))

        assertEquals(SendResult.Sent, result)
        val writes = ring.writes()
        assertEquals(listOf("write 8327ad98 01 00 00", "write 8327ad98 01 01 31 82 67 00", "write 8327ad98 95 00 00"), writes)
        assertTrue(ring.log.indexOf("createBond") < ring.log.indexOf(writes.first()), "every write after the bond request")
    }
}
