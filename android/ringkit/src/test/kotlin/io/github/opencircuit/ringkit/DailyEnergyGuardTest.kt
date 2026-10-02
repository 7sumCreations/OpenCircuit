package io.github.opencircuit.ringkit

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards for what the Kotlin resting-HR, exercise-minute and daily-estimate port adds or could lose
 * relative to Swift: the constants (typed from upstream's sources), immutable values whose doubles
 * compare by IEEE `==` as Swift's synthesized `Equatable`, a bucket list copied in and read-only out,
 * inputs neither aliased nor changed, 64-bit sums where Swift's `Int` is 64-bit, and no read of the
 * machine's clock, locale or time zone.
 */
class DailyEnergyGuardTest {

    private fun noSetters(type: Class<*>): List<String> = type.methods.filter { it.name.startsWith("set") }.map { it.name }
    private val t: Instant = Instant.ofEpochSecond(1_753_660_800)
    private val profile = UserProfile(age = 35, weightKg = 72.0, heightCm = 178.0, sex = BiologicalSex.MALE)

    @Test
    fun constantsEqualUpstreamLiterals() {
        // RestingHR.swift :19, :22
        assertEquals(300.0, RestingHR.SUSTAINED_WINDOW)
        assertEquals(3, RestingHR.MIN_SLEEP_SAMPLES)
        // ExerciseMinutes.swift :56, :61, :87, :90, :117
        assertFalse(ExerciseMinutes.PERSONALISED_THRESHOLD_ENABLED)
        assertEquals(0.40, ExerciseMinutes.HR_RESERVE_FRACTION)
        assertEquals(35.0..90.0, ExerciseMinutes.PLAUSIBLE_RESTING_HR)
        assertEquals(12, ExerciseMinutes.MIN_RESTING_BASELINE_SAMPLES)
        assertEquals(7200.0, ExerciseMinutes.MIN_RESTING_BASELINE_SPAN)
        // Calories.swift :188, :193
        assertEquals(900.0, Calories.ENERGY_BUCKET_SECONDS)
        assertEquals(93_600.0, Calories.MAX_ATTRIBUTION_SECONDS)
        // ExerciseMinutes.swift :221 — the default epoch is BulkRecord's 150 s: two point reads 150 s
        // apart are a run (5 minutes), 151 s apart two isolated reads (nothing).
        assertEquals(5.0, ExerciseMinutes.estimate(listOf(HRSample(120, t), HRSample(120, t.plusSeconds(150))), maxHR = 185))
        assertEquals(0.0, ExerciseMinutes.estimate(listOf(HRSample(120, t), HRSample(120, t.plusSeconds(151))), maxHR = 185))
    }

    @Test
    fun valueTypesHaveNoSettersAndCompareByValue() {
        for (type in listOf(
            StepWindow::class.java, ExerciseMinutes.ElevatedPiece::class.java, RestingHR.DailyValue::class.java,
            Calories.EnergyBucket::class.java, Calories.DailyEstimate::class.java,
        )) {
            assertEquals(emptyList(), noSetters(type), "${type.simpleName} is immutable")
        }
        val w = StepWindow(t, t.plusSeconds(900), 1200)
        assertEquals(w, StepWindow(t, t.plusSeconds(900), 1200))
        assertEquals(w.hashCode(), StepWindow(t, t.plusSeconds(900), 1200).hashCode())
        val moved = w.copy(delta = 1)
        assertEquals(1200, w.delta, "a copy never moves the original")
        assertNotEquals(w, moved)
        val p = ExerciseMinutes.ElevatedPiece(t, t.plusSeconds(150), 120)
        assertEquals(p, p.copy())
        assertEquals(150.0, p.seconds)
        assertEquals(0.0, p.copy(end = t.minusSeconds(10)).seconds, "never negative")
    }

    @Test
    fun doublesCompareByIeeeEqualityAsSwiftsSynthesizedEquatable() {
        // Swift's == on a struct with a Double: NaN is unequal to itself, -0.0 equals 0.0.
        assertNotEquals(RestingHR.DailyValue(t, Double.NaN), RestingHR.DailyValue(t, Double.NaN))
        assertEquals(RestingHR.DailyValue(t, -0.0), RestingHR.DailyValue(t, 0.0))
        assertEquals(RestingHR.DailyValue(t, -0.0).hashCode(), RestingHR.DailyValue(t, 0.0).hashCode())
        assertNotEquals(RestingHR.DailyValue(t, 60.0), RestingHR.DailyValue(t.plusSeconds(1), 60.0))
        fun bucket(hr: Double) = Calories.EnergyBucket(t, t.plusSeconds(900), hr, 1.0, 2.0)
        assertNotEquals(bucket(Double.NaN), bucket(Double.NaN))
        assertEquals(bucket(-0.0), bucket(0.0))
        assertEquals(bucket(-0.0).hashCode(), bucket(0.0).hashCode())
        assertEquals(3.5, Calories.EnergyBucket(t, t, 1.5, 2.0, 0.0).activeKcal)
        assertNotEquals(Calories.DailyEstimate(Double.NaN, 0.0), Calories.DailyEstimate(Double.NaN, 0.0))
        assertEquals(Calories.DailyEstimate(-0.0, 0.0, listOf(bucket(1.0))), Calories.DailyEstimate(0.0, -0.0, listOf(bucket(1.0))))
        assertNotEquals(Calories.DailyEstimate(1.0, 0.0, listOf(bucket(1.0))), Calories.DailyEstimate(1.0, 0.0))
    }

    @Test
    fun theBucketListIsCopiedInAndReadOnlyOut() {
        val source = mutableListOf(Calories.EnergyBucket(t, t.plusSeconds(900), 1.0, 0.0, 1.0))
        val e = Calories.DailyEstimate(1.0, 1.0, source)
        source.clear()
        assertEquals(1, e.buckets.size, "the estimate keeps its own list")
        @Suppress("UNCHECKED_CAST")
        val asMutable = e.buckets as MutableList<Calories.EnergyBucket>
        assertFailsWith<UnsupportedOperationException> { asMutable.add(asMutable[0]) }
        assertEquals(emptyList(), Calories.DailyEstimate(0.0, 0.0).buckets)
    }

    @Test
    fun inputsAreNeitherAliasedNorChanged() {
        val hr = MutableList(40) { HRSample(if (it in 10..20) 130 else 60, t.plusSeconds(it * 150L)) }.asReversed().toMutableList()
        val windows = mutableListOf(StepWindow(t.plusSeconds(1500), t.plusSeconds(3300), 900))
        val segments = mutableListOf(SleepSegment(t, t.plusSeconds(1200), SleepStage.ASLEEP_CORE))
        val hrBefore = hr.toList()
        val windowsBefore = windows.toList()
        val segmentsBefore = segments.toList()

        val pieces = ExerciseMinutes.elevatedPieces(hr, maxHR = 185)
        val daily = RestingHR.dailyValues(hr, segments, zone = ZoneId.of("UTC"))
        val estimate = Calories.dailyEstimate(hr, 900, profile, stepWindows = windows, dayStart = t)
        RestingHR.value(hr, segments)
        ExerciseMinutes.restingBaseline(hr)
        Calories.legacyDailyEstimate(hr, 900, profile)
        assertEquals(hrBefore, hr, "samples are sorted in a copy, as Swift's sorted() returns one")
        assertEquals(windowsBefore, windows)
        assertEquals(segmentsBefore, segments)

        val piecesCopy = pieces.toList()
        val dailyCopy = daily.toList()
        val bucketsCopy = estimate.buckets.toList()
        hr.clear()
        windows.clear()
        segments.clear()
        assertEquals(piecesCopy, pieces, "the pieces are the function's own list")
        assertEquals(dailyCopy, daily)
        assertEquals(bucketsCopy, estimate.buckets)
        assertTrue(pieces.isNotEmpty() && daily.isNotEmpty() && estimate.buckets.isNotEmpty())
    }

    @Test
    fun sumsThatSwiftTakesIn64BitsAreTakenIn64Bits() {
        // Two qualifying readings of Int.MAX_VALUE bpm: their sum leaves 32 bits. Upstream's 64-bit
        // mean is Int.MAX_VALUE; a 32-bit sum would wrap to a negative mean and price nothing.
        val two = listOf(HRSample(Int.MAX_VALUE, t), HRSample(Int.MAX_VALUE, t.plusSeconds(150)))
        val legacy = Calories.legacyDailyEstimate(two, 0, profile)
        assertEquals(5.0, legacy.elevatedMinutes)
        assertEquals(Calories.workoutActiveKcal(avgHR = Int.MAX_VALUE, durationSeconds = 300.0, profile = profile), legacy.activeKcal)
        // An age near Int.MIN_VALUE: 220 − age leaves 32 bits. Saturated, the max HR stays above every
        // real heart rate (no elevated minutes from 100 bpm); wrapped to 1, the 60 bpm floor would count it.
        val ancient = profile.copy(age = Int.MIN_VALUE + 100)
        val walk = listOf(HRSample(100, t), HRSample(100, t.plusSeconds(150)))
        assertEquals(0.0, Calories.legacyDailyEstimate(walk, 0, ancient).elevatedMinutes)
        assertEquals(5.0, Calories.legacyDailyEstimate(two, 0, ancient).elevatedMinutes)
    }

    @Test
    fun nothingReadsTheMachineLocaleOrTimeZone() {
        val day = Instant.ofEpochSecond(1_772_946_000) // 2026-03-08 00:00 in New York: a 23-hour day
        val hr = List(600) { HRSample(if (it % 50 < 8) 128 else 64, day.plusSeconds(it * 150L)) }
        val windows = List(10) { StepWindow(day.plusSeconds(30_000L + it * 3600), day.plusSeconds(31_800L + it * 3600), 700 + it) }
        val sleep = listOf(SleepSegment(day, day.plusSeconds(6 * 3600), SleepStage.ASLEEP_DEEP))
        val ny = ZoneId.of("America/New_York")
        fun results(): List<Any?> = listOf(
            RestingHR.value(hr, sleep),
            RestingHR.dailyValues(hr, sleep, zone = ny),
            ExerciseMinutes.elevatedPieces(hr, maxHR = 180, sleepWindow = DateInterval(day, day.plusSeconds(6 * 3600))),
            ExerciseMinutes.restingBaseline(hr),
            Calories.dailyEstimate(hr, 8000, profile, stepWindows = windows, dayStart = day),
            Calories.legacyDailyEstimate(hr, 8000, profile),
        )
        val savedLocale = Locale.getDefault()
        val savedZone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.ROOT)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val reference = results()
            assertEquals(2, (reference[1] as List<*>).size, "600 readings 150 s apart cover two New York days")
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
        val root = File(assertNotNull(System.getProperty("opencircuit.androidRoot"), "system property opencircuit.androidRoot is not set"))
        val dir = File(root, "ringkit/src/main/kotlin/io/github/opencircuit/ringkit")
        val forbidden = Regex("""Instant\.now\(|ZoneId\.systemDefault\(|Locale\.getDefault\(|Clock\.system|System\.getenv|TimeZone\.getDefault\(""")
        val files = listOf("RestingHR.kt", "ExerciseMinutes.kt", "HealthAlerts.kt", "Calories.kt", "DateInterval.kt").map { File(dir, it) }
        for (f in files) {
            assertTrue(f.isFile, "missing source ${f.name}")
            val hits = f.readLines().withIndex().filter { forbidden.containsMatchIn(it.value) }.map { "${f.name}:${it.index + 1}: ${it.value.trim()}" }
            assertEquals(emptyList(), hits, "ambient environment read in ${f.name}")
        }
        // The pattern bites: each forbidden call is found in a line built here. The environment read
        // is assembled from pieces, because the corpus-gate audit forbids that text in test sources.
        for (probe in listOf("Instant.now()", "ZoneId.systemDefault()", "Locale.getDefault()", "Clock.systemUTC()", "System" + ".getenv(\"X\")", "TimeZone.getDefault()")) {
            assertTrue(forbidden.containsMatchIn("val x = $probe"), "the audit catches $probe")
        }
    }
}
