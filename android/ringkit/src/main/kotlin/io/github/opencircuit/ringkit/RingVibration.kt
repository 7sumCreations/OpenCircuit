package io.github.opencircuit.ringkit

// Ring vibration motor (Gen 3) — patterns and the model gate. The frame builder is
// `Command.vibrate` in Opcodes.kt. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/RingVibration.swift:45-86 (@ b1c2fdd).
//
// PROVENANCE (../docs/PROTOCOL.md §5.9): the motor opcode is NOT in the RingConn APK — it was
// recovered from a live HCI snoop of a Gen 3 ring (FR05.011, official app 4.3.1) and confirmed by
// driving it ourselves. 🟢 12 buzzes for 12 attempts:
//     0b 03 01 64 00 → short-short-long · 0b 03 01 32 00 → same · 0b 03 01 0a 00 → same
//     0b 03 02 64 00 → one long buzz
// The ring answers `8b 00 8b`. Like all commands it is NOT XOR-checksummed.
//   [0] 0x0b opcode · [1] 0x03 subcommand (constant) · [2] PATTERN selector 🟢
//   [3] 🟢 MEASURED INERT (not intensity, not duration; 0x64/0x32/0x0a felt identical). We send
//       0x64, exactly what the official app sends, and never vary it. 0x00 is untested.
//   [4] 0x00 terminator.
// ⚠️ There is no "stop" command and no acknowledgement that the motor ran: `8b 00 8b` means the
// frame was accepted, not that the wearer felt anything. Treat delivery as unverified.

/**
 * The two motor patterns the ring firmware implements. There is no third — every frame the
 * official app was seen to send used `[2]` ∈ {0x01, 0x02}, and nothing else has been probed.
 * [rawValue] is the pattern byte (0..255).
 */
enum class VibrationPattern(val rawValue: Int) {
    /** Three pulses: short, short, long. The official app's reminder / measurement-end buzz. 🟢 */
    NOTIFICATION(0x01),

    /** One sustained buzz. The official app uses it to mark the start of a workout. 🟢 */
    LONG(0x02),
    ;

    /** Short user-facing name for the settings screen. */
    val displayName: String
        get() = when (this) {
            NOTIFICATION -> "Triple pulse"
            LONG -> "Single long buzz"
        }

    /** How it actually feels, for a picker where the user can't preview without a ring on. */
    val displayDetail: String
        get() = when (this) {
            NOTIFICATION -> "Short, short, long — the pattern RingConn uses for reminders."
            LONG -> "One sustained buzz — RingConn's workout-start signal."
        }
}

object RingVibration {
    /** The inert third byte, sent verbatim. See the provenance note above before changing it. */
    const val INTENSITY_BYTE = 0x64

    /**
     * Whether this ring has a motor we can drive. Gen 3 ONLY, deliberately narrow: no Gen 1, Gen 2
     * or Gen 2 Air has ever been sent `0x0b`. [RingGeneration.UNKNOWN] (before the DIS firmware read
     * lands) is excluded too — the gate fails CLOSED, hiding a feature rather than showing a dead
     * button (PL-2026-09-30-a).
     */
    fun isSupported(generation: RingGeneration): Boolean = generation == RingGeneration.GEN3
}
