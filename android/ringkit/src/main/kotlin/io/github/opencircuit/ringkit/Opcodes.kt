package io.github.opencircuit.ringkit

import java.time.Instant

// RingConn command/response opcodes, TX command literals and BLE transport constants.
// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Opcodes.swift (@ b1c2fdd).
// All 🟢 confirmed from the FR02.018 capture (../docs/PROTOCOL.md §4) unless noted.

/** Opcode bytes (0..255). Opcodes.swift:6-26; PROTOCOL.md §4. */
object Opcode {
    /** Live-sample poll / keepalive → response 0x15. */
    const val POLL = 0x95

    /** Fetch next history record header → response 0x87. */
    const val FETCH_RECORD = 0x07

    /** Bulk-transfer page ACK / continue → responses 0x47 and 0x4C. */
    const val PAGE_47 = 0xC7
    const val PAGE_4C = 0xCC

    // Session setup / metadata (roles partly 🟡 — PROTOCOL.md §4).
    const val SESSION_SETUP = 0x01
    const val SYNC_OPEN = 0x02
    const val LIVE_HR_MODE = 0x06
    const val STATUS_QUERY = 0xD0

    /** OSA dense-PPG store-and-forward burst (#91, 🟢), sent unprompted with no per-frame ack. */
    const val OSA_PPG = 0x48
}

/**
 * ByteArray from unsigned byte values — the production literal builder behind every `Command` getter.
 * Swift's `UInt8` parameters made an out-of-range byte unrepresentable; with `Int` parameters
 * this check keeps e.g. `sportStart(0x100)` from silently truncating to 0x00
 * (no silent fail-open at a boundary).
 */
private fun bytes(vararg v: Int): ByteArray {
    require(v.all { it in 0..0xFF }) { "command byte out of range 0..255: ${v.toList()}" }
    return ByteArray(v.size) { v[it].toByte() }
}

/**
 * Exact TX command byte sequences, sent VERBATIM (🟢 PROTOCOL.md §3). Commands are NOT
 * XOR-checksummed — never build them with [Frame.xorTrailer]; that yields frames the ring ignores.
 *
 * Every literal is a getter returning a FRESH array: no caller can mutate
 * another caller's command bytes. Opcodes.swift:32-188, minus the legacy auth fallbacks
 * `status1`, `liveHRStart` and `authNonce`/`knownAuthNonces` — not ported (see D-1 in
 * PORTING.md). Per-connection auth is `RingAuth` (PROTOCOL.md §5.8).
 */
object Command {
    val status0: ByteArray get() = bytes(0x01, 0x00, 0x00)

    /**
     * Open the data session at cursor 0xFFFFFFFF — a far-FUTURE cursor believed to return an EMPTY
     * history (the live path's "skip the backlog" open) — 🟡 (PROTOCOL.md §3). For history use [syncUpToNow].
     */
    val syncAll: ByteArray get() = bytes(0x02, 0x00, 0xFF, 0xFF, 0xFF, 0xFF, 0x00, 0x01, 0x00)

    /** → 0x50; precedes live mode. */
    val statusQuery: ByteArray get() = bytes(0xD0, 0x00, 0x00)

    /** `06 01` = HR; `06 02` = SpO2. */
    val liveHRMode: ByteArray get() = bytes(0x06, 0x01, 0x00)
    val liveSpO2Mode: ByteArray get() = bytes(0x06, 0x02, 0x00)
    val fetch: ByteArray get() = bytes(0x07, 0x00, 0x00)
    val poll: ByteArray get() = bytes(0x95, 0x00, 0x00)
    val pageAck47: ByteArray get() = bytes(0xC7, 0x00, 0x00)
    val pageAck4C: ByteArray get() = bytes(0xCC, 0x00, 0x00)

    /** Continue a `0x4d` historical sport page (0x4d = 0xcd ^ 0x80). */
    val pageAck4D: ByteArray get() = bytes(0xCD, 0x00, 0x00)

    /**
     * Reply to the ring's unsolicited `0x11` heartbeat: a constant `91 00 00` — the counter/token is
     * NOT echoed (🟢 PROTOCOL.md §5.8).
     */
    val heartbeatAck: ByteArray get() = bytes(0x91, 0x00, 0x00)

    // Native sport / workout mode (🟢 FR02.018, #90).

    /** Enter native workout mode for [type] (0x01–0x07): `06 03 <type> 04 00` → resp `86 00 86`. */
    fun sportStart(type: Int): ByteArray = bytes(0x06, 0x03, type, 0x04, 0x00)

    /** End native workout mode: `06 00 00` → resp `86 00 86`. */
    val sportStop: ByteArray get() = bytes(0x06, 0x00, 0x00)

    /** Ack a `0x4e` sport-stream frame to keep the stream flowing (0x4e = 0xce ^ 0x80). */
    val sportStreamAck: ByteArray get() = bytes(0xCE, 0x00, 0x00)

    // Device actions (#96).

    /** Find My Ring — locator LED on. `24 01 00` → resp `a4 00 a4`. 🟢 device-verified. */
    val findRingLight: ByteArray get() = bytes(0x24, 0x01, 0x00)

    /** Find My Ring — locator LED off. `24 00 00`. 🟡 probable (on/off convention). */
    val findRingLightOff: ByteArray get() = bytes(0x24, 0x00, 0x00)

    /** Ring airplane mode ON — drops the BLE link; only the charging case re-wakes it. `08 04 00` → `88 00 88`. */
    val airplaneModeOn: ByteArray get() = bytes(0x08, 0x04, 0x00)

    /**
     * Drive the Gen 3 vibration motor (🟢 PROTOCOL.md §5.9, recovered from an HCI capture — NOT in
     * the APK): `0b 03 <pattern> 64 00` → resp `8b 00 8b`. `[3]` is MEASURED INERT and pinned to
     * [RingVibration.INTENSITY_BYTE]. The reply means ACCEPTED, not felt — no receipt, no stop
     * command. Gate on [RingVibration.isSupported] first. Opcodes.swift:95-97.
     */
    fun vibrate(pattern: VibrationPattern): ByteArray =
        bytes(0x0B, 0x03, pattern.rawValue, RingVibration.INTENSITY_BYTE, 0x00)

    /** Arm an overnight OSA (sleep-apnea) assessment (#91, 🟢): `05 22 01`. */
    val osaAssessmentStart: ByteArray get() = bytes(0x05, 0x22, 0x01)

    /** Stop/disarm the OSA assessment: `05 22 02`. */
    val osaAssessmentStop: ByteArray get() = bytes(0x05, 0x22, 0x02)

    /** Enable/disable ring-side automatic workout recognition (#179, 🟢): `05 23 <01|00> 00`. */
    fun automaticSportRecognition(enabled: Boolean): ByteArray =
        bytes(0x05, 0x23, if (enabled) 0x01 else 0x00, 0x00)

    /** Sync-cursor epoch: seconds since 2019-12-31 12:00:00 UTC (🟢 PROTOCOL.md §5.6). */
    const val SYNC_EPOCH = 1_577_793_600L

    /** History-channel selector, `byte[6]` of the `0x02` sync-open (PROTOCOL.md §5.6.1): sleep/overnight log. */
    const val SYNC_CHANNEL_SLEEP = 0x00

    /** Store-and-forward 10-second sport history (#179). */
    const val SYNC_CHANNEL_SPORT = 0x02

    /** Awake/all-day log: activity HR + periodic daytime SpO₂. */
    const val SYNC_CHANNEL_ALL_DAY = 0x03

    /**
     * Build `02 00 <cursor BE4> <channel> 01 00` with `cursor = unixSeconds − SYNC_EPOCH`.
     * A plausible-recent cursor is a "drain up to ≈now" trigger, not a hard bound (PROTOCOL.md §3).
     */
    fun syncSince(unixSeconds: Long, channel: Int = SYNC_CHANNEL_SLEEP): ByteArray {
        // UInt32(clamping:) semantics: a pre-2020 clock clamps to 0, a far-future clock to 0xFFFFFFFF
        // (fails safe to the skip-backlog open) — never WRAPS to a small, valid-looking cursor.
        // Compare before subtracting: `unixSeconds - SYNC_EPOCH` overflows near Long.MIN_VALUE
        // (Swift traps there; Kotlin would wrap to a huge positive cursor).
        val c = if (unixSeconds <= SYNC_EPOCH) 0L else (unixSeconds - SYNC_EPOCH).coerceAtMost(0xFFFF_FFFFL)
        return bytes(
            0x02, 0x00,
            ((c ushr 24) and 0xFF).toInt(), ((c ushr 16) and 0xFF).toInt(),
            ((c ushr 8) and 0xFF).toInt(), (c and 0xFF).toInt(),
            channel, 0x01, 0x00,
        )
    }

    /**
     * Open a HISTORY sync "up to NOW" on [channel] — the official app's history behaviour
     * (🟢 PROTOCOL.md §3). Use this, NOT [syncAll], for sleep/vitals history. [now] is injectable so tests can pin the clock.
     */
    fun syncUpToNow(now: Instant = Instant.now(), channel: Int = SYNC_CHANNEL_SLEEP): ByteArray =
        syncSince(now.epochSecond, channel)
}

/** BLE transport handles and UUIDs (🟢 PROTOCOL.md §1). Opcodes.swift:193-211. */
object Transport {
    /** All responses + data. */
    const val NOTIFY_HANDLE = 0x0804

    /** All commands. */
    const val WRITE_HANDLE = 0x0802

    /** Enable with `01 00`. */
    const val NOTIFY_CCCD = 0x0805

    /** Primary data service + characteristic UUIDs (🟢 confirmed by scan, FR02.018). */
    const val DATA_SERVICE_UUID = "8327ad99-2d87-4a22-a8ce-6dd7971c0437"
    const val NOTIFY_CHAR_UUID = "8327ad97-2d87-4a22-a8ce-6dd7971c0437"
    const val WRITE_CHAR_UUID = "8327ad98-2d87-4a22-a8ce-6dd7971c0437"

    /** Advertised-name prefixes matched while scanning. Observed: "RingConn Gen2-<MAC suffix>" (🟢). */
    val namePrefixes: List<String> = listOf("RingConn", "Ring")

    fun matchesRingName(name: String): Boolean = namePrefixes.any { name.startsWith(it) }
}
