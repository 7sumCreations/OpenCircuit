package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Kotlin-only hostile-input checks for the stored-hypnogram codec: truncated and corrupt stored
 * bytes, non-ASCII digits and whitespace (Kotlin's `Character.isDigit`, `digitToInt` and `toInt`
 * accept digits Swift's JSON reader rejects), every number spelling Swift's reader accepts or
 * refuses, the text encodings it detects, rows whose times lie beyond `Instant`'s range, and
 * encode's rounding. Kept out of the upstream-port class so its count stays exact.
 *
 * Every expected value was measured on upstream's pinned Swift build by decoding the same bytes.
 * A whole payload either decodes or gives no night at all — never the rows read before an error.
 */
class SleepHypnogramCodecHazardTest {

    private fun describe(segs: List<SleepSegment>): String =
        segs.joinToString(" ") { "(${it.start.epochSecond},${it.end.epochSecond},${it.stage.rawValue},${it.provenance.rawValue})" }

    private fun dec(s: String): String = describe(SleepHypnogramCodec.decode(s.toByteArray(Charsets.UTF_8)))
    private fun decBytes(b: ByteArray): String = describe(SleepHypnogramCodec.decode(b))
    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    private fun utf16le(s: String) = s.toByteArray(Charsets.UTF_16LE)
    private fun utf16be(s: String) = s.toByteArray(Charsets.UTF_16BE)
    private fun utf32le(s: String) = s.toByteArray(charset("UTF-32LE"))
    private fun utf32be(s: String) = s.toByteArray(charset("UTF-32BE"))

    private val deep = "(1700000000,1700000150,asleepDeep,measured)"
    private val one = "(1,2,asleepDeep,measured)"

    @Test
    fun truncatedAndCorruptStoredBytesDecodeToNoNight() {
        val corrupt = listOf(
            "[[1700000000,1700000150,3],[17000", // truncated mid-row
            "[[1700000000,1700000150,3],[1700000150,1700000300,2],[17000", // two good rows, then cut: no half night
            "[[1700000000,1700000150,3]", // closing bracket lost
            "[[1700000000,1700000150,3]]x", "[[1700000000,1700000150,3]]\u0000", "[[1700000000,1700000150,3]][]",
            "[,[1700000000,1700000150,3]]", "[[1700000000,,1700000150,3]]", "[[1700000000 1700000150,3]]",
            "[[1,2,3],,]", "[,]", "[[,]]", "[[1,2,3,,]]",
            "/*c*/[[1,2,3]]", "[[1,2,3]]//c",
            "[[[1,2,3]]]", "[".repeat(600) + "]".repeat(600),
            "{}", "{\"night\":1}", "null", "3", "\"x\"", "true",
            "[[1700000000,1700000150,null]]", "[[1700000000,1700000150,true]]", "[[1700000000,1700000150,\"3\"]]",
            "[[1700000000,1700000150,[3]]]", "[[1700000000,1700000150,3],null]", "[[1700000000,1700000150,3],{}]",
            "[[1,2,3,null]]",
        )
        for (s in corrupt) assertEquals("", dec(s), "corrupt payload ${s.take(60)}")
        // Invalid UTF-8 anywhere is no night.
        assertEquals("", decBytes("[[1,2,3]]".toByteArray() + bytes(0xFF)))
        assertEquals("", decBytes("[[1,2,3]".toByteArray() + bytes(0xC3) + "]".toByteArray()))
    }

    @Test
    fun nonAsciiDigitsAndWhitespaceAreRejected() {
        val unicode = listOf(
            "[[１７００００００００,1700000150,3]]", // fullwidth start
            "[[1700000000,1700000150,３]]", // fullwidth stage
            "[[1700000000,1700000150,٣]]", // Arabic-Indic three
            "[[1,2,2],[3,4,３]]", // one bad row poisons the payload, as upstream
            " [[1700000000,1700000150,3]]", // no-break space
            "[[1,2,3]] ", "[[1,2,3]]﻿", "[﻿[1,2,3]]",
            "[[1\u000B,2,3]]", "[[1\u000C,2,3]]",
        )
        for (s in unicode) assertEquals("", dec(s), "non-ASCII payload $s")
        assertEquals("", decBytes("[[1,2,3]]".toByteArray() + bytes(0xA0)))
        // JSON's own whitespace is fine.
        assertEquals(deep, dec(" \t\n\r[ [ 1700000000 , 1700000150 , 3 ] ]\n"))
        assertEquals(deep, dec("[[1700000000,1700000150,3]] "))
        assertEquals(deep, dec("[[1700000000,1700000150,3]]\n\n"))
    }

    @Test
    fun numbersDecodeExactlyAsUpstream() {
        fun stage(token: String): String = dec("[[1,2,$token]]")
        // Integral values in any JSON spelling are integers.
        for (t in listOf("3e0", "3E0", "30e-1", "300e-2", "2.9999999999999999", "3.00000000000000000000001", "3.0e-0", "0.3e1",
                "30000000000000000000000e-22", "0.000000000000000000000000000003e30")) {
            assertEquals(one, stage(t), "token $t")
        }
        for (t in listOf("-0", "0.0", "-0.0", "0e5", "1e-400", "0")) assertEquals("(1,2,inBed,measured)", stage(t), "token $t")
        for (t in listOf("1E+0", "1e-0", "1.00000000000000001", "1" + "0".repeat(30) + "e-30")) assertEquals("(1,2,awake,measured)", stage(t), "token $t")
        // Not JSON, or not an integer: the whole payload is refused.
        for (t in listOf("03", "+3", ".3", "3.", "-", "0x3", "NaN", "Infinity", "00", "-00", "0.", "1e", "1e+", "-01", "1.e5",
                "1.5e0", "1.0000000000000002", "4.9e-324", "1e400", "-1e400")) {
            assertEquals("", stage(t), "token $t")
        }
        assertEquals(deep, dec("[[1700000000.0,1700000150,3]]"))
        assertEquals("", dec("[[1700000000.5,1700000150,3]]"))
        assertEquals(deep, dec("[[1.7e9,1700000150,3]]"))
        assertEquals(deep, dec("[[17e8,1700000150,3]]"))
        assertEquals("(1,15,asleepCore,measured)", dec("[[1,1.5e1,2]]"))
        assertEquals("", dec("[[1,15e-1,2]]"))
        assertEquals("(100,200,asleepCore,measured)", dec("[[1E2,2E2,2]]"))
        assertEquals("(100,200,asleepCore,measured)", dec("[[100,2E2,2.0E0]]"))
        // 64-bit range: integer spellings exactly; others through a double that must lie inside it.
        for (s in listOf("[[1,2,9223372036854775808]]", "[[1,2,18446744073709551616]]", "[[1,2,-9223372036854775809]]",
                "[[1,1e19,2]]", "[[-9223372036854775808.0,1,2]]", "[[-9.223372036854775808e18,1,2]]",
                "[[1,9223372036854775807.0,2]]", "[[1,9223372036854775806.0,2]]", "[[1,2,3,99999999999999999999]]")) {
            assertEquals("", dec(s), s)
        }
        // At and above 2^53 a non-integer spelling is read exactly, truncated toward zero (upstream's
        // decimal path): 12345678901234567.0 stays …567, so the row survives and both ends then
        // round to the same double second, as upstream's `Date` does.
        assertEquals("(12345678901234568,12345678901234568,asleepCore,measured)", dec("[[12345678901234567.0,12345678901234568,2]]"))
        assertEquals("", dec("[[9007199254740993.00,9007199254740993,2]]")) // exact …993, not the double …992
        assertEquals("", dec("[[9007199254740993e0,9007199254740993,2]]"))
        assertEquals("(1,9007199254740991,asleepCore,measured)", dec("[[1,9007199254740991.5,2]]"))
        assertEquals("(1,9007199254740992,asleepCore,measured)", dec("[[1,9007199254740992.5,2]]"))
        assertEquals("(1,9007199254740992,asleepCore,measured)", dec("[[1,9007199254740993.5,2]]"))
        assertEquals(
            "(1,2,awake,measured) (9007199254740992,9007199254740994,asleepCore,measured)",
            dec("[[1,2,1.0e0],[9007199254740993.4,9007199254740994,2]]"),
        )
        // Integer rows above 2^53 become the same double seconds upstream's `Date` holds.
        assertEquals("(9007199254740992,9007199254740992,asleepCore,measured)", dec("[[9007199254740992,9007199254740993,2]]"))
        assertEquals("(9007199254740992,9007199254740996,asleepCore,measured)", dec("[[9007199254740993,9007199254740995,2]]"))
    }

    @Test
    fun rowsBeyondTheRangeOfInstantAreDroppedNotThrown() {
        // Upstream keeps these as far-off `Date`s; java.time cannot hold them, so only that row goes.
        assertEquals(one, dec("[[-9223372036854775808,9223372036854775807,2],[1,2,3]]"))
        assertEquals("", dec("[[9223372036854775806,9223372036854775807,2]]"))
        assertEquals("", dec("[[1,9.223372036854775e18,2]]"))
        assertEquals("", dec("[[31556889864403198,31556889864403199,2]]")) // its double is one past Instant.MAX
        // Instant.MIN itself is representable: upstream's zero-length row (both ends round to it) survives.
        assertEquals(
            "(${Instant.MIN.epochSecond},${Instant.MIN.epochSecond},asleepCore,measured)",
            dec("[[-31557014167219201,-31557014167219200,2]]"),
        )
        assertEquals("(-100,-50,asleepCore,measured)", dec("[[-100,-50,2]]"))
    }

    @Test
    fun textEncodingsAreDetectedAsUpstreamDoes() {
        val j = "[[1,2,3]]"
        val accepted = listOf(
            "utf8 bom" to bytes(0xEF, 0xBB, 0xBF) + j.toByteArray(),
            "utf16le bom" to bytes(0xFF, 0xFE) + utf16le(j), "utf16be bom" to bytes(0xFE, 0xFF) + utf16be(j),
            "utf16le" to utf16le(j), "utf16be" to utf16be(j), "utf16le leading space" to utf16le(" $j"),
            "utf16be leading space" to utf16be(" $j"), "utf32le" to utf32le(j), "utf32be" to utf32be(j),
            "utf32be bom" to bytes(0, 0, 0xFE, 0xFF) + utf32be(j),
            "utf16le odd byte" to utf16le(j) + bytes(0x5B), "utf16le odd 0xff" to utf16le(j) + bytes(0xFF),
            "utf16be odd byte" to utf16be(j) + bytes(0x20), "utf16le bom odd byte" to bytes(0xFF, 0xFE) + utf16le(j) + bytes(0x20),
            "utf32le 1 extra" to utf32le(j) + bytes(0x20), "utf32le 3 extra" to utf32le(j) + bytes(0x20, 0, 0),
            "utf32be 2 extra" to utf32be(j) + bytes(0, 0), "utf16le crlf" to utf16le("\r\n$j\r\n"),
            "utf16le trailing commas" to utf16le("[[1,2,3,],]"), "utf16le numbers" to utf16le("[[1.0,2e0,3]]"),
            "utf16le two spaces" to utf16le("$j  "),
        )
        for ((name, b) in accepted) assertEquals(one, decBytes(b), name)
        val refused = listOf(
            "utf32le bom" to bytes(0xFF, 0xFE, 0, 0) + utf32le(j),
            "utf16le bom then feff" to bytes(0xFF, 0xFE) + utf16le("﻿$j"), "utf16le trailing feff" to utf16le("$j﻿"),
            "utf16le trailing nul" to utf16le(j) + bytes(0, 0), "utf16le surrogate pair" to utf16le("$j😀"),
            "utf16le lone surrogate" to utf16le(j) + bytes(0x00, 0xD8), "utf16le lone surrogate inside" to utf16le("[[1,2,3]") + bytes(0x00, 0xD8) + utf16le("]"),
            "utf16le no-break space" to utf16le("$j "), "utf16le fullwidth" to utf16le("[[1,2,３]]"),
            "utf32le bad code point" to utf32le("[[1,2,3]") + bytes(0, 0, 0x11, 0) + utf32le("]"),
            "utf32be surrogate code point" to utf32be("[[1,2,3]") + bytes(0, 0, 0xD8, 0) + utf32be("]"),
            "nul first" to bytes(0) + j.toByteArray(), "nul second" to bytes(0x5B, 0) + "[1,2,3]]".toByteArray(),
            "utf8 bom only" to bytes(0xEF, 0xBB, 0xBF), "utf16 bom only" to bytes(0xFF, 0xFE),
            "utf8 bom then utf16" to bytes(0xEF, 0xBB, 0xBF) + utf16le(j), "utf16 bom then utf8" to bytes(0xFF, 0xFE) + j.toByteArray(),
            "utf16le empty array" to utf16le("[]"), "utf16be short" to bytes(0, 0x5B, 0, 0x5D),
        )
        for ((name, b) in refused) assertEquals("", decBytes(b), name)
    }

    @Test
    fun rowRulesAsUpstream() {
        // A trailing comma is accepted by upstream's reader, at either depth.
        assertEquals("$one (4,5,asleepCore,measured)", dec("[[1,2,3,],[4,5,2]]"))
        assertEquals(one, dec("[[1,2,3] , ]"))
        assertEquals(one, dec("[[1,2,3],\n]"))
        assertEquals(one, dec("[[1,2,3,\n]]"))
        assertEquals(one, dec("[[1 ,2 ,3 ] ]"))
        assertEquals(deep, dec("[[1700000000,1700000150,3],[]]")) // an empty row is a wrong-arity row
        for (s in listOf("[]", "[ ]", "[[]]", "[[1,2,3,4,5]]", "[[1,2,-1]]", "[[1,2,5]]")) assertEquals("", dec(s), s)
        // The fourth element: known codes are labels, anything else reads as measured.
        assertEquals(one, dec("[[1,2,3,99]]"))
        assertEquals(one, dec("[[1,2,3,-1]]"))
        assertEquals(one, dec("[[1,2,3,0]]"))
        assertEquals("(1,2,asleepDeep,asserted)", dec("[[1,2,3,1]]"))
        assertEquals("(1,2,asleepDeep,assertedOverMeasured)", dec("[[1,2,3,2]]"))
        assertEquals("(1,2,asleepDeep,assertedCoverageUnknown)", dec("[[1,2,3,3]]"))
        assertEquals("(1,2,asleepDeep,asserted)", dec("[[1,2,3,1.0]]"))
        assertEquals("", dec("[[1,2,3,1.5]]"))
        // Rows are kept as stored: duplicates and order are not decode's business.
        assertEquals("$deep $deep", dec("[[1700000000,1700000150,3],[1700000000,1700000150,3]]"))
        assertEquals("(1700000150,1700000300,asleepCore,measured) $deep", dec("[[1700000150,1700000300,2],[1700000000,1700000150,3]]"))
    }

    @Test
    @Timeout(20)
    fun aLongStoredNightDecodesWhole() {
        val many = (0 until 20_000).joinToString(",", "[", "]") { "[${1_700_000_000L + it * 150L},${1_700_000_150L + it * 150L},2]" }
        assertEquals(20_000, SleepHypnogramCodec.decode(many.toByteArray()).size)
    }

    @Test
    fun encodeRoundsHalfAwayFromZero() {
        fun enc(vararg segs: SleepSegment) = String(SleepHypnogramCodec.encode(segs.toList()), Charsets.UTF_8)
        fun s(sec: Long, nano: Long = 0) = Instant.ofEpochSecond(sec, nano)
        assertEquals("[[1700000001,1700000150,2]]", enc(SleepSegment(s(1_700_000_000, 500_000_000), s(1_700_000_150, 490_000_000), SleepStage.ASLEEP_CORE)))
        assertEquals("[[-1,2,2]]", enc(SleepSegment(s(-1, 500_000_000), s(1, 500_000_000), SleepStage.ASLEEP_CORE))) // -0.5 .. 1.5
        assertEquals("[[-2,-1,2]]", enc(SleepSegment(s(-2, 500_000_000), s(-1, 500_000_000), SleepStage.ASLEEP_CORE))) // -1.5 .. -0.5
        assertEquals("[[100,101,2]]", enc(SleepSegment(s(100, 400_000_000), s(100, 600_000_000), SleepStage.ASLEEP_CORE)))
        assertEquals("[]", enc(SleepSegment(s(100, 200_000_000), s(100, 400_000_000), SleepStage.ASLEEP_CORE))) // both round to 100
        assertEquals(
            "[[100,250,2,1],[250,400,0,2],[400,550,1,3],[550,700,4]]",
            enc(
                SleepSegment(s(100), s(250), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED),
                SleepSegment(s(250), s(400), SleepStage.IN_BED, SleepProvenance.ASSERTED_OVER_MEASURED),
                SleepSegment(s(400), s(550), SleepStage.AWAKE, SleepProvenance.ASSERTED_COVERAGE_UNKNOWN),
                SleepSegment(s(550), s(700), SleepStage.ASLEEP_REM, SleepProvenance.MEASURED),
            ),
        )
        // The ends of time encode without overflow, to the integers upstream writes.
        assertEquals("[[-31557014167219200,31556889864403200,3]]", enc(SleepSegment(Instant.MIN, Instant.MAX, SleepStage.ASLEEP_DEEP)))
        // A segment of whole seconds round-trips.
        val seg = SleepSegment(s(1_700_000_000), s(1_700_000_000).plus(Duration.ofMinutes(5)), SleepStage.ASLEEP_REM)
        assertEquals(listOf(seg), SleepHypnogramCodec.decode(SleepHypnogramCodec.encode(listOf(seg))))
    }
}
