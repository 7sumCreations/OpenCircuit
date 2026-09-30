package io.github.opencircuit.ringkit

// Live-sample decode. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/LiveHR.swift:11-80 (@ b1c2fdd), itself ported from
// desktop/opencircuit/framing.py.
//
// The `0x95` poll yields a `0x15` frame in one of two shapes (PROTOCOL.md §5.1):
//   • HR mode (`06 01 00`):   `15 00 <hr> 0a b0 <xor>`  → byte[2] = HR bpm 🟢
//   • SpO2 mode (`06 02 00`): `15 01 … <spo2> …`        → byte[14] = SpO2 % 🟡
// HR is verified (HR-only capture settled to 61 bpm resting); the first HR sample is a warm-up
// sentinel (byte[2] ≈ 8). SpO2 byte[14] matches the app's 96–97 % live.
//
// Every decoder returns null on a failed guard and never throws (ADR E1 — fail closed).

object LiveHR {
    /** Live-sample response opcode, ring → host (reply to the `0x95` poll). */
    private const val OPCODE = 0x15

    /** Below this, the sensor hasn't locked on (warm-up); treat as not-yet-valid. */
    const val MIN_VALID_BPM = 30

    /**
     * Upper physiological ceiling for a single HR reading. Above this is a decode artifact, not a
     * real beat rate (the zone/max-HR formula tops out at 220 = 220 − age 0).
     */
    const val MAX_VALID_BPM = 220

    /**
     * The plausible band a decoded HR must fall in to be treated as a real reading. Shared by the
     * live decoder, `SportFrame`, AND (from E2) the history/sleep-vitals decoder, so a garbage epoch
     * (e.g. byte[4] == 4, the cause of the impossible "Resting HR 4 bpm") can never become a
     * persisted sample. The single home of this rule (I3).
     */
    val VALID_BPM: IntRange = MIN_VALID_BPM..MAX_VALID_BPM

    /**
     * HR in bpm from a SHORT `15 00 <hr> …` frame, or null (incl. long `15 01` frames, whose
     * byte[2] is 0 — they carry SpO2, not HR).
     */
    fun decode(payload: ByteArray): Int? {
        if (payload.size < 4 || payload.u8(0) != OPCODE || payload.u8(1) != 0x00) return null
        return payload.u8(2)
    }

    /**
     * HR only once the sensor has locked on AND within the plausible band (filters the warm-up
     * sentinel ≈ 8 and any over-ceiling decode artifact).
     */
    fun decodeLocked(payload: ByteArray): Int? {
        val hr = decode(payload) ?: return null
        return if (hr in VALID_BPM) hr else null
    }

    // Settling a user-facing reading

    /**
     * Locked frames required before a live HR is shown or persisted as a finished reading.
     *
     * 🟢 MEASURED, from the only real poll capture upstream holds (FR02.018, `RingKitVerify`'s
     * `realHRFrames`): ONE user read yields the locked sequence 82, 84, 88, 90, 91, 66, 61 — a
     * 30 bpm spread inside a single measurement, after the warm-up sentinel (8) is filtered. A
     * display that renders whichever frame arrived last is a coin flip across that spread, which is
     * what a tester saw as an "abnormally low" manual reading.
     *
     * 5 is chosen from the poll cadence, not fitted: live monitoring polls every 2 s, so 5 frames
     * is ~10 s of measurement, and a 5-sample median has a 2-sample breakdown point, so a single
     * sensor dropout cannot move it. The session's live-HR trend retains 12, so the window always
     * fits.
     *
     * ⚠️ This matters far more on Gen 2 Air (FR04) than on Gen 2: upstream measured 0.0–0.5 % of
     * epochs outside [VALID_BPM] on FR02.018 captures against 0.4–6.7 % on FR04.009 captures.
     */
    const val SETTLE_SAMPLE_COUNT = 5

    /**
     * The settled HR for a user-facing read: the median of the last [SETTLE_SAMPLE_COUNT] locked
     * frames, or null while fewer than that have arrived (the caller should still say "measuring…").
     *
     * The median, not the mean: it discards a symmetric pair of outliers outright rather than
     * averaging them in. Pass a trend holding only values that already passed [decodeLocked] — this
     * function does NOT re-filter.
     */
    fun settled(trend: List<Int>): Int? {
        if (trend.size < SETTLE_SAMPLE_COUNT) return null
        val window = trend.takeLast(SETTLE_SAMPLE_COUNT).sorted()
        return window[window.size / 2]
    }

    /**
     * SpO2 % from a long SpO2-mode frame `15 01 … <spo2> …` (byte[14]), or null. 🟡
     * Single-window measurement, not multi-sample ground-truthed — render with an "est." caveat in
     * UI to indicate lower confidence (upstream #59).
     */
    fun decodeSpO2(payload: ByteArray): Int? {
        if (payload.size < 15 || payload.u8(0) != OPCODE || payload.u8(1) != 0x01) return null
        val spo2 = payload.u8(14)
        return if (spo2 in 70..100) spo2 else null
    }
}
