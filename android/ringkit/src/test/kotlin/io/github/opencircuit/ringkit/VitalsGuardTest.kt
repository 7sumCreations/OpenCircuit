package io.github.opencircuit.ringkit

import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards for what the Kotlin vitals basics add or could lose relative to Swift: the constants and
 * raw names (typed from upstream's sources), immutable value types, inputs neither aliased nor
 * changed where Swift copied its arrays, 64-bit results where Swift's `Int` is 64-bit, and no read of
 * the machine's clock, locale or time zone.
 */
class VitalsGuardTest {

    private fun noSetters(type: Class<*>): List<String> = type.methods.filter { it.name.startsWith("set") }.map { it.name }

    @Test
    fun constantsEqualUpstreamLiterals() {
        // Strain.swift :17-19
        assertEquals(600, Strain.MIN_READINGS)
        assertEquals(21.0, Strain.MAX_STRAIN)
        assertEquals(8.882_643_961_783_384, Strain.LN_7201)
        // Stress.swift :18
        assertEquals(50, Stress.BIN_WIDTH)
        // DistanceEstimate.swift :20
        assertEquals(0.248, DistanceEstimate.METERS_PER_STEP)
        // Calories.swift :16-17, :24, :72, :77, :83, :93
        assertEquals(listOf(5.0, 0.5, 0.01, 0.20), listOf(Calories.TRIMP_KCAL_FACTOR, Calories.WALK_KCAL_PER_KG_PER_KM, Calories.RESTING_ENERGY_FRACTION_PER_BPM, Calories.MAX_RESTING_ENERGY_ADJUSTMENT))
        assertEquals(listOf(60, 3, 5), listOf(Calories.DEFAULT_RESTING_HR, Calories.MIN_RESTING_BASELINE_DAYS, Calories.MIN_TRIMMED_BASELINE_DAYS))
        // HRV.swift :40, :63 — the default rolling window is 300 values.
        val rr = List(300) { 800 + it % 3 }
        assertEquals(1, HRV.rollingRMSSD(rr).size)
        assertEquals(emptyList(), HRV.rollingRMSSD(rr.drop(1)))
        assertNotNull(HRV.summary(rr))
    }

    @Test
    fun rawNamesEqualUpstream() {
        // UserProfile.swift :3-6 — the stored names, in declaration (CaseIterable) order.
        assertEquals(listOf("male", "female"), BiologicalSex.entries.map { it.rawValue })
    }

    @Test
    fun valueTypesHaveNoSettersAndCompareByValue() {
        for (type in listOf(HRSample::class.java, UserProfile::class.java, HRV.Summary::class.java, Strain::class.java)) {
            assertEquals(emptyList(), noSetters(type), "${type.simpleName} is immutable")
        }
        val t = Instant.ofEpochSecond(1_750_000_000)
        val a = HRSample(bpm = 70, start = t)
        assertEquals(t, a.end, "end defaults to start, as upstream's init")
        assertEquals(a, HRSample(70, t, t))
        assertEquals(a.hashCode(), HRSample(70, t, t).hashCode())
        val later = a.copy(end = t.plusSeconds(60))
        assertEquals(t, a.end, "a copy never moves the original")
        assertNotEquals(a, later)
        val p = UserProfile(30, 80.0, 180.0, BiologicalSex.MALE)
        assertEquals(p, p.copy())
        assertNotEquals(p, p.copy(sex = BiologicalSex.FEMALE))
        assertEquals(HRV.Summary(1, 2, 3), HRV.Summary(1, 2, 3))
    }

    @Test
    fun profileEqualityIsTheDataClassRuleNotSwiftsIeeeRule() {
        // Swift's synthesized == compares doubles by IEEE ==: a NaN weight is unequal to itself and
        // -0.0 equals 0.0. A Kotlin data class compares them as boxed doubles: the reverse, in both
        // cases (PORTING.md D-72). Nothing in the energy maths compares profiles.
        val nan = UserProfile(30, Double.NaN, 180.0, BiologicalSex.MALE)
        assertEquals(nan, nan.copy())
        assertNotEquals(UserProfile(30, -0.0, 180.0, BiologicalSex.MALE), UserProfile(30, 0.0, 180.0, BiologicalSex.MALE))
    }

    @Test
    fun inputsAreNeitherAliasedNorChanged() {
        val group = mutableListOf(800, -5, 900)
        val groups = mutableListOf<List<Int>>(group, listOf(1000))
        val clean = HRV.cleanRR(groups)
        group[0] = 1
        groups.clear()
        assertEquals(listOf(800, 900, 1000), clean, "the cleaned list is the function's own")

        val rr = MutableList(400) { 800 + (it * 37) % 90 }
        val rolled = HRV.rollingRMSSD(rr, windowSize = 300)
        val rolledCopy = rolled.toList()
        rr.fill(0)
        assertEquals(rolledCopy, rolled, "the rolling series does not view the caller's list")

        val prior = mutableListOf(70.0, 58.0, 62.0, 100.0, 61.0)
        val before = prior.toList()
        Calories.restingBaselineBpm(prior)
        assertEquals(before, prior, "the baseline sorts its own copy, as Swift's sorted() returns one")

        val bpms = MutableList(600) { 150 }
        val samples = MutableList(600) { HRSample(150, Instant.ofEpochSecond(it.toLong())) }
        Strain(190, 60).calculate(bpms)
        Calories.activeKcal(samples, maxHR = 180)
        Stress.index(bpms)
        assertEquals(List(600) { 150 }, bpms)
        assertEquals(List(600) { HRSample(150, Instant.ofEpochSecond(it.toLong())) }, samples)
    }

    @Test
    fun hrvResultsAre64BitAsSwiftsInt() {
        // Each window's RMSSD is 2^32 - 1 and the summary sums three of them: neither fits 32 bits.
        val extreme = listOf(Int.MIN_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE)
        assertEquals(List(3) { 4_294_967_295L }, HRV.rollingRMSSD(extreme, windowSize = 2))
        assertEquals(4_294_967_295L, HRV.summary(extreme, windowSize = 2)!!.avg)
        assertEquals(Long::class.javaPrimitiveType, HRV.Summary::class.java.getDeclaredField("avg").type)
    }

    @Test
    fun nothingReadsTheMachineLocaleOrTimeZone() {
        val t = Instant.ofEpochSecond(1_772_953_200) // 2026-03-08 07:00 UTC: New York's spring-forward instant
        val samples = List(700) { HRSample(if (it % 3 == 0) 160 else 95, t.plusSeconds(it * 60L), t.plusSeconds(it * 60L + 45)) }
        val profile = UserProfile(41, 72.5, 177.0, BiologicalSex.FEMALE)
        fun results(): List<Any?> = listOf(
            HRV.summary(List(500) { 820 + (it * 13) % 70 }),
            Stress.index(List(150) { 600 + (it * 29) % 120 }),
            Strain(185, 55).calculate(samples.map { it.bpm }, sampleSeconds = 60.0),
            Calories.activeKcal(samples, maxHR = 185),
            Calories.basalKcalPerHour(profile, restingHR = 63.0, baselineRestingHR = 58.5),
            Calories.restingBaselineBpm(listOf(58.0, 61.5, 57.0, 90.0, 60.5, 59.0)),
            Calories.workoutActiveKcal(avgHR = 133, durationSeconds = 1830.5, profile = profile),
            Calories.activeKcalFromSteps(9_876, profile),
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
        val root = File(assertNotNull(System.getProperty("opencircuit.androidRoot"), "system property opencircuit.androidRoot is not set"))
        val dir = File(root, "ringkit/src/main/kotlin/io/github/opencircuit/ringkit")
        val forbidden = Regex("""Instant\.now\(|ZoneId\.systemDefault\(|Locale\.getDefault\(|Clock\.system|System\.getenv|TimeZone\.getDefault\(""")
        val files = listOf("HRV.kt", "Stress.kt", "Strain.kt", "DistanceEstimate.kt", "UserProfile.kt", "Calories.kt").map { File(dir, it) }
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
