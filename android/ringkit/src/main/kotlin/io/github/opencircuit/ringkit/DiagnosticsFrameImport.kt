package io.github.opencircuit.ringkit

import java.time.Instant

// Recover epoch records from a diagnostics export's raw-frame capture. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/DiagnosticsFrameImport.swift:19-165 (@ b1c2fdd).
//
// WHY THIS CAN WORK AT ALL. The raw-frame capture (`HistoryFrameCapture`) taps the inbound stream
// ABOVE the retention gate. So when a page was acked and then discarded, its bytes are still in the
// user's own diagnostics export even though the epoch never reached the archive — and the ring's
// resume pointer has long since moved past it and will never offer it again.
//
// The capture format is one frame per line:
//     2026-08-04T12:56:16Z  0x4c  142b  4c 00 ca 0c 66 2f 3a 37 …
// Only `0x4c` carries epoch records; `0x47` (PPG), `0x50`, `0x10` / `0x87` do not.

object DiagnosticsFrameImport {

    /**
     * Identity of the ring the export came FROM, read out of the report's `# Device` header. The
     * MAC is usually redacted to its last octet (`··:··:··:··:··:AD`), which is still enough to
     * tell two rings apart in practice; model and firmware corroborate it.
     */
    data class SourceRing(
        val model: String? = null,
        val firmware: String? = null,
        /** Last octet of the MAC, uppercased (e.g. "AD"), or null when the header is absent. */
        val macSuffix: String? = null,
    ) {
        val isEmpty: Boolean get() = model == null && firmware == null && macSuffix == null

        /**
         * Whether this export plausibly came from the SAME ring as [other]. Conservative: unknown
         * on either side is NOT a match, so the caller must ask rather than silently merge.
         */
        fun matches(other: SourceRing): Boolean {
            // Any field known on BOTH sides that disagrees is an immediate mismatch.
            if (macSuffix != null && other.macSuffix != null && macSuffix != other.macSuffix) return false
            if (model != null && other.model != null && model != other.model) return false
            if (firmware != null && other.firmware != null && firmware != other.firmware) return false
            // …and at least one field must be corroborated on both sides.
            val macAgrees = macSuffix != null && macSuffix == other.macSuffix
            val modelAgrees = model != null && model == other.model
            return macAgrees || modelAgrees
        }
    }

    /** What an import recovered. Built only by [recordsFromDiagnosticsText]. */
    class Result internal constructor(
        records: List<BulkRecord>,
        /** `0x4c` lines seen in the text. */
        val pagesSeen: Int,
        /** Pages rejected by the real decoder (bad XOR, wrong length, malformed hex). */
        val pagesRejected: Int,
        /** Records dropped as exact counter duplicates (the ring re-sends a page after a missed ack). */
        val duplicateRecords: Int,
        /**
         * Which ring the file says it came from — the caller MUST check this before merging, since
         * the archive is per-ring and a foreign export would silently pollute it.
         */
        val sourceRing: SourceRing,
    ) {
        /** Decoded, de-duplicated records, ascending by counter. */
        val records: List<BulkRecord> = records.toList()

        val isEmpty: Boolean get() = records.isEmpty()

        /** Wall-clock span the recovered records cover, both ends inclusive. */
        val coverage: ClosedRange<Instant>?
            get() {
                val f = records.firstOrNull()?.date() ?: return null
                val l = records.last().date()
                return if (f <= l) f..l else null
            }

        override fun equals(other: Any?): Boolean =
            other is Result && records == other.records && pagesSeen == other.pagesSeen &&
                pagesRejected == other.pagesRejected && duplicateRecords == other.duplicateRecords &&
                sourceRing == other.sourceRing

        override fun hashCode(): Int =
            listOf(records, pagesSeen, pagesRejected, duplicateRecords, sourceRing).hashCode()

        override fun toString(): String =
            "Result(records=${records.size}, pagesSeen=$pagesSeen, pagesRejected=$pagesRejected, " +
                "duplicateRecords=$duplicateRecords, sourceRing=$sourceRing)"
    }

    /**
     * Parse every `0x4c` page out of a diagnostics export and decode its epoch records.
     *
     * Decoding goes through the SAME [BulkSleep.recordsFromPage] the live BLE path uses, so a page
     * with a bad XOR trailer or a body that is not a whole number of records is rejected here
     * exactly as it would be on the wire.
     */
    fun recordsFromDiagnosticsText(text: String): Result {
        val decoded = HashMap<Long, BulkRecord>()
        var seen = 0
        var rejected = 0
        var duplicates = 0
        for (line in lines(text)) {
            val bytes = pageBytesFromLine(line) ?: continue
            seen += 1
            val recs = BulkSleep.recordsFromPage(bytes)
            if (recs.isEmpty()) {
                rejected += 1
                continue
            }
            for (r in recs) {
                if (decoded.containsKey(r.counter)) duplicates += 1 else decoded[r.counter] = r
            }
        }
        return Result(
            records = decoded.values.sortedBy { it.counter },
            pagesSeen = seen,
            pagesRejected = rejected,
            duplicateRecords = duplicates,
            sourceRing = sourceRingFromDiagnosticsText(text),
        )
    }

    /**
     * Read the `# Device` header — `Firmware:` / `Model:` / `MAC:` — so the caller can refuse an
     * export from a DIFFERENT ring before merging it into a per-ring archive.
     */
    fun sourceRingFromDiagnosticsText(text: String): SourceRing {
        var model: String? = null
        var firmware: String? = null
        var macSuffix: String? = null
        for (line in lines(text)) {
            val whole = trimWhitespace(line)
            // The MAC is read from the WHOLE line, before the `·` split below: the redacted MAC's
            // padding character IS the `·` separator, so splitting first would shred it.
            if (macSuffix == null && whole.startsWith("MAC:")) {
                val v = trimWhitespace(whole.substring("MAC:".length))
                val last = v.split(':').filter { it.isNotEmpty() }.lastOrNull()
                if (last != null && last.length == 2 && parseByte(last) != null) {
                    macSuffix = last.uppercase()
                }
            }
            // The other fields come both ways: one `·`-separated summary line ("Firmware: FR02.018 ·
            // Generation: Gen 2 · Model: …") and one field per line in the `# Device` block. Split
            // on `·` so a value can never swallow the fields after it on the same line.
            for (segment in line.split('·').filter { it.isNotEmpty() }) {
                val t = trimWhitespace(segment)
                fun value(prefix: String): String? {
                    if (!t.startsWith(prefix)) return null
                    return trimWhitespace(t.substring(prefix.length)).ifEmpty { null }
                }
                if (firmware == null) value("Firmware:")?.let { firmware = it }
                if (model == null) value("Model:")?.let { model = it }
            }
            // The header sits above the frame dump; stop before scanning thousands of hex lines.
            if (line.startsWith("# Frames")) break
        }
        return SourceRing(model = model, firmware = firmware, macSuffix = macSuffix)
    }

    /**
     * The bytes of a `0x4c` capture line, or null for any other line. Tolerant of the surrounding
     * report and strict about the frame: the opcode token must be exactly `0x4c` (any case), every
     * token after the length must be a two-character hex byte, and a declared length (`142b`) must
     * match the byte count.
     */
    internal fun pageBytesFromLine(line: String): ByteArray? {
        val fields = trimWhitespaceAndNewlines(line).split(' ').filter { it.isNotEmpty() }
        // timestamp, opcode, length, then ≥ 1 hex byte
        if (fields.size < 4) return null
        if (fields[1].lowercase() != "0x4c") return null
        val out = ByteArray(fields.size - 3)
        for ((i, token) in fields.drop(3).withIndex()) {
            if (token.length != 2) return null
            out[i] = (parseByte(token) ?: return null).toByte()
        }
        if (out.size < 4 || out.u8(0) != 0x4C) return null
        // Cross-check the declared length when present — a truncated copy-paste would otherwise
        // decode a short body as if it were whole.
        val declared = if (fields[2].endsWith("b")) fields[2].dropLast(1).toIntOrNull() else null
        if (declared != null && declared != out.size) return null
        return out
    }

    // --- Swift string semantics, spelled out ---

    /**
     * Swift's `split(whereSeparator: \.isNewline)`: `\r\n` is ONE separator (a single grapheme in
     * Swift), as is each of LF, VT, FF, CR, NEL, LS and PS; empty lines are dropped.
     */
    private val NEWLINE = Regex("\r\n|[\n\u000B\u000C\r\u0085\u2028\u2029]")

    private fun lines(text: String): List<String> = text.split(NEWLINE).filter { it.isNotEmpty() }

    /** Swift's `CharacterSet.whitespaces`: tab plus every Unicode space separator (Zs). */
    private fun isSwiftWhitespace(c: Char): Boolean = c == '\t' || Character.getType(c) == Character.SPACE_SEPARATOR.toInt()

    private fun isSwiftNewline(c: Char): Boolean =
        c in '\n'..'\r' || c == '\u0085' || c == '\u2028' || c == '\u2029'

    private fun trimWhitespace(s: String): String = s.trim { isSwiftWhitespace(it) }

    private fun trimWhitespaceAndNewlines(s: String): String = s.trim { isSwiftWhitespace(it) || isSwiftNewline(it) }

    /**
     * Swift's `UInt8(_, radix: 16)`: an optional sign, ASCII hex digits, value 0–255; null
     * otherwise. `toIntOrNull(16)` alone would also take fullwidth and other Unicode digits,
     * which Swift rejects.
     */
    private fun parseByte(token: String): Int? {
        val digits = if (token.startsWith('+') || token.startsWith('-')) token.substring(1) else token
        if (digits.isEmpty() || !digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return token.toIntOrNull(16)?.takeIf { it in 0..0xFF }
    }
}
