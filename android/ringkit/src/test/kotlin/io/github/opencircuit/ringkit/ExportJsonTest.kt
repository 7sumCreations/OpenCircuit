package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.ExportJson.Companion.arr
import io.github.opencircuit.ringkit.ExportJson.Companion.obj
import io.github.opencircuit.ringkit.ExportJson.JBool
import io.github.opencircuit.ringkit.ExportJson.JDouble
import io.github.opencircuit.ringkit.ExportJson.JInt
import io.github.opencircuit.ringkit.ExportJson.JNull
import io.github.opencircuit.ringkit.ExportJson.JString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The export's JSON writer against `JSONSerialization.data(withJSONObject:options: [.prettyPrinted,
 * .sortedKeys])`. Every expected text was printed by Foundation on the pinned toolchain (Swift 6.3.2,
 * macOS 26) for the same tree; none comes from this code.
 */
class ExportJsonTest {

    @Test
    fun `objects, arrays and scalars lay out as Foundation's pretty printer`() {
        // Measured for ["b": [1, 2], "a": [:], "c": [], "d": NSNull(), "e": true, "f": ["x": 1]].
        val tree = obj(
            "b" to arr(listOf(JInt(1), JInt(2))), "a" to obj(), "c" to arr(emptyList()), "d" to JNull,
            "e" to JBool(true), "f" to obj("x" to JInt(1)),
        )
        val measured = "{\n  \"a\" : {\n\n  },\n  \"b\" : [\n    1,\n    2\n  ],\n  \"c\" : [\n\n  ],\n  \"d\" : null,\n" +
            "  \"e\" : true,\n  \"f\" : {\n    \"x\" : 1\n  }\n}"
        assertEquals(measured, ExportJson.pretty(tree))
    }

    @Test
    fun `empty containers print an empty line at every depth`() {
        // Measured for ["a": ["b": [:], "c": [], "d": [[], [:], [[:]]]]], and for a bare [:] and [].
        val tree = obj("a" to obj("b" to obj(), "c" to arr(emptyList()), "d" to arr(listOf(arr(emptyList()), obj(), arr(listOf(obj()))))))
        val measured = "{\n  \"a\" : {\n    \"b\" : {\n\n    },\n    \"c\" : [\n\n    ],\n    \"d\" : [\n      [\n\n      ],\n" +
            "      {\n\n      },\n      [\n        {\n\n        }\n      ]\n    ]\n  }\n}"
        assertEquals(measured, ExportJson.pretty(tree))
        assertEquals("{\n\n}", ExportJson.pretty(obj()))
        assertEquals("[\n\n]", ExportJson.pretty(arr(emptyList())))
    }

    @Test
    fun `objects inside arrays indent by two per level`() {
        // Measured for ["x": [["k": 1], ["k": 2]], "m": [0, 1, 2]].
        val tree = obj("x" to arr(listOf(obj("k" to JInt(1)), obj("k" to JInt(2)))), "m" to arr(listOf(JInt(0), JInt(1), JInt(2))))
        val measured = "{\n  \"m\" : [\n    0,\n    1,\n    2\n  ],\n  \"x\" : [\n    {\n      \"k\" : 1\n    },\n    {\n      \"k\" : 2\n    }\n  ]\n}"
        assertEquals(measured, ExportJson.pretty(tree))
    }

    @Test
    fun `integers, booleans and doubles print as Foundation prints them`() {
        // Measured: Int.max, Int.min, 0, true, false; and 5 (Int) vs 5.0 (Double) vs 1.5.
        val ints = obj("i" to JInt(Long.MAX_VALUE), "n" to JInt(Long.MIN_VALUE), "z" to JInt(0), "t" to JBool(true), "f" to JBool(false))
        assertEquals(
            "{\n  \"f\" : false,\n  \"i\" : 9223372036854775807,\n  \"n\" : -9223372036854775808,\n  \"t\" : true,\n  \"z\" : 0\n}",
            ExportJson.pretty(ints),
        )
        val numbers = obj("i" to JInt(5), "b" to JBool(true), "d" to JDouble(5.0), "n" to JDouble(1.5), "s" to JDouble(34.2), "m" to JDouble(-0.0))
        assertEquals(
            "{\n  \"b\" : true,\n  \"d\" : 5,\n  \"i\" : 5,\n  \"m\" : -0,\n  \"n\" : 1.5,\n  \"s\" : 34.200000000000003\n}",
            ExportJson.pretty(numbers),
        )
    }

    @Test
    fun `keys sort in Foundation's order and strings escape as Foundation escapes`() {
        // Measured key order for {B, a, é, Z, aa, _, 10, 9, a.b, aB, ab}, values the string "a/b".
        val tree = obj(*listOf("B", "a", "Z", "aa", "_", "10", "9", "a.b", "aB", "ab").map { it to JString("a/b") }.toTypedArray())
        val keys = listOf("_", "9", "10", "a", "a.b", "aa", "ab", "aB", "B", "Z")
        assertEquals("{\n" + keys.joinToString(",\n") { "  \"$it\" : \"a\\/b\"" } + "\n}", ExportJson.pretty(tree))
    }

    @Test
    fun `a non-finite double anywhere in the tree makes the writer return null`() {
        // Foundation raises NSInvalidArgumentException for NaN or an infinity and the process dies
        // (measured); the port's writer returns null, the documented contract of upstream's toJSON.
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull(ExportJson.pretty(obj("v" to JDouble(bad))), "top level $bad")
            val deep = obj("a" to arr(listOf(obj("ok" to JDouble(1.0)), obj("x" to arr(listOf(JInt(1), JDouble(bad)))))), "z" to JString("after"))
            assertNull(ExportJson.pretty(deep), "nested $bad")
        }
        // The same tree with a finite value still writes.
        assertEquals("{\n  \"v\" : 1\n}", ExportJson.pretty(obj("v" to JDouble(1.0))))
    }

    @Test
    fun `a member key may be given only once`() {
        assertFailsWith<IllegalArgumentException> { obj("k" to JInt(1), "k" to JInt(2)) }
    }

    @Test
    fun `a tree is a snapshot of the lists it was built from`() {
        val members = linkedMapOf<String, ExportJson>("a" to JInt(1))
        val items = mutableListOf<ExportJson>(JInt(1))
        val o = ExportJson.JObject(members)
        val a = ExportJson.JArray(items)
        members["b"] = JInt(2)
        items += JInt(2)
        assertEquals("{\n  \"a\" : 1\n}", ExportJson.pretty(o))
        assertEquals("[\n  1\n]", ExportJson.pretty(a))
    }
}
