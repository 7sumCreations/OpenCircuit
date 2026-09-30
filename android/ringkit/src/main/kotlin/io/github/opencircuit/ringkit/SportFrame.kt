package io.github.opencircuit.ringkit

// RingConn native SPORT-mode protocol (upstream #90). Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/SportFrame.swift:27-78 (@ b1c2fdd).
//
// The ring has a dedicated workout mode entered with `06 03 <type> 04 00` (`Command.sportStart`),
// which streams a `0x4e` frame roughly every ~10 s carrying HR + steps for the interval, each acked
// with `ce 00 00` (`Command.sportStreamAck`; 0x4e = 0xce ^ 0x80), and ended with `06 00 00`
// (`Command.sportStop`).
//
// Ground truth (🟢 FR02.018, captured 2026-07-06, HR + steps validated on 2 workouts):
//   yoga     `06 03 07 04 00` → avg 75 / max 81, ~0 steps (matched app exactly)
//   walk     `06 03 02 04 00` → avg 80 / max 90, 178 steps ≈ app's ~170
//
//   0x4e frame: `4e <cursor:4 BE> <hr> <steps> <misc…> <xor>`
//     [0]      = 0x4e opcode
//     [1..<5]  = cursor (sync-epoch seconds, big-endian) — the interval's end time
//     [5]      = HR bpm
//     [6]      = steps taken in this interval
//     [7..<12] = perfusion/quality (undecoded, not needed)
//     [last]   = XOR trailer
//
// Calories, the 5 HR zones and distance are NOT on the wire — the app computes them.
// See ../docs/PROTOCOL.md §4.

/**
 * The 7 workout types the RingConn app drives via `06 03 <type>` (🟢 all captured; these are the only
 * workouts the app offers). The type byte tunes the ring's own HR sampling for the activity; the
 * user-facing Health Connect exercise type is chosen independently. [rawValue] is the wire byte.
 */
enum class SportType(val rawValue: Int, val displayName: String) {
    OUTDOOR_RUNNING(0x01, "Outdoor Running"),
    OUTDOOR_WALKING(0x02, "Outdoor Walking"),
    INDOOR_RUNNING(0x03, "Indoor Running"),
    OUTDOOR_CYCLING(0x04, "Outdoor Cycling"),
    INDOOR_CYCLING(0x05, "Indoor Cycling"),
    INDOOR_ROWING(0x06, "Indoor Rowing"),
    YOGA(0x07, "Yoga"),
}

object SportFrame {

    /** Sport-stream opcode, ring → host. */
    private const val OPCODE = 0x4e

    /** One decoded sample from a ring→host `0x4e` sport-stream frame. */
    data class Sample(
        /** HR in bpm, or null if outside [LiveHR.VALID_BPM] (warm-up sentinel / decode artifact). */
        val hr: Int?,
        /** Steps taken in this ~10 s interval (byte[6]). */
        val steps: Int,
        /**
         * Interval-end cursor: seconds since the sync epoch (2019-12-31 12:00 UTC, `Command.SYNC_EPOCH`),
         * big-endian unsigned 32-bit. `Long` because a Kotlin `Int` can't hold values ≥ 2³¹ (ADR E1 D1 A).
         */
        val cursor: Long,
    )

    /**
     * Decode a `0x4e` sport-stream frame. Validates the XOR trailer first, then extracts HR (byte[5],
     * gated to [LiveHR.VALID_BPM]) and steps (byte[6]). Returns null for any non-`0x4e` / too-short /
     * bad-checksum frame — never fabricates a sample from a corrupt frame.
     */
    fun decode(payload: ByteArray): Sample? {
        if (payload.size < 8 || payload.u8(0) != OPCODE || !Frame.isValid(payload)) return null
        val cursor = (payload.u8(1).toLong() shl 24) or (payload.u8(2).toLong() shl 16) or
            (payload.u8(3).toLong() shl 8) or payload.u8(4).toLong()
        val hrByte = payload.u8(5)
        val hr = if (hrByte in LiveHR.VALID_BPM) hrByte else null
        return Sample(hr = hr, steps = payload.u8(6), cursor = cursor)
    }
}
