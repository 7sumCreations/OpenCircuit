package io.github.opencircuit.app

import io.github.opencircuit.app.details.AdManufacturerData
import io.github.opencircuit.app.details.AdServiceData
import io.github.opencircuit.app.details.AdStructureParser
import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The advertisement a scan saw, decoded for the Connection details card: a list of AD structures
 * (Bluetooth Core Supplement, Part A §1), each `[length][type][length − 1 bytes]`. The records are
 * built here byte by byte in the shape a ring advertises (flags, its 128-bit data service, its
 * name, tx power, manufacturer data); every value is made up. Whatever the bytes, the parser never
 * throws: a malformed element ends or skips with a problem noted, and what came before is kept.
 */
class AdStructureParserTest {

    /** The ring's data service `8327ad99-2d87-4a22-a8ce-6dd7971c0437`, as advertised: little-endian. */
    private val dataServiceLittleEndian = bytes(
        0x37, 0x04, 0x1c, 0x97, 0xd7, 0x6d, 0xce, 0xa8, 0x22, 0x4a, 0x87, 0x2d, 0x99, 0xad, 0x27, 0x83,
    )

    private val flags = bytes(0x02, 0x01, 0x06)
    private val completeServices128 = bytes(0x11, 0x07) + dataServiceLittleEndian
    private val name = "RingConn Gen2-EEFF".toByteArray(Charsets.UTF_8)
    private val completeName = byteArrayOf((name.size + 1).toByte(), 0x09) + name
    private val txPower = bytes(0x02, 0x0a, 0xf4) // −12 dBm
    private val manufacturer = bytes(0x06, 0xff, 0xff, 0xff, 0x01, 0x02, 0x03) // company 0xffff (reserved for tests)
    private val serviceData16 = bytes(0x05, 0x16, 0x0d, 0x18, 0xaa, 0xbb) // 0x180d + aa bb

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun aRingShapedAdvertisementDecodesEveryElement() {
        val record = flags + completeServices128 + completeName + txPower + manufacturer + serviceData16

        val ad = AdStructureParser.parse(record)

        assertEquals(0x06, ad.flags)
        assertEquals(listOf("8327ad99-2d87-4a22-a8ce-6dd7971c0437"), ad.serviceUuids)
        assertEquals("RingConn Gen2-EEFF", ad.localName)
        assertTrue(ad.localNameComplete)
        assertEquals(-12, ad.txPowerDbm)
        assertEquals(listOf(AdManufacturerData(0xffff, "010203")), ad.manufacturerData)
        assertEquals(listOf(AdServiceData("180d", "aabb")), ad.serviceData)
        assertTrue(ad.problems.isEmpty(), "${ad.problems}")
    }

    @Test
    fun sixteenAndThirtyTwoBitUuidListsAreLittleEndianAndAShortenedNameIsMarked() {
        val record = bytes(0x05, 0x02, 0x0d, 0x18, 0x0f, 0x18) + // incomplete 16-bit: 180d, 180f
            bytes(0x09, 0x05, 0x78, 0x56, 0x34, 0x12, 0xef, 0xcd, 0xab, 0x90) + // complete 32-bit
            bytes(0x05, 0x08) + "Ring".toByteArray(Charsets.UTF_8)

        val ad = AdStructureParser.parse(record)

        assertEquals(listOf("180d", "180f", "12345678", "90abcdef"), ad.serviceUuids)
        assertEquals("Ring", ad.localName)
        assertFalse(ad.localNameComplete)
        assertTrue(ad.problems.isEmpty())
    }

    @Test
    fun serviceDataWith32And128BitUuids() {
        val record = bytes(0x07, 0x20, 0x78, 0x56, 0x34, 0x12, 0x01, 0x02) +
            byteArrayOf(0x12, 0x21) + dataServiceLittleEndian + bytes(0x7f)

        val ad = AdStructureParser.parse(record)

        assertEquals(
            listOf(AdServiceData("12345678", "0102"), AdServiceData("8327ad99-2d87-4a22-a8ce-6dd7971c0437", "7f")),
            ad.serviceData,
        )
    }

    @Test
    fun theZeroPaddingAndroidAddsAfterTheRecordIsNotAProblem() {
        val record = flags + completeName + ByteArray(40)

        val ad = AdStructureParser.parse(record)

        assertEquals("RingConn Gen2-EEFF", ad.localName)
        assertTrue(ad.problems.isEmpty(), "${ad.problems}")
    }

    @Test
    fun anEmptyRecordDecodesToNothing() {
        val ad = AdStructureParser.parse(ByteArray(0))

        assertNull(ad.flags)
        assertTrue(ad.serviceUuids.isEmpty())
        assertNull(ad.localName)
        assertTrue(ad.problems.isEmpty())
    }

    @Test
    fun anElementLongerThanTheRecordStopsTheParseAndKeepsWhatCameBefore() {
        val record = flags + txPower + bytes(0x1e, 0xff, 0x01, 0x02) // claims 30 bytes, 3 left

        val ad = AdStructureParser.parse(record)

        assertEquals(0x06, ad.flags)
        assertEquals(-12, ad.txPowerDbm)
        assertTrue(ad.manufacturerData.isEmpty())
        assertEquals(1, ad.problems.size)
        assertTrue("30" in ad.problems.single() && "byte 6" in ad.problems.single(), ad.problems.single())
    }

    @Test
    fun aLengthOf255WithAFewBytesLeftIsAProblemNotACrash() {
        val ad = AdStructureParser.parse(bytes(0xff, 0x09, 0x41, 0x42))

        assertNull(ad.localName)
        assertEquals(1, ad.problems.size)
    }

    @Test
    fun aZeroLengthElementFollowedByMoreDataEndsTheParseWithAProblem() {
        val record = flags + bytes(0x00) + txPower

        val ad = AdStructureParser.parse(record)

        assertEquals(0x06, ad.flags)
        assertNull(ad.txPowerDbm, "nothing after the zero length is read")
        assertEquals(1, ad.problems.size)
    }

    @Test
    fun elementsWithTooFewBytesForTheirTypeAreProblemsAndLeaveTheFieldEmpty() {
        val record = bytes(0x01, 0x01) + // flags, no value
            bytes(0x01, 0x0a) + // tx power, no value
            bytes(0x02, 0xff, 0x4c) + // manufacturer data, half a company id
            bytes(0x02, 0x16, 0x0d) // service data, half a 16-bit uuid

        val ad = AdStructureParser.parse(record)

        assertNull(ad.flags)
        assertNull(ad.txPowerDbm)
        assertTrue(ad.manufacturerData.isEmpty())
        assertTrue(ad.serviceData.isEmpty())
        assertEquals(4, ad.problems.size, "${ad.problems}")
    }

    @Test
    fun aUuidListWithLeftoverBytesKeepsTheWholeUuidsAndNotesTheRest() {
        val ad = AdStructureParser.parse(bytes(0x04, 0x03, 0x0d, 0x18, 0x0f))

        assertEquals(listOf("180d"), ad.serviceUuids)
        assertEquals(1, ad.problems.size)
    }

    @Test
    fun aNameThatIsNotValidUtf8IsShownWithReplacementCharacters() {
        val ad = AdStructureParser.parse(bytes(0x04, 0x09, 0x52, 0xff, 0x67))

        assertEquals("R�g", ad.localName)
    }

    @Test
    fun unknownTypesAreListedNotDropped() {
        val ad = AdStructureParser.parse(bytes(0x03, 0x19, 0x40, 0x03) + flags) // appearance, then flags

        assertEquals(listOf(0x19), ad.otherTypes)
        assertEquals(0x06, ad.flags)
    }

    @Test
    fun hexStaysAsciiWhateverTheDefaultLocale() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"))
            val ad = AdStructureParser.parse(completeServices128 + manufacturer)
            assertEquals("8327ad99-2d87-4a22-a8ce-6dd7971c0437", ad.serviceUuids.single())
            assertEquals("010203", ad.manufacturerData.single().dataHex)
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun noPrefixOfARealRecordAndNoRandomRecordThrows() {
        val record = flags + completeServices128 + completeName + txPower + manufacturer + serviceData16
        for (end in 0..record.size) AdStructureParser.parse(record.copyOf(end))
        val random = Random(20261006)
        repeat(2_000) { AdStructureParser.parse(random.nextBytes(random.nextInt(0, 80))) }
    }

    @Test
    fun theCallersBytesAreNeitherChangedNorShared() {
        val record = flags + manufacturer
        val before = record.copyOf()

        val ad = AdStructureParser.parse(record)
        assertTrue(record.contentEquals(before), "parsing does not change the record")
        record.fill(0)

        assertEquals("010203", ad.manufacturerData.single().dataHex, "the result does not share the record's bytes")
    }
}
