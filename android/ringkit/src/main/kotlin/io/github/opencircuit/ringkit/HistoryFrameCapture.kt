package io.github.opencircuit.ringkit

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

// Debug-only raw history-frame recorder. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/Diagnostics/HistoryFrameCapture.swift:23-137
// (@ b1c2fdd), without its `Codable` conformance (the stored form is decided with the storage
// design).
//
// The `0x4c` record layout is pinned to the Gen 2 firmware. On another generation the overnight
// records may not decode, and recovering a new layout needs the RAW `0x4c` / `0x47` bytes. This is
// the buffer the app records into during any drain (foreground or background) so a tester can
// export it and send it to be decoded.
//
// TEXT FORMAT. The report must be byte-identical to upstream's, so an export from either app is
// read back by `DiagnosticsFrameImport`. Every number is formatted with `Locale.ROOT`; timestamps
// are ISO-8601 in UTC with whole seconds (upstream's `ISO8601DateFormatter` with
// `.withInternetDateTime` drops the fraction, rounding down).

/**
 * One raw frame received from the ring, kept for offline decoding. Holds the frame as text, so
 * changing the caller's array afterwards never changes it.
 */
class CapturedFrame(val date: Instant, bytes: ByteArray) {
    /** First byte (0–255), or 0 for an empty frame. */
    val opcode: Int = if (bytes.isEmpty()) 0 else bytes.u8(0)

    /** The whole frame as lowercase space-separated hex (e.g. "4c 00 12 …"). */
    val hex: String = lowercaseHex(bytes)

    /** Frame length in bytes. */
    val byteCount: Int = bytes.size

    override fun equals(other: Any?): Boolean =
        other is CapturedFrame && date == other.date && opcode == other.opcode &&
            hex == other.hex && byteCount == other.byteCount

    override fun hashCode(): Int = ((date.hashCode() * 31 + opcode) * 31 + hex.hashCode()) * 31 + byteCount

    override fun toString(): String = "CapturedFrame(date=$date, opcode=$opcode, byteCount=$byteCount, hex=$hex)"

    private companion object {
        const val HEX_DIGITS = "0123456789abcdef"

        /**
         * `%02x` per byte, space-separated, without a `String.format` call per byte: this runs for
         * every captured frame on the inbound stream (up to ~140 bytes each).
         */
        fun lowercaseHex(bytes: ByteArray): String {
            if (bytes.isEmpty()) return ""
            val sb = StringBuilder(bytes.size * 3 - 1)
            for ((i, b) in bytes.withIndex()) {
                if (i > 0) sb.append(' ')
                val v = b.toInt() and 0xFF
                sb.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0F])
            }
            return sb.toString()
        }
    }
}

/**
 * Bounded buffer of captured frames, oldest → newest, plus a human-readable report.
 *
 * OWNERSHIP. Upstream is a Swift `mutating struct`. Here it is a mutable class owned by one
 * recorder: sharing the reference shares the buffer, [copy] gives an independent one, [frames]
 * hands out a fresh list on every read, and two buffers compare by content.
 */
class HistoryFrameCapture(frames: List<CapturedFrame> = emptyList()) {

    /** One row of [countsByOpcode]. */
    data class OpcodeCount(val opcode: Int, val count: Int)

    companion object {
        /** Keep at most this many (newest survive): a whole overnight drain is a few hundred frames. */
        const val CAP = 1500

        /**
         * Opcodes worth capturing: history pages (`0x47` PPG / `0x4c` sleep), the end-of-history
         * report (`0x50`), the sync-open ACK (`0x82`), the status descriptor (`0x10` / `0x87`),
         * sport-history pages (`0x4d`) and the manual sport-mode stream (`0x4e`). Excludes the
         * high-rate live poll (`0x15`) and heartbeats (`0x11`).
         */
        val CAPTURED_OPCODES: Set<Int> = setOf(0x47, 0x4c, 0x4d, 0x50, 0x82, 0x10, 0x87, 0x4e)

        /** True when this frame's opcode should be recorded (see [CAPTURED_OPCODES]). */
        fun shouldCapture(bytes: ByteArray): Boolean = bytes.isNotEmpty() && bytes.u8(0) in CAPTURED_OPCODES

        private val ISO_UTC: DateTimeFormatter =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

        /** Upstream's `ISO8601DateFormatter` with `.withInternetDateTime`: UTC, whole seconds. */
        internal fun isoUtc(instant: Instant): String = ISO_UTC.format(instant)
    }

    private val held = ArrayList<CapturedFrame>(frames)

    init {
        trim()
    }

    /** Captured frames, oldest → newest. A fresh list on every read. */
    val frames: List<CapturedFrame> get() = held.toList()

    val count: Int get() = held.size

    /**
     * Record a frame received at [at] if its opcode is one worth triaging. Returns whether it was
     * recorded, so the caller can skip a costly save when nothing changed.
     */
    fun recordIfRelevant(bytes: ByteArray, at: Instant): Boolean {
        if (!shouldCapture(bytes)) return false
        held += CapturedFrame(at, bytes)
        trim()
        return true
    }

    fun clear() {
        held.clear()
    }

    private fun trim() {
        if (held.size > CAP) held.subList(0, held.size - CAP).clear()
    }

    /** Count of captured frames per opcode, sorted by opcode. */
    fun countsByOpcode(): List<OpcodeCount> =
        held.groupingBy { it.opcode }.eachCount().toSortedMap().map { (op, n) -> OpcodeCount(op, n) }

    /**
     * A shareable plain-text report: a device/firmware header, an opcode summary, then every
     * captured frame as `timestamp  opcode  Nb  hex`. Lines are joined with `\n`, no trailing
     * newline.
     */
    fun report(firmware: FirmwareInfo, generatedAt: Instant): String {
        val lines = mutableListOf<String>()
        lines += "OpenCircuit — RingConn history-frame diagnostic capture"
        lines += "Generated: ${isoUtc(generatedAt)}"
        lines += ""
        lines += "# Device"
        lines += "Firmware:     ${firmware.version.ifEmpty { "(unread)" }}"
        lines += "Generation:   ${firmware.generation.rawValue}"
        lines += "Pinned build: ${FirmwareInfo.PINNED_VERSION}"
        lines += "Model:        ${firmware.modelName.ifEmpty { "(unread)" }}"
        lines += "Manufacturer: ${firmware.manufacturer.ifEmpty { "(unread)" }}"
        lines += "HW revision:  ${firmware.hardwareRevision ?: "(unread)"}"
        lines += "MAC:          ${firmware.mac ?: "(unread)"}"
        lines += ""
        lines += "# Privacy"
        lines += "These frames include the ring's overnight HR / HRV / SpO₂ history bytes and its"
        lines += "MAC. They are not encrypted health records, but treat this file as personal data"
        lines += "and only share it with someone you trust to decode it."
        lines += ""
        lines += "# Summary"
        lines += "Frames captured: ${held.size} (cap $CAP)"
        for (entry in countsByOpcode()) {
            lines += String.format(Locale.ROOT, "  0x%02x: %d", entry.opcode, entry.count)
        }
        lines += ""
        lines += "# Frames (oldest → newest)"
        if (held.isEmpty()) {
            lines += "(none — enable capture, then do an overnight wear + morning sync)"
        } else {
            for (f in held) {
                lines += isoUtc(f.date) + "  " +
                    String.format(Locale.ROOT, "0x%02x  %3db  ", f.opcode, f.byteCount) +
                    f.hex
            }
        }
        return lines.joinToString("\n")
    }

    /** An independent copy: recording into or clearing either buffer never changes the other. */
    fun copy(): HistoryFrameCapture = HistoryFrameCapture(held)

    override fun equals(other: Any?): Boolean = other is HistoryFrameCapture && held == other.held

    override fun hashCode(): Int = held.hashCode()

    override fun toString(): String = "HistoryFrameCapture(count=${held.size})"
}
