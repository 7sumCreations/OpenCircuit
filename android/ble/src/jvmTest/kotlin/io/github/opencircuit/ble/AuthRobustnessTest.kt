package io.github.opencircuit.ble

import io.github.opencircuit.ble.FakeGatt.Operation
import io.github.opencircuit.ble.Fixtures.hex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The link owns the ring's auth exchange: it answers every `81 00 <challenge>` it receives, with
 * the reply `RingAuth` computes from the ring's MAC. The MAC is the System ID's (`0x2a23`) when
 * the ring has one, else the device address's; a challenge that arrives before that is settled
 * waits for it (PORTING.md D-188) instead of being answered with a guess (PORTING.md D-192).
 * Challenge / reply pairs are the captured ones in `RingAuthTest`; every other address is synthetic.
 */
class AuthRobustnessTest {

    private fun reply(hexBytes: String) = "write 8327ad98 $hexBytes"

    /** A placeholder address that is not the System ID's MAC. */
    private val otherRing = RememberedRing("AA:BB:CC:DD:EE:FF", AddressType.RANDOM, "RingConn Gen2-EEFF")

    /** `F8:79:99:F7:03:AD` with its first two digits written as fullwidth `F` (U+FF26) and `8` (U+FF18). */
    private val fullwidthAddress = "${Char(0xFF26)}${Char(0xFF18)}:79:99:F7:03:AD"

    /** A six-group address whose last digit is the Arabic-Indic three (U+0663), not an ASCII digit. */
    private val arabicIndicAddress = "F8:79:99:F7:03:A${Char(0x0663)}"

    @Test
    fun everyChallengeInOneConnectionIsAnsweredOnceEachInArrivalOrder() = runTest {
        val (ring, _) = authenticatedLink()
        val before = ring.log.size

        ring.notifyAll(listOf(Fixtures.challengeFrame0f, Fixtures.challengeFrame49, Fixtures.challengeFrame))
        runCurrent()

        assertEquals(
            listOf(reply("01 01 4b cc e6 00"), reply("01 01 0b 20 6f 00"), reply("01 01 31 82 67 00")),
            ring.log.drop(before),
        )
    }

    @Test
    fun theSameChallengeRepeatedIsAnsweredEveryTime() = runTest {
        val (ring, link) = authenticatedLink()
        val before = ring.log.size

        ring.notifyAll(listOf(Fixtures.challengeFrame, Fixtures.challengeFrame))
        runCurrent()

        assertEquals(listOf(reply("01 01 31 82 67 00"), reply("01 01 31 82 67 00")), ring.log.drop(before))
        assertEquals(LinkState.Authenticated, link.state.value)
    }

    /** Upstream reads only `[1] == 0x00` and `[2]`, never the trailer (RingSession.swift:5292-5294); so does the link. */
    @Test
    fun aChallengeWithAWrongTrailerIsAnsweredLikeAnyOther() = runTest {
        val (ring, link) = authenticatedLink()
        val delivered = recordFrames(link)
        val before = ring.log.size

        ring.notify(hex("81000f00"))
        runCurrent()

        assertEquals(listOf(reply("01 01 4b cc e6 00")), ring.log.drop(before))
        assertEquals(listOf("15 00 08 0a b0 a7"), delivered, "a challenge is the link's own and is never delivered")
    }

    @Test
    fun aChallengeBeforeAnyMacIsKnownIsHeldAndAnsweredWhenTheSystemIdLands() = runTest {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        ring.hold(Operation.READ)
        val malformed = RememberedRing("F8-79-99-F7-03-AD", AddressType.RANDOM, "RingConn Gen2-03AD")
        val link = RingLink(malformed, ring, backgroundScope)
        link.connect()
        runCurrent()
        assertEquals("read 00002a23", ring.log.last())

        ring.notify(Fixtures.challengeFrame0f)
        runCurrent()
        assertEquals("read 00002a23", ring.log.last(), "no reply while no MAC is known")
        ring.release(Operation.READ)
        runCurrent()

        val afterSystemId = ring.log.dropWhile { it != "read 00002a23" }.drop(1)
        assertEquals(reply("01 01 4b cc e6 00"), afterSystemId.first(), "the held challenge is answered as soon as the MAC lands")
        assertEquals(1, ring.log.count { it == reply("01 01 4b cc e6 00") })
        assertEquals(LinkState.Authenticated, link.state.value)
        assertEquals("F8:79:99:F7:03:AD", link.info.value.mac)
    }

    @Test
    fun aChallengeBeforeTheSystemIdIsReadWaitsForItEvenWhenTheAddressParses() = runTest {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        ring.hold(Operation.READ)
        val link = RingLink(otherRing, ring, backgroundScope)
        link.connect()
        runCurrent()

        ring.notify(Fixtures.challengeFrame0f)
        runCurrent()
        assertEquals("read 00002a23", ring.log.last(), "not answered from the address before the System ID is read")
        ring.release(Operation.READ)
        runCurrent()

        val replies = ring.log.filter { it.startsWith(reply("01 01")) }
        assertEquals(listOf(reply("01 01 4b cc e6 00"), reply("01 01 31 82 67 00")), replies, "both from the System ID's MAC")
        assertEquals(LinkState.Authenticated, link.state.value)
    }

    @Test
    fun whenTheAddressAndTheSystemIdDisagreeTheSystemIdMacIsUsedAndTheMismatchIsReported() = runTest {
        val (ring, link) = authenticatedLink(ring = otherRing)

        assertEquals(listOf(reply("01 01 31 82 67 00"), reply("d0 00 00")), ring.log.takeLast(2))
        assertEquals("F8:79:99:F7:03:AD", link.info.value.mac)
        assertTrue(link.info.value.macMismatch)
    }

    @Test
    fun whenTheAddressAndTheSystemIdAgreeNoMismatchIsReported() = runTest {
        val (_, link) = authenticatedLink()

        assertEquals("F8:79:99:F7:03:AD", link.info.value.mac)
        assertFalse(link.info.value.macMismatch)
    }

    @Test
    fun aLowerCaseAddressIsTheSameMac() = runTest {
        val noSystemId = Fixtures.acceptingRing(deviceInformation = Fixtures.deviceInformation - GattPort.SYSTEM_ID)
        val lowerCase = RememberedRing("f8:79:99:f7:03:ad", AddressType.RANDOM, "RingConn Gen2-03AD")

        val (ring, link) = authenticatedLink(noSystemId, lowerCase)

        assertEquals(listOf(reply("01 01 31 82 67 00"), reply("d0 00 00")), ring.log.takeLast(2))
        assertEquals("F8:79:99:F7:03:AD", link.info.value.mac)
        assertFalse(link.info.value.macMismatch)
    }

    /** PORTING.md D-11: only six pairs of ASCII hex digits make a MAC; Kotlin's digit parsers would take fullwidth or Arabic-Indic digits too. */
    @Test
    fun anAddressThatIsNotSixPairsOfAsciiHexDigitsIsNeverParsed() {
        val refused = listOf(
            fullwidthAddress,
            arabicIndicAddress,
            "F8:79:99:F7:03", // five groups
            "F8:79:99:F7:03:AD:00", // seven groups
            "F8-79-99-F7-03-AD",
            "F8:79:99:F7:3:AD", // one digit in a group
            "F8:79:99:F7:03:AG",
            " F8:79:99:F7:03:AD",
            "",
        )

        for (address in refused) assertNull(macFromAddress(address), "refused: \"$address\"")
        assertEquals(listOf(0xF8, 0x79, 0x99, 0xF7, 0x03, 0xAD), macFromAddress("F8:79:99:f7:03:ad")?.map { it.toInt() and 0xFF })
    }

    @Test
    fun withNoMacFromEitherSourceNoChallengeIsEverAnsweredWithAGuess() = runTest {
        val noSystemId = Fixtures.acceptingRing(deviceInformation = Fixtures.deviceInformation - GattPort.SYSTEM_ID)
        val ring = FakeGatt(backgroundScope, noSystemId)
        val fullwidth = RememberedRing(fullwidthAddress, AddressType.RANDOM, "RingConn Gen2-03AD")
        val link = RingLink(fullwidth, ring, backgroundScope)

        link.connect()
        runCurrent()
        ring.notify(Fixtures.challengeFrame)
        runCurrent()

        assertFalse(ring.log.any { it.startsWith(reply("01 01")) }, "no auth reply at all: ${ring.log}")
        assertEquals(reply("01 00 00"), ring.log.last())
        assertEquals(LinkState.Authenticating, link.state.value)
        assertNull(link.info.value.mac)
    }

    @Test
    fun aChallengeHeldOnADroppedConnectionIsNeverAnsweredOnTheNext() = runTest {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        ring.hold(Operation.READ)
        val link = RingLink(Fixtures.ring, ring, backgroundScope)
        link.connect()
        runCurrent()
        ring.notify(Fixtures.challengeFrame0f) // held: the System ID is not read yet
        runCurrent()

        ring.dropConnection(8)
        advance(1_000) // the next connection reaches the System ID read, held again
        ring.release(Operation.READ)
        runCurrent()

        assertEquals(LinkState.Authenticated, link.state.value)
        assertFalse(ring.log.contains(reply("01 01 4b cc e6 00")), "the old connection's challenge is not answered")
        assertEquals(1, ring.log.count { it.startsWith(reply("01 01")) })
    }
}
