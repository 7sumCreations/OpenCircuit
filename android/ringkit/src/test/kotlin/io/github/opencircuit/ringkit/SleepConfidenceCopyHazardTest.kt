package io.github.opencircuit.ringkit

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Kotlin-only hostile-input checks for the sleep card's caveat copy: the gap formatter on zero,
 * negative, half-minute, hour-boundary, huge and non-finite spans; whole sentences pinned character for
 * character; instants at the ends of `Instant`'s range; and a default locale with non-ASCII digits.
 * Kept out of the upstream-port classes so their counts stay exact.
 *
 * Every expected string was printed by upstream's pinned Swift build with the same inputs and the
 * same fixed clock, then pasted here — never derived from the Kotlin code.
 */
class SleepConfidenceCopyHazardTest {

    private val utc: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC)
    private val clock: (Instant) -> String = { utc.format(it) }
    private val epochClock: (Instant) -> String = { it.epochSecond.toString() }

    private val t0: Instant = Instant.ofEpochSecond(1_723_000_000)
    private val end: Instant = t0.plusSeconds(253 * 60)

    private fun hints(before: Instant?, after: Instant?, cut: Double = WakeProvenance.MATERIAL_GAP_SECONDS): List<SleepConfidence.Hint> =
        SleepConfidence.hints(
            SleepConfidence.assess(
                asleep = 250 * 60.0, inBed = 253 * 60.0,
                coverage = SleepConfidence.Coverage(t0, end, before, after, emptyList(), t0.minusSeconds(7 * 86_400)),
                materialGapSeconds = cut,
            ),
            clock,
        )

    @Test
    fun theGapFormatterOnZeroNegativeHalfMinuteAndHourBoundariesAsUpstream() {
        val cases = listOf(
            0.0 to "1 minute", -0.0 to "1 minute", 1.0 to "1 minute", 29.999999 to "1 minute", 30.0 to "1 minute",
            30.000001 to "1 minute", 89.999999 to "1 minute", 90.0 to "2 minutes", 150.0 to "3 minutes", 210.0 to "4 minutes",
            -1.0 to "1 minute", -29.9 to "1 minute", -30.0 to "1 minute", -90.0 to "1 minute", -150.0 to "1 minute",
            -1e18 to "1 minute", 3_540.0 to "59 minutes", 3_569.999 to "59 minutes", 3_570.0 to "1 hour", 3_600.0 to "1 hour",
            3_629.999 to "1 hour", 3_630.0 to "1h 1m", 5_370.0 to "1h 30m", 7_170.0 to "2 hours", 7_200.0 to "2 hours",
            86_340.0 to "23h 59m", 86_370.0 to "24 hours", 14_515.0 to "4h 2m",
            1e15 to "277777777777h 47m", 3.15e16 to "8750000000000 hours", 6.3e16 to "17500000000000 hours",
            5.5e20 to "152777777777777783h 28m", 5.534e20 to "153722222222222216h 32m",
        )
        for ((seconds, expected) in cases) {
            assertEquals(expected, SleepConfidence.approximateDuration(seconds), "approximateDuration($seconds)")
        }
    }

    /** Upstream traps on these ("Double value cannot be converted to Int"); here they are rejected. */
    @Test
    fun spansUpstreamTrapsOnAreRejected() {
        for (seconds in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 5.6e20, -5.6e20)) {
            assertFailsWith<IllegalArgumentException>("approximateDuration($seconds)") { SleepConfidence.approximateDuration(seconds) }
        }
    }

    @Test
    fun bothEdgeSentencesAsUpstreamCharacterForCharacter() {
        val list = hints(before = t0.minusMillis(8_958_000), after = end.plusSeconds(14_514))
        assertEquals(
            listOf(
                SleepConfidence.Hint(
                    SleepConfidence.Reason.NoRecordingAfterWake(end, 14_514.0),
                    "sunrise",
                    "Nothing was recorded between 07:19 and 11:21 — 4h 2m with no data — so 07:19 may be where the data ends, not when you woke. If you slept longer, tap Edit to correct it.",
                ),
                SleepConfidence.Hint(
                    SleepConfidence.Reason.NoRecordingBeforeBedtime(t0, 8_958.0),
                    "bed.double",
                    "03:06 is when the ring started recording again, not when you settled — it recorded nothing for 2h 29m before that. If you were already in bed, tap Edit to correct it.",
                ),
            ),
            list,
        )
    }

    @Test
    fun backEdgeSentencesAcrossTheFormatterBoundariesAsUpstream() {
        val cases = listOf(
            301L to "Nothing was recorded between 07:19 and 07:24 — 5 minutes with no data — so 07:19 may be where the data ends, not when you woke. If you slept longer, tap Edit to correct it.",
            330L to "Nothing was recorded between 07:19 and 07:25 — 6 minutes with no data — so 07:19 may be where the data ends, not when you woke. If you slept longer, tap Edit to correct it.",
            3_600L to "Nothing was recorded between 07:19 and 08:19 — 1 hour with no data — so 07:19 may be where the data ends, not when you woke. If you slept longer, tap Edit to correct it.",
            3_630L to "Nothing was recorded between 07:19 and 08:20 — 1h 1m with no data — so 07:19 may be where the data ends, not when you woke. If you slept longer, tap Edit to correct it.",
            7_200L to "Nothing was recorded between 07:19 and 09:19 — 2 hours with no data — so 07:19 may be where the data ends, not when you woke. If you slept longer, tap Edit to correct it.",
            1_814_400L to "Nothing was recorded between 07:19 and 07:19 — 504 hours with no data — so 07:19 may be where the data ends, not when you woke. If you slept longer, tap Edit to correct it.",
        )
        for ((gap, expected) in cases) {
            val list = hints(before = null, after = end.plusSeconds(gap), cut = 0.0)
            assertEquals(listOf(expected), list.map { it.text }, "gap $gap s")
            assertEquals(listOf("sunrise"), list.map { it.systemImage })
        }
        // 3629.9 s rounds to the hour, while the resumed clock time is already the next minute.
        val fraction = hints(before = null, after = end.plusMillis(3_629_900), cut = 0.0).single().text
        assertEquals(
            "Nothing was recorded between 07:19 and 08:20 — 1 hour with no data — so 07:19 may be where the data ends, not when you woke. If you slept longer, tap Edit to correct it.",
            fraction,
        )
    }

    @Test
    fun theEndsOfTimeRenderWithoutThrowing() {
        // Measured upstream at ±3e16 s, rendered with a clock that prints whole epoch seconds.
        val farPast = Instant.ofEpochSecond(-30_000_000_000_000_000)
        val far = SleepConfidence.assess(
            asleep = 3_600.0, inBed = 3_600.0,
            coverage = SleepConfidence.Coverage(farPast, farPast.plusSeconds(3_600), null, Instant.ofEpochSecond(30_000_000_000_000_000), emptyList(), null),
        )
        assertEquals(
            listOf(
                "Nothing was recorded between -29999999999996400 and 30000000000000000 — 16666666666665h 40m with no data — so -29999999999996400 may be where the data ends, not when you woke. If you slept longer, tap Edit to correct it.",
            ),
            SleepConfidence.hints(far, epochClock).map { it.text },
        )

        // The whole range of `Instant`, which a `Date` cannot hold: the gap as a `Double` rounds one
        // second past `Instant.MAX`, so the resumed instant is clamped to the end of the range.
        val whole = SleepConfidence.assess(
            asleep = 0.0, inBed = 0.0,
            coverage = SleepConfidence.Coverage(Instant.MIN, Instant.MIN, null, Instant.MAX, emptyList(), null),
            materialGapSeconds = 0.0,
        )
        assertEquals(
            listOf(
                "Nothing was recorded between -31557014167219200 and 31556889864403199 — 17531640008784 hours with no data — so -31557014167219200 may be where the data ends, not when you woke. If you slept longer, tap Edit to correct it.",
            ),
            SleepConfidence.hints(whole, epochClock).map { it.text },
        )
    }

    /** Every number in the copy is a whole integer printed the way Swift's interpolation prints it. */
    @Test
    fun aDefaultLocaleWithOtherDigitsChangesNoString() {
        fun rendered(): List<String> =
            hints(before = t0.minusMillis(8_958_000), after = end.plusSeconds(14_514)).map { it.text } +
                hints(before = null, after = end.plusSeconds(1_814_400), cut = 0.0).map { it.text } +
                listOf(0.0, 90.0, 3_600.0, 3_630.0, 1e15).map { SleepConfidence.approximateDuration(it) }

        val savedLocale = Locale.getDefault()
        val savedZone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.ROOT)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val baseline = rendered()
            assertEquals("277777777777h 47m", baseline.last())
            for ((locale, tz) in listOf(
                Locale.forLanguageTag("ar-EG") to "Pacific/Kiritimati",
                Locale.forLanguageTag("fa-IR") to "Asia/Tehran",
                Locale.forLanguageTag("hi-IN-u-nu-deva") to "America/St_Johns",
                Locale.forLanguageTag("tr-TR") to "Asia/Kathmandu",
            )) {
                Locale.setDefault(locale)
                TimeZone.setDefault(TimeZone.getTimeZone(tz))
                assertEquals(baseline, rendered(), "default locale $locale, default zone $tz")
            }
        } finally {
            Locale.setDefault(savedLocale)
            TimeZone.setDefault(savedZone)
        }
    }
}
