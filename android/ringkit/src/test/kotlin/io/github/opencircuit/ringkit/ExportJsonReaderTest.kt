package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.ExportJson.Companion.arr
import io.github.opencircuit.ringkit.ExportJson.Companion.obj
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The export tests' JSON reader, on texts Foundation printed (pinned toolchain) and on the port's own
 * writer output. Upstream's export tests read the file back with `JSONSerialization.jsonObject` and
 * tell numbers from booleans with `isNumber` (an `NSNumber` that is not a `CFBoolean`); the reader
 * must keep that distinction and each number's literal.
 */
class ExportJsonReaderTest {

    @Test
    fun `reads Foundation's layout, keeping empty containers, null and booleans apart from numbers`() {
        // Measured text for ["b": [1, 2], "a": [:], "c": [], "d": NSNull(), "e": true, "f": ["x": 1]].
        val text = "{\n  \"a\" : {\n\n  },\n  \"b\" : [\n    1,\n    2\n  ],\n  \"c\" : [\n\n  ],\n  \"d\" : null,\n" +
            "  \"e\" : true,\n  \"f\" : {\n    \"x\" : 1\n  }\n}"
        val root = ExportJsonReader.root(text)
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), root.keys.toList())
        assertEquals(emptySet(), assertNotNull(root.obj("a")).keys)
        assertEquals(listOf(1L, 2L), assertNotNull(root.array("b")).map { it.asLong() })
        assertEquals(emptyList(), root.array("c"))
        assertIs<ReplayJson.Null>(root["d"])
        assertFalse(ExportJsonReader.isNumber(root["e"]), "a boolean is not a number")
        assertTrue(ExportJsonReader.isNumber(assertNotNull(root.array("b"))[0]))
        assertFalse(ExportJsonReader.isNumber(root["d"]))
        assertFalse(ExportJsonReader.isNumber(root["missing"]))
        assertEquals(1L, root.obj("f")?.long("x"))
    }

    @Test
    fun `keeps each number's literal and reads it back to the same double`() {
        // Measured JSONSerialization texts of 34.2, 1e21, 1e-7, -0.0, 5e-324, Int.max.
        val text = "{\n  \"a\" : 34.200000000000003,\n  \"b\" : 1e+21,\n  \"c\" : 9.9999999999999995e-08,\n  \"d\" : -0,\n" +
            "  \"e\" : 4.9406564584124654e-324,\n  \"f\" : 9223372036854775807\n}"
        val root = ExportJsonReader.root(text)
        assertEquals("34.200000000000003", root["a"].toString())
        assertEquals(34.2, root.double("a"))
        assertEquals(1e21, root.double("b"))
        assertEquals(1e-7, root.double("c"))
        assertEquals(5e-324, root.double("e"))
        assertEquals(Long.MAX_VALUE, root.long("f"))
        assertEquals("-0", root["d"].toString(), "negative zero keeps its sign in the literal")
    }

    @Test
    fun `reads Foundation's escapes back to the original string`() {
        // Measured text of "a/b \"q\" \\ \u{1} \u{1f} \t \n \r é 😀 \u{7f} \u{2028}".
        val text = "{\n  \"s\" : \"a\\/b \\\"q\\\" \\\\ \\u0001 \\u001f \\t \\n \\r é 😀 \u007f  \"\n}"
        assertEquals("a/b \"q\" \\ \u0001 \u001f \t \n \r é 😀 \u007f  ", ExportJsonReader.root(text).string("s"))
    }

    @Test
    fun `refuses what Foundation refuses`() {
        for (bad in listOf("{\"v\" : NaN}", "{\"v\" : 01}", "{\"v\" : 1.}", "{\"v\" : .5}", "{\"v\" : \"a\u0001\"}", "{\"v\" : 1", "[1] x", "")) {
            assertFailsWith<ReplayJson.SyntaxError>(bad) { ExportJsonReader.parse(bad) }
        }
        assertFailsWith<IllegalArgumentException> { ExportJsonReader.root("[1]") }
    }

    @Test
    fun `reads the writer's own output back to the tree it wrote`() {
        val tree = obj(
            "n" to ExportJson.JDouble(0.1), "i" to ExportJson.JInt(-7), "t" to ExportJson.JBool(false), "z" to ExportJson.JNull,
            "s" to ExportJson.JString("x,\"y\"\n/é"), "e" to obj(), "l" to arr(listOf(obj("k" to ExportJson.JDouble(-0.0)), arr(emptyList()))),
        )
        val root = ExportJsonReader.root(assertNotNull(ExportJson.pretty(tree)))
        assertEquals(listOf("e", "i", "l", "n", "s", "t", "z"), root.keys.toList())
        assertEquals(0.1, root.double("n"))
        assertEquals("0.10000000000000001", root["n"].toString())
        assertEquals(-7L, root.long("i"))
        assertEquals(false, (root["t"] as ReplayJson.Bool).value)
        assertIs<ReplayJson.Null>(root["z"])
        assertEquals("x,\"y\"\n/é", root.string("s"))
        val l = assertNotNull(root.array("l"))
        assertEquals("-0", l[0].asObject()?.get("k").toString())
        assertEquals(emptyList(), l[1].asArray())
    }
}
