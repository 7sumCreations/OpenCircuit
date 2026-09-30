package io.github.opencircuit.ringkit

// Device-status decode — the 0x10 / 0x87 fixed 19-byte descriptor (PROTOCOL.md §5.4).
// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/DeviceStatus.swift:16-166 (@ b1c2fdd).
//
// The ring emits this frame spontaneously (~30–60 s) and as the `0x07`/`0xd0` response.
// Map of the 0x10/0x87 status model (matches the official app's device-status fields):
//   `[1]`     = ring battery %                                                            🟢
//   `[2]`     = state: 0x02/0x03 worn-stream toggle · 0x01 startup/settle · 0x04 ON CHARGER 🟢
//   `[4:6]`   = step count (16-bit BE) · `[6:10]` = skin temp (2 channels, 0.1 °C BE)     🟢
//   `[14:16]` = ring voltage mV, 16-bit BE (4001→4384 across a charge)                     🟢
//   `[17]`    = CASE byte: low 7 bits = case battery % · bit 0x80 = case charging ·
//               0xff = ring NOT in case (#89)                                               🟢
//
// Every byte is read through `u8`: the real frames carry bytes ≥ 0x80.
// Decoders return null for anything that fails a guard; they never throw.

/**
 * Skin temperature decoded from a 0x10/0x87 descriptor (PROTOCOL.md §5.4 🟢).
 * Two near-equal 16-bit channels (skin + reference); [celsius] is their mean.
 */
data class SkinTemperature(
    /** `[6:8]`, °C. */
    val channelA: Double,
    /** `[8:10]`, °C. */
    val channelB: Double,
) {
    val celsius: Double get() = (channelA + channelB) / 2
    val fahrenheit: Double get() = celsius * 9 / 5 + 32
}

object DeviceStatus {

    /** Descriptor frames are ≥ 19 bytes and start with `0x10` (spontaneous) or `0x87` (response). */
    private fun isDescriptor(frame: ByteArray): Boolean =
        frame.size >= 19 && (frame.u8(0) == 0x10 || frame.u8(0) == 0x87)

    /** 16-bit big-endian unsigned read at [i], [i]+1. */
    private fun be16(frame: ByteArray, i: Int): Int = (frame.u8(i) shl 8) or frame.u8(i + 1)

    /**
     * The ring's onboard step count from a 0x10/0x87 descriptor (`[4:6]`, 16-bit BE), or null if
     * the frame isn't one.
     *
     * 🟢 **Steps so far in the ring's CURRENT WALL-CLOCK QUARTER-HOUR**, cleared to 0 at every
     * `:00`/`:15`/`:30`/`:45` (#192). Not cumulative over the day — never read it as "today's steps".
     * `0` is legitimate and common. Fold it with `StepAccumulator` (a later epic).
     */
    fun steps(frame: ByteArray): Int? {
        if (!isDescriptor(frame)) return null
        return be16(frame, 4)
    }

    /**
     * Battery percentage from a 0x10/0x87 descriptor: `[1]` (§5.4 🟢, ground-truthed 2026-06-15).
     * Returns null if not a descriptor or outside the 1…100 band.
     */
    fun battery(frame: ByteArray): Int? {
        if (!isDescriptor(frame)) return null
        val pct = frame.u8(1)
        return if (pct in 1..100) pct else null
    }

    /**
     * Skin temperature from a 0x10/0x87 descriptor: two 0.1 °C big-endian channels at
     * `[6:8]`/`[8:10]` (§5.4 🟢). Returns null if the frame isn't a descriptor or either channel is
     * outside the plausible 15–50 °C band (filters zero/garbage frames); a cold, just-donned ring
     * still reads ~28 °C and is returned.
     */
    fun skinTemperature(frame: ByteArray): SkinTemperature? {
        if (!isDescriptor(frame)) return null
        val a = be16(frame, 6)
        val b = be16(frame, 8)
        if (a !in 150..500 || b !in 150..500) return null // 15–50 °C
        return SkinTemperature(channelA = a / 10.0, channelB = b / 10.0)
    }

    /**
     * 🟢 True when a 0x10/0x87 descriptor reports the ring is **on the charger** (`[2] == 0x04`,
     * PROTOCOL.md §5.4). Returns null if the frame isn't a descriptor.
     *
     * The real hardware signal that supersedes the [isCharging] battery-trend proxy: per-frame and
     * instant. Prefer it whenever a live descriptor frame is available.
     */
    fun isOnCharger(frame: ByteArray): Boolean? {
        if (!isDescriptor(frame)) return null
        return frame.u8(2) == 0x04
    }

    /**
     * 🟢 Ring battery voltage in millivolts from a 0x10/0x87 descriptor: `[14:16]`, 16-bit BE
     * (#89). Returns null if not a descriptor or outside the plausible single-cell band
     * 2500–4600 mV (filters zero/garbage frames).
     */
    fun batteryVoltageMillivolts(frame: ByteArray): Int? {
        if (!isDescriptor(frame)) return null
        val mv = be16(frame, 14)
        return if (mv in 2500..4600) mv else null
    }

    /** Charging-case state decoded from a 0x10/0x87 descriptor `[17]` (#89 🟢). */
    data class CaseBattery(
        /** Case battery percentage (0…100). */
        val percent: Int,
        /** True when the case itself is plugged in and charging (bit 0x80). */
        val isCharging: Boolean,
    )

    /**
     * 🟢 Charging-case battery from a 0x10/0x87 descriptor: byte `[17]` — low 7 bits = case battery
     * %, bit 0x80 = case charging. `0xff` is the sentinel for **ring not in the case** → null.
     * Also null if the frame isn't a descriptor or the percentage is implausible (> 100).
     */
    fun caseBattery(frame: ByteArray): CaseBattery? {
        if (!isDescriptor(frame)) return null
        val b = frame.u8(17)
        if (b == 0xff) return null // not docked in the case
        val pct = b and 0x7f
        if (pct > 100) return null // garbage guard
        return CaseBattery(percent = pct, isCharging = (b and 0x80) != 0)
    }

    /**
     * 🟡 Inferred wear state from the skin-temperature field of a 0x10/0x87 descriptor.
     *
     * A worn Gen-2 ring reads ~30–34 °C; off-wrist / on the charger it falls toward room ambient
     * (~20–24 °C). True when the mean of the two channels is at or above [wornMinC], false when
     * below, null when the frame isn't a descriptor or has no plausible temperature reading.
     * Labelled "inferred" / "likely" wherever it appears — never "confirmed".
     *
     * A miss (ring cold from just being put on) costs at most one unfiltered charger block; it
     * never *adds* spurious sleep.
     */
    fun isWorn(frame: ByteArray, wornMinC: Double = ActivityPeriod.WORN_MIN_TEMPERATURE_C): Boolean? {
        val temp = skinTemperature(frame) ?: return null
        return temp.celsius >= wornMinC
    }

    /**
     * 🟢 Inferred charging state from a rolling window of battery % readings — the **fallback** for
     * when no live descriptor frame is available (use [isOnCharger] whenever a frame is in hand).
     * Delegates to [ChargingInference.inferred]: ≥ 2 readings, strictly rising.
     */
    fun isCharging(batteryTrend: List<Int>): Boolean = ChargingInference.inferred(batteryTrend)
}
