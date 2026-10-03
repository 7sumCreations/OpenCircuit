package io.github.opencircuit.store.codec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules every stored form is read by, one test per rule.
 *
 * Kotlin-only. Upstream reads its stored forms with Foundation's `JSONDecoder`; these are the
 * outcomes measured on it (Swift 6.3.2, default decoder, upstream pinned at b1c2fdd), and each
 * test pins the same outcome on the store's reader. Where Foundation fails the whole document,
 * the store's reader returns an unreadable value instead of throwing.
 */
class JsonReadTest {

    private fun <T> read(raw: String, block: (JsonElement) -> T): Decoded<T> = readStored(raw, block)

    private fun intField(raw: String): Decoded<Int> = read(raw) { it.obj().required("n").int() }

    private fun longField(raw: String): Decoded<Long> = read(raw) { it.obj().required("n").long() }

    private fun doubleField(raw: String): Decoded<Double> = read(raw) { it.obj().required("n").double() }

    private fun <T> assertUnreadable(d: Decoded<T>, raw: String) {
        val u = assertIs<Decoded.Unreadable>(d, "expected unreadable: $raw")
        assertEquals(raw, u.raw, "the unreadable value keeps the stored text")
    }

    private fun <T> valueOf(d: Decoded<T>): T = assertIs<Decoded.Readable<T>>(d).value

    @Test
    fun aMissingRequiredKeyMakesTheWholeValueUnreadable() {
        assertUnreadable(intField("{}"), "{}")
        assertUnreadable(intField("""{"m":1}"""), """{"m":1}""")
    }

    @Test
    fun anExplicitNullForARequiredKeyIsUnreadableAndForAnOptionalKeyIsAbsent() {
        assertUnreadable(intField("""{"n":null}"""), """{"n":null}""")
        assertNull(valueOf(read("""{"n":null}""") { it.obj().optional("n")?.int() }))
        assertNull(valueOf(read("{}") { it.obj().optional("n")?.int() }))
    }

    @Test
    fun anIntegerIsReadFromAnIntegerLiteralIncludingAWholeDecimalOrExponentForm() {
        assertEquals(7, valueOf(intField("""{"n":7}""")))
        assertEquals(1, valueOf(intField("""{"n":1.0}""")))
        assertEquals(1000, valueOf(intField("""{"n":1e3}""")))
        assertEquals(-12, valueOf(intField("""{"n":-1.2E1}""")))
        assertEquals(0, valueOf(intField("""{"n":-0}""")))
        assertEquals(5_000_000_000L, valueOf(longField("""{"n":5000000000}""")))
    }

    @Test
    fun aFractionalNumberAQuotedNumberOrABooleanIsNotAnInteger() {
        for (raw in listOf("""{"n":1.5}""", """{"n":"7"}""", """{"n":true}""", """{"n":1e-1}""", """{"n":[1]}""")) {
            assertUnreadable(intField(raw), raw)
        }
    }

    @Test
    fun anIntegerPastSixtyFourBitsIsUnreadable() {
        assertEquals(Long.MAX_VALUE, valueOf(longField("""{"n":9223372036854775807}""")))
        assertEquals(Long.MIN_VALUE, valueOf(longField("""{"n":-9223372036854775808}""")))
        for (raw in listOf(
            """{"n":9223372036854775808}""",
            """{"n":-9223372036854775809}""",
            """{"n":1e19}""",
            """{"n":1e999999999}""",
            """{"n":1e-999999999}""",
        )) {
            assertUnreadable(longField(raw), raw)
        }
    }

    @Test
    fun aValuePastThirtyTwoBitsIsUnreadableForAFieldTheKotlinTypeHoldsAsInt() {
        // Swift's Int is 64-bit and decodes 2147483648; the Kotlin types hold these fields as Int.
        assertEquals(Int.MAX_VALUE, valueOf(intField("""{"n":2147483647}""")))
        assertEquals(Int.MIN_VALUE, valueOf(intField("""{"n":-2147483648}""")))
        assertUnreadable(intField("""{"n":2147483648}"""), """{"n":2147483648}""")
        assertUnreadable(intField("""{"n":-2147483649}"""), """{"n":-2147483649}""")
        assertUnreadable(intField("""{"n":5000000000}"""), """{"n":5000000000}""")
    }

    @Test
    fun anUnsignedByteOrUnsigned32BitFieldOutOfRangeIsUnreadable() {
        val byte = { raw: String -> read(raw) { it.obj().required("n").uInt8() } }
        val u32 = { raw: String -> read(raw) { it.obj().required("n").uInt32() } }
        assertEquals(255, valueOf(byte("""{"n":255}""")))
        assertEquals(0, valueOf(byte("""{"n":0}""")))
        assertUnreadable(byte("""{"n":256}"""), """{"n":256}""")
        assertUnreadable(byte("""{"n":-1}"""), """{"n":-1}""")
        assertEquals(4_294_967_295L, valueOf(u32("""{"n":4294967295}""")))
        assertUnreadable(u32("""{"n":4294967296}"""), """{"n":4294967296}""")
        assertUnreadable(u32("""{"n":-1}"""), """{"n":-1}""")
    }

    @Test
    fun onlyAFiniteDoubleIsRead() {
        assertEquals(1e300, valueOf(doubleField("""{"n":1e300}""")))
        assertEquals(6.0, valueOf(doubleField("""{"n":6}""")))
        assertEquals(-0.0, valueOf(doubleField("""{"n":-0.0}""")))
        assertTrue(valueOf(doubleField("""{"n":-0}""")).let { 1.0 / it } < 0, "the sign of -0 is kept")
        for (raw in listOf("""{"n":1e400}""", """{"n":-1e400}""", """{"n":NaN}""", """{"n":"NaN"}""", """{"n":Infinity}""")) {
            assertUnreadable(doubleField(raw), raw)
        }
    }

    @Test
    fun aBooleanIsReadOnlyFromTrueOrFalse() {
        val bool = { raw: String -> read(raw) { it.obj().required("n").bool() } }
        assertEquals(true, valueOf(bool("""{"n":true}""")))
        assertEquals(false, valueOf(bool("""{"n":false}""")))
        for (raw in listOf("""{"n":1}""", """{"n":"true"}""", """{"n":0}""")) assertUnreadable(bool(raw), raw)
    }

    @Test
    fun aStringIsReadOnlyFromAJsonString() {
        val str = { raw: String -> read(raw) { it.obj().required("n").string() } }
        assertEquals("a\"b\\c/\u00e9\n\ud83d\ude00", valueOf(str("""{"n":"a\"b\\c\/\u00e9\n\ud83d\ude00"}""")))
        for (raw in listOf("""{"n":5}""", """{"n":true}""", """{"n":{}}""")) assertUnreadable(str(raw), raw)
    }

    @Test
    fun anEnumIsReadOnlyFromAKnownRawStringWithTheSameCase() {
        val stage = { raw: String -> read(raw) { it.obj().required("n").enumOf(Raw.entries) { e -> e.raw } } }
        assertEquals(Raw.ASLEEP_CORE, valueOf(stage("""{"n":"asleepCore"}""")))
        for (raw in listOf("""{"n":"AsleepCore"}""", """{"n":"asleepcore"}""", """{"n":"nap"}""", """{"n":3}""", """{"n":""}""")) {
            assertUnreadable(stage(raw), raw)
        }
    }

    @Test
    fun anUnknownKeyIsIgnored() {
        assertEquals(7, valueOf(intField("""{"n":7,"later":{"x":[1,2]},"z":null}""")))
    }

    @Test
    fun aDuplicateKeyKeepsTheFirstValueAsFoundationDoes() {
        // Foundation measured: {"n":1,"n":2} decodes 1. The library's own tree parser keeps the
        // last one (pinned below), which is why the store parses stored text itself.
        assertEquals(1, valueOf(intField("""{"n":1,"n":2}""")))
        assertEquals(1, valueOf(read("""{"o":{"n":1,"n":"x"}}""") { it.obj().required("o").obj().required("n").int() }))
    }

    @Test
    fun theLibraryTreeParserKeepsTheLastDuplicateAndAcceptsUnquotedTokens() {
        // Measurement of kotlinx-serialization-json 1.11.0's parseToJsonElement, the reason the
        // store does not use it to read stored text: it disagrees with Foundation on both counts.
        assertEquals("2", Json.parseToJsonElement("""{"n":1,"n":2}""").jsonObject.getValue("n").jsonPrimitive.content)
        assertEquals("abc", Json.parseToJsonElement("""{"n":abc}""").jsonObject.getValue("n").jsonPrimitive.content)
    }

    @Test
    fun aDateIsWholeEpochMillisecondsFromAnInteger() {
        val date = { raw: String -> read(raw) { it.obj().required("t").instant() } }
        assertEquals(Instant.ofEpochMilli(978_307_200_000L), valueOf(date("""{"t":978307200000}""")))
        assertEquals(Instant.ofEpochMilli(-62_135_596_800_000L), valueOf(date("""{"t":-62135596800000}""")))
        for (raw in listOf("""{"t":"2001-01-01T00:00:00Z"}""", """{"t":1.5}""", """{"t":null}""")) assertUnreadable(date(raw), raw)
    }

    @Test
    fun oneBadElementFailsTheWholeArray() {
        val ints = { raw: String -> read(raw) { it.array().map { e -> e.int() } } }
        assertEquals(listOf(1, 2), valueOf(ints("[1,2]")))
        assertEquals(emptyList(), valueOf(ints("[]")))
        assertUnreadable(ints("""[1,"x"]"""), """[1,"x"]""")
        assertUnreadable(ints("""{"a":1}"""), """{"a":1}""")
    }

    @Test
    fun textThatIsNotStrictJsonIsUnreadable() {
        for (raw in listOf(
            "", " ", "\u0000\u0001\u0002", "{", "{\"n\":1", "{\"n\":1}}", "{\"n\":1} x", "{\"n\":01}",
            "{\"n\":+1}", "{\"n\":.5}", "{\"n\":1.}", "{\"n\":1e}", "{\"n\":-}", "{\"n\":1,}", "[1,]",
            "{'n':1}", "{n:1}", "{\"n\":tru}", "{\"n\":nul}", "{\"n\":\"a\u0001\"}", "{\"n\":\"\\x\"}",
            "{\"n\":\"\\u12g4\"}", "{\"n\":\"\\ud800\"}", "{\"n\":\"\\udc00\\ud800\"}", "\ufeff{\"n\":1}",
            "{\"n\":1}\u0000",
        )) {
            assertUnreadable(intField(raw), raw)
        }
    }

    @Test
    fun deepNestingIsUnreadableInsteadOfOverflowingTheStack() {
        val raw = "[".repeat(100_000) + "]".repeat(100_000)
        assertUnreadable(read(raw) { 0 }, raw)
        val ok = "[".repeat(100) + "]".repeat(100)
        assertEquals(0, valueOf(read(ok) { 0 }))
    }

    @Test
    fun noInputMakesTheReaderThrow() {
        // Mutations of a real stored shape: every one either reads or is unreadable, never throws.
        val seed = """{"label":"sport","channel":2,"startedAt":978307200000,"n":[1,-2.5e3,true,null,"\u00e9"]}"""
        val alphabet = "{}[]\":,.-+eE0123456789tfnul\\ \u0000\ud800x"
        val rnd = Random(20261003)
        repeat(5_000) {
            val chars = seed.toCharArray().toMutableList()
            repeat(1 + rnd.nextInt(4)) {
                when (rnd.nextInt(3)) {
                    0 -> if (chars.isNotEmpty()) chars.removeAt(rnd.nextInt(chars.size))
                    1 -> chars.add(rnd.nextInt(chars.size + 1), alphabet[rnd.nextInt(alphabet.length)])
                    else -> if (chars.isNotEmpty()) chars[rnd.nextInt(chars.size)] = alphabet[rnd.nextInt(alphabet.length)]
                }
            }
            read(String(chars.toCharArray())) { root ->
                val o = root.obj()
                Triple(o.required("label").string(), o.required("channel").uInt8(), o.required("startedAt").instant()) to
                    o.optional("n")?.array()?.toList()
            }
        }
    }

    private enum class Raw(val raw: String) { ASLEEP_CORE("asleepCore"), AWAKE("awake") }
}
