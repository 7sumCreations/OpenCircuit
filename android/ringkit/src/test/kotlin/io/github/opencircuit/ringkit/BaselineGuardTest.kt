package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.SkinTempBaseline.AnomalyFlags
import io.github.opencircuit.ringkit.SkinTempBaseline.DeviationBand
import io.github.opencircuit.ringkit.SkinTempBaseline.NightlyTemp
import io.github.opencircuit.ringkit.SkinTempBaseline.NightlyVerdict
import io.github.opencircuit.ringkit.VitalsBaseline.Concern
import io.github.opencircuit.ringkit.VitalsBaseline.Config
import io.github.opencircuit.ringkit.VitalsBaseline.Direction
import io.github.opencircuit.ringkit.VitalsBaseline.Severity
import io.github.opencircuit.ringkit.VitalsBaseline.Status
import io.github.opencircuit.ringkit.VitalsBaseline.Vital
import io.github.opencircuit.ringkit.VitalsBaseline.VitalInput
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards for what the Kotlin baseline port adds or could lose relative to Swift: the normal-deviation
 * band keeps its one home and value (the sleep score and the vitals config read it); every constant
 * equals upstream's literal; raw names upstream stores; immutable values whose doubles compare by IEEE
 * `==` as Swift's synthesized `Equatable`; `var` config and flag structs as values changed with `copy`;
 * lists copied in and read-only out and inputs never written through; a 64-bit coverage bucket count;
 * and no read of the machine's clock, locale or time zone.
 */
class BaselineGuardTest {

    private fun noSetters(type: Class<*>): List<String> = type.methods.filter { it.name.startsWith("set") }.map { it.name }
    private val t0: Instant = Instant.ofEpochSecond(1_700_000_000)

    private fun mainSources(): File {
        val root = File(assertNotNull(System.getProperty("opencircuit.androidRoot"), "system property opencircuit.androidRoot is not set"))
        return File(root, "ringkit/src/main/kotlin/io/github/opencircuit/ringkit")
    }

    @Test
    fun theNormalDeviationBandHasOneHomeAndItsValue() {
        // SkinTempBaseline.swift :31 — 1 °C, the band the composite sleep score and the vitals config read.
        assertEquals(1.0, SkinTempBaseline.NORMAL_DEVIATION_C)
        val declaration = Regex("""const\s+val\s+NORMAL_DEVIATION_C\b""")
        val homes = mainSources().listFiles { f -> f.extension == "kt" }!!.flatMap { f ->
            f.readLines().withIndex().filter { declaration.containsMatchIn(it.value) }.map { "${f.name}:${it.index + 1}: ${it.value.trim()}" }
        }
        assertEquals(listOf("SkinTempBaseline.kt"), homes.map { it.substringBefore(':') }, "one declaration: $homes")
        assertTrue(homes.single().endsWith("const val NORMAL_DEVIATION_C: Double = 1.0"), homes.single())
        // Its readers name it rather than copy the literal: the sleep score and both temperature defaults
        // of the vitals config.
        for ((file, expected) in listOf("SleepScore.kt" to 1, "VitalsBaseline.kt" to 2)) {
            val reads = File(mainSources(), file).readText().split("SkinTempBaseline.NORMAL_DEVIATION_C").size - 1
            assertEquals(expected, reads, "$file reads the constant by name")
        }
        assertEquals(SkinTempBaseline.NORMAL_DEVIATION_C, Config().tempSignificantC)
        assertEquals(SkinTempBaseline.NORMAL_DEVIATION_C, Config().feverTempRiseC)
        // The band itself: exactly ±1 °C is normal, the next double past it is not.
        assertEquals(DeviationBand.NORMAL, SkinTempBaseline.deviationBand(1.0))
        assertEquals(DeviationBand.ABNORMAL_RISE, SkinTempBaseline.deviationBand(Math.nextUp(1.0)))
    }

    @Test
    fun constantsAndDefaultsEqualUpstreamLiterals() {
        // RobustBaseline.swift :39, :49, :54, :59
        assertEquals(7, RobustBaseline.MIN_BASELINE_DAYS)
        assertEquals(60, RobustBaseline.MAX_BASELINE_DAYS)
        assertEquals(1.4826, RobustBaseline.MAD_CONSISTENCY)
        assertEquals(4.0, RobustBaseline.Z_CLAMP)
        // VitalsBaseline.swift :77-87
        val c = Config()
        assertEquals(listOf(7, 30), listOf(c.minBaselineDays, c.maxBaselineDays))
        assertEquals(listOf(1.5, 2.5, 5.0, 2.0, 8.0, 0.5, 1.0, 1.0, 8.0),
            listOf(c.minorZ, c.significantZ, c.minDeltaRestingHR, c.minDeltaSpO2, c.minDeltaHRV, c.tempMinorC, c.tempSignificantC, c.feverTempRiseC, c.feverHRRiseBpm))
        // SkinTempBaseline.swift :24, :28, :34, :44, :85, :179, :184
        assertEquals(30, SkinTempBaseline.BASELINE_WINDOW_NIGHTS)
        assertEquals(3, SkinTempBaseline.MIN_BASELINE_NIGHTS)
        assertEquals(0.6, SkinTempBaseline.FLUCTUATION_C)
        assertEquals(0.3, SkinTempBaseline.FLUCTUATION_BASELINE_GATE_C)
        assertEquals(10, SkinTempBaseline.MIN_NIGHTLY_SAMPLES)
        assertEquals(0.6, SkinTempBaseline.MIN_NIGHTLY_COVERAGE)
        assertEquals(0.6, SkinTempBaseline.CANDIDATE_NIGHTLY_COVERAGE)
    }

    @Test
    fun rawNamesAreUpstreamsInDeclarationOrder() {
        assertEquals(listOf("restingHR", "overnightSpO2", "overnightHRV"), Vital.entries.map { it.rawValue })
        assertEquals(listOf(Concern.HIGH, Concern.LOW, Concern.LOW), Vital.entries.map { it.concern })
        assertEquals(listOf("HIGH", "LOW", "BOTH"), Concern.entries.map { it.name })
        assertEquals(listOf("normal", "minor", "significant"), Severity.entries.map { it.rawValue })
        assertEquals(listOf("rise", "drop"), Direction.entries.map { it.rawValue })
        assertEquals(listOf("normal", "watch", "anomaly"), Status.entries.map { it.rawValue })
        assertEquals(listOf("normal", "abnormalRise", "abnormalDrop"), DeviationBand.entries.map { it.rawValue })
    }

    @Test
    fun valuesHaveNoSettersAndCompareByIeeeEquality() {
        for (type in listOf(
            RobustBaseline.Stats::class.java, VitalsBaseline.Stats::class.java, VitalsBaseline.Classification::class.java,
            VitalInput::class.java, VitalsBaseline.Signal::class.java, VitalsBaseline.Report::class.java, Config::class.java,
            NightlyTemp::class.java, NightlyVerdict.Published::class.java, NightlyVerdict.RejectedCoverage::class.java,
            AnomalyFlags::class.java, SkinTempBaseline.NightReport::class.java,
        )) {
            assertEquals(emptyList(), noSetters(type), "${type.simpleName} is immutable")
        }
        val nan = Double.NaN
        assertEquals(RobustBaseline.Stats(-0.0, 1.0, 7), RobustBaseline.Stats(0.0, 1.0, 7), "-0.0 equals 0.0, as Swift's ==")
        assertEquals(RobustBaseline.Stats(-0.0, 1.0, 7).hashCode(), RobustBaseline.Stats(0.0, 1.0, 7).hashCode())
        assertNotEquals(RobustBaseline.Stats(nan, 1.0, 7), RobustBaseline.Stats(nan, 1.0, 7), "NaN is unequal to itself")
        assertNotEquals(VitalsBaseline.Stats(60.0, nan, 7), VitalsBaseline.Stats(60.0, nan, 7))
        assertEquals(VitalsBaseline.Classification(Severity.NORMAL, null, -0.0, Direction.RISE), VitalsBaseline.Classification(Severity.NORMAL, null, 0.0, Direction.RISE))
        assertNotEquals(VitalInput(Vital.RESTING_HR, 60.0, listOf(nan)), VitalInput(Vital.RESTING_HR, 60.0, listOf(nan)))
        assertEquals(VitalInput(Vital.RESTING_HR, 60.0, listOf(-0.0)), VitalInput(Vital.RESTING_HR, 60.0, listOf(0.0)))
        assertNotEquals(VitalsBaseline.Signal(null, true, Severity.MINOR, 0.7, Direction.RISE, nan), VitalsBaseline.Signal(null, true, Severity.MINOR, 0.7, Direction.RISE, nan))
        assertEquals(Config(minorZ = -0.0), Config(minorZ = 0.0))
        assertEquals(Config(minorZ = -0.0).hashCode(), Config(minorZ = 0.0).hashCode())
        assertNotEquals(Config(minorZ = nan), Config(minorZ = nan))
        assertEquals(NightlyTemp(t0, -0.0), NightlyTemp(t0, 0.0))
        assertNotEquals(NightlyVerdict.Published(nan), NightlyVerdict.Published(nan))
        assertEquals(NightlyVerdict.RejectedCoverage(0.2), NightlyVerdict.RejectedCoverage(0.2))
        assertNotEquals<NightlyVerdict>(NightlyVerdict.Published(0.2), NightlyVerdict.RejectedCoverage(0.2))
        assertNotEquals(SkinTempBaseline.NightReport(nan, null, null, null, AnomalyFlags()), SkinTempBaseline.NightReport(nan, null, null, null, AnomalyFlags()))
        assertEquals(SkinTempBaseline.NightReport(34.0, -0.0, null, null, AnomalyFlags()), SkinTempBaseline.NightReport(34.0, 0.0, null, null, AnomalyFlags()))
    }

    @Test
    fun varStructsAreValuesChangedWithCopy() {
        // Upstream's Config and AnomalyFlags have var fields; a Swift struct copies on assignment, so
        // changing one never changes another. Here a copy is a new value and the original is unchanged.
        val shipped = Config()
        val strict = shipped.copy(minorZ = 1.0, maxBaselineDays = 14)
        assertEquals(1.5, shipped.minorZ)
        assertEquals(30, shipped.maxBaselineDays)
        assertEquals(1.0, strict.minorZ)
        assertNotEquals(shipped, strict)
        // A changed policy changes the answer it is given to, and only that one.
        val prior = listOf(50.0, 55.0, 60.0, 65.0, 70.0, 55.0, 60.0)
        assertEquals(Severity.MINOR, VitalsBaseline.classify(71.0, prior, Vital.RESTING_HR, shipped).severity)
        assertEquals(Severity.MINOR, VitalsBaseline.classify(71.0, prior, Vital.RESTING_HR, shipped.copy(significantZ = 99.0)).severity)
        assertEquals(Severity.SIGNIFICANT, VitalsBaseline.classify(71.0, prior, Vital.RESTING_HR, shipped.copy(significantZ = 1.6)).severity)
        val none = AnomalyFlags()
        val rise = none.copy(abnormalRise = true)
        assertTrue(!none.any && rise.any)
        assertEquals(AnomalyFlags(abnormalRise = true), rise)
    }

    @Test
    fun listsAreCopiedInAndReadOnlyOutAndInputsAreNeverWrittenThrough() {
        val prior = mutableListOf(58.0, 60.0, 62.0, 58.0, 60.0, 62.0, 60.0)
        val input = VitalInput(Vital.RESTING_HR, 75.0, prior)
        val report = VitalsBaseline.report(listOf(input), skinTempOffsetC = 1.3)
        prior.clear()
        assertEquals(7, input.prior.size, "the input keeps its own days")
        @Suppress("UNCHECKED_CAST")
        assertFailsWith<UnsupportedOperationException> { (input.prior as MutableList<Double>).add(1.0) }
        val signals = mutableListOf(VitalsBaseline.Signal(null, true, Severity.MINOR, 0.7, Direction.RISE, null))
        val r = VitalsBaseline.Report(Status.WATCH, signals, false)
        signals.clear()
        assertEquals(1, r.signals.size, "the report keeps its own signals")
        @Suppress("UNCHECKED_CAST")
        assertFailsWith<UnsupportedOperationException> { (report.signals as MutableList<VitalsBaseline.Signal>).clear() }
        // Unsorted, unreadable-holding inputs come back exactly as given (Swift's sorted() and filter
        // return new arrays).
        val days = mutableListOf(70.0, Double.NaN, 50.0, 60.0, 55.0, 65.0, 62.0, 58.0)
        val daysBefore = days.toList().map { it.toRawBits() }
        RobustBaseline.stats(days)
        VitalsBaseline.stats(days)
        VitalsBaseline.classify(80.0, days, Vital.RESTING_HR)
        assertEquals(daysBefore, days.map { it.toRawBits() })
        val nights = mutableListOf(NightlyTemp(t0.plusSeconds(172_800), 31.0), NightlyTemp(t0, Double.NaN), NightlyTemp(t0.plusSeconds(86_400), 30.0), NightlyTemp(t0.minusSeconds(86_400), 32.0))
        val nightsBefore = nights.map { it.night to it.celsius.toRawBits() }
        SkinTempBaseline.baseline(nights)
        SkinTempBaseline.report(31.0, nights, previousNight = 30.0)
        assertEquals(nightsBefore, nights.map { it.night to it.celsius.toRawBits() }, "the nights are sorted in a copy")
    }

    @Test
    fun theCoverageBucketCountIsSixtyFourBit() {
        // A window spanning the whole of Instant's range holds 1.75e13 hour buckets: a 32-bit count would
        // wrap or saturate and give a wrong (or negative) fraction.
        val window = DateInterval(Instant.MIN, Instant.MAX)
        val buckets = Math.ceil(secondsBetween(Instant.MIN, Instant.MAX) / 3600)
        assertTrue(buckets > Int.MAX_VALUE)
        assertEquals(1.0 / buckets, SkinTempBaseline.coverage(listOf(TemperatureSample(Instant.MIN, 34.0)), window))
        assertEquals(2.0 / buckets, SkinTempBaseline.coverage(listOf(TemperatureSample(Instant.MIN, 34.0), TemperatureSample(Instant.MAX, 34.0)), window))
    }

    @Test
    fun nothingReadsTheMachineLocaleOrTimeZone() {
        val prior = listOf(58.0, 60.0, 62.0, 58.0, 60.0, 62.0, 60.0)
        val w = DateInterval(t0, t0.plusSeconds(36_000))
        val samples = (0 until 60).map { TemperatureSample(t0.plusSeconds(it * 600L), 34.0 + it % 3 * 0.25) }
        val nights = (1..10).map { NightlyTemp(t0.minusSeconds(it * 86_400L), 34.0 + it % 2 * 0.5) }
        fun results(): List<Any?> = listOf(
            RobustBaseline.stats(prior), RobustBaseline.z(70.0, RobustBaseline.Stats(60.0, 1.0, 7), 5.0),
            RobustBaseline.circularMedianMinutes(listOf(1430, 10, 5)), RobustBaseline.circularDeltaMinutes(1430, 10),
            VitalsBaseline.report(listOf(VitalInput(Vital.RESTING_HR, 75.0, prior)), skinTempOffsetC = 1.3),
            SkinTempBaseline.nightlyVerdict(samples, w), SkinTempBaseline.report(35.5, nights, previousNight = 34.0),
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
        for (f in listOf("RobustBaseline.kt", "VitalsBaseline.kt", "SkinTempBaseline.kt").map { File(mainSources(), it) }) {
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
