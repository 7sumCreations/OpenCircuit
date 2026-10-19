package io.github.opencircuit.health

import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import java.time.Instant

/**
 * A stable content signature of a night's staged segments. Port of upstream
 * ios/OpenCircuit/Health/HealthKitWriter.swift `sleepSignature` (:2373-2397) and `sleepValue`
 * (:2399-2407) @ b1c2fdd: FNV-1a 64 over the segments in total order (start, end, stage), each time
 * as whole seconds (rounded half away from zero) and each stage as HealthKit's category value, mixed
 * as eight little-endian bytes; printed as lower-case hex without padding. The same inputs give
 * upstream's own text.
 *
 * Here it only lets the writer skip a night Health Connect already holds as it is staged now: a
 * night's record is replaced by its id, so the signature saves a write, it does not decide one.
 */
object SleepSignature {
    private const val OFFSET_BASIS = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    private const val PRIME = 0x100000001b3L

    fun of(segments: List<SleepSegment>): String {
        var hash = OFFSET_BASIS
        fun mix(value: Long) {
            var bits = value
            repeat(8) {
                hash = (hash xor (bits and 0xff)) * PRIME
                bits = bits ushr 8
            }
        }
        val ordered = segments.sortedWith(compareBy<SleepSegment>({ it.start }, { it.end }, { healthKitValue(it.stage) }))
        for (s in ordered) {
            mix(roundedSeconds(s.start))
            mix(roundedSeconds(s.end))
            mix(healthKitValue(s.stage).toLong())
        }
        return java.lang.Long.toUnsignedString(hash, 16)
    }

    /** Swift's `timeIntervalSince1970.rounded()`: to the nearest second, a half away from zero. */
    private fun roundedSeconds(t: Instant): Long {
        val half = 500_000_000
        // epochSecond + nano/1e9: before 1970 the nanoseconds pull toward zero, so a half rounds down.
        return if (t.epochSecond >= 0) {
            if (t.nano >= half) t.epochSecond + 1 else t.epochSecond
        } else {
            if (t.nano > half) t.epochSecond + 1 else t.epochSecond
        }
    }

    /** HealthKit's `HKCategoryValueSleepAnalysis` raw value of [stage] (upstream `sleepValue`). */
    private fun healthKitValue(stage: SleepStage): Int = when (stage) {
        SleepStage.IN_BED -> 0
        SleepStage.AWAKE -> 2
        SleepStage.ASLEEP_CORE -> 3
        SleepStage.ASLEEP_DEEP -> 4
        SleepStage.ASLEEP_REM -> 5
    }
}
