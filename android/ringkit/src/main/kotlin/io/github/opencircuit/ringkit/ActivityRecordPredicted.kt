package io.github.opencircuit.ringkit

// PREDICTED decoder for the still-uncaptured "history ACTIVITY response" record
// (../docs/PROTOCOL.md §5.3.1). Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/ActivityRecordPredicted.swift:24-74 (@ b1c2fdd).
//
// This is a DIFFERENT record from the `0x4c` MEASUREMENT record `BulkRecord` decodes, and it has
// never been observed on the wire. The layout is PREDICTED with the `wire_index = APK_loc - 3`
// convention that landed five fields of the measurement record byte-for-byte, ported 1:1 from
// upstream's desktop/decode_activity.py. Every field is 🔴 unconfirmed until a real capture proves
// it. Do NOT wire this into the sample path, Health Connect or any UI before then — on today's
// measurement records it returns implausible values BY DESIGN (see [isPlausible]).
//
// Distance is deliberately not a field: the app computes it client-side from steps.
//
// Value-type notes: Swift `UInt8` fields are `Int` 0–255 here, range-checked at construction; the
// three `item5p0` bytes are an immutable `List<Int>` copied from the constructor argument.

import java.time.Instant

/** Predicted decode of a 23-byte history-activity record (🔴 unconfirmed — see file header). */
class ActivityRecordPredicted internal constructor(
    val date: Instant,
    /** 🔴 per-epoch step count (LE). */
    val steps: Int,
    /** 🔴 wear/charge state enum (0–255). */
    val deviceState: Int,
    /** 🔴 per-epoch battery % (0…100 if the prediction is right; 0–255 on the wire). */
    val powerLevel: Int,
    /** 🔴 four per-epoch skin-temp samples — units/scale unconfirmed. */
    val temp1: Int,
    val temp2: Int,
    val temp3: Int,
    val temp4: Int,
    item5p0: List<Int>,
    /** 🔴 active seconds this epoch — predicted bound 0…150 (the epoch length). */
    val activeSeconds: Int,
    /** 🔴 stand/active flag for this epoch (0–255). */
    val dailyActiveFlag: Int,
) {
    /** 🔴 three unidentified small integers, each 0–255. An immutable copy. */
    val item5p0: List<Int> = item5p0.toList()

    init {
        require(deviceState in 0..0xFF) { "deviceState must be a byte 0..255: $deviceState" }
        require(powerLevel in 0..0xFF) { "powerLevel must be a byte 0..255: $powerLevel" }
        require(dailyActiveFlag in 0..0xFF) { "dailyActiveFlag must be a byte 0..255: $dailyActiveFlag" }
        require(this.item5p0.size == 3 && this.item5p0.all { it in 0..0xFF }) { "item5p0 must be three bytes 0..255: $item5p0" }
    }

    companion object {
        /**
         * Decode a raw 23-byte record via the PREDICTED layout. Null unless [bytes] is exactly one
         * whole record. Mirrors upstream's desktop decode_activity_record_PREDICTED() exactly.
         */
        fun decode(bytes: ByteArray, epoch: Long = Command.SYNC_EPOCH): ActivityRecordPredicted? {
            if (bytes.size != BulkRecord.LENGTH) return null
            fun le16(i: Int): Int = bytes.u8(i) or (bytes.u8(i + 1) shl 8)
            val counter = (bytes.u8(0).toLong() shl 24) or (bytes.u8(1).toLong() shl 16) or
                (bytes.u8(2).toLong() shl 8) or bytes.u8(3).toLong()
            return ActivityRecordPredicted(
                date = Instant.ofEpochSecond(counter + epoch),
                steps = le16(4),
                deviceState = bytes.u8(6),
                powerLevel = bytes.u8(7),
                temp1 = le16(8),
                temp2 = le16(10),
                temp3 = le16(12),
                temp4 = le16(14),
                item5p0 = listOf(bytes.u8(16), bytes.u8(17), bytes.u8(18)),
                activeSeconds = le16(19),
                dailyActiveFlag = bytes.u8(21),
            )
        }
    }

    /**
     * Sanity bounds a genuine activity record should satisfy (`powerLevel <= 100`,
     * `activeSeconds <= 150`, `steps <= 5000`). Today's MEASUREMENT records fail it by design.
     */
    val isPlausible: Boolean get() = powerLevel <= 100 && activeSeconds <= 150 && steps <= 5000

    override fun equals(other: Any?): Boolean =
        other is ActivityRecordPredicted && date == other.date && steps == other.steps &&
            deviceState == other.deviceState && powerLevel == other.powerLevel &&
            temp1 == other.temp1 && temp2 == other.temp2 && temp3 == other.temp3 && temp4 == other.temp4 &&
            item5p0 == other.item5p0 && activeSeconds == other.activeSeconds && dailyActiveFlag == other.dailyActiveFlag

    override fun hashCode(): Int =
        listOf(date, steps, deviceState, powerLevel, temp1, temp2, temp3, temp4, item5p0, activeSeconds, dailyActiveFlag).hashCode()

    override fun toString(): String =
        "ActivityRecordPredicted(date=$date, steps=$steps, deviceState=$deviceState, powerLevel=$powerLevel, " +
            "temp=[$temp1, $temp2, $temp3, $temp4], item5p0=$item5p0, activeSeconds=$activeSeconds, " +
            "dailyActiveFlag=$dailyActiveFlag)"
}
