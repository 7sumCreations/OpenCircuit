package io.github.opencircuit.ringkit

// The SENTENCES the sleep card puts under a night, derived from `SleepConfidence.Assessment`. Port of
// upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/SleepConfidenceCopy.swift (@ b1c2fdd); the
// `Hint` type and the public `hints` / `approximateDuration` entry points are declared on
// `SleepConfidence`, the logic is here.
//
// The copy lives in the library, not the view, so a test (or a corpus harness) can print exactly
// what the card SAYS on any night. THREE RULES THE STRINGS OBEY, each measured rather than stylistic:
//  1. NAME THE MEASUREMENT, NEVER A CAUSE. "Nothing was recorded between 02:37 and 06:39" is what the
//     store says; "the ring stopped" is a guess, and wrong whenever the cause is our own sync.
//  2. STATE THE GAP, NEVER AN INFERRED SLEEP TOTAL. The gap BOUNDS the error; it does not estimate it.
//  3. POINT AT EDIT — the only lever the wearer has, and the supervised label this area lacks.
//
// The sentences are upstream's, character for character (the dashes are U+2014 em dashes; the
// apostrophe in "can't" is ASCII, as upstream's). Every number in them is a whole integer rendered by
// Kotlin's locale-free `toString`, as Swift's interpolation renders it. The one thing this file
// cannot own is the CLOCK format, so the caller injects it.

import java.time.DateTimeException
import java.time.Instant
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * `SleepConfidence.approximateDuration`. Upstream's body is character for character the Sleep card's
 * house formatter that `SleepEditedNightNotice.duration` already ports (whole minutes rounded half away
 * from zero, never below one; hours and minutes from an hour up), so this reuses that one
 * implementation rather than keeping a second copy that could drift from it.
 */
internal fun approximateSpan(seconds: Double): String = SleepEditedNightNotice.duration(seconds)

/** `SleepConfidence.hints` — see its KDoc. */
internal fun confidenceHints(assessment: SleepConfidence.Assessment, clock: (Instant) -> String): List<SleepConfidence.Hint> =
    assessment.reasons.map { reason ->
        when (reason) {
            // BACK EDGE — the one with no other surface in the app. Names BOTH instants and the gap, so
            // the wearer can check the claim against their own memory of the night. "may be where the
            // data ends, not when you woke" is the honest hedge.
            is SleepConfidence.Reason.NoRecordingAfterWake -> {
                val from = reason.from
                val silentFor = reason.silentFor
                val resumed = from.plusSecondsSaturating(silentFor)
                SleepConfidence.Hint(
                    reason = reason,
                    systemImage = "sunrise",
                    text = "Nothing was recorded between ${clock(from)} and " +
                        "${clock(resumed)} — " +
                        "${SleepConfidence.approximateDuration(silentFor)} with no data — so ${clock(from)} may be " +
                        "where the data ends, not when you woke. If you slept longer, tap Edit to " +
                        "correct it.",
                )
            }

            // FRONT EDGE — the sentence upstream already shipped, KEPT WORD FOR WORD.
            is SleepConfidence.Reason.NoRecordingBeforeBedtime -> {
                val until = reason.until
                val silentFor = reason.silentFor
                SleepConfidence.Hint(
                    reason = reason,
                    systemImage = "bed.double",
                    text = "${clock(until)} is when the ring started recording again, not when you " +
                        "settled — it recorded nothing for ${SleepConfidence.approximateDuration(silentFor)} " +
                        "before that. If you were already in bed, tap Edit to correct it.",
                )
            }

            // DURATION — also verbatim from upstream's shipped hint.
            SleepConfidence.Reason.DurationLikelyHigh -> SleepConfidence.Hint(
                reason = reason,
                systemImage = "info.circle",
                text = "Very still night — duration may read a little high. The ring can't sense " +
                    "motionless wakefulness (no movement, near-sleep heart rate), so quiet " +
                    "time awake in bed is counted as light sleep.",
            )
        }
    }

/**
 * Swift's `addingTimeInterval`: this instant plus [seconds] (rounded to the nanosecond), clamped to the
 * ends of `Instant`'s range. A Swift `Date` never overflows; an `Instant` throws past its range, and a
 * gap spanning that whole range, held as a `Double`, rounds one second past the end.
 */
private fun Instant.plusSecondsSaturating(seconds: Double): Instant {
    val whole = floor(seconds)
    if (!(whole >= Long.MIN_VALUE.toDouble() && whole < Long.MAX_VALUE.toDouble())) return if (seconds < 0) Instant.MIN else Instant.MAX
    return try {
        plusSeconds(whole.toLong()).plusNanos(((seconds - whole) * 1e9).roundToLong())
    } catch (e: DateTimeException) {
        if (seconds < 0) Instant.MIN else Instant.MAX
    } catch (e: ArithmeticException) {
        if (seconds < 0) Instant.MIN else Instant.MAX
    }
}
