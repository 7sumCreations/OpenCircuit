package io.github.opencircuit.store.codec

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import java.math.BigDecimal
import java.time.DateTimeException
import java.time.Instant

/**
 * A stored value read back: either the value, or the stored text this build cannot read.
 *
 * Reading never throws. What an unreadable value costs is decided per stored type by its caller
 * (an empty ledger, no snapshot, an alarm kept as it was stored); [Unreadable.raw] keeps the text
 * byte for byte so nothing is lost by reading it, and [Unreadable.reason] says what failed.
 */
sealed interface Decoded<out T> {
    data class Readable<out T>(val value: T) : Decoded<T>

    data class Unreadable(val raw: String, val reason: String) : Decoded<Nothing>

    /** The value, or null when unreadable. */
    fun valueOrNull(): T? = (this as? Readable<T>)?.value
}

/**
 * The rules every stored form is read by. They are Foundation's `JSONDecoder` defaults, which
 * upstream reads its stored forms with, as measured on Swift 6.3.2:
 * - a missing required key, or `null` for one, makes the whole value unreadable; for an optional
 *   key, missing and `null` both mean absent;
 * - an integer comes only from a number literal with no fractional part (`1.0` and `1e3` are
 *   integers, `1.5` is not); a quoted number or a boolean is not an integer; one past 64 bits is
 *   unreadable, and so is one past 32 bits for a field the Kotlin type holds as `Int` (Swift's
 *   `Int` is 64-bit; nothing this build writes is that large, so only a damaged value gets there);
 * - a double is finite or unreadable (`1e400` is not read as infinity);
 * - an enum comes only from a known raw string, compared with case;
 * - unknown keys are ignored; a duplicate key keeps its FIRST value;
 * - one unreadable element makes the whole array unreadable, unless a stored type says otherwise.
 *
 * The text is parsed here, strictly (RFC 8259), and not by the serialization library's own tree
 * parser, which keeps the LAST duplicate and accepts unquoted tokens such as `NaN` (measured).
 */
internal fun <T> readStored(raw: String, read: (JsonElement) -> T): Decoded<T> =
    try {
        Decoded.Readable(read(StrictJson.parse(raw)))
    } catch (e: NotReadable) {
        Decoded.Unreadable(raw, e.message ?: "unreadable")
    } catch (e: IllegalArgumentException) {
        // A value a ported type's own range check refuses: unreadable, never a crash.
        Decoded.Unreadable(raw, e.message ?: "refused by the type")
    } catch (e: ArithmeticException) {
        Decoded.Unreadable(raw, e.message ?: "out of range")
    } catch (e: DateTimeException) {
        Decoded.Unreadable(raw, e.message ?: "date out of range")
    }

/** Thrown inside [readStored] only, and always turned into [Decoded.Unreadable] there. */
internal class NotReadable(message: String) : RuntimeException(message)

/** Refuses the stored value being read; only valid inside a [readStored] block. */
internal fun unreadable(message: String): Nothing = throw NotReadable(message)

internal fun JsonElement.obj(): JsonObject = this as? JsonObject ?: unreadable("expected an object")

internal fun JsonElement.array(): JsonArray = this as? JsonArray ?: unreadable("expected an array")

/** The value of a required key; missing or `null` is unreadable. */
internal fun JsonObject.required(key: String): JsonElement {
    val v = this[key] ?: unreadable("missing key \"$key\"")
    if (v is JsonNull) unreadable("null for required key \"$key\"")
    return v
}

/** The value of an optional key, or null when it is missing or `null`. */
internal fun JsonObject.optional(key: String): JsonElement? = this[key]?.takeUnless { it is JsonNull }

/** The literal text of a JSON number, or unreadable for anything else. */
private fun JsonElement.numberText(): String {
    val p = this as? JsonPrimitive ?: unreadable("expected a number")
    if (p is JsonNull || p.isString || p.content == "true" || p.content == "false") unreadable("expected a number")
    return p.content
}

/** The most integer digits a 64-bit value can have; bounds the work on a huge exponent. */
private const val LONG_DIGITS = 19

internal fun JsonElement.long(): Long {
    // An exponent past 32 bits is refused by the parse itself; it is still just a number this
    // reader cannot take, so a lenient caller can fall back to its default for it.
    val n = try {
        BigDecimal(numberText())
    } catch (e: NumberFormatException) {
        unreadable("integer past 64 bits")
    }
    if (n.signum() == 0) return 0L
    // In Long: precision minus a scale near Int.MIN_VALUE overflows an Int.
    if (n.precision().toLong() - n.scale() > LONG_DIGITS) unreadable("integer past 64 bits")
    val whole = n.stripTrailingZeros()
    if (whole.scale() > 0) unreadable("not an integer")
    return try {
        whole.toBigIntegerExact().longValueExact()
    } catch (e: ArithmeticException) {
        unreadable("integer past 64 bits")
    }
}

internal fun JsonElement.int(): Int {
    val v = long()
    if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) unreadable("integer past 32 bits: $v")
    return v.toInt()
}

internal fun JsonElement.uInt8(): Int {
    val v = long()
    if (v !in 0L..0xFFL) unreadable("not a byte 0-255: $v")
    return v.toInt()
}

internal fun JsonElement.uInt32(): Long {
    val v = long()
    if (v !in 0L..0xFFFF_FFFFL) unreadable("not an unsigned 32-bit value: $v")
    return v
}

internal fun JsonElement.double(): Double {
    val v = numberText().toDouble()
    if (!v.isFinite()) unreadable("not a finite number")
    return v
}

internal fun JsonElement.bool(): Boolean {
    val p = this as? JsonPrimitive
    if (p == null || p is JsonNull || p.isString) unreadable("expected a boolean")
    return when (p.content) {
        "true" -> true
        "false" -> false
        else -> unreadable("expected a boolean")
    }
}

internal fun JsonElement.string(): String {
    val p = this as? JsonPrimitive
    if (p == null || !p.isString) unreadable("expected a string")
    return p.content
}

/** A date: whole epoch milliseconds. */
internal fun JsonElement.instant(): Instant = Instant.ofEpochMilli(long())

/**
 * One lowercase hex digit, the only form the store writes: `0`–`9` and `a`–`f` in ASCII. An
 * uppercase digit, a fullwidth digit or anything else is unreadable.
 */
internal fun lowerHexDigit(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    else -> unreadable("not the stored lowercase hex")
}

/** The entry whose raw string is exactly this string. */
internal fun <E> JsonElement.enumOf(entries: List<E>, raw: (E) -> String): E {
    val s = string()
    return entries.firstOrNull { raw(it) == s } ?: unreadable("unknown value \"$s\"")
}

// MARK: - Writing

/** A date as whole epoch milliseconds, cut toward the past (the store's one time rule). */
internal fun Instant.json(): JsonPrimitive = JsonPrimitive(toEpochMilli())

/** A double for storage, or null when it is NaN or infinite, which JSON cannot hold. */
internal fun Double.finiteJsonOrNull(): JsonPrimitive? = if (isFinite()) JsonPrimitive(this) else null

/** An object from its keys in the order given, leaving out each key whose value is null. */
internal fun jsonObjectOf(vararg entries: Pair<String, JsonElement?>): JsonObject {
    val m = LinkedHashMap<String, JsonElement>()
    for ((k, v) in entries) if (v != null) m[k] = v
    return JsonObject(m)
}

// MARK: - Strict parsing

/**
 * RFC 8259 JSON to the library's element tree, with a duplicate key keeping its first value.
 * Numbers stay as their literal text, so each reader above applies its own rule to it. Lone
 * surrogates, control characters in strings, a byte-order mark, trailing text and nesting deeper
 * than [MAX_DEPTH] are unreadable.
 */
internal object StrictJson {
    const val MAX_DEPTH = 512

    fun parse(text: String): JsonElement {
        val p = Parser(text)
        p.skipWhitespace()
        val v = p.value(0)
        p.skipWhitespace()
        if (p.pos != text.length) unreadable("text after the value at ${p.pos}")
        return v
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipWhitespace() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\t' || s[pos] == '\n' || s[pos] == '\r')) pos++
        }

        fun value(depth: Int): JsonElement {
            if (depth > MAX_DEPTH) unreadable("nested deeper than $MAX_DEPTH")
            if (pos >= s.length) unreadable("unexpected end")
            return when (val c = s[pos]) {
                '{' -> obj(depth)
                '[' -> array(depth)
                '"' -> JsonPrimitive(string())
                't' -> word("true", JsonPrimitive(true))
                'f' -> word("false", JsonPrimitive(false))
                'n' -> word("null", JsonNull)
                else -> if (c == '-' || c in '0'..'9') number() else unreadable("unexpected '$c' at $pos")
            }
        }

        private fun word(w: String, v: JsonElement): JsonElement {
            if (!s.startsWith(w, pos)) unreadable("bad literal at $pos")
            pos += w.length
            return v
        }

        private fun expect(c: Char) {
            if (pos >= s.length || s[pos] != c) unreadable("expected '$c' at $pos")
            pos++
        }

        private fun obj(depth: Int): JsonObject {
            pos++
            val m = LinkedHashMap<String, JsonElement>()
            skipWhitespace()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return JsonObject(m)
            }
            while (true) {
                skipWhitespace()
                if (pos >= s.length || s[pos] != '"') unreadable("expected a key at $pos")
                val key = string()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                val v = value(depth + 1)
                if (key !in m) m[key] = v // Foundation keeps the first of a duplicate key.
                skipWhitespace()
                if (pos >= s.length) unreadable("unexpected end")
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return JsonObject(m)
                    else -> unreadable("expected ',' or '}' at ${pos - 1}")
                }
            }
        }

        private fun array(depth: Int): JsonArray {
            pos++
            val items = ArrayList<JsonElement>()
            skipWhitespace()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return JsonArray(items)
            }
            while (true) {
                skipWhitespace()
                items += value(depth + 1)
                skipWhitespace()
                if (pos >= s.length) unreadable("unexpected end")
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return JsonArray(items)
                    else -> unreadable("expected ',' or ']' at ${pos - 1}")
                }
            }
        }

        private fun string(): String {
            pos++
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) unreadable("unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> break
                    c == '\\' -> {
                        if (pos >= s.length) unreadable("unterminated escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> sb.append(hex4())
                            else -> unreadable("bad escape '\\$e'")
                        }
                    }
                    c < ' ' -> unreadable("control character in a string")
                    else -> sb.append(c)
                }
            }
            requireWellFormed(sb)
            return sb.toString()
        }

        private fun hex4(): Char {
            if (pos + 4 > s.length) unreadable("short \\u escape")
            var v = 0
            repeat(4) {
                val d = when (val h = s[pos++]) {
                    in '0'..'9' -> h - '0'
                    in 'a'..'f' -> h - 'a' + 10
                    in 'A'..'F' -> h - 'A' + 10
                    else -> unreadable("bad \\u escape")
                }
                v = v * 16 + d
            }
            return v.toChar()
        }

        private fun requireWellFormed(sb: CharSequence) {
            var i = 0
            while (i < sb.length) {
                val c = sb[i]
                if (Character.isHighSurrogate(c)) {
                    if (i + 1 >= sb.length || !Character.isLowSurrogate(sb[i + 1])) unreadable("lone surrogate")
                    i += 2
                } else {
                    if (Character.isLowSurrogate(c)) unreadable("lone surrogate")
                    i++
                }
            }
        }

        @OptIn(ExperimentalSerializationApi::class)
        private fun number(): JsonElement {
            val start = pos
            if (s[pos] == '-') pos++
            when {
                pos < s.length && s[pos] == '0' -> pos++
                pos < s.length && s[pos] in '1'..'9' -> digits()
                else -> unreadable("bad number at $start")
            }
            if (pos < s.length && s[pos] == '.') {
                pos++
                if (pos >= s.length || s[pos] !in '0'..'9') unreadable("bad fraction at $start")
                digits()
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                if (pos >= s.length || s[pos] !in '0'..'9') unreadable("bad exponent at $start")
                digits()
            }
            return JsonUnquotedLiteral(s.substring(start, pos))
        }

        private fun digits() {
            while (pos < s.length && s[pos] in '0'..'9') pos++
        }
    }
}
