package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/TodaySynthesis.swift
// (@ 2d07e550, the last MIT commit to touch it; the global pin stays b1c2fdd).
//
// Today synthesis — the one plain-language sentence at the top of the Today screen.
//
// Deterministic templates over numbers the app already computes: the Wellness Balance readiness
// (exactly as the readiness card produces it — this type never re-scores anything) and the tiles'
// `BaselineTrend` directions. No model, no randomness, no advice beyond "an easier day may help" /
// "take today gently" — and every missing input degrades to a sentence that SAYS it is missing
// rather than one that guesses around it.
//
// Rule order (first match wins, then clauses are composed):
//   1. no data at all               → say so, point at connect + sync
//   2. newest data older than 36 h  → say how old, point at sync
//   3. readiness states             → lead with readiness (or why there is none), then the most
//                                     notable vitals clause: concerns first, then positives, then
//                                     "in your usual range"
// Possible fever signs are surfaced ahead of any other clause, but ONLY when Vitals Status itself
// flagged them (`Input.feverSuspected`, from `VitalsBaseline.suspectedFever`: skin temp up ≥ 1.0 °C
// AND resting HR up ≥ 8 bpm against a 7+ day baseline). The tiles' own bands are looser (0.3 °C,
// 2 bpm, 4 days), so both tiles reading "above your usual" is not fever, and this line must never say
// "take today gently" on a screen whose Vitals Status says nothing is wrong. Each tile direction
// still speaks for itself as an ordinary clause.
//
// Kotlin differences, each deliberate:
// - `now` is part of the input (upstream's too); nothing here reads a clock, a locale or a zone.
//   Numbers are printed as ASCII digits whatever the default locale, as Swift's interpolation does.
// - The age in days truncates toward zero, as Swift's `Int(age / 86_400)`: a newest reading dated in
//   the future has a negative age, is not stale, and reads as fresh — the same as upstream.
// - The first letter is upper-cased with the locale-independent rule (Swift's `Character.uppercased`).

/** The one-sentence summary of today. */
object TodaySynthesis {

    /** Readiness as the readiness card ended up showing it. */
    sealed interface Readiness {
        /** A score was computed. [factorCount] is how many sub-scores went into it (1 = sleep alone). */
        data class Scored(val score: Int, val tier: WellnessBalance.Tier, val factorCount: Int) : Readiness

        /** No night that ended today is stored — a sync can fix this. */
        data object NoNight : Readiness

        /** Last night is stored but carries no sleep score — a sync cannot fix this. */
        data object NoScore : Readiness

        /** Not computed yet (first frame, or the card hasn't reported). */
        data object Pending : Readiness
    }

    /**
     * What the sentence is built from. [feverSuspected] is Vitals Status's own verdict (false while it
     * hasn't reported); [lastNightSleepMinutes] last night's asleep minutes when a night that ended
     * today is stored; [usualSleepMinutes] typical asleep minutes over recent nights, for the
     * short-night rule; [newestDataAt] the time of the newest stored ring data of any kind, null when
     * there is none. Doubles compare by IEEE `==`, as Swift's synthesized `Equatable`.
     */
    data class Input(
        val readiness: Readiness = Readiness.Pending,
        val hrv: BaselineTrend.Direction? = null,
        val restingHR: BaselineTrend.Direction? = null,
        val skinTemp: BaselineTrend.Direction? = null,
        val feverSuspected: Boolean = false,
        val lastNightSleepMinutes: Int? = null,
        val usualSleepMinutes: Double? = null,
        val newestDataAt: Instant?,
        val now: Instant,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Input && readiness == other.readiness && hrv == other.hrv && restingHR == other.restingHR &&
                skinTemp == other.skinTemp && feverSuspected == other.feverSuspected &&
                lastNightSleepMinutes == other.lastNightSleepMinutes && sameUsual(usualSleepMinutes, other.usualSleepMinutes) &&
                newestDataAt == other.newestDataAt && now == other.now

        override fun hashCode(): Int =
            listOf(readiness, hrv, restingHR, skinTemp, feverSuspected, lastNightSleepMinutes, usualSleepMinutes?.let(::ieeeHash), newestDataAt, now)
                .hashCode()

        private fun sameUsual(a: Double?, b: Double?): Boolean = if (a == null || b == null) a == null && b == null else a == b
    }

    /** Data older than this reads as stale rather than "today". */
    val STALE_AFTER: Duration = Duration.ofHours(36)

    /** A night shorter than this many minutes is "short" regardless of the user's usual. */
    const val SHORT_NIGHT_MINUTES: Int = 360

    /** …or this many minutes shorter than their usual. */
    const val SHORT_VS_USUAL_MINUTES: Double = 60.0

    private const val SECONDS_PER_DAY = 86_400.0

    private const val FEVER_CLAUSE = "skin temperature and resting heart rate are both above your usual"

    /** The sentence for [input]. */
    fun sentence(input: Input): String {
        val newest = input.newestDataAt
            ?: return "There's no data from your device yet, so connect and sync it to see today's summary."
        val age = secondsBetween(newest, input.now)
        if (age > STALE_AFTER.seconds.toDouble()) {
            val days = (age / SECONDS_PER_DAY).toInt()
            // The app only looks back two weeks, so it can't honestly name an age past that.
            val howOld = if (days <= 1) "over a day old" else if (days >= 14) "more than two weeks old" else "$days days old"
            return "Your newest data is $howOld, so sync your device to bring today's summary up to date."
        }

        val concerns = concernClauses(input)
        val positives = positiveClauses(input)
        val steady = steadyClause(input)
        val feverPair = input.feverSuspected

        return when (val readiness = input.readiness) {
            is Readiness.Scored -> {
                var lead = when (readiness.tier) {
                    WellnessBalance.Tier.EXCELLENT -> "Readiness is excellent at ${readiness.score}"
                    WellnessBalance.Tier.GOOD -> "Readiness is good at ${readiness.score}"
                    WellnessBalance.Tier.NEEDS_IMPROVEMENT -> "Readiness needs improvement at ${readiness.score}"
                }
                if (readiness.factorCount <= 1) lead += " based on last night's sleep alone"
                when {
                    feverPair -> "$lead, but $FEVER_CLAUSE, so take today gently."
                    readiness.tier == WellnessBalance.Tier.NEEDS_IMPROVEMENT ->
                        concerns.firstOrNull()?.let { "$lead, and $it, so an easier day may help." } ?: "$lead, so an easier day may help."
                    concerns.isNotEmpty() -> "$lead, though ${concerns.first()}."
                    positives.isNotEmpty() -> "$lead, and ${positives.first()}."
                    steady != null -> "$lead, with ${steady.subject} in your usual range."
                    else -> "$lead."
                }
            }
            Readiness.NoNight, Readiness.NoScore -> {
                val lead = if (readiness == Readiness.NoNight) {
                    "Last night's sleep hasn't synced yet, so there's no readiness score"
                } else {
                    "Last night has no sleep score, so there's no readiness today"
                }
                val clause = concerns.firstOrNull() ?: positives.firstOrNull()
                when {
                    feverPair -> "$lead, but $FEVER_CLAUSE."
                    clause != null -> "$lead, but $clause."
                    steady != null -> "$lead, but ${steady.subject} ${steady.verb} in your usual range."
                    else -> "$lead."
                }
            }
            Readiness.Pending -> {
                val clause = concerns.firstOrNull() ?: positives.firstOrNull()
                when {
                    feverPair -> capitalized(FEVER_CLAUSE) + ", so take today gently."
                    clause != null -> capitalized(clause) + "."
                    steady != null -> capitalized(steady.subject) + " ${steady.verb} in your usual range."
                    else -> "Working out today's readiness."
                }
            }
        }
    }

    // Clauses

    private fun sleepWasShort(i: Input): Boolean {
        val last = i.lastNightSleepMinutes ?: return false
        if (last <= 0) return false
        if (last < SHORT_NIGHT_MINUTES) return true
        val usual = i.usualSleepMinutes
        return usual != null && usual > 0 && last.toDouble() < usual - SHORT_VS_USUAL_MINUTES
    }

    private fun concernClauses(i: Input): List<String> = buildList {
        if (sleepWasShort(i)) add("last night's sleep was short")
        if (i.hrv == BaselineTrend.Direction.BELOW) add("HRV is below your usual")
        if (i.restingHR == BaselineTrend.Direction.ABOVE) add("resting heart rate is above your usual")
        if (i.skinTemp == BaselineTrend.Direction.ABOVE) add("skin temperature is above your usual")
    }

    private fun positiveClauses(i: Input): List<String> = buildList {
        if (i.hrv == BaselineTrend.Direction.ABOVE) add("HRV is above your usual")
        if (i.restingHR == BaselineTrend.Direction.BELOW) add("resting heart rate is below your usual")
    }

    private class Steady(val subject: String, val verb: String)

    /** The metrics that are known AND within their usual range, with the verb that agrees with them. */
    private fun steadyClause(i: Input): Steady? {
        val hrv = i.hrv == BaselineTrend.Direction.WITHIN
        val rhr = i.restingHR == BaselineTrend.Direction.WITHIN
        return when {
            hrv && rhr -> Steady("HRV and resting heart rate", "are")
            hrv -> Steady("HRV", "is")
            rhr -> Steady("resting heart rate", "is")
            else -> null
        }
    }

    private fun capitalized(s: String): String = if (s.isEmpty()) s else s.substring(0, 1).uppercase() + s.substring(1)

    /** Swift's `now.timeIntervalSince(newest)`: signed seconds, with the fraction. */
    private fun secondsBetween(from: Instant, to: Instant): Double =
        (to.epochSecond - from.epochSecond).toDouble() + (to.nano - from.nano) / 1e9
}
