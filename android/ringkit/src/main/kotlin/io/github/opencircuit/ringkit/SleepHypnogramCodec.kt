package io.github.opencircuit.ringkit

// On-disk codec for a night's staged hypnogram. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/SleepHypnogramCodec.swift (@ b1c2fdd).
//
// WHY a hand-pinned byte format instead of serialising `SleepSegment`: `SleepSegment` is a live model
// type that other work is free to refactor, and a field or a renamed stage would silently change the
// encoding of every night already stored. This format is deliberately narrow (three integers per
// segment, a fourth only for user-asserted time), and a test pins its exact bytes.
//
// Format: a JSON array of 3- OR 4-element integer arrays
//     [[startEpochSeconds, endEpochSeconds, stageCode], …]
//     [[startEpochSeconds, endEpochSeconds, stageCode, provenanceCode], …]
// with stageCode 0=inBed 1=awake 2=asleepCore 3=asleepDeep 4=asleepREM
// and provenanceCode 1=asserted 2=assertedOverMeasured 3=assertedCoverageUnknown (0 = measured, OMITTED).
//
// ⚠️ THE FOURTH ELEMENT IS WRITTEN ONLY WHEN PROVENANCE IS NOT MEASURED, so an unedited night encodes to
// the identical bytes it did before provenance existed, and an older reader's 3-element guard drops
// exactly the user-asserted rows — the safe direction.
//
// Robustness policy (this is stored user data — it must never throw and never invent):
//   • empty bytes, non-JSON bytes, or JSON that isn't an array of integer arrays → no segments at all
//     (never the rows read before the error);
//   • a row that isn't three or four integers, carries an unknown stage code, or is reversed /
//     zero-length → that ONE row is dropped, the rest survive;
//   • an UNKNOWN provenance code reads as measured rather than dropping the row: losing real sleep is
//     worse than losing its label.
//
// THE READER IS UPSTREAM'S JSON READER, REPRODUCED (every case below was measured on the pinned
// build). JSON's grammar only — no comments, no NaN, no leading `+` or zeros — except that a trailing
// comma before `]` is accepted at either depth. Only ASCII digits and JSON's four whitespace
// characters: Kotlin's `Character.isDigit`, `digitToInt` and `String.toLong` accept fullwidth and other
// Unicode digits that upstream rejects, so none of them is used here. A number spelled without
// `.`/`e`/`E` must fit 64 bits exactly; any other spelling is read as a double that must be a whole
// number inside the 64-bit range, and from 2^53 up its decimal digits are then read exactly and
// truncated toward zero. The text may be UTF-8 (with or without a byte-order mark), UTF-16 or UTF-32
// (detected from a byte-order mark or from where the zero bytes sit, a trailing partial code unit
// ignored).
//
// TIMES: upstream turns each stored integer into a `Date`, a double count of seconds, so above 2^53 s
// the value is rounded to the nearest double; that rounding is reproduced. A row whose rounded seconds
// lie outside `Instant`'s range (about a billion years either side) is dropped, where upstream keeps a
// `Date` no `Instant` can hold. Encoding rounds each instant to whole seconds, half away from zero, on
// the exact nanosecond value.

import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant

/** Encode / decode a night's segments to and from the stored hypnogram bytes. */
object SleepHypnogramCodec {

    /** Wire code for each stage. On disk in every install: a stage may be added, never renumbered. */
    private val CODE_FOR_STAGE: Map<SleepStage, Int> = mapOf(
        SleepStage.IN_BED to 0, SleepStage.AWAKE to 1, SleepStage.ASLEEP_CORE to 2,
        SleepStage.ASLEEP_DEEP to 3, SleepStage.ASLEEP_REM to 4,
    )
    private val STAGE_FOR_CODE: Map<Long, SleepStage> = CODE_FOR_STAGE.entries.associate { (stage, code) -> code.toLong() to stage }

    /** Wire code for provenance. MEASURED is 0 and is never written. */
    private val CODE_FOR_PROVENANCE: Map<SleepProvenance, Int> = mapOf(
        SleepProvenance.MEASURED to 0, SleepProvenance.ASSERTED to 1,
        SleepProvenance.ASSERTED_OVER_MEASURED to 2, SleepProvenance.ASSERTED_COVERAGE_UNKNOWN to 3,
    )
    private val PROVENANCE_FOR_CODE: Map<Long, SleepProvenance> =
        CODE_FOR_PROVENANCE.entries.associate { (p, code) -> code.toLong() to p }

    /**
     * Encode [segments] to the stored form — compact, key-free JSON, so the bytes are fully determined
     * by the numbers. Segments that [decode] would refuse (reversed or zero-length once rounded to
     * whole seconds) are skipped: a stored row we would not read back makes the stored night disagree
     * with the loaded one. Returns a new array on every call.
     */
    fun encode(segments: List<SleepSegment>): ByteArray {
        val out = StringBuilder("[")
        var first = true
        for (seg in segments) {
            val code = CODE_FOR_STAGE[seg.stage] ?: continue
            val start = roundedSeconds(seg.start)
            val end = roundedSeconds(seg.end)
            if (end <= start) continue
            val p = CODE_FOR_PROVENANCE[seg.provenance] ?: 0
            if (!first) out.append(',')
            first = false
            // StringBuilder.append(Long) writes ASCII digits whatever the default locale.
            out.append('[').append(start).append(',').append(end).append(',').append(code)
            if (p != 0) out.append(',').append(p)
            out.append(']')
        }
        return out.append(']').toString().toByteArray(Charsets.US_ASCII)
    }

    /**
     * Decode the stored form. Returns no segments for anything it cannot read; drops individual
     * malformed rows rather than a whole night for one bad row. Never throws, never fabricates.
     */
    fun decode(data: ByteArray): List<SleepSegment> {
        val units = codeUnits(data) ?: return emptyList()
        val rows = JsonRows(units).parse() ?: return emptyList()
        return rows.mapNotNull { row ->
            if (row.size != 3 && row.size != 4) return@mapNotNull null
            val stage = STAGE_FOR_CODE[row[2]] ?: return@mapNotNull null
            val start = row[0]
            val end = row[1]
            if (end <= start) return@mapNotNull null
            // A 3-element row is a legacy (pre-provenance) segment: measured is the only honest reading.
            val provenance = if (row.size == 4) PROVENANCE_FOR_CODE[row[3]] ?: SleepProvenance.MEASURED else SleepProvenance.MEASURED
            val s = storedInstant(start) ?: return@mapNotNull null
            val e = storedInstant(end) ?: return@mapNotNull null
            SleepSegment(s, e, stage, provenance)
        }
    }

    /** Whole seconds, half away from zero, of the exact instant (upstream: `Int(date.rounded())`). */
    private fun roundedSeconds(t: Instant): Long {
        val s = t.epochSecond
        val n = t.nano
        return if (s >= 0) (if (n >= 500_000_000) s + 1 else s) else (if (n > 500_000_000) s + 1 else s)
    }

    /** The instant upstream's `Date(timeIntervalSince1970: Double(x))` names, or null when no `Instant` can. */
    private fun storedInstant(x: Long): Instant? {
        val seconds = x.toDouble().toLong() // the nearest double, as upstream's TimeInterval holds it
        if (seconds < Instant.MIN.epochSecond || seconds > Instant.MAX.epochSecond) return null
        return Instant.ofEpochSecond(seconds)
    }

    // --- Text decoding: upstream's encoding detection ---

    private const val NOT_ASCII = -1

    /** The text's code units, each 0..0x7F or [NOT_ASCII]; null for empty input. */
    private fun codeUnits(b: ByteArray): IntArray? {
        if (b.isEmpty()) return null
        fun u(i: Int): Int = b[i].toInt() and 0xFF
        val n = b.size
        if (n >= 3 && u(0) == 0xEF && u(1) == 0xBB && u(2) == 0xBF) return IntArray(n - 3) { ascii(u(it + 3)) }
        if (n >= 4 && u(0) == 0 && u(1) == 0 && u(2) == 0xFE && u(3) == 0xFF) return wide(b, 4, 4, bigEndian = true)
        if (n >= 2 && u(0) == 0xFE && u(1) == 0xFF) return wide(b, 2, 2, bigEndian = true)
        if (n >= 2 && u(0) == 0xFF && u(1) == 0xFE) return wide(b, 2, 2, bigEndian = false)
        if (n >= 4) {
            if (u(0) == 0 && u(1) == 0 && u(2) == 0) return wide(b, 0, 4, bigEndian = true)
            if (u(1) == 0 && u(2) == 0 && u(3) == 0) return wide(b, 0, 4, bigEndian = false)
            if (u(0) == 0 && u(2) == 0) return wide(b, 0, 2, bigEndian = true)
            if (u(1) == 0 && u(3) == 0) return wide(b, 0, 2, bigEndian = false)
        }
        return IntArray(n) { ascii(u(it)) }
    }

    private fun ascii(unit: Long): Int = if (unit in 0..0x7F) unit.toInt() else NOT_ASCII
    private fun ascii(unit: Int): Int = ascii(unit.toLong())

    /** UTF-16 / UTF-32 code units from [offset], [width] bytes each; a trailing partial unit is ignored. */
    private fun wide(b: ByteArray, offset: Int, width: Int, bigEndian: Boolean): IntArray =
        IntArray((b.size - offset) / width) { k ->
            var v = 0L
            for (j in 0 until width) {
                val byte = (b[offset + k * width + (if (bigEndian) j else width - 1 - j)].toInt() and 0xFF).toLong()
                v = (v shl 8) or byte
            }
            ascii(v)
        }

    // --- The JSON reader: an array of arrays of integers, nothing else ---

    private class JsonRows(private val u: IntArray) {
        private var i = 0

        private fun skipWhitespace() {
            while (i < u.size && (u[i] == 0x20 || u[i] == 0x09 || u[i] == 0x0A || u[i] == 0x0D)) i++
        }

        private fun eat(c: Char): Boolean {
            if (i < u.size && u[i] == c.code) {
                i++
                return true
            }
            return false
        }

        /** Every row, or null when the text is not exactly one array of integer arrays. */
        fun parse(): List<LongArray>? {
            skipWhitespace()
            val rows = list { row() } ?: return null
            skipWhitespace()
            return if (i == u.size) rows else null
        }

        private fun row(): LongArray? = list { number() }?.toLongArray()

        /** `[` item (`,` item)* `,`? `]`, or `[` `]`. */
        private fun <T> list(item: () -> T?): List<T>? {
            if (!eat('[')) return null
            val out = ArrayList<T>()
            skipWhitespace()
            if (eat(']')) return out
            while (true) {
                skipWhitespace()
                out += item() ?: return null
                skipWhitespace()
                if (eat(',')) {
                    skipWhitespace()
                    if (eat(']')) return out // a trailing comma, as upstream's reader accepts
                    continue
                }
                if (eat(']')) return out
                return null
            }
        }

        private fun isDigit(at: Int): Boolean = at < u.size && u[at] in '0'.code..'9'.code

        /** One JSON number that is a whole 64-bit value, or null. */
        private fun number(): Long? {
            val start = i
            eat('-')
            if (!isDigit(i)) return null
            if (u[i] == '0'.code) i++ else while (isDigit(i)) i++
            var integral = true
            if (eat('.')) {
                integral = false
                if (!isDigit(i)) return null
                while (isDigit(i)) i++
            }
            if (i < u.size && (u[i] == 'e'.code || u[i] == 'E'.code)) {
                integral = false
                i++
                if (i < u.size && (u[i] == '+'.code || u[i] == '-'.code)) i++
                if (!isDigit(i)) return null
                while (isDigit(i)) i++
            }
            val token = String(CharArray(i - start) { u[start + it].toChar() })
            return if (integral) exactLong(token) else wholeLong(token)
        }

        /** An integer spelling: exact, or null outside 64 bits. ASCII digits only reach here. */
        private fun exactLong(token: String): Long? {
            val negative = token[0] == '-'
            var acc = 0L // accumulated negatively, so Long.MIN_VALUE fits
            for (k in (if (negative) 1 else 0) until token.length) {
                val d = token[k].code - '0'.code
                if (acc < Long.MIN_VALUE / 10) return null
                acc *= 10
                if (acc < Long.MIN_VALUE + d) return null
                acc -= d
            }
            return if (negative) acc else if (acc == Long.MIN_VALUE) null else -acc
        }

        /** A fraction or exponent spelling: a whole double inside 64 bits; exact decimal digits from 2^53. */
        private fun wholeLong(token: String): Long? {
            val d = token.toDouble() // java.lang.Double.parseDouble: locale-free, correctly rounded
            if (!d.isFinite() || d != Math.floor(d)) return null
            if (d < -TWO_POW_63 || d >= TWO_POW_63) return null
            if (Math.abs(d) < TWO_POW_53) return d.toLong()
            val exact: BigInteger = try {
                BigDecimal(token).toBigInteger() // truncates toward zero
            } catch (e: NumberFormatException) {
                return null // an exponent BigDecimal cannot hold: no whole 64-bit value either
            } catch (e: ArithmeticException) {
                return null
            }
            return if (exact.abs() > LONG_MAX) null else exact.toLong()
        }

        private companion object {
            const val TWO_POW_53 = 9.007199254740992E15
            const val TWO_POW_63 = 9.223372036854775808E18
            val LONG_MAX: BigInteger = BigInteger.valueOf(Long.MAX_VALUE)
        }
    }
}
