package io.github.opencircuit.ble

import io.github.opencircuit.ble.FakeGatt.Operation
import io.github.opencircuit.ble.Fixtures.hex
import io.github.opencircuit.ble.GattPort.BondState
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The link's step-by-step record for a "connection details" screen: each bring-up step started
 * and finished, each bond-state change marked as before or after the link asked for the bond,
 * the MTU granted, failures and teardowns, stamped with the milliseconds since the connection
 * attempt started, on the scheduler's clock. At most the last 64 entries are kept, and none
 * names the ring's address, its MAC or any frame bytes.
 */
class LinkDiagnosticsTest {

    private fun LinkDiagnostics.events(): List<String> = diagnostics.value.map { it.event }

    private fun LinkDiagnostics.detailOf(event: String): String = diagnostics.value.last { it.event == event }.detail

    /** The app reaches the diagnostics by a cast, so the link the public factory builds must offer them. */
    @Test
    fun theLinkThePublicFactoryBuildsOffersItsDiagnostics() = runTest {
        val link = RingLink(Fixtures.ring, FakeGatt(backgroundScope, Fixtures.acceptingRing()), backgroundScope)

        link.connect()
        runCurrent()

        val diagnostics = (link as? LinkDiagnostics)?.diagnostics?.value ?: error("no diagnostics on the factory's link")
        assertEquals("Authenticated", diagnostics.last().event)
    }

    @Test
    fun aColdBringUpRecordsEveryStepInOrder() = runTest {
        val (_, link) = linkTo()

        link.connect()
        runCurrent()

        assertEquals(
            listOf(
                "connect started", "connect finished",
                "discover started", "discover finished",
                "bond started", "bond finished",
                "MTU started", "MTU finished",
                "CCCD started", "CCCD finished",
                "DIS read started", "DIS read finished", "MAC source",
                "DIS read started", "DIS read finished",
                "DIS read started", "DIS read finished",
                "DIS read started", "DIS read finished",
                "auth started", "auth finished",
                "auth reply started", "auth reply finished",
                "Authenticated",
            ),
            link.events(),
        )
        assertEquals("direct", link.detailOf("connect started"))
        assertEquals("6 characteristics", link.detailOf("discover finished"))
        assertEquals("read BONDED", link.detailOf("bond started"))
        assertEquals("asked 517", link.detailOf("MTU started"))
        assertEquals("granted 247", link.detailOf("MTU finished"))
        assertEquals(listOf("2a23", "2a26", "2a29", "2a27"), link.diagnostics.value.filter { it.event == "DIS read finished" }.map { it.detail })
        assertEquals("System ID", link.detailOf("MAC source"))
    }

    @Test
    fun entriesAreStampedOnTheSchedulersClockFromTheirOwnConnectionAttempt() = runTest {
        val (ring, link) = linkTo()
        ring.hold(Operation.DISCOVER_SERVICES)

        link.connect()
        runCurrent()
        advance(1_500)
        ring.release(Operation.DISCOVER_SERVICES)
        runCurrent()
        advance(3_000)
        ring.dropConnection(8)
        runCurrent()
        advance(1_000) // the reconnect starts here

        val entries = link.diagnostics.value
        assertEquals(0, entries.first { it.event == "discover started" }.sinceConnectMillis)
        assertEquals(1_500, entries.first { it.event == "discover finished" }.sinceConnectMillis)
        assertEquals(4_500, entries.first { it.event == "disconnected" }.sinceConnectMillis)
        assertEquals("status 8", entries.first { it.event == "disconnected" }.detail)
        assertEquals(4_500, entries.first { it.event == "closed" }.sinceConnectMillis)
        val reconnect = entries.indexOfLast { it.event == "connect started" }
        assertTrue(reconnect > entries.indexOfFirst { it.event == "closed" })
        assertEquals(0, entries[reconnect].sinceConnectMillis, "a reconnect starts its own count")
        assertTrue(entries.drop(reconnect).all { it.sinceConnectMillis == 0L })
    }

    @Test
    fun theTeardownTheMtuGrantAndAFailedStepAreRecorded() = runTest {
        val (ring, link) = linkTo(Fixtures.acceptingRing(mtuGrant = 185))
        link.connect()
        runCurrent()
        assertEquals("granted 185", link.detailOf("MTU finished"))
        ring.hold(Operation.WRITE)
        backgroundScope.launch { link.send(hex("950000")) }
        runCurrent()

        advance(5_000)

        assertEquals("write", link.detailOf("timed out"))
        assertEquals("linkDropped, 1 undelivered frames", link.detailOf("closed"))
        assertEquals("attempt 1 in 1000 ms", link.detailOf("reconnect scheduled"))
    }

    @Test
    fun aBondTheRingStartedBeforeTheLinkAskedIsMarkedBeforeCreateBond() = runTest {
        val (ring, link) = linkTo(unbondedRing())
        ring.hold(Operation.DISCOVER_SERVICES)
        link.connect()
        runCurrent()

        ring.changeBondState(BondState.BONDING) // the ring's Security Request
        runCurrent()
        ring.release(Operation.DISCOVER_SERVICES)
        runCurrent()
        ring.changeBondState(BondState.BONDED)
        runCurrent()

        val bondStates = link.diagnostics.value.filter { it.event == "bond state" }.map { it.detail }
        assertEquals(listOf("BONDING, before createBond", "BONDING → BONDED, before createBond"), bondStates)
        assertEquals("read BONDING", link.detailOf("bond started"))
        assertTrue("bond requested" !in link.events())
        assertEquals("bonded", link.detailOf("bond finished"))
    }

    @Test
    fun aBondTheLinkAskedForIsMarkedAfterCreateBond() = runTest {
        val (ring, link) = linkTo(unbondedRing())
        link.connect()
        runCurrent()

        ring.changeBondState(BondState.BONDING)
        ring.changeBondState(BondState.NONE)
        runCurrent()

        assertEquals("read NONE", link.detailOf("bond started"))
        assertEquals("createBond accepted", link.detailOf("bond requested"))
        val bondStates = link.diagnostics.value.filter { it.event == "bond state" }.map { it.detail }
        assertEquals(listOf("NONE → BONDING, after createBond", "BONDING → NONE, after createBond"), bondStates)
        assertEquals("BOND_NOT_COMPLETED", link.detailOf("pairing failed"))
    }

    /**
     * Turning Bluetooth off and on is the one link event the phone's owner can cause at will, and
     * the diagnostics are the only place it can be read on a phone with no cable: every change of
     * the adapter's state is noted, "before → after", and a repeat of the same state is not.
     */
    @Test
    fun bluetoothTurnedOffAndOnIsRecordedOncePerChange() = runTest {
        val (_, link) = linkTo()
        link.onAdapterState(AdapterState.ON) // the broadcasts' first read, at build time
        link.onAdapterState(AdapterState.ON) // and its re-read from the receivers' thread
        link.connect()
        runCurrent()

        link.onAdapterState(AdapterState.TURNING_OFF)
        link.onAdapterState(AdapterState.OFF)
        link.onAdapterState(AdapterState.OFF)
        link.onAdapterState(AdapterState.TURNING_ON)
        link.onAdapterState(AdapterState.ON)
        runCurrent()

        val adapterLines = link.diagnostics.value.filter { it.event == "Bluetooth adapter" }.map { it.detail }
        assertEquals(listOf("ON", "ON → TURNING_OFF", "TURNING_OFF → OFF", "OFF → TURNING_ON", "TURNING_ON → ON"), adapterLines)
        assertEquals(LinkState.Authenticated, link.state.value)
    }

    @Test
    fun notStreamingAndASuspectedLostBondAreRecorded() = runTest {
        val (ring, link) = linkTo(Fixtures.acceptingRing().copy(acceptedAuthReply = null))
        link.connect()
        runCurrent()
        advance(10_000)
        assertEquals(10_000, link.diagnostics.value.last { it.event == "not streaming" }.sinceConnectMillis)

        ring.hold(Operation.DISCOVER_SERVICES)
        ring.dropConnection(8) // 10 s into a connection through discovery: not an early drop
        runCurrent()
        repeat(3) {
            advance((link.state.value as LinkState.Reconnecting).delay.toMillis())
            ring.dropConnection(5)
            runCurrent()
        }

        assertEquals("3 early drops in a row", link.detailOf("bond lost suspected"))
    }

    @Test
    fun onlyTheLast64EntriesAreKeptOldestFirst() = runTest {
        val (ring, link) = linkTo()
        link.connect()
        runCurrent()

        repeat(100) {
            ring.notify(Fixtures.challengeFrame) // each answered: "auth reply started" and "finished"
            runCurrent()
        }

        val entries = link.diagnostics.value
        assertEquals(64, entries.size)
        assertEquals(List(32) { listOf("auth reply started", "auth reply finished") }.flatten(), entries.map { it.event })
    }

    /**
     * Every kind of entry, from links whose address and System ID name different MACs: a whole
     * bring-up with frames, a drop and a reconnect; a bond requested and declined; a ring that
     * never streams and then drops early three times; a write that times out.
     */
    @Test
    fun noEntryNamesTheAddressTheMacOrAnyFrameBytes() = runTest {
        val placeholder = RememberedRing("AA:BB:CC:DD:EE:FF", AddressType.RANDOM, "RingConn Gen2-03AD")
        val entries = mutableListOf<LinkDiagnostic>()

        val (ring, link) = linkTo(Fixtures.acceptingRing(), placeholder)
        link.connect()
        runCurrent()
        assertTrue(link.info.value.macMismatch)
        ring.notifyAll(listOf(Fixtures.sleepPage4c, Fixtures.heartbeat(3), Fixtures.challengeFrame, Fixtures.descriptor10))
        runCurrent()
        ring.dropConnection(8)
        runCurrent()
        advance(1_000)
        assertEquals(LinkState.Authenticated, link.state.value)
        assertEquals("System ID, differs from the device address", link.detailOf("MAC source"))
        ring.hold(Operation.WRITE)
        backgroundScope.launch { link.send(hex("950000")) }
        runCurrent()
        advance(5_000)
        entries += link.diagnostics.value

        val (pairingRing, pairing) = linkTo(unbondedRing(), placeholder)
        pairing.connect()
        runCurrent()
        pairingRing.changeBondState(BondState.BONDING)
        pairingRing.changeBondState(BondState.NONE)
        runCurrent()
        entries += pairing.diagnostics.value

        val (silentRing, silent) = linkTo(Fixtures.acceptingRing().copy(acceptedAuthReply = null), placeholder)
        silent.connect()
        runCurrent()
        advance(10_000)
        assertEquals(LinkState.NotStreaming, silent.state.value)
        silentRing.hold(Operation.DISCOVER_SERVICES)
        silentRing.dropConnection(8)
        runCurrent()
        repeat(3) {
            advance((silent.state.value as LinkState.Reconnecting).delay.toMillis())
            silentRing.dropConnection(5)
            runCurrent()
        }
        assertEquals(LinkState.BondLostSuspected, silent.state.value)
        entries += silent.diagnostics.value

        val kinds = entries.map { it.event }.toSet()
        listOf("MAC source", "timed out", "pairing failed", "not streaming", "bond lost suspected", "closed").forEach {
            assertTrue(it in kinds, "the scenarios reach \"$it\"")
        }
        val text = entries.joinToString("\n") { "${it.event} | ${it.detail}" }
        val forbidden = listOf(
            "AA:BB:CC:DD:EE:FF", "AABBCCDDEEFF", "aa:bb:cc:dd:ee:ff", "aabbccddeeff",
            "F8:79:99:F7:03:AD", "F87999F703AD", "f8:79:99:f7:03:ad", "f87999f703ad", "f87999fffef703ad",
        )
        forbidden.forEach { assertTrue(it.lowercase() !in text.lowercase(), "\"$it\" in:\n$text") }
        val macLike = Regex("""(?i)\b(?:[0-9a-f]{2}[:\- ]){5}[0-9a-f]{2}\b|\b[0-9a-f]{12,}\b""")
        assertEquals(null, macLike.find(text)?.value, text)
        val spacedBytes = Regex("""(?i)\b[0-9a-f]{2}(?: [0-9a-f]{2}){2,}\b""")
        assertEquals(null, spacedBytes.find(text)?.value, "frame bytes in:\n$text")
    }
}
