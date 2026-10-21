package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.BaselineTrend.Direction
import io.github.opencircuit.ringkit.TodaySynthesis.Input
import io.github.opencircuit.ringkit.TodaySynthesis.Readiness
import io.github.opencircuit.ringkit.WellnessBalance.Tier
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards for what the Kotlin synthesis port adds or could lose relative to Swift: numbers print as
 * ASCII digits whatever the default locale (Swift's interpolation); the 36 h staleness edge to the
 * second; a newest reading dated in the future reads as fresh (the age truncates toward zero and is
 * never stale); the age in days at the 1 / 2 / 14 day edges; the short-night edges at 360 minutes
 * and 60 minutes under the usual; a NaN usual never makes a night short; doubles compare by IEEE `==`.
 */
class TodaySynthesisGuardTest {

    private val now: Instant = Instant.ofEpochSecond(1_780_000_000)
    private val good = Readiness.Scored(score = 88, tier = Tier.GOOD, factorCount = 3)

    private fun say(newest: Instant?, readiness: Readiness = Readiness.Pending, sleep: Int? = null, usual: Double? = null): String =
        TodaySynthesis.sentence(Input(readiness = readiness, lastNightSleepMinutes = sleep, usualSleepMinutes = usual, newestDataAt = newest, now = now))

    private fun <T> withDefaultLocale(locale: Locale, block: () -> T): T {
        val saved = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            return block()
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun scoresAndDayCountsPrintAsciiDigitsUnderAnArabicDigitDefaultLocale() {
        withDefaultLocale(Locale.forLanguageTag("ar-EG-u-nu-arab")) {
            assertEquals("Readiness is good at 88.", say(now.minusSeconds(60), readiness = good))
            assertEquals(
                "Your newest data is 3 days old, so sync your device to bring today's summary up to date.",
                say(now.minusSeconds(3 * 86_400 + 600)),
            )
        }
    }

    @Test
    fun exactlyThirtySixHoursIsFreshAndOneSecondMoreIsStale() {
        assertEquals("Working out today's readiness.", say(now.minusSeconds(36L * 3600)))
        assertEquals(
            "Your newest data is over a day old, so sync your device to bring today's summary up to date.",
            say(now.minusSeconds(36L * 3600 + 1)),
        )
        assertEquals(Duration.ofHours(36), TodaySynthesis.STALE_AFTER)
    }

    @Test
    fun aNewestReadingDatedInTheFutureReadsAsFresh() {
        assertEquals("Readiness is good at 88.", say(now.plusSeconds(2 * 86_400), readiness = good))
        assertEquals("Working out today's readiness.", say(now.plusSeconds(1)))
    }

    @Test
    fun theAgeInDaysTruncatesAtTheOneTwoAndFourteenDayEdges() {
        fun howOld(seconds: Long) = say(now.minusSeconds(seconds)).removePrefix("Your newest data is ").substringBefore(", so")
        assertEquals("over a day old", howOld(2 * 86_400 - 1))
        assertEquals("2 days old", howOld(2 * 86_400))
        assertEquals("13 days old", howOld(14 * 86_400 - 1))
        assertEquals("more than two weeks old", howOld(14 * 86_400))
        assertEquals("more than two weeks old", howOld(400 * 86_400))
    }

    @Test
    fun aNightIsShortBelow360MinutesOrMoreThan60MinutesUnderTheUsual() {
        val fresh = now.minusSeconds(60)
        val short = "Readiness is good at 88, though last night's sleep was short."
        val plain = "Readiness is good at 88."
        assertEquals(plain, say(fresh, good, sleep = 360))
        assertEquals(short, say(fresh, good, sleep = 359))
        assertEquals(plain, say(fresh, good, sleep = 420, usual = 480.0))
        assertEquals(short, say(fresh, good, sleep = 419, usual = 480.0))
        assertEquals(plain, say(fresh, good, sleep = 0), "a zero-minute night is no night, not a short one")
        assertEquals(plain, say(fresh, good, sleep = 400, usual = Double.NaN))
        assertEquals(plain, say(fresh, good, sleep = 400, usual = 0.0), "no usual yet")
    }

    @Test
    fun inputsCompareTheirDoublesByIeeeEquality() {
        val a = Input(usualSleepMinutes = 0.0, newestDataAt = now, now = now, hrv = Direction.WITHIN)
        val b = Input(usualSleepMinutes = -0.0, newestDataAt = now, now = now, hrv = Direction.WITHIN)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
