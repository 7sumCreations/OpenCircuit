package io.github.opencircuit.ble

import io.github.opencircuit.ringkit.FirmwareInfo
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The text form of every public value that carries the ring's address or MAC shows neither, so a
 * value printed into a log, an assertion message or a crash report never identifies the ring.
 * The advertised name ends with two bytes of the MAC (`RingConn Gen2-03AD`), so it is left out too.
 */
class TextFormPrivacyTest {

    private fun assertNoIdentifier(text: String) {
        val lower = text.lowercase()
        assertFalse("f8:79:99" in lower, "the address or MAC is in: $text")
        assertFalse("03ad" in lower, "the MAC's tail is in: $text")
    }

    @Test
    fun aRememberedRingShowsItsAddressTypeOnly() {
        val text = Fixtures.ring.toString()
        assertNoIdentifier(text)
        assertTrue("RANDOM" in text, text)
    }

    @Test
    fun linkInfoShowsNeitherTheMacNorTheModelName() {
        val mac = Fixtures.RING_ADDRESS
        val info = LinkInfo(
            firmware = FirmwareInfo(version = "FR02.018", modelName = "RingConn Gen2-03AD", manufacturer = "RingConn", mac = mac),
            mac = mac,
            attMtu = 247,
            historySafe = false,
            bonded = true,
        )
        val text = info.toString()
        assertNoIdentifier(text)
        assertTrue("FR02.018" in text && "attMtu=247" in text && "bonded=true" in text, text)
    }

    @Test
    fun scanUpdatesShowNoRingAddress() {
        val rings = listOf(Fixtures.ring)
        for (update in listOf(ScanUpdate.Found(rings), ScanUpdate.Selected(Fixtures.ring), ScanUpdate.Choose(rings))) {
            assertNoIdentifier(update.toString())
        }
    }

    @Test
    fun valuesWithTheSameContentAreStillEqual() {
        assertTrue(Fixtures.ring == Fixtures.ring.copy())
        assertTrue(LinkInfo(mac = Fixtures.RING_ADDRESS) == LinkInfo(mac = Fixtures.RING_ADDRESS))
        assertFalse(LinkInfo(mac = Fixtures.RING_ADDRESS) == LinkInfo(mac = null))
    }
}
