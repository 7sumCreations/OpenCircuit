package io.github.opencircuit.app

import io.github.opencircuit.app.data.PrefsRememberedRingStore
import io.github.opencircuit.app.data.RingAddress
import io.github.opencircuit.ble.AddressType
import io.github.opencircuit.ble.RememberedRing
import java.util.Base64
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The remembered ring: one string under `ring.remembered.v1` holding the address (upper-case),
 * the address type and the advertised name. Anything that does not read back as all three reads
 * as no ring at all, so a damaged file never turns into a connection to a made-up address.
 * Addresses here are synthetic; none names a real device.
 */
class RememberedRingStoreTest {

    private val values = InMemoryKeyValues()
    private val store = PrefsRememberedRingStore(values)

    private fun stored(raw: Any) = values.putRaw(KEY, raw)

    @Test
    fun aSavedRingReadsBackWithItsAddressTypeAndName() {
        val ring = RememberedRing("C0:FF:EE:00:00:01", AddressType.RANDOM, "RingConn Gen2 TEST")

        assertTrue(store.save(ring))

        assertEquals(ring, store.load())
    }

    @Test
    fun aRelaunchedStoreOverTheSameFileReadsTheSameRing() {
        val ring = RememberedRing("C0:FF:EE:00:00:02", AddressType.PUBLIC, "R")
        store.save(ring)

        assertEquals(ring, PrefsRememberedRingStore(values).load())
    }

    @Test
    fun noNameAndAnEmptyNameStayDifferent() {
        store.save(RememberedRing("C0:FF:EE:00:00:03", AddressType.RANDOM, null))
        assertNull(store.load()!!.name)

        store.save(RememberedRing("C0:FF:EE:00:00:03", AddressType.RANDOM, ""))
        assertEquals("", store.load()!!.name)
    }

    @Test
    fun aNameWithTheSeparatorNewlinesAndNonAsciiTextReadsBackExactly() {
        val name = "Ring | é ☃\n|-|b64:"
        store.save(RememberedRing("C0:FF:EE:00:00:04", AddressType.RANDOM, name))

        assertEquals(name, store.load()!!.name)
    }

    @Test
    fun aLowerCaseAddressIsSavedUpperCase() {
        store.save(RememberedRing("c0:ff:ee:00:00:0a", AddressType.RANDOM, null))

        assertEquals("C0:FF:EE:00:00:0A", store.load()!!.address)
        assertTrue((values.raw(KEY) as String).startsWith("C0:FF:EE:00:00:0A|"))
    }

    @Test
    fun aStoredLowerCaseAddressReadsBackUpperCase() {
        stored("c0:ff:ee:00:00:0b|RANDOM|-")

        assertEquals("C0:FF:EE:00:00:0B", store.load()!!.address)
    }

    @Test
    fun addressesCompareWhateverTheirCaseAndWhateverTheDefaultLocale() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertTrue(RingAddress.same("c0:ff:ee:0a:bc:de", "C0:FF:EE:0A:BC:DE"))
            assertFalse(RingAddress.same("C0:FF:EE:0A:BC:DE", "C0:FF:EE:0A:BC:DF"))
            assertEquals("C0:FF:EE:0A:BC:DE", RingAddress.normalized("c0:ff:ee:0a:bc:de"))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun anAddressThatIsNotSixAsciiHexPairsIsRefusedAndKeepsTheSavedRing() {
        val kept = RememberedRing("C0:FF:EE:00:00:05", AddressType.RANDOM, null)
        store.save(kept)

        val bad = listOf(
            "C0:FF:EE:00:00", // five pairs
            "C0:FF:EE:00:00:05:06", // seven pairs
            "C0-FF-EE-00-00-05", // other separator
            "C0:FF:EE:00:00:G5", // not hex
            "C0:FF:EE:00:00:０５", // fullwidth digits, which Char.isDigit accepts
            "C0:FF:EE:00:00:5", // one digit
            "",
        )
        for (address in bad) {
            assertFalse(store.save(RememberedRing(address, AddressType.RANDOM, null)), address)
            assertEquals(kept, store.load(), address)
        }
    }

    @Test
    fun aDamagedValueReadsAsNoRing() {
        val damaged = listOf(
            "",
            "C0:FF:EE:00:00:06", // address only
            "C0:FF:EE:00:00:06|RANDOM", // no name field
            "C0:FF:EE:00:00:06|RANDOM|-|extra",
            "C0:FF:EE:00:00:06|STATIC|-", // not an address type
            "C0:FF:EE:00:00:06|random|-", // the type's name is exact
            "C0:FF:EE:00:06|RANDOM|-", // a short address
            "C0:FF:EE:00:00:０６|RANDOM|-", // fullwidth digits
            "C0:FF:EE:00:00:06|RANDOM|name", // no name marker
            "C0:FF:EE:00:00:06|RANDOM|b64:@@not base64@@",
            "C0:FF:EE:00:00:06|RANDOM|b64:" + Base64.getEncoder().encodeToString(byteArrayOf(0xff.toByte(), 0xfe.toByte())), // not UTF-8
        )
        for (raw in damaged) {
            stored(raw)
            assertNull(store.load(), "'$raw' must read as no ring")
        }
    }

    @Test
    fun aValueOfTheWrongTypeReadsAsNoRing() {
        stored(true)
        assertNull(store.load())
        stored(42)
        assertNull(store.load())
    }

    @Test
    fun clearForgetsTheRing() {
        store.save(RememberedRing("C0:FF:EE:00:00:07", AddressType.RANDOM, "R"))

        assertTrue(store.clear())

        assertNull(store.load())
        assertNull(values.raw(KEY))
    }

    @Test
    fun aFailedWriteIsReported() {
        values.failWrites = true

        assertFalse(store.save(RememberedRing("C0:FF:EE:00:00:08", AddressType.RANDOM, null)))
        assertFalse(store.clear())
        assertNull(store.load())
    }

    @Test
    fun nothingSavedReadsAsNoRing() {
        assertNull(store.load())
    }

    private companion object {
        /** The key, typed here rather than read from the code. */
        const val KEY = "ring.remembered.v1"
    }
}
