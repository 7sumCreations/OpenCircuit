package io.github.opencircuit.ringkit

import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Foundation text the export's bytes rest on — `JSONSerialization`'s number text, escapes and key
 * order, `ISO8601DateFormatter` and `DateFormatter("yyyy-MM-dd")` dates, Swift's `String(Double)` —
 * against texts measured on the pinned toolchain (Swift 6.3.2, macOS 26). Every expected string here
 * was printed by Swift, never by this code; where the port keeps a deliberate difference, the
 * Kotlin text is pinned and the measured Foundation text is quoted beside it.
 */
class FoundationTextHazardTest {

    private fun bits(unsigned: String): Long = unsigned.toULong().toLong()
    private fun ref(bits: Long) = FoundationDate.referenceBits(bits)
    private val utc: ZoneId = ZoneOffset.UTC

    // --- JSON numbers: `%.17g` with trailing zeros stripped ---

    @Test
    fun `JSON numbers print as Foundation's 17 significant digits with trailing zeros stripped`() {
        val measured = listOf(
            72.0 to "72", 0.9375 to "0.9375", 34.2 to "34.200000000000003", 0.1 to "0.10000000000000001",
            1.0 / 3.0 to "0.33333333333333331", 1e-7 to "9.9999999999999995e-08", 1e-5 to "1.0000000000000001e-05",
            1e16 to "10000000000000000", 1e21 to "1e+21", 123456789012.5 to "123456789012.5", -0.0 to "-0",
            5e-324 to "4.9406564584124654e-324", Double.MAX_VALUE to "1.7976931348623157e+308", 36.5 to "36.5",
            0.30000000000000004 to "0.30000000000000004", 2.5e-4 to "0.00025000000000000001", 100.0 to "100",
            1e15 to "1000000000000000", 12345678.9 to "12345678.9", 9.5e15 to "9500000000000000",
            123456789012345678.0 to "1.2345678901234568e+17", 1e17 to "1e+17", 1e-4 to "0.0001",
            9.999e-5 to "9.9989999999999996e-05", 2.2250738585072014e-308 to "2.2250738585072014e-308",
            0.5 to "0.5", 1.5 to "1.5", 12.0 to "12", 1e300 to "1.0000000000000001e+300", 4.35 to "4.3499999999999996",
            0.0 to "0",
        )
        for ((v, text) in measured) assertEquals(text, FoundationText.jsonNumber(v), "jsonNumber($v)")
    }

    // --- Swift's String(Double): shortest round-trip, exponent above 2^53 or below 1e-4 ---

    @Test
    fun `Swift's description of a double is the shortest round trip with its own exponent rule`() {
        val measured = listOf(
            72.0 to "72.0", 0.9375 to "0.9375", 34.2 to "34.2", 0.1 to "0.1", 1.0 / 3.0 to "0.3333333333333333",
            1e-7 to "1e-07", 1e-5 to "1e-05", 1e16 to "1e+16", 1e21 to "1e+21", 123456789012.5 to "123456789012.5",
            -0.0 to "-0.0", 5e-324 to "5e-324", Double.MAX_VALUE to "1.7976931348623157e+308", 36.5 to "36.5",
            0.30000000000000004 to "0.30000000000000004", 2.5e-4 to "0.00025", 100.0 to "100.0",
            1e15 to "1000000000000000.0", 12345678.9 to "12345678.9", 1e-4 to "0.0001", 0.001 to "0.001",
            1e17 to "1e+17", 9007199254740992.0 to "9007199254740992.0", 150.0 to "150.0", 450.5 to "450.5",
            9007199254740994.0 to "9.007199254740994e+15", 9.5e15 to "9.5e+15", 9999999999999998.0 to "9.999999999999998e+15",
            Math.nextDown(1e-4) to "9.999999999999999e-05", 1.5e-7 to "1.5e-07", 2.5e-5 to "2.5e-05",
            123456789.123 to "123456789.123", 1e100 to "1e+100", 1e-100 to "1e-100", 4.35 to "4.35",
            2.2250738585072014e-308 to "2.2250738585072014e-308", 100.25 to "100.25", -1e-5 to "-1e-05", -9.5e15 to "-9.5e+15",
            Double.POSITIVE_INFINITY to "inf", Double.NEGATIVE_INFINITY to "-inf",
        )
        for ((v, text) in measured) assertEquals(text, FoundationText.swiftDescription(v), "swiftDescription($v)")
        // NaN prints unsigned whatever its sign or signalling bit (measured: -nan, snan, -snan, payload NaN).
        for (nanBits in listOf(0x7ff8000000000000L, bits("18444492273895866368"), 0x7ff4000000000000L, 0x7ff8000000000001L)) {
            assertEquals("nan", FoundationText.swiftDescription(java.lang.Double.longBitsToDouble(nanBits)))
        }
    }

    @Test
    fun `a tie between two shortest decimals goes to the even last digit as Swift's description does`() {
        // Measured (Swift 6.3.2, String(Double)): 2^49 + k/4 has an ulp of 1/8, so at .25 and .75 two
        // 16-digit decimals both read back and sit at the same distance; Swift prints the one whose last
        // digit is even. Found by the export's 10 000-double sweep (the samples CSV's fraction branch).
        val measured = listOf(
            562949953421312.25 to "562949953421312.2",
            562949953421312.75 to "562949953421312.8",
            562949953421313.25 to "562949953421313.2",
            562949953421313.75 to "562949953421313.8",
            -568607489321053.75 to "-568607489321053.8",
            600000000000000.25 to "600000000000000.2",
            600000000000000.75 to "600000000000000.8",
            700000000000001.25 to "700000000000001.2",
            700000000000001.75 to "700000000000001.8",
            562949953421312.5 to "562949953421312.5",
        )
        for ((v, text) in measured) assertEquals(text, FoundationText.swiftDescription(v), "swiftDescription(${v.toRawBits()})")
    }

    @Test
    fun `percent-zero-f rounds the binary value half to even through the one fixed-decimal helper`() {
        val measured = listOf(2.5 to "2", 3.5 to "4", -0.0 to "-0", 0.5 to "0", 1e20 to "100000000000000000000", 72.0 to "72", -3.0 to "-3")
        for ((v, text) in measured) assertEquals(text, swiftFixed(v, 0), "%.0f of $v")
    }

    // --- ISO-8601 with milliseconds: the rounding rule ---

    @Test
    fun `the millisecond is rounded from the Date's double as Foundation does, not from the exact value`() {
        // Each instant is the double a Swift `Date(timeIntervalSince1970:)` held (bits of its seconds
        // since 2001); the text is what ISO8601DateFormatter printed for it.
        val measured = listOf(
            "4739337206362017890" to "2023-11-14T22:13:20.001Z", // unix 1700000000.0005 — exact value just BELOW the tie
            "4739337206362026278" to "2023-11-14T22:13:20.002Z", // .0015
            "4739337206370398110" to "2023-11-14T22:13:21.000Z", // .9995 carries into the next second
            "4739337206362017052" to "2023-11-14T22:13:20.000Z", // .0004
            "4739337206370398948" to "2023-11-14T22:13:21.000Z", // .9996
            "13964861880825557287" to "1969-12-31T23:59:59.998Z", // unix -0.0015
            "13964861880825548898" to "1970-01-01T00:00:00.000Z", // unix -0.0005
            "13965681567846367232" to "1966-10-31T15:53:29.188Z", // an exact .5 product before 1970: half UP, not away from zero
        )
        for ((b, text) in measured) assertEquals(text, FoundationText.iso8601(ref(bits(b)), utc), "bits $b")
        assertEquals("1969-12-31T23:59:58.500Z", FoundationText.iso8601(FoundationDate.unix(-1.5), utc))
        assertEquals("1970-01-01T00:00:00.123Z", FoundationText.iso8601(FoundationDate.unix(0.123456), utc))
    }

    @Test
    fun `half a millisecond before midnight carries the date-only label into the next day`() {
        // UTC midnight 2023-11-15 minus 0.5 ms and minus 0.6 ms (measured, ISO and yyyy-MM-dd).
        assertEquals("2023-11-15T00:00:00.000Z", FoundationText.iso8601(ref(4739337260049100702L), utc))
        assertEquals("2023-11-15", FoundationText.dateOnly(ref(4739337260049100702L), utc))
        assertEquals("2023-11-14T23:59:59.999Z", FoundationText.iso8601(ref(4739337260049099863L), utc))
        assertEquals("2023-11-14", FoundationText.dateOnly(ref(4739337260049099863L), utc))
        // The same at Kolkata's local midnight.
        val kolkata = ZoneId.of("Asia/Kolkata")
        assertEquals("2023-11-15T00:00:00.000+05:30", FoundationText.iso8601(ref(4739337093954662302L), kolkata))
        assertEquals("2023-11-15", FoundationText.dateOnly(ref(4739337093954662302L), kolkata))
        assertEquals("2023-11-14T23:59:59.999+05:30", FoundationText.iso8601(ref(4739337093954661463L), kolkata))
        assertEquals("2023-11-14", FoundationText.dateOnly(ref(4739337093954661463L), kolkata))
    }

    // --- offsets, years ---

    @Test
    fun `the offset prints Z at zero and hours, minutes and any seconds otherwise`() {
        val t = FoundationDate.unix(1_700_000_000.0)
        val measured = listOf(
            "Europe/Amsterdam" to "2023-11-14T23:13:20.000+01:00", "America/St_Johns" to "2023-11-14T18:43:20.000-03:30",
            "Asia/Kolkata" to "2023-11-15T03:43:20.000+05:30", "Europe/London" to "2023-11-14T22:13:20.000Z",
            "UTC" to "2023-11-14T22:13:20.000Z", "Pacific/Kiritimati" to "2023-11-15T12:13:20.000+14:00",
        )
        for ((zone, text) in measured) assertEquals(text, FoundationText.iso8601(t, ZoneId.of(zone)), zone)
        assertEquals("1969-12-31T23:15:30.000-00:44:30", FoundationText.iso8601(FoundationDate.unix(0.0), ZoneId.of("Africa/Monrovia")))
        // tzdata: Foundation's Amsterdam offset in 1906 is 0 (measured); the JDK's rules agree.
        assertEquals("1906-08-16T20:26:40.000Z", FoundationText.iso8601(FoundationDate.unix(-2_000_000_000.0), ZoneId.of("Europe/Amsterdam")))
        // Kept difference: Foundation drops the seconds of a fixed seconds-offset zone (`+01:01` for
        // 3661 s); a device zone is a region zone, whose seconds offsets Foundation prints (Monrovia).
        assertEquals("2023-11-14T23:14:21.000+01:01:01", FoundationText.iso8601(t, ZoneOffset.ofTotalSeconds(3661)))
    }

    @Test
    fun `dates print the local day in the zone passed`() {
        val t = FoundationDate.unix(1_699_920_000.0)
        assertEquals("2023-11-14", FoundationText.dateOnly(t, ZoneId.of("Europe/Amsterdam")))
        assertEquals("2023-11-13", FoundationText.dateOnly(t, ZoneId.of("America/Los_Angeles")))
    }

    @Test
    fun `a five-digit year prints unsigned and a date before the Gregorian switch prints proleptic`() {
        assertEquals("10000-01-01T00:00:00.000Z", FoundationText.iso8601(ref(4777583434807640064L), utc))
        assertEquals("10000-01-01", FoundationText.dateOnly(FoundationDate.unix(253_402_300_800.0), utc))
        // Kept difference: Foundation prints the Julian `0001-01-03T00:00:00.000Z` here (measured).
        assertEquals("0001-01-01T00:00:00.000Z", FoundationText.iso8601(ref(bits("13991949308610478080")), utc))
        assertEquals("0001-01-01", FoundationText.dateOnly(FoundationDate.unix(-62_135_596_800.0), utc))
    }

    // --- JSON key order ---

    private fun sortedByFoundation(keys: List<String>): List<String> = keys.shuffled(java.util.Random(7)).sortedWith(FoundationText.JSON_KEY_ORDER)

    @Test
    fun `JSON keys sort in Foundation's measured order, not by code point`() {
        val probe = listOf("_", "9", "10", "a", "a.b", "aa", "ab", "aB", "B", "Z")
        assertEquals(probe, sortedByFoundation(probe))
        val channels = listOf("page4cCount", "page4CCount", "page4DCount", "page47Count")
        assertEquals(channels, sortedByFoundation(channels))
        val numbers = listOf("0", "00", "1", "01", "001", "2", "10")
        assertEquals(numbers, sortedByFoundation(numbers))
        val measuredMixed = listOf(
            "a", "a_1", "a.1", "a0", "a00", "a1", "a01", "a001", "a1b", "a1B", "a01b", "a2", "a9", "a09", "a10",
            "aa", "aA", "Aa", "AA", "ab", "aB", "Ab", "AB", "b", "B",
        )
        assertEquals(measuredMixed, sortedByFoundation(measuredMixed))
        // Upper-case letter before a digit run, after the same key in lower case (measured `a001` < upper-case `a1`).
        val upperRun = listOf("a001", "${'A'}1")
        assertEquals(upperRun, sortedByFoundation(upperRun))
        val runs = listOf("x1y2", "x1y02", "x01y2", "x1y10", "x10y1")
        assertEquals(runs, sortedByFoundation(runs))
        // Real export keys whose code-point order is the reverse (`R` < `e` and `S` < `s` by code point).
        val vocabulary = listOf("exportedAt", "exportRange", "steps", "stepSamples")
        assertEquals(vocabulary, sortedByFoundation(vocabulary))
    }

    @Test
    fun `every printable ASCII character has Foundation's measured rank`() {
        val measured = listOf(
            " ", "_", "-", ",", ";", ":", "!", "?", ".", "'", "\"", "(", ")", "[", "]", "{", "}", "@", "*", "/", "\\", "&", "#",
            "%", "`", "^", "+", "<", "=", ">", "|", "~", "$", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
        ) + ('a'..'z').flatMap { listOf(it.toString(), it.uppercaseChar().toString()) }
        assertEquals(95, measured.size)
        assertEquals(measured, sortedByFoundation(measured))
    }

    @Test
    fun `distinct keys never compare equal`() {
        val keys = listOf("a1", "a01", "b1", "B1", "a", "A", "1", "01", "a.b", "a_b")
        for (x in keys) for (y in keys) {
            val c = FoundationText.JSON_KEY_ORDER.compare(x, y)
            assertEquals(x == y, c == 0, "compare($x, $y) = $c")
            assertEquals(Integer.signum(c), -Integer.signum(FoundationText.JSON_KEY_ORDER.compare(y, x)), "antisymmetry $x $y")
        }
    }

    // --- JSON string escapes ---

    @Test
    fun `JSON strings escape as Foundation does`() {
        // Measured: "a/b \"q\" \\ \u{1} \u{1f} \t \n \r é 😀 \u{7f} \u{2028}" and "\u{0}\u{8}\u{c}\u{7f}\u{80}\u{feff}\u{2029}\u{1b}".
        assertEquals(
            "\"a\\/b \\\"q\\\" \\\\ \\u0001 \\u001f \\t \\n \\r é 😀 \u007f  \"",
            FoundationText.jsonQuoted("a/b \"q\" \\ \u0001 \u001f \t \n \r é 😀 \u007f  "),
        )
        assertEquals("\"\\u0000\\b\\f\u007f\u0080﻿ \\u001b\"", FoundationText.jsonQuoted("\u0000\b\u000c\u007f\u0080﻿ \u001b"))
        assertEquals("\"\"", FoundationText.jsonQuoted(""))
        assertTrue(FoundationText.jsonQuoted("x").startsWith("\""))
    }
}
