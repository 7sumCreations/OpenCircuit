package io.github.opencircuit.ringkit

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * The replay harness's manifest reader: a dependency-free JSON reader with the reading rules of
 * Foundation's `JSONSerialization` as upstream's harness uses it, and its values with the casting
 * rules of Foundation's `as? Int` / `as? Double` / `as? Bool` / `as? String`. Test-only; `:ringkit`
 * itself has no JSON.
 *
 * Measured on this machine against Foundation and reproduced:
 *  - RFC 8259 grammar; a byte-order mark and one trailing comma before a closing bracket are
 *    accepted; a duplicated key keeps its FIRST value; the top level must be an object or array.
 *  - Casts: `as? Int` takes integers in 64 bits, integral doubles (`5.0`, `5e0`; `1e-400` is 0) and
 *    booleans (true is 1); `as? Double` takes every number and boolean but refuses an integer it
 *    cannot hold exactly (Int64.max, or anything past 64 bits); `as? Bool` takes booleans and the
 *    numbers 0 and 1; strings never convert.
 *
 * Refused here where Foundation reads leniently (a loud failure, never a different answer): text
 * that is not UTF-8 (Foundation also reads UTF-16/32), a number no Double holds (Foundation reads
 * `-1E400` as -infinity), and nesting deeper than [MAX_DEPTH] (Foundation's own limit, measured, is
 * 513 levels for arrays) — the reader is recursive, and the bound is what keeps a hostile manifest
 * from overflowing the stack.
 */
object ReplayJson {

    /** Nesting deeper than this is refused. */
    const val MAX_DEPTH = 512

    class SyntaxError(message: String) : Exception(message)

    sealed interface Value {
        /** Foundation `as? Int` (64-bit). */
        fun asLong(): Long? = null

        /** Foundation `as? Double`. */
        fun asDouble(): Double? = null

        /** Foundation `as? Bool`. */
        fun asBool(): Boolean? = null

        /** Foundation `as? String`. */
        fun asString(): String? = null

        /** Foundation `as? [String: Any]`. */
        fun asObject(): Obj? = null

        /** Foundation `as? [Any]`. */
        fun asArray(): List<Value>? = null

        /** Foundation `as? [String]`: null unless EVERY element is a string. */
        fun asStringList(): List<String>? = asArray()?.map { it.asString() ?: return null }

        /** Foundation `as? [[String: Any]]`: null unless EVERY element is an object. */
        fun asObjectList(): List<Obj>? = asArray()?.map { it.asObject() ?: return null }
    }

    data object Null : Value

    data class Bool(val value: Boolean) : Value {
        override fun asLong(): Long = if (value) 1 else 0
        override fun asDouble(): Double = if (value) 1.0 else 0.0
        override fun asBool(): Boolean = value
    }

    /** A number, kept as its literal so integers wider than a Double stay exact. */
    class Number(val text: String) : Value {
        private val integerLiteral = text.none { it == '.' || it == 'e' || it == 'E' }

        override fun asLong(): Long? = if (integerLiteral) text.toLongOrNull() else integral(text.toDouble())

        override fun asDouble(): Double? {
            if (!integerLiteral) return text.toDouble()
            val l = text.toLongOrNull() ?: return null
            val d = l.toDouble()
            return if (BigDecimal(d).compareTo(BigDecimal.valueOf(l)) == 0) d else null
        }

        override fun asBool(): Boolean? {
            val d = asDouble() ?: return null
            return if (d == 0.0) false else if (d == 1.0) true else null
        }

        override fun toString(): String = text

        private fun integral(d: Double): Long? =
            if (d.isFinite() && d == Math.floor(d) && d >= -9.223372036854775808E18 && d < 9.223372036854775808E18) d.toLong() else null
    }

    data class Str(val value: String) : Value {
        override fun asString(): String = value
    }

    class Arr(val items: List<Value>) : Value {
        override fun asArray(): List<Value> = items
    }

    class Obj(private val members: Map<String, Value>) : Value {
        override fun asObject(): Obj = this

        val keys: Set<String> get() = members.keys

        operator fun get(key: String): Value? = members[key]

        fun has(key: String): Boolean = key in members

        fun string(key: String): String? = members[key]?.asString()

        fun long(key: String): Long? = members[key]?.asLong()

        fun double(key: String): Double? = members[key]?.asDouble()

        fun bool(key: String): Boolean? = members[key]?.asBool()

        fun obj(key: String): Obj? = members[key]?.asObject()

        fun array(key: String): List<Value>? = members[key]?.asArray()
    }

    /** Parse [bytes] (UTF-8). Throws [SyntaxError] on anything Foundation would refuse, or that is refused above. */
    fun parse(bytes: ByteArray): Value {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            throw SyntaxError("not UTF-8 text: ${e.message}")
        }
        return Reader(text).document()
    }

    private class Reader(private val s: String) {
        private var i = if (s.startsWith('﻿')) 1 else 0

        fun document(): Value {
            ws()
            val c = peek()
            if (c != '{' && c != '[') fail("the top level must be an object or an array")
            val v = value(1)
            ws()
            if (i != s.length) fail("unexpected text after the document")
            return v
        }

        private fun fail(why: String): Nothing = throw SyntaxError("$why at offset $i")

        private fun peek(): Char? = s.getOrNull(i)

        private fun isDigit(c: Char?): Boolean = c != null && c in '0'..'9'

        private fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        private fun expect(c: Char) {
            if (peek() != c) fail("expected '$c'")
            i++
        }

        private fun value(depth: Int): Value {
            if (depth > MAX_DEPTH) fail("nested deeper than $MAX_DEPTH levels")
            ws()
            return when (peek()) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> Str(string())
                't' -> literal("true", Bool(true))
                'f' -> literal("false", Bool(false))
                'n' -> literal("null", Null)
                null -> fail("unexpected end of text")
                else -> number()
            }
        }

        private fun literal(word: String, v: Value): Value {
            if (!s.startsWith(word, i)) fail("invalid literal")
            i += word.length
            return v
        }

        private fun obj(depth: Int): Value {
            expect('{')
            val members = LinkedHashMap<String, Value>()
            ws()
            if (peek() == '}') {
                i++
                return Obj(members)
            }
            while (true) {
                ws()
                if (peek() != '"') fail("expected a member name")
                val key = string()
                ws()
                expect(':')
                val v = value(depth + 1)
                if (key !in members) members[key] = v // a duplicated key keeps its FIRST value
                ws()
                when (peek()) {
                    ',' -> {
                        i++
                        ws()
                        if (peek() == '}') { // one trailing comma, as Foundation accepts
                            i++
                            return Obj(members)
                        }
                    }
                    '}' -> {
                        i++
                        return Obj(members)
                    }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun arr(depth: Int): Value {
            expect('[')
            val items = ArrayList<Value>()
            ws()
            if (peek() == ']') {
                i++
                return Arr(items)
            }
            while (true) {
                items += value(depth + 1)
                ws()
                when (peek()) {
                    ',' -> {
                        i++
                        ws()
                        if (peek() == ']') {
                            i++
                            return Arr(items)
                        }
                    }
                    ']' -> {
                        i++
                        return Arr(items)
                    }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = peek() ?: fail("unterminated string")
                i++
                when {
                    c == '"' -> return sb.toString()
                    c < ' ' -> fail("raw control character in a string")
                    c == '\\' -> {
                        when (val e = peek() ?: fail("unterminated escape")) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                i++
                                val unit = hex4()
                                if (Character.isHighSurrogate(unit)) {
                                    if (!s.startsWith("\\u", i)) fail("lone surrogate escape")
                                    i += 2
                                    val low = hex4()
                                    if (!Character.isLowSurrogate(low)) fail("lone surrogate escape")
                                    sb.append(unit).append(low)
                                } else if (Character.isLowSurrogate(unit)) {
                                    fail("lone surrogate escape")
                                } else {
                                    sb.append(unit)
                                }
                                continue
                            }
                            else -> fail("invalid escape")
                        }
                        i++
                    }
                    else -> sb.append(c)
                }
            }
        }

        /** Four ASCII hex digits (never a locale's other digits). */
        private fun hex4(): Char {
            if (i + 4 > s.length) fail("short \\u escape")
            var v = 0
            for (k in 0 until 4) {
                val ch = s[i + k]
                val d = when (ch) {
                    in '0'..'9' -> ch - '0'
                    in 'a'..'f' -> ch - 'a' + 10
                    in 'A'..'F' -> ch - 'A' + 10
                    else -> fail("invalid \\u escape")
                }
                v = v * 16 + d
            }
            i += 4
            return v.toChar()
        }

        private fun number(): Value {
            val start = i
            if (peek() == '-') i++
            when {
                peek() == '0' -> i++
                isDigit(peek()) -> while (isDigit(peek())) i++
                else -> fail("invalid value")
            }
            if (peek() == '.') {
                i++
                if (!isDigit(peek())) fail("a decimal point needs digits")
                while (isDigit(peek())) i++
            }
            if (peek() == 'e' || peek() == 'E') {
                i++
                if (peek() == '+' || peek() == '-') i++
                if (!isDigit(peek())) fail("an exponent needs digits")
                while (isDigit(peek())) i++
            }
            val text = s.substring(start, i)
            if (text.any { it == '.' || it == 'e' || it == 'E' } && !text.toDouble().isFinite()) fail("a number no Double holds")
            return Number(text)
        }
    }
}
