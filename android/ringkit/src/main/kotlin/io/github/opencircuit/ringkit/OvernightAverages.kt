package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/OvernightAverages.swift
// (@ b1c2fdd), whole.

import java.time.Instant

/**
 * Overnight windowed averages — the single source of truth for a night's mean vital (HRV / HR /
 * SpO₂ / RR), used by both the sleep view and the vitals view so the two can never disagree (one
 * showed the single newest epoch, the other the overnight mean). Pure math over (value, time)
 * pairs; an empty window yields null (shown as "—"), never a fabricated value.
 */
object OvernightAverages {

    /** One timestamped sample value (kind-agnostic — the caller pre-filters to one metric). */
    data class Point(val value: Double, val start: Instant)

    /**
     * Arithmetic mean of the values whose [Point.start] falls within [window] — inclusive of BOTH
     * ends, matching the sleep view's in-bed span check — summed in order; null when none qualify.
     */
    fun mean(points: List<Point>, window: DateInterval): Double? {
        var sum = 0.0
        var n = 0
        for (p in points) {
            if (!window.containsClosed(p.start)) continue
            sum += p.value
            n++
        }
        return if (n > 0) sum / n else null
    }
}
