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
 * Addresses are the project's two placeholders, which name no device.
 */
class RememberedRingStoreTest {

    private val values = InMemoryKeyValues()
    private val store = PrefsRememberedRingStore(values)

    private fun stored(raw: Any) = values.putRaw(KEY, raw)

    @Test
    fun aSavedRingReadsBackWithItsAddressTypeAndName() {
        val ring = RememberedRing(FF, AddressType.RANDOM, "RingConn Gen2 TEST")

        assertTrue(store.save(ring))

        assertEquals(ring, store.load())
    }

    @Test
    fun aRelaunchedStoreOverTheSameFileReadsTheSameRing() {
        val ring = RememberedRing(ZERO, AddressType.PUBLIC, "R")
        store.save(ring)

        assertEquals(ring, PrefsRememberedRingStore(values).load())
    }

    @Test
    fun noNameAndAnEmptyNameStayDifferent() {
        store.save(RememberedRing(FF, AddressType.RANDOM, null))
        assertNull(store.load()!!.name)

        store.save(RememberedRing(FF, AddressType.RANDOM, ""))
        assertEquals("", store.load()!!.name)
    }

    @Test
    fun aNameWithTheSeparatorNewlinesAndNonAsciiTextReadsBackExactly() {
        val name = "Ring | é ☃\n|-|b64:"
        store.save(RememberedRing(FF, AddressType.RANDOM, name))

        assertEquals(name, store.load()!!.name)
    }

    @Test
    fun aLowerCaseAddressIsSavedUpperCase() {
        store.save(RememberedRing(FF.lowercase(Locale.ROOT), AddressType.RANDOM, null))

        assertEquals(FF, store.load()!!.address)
        assertTrue((values.raw(KEY) as String).startsWith("$FF|"))
    }

    @Test
    fun aStoredLowerCaseAddressReadsBackUpperCase() {
        stored("${ZERO.lowercase(Locale.ROOT)}|RANDOM|-")

        assertEquals(ZERO, store.load()!!.address)
    }

    @Test
    fun addressesCompareWhateverTheirCaseAndWhateverTheDefaultLocale() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertTrue(RingAddress.same(FF.lowercase(Locale.ROOT), FF))
            assertFalse(RingAddress.same(FF, ZERO))
            assertEquals(FF, RingAddress.normalized(FF.lowercase(Locale.ROOT)))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun anAddressThatIsNotSixAsciiHexPairsIsRefusedAndKeepsTheSavedRing() {
        val kept = RememberedRing(FF, AddressType.RANDOM, null)
        store.save(kept)

        val bad = listOf(
            "AA:BB:CC:DD:EE", // five pairs
            "$FF:00", // seven pairs
            FF.replace(':', '-'), // another separator
            "AA:BB:CC:DD:EE:GG", // not hex
            "AA:BB:CC:DD:EE:０５", // fullwidth digits, which Char.isDigit accepts
            "AA:BB:CC:DD:EE:5", // one digit
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
            FF, // address only
            "$FF|RANDOM", // no name field
            "$FF|RANDOM|-|extra",
            "$FF|STATIC|-", // not an address type
            "$FF|random|-", // the type's name is exact
            "AA:BB:CC:DD:EE|RANDOM|-", // a short address
            "AA:BB:CC:DD:EE:０６|RANDOM|-", // fullwidth digits
            "$FF|RANDOM|name", // no name marker
            "$FF|RANDOM|b64:@@not base64@@",
            "$FF|RANDOM|b64:" + Base64.getEncoder().encodeToString(byteArrayOf(0xff.toByte(), 0xfe.toByte())), // not UTF-8
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
        store.save(RememberedRing(FF, AddressType.RANDOM, "R"))

        assertTrue(store.clear())

        assertNull(store.load())
        assertNull(values.raw(KEY))
    }

    @Test
    fun aFailedWriteIsReported() {
        values.failWrites = true

        assertFalse(store.save(RememberedRing(FF, AddressType.RANDOM, null)))
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

        /** The project's placeholder addresses. */
        const val FF = "AA:BB:CC:DD:EE:FF"
        const val ZERO = "AA:BB:CC:DD:EE:00"
    }
}
