package io.github.opencircuit.ringkit

// One 23-byte record of the `0x4c` bulk activity/sleep history stream. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/BulkSleep.swift:37-366 (@ b1c2fdd).
//
// Ground truth is ../docs/PROTOCOL.md §5.3 (confirmed on FW FR02.018 against the RingConn app's
// own readout for the 2026-06-13 night). The counter is seconds since `Command.SYNC_EPOCH`;
// records step 150 s, so each record is one 2.5-min epoch.
//
// Two record layouts, keyed on byte [8]:
//   • Sleep-vitals epoch: [4]=HR bpm 🟢, [5]=HRV/RMSSD ms 🟢, [6]=confidence 🟢, [7]=RR×8 🟢,
//     [8]=SpO2 % 🟢, [10:20]=acti_counts.
//   • Activity/awake epoch ([8]=0x12/0x13, or the 0x11 "nothing measured" sentinel): [4]=HR bpm
//     🟢 (the all-day HR), [10:20]=acti_counts. Bytes [5] and [7] are real HRV and RR here too,
//     but only the HealthKit sample path may use them (`measuredHRVRMSSD` /
//     `measuredRespiratoryRate`); the strict accessors stay the "ring is measuring sleep" signal.
//
// Value-type notes: Swift's `[UInt8]` copies on assignment and the failable `init?` rejects any
// length but 23. Here the record keeps a private copy of its bytes, hands out a fresh copy on
// every read, compares by content, and exists only through [BulkRecord.of]. Every byte is read
// unsigned through `u8()` — the real frames are full of bytes ≥ 0x80.

import java.time.Instant

/** One 23-byte record from a `0x4c` bulk activity/sleep page (PROTOCOL.md §5.3). */
class BulkRecord private constructor(private val bytes: ByteArray) {

    companion object {
        /** Bytes per record (🟢). The same fact as the epoch-page split size. */
        const val LENGTH = EpochRecord.ACTIVITY_RECORD_SIZE

        /** Counter step between consecutive records: 0x96 = 150 s (🟢). */
        const val EPOCH_SECONDS = 150

        /**
         * A record over a private copy of [bytes], or null unless [bytes] is exactly [LENGTH] long
         * (upstream's failable initializer).
         */
        fun of(bytes: ByteArray): BulkRecord? = if (bytes.size == LENGTH) BulkRecord(bytes.copyOf()) else null

        /**
         * Physiological RMSSD ceiling (ms) for an EMITTED HRV sample. 🟢 MEASURED upstream: the max
         * byte[5] over every worn epoch of every archive is exactly 200, so this rejects only the
         * never-observed 201…255 byte space. A byte-garbage backstop, not the motion discriminator.
         */
        internal const val MAX_PLAUSIBLE_HRV_MS = 200

        /**
         * Plausible adult respiratory-rate band (brpm) for an EMITTED sample. 🟢 MEASURED upstream:
         * every byte[7] > 0 epoch sits in 12.625…17.625 brpm, so this rejects only raw 1…31 and
         * 241…255 — sentinel / stuck-byte space. The ceiling is reachable inside a byte (255/8).
         */
        internal val PLAUSIBLE_RESPIRATORY_RATE: ClosedFloatingPointRange<Double> = 4.0..30.0

        /** Clinically plausible SpO2 band (%) for an emitted reading. */
        private val PLAUSIBLE_SPO2 = 70..100
    }

    /** The raw 23 bytes. A fresh copy on every read. */
    val raw: ByteArray get() = bytes.copyOf()

    /** `[0:4]` big-endian counter — seconds since `Command.SYNC_EPOCH`. Unsigned 32-bit, held in a `Long`. */
    val counter: Long
        get() = (bytes.u8(0).toLong() shl 24) or (bytes.u8(1).toLong() shl 16) or
            (bytes.u8(2).toLong() shl 8) or bytes.u8(3).toLong()

    /** Wall-clock time of this epoch (counter + epoch offset). */
    fun date(epoch: Long = Command.SYNC_EPOCH): Instant = Instant.ofEpochSecond(counter + epoch)

    enum class Layout {
        /** Unworn / no measurement: motion 01×5 and zero payload. */
        IDLE,

        /** [8] is an SpO2 %; HR/HRV/SpO2 live in [4:9]. */
        SLEEP_VITALS,

        /**
         * No SpO2 read in this epoch: [8] is a sentinel (0x12/0x13 awake-active, 0x11 the ring's
         * "nothing measured" block terminator), NOT a percentage.
         */
        ACTIVITY,
    }

    /**
     * Decided from STRUCTURE, never from the SpO2 value: a genuine desaturation below 87 % must
     * stay sleep-vitals so its HR/HRV/SpO2 survive.
     *  • idle/unworn template (🟢 §5.3): [4:8]=05 00 0c 00, [9]=0a, motion 01×5, [15:22]=00×7. The
     *    FULL template matters — a deep-sleep epoch is also still with a zero tail but carries
     *    real HR/HRV in [4:8].
     *  • activity/awake epoch (🟢 §5.3): [8] tag 0x12 / 0x13 / 0x11.
     * Anything else is sleep-vitals. 0x11 is a "no SpO2 here" sentinel (every corpus 0x11 record
     * carries the unmeasured-HR byte 0x04); the other impossible SpO2 bytes (0x00 / 0x0a / 0x0e)
     * are deliberately left as sleep-vitals epochs whose SpO2 read failed — [spo2Percent]'s band
     * keeps their value out of a sample.
     */
    val layout: Layout
        get() {
            if (bytes.u8(4) == 0x05 && bytes.u8(5) == 0x00 && bytes.u8(6) == 0x0c && bytes.u8(7) == 0x00 &&
                bytes.u8(9) == 0x0a &&
                (10 until 15).all { bytes.u8(it) == 1 } &&
                (15 until 22).all { bytes.u8(it) == 0 }
            ) {
                return Layout.IDLE
            }
            val tag = bytes.u8(8)
            if (tag == 0x12 || tag == 0x13 || tag == 0x11) return Layout.ACTIVITY
            return Layout.SLEEP_VITALS
        }

    /**
     * Heart rate in bpm — `[4]` on ANY worn epoch (🟢): the all-day HR, carried by sleep-vitals
     * AND activity epochs alike. Null for the idle template and for any byte outside
     * [LiveHR.VALID_BPM], which stops a garbage epoch (the impossible "Resting HR 4 bpm") from
     * becoming a sample.
     */
    val heartRate: Int?
        get() {
            if (layout == Layout.IDLE) return null
            val hr = bytes.u8(4)
            return if (hr in LiveHR.VALID_BPM) hr else null
        }

    /**
     * HRV in ms — `[5]` on a sleep-vitals epoch (🟢). The ring reports RMSSD.
     *
     * ⚠️ This is a MODE SIGNAL as well as a value: non-null means "the ring emitted a sleep-vitals
     * template here", and sleep detection, staging, naps and stress read it that way. It therefore
     * deliberately does NOT recover the HRV activity epochs also carry — that is
     * [measuredHRVRMSSD], for the sample path only.
     */
    val hrvRMSSD: Int?
        get() {
            if (layout != Layout.SLEEP_VITALS) return null
            val v = bytes.u8(5)
            return if (v > 0) v else null
        }

    /**
     * SpO2 percent — `[8]` on a sleep-vitals epoch (🟢), guarded to a clinically plausible band
     * (70…100). Genuine desaturations below the healthy range are admitted; the HR/HRV of a
     * sub-70 epoch still decode — only the implausible SpO2 reading is dropped.
     */
    val spo2Percent: Int?
        get() {
            if (layout != Layout.SLEEP_VITALS) return null
            val v = bytes.u8(8)
            return if (v in PLAUSIBLE_SPO2) v else null
        }

    /**
     * Respiratory rate in breaths/min — `[7]` ÷ 8 on a sleep-vitals epoch (🟢, ground-truthed
     * against the app's nightly average). Sleep-vitals-scoped like [hrvRMSSD]; the activity-epoch
     * RR is [measuredRespiratoryRate].
     */
    val respiratoryRate: Double?
        get() {
            if (layout != Layout.SLEEP_VITALS) return null
            val v = bytes.u8(7)
            return if (v > 0) v / 8.0 else null
        }

    // Measured vitals — HealthKit sample emission ONLY. These answer "did the ring MEASURE this
    // value on this epoch, whichever template carried it?" and are consumed by `BulkSleep.samples`
    // and nothing else. They stay internal so no sleep-pipeline consumer outside this module can
    // reach them.

    /**
     * HRV in ms the ring MEASURED on this epoch, on either template. Sleep-vitals epochs pass as
     * [hrvRMSSD] does (band-guarded to [MAX_PLAUSIBLE_HRV_MS]); activity epochs must ADDITIONALLY
     * be quiet ([motionIntensityTailIsZero]) — a high RMSSD on a moving wrist is PPG artifact.
     * The gate is deliberately asymmetric: applying it to sleep-vitals epochs would drop about a
     * third of the HRV samples upstream ships.
     */
    internal val measuredHRVRMSSD: Int?
        get() {
            if (layout == Layout.IDLE) return null
            val v = bytes.u8(5)
            if (v <= 0 || v > MAX_PLAUSIBLE_HRV_MS) return null
            if (layout != Layout.SLEEP_VITALS && !motionIntensityTailIsZero) return null
            return v
        }

    /**
     * RR in brpm the ring MEASURED on this epoch, on either template: `[7]` ÷ 8 on any worn epoch,
     * band-guarded to [PLAUSIBLE_RESPIRATORY_RATE]. Motion does not corrupt this field, so unlike
     * HRV there is no quiet gate.
     */
    internal val measuredRespiratoryRate: Double?
        get() {
            if (layout == Layout.IDLE) return null
            val v = bytes.u8(7)
            if (v <= 0) return null
            val rr = v / 8.0
            return if (rr in PLAUSIBLE_RESPIRATORY_RATE) rr else null
        }

    /** `[10:15]` — 5 per-30 s motion/activity counts (🟢 role). `01` baseline = still. A fresh copy on every read. */
    val motion: ByteArray get() = bytes.copyOfRange(10, 15)

    /**
     * True when `[10:15]` is a CONSTANT run on a worn epoch — the ring's "no fine-motion recorded"
     * placeholder (🟢). Keys on the structure (zero intra-epoch variation), never a specific value:
     * the placeholder level is device- and posture-dependent. Layout-agnostic on purpose; the idle
     * template is excluded.
     */
    val motionIsPlaceholder: Boolean
        get() {
            if (layout == Layout.IDLE) return false
            val first = bytes.u8(10)
            return (11 until 15).all { bytes.u8(it) == first }
        }

    /**
     * True when the five 30-s sub-samples all sit within [ActivityPeriod.MOTION_STILL_THRESHOLD]
     * of one another — the epoch on its own cannot express movement. [motionIsPlaceholder] is the
     * zero-spread special case.
     */
    internal val motionResolvesStillness: Boolean
        get() {
            if (layout == Layout.IDLE) return false
            val m = (10 until 15).map { bytes.u8(it) }
            return (m.max() - m.min()).toFloat() <= ActivityPeriod.MOTION_STILL_THRESHOLD
        }

    /**
     * True when `[15:20]` is all zero — the ring's OWN "nothing moved in this epoch" verdict.
     *
     * ⚠️ Byte-aligned while the field is not: `[15:23)` is five 12-bit magnitudes, so this window
     * cannot see magnitudes 3 and 4. It is kept EXACTLY as it is on purpose — three shipped
     * calibrations (the degenerate-motion fractions, the quiet gate of [measuredHRVRMSSD] and the
     * HRV pooling noise floor) are fitted to this predicate's population. Use
     * [activityMagnitudesAreZero] for new work.
     */
    internal val motionIntensityTailIsZero: Boolean get() = (15 until 20).all { bytes.u8(it) == 0 }

    /**
     * `[15:23)` decoded: five 12-bit big-endian magnitudes, nibble-packed (🟢 bit layout, measured
     * upstream). Magnitude k is nibbles 3k, 3k+1, 3k+2 counting from the high nibble of `[15]`;
     * the leftover low nibble of `[22]` is [activityInfoNibble]. Each value is 0..4095.
     * 🔴 What the five numbers physically are is NOT established.
     */
    val activityMagnitudes: List<Int>
        get() {
            fun nibble(i: Int): Int {
                val b = bytes.u8(15 + i / 2)
                return if (i % 2 == 0) b shr 4 else b and 0x0f
            }
            return (0 until 5).map { (nibble(it * 3) shl 8) or (nibble(it * 3 + 1) shl 4) or nibble(it * 3 + 2) }
        }

    /** The 4-bit `info` flag that closes the `[15:23)` block — the low nibble of `[22]` (0..15). */
    val activityInfoNibble: Int get() = bytes.u8(22) and 0x0f

    /**
     * The layout-correct counterpart of [motionIntensityTailIsZero]: every 12-bit magnitude is
     * zero. Upstream wires it only behind a motion-channel flag that ships off.
     */
    internal val activityMagnitudesAreZero: Boolean get() = activityMagnitudes.all { it == 0 }

    /**
     * Confidence / signal quality, `[6]` (🟢 named, range ~0…12). Decoded but not consumed by any
     * analytic. Null on the idle template.
     */
    val confidence: Int?
        get() = if (layout == Layout.IDLE) null else bytes.u8(6)

    /** `[10:20]` — the FULL 10-byte `acti_counts` blob (🟢 role). A fresh copy on every read. */
    val activityCounts: ByteArray get() = bytes.copyOfRange(10, 20)

    /**
     * `[15:20]` — the intensity half of [activityCounts]: zero while still, carries measured
     * activity when some firmware sessions replace `[10:15]` with a constant filler. A fresh copy
     * on every read.
     */
    val motionIntensityTail: ByteArray get() = bytes.copyOfRange(15, 20)

    override fun equals(other: Any?): Boolean = other is BulkRecord && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String =
        "BulkRecord(${bytes.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }})"
}
