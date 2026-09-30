package io.github.opencircuit.ringkit

// Auto-measure wear gate. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/AutoMeasureGate.swift:20-59 (@ b1c2fdd).
//
// The ring reports HR/SpO₂ only on demand, so the app periodically enters a brief live read to
// refresh them. On the charger / off the wrist the ring never locks a reading, so a fixed-cadence
// probe just times out every ~10 min and drains both the ring and the phone. This infers "not worn"
// from PROVEN proxies and backs the probe off exponentially, resuming the normal cadence the instant
// a reading locks (or the skin temperature reads warm) again.
//
// Proxies, honest about confidence:
//   • consecutive auto-measures that never lock a reading (🟢 — the absence of ANY lock over several
//     attempts is strong evidence the ring isn't on a finger);
//   • the raw skin-temperature descriptor (🟡 — a worn Gen 2 reads ≳28 °C; off-wrist it falls toward
//     room ambient ~20–24 °C). Used only to ACCELERATE the inference and to clear it on re-wear.
// It deliberately consults NO charging-flag byte — that descriptor field is undecoded, so we never
// CLAIM to know the ring is charging.

import java.time.Duration

object AutoMeasureGate {

    /**
     * Consecutive auto-measure cycles with no lock after which the ring is inferred NOT WORN when
     * there's no temperature signal to lean on (🟢 fallback). With a cold reading a single miss
     * already confirms it (see [appearsNotWorn]).
     *
     * iOS-tuned — re-check in E9/E11. Ported verbatim from upstream (`:24`).
     */
    const val NOT_WORN_AFTER_FAILURES: Int = 2

    /**
     * Hard ceiling on how many times the base interval is doubled while not worn (base × 2ⁿ).
     *
     * iOS-tuned — re-check in E9/E11. Ported verbatim from upstream (`:27`).
     */
    const val MAX_BACKOFF_DOUBLINGS: Int = 4

    /**
     * Whether the ring appears NOT WORN, from the consecutive no-lock count and (optionally) the
     * most recent raw skin temperature:
     * - a warm reading (≥ [wornMinC]) is direct evidence of wear → not-worn = false;
     * - a cold reading needs only ONE failed lock to confirm not-worn (never on a cold reading alone —
     *   a worn-but-cool ring would have LOCKED that one probe);
     * - with no temperature signal, fall back to [NOT_WORN_AFTER_FAILURES] consecutive misses.
     */
    fun appearsNotWorn(
        consecutiveNoLock: Int,
        rawSkinTempC: Double? = null,
        wornMinC: Double = ActivityPeriod.WORN_MIN_TEMPERATURE_C,
    ): Boolean {
        if (rawSkinTempC != null) {
            if (rawSkinTempC >= wornMinC) return false // warm skin ⇒ worn (🟡, direct)
            return consecutiveNoLock >= 1              // cold + a missed lock ⇒ not worn
        }
        return consecutiveNoLock >= NOT_WORN_AFTER_FAILURES // no temperature ⇒ 🟢 failed-lock fallback
    }

    /**
     * Next interval between auto-measure cycles. While worn (or not yet inferred not-worn) this is
     * [base]; once not worn it doubles per additional consecutive miss, capped at both
     * `base × 2^MAX_BACKOFF_DOUBLINGS` and [cap]. A lock (consecutiveNoLock → 0) or a warm reading
     * drops it straight back to [base].
     */
    fun interval(
        base: Duration,
        cap: Duration,
        consecutiveNoLock: Int,
        rawSkinTempC: Double? = null,
        wornMinC: Double = ActivityPeriod.WORN_MIN_TEMPERATURE_C,
    ): Duration {
        if (!appearsNotWorn(consecutiveNoLock, rawSkinTempC, wornMinC)) return base
        val doublings = (consecutiveNoLock - 1).coerceIn(0, MAX_BACKOFF_DOUBLINGS)
        return minOf(base.multipliedBy(1L shl doublings), cap)
    }
}
