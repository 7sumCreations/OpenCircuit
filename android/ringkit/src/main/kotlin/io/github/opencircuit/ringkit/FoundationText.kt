package io.github.opencircuit.ringkit

// The Foundation text upstream's export is made of, reproduced byte for byte: `JSONSerialization`'s
// number text, string escapes and sorted-key order, `ISO8601DateFormatter` (with fractional seconds)
// and `DateFormatter("yyyy-MM-dd")` dates, and Swift's `String(Double)`. Each rule below was measured
// on the pinned toolchain (Swift 6.3.2, macOS 26) and is pinned by `FoundationTextHazardTest`; the
// export differential compares whole files. `%.Nf` text is `swiftFixed` (SwiftNumerics.kt).
//
// Nothing here reads a default zone, locale or clock: every date takes its zone, digits are ASCII.

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

internal object FoundationText {

    // --- numbers ---

    /**
     * A finite double as `JSONSerialization` prints it: `%.17g` — the exact binary value rounded half
     * to even to 17 significant digits — with trailing zeros (and a bare point) removed; exponent form
     * (`1e+21`, `9.9999999999999995e-08`, two-digit minimum) when the decimal exponent after rounding
     * is below −4 or at least 17; `-0` for negative zero. Foundation cannot print NaN or an infinity at
     * all (it raises and the process dies), so the caller decides what a non-finite value becomes.
     */
    fun jsonNumber(v: Double): String {
        require(v.isFinite()) { "JSON has no text for $v" }
        if (v == 0.0) return if (v.toRawBits() < 0) "-0" else "0"
        val rounded = BigDecimal(v).abs().round(MathContext(17, RoundingMode.HALF_EVEN)).stripTrailingZeros()
        val exponent = rounded.precision() - rounded.scale() - 1
        val sign = if (v < 0) "-" else ""
        return if (exponent < -4 || exponent >= 17) sign + scientific(rounded, exponent) else sign + rounded.toPlainString()
    }

    /**
     * Swift's `String(v)` / `v.description`: the SHORTEST decimal that reads back as [v] (the closest
     * one when two of that length do), in plain form — always with a fractional part (`72.0`) — when
     * the magnitude is at most 2^53 and the decimal exponent is at least −4, else in exponent form
     * (`1e-05`, `9.5e+15`, two-digit minimum). `nan` whatever the sign or payload, `inf`, `-inf`.
     * JDK 17's `Double.toString` is not shortest and spells exponents `1.0E-5`, so it is never used.
     */
    fun swiftDescription(v: Double): String {
        if (v.isNaN()) return "nan"
        if (v.isInfinite()) return if (v > 0) "inf" else "-inf"
        val sign = if (v.toRawBits() < 0) "-" else ""
        if (v == 0.0) return sign + "0.0"
        val digits = shortestRoundTrip(Math.abs(v))
        val exponent = digits.precision() - digits.scale() - 1
        if (Math.abs(v) > TWO_TO_53 || exponent < -4) return sign + scientific(digits, exponent)
        val plain = digits.toPlainString()
        return sign + if (plain.contains('.')) plain else "$plain.0"
    }

    private const val TWO_TO_53 = 9007199254740992.0

    /** The shortest decimal (trailing zeros stripped) that parses back to [x] (> 0, finite); closest on a tie of length. */
    private fun shortestRoundTrip(x: Double): BigDecimal {
        val exact = BigDecimal(x)
        for (p in 1..17) {
            val mc = { mode: RoundingMode -> exact.round(MathContext(p, mode)) }
            val down = mc(RoundingMode.DOWN)
            val up = mc(RoundingMode.UP)
            val downOk = down.signum() != 0 && down.toPlainString().toDouble() == x
            val upOk = up.toPlainString().toDouble() == x
            val pick = when {
                downOk && upOk -> if (exact.subtract(down) <= up.subtract(exact)) down else up
                downOk -> down
                upOk -> up
                else -> null
            }
            if (pick != null) return pick.stripTrailingZeros()
        }
        return exact.round(MathContext(17, RoundingMode.HALF_EVEN)).stripTrailingZeros()
    }

    /** `d[.ddd]e±XX` from a positive, trailing-zero-stripped decimal whose decimal exponent is [exponent]. */
    private fun scientific(d: BigDecimal, exponent: Int): String {
        val all = d.unscaledValue().toString()
        val mantissa = if (all.length == 1) all else all[0] + "." + all.substring(1)
        val e = Math.abs(exponent).toString().padStart(2, '0')
        return mantissa + "e" + (if (exponent < 0) "-" else "+") + e
    }

    // --- dates ---

    /** 2001-01-01 00:00:00 UTC in seconds since 1970 — the reference date a Swift `Date` counts from. */
    private const val REFERENCE_UNIX_SECONDS = 978_307_200L

    /**
     * The double a Swift `Date` holding [t] holds: [t]'s seconds since 2001, correctly rounded. For an
     * instant that came from a `Date` this is that `Date`'s own double again.
     */
    fun referenceSeconds(t: Instant): Double =
        if (t.nano == 0) {
            (t.epochSecond - REFERENCE_UNIX_SECONDS).toDouble() // whole seconds: `Long.toDouble` rounds to nearest, as the general path
        } else {
            BigDecimal.valueOf(t.epochSecond - REFERENCE_UNIX_SECONDS).add(BigDecimal.valueOf(t.nano.toLong(), 9)).toPlainString().toDouble()
        }

    /**
     * The millisecond Foundation's formatters print for [t] — measured: `floor((d + 978307200) * 1000
     * + 0.5)` in double arithmetic on the `Date`'s double `d`. So a value just below a half
     * millisecond can still round up (the product rounds first), a tie rounds up even before 1970, and
     * the carry reaches the second, the minute and the date-only label.
     */
    private fun foundationMillis(t: Instant): Long = Math.floor((referenceSeconds(t) + REFERENCE_UNIX_SECONDS.toDouble()) * 1000.0 + 0.5).toLong()

    private fun local(t: Instant, zone: ZoneId): Pair<LocalDateTime, ZoneOffset> {
        val rounded = Instant.ofEpochMilli(foundationMillis(t))
        val offset = zone.rules.getOffset(rounded)
        return LocalDateTime.ofEpochSecond(rounded.epochSecond, rounded.nano, offset) to offset
    }

    /**
     * `ISO8601DateFormatter` with `.withInternetDateTime, .withFractionalSeconds` in [zone]:
     * `yyyy-MM-ddTHH:mm:ss.SSS` then `Z` for a zero offset, else `±hh:mm`, with `:ss` when the offset
     * has seconds. A year past 9999 prints unsigned (`10000`). Kept differences (measured): a date
     * before 1582-10-15 prints proleptic Gregorian where Foundation switches to the Julian calendar,
     * and a fixed offset with seconds prints them where Foundation drops a fixed zone's seconds.
     */
    fun iso8601(t: Instant, zone: ZoneId): String {
        val (d, offset) = local(t, zone)
        val sb = StringBuilder(29)
        appendDate(sb, d)
        sb.append('T')
        pad(sb, d.hour, 2).append(':')
        pad(sb, d.minute, 2).append(':')
        pad(sb, d.second, 2).append('.')
        pad(sb, d.nano / 1_000_000, 3)
        val total = offset.totalSeconds
        if (total == 0) return sb.append('Z').toString()
        val a = Math.abs(total)
        sb.append(if (total < 0) '-' else '+')
        pad(sb, a / 3600, 2).append(':')
        pad(sb, a / 60 % 60, 2)
        if (a % 60 != 0) pad(sb.append(':'), a % 60, 2)
        return sb.toString()
    }

    /** `DateFormatter` `yyyy-MM-dd` (`en_US_POSIX`) in [zone], from the same rounded millisecond as [iso8601]. */
    fun dateOnly(t: Instant, zone: ZoneId): String = appendDate(StringBuilder(10), local(t, zone).first).toString()

    private fun appendDate(sb: StringBuilder, d: LocalDateTime): StringBuilder {
        if (d.year < 0) sb.append('-')
        pad(sb, Math.abs(d.year), 4).append('-')
        pad(sb, d.monthValue, 2).append('-')
        return pad(sb, d.dayOfMonth, 2)
    }

    /** [n] (≥ 0) in ASCII digits, zero-padded to [width]. */
    private fun pad(sb: StringBuilder, n: Int, width: Int): StringBuilder {
        val s = n.toString()
        for (i in s.length until width) sb.append('0')
        return sb.append(s)
    }

    // --- strings ---

    /**
     * [s] as a JSON string literal, escaped as `JSONSerialization` escapes: `\"`, `\\`, `\/`, the short
     * forms `\b \f \n \r \t`, every other character below U+0020 as lowercase `\u00xx`; DEL and
     * everything from U+0080 on (U+2028, U+2029, the BOM) written as is.
     */
    fun jsonQuoted(s: String): String = appendJsonQuoted(StringBuilder(s.length + 2), s).toString()

    fun appendJsonQuoted(sb: StringBuilder, s: String): StringBuilder {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '/' -> sb.append("\\/")
                '\b' -> sb.append("\\b")
                '\u000c' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u00").append(HEX[c.code shr 4]).append(HEX[c.code and 0xf]) else sb.append(c)
            }
        }
        return sb.append('"')
    }

    private const val HEX = "0123456789abcdef"

    // --- key order ---

    /**
     * The order `JSONSerialization` `.sortedKeys` writes object keys in — not code-point order.
     * Measured over every printable ASCII key shape (and the export's whole key vocabulary):
     *
     * - A key is read as tokens: each maximal run of ASCII digits is one token, every other character
     *   is its own token.
     * - First pass, token by token: two digit runs compare by numeric value (leading zeros ignored); a
     *   digit run against a character compares as `0` would; two characters compare by Foundation's
     *   rank ([ASCII_RANK]: space, then punctuation in a collation order — `_` before `-` before `.`,
     *   `$` last — then digits, then letters, case ignored). If one key's tokens run out first, it
     *   sorts first.
     * - If that pass finds them equal, the first token that differs decides: in case, lower before
     *   upper; in leading zeros, fewer first.
     *
     * Characters outside printable ASCII are not measured (no export key holds one); they rank after
     * all of ASCII, by code point, so distinct keys still never compare equal.
     */
    val JSON_KEY_ORDER: Comparator<String> = Comparator { x, y -> compareKeys(x, y) }

    /** Foundation's rank of each printable ASCII character, letters by their lower case (measured). */
    private val ASCII_RANK: IntArray = IntArray(128) { -1 }.also { rank ->
        val order = " _-,;:!?.'\"()[]{}@*/\\&#%`^+<=>|~$0123456789abcdefghijklmnopqrstuvwxyz"
        order.forEachIndexed { i, c -> rank[c.code] = i }
        for (c in 'A'..'Z') rank[c.code] = rank[c.lowercaseChar().code]
    }

    private fun rank(c: Char): Int {
        val r = if (c.code < 128) ASCII_RANK[c.code] else -1
        return if (r >= 0) r else 1_000 + c.code
    }

    private fun isDigit(c: Char) = c in '0'..'9'

    private fun compareKeys(x: String, y: String): Int {
        var i = 0
        var j = 0
        var tie = 0 // the first case / leading-zero difference, kept for when the first pass is equal
        while (i < x.length && j < y.length) {
            val a = x[i]
            val b = y[j]
            if (isDigit(a) && isDigit(b)) {
                var ie = i
                while (ie < x.length && isDigit(x[ie])) ie++
                var je = j
                while (je < y.length && isDigit(y[je])) je++
                var iz = i
                while (iz < ie - 1 && x[iz] == '0') iz++
                var jz = j
                while (jz < je - 1 && y[jz] == '0') jz++
                val lenA = ie - iz
                val lenB = je - jz
                if (lenA != lenB) return lenA.compareTo(lenB)
                for (k in 0 until lenA) if (x[iz + k] != y[jz + k]) return x[iz + k].compareTo(y[jz + k])
                if (tie == 0 && iz - i != jz - j) tie = (iz - i).compareTo(jz - j)
                i = ie
                j = je
            } else {
                val ra = if (isDigit(a)) rank('0') else rank(a)
                val rb = if (isDigit(b)) rank('0') else rank(b)
                if (ra != rb) return ra.compareTo(rb)
                if (tie == 0 && a != b) tie = if (a.isLowerCase()) -1 else 1
                i++
                j++
            }
        }
        val restA = x.length - i
        val restB = y.length - j
        if (restA != restB) return if (restA == 0) -1 else 1
        return tie
    }
}
