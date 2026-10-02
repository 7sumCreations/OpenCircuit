package io.github.opencircuit.ringkit

// A span of time between two instants — the Kotlin stand-in for the Swift `Range<Date>` and
// `DateInterval` values in upstream ios/OpenCircuitKit (@ b1c2fdd). One shared type, so every
// ported interval signature speaks the same language.
//
// Like Swift's `Range<Date>` and Foundation's `DateInterval`, [end] may not precede [start]
// (Swift traps; here the constructor throws). `start == end` is a valid, empty interval.
//
// ⚠️ TWO CONTAINMENT RULES EXIST UPSTREAM, AND THEY DIFFER AT THE END POINT:
//   • Swift `Range<Date>` (`a ..< b`) is HALF-OPEN — [contains] here: start ≤ t < end.
//   • Foundation `DateInterval.contains(_:)` is CLOSED — [containsClosed] here: start ≤ t ≤ end.
// When porting code that called `DateInterval.contains`, use [containsClosed], or an epoch that
// lands exactly on the window's end is silently dropped.

import java.time.Duration
import java.time.Instant

data class DateInterval(val start: Instant, val end: Instant) {

    init {
        require(!end.isBefore(start)) { "an interval cannot end before it starts: $start .. $end" }
    }

    /** `end - start`. */
    val duration: Duration get() = Duration.between(start, end)

    /** True when [start] == [end]: the interval covers no time. */
    val isEmpty: Boolean get() = start == end

    /** Half-open membership, as Swift's `Range<Date>.contains`: start ≤ [t] < end. */
    operator fun contains(t: Instant): Boolean = !t.isBefore(start) && t.isBefore(end)

    /** Closed membership, as Foundation's `DateInterval.contains`: start ≤ [t] ≤ end. */
    fun containsClosed(t: Instant): Boolean = !t.isBefore(start) && !t.isAfter(end)

    companion object {
        /** The interval of [duration] beginning at [start]. [duration] must not be negative. */
        fun of(start: Instant, duration: Duration): DateInterval = DateInterval(start, start.plus(duration))
    }
}

/**
 * Whole seconds in this duration, truncated TOWARD ZERO — what Swift's `Int(_:)` does to a
 * `TimeInterval`. [Duration.getSeconds] alone floors a negative fractional span (-0.5 s → -1);
 * this gives 0, as upstream does.
 */
internal fun Duration.wholeSecondsTowardZero(): Long = if (isNegative && nano > 0) seconds + 1 else seconds

/**
 * `to - from` in `Double` seconds — Swift's `to.timeIntervalSince(from)`. The span of `Instant`'s whole
 * range fits a `Duration`, so this never throws (a Swift `Date` never overflows either).
 */
internal fun secondsBetween(from: Instant, to: Instant): Double = SleepStaging.seconds(Duration.between(from, to))
