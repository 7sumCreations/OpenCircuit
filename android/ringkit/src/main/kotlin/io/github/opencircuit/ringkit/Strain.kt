package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/Strain.swift (@ b1c2fdd),
// whole. Upstream ported it from openwhoop-algos/src/strain.rs.
//
// Port notes:
//  • `Strain` keeps upstream's two stored properties; its static members live in the companion. It
//    is a data class, so it compares by value (upstream's struct is not `Equatable`; this only adds).
//  • `log` is `StrictMath.log` (fdlibm), the same bits on every JVM and on Android; `rounded()` is
//    Swift's (`roundHalfAwayFromZero`).
//  • A timestamped sample's duration is the exact difference of its two instants, taken from
//    epoch seconds so no span between representable instants can overflow.
//
//   1. HR Reserve = maxHR - restingHR
//   2. classify each BPM sample into zone 1-5 by %HRR (else 0)
//   3. TRIMP = sum(sampleMinutes × zoneWeight)
//   4. strain = 21 × ln(TRIMP + 1) / ln(7201)   (24h at max HR → 7200 → 21)

import java.time.Instant

/** Edwards' zone-based TRIMP over heart-rate reserve, on the WHOOP 0…21 strain scale. */
data class Strain(val maxHR: Int, val restingHR: Int) {

    /**
     * Strain for a BPM series sampled every [sampleSeconds]; `null` with fewer than [MIN_READINGS]
     * readings or when `maxHR <= restingHR`. As `StrainCalculator::calculate`.
     */
    fun calculate(bpms: List<Int>, sampleSeconds: Double = 1.0): Double? {
        val trimp = edwardsTRIMP(bpms, maxHR = maxHR, restingHR = restingHR, sampleSeconds = sampleSeconds) ?: return null
        return trimpToStrain(trimp)
    }

    companion object {
        /** 10 minutes at 1 Hz — openwhoop's minimum before a strain score is meaningful. */
        const val MIN_READINGS: Int = 600
        internal const val MAX_STRAIN: Double = 21.0

        /** ln(7201), upstream's literal. */
        internal const val LN_7201: Double = 8.882_643_961_783_384

        /** Edwards zone weight (1-5) for one BPM sample, 0 below zone 1. */
        internal fun zoneWeight(bpm: Int, restingHR: Int, hrReserve: Double): Int {
            val pct = (bpm.toDouble() - restingHR.toDouble()) / hrReserve * 100.0
            return when {
                pct >= 90.0 -> 5
                pct >= 80.0 -> 4
                pct >= 70.0 -> 3
                pct >= 60.0 -> 2
                pct >= 50.0 -> 1
                else -> 0
            }
        }

        /** Raw Edwards zone-weighted TRIMP for a BPM series; a non-positive interval counts as one second. */
        fun edwardsTRIMP(bpms: List<Int>, maxHR: Int, restingHR: Int, sampleSeconds: Double = 1.0): Double? {
            if (bpms.size < MIN_READINGS || maxHR <= restingHR) return null
            val hrReserve = maxHR.toDouble() - restingHR.toDouble()
            val sampleMin = if (sampleSeconds <= 0) 1.0 / 60.0 else sampleSeconds / 60.0
            var acc = 0.0
            for (bpm in bpms) acc += sampleMin * zoneWeight(bpm, restingHR, hrReserve).toDouble()
            return acc
        }

        /** Raw Edwards zone-weighted TRIMP for timestamped samples; a sample with no positive duration counts as one second. */
        fun edwardsTRIMP(hrSamples: List<HRSample>, maxHR: Int, restingHR: Int): Double? {
            if (hrSamples.size < MIN_READINGS || maxHR <= restingHR) return null
            val hrReserve = maxHR.toDouble() - restingHR.toDouble()
            var acc = 0.0
            for (sample in hrSamples) {
                val duration = secondsBetween(sample.start, sample.end)
                val sampleMin = if (duration > 0) duration / 60.0 else 1.0 / 60.0
                acc += sampleMin * zoneWeight(sample.bpm, restingHR, hrReserve).toDouble()
            }
            return acc
        }

        internal fun trimpToStrain(trimp: Double): Double {
            if (!(trimp > 0)) return 0.0
            val raw = MAX_STRAIN * StrictMath.log(trimp + 1.0) / LN_7201
            return roundHalfAwayFromZero(raw * 100.0) / 100.0
        }

        /** `end` minus `start` in seconds, as upstream's `timeIntervalSince`; never overflows. */
        private fun secondsBetween(start: Instant, end: Instant): Double =
            (end.epochSecond - start.epochSecond).toDouble() + (end.nano - start.nano).toDouble() / 1e9
    }
}
