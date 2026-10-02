package io.github.opencircuit.ringkit

// Turn a BLE link RSSI into a human-facing "how close is my ring" estimate — the Find My Ring screen.
// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/RingProximity.swift (@ b1c2fdd), whole.
//
// The official app shows an approximate Bluetooth distance ("~3 ft") plus a locate-by-LED control; this
// mirrors the distance readout from the live RSSI of the already-connected ring.
//
// RSSI → distance is intrinsically noisy — multipath, the wearer's body and antenna orientation swing it
// several dBm — so the QUALITATIVE band is the primary signal and the distance is a rough hint,
// deliberately coarse. Distance uses the standard log-distance path-loss model:
//     d = 10^((txPower − rssi) / (10·n))
// where txPower = the RSSI you'd read at 1 m and n = the environmental path-loss exponent.
//
// Shape notes. `pow` is `StrictMath.pow`, so the distance does not depend on the platform's libm. The
// display strings are upstream's, verbatim. One deliberate difference: a non-negative RSSI (127 is the
// Bluetooth "RSSI not available" value) gives an empty dial, agreeing with the "searching" band, where
// upstream shows a full one.

object RingProximity {

    /**
     * Reference RSSI (dBm) at 1 m for the RingConn link — an empirical BLE ballpark (small low-power antenna,
     * body-worn). Not ring-calibrated; only anchors the rough distance curve.
     */
    const val TX_POWER_AT_1M: Double = -59.0

    /** Path-loss exponent: 2.0 in free space, ~2.5–3.0 indoors with a body in the path. 2.5 splits it. */
    const val PATH_LOSS_EXPONENT: Double = 2.5

    /** Coarse proximity buckets — the reliable part of the estimate. Contiguous, no gaps; weakest first. */
    enum class Band {
        /** No or very weak signal. */
        SEARCHING,
        FAR,
        NEARBY,
        CLOSE,
        VERY_CLOSE,
        ;

        /** The short label the screen shows. */
        val label: String
            get() = when (this) {
                SEARCHING -> "Searching…"
                FAR -> "Far"
                NEARBY -> "Nearby"
                CLOSE -> "Close"
                VERY_CLOSE -> "Very close"
            }
    }

    /**
     * Map a (smoothed) RSSI to a band. null, a non-negative value (the 127 "not available" sentinel), or
     * anything below −95 dBm all read as searching.
     */
    fun band(rssi: Int?): Band {
        if (rssi == null || rssi >= 0) return Band.SEARCHING
        return when {
            rssi >= -55 -> Band.VERY_CLOSE
            rssi >= -68 -> Band.CLOSE
            rssi >= -80 -> Band.NEARBY
            rssi >= -95 -> Band.FAR
            else -> Band.SEARCHING // below −95 dBm
        }
    }

    /**
     * Approximate distance in metres from RSSI via the path-loss model; null when there's no usable signal
     * (missing, a non-negative sentinel, or −100 dBm and weaker, where the estimate is meaningless).
     */
    fun approximateMeters(rssi: Int?): Double? {
        if (rssi == null || rssi >= 0 || rssi <= -100) return null
        val exponent = (TX_POWER_AT_1M - rssi.toDouble()) / (10.0 * PATH_LOSS_EXPONENT)
        return StrictMath.pow(10.0, exponent)
    }

    /** Approximate distance in feet; null when there's no usable signal. */
    fun approximateFeet(rssi: Int?): Double? = approximateMeters(rssi)?.let { it * 3.280839895 }

    /**
     * A short display string for the distance hint — "Right here" up close, "≈ N ft" in between, and
     * "≈ 20+ ft" past the point the estimate is trustworthy. null when there's no signal.
     */
    fun distanceText(rssi: Int?): String? {
        val feet = approximateFeet(rssi) ?: return null
        if (feet < 1.5) return "Right here"
        if (feet >= 20) return "≈ 20+ ft"
        return "≈ ${roundHalfAwayFromZero(feet).toLong()} ft"
    }

    /**
     * Signal strength as a 0…1 fraction for a meter or dial, mapping −95…−45 dBm linearly onto 0…1. Empty
     * (0) with no reading, and for a non-negative one, which [band] reads as searching (upstream clamps a
     * non-negative reading to −45 dBm and shows a full dial).
     */
    fun signalFraction(rssi: Int?): Double {
        if (rssi == null || rssi >= 0) return 0.0
        val clamped = minOf(-45, maxOf(-95, rssi)).toDouble()
        return (clamped + 95.0) / 50.0
    }
}
