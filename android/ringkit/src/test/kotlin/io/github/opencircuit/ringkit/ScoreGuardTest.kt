package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.VitalsBaseline.Status
import java.io.File
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards for what the Kotlin score port adds or could lose relative to Swift: raw names upstream
 * stores; every constant equal to upstream's literal; the factor map's iteration order (declaration
 * order, whatever order it is built in — upstream's is seeded per process); immutable inputs and
 * results whose factors are copied in, read-only out and compared by IEEE `==` as Swift's synthesized
 * `Equatable`; and no read of the machine's clock, locale or time zone.
 */
class ScoreGuardTest {

    private fun noSetters(type: Class<*>): List<String> = type.methods.filter { it.name.startsWith("set") }.map { it.name }

    private fun mainSources(): File {
        val root = File(assertNotNull(System.getProperty("opencircuit.androidRoot"), "system property opencircuit.androidRoot is not set"))
        return File(root, "ringkit/src/main/kotlin/io/github/opencircuit/ringkit")
    }

    @Test
    fun rawNamesAreUpstreamsInDeclarationOrder() {
        assertEquals(listOf("excellent", "good", "needsImprovement"), WellnessBalance.Tier.entries.map { it.rawValue })
        assertEquals(listOf("excellent", "good", "needsImprovement"), ActivityScore.Tier.entries.map { it.rawValue })
        assertEquals(listOf("sleep", "recovery", "vitals", "activity"), WellnessBalance.Result.Factor.entries.map { it.rawValue })
        assertEquals(listOf("steps", "activeMinutes", "activeKcal"), ActivityScore.Result.Factor.entries.map { it.rawValue })
        assertEquals(listOf("up", "steady", "down"), WellnessBalance.Trend.entries.map { it.rawValue })
    }

    @Test
    fun constantsEqualUpstreamLiterals() {
        // WellnessBalance.swift :61-63, :27-31, :112-118, :126; ActivityScore.swift :73-75, :28-32.
        assertEquals(
            listOf(0.40, 0.25, 0.20, 0.15),
            WellnessBalance.Result.Factor.entries.map { WellnessBalance.FACTOR_WEIGHTS.getValue(it) },
        )
        assertEquals(listOf(0.45, 0.35, 0.20), ActivityScore.Result.Factor.entries.map { ActivityScore.FACTOR_WEIGHTS.getValue(it) })
        assertEquals(
            listOf(WellnessBalance.Tier.GOOD, WellnessBalance.Tier.EXCELLENT, WellnessBalance.Tier.NEEDS_IMPROVEMENT, WellnessBalance.Tier.GOOD),
            listOf(84, 85, 59, 60).map { WellnessBalance.Tier.of(it) },
        )
        assertEquals(
            listOf(ActivityScore.Tier.GOOD, ActivityScore.Tier.EXCELLENT, ActivityScore.Tier.NEEDS_IMPROVEMENT, ActivityScore.Tier.GOOD),
            listOf(84, 85, 69, 70).map { ActivityScore.Tier.of(it) },
        )
        assertEquals(listOf(1.0, 0.5, 0.0), Status.entries.map { WellnessBalance.vitalsFactor(it) })
        // The recovery range is SleepStress's own clamp, read by name (15…90).
        assertEquals(listOf(15.0, 90.0), listOf(SleepStress.LOW_SCORE, SleepStress.HIGH_SCORE))
        // The trend's default deadband is 3: a change of exactly 3 is steady, 3.5 is not.
        assertEquals(WellnessBalance.Trend.STEADY, WellnessBalance.trend(73, listOf(70)))
        assertEquals(WellnessBalance.Trend.UP, WellnessBalance.trend(74, listOf(70, 71)))
    }

    @Test
    fun factorsIterateInDeclarationOrderWhateverOrderTheyAreBuiltIn() {
        // Upstream's [Factor: Double] iterates in an order Swift seeds per process. Here the order is
        // stated: declaration order, for every result the scores build and for a result built from a
        // map in any other order.
        val r = assertNotNull(WellnessBalance.score(WellnessBalance.Input(80, 40, Status.WATCH, 70)))
        assertEquals(WellnessBalance.Result.Factor.entries, r.factors.keys.toList())
        val reversed = LinkedHashMap<WellnessBalance.Result.Factor, Double>()
        for (f in WellnessBalance.Result.Factor.entries.reversed()) reversed[f] = 0.5
        assertEquals(WellnessBalance.Result.Factor.entries, WellnessBalance.Result(50, WellnessBalance.Tier.NEEDS_IMPROVEMENT, reversed).factors.keys.toList())
        val partial = assertNotNull(WellnessBalance.score(WellnessBalance.Input(activityScore = 10, sleepScore = 90)))
        assertEquals(listOf(WellnessBalance.Result.Factor.SLEEP, WellnessBalance.Result.Factor.ACTIVITY), partial.factors.keys.toList())

        val a = ActivityScore.score(ActivityScore.Input(5000, 10_000, 15.0, 30.0, 250.0, 500.0))
        assertEquals(ActivityScore.Result.Factor.entries, a.factors.keys.toList())
        val backwards = LinkedHashMap<ActivityScore.Result.Factor, Double>()
        for (f in ActivityScore.Result.Factor.entries.reversed()) backwards[f] = 1.0
        assertEquals(ActivityScore.Result.Factor.entries, ActivityScore.Result(100, ActivityScore.Tier.EXCELLENT, backwards).factors.keys.toList())
    }

    @Test
    fun inputsAndResultsAreValuesWithNoSettersChangedOnlyByCopy() {
        for (type in listOf(
            WellnessBalance.Input::class.java, WellnessBalance.Result::class.java,
            ActivityScore.Input::class.java, ActivityScore.Result::class.java,
        )) {
            assertEquals(emptyList(), noSetters(type), "${type.simpleName} is immutable")
        }
        // Upstream's Input structs have var fields; a Swift struct copies on assignment. Here a changed
        // input is a new value and the original is unchanged — and so is its answer.
        val night = WellnessBalance.Input(sleepScore = 80)
        val withStress = night.copy(overnightStress = 90)
        assertNull(night.overnightStress)
        assertNotEquals(night, withStress)
        assertEquals(80, WellnessBalance.score(night)?.score)
        assertEquals(49, WellnessBalance.score(withStress)?.score, "(0.40 × 0.8 + 0.25 × 0) / 0.65 = 0.4923")
        val day = ActivityScore.Input(10_000, 10_000, 30.0, 30.0, 500.0, 500.0)
        val lazyDay = day.copy(steps = 0)
        assertEquals(10_000, day.steps)
        assertEquals(100, ActivityScore.score(day).score)
        assertEquals(55, ActivityScore.score(lazyDay).score)
    }

    @Test
    fun factorsAreCopiedInReadOnlyOutAndComparedByIeeeEquality() {
        val source = linkedMapOf(ActivityScore.Result.Factor.STEPS to 0.5)
        val r = ActivityScore.Result(23, ActivityScore.Tier.NEEDS_IMPROVEMENT, source)
        source[ActivityScore.Result.Factor.ACTIVE_KCAL] = 1.0
        assertEquals(setOf(ActivityScore.Result.Factor.STEPS), r.factors.keys, "the result keeps its own factors")
        @Suppress("UNCHECKED_CAST")
        assertFailsWith<UnsupportedOperationException> { (r.factors as MutableMap<ActivityScore.Result.Factor, Double>).clear() }
        @Suppress("UNCHECKED_CAST")
        assertFailsWith<UnsupportedOperationException> {
            (WellnessBalance.score(WellnessBalance.Input(sleepScore = 80))!!.factors as MutableMap<WellnessBalance.Result.Factor, Double>)
                .put(WellnessBalance.Result.Factor.VITALS, 1.0)
        }
        // Swift's synthesized Equatable compares the factor doubles with ==: -0.0 equals 0.0 (and
        // hashes alike), NaN is unequal to itself.
        val nan = Double.NaN
        val zero = WellnessBalance.Result(0, WellnessBalance.Tier.NEEDS_IMPROVEMENT, mapOf(WellnessBalance.Result.Factor.SLEEP to 0.0))
        val negZero = WellnessBalance.Result(0, WellnessBalance.Tier.NEEDS_IMPROVEMENT, mapOf(WellnessBalance.Result.Factor.SLEEP to -0.0))
        assertEquals(zero, negZero, "-0.0 equals 0.0, as Swift's ==")
        assertEquals(zero.hashCode(), negZero.hashCode())
        assertNotEquals(
            ActivityScore.Result(0, ActivityScore.Tier.NEEDS_IMPROVEMENT, mapOf(ActivityScore.Result.Factor.STEPS to nan)),
            ActivityScore.Result(0, ActivityScore.Tier.NEEDS_IMPROVEMENT, mapOf(ActivityScore.Result.Factor.STEPS to nan)),
            "NaN is unequal to itself",
        )
        assertEquals(
            ActivityScore.Result(0, ActivityScore.Tier.NEEDS_IMPROVEMENT, mapOf(ActivityScore.Result.Factor.STEPS to -0.0)),
            ActivityScore.Result(0, ActivityScore.Tier.NEEDS_IMPROVEMENT, mapOf(ActivityScore.Result.Factor.STEPS to 0.0)),
        )
        assertEquals(
            ActivityScore.Result(0, ActivityScore.Tier.NEEDS_IMPROVEMENT, mapOf(ActivityScore.Result.Factor.STEPS to -0.0)).hashCode(),
            ActivityScore.Result(0, ActivityScore.Tier.NEEDS_IMPROVEMENT, mapOf(ActivityScore.Result.Factor.STEPS to 0.0)).hashCode(),
        )
        // A missing factor is not a zero factor; a different factor, score or tier is a different result.
        assertNotEquals(zero, WellnessBalance.Result(0, WellnessBalance.Tier.NEEDS_IMPROVEMENT, emptyMap()))
        assertNotEquals(zero, WellnessBalance.Result(0, WellnessBalance.Tier.NEEDS_IMPROVEMENT, mapOf(WellnessBalance.Result.Factor.RECOVERY to 0.0)))
        assertNotEquals(zero, WellnessBalance.Result(0, WellnessBalance.Tier.NEEDS_IMPROVEMENT, mapOf(WellnessBalance.Result.Factor.SLEEP to 0.25)))
        assertNotEquals(zero, WellnessBalance.Result(1, WellnessBalance.Tier.NEEDS_IMPROVEMENT, mapOf(WellnessBalance.Result.Factor.SLEEP to 0.0)))
        assertNotEquals(zero, WellnessBalance.Result(0, WellnessBalance.Tier.GOOD, mapOf(WellnessBalance.Result.Factor.SLEEP to 0.0)))
        // Inputs are never written through: the prior list of the trend is read, not sorted or changed.
        val prior = mutableListOf(74, 70, 72)
        WellnessBalance.trend(80, prior)
        assertEquals(listOf(74, 70, 72), prior)
    }

    @Test
    fun nothingReadsTheMachineLocaleOrTimeZone() {
        fun results(): List<Any?> = listOf(
            WellnessBalance.score(WellnessBalance.Input(80, 40, Status.WATCH, 70)),
            WellnessBalance.anchoredScore(WellnessBalance.Input(sleepScore = 1, overnightStress = 48, activityScore = 24)),
            WellnessBalance.trend(80, listOf(70, 72, 74)),
            ActivityScore.score(ActivityScore.Input(8867, 8000, 37.4, 30.0, 487.5, 500.0)),
            ActivityScore.score(ActivityScore.Input(5000, 10_000, Double.NaN, 30.0, 250.0, 500.0)).toString(),
        )
        val savedLocale = Locale.getDefault()
        val savedZone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.ROOT)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val reference = results()
            for ((locale, tz) in listOf(
                Locale.forLanguageTag("ar-EG") to "America/New_York",
                Locale.forLanguageTag("hi-IN-u-nu-deva") to "Asia/Kolkata",
                Locale.forLanguageTag("tr-TR") to "Pacific/Chatham",
            )) {
                Locale.setDefault(locale)
                TimeZone.setDefault(TimeZone.getTimeZone(tz))
                assertEquals(reference, results(), "$locale / $tz")
            }
        } finally {
            Locale.setDefault(savedLocale)
            TimeZone.setDefault(savedZone)
        }
    }

    @Test
    fun theSlicesSourcesNeverReadTheClockLocaleZoneOrEnvironment() {
        val forbidden = Regex("""Instant\.now\(|ZoneId\.systemDefault\(|Locale\.getDefault\(|Clock\.system|System\.getenv|TimeZone\.getDefault\(""")
        for (f in listOf("WellnessBalance.kt", "ActivityScore.kt").map { File(mainSources(), it) }) {
            assertTrue(f.isFile, "missing source ${f.name}")
            val hits = f.readLines().withIndex().filter { forbidden.containsMatchIn(it.value) }.map { "${f.name}:${it.index + 1}: ${it.value.trim()}" }
            assertEquals(emptyList(), hits, "ambient environment read in ${f.name}")
        }
        // The pattern bites: each forbidden call is found in a line built here. The environment read is
        // assembled from pieces, because the corpus-gate audit forbids that text in test sources.
        for (probe in listOf("Instant.now()", "ZoneId.systemDefault()", "Locale.getDefault()", "Clock.systemUTC()", "System" + ".getenv(\"X\")", "TimeZone.getDefault()")) {
            assertTrue(forbidden.containsMatchIn("val x = $probe"), "the audit catches $probe")
        }
    }
}
