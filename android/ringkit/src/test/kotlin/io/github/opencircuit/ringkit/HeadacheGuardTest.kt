package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.HeadacheEvaluation.Metrics
import io.github.opencircuit.ringkit.HeadacheEvaluation.ScoredDay
import io.github.opencircuit.ringkit.HeadacheSignals.AbsentReason
import io.github.opencircuit.ringkit.HeadacheSignals.Band
import io.github.opencircuit.ringkit.HeadacheSignals.Contribution
import io.github.opencircuit.ringkit.HeadacheSignals.DayInput
import io.github.opencircuit.ringkit.HeadacheSignals.Feature
import io.github.opencircuit.ringkit.HeadacheSignals.Series
import io.github.opencircuit.ringkit.HeadacheSignals.Tuning
import io.github.opencircuit.ringkit.HeadacheSignals.Verdict
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.Random
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards for what the Kotlin headache port adds or could lose relative to Swift: every value type is
 * immutable, compares its doubles by IEEE `==` as Swift's synthesized `Equatable` does, and copies its
 * collections in and hands them out read-only (Swift's structs and arrays copy on assignment); the raw
 * names, noise floors, weights and tuning defaults are upstream's, in order; no clock has a default and
 * no source reads one; nothing reads the machine's time zone or locale; and the Swift sort the ranks
 * use is the one `swiftSorted` uses. The unreadable-reading rule and the crash bounds are pinned in
 * `HeadacheHazardTest`.
 */
class HeadacheGuardTest {

    private val t0: Instant = Instant.parse("2026-03-01T13:00:00Z")

    private fun day(rhr: Double = 66.0, eff: Double = 70.0) = DayInput(
        day = t0, now = t0.plusSeconds(8 * 3600), lastRingDataAt = t0,
        restingHR = Series(rhr, List(14) { 60.0 }), hrvSDNN = Series(50.0, List(14) { 50.0 }),
        sleepEfficiencyPct = Series(eff, List(14) { 90.0 }), sleepFragmentationMin = Series(40.0, List(14) { 40.0 }),
        sleepDurationMin = Series(420.0, List(14) { 420.0 }), skinTempOffsetC = 0.4, inBedStartMinutes = 1_400,
        priorInBedStartMinutes = List(14) { 1_380 }, dayHRPrevious = 70.0, dayHRTwoDaysAgo = 78.0, dayHRPrior = List(14) { 70.0 },
        isPerimenstrual = true, priorIndices = List(30) { it % 20 },
    )

    @Test
    fun rawNamesFloorsWeightsAndDefaultsAreUpstreams() {
        // HeadacheSignals.swift:45-105, typed from upstream.
        assertEquals(
            listOf(
                "sleepEfficiencyDrop", "arousalLetdown", "hrvDeviation", "restingHRDeviation", "sleepFragmentation",
                "sleepDurationDeviation", "scheduleShift", "skinTempDeviation", "perimenstrual",
            ),
            Feature.entries.map { it.rawValue },
        )
        assertEquals(listOf(5.0, 0.0, 8.0, 5.0, 15.0, 30.0, 30.0, 0.3, 0.0), Feature.entries.map { it.noiseFloor })
        assertEquals(listOf(0.18, 0.18, 0.14, 0.14, 0.10, 0.10, 0.08, 0.08, 0.20), Feature.entries.map { it.weight })
        assertEquals(1.0, Feature.entries.filter { it.isRingDerived }.sumOf { it.weight }, 1e-12, "the eight ring weights sum to 1.00")
        assertEquals(listOf(Feature.PERIMENSTRUAL), Feature.entries.filter { !it.isRingDerived })
        assertEquals(
            setOf(Feature.SLEEP_EFFICIENCY_DROP, Feature.SLEEP_FRAGMENTATION, Feature.SLEEP_DURATION_DEVIATION, Feature.HRV_DEVIATION, Feature.RESTING_HR_DEVIATION),
            Feature.ANCHORS,
        )
        assertEquals(listOf("noBaseline", "noDataThisDay", "featureDisabled", "lowCoverage", "notApplicable"), AbsentReason.entries.map { it.rawValue })
        assertEquals(listOf(0, 1, 2), Band.entries.map { it.rawValue })
        assertTrue(Band.TYPICAL < Band.ELEVATED && Band.ELEVATED < Band.FLAGGED, "ordered by raw value, as upstream's Comparable")
        assertEquals(listOf("fever", "headacheAlreadyLogged"), HeadacheSignals.Suppression.entries.map { it.rawValue })
        assertEquals(listOf("all", "preUnlock", "postUnlock"), HeadacheEvaluation.Scope.entries.map { it.rawValue })
        assertEquals(listOf("noBetterThanChance", "noUsefulPrecisionGain"), HeadacheEvaluation.Reason.entries.map { it.rawValue })

        // Tuning defaults (HeadacheSignals.swift:126-176, HeadacheEvaluation.swift:186-270).
        val t = Tuning()
        assertEquals(
            listOf(1.0, 2.5, 0.5, 1.0, 0.35, 0.5, 0.75, 0.90, 0.5, 24.0),
            listOf(
                t.onsetZ, t.saturationZ, t.tempOnsetC, t.tempSaturationC, t.maxSingleFeatureShare, t.truncatedSleepQuality,
                t.elevatedPercentile, t.flaggedPercentile, t.contributingThreshold, t.dataGapHours,
            ),
        )
        assertEquals(listOf(4, 3, 21, 60, 3), listOf(t.minRingFeaturesForScore, t.maxCapPasses, t.minDaysForBanding, t.bandWindowDays, t.minContributingFeatures))
        val e = HeadacheEvaluation.Tuning()
        assertEquals(
            listOf(365, 21, 120, 8, 180, 8, 10),
            listOf(
                e.evaluationWindowDays, e.minFrozenDaysForNotification, e.minScoredDaysForWorking, e.minPositivesForWorking,
                e.minScoredDaysForRetirement, e.minPositivesForRetirement, e.minFlaggedForRetirement,
            ),
        )
        assertEquals(
            listOf(24.0, 24.0, 0.01, 0.5, 0.05, 1.96, 1.645),
            listOf(e.outcomeWindowHours, e.inProgressLookbackHours, e.workingAlpha, e.chanceAUC, e.minUsefulPrecisionGain, e.ciZ, e.equivalenceZ),
        )
        // The notification floor is read live from the banding floor (one home): moving one moves the other.
        assertEquals(Tuning().minDaysForBanding, HeadacheEvaluation.Tuning().minFrozenDaysForNotification)
    }

    @Test
    fun valuesAreImmutableAndCompareAsSwiftDoes() {
        val types = listOf(
            Tuning::class.java, Series::class.java, DayInput::class.java, Contribution::class.java, HeadacheSignals.Assessment::class.java,
            Verdict.InsufficientData::class.java, ScoredDay::class.java, Metrics::class.java, HeadacheEvaluation.Tuning::class.java,
        )
        for (type in types) assertEquals(emptyList(), type.methods.filter { it.name.startsWith("set") }.map { it.name }, "no setters on ${type.simpleName}")

        // Swift's `==` on doubles: −0.0 equals 0.0 (and hashes alike), NaN is unequal even to itself.
        assertEquals(Tuning(onsetZ = 0.0), Tuning(onsetZ = -0.0))
        assertEquals(Tuning(onsetZ = 0.0).hashCode(), Tuning(onsetZ = -0.0).hashCode())
        assertNotEquals(Tuning(onsetZ = Double.NaN), Tuning(onsetZ = Double.NaN))
        assertNotEquals(Tuning(), Tuning(maxCapPasses = 4))
        assertEquals(HeadacheEvaluation.Tuning(ciZ = 0.0), HeadacheEvaluation.Tuning(ciZ = -0.0))
        assertNotEquals(HeadacheEvaluation.Tuning(ciZ = Double.NaN), HeadacheEvaluation.Tuning(ciZ = Double.NaN))
        assertEquals(Series(0.0, listOf(-0.0)), Series(-0.0, listOf(0.0)))
        assertEquals(Series(0.0, listOf(-0.0)).hashCode(), Series(-0.0, listOf(0.0)).hashCode())
        assertNotEquals(Series(Double.NaN, emptyList()), Series(Double.NaN, emptyList()))
        assertEquals(Contribution(Feature.HRV_DEVIATION, 0.0, -0.0, 0.14, null), Contribution(Feature.HRV_DEVIATION, -0.0, 0.0, 0.14, null))
        assertNotEquals(Contribution(Feature.HRV_DEVIATION, Double.NaN, 0.0, 0.14, null), Contribution(Feature.HRV_DEVIATION, Double.NaN, 0.0, 0.14, null))
        assertNotEquals(Contribution(Feature.HRV_DEVIATION, null, null, 0.0, null), Contribution(Feature.HRV_DEVIATION, 0.0, null, 0.0, null), "absent is not 0")
        val m = HeadacheEvaluation.metrics(emptyList(), now = t0)
        assertEquals(m.copy(baseRate = 0.0), m.copy(baseRate = -0.0))
        assertEquals(m.copy(baseRate = 0.0).hashCode(), m.copy(baseRate = -0.0).hashCode())
        assertNotEquals(m.copy(pValue = Double.NaN), m.copy(pValue = Double.NaN))
        assertNotEquals(m, m.copy(pValue = 0.0), "absent is not 0")
        assertEquals(day(), day(), "two days built alike are equal")
        assertEquals(day().hashCode(), day().hashCode())
        assertNotEquals(day(), day().copy(skinTempOffsetC = Double.NaN))
        assertEquals(day().copy(skinTempOffsetC = 0.0), day().copy(skinTempOffsetC = -0.0))
        // A copy never changes the original.
        val d = day()
        val e = d.copy(skinTempOffsetC = 1.0, priorIndices = emptyList())
        assertEquals(0.4, d.skinTempOffsetC)
        assertEquals(30, d.priorIndices.size)
        assertEquals(1.0, e.skinTempOffsetC)
        assertEquals(0, e.priorIndices.size)
    }

    @Test
    fun collectionsAreCopiedInAndHandedOutReadOnly() {
        val prior = mutableListOf(60.0, 61.0)
        val s = Series(60.0, prior)
        prior[0] = 999.0
        assertEquals(listOf(60.0, 61.0), s.prior, "the caller's list is copied in")
        @Suppress("UNCHECKED_CAST")
        assertFailsWith<UnsupportedOperationException> { (s.prior as MutableList<Double>).add(1.0) }

        val bed = mutableListOf(1_380, 1_390)
        val hr = mutableListOf(70.0)
        val indices = mutableListOf(1, 2, 3)
        val input = DayInput(day = t0, now = t0, priorInBedStartMinutes = bed, dayHRPrior = hr, priorIndices = indices)
        bed.clear()
        hr.clear()
        indices.clear()
        assertEquals(listOf(1_380, 1_390), input.priorInBedStartMinutes)
        assertEquals(listOf(70.0), input.dayHRPrior)
        assertEquals(listOf(1, 2, 3), input.priorIndices)
        for (list in listOf<List<*>>(input.priorInBedStartMinutes, input.dayHRPrior, input.priorIndices)) {
            @Suppress("UNCHECKED_CAST")
            assertFailsWith<UnsupportedOperationException> { (list as MutableList<Any?>).clear() }
        }

        val a = (HeadacheSignals.assess(day()) as Verdict.Scored).assessment
        @Suppress("UNCHECKED_CAST")
        assertFailsWith<UnsupportedOperationException> { (a.contributions as MutableList<Contribution>).clear() }
        @Suppress("UNCHECKED_CAST")
        assertFailsWith<UnsupportedOperationException> { (Feature.ANCHORS as MutableSet<Feature>).add(Feature.PERIMENSTRUAL) }

        val missing = mutableMapOf(Feature.HRV_DEVIATION to AbsentReason.NO_BASELINE)
        val v = Verdict.InsufficientData(missing)
        missing[Feature.SLEEP_FRAGMENTATION] = AbsentReason.NO_DATA_THIS_DAY
        assertEquals(mapOf(Feature.HRV_DEVIATION to AbsentReason.NO_BASELINE), v.missing)
        @Suppress("UNCHECKED_CAST")
        assertFailsWith<UnsupportedOperationException> { (v.missing as MutableMap<Feature, AbsentReason>).clear() }

        // Inputs are never changed: the cap works on its own copy; the evaluation only reads its rows.
        val contributions = a.contributions.toMutableList()
        val snapshot = contributions.toList()
        val capped = HeadacheSignals.applySingleFeatureCap(contributions, Tuning(maxSingleFeatureShare = 0.1))
        assertEquals(snapshot, contributions)
        assertNotEquals(snapshot, capped)
        @Suppress("UNCHECKED_CAST")
        assertFailsWith<UnsupportedOperationException> { (capped as MutableList<Contribution>).clear() }
        val rows = MutableList(30) { i -> ScoredDay(t0.plusSeconds(86_400L * i), t0.plusSeconds(86_400L * i + 36_000), i, Band.TYPICAL) }
        val rowsSnapshot = rows.toList()
        HeadacheEvaluation.status(rows, now = t0.plusSeconds(86_400L * 40))
        assertEquals(rowsSnapshot, rows)
    }

    @Test
    fun noClockParameterHasADefaultAndNoSourceLineReadsOne() {
        // The only defaulted parameters are upstream's tunings, contributions and scope; `now` is a
        // required parameter wherever upstream takes one (the day's own `now` included).
        val signalDefaults = HeadacheSignals::class.java.declaredMethods.filter { it.name.endsWith("\$default") }.map { it.name }.sorted()
        // (`assess$add$default` is assess's local helper: an absent reason and a quality factor.)
        assertEquals(listOf("assess\$add\$default", "assess\$default", "band\$default"), signalDefaults)
        val evalDefaults = HeadacheEvaluation::class.java.declaredMethods.filter { it.name.endsWith("\$default") }.map { it.name }.sorted()
        assertEquals(listOf("meetsWorkingBar\$default", "metrics\$default", "shouldRetire\$default", "status\$default"), evalDefaults)

        val root = File(assertNotNull(System.getProperty("opencircuit.androidRoot"), "system property opencircuit.androidRoot is not set"))
        // A defaulted instant, zone, locale or clock — other than "none" (null) or a copy's own value.
        val defaultedClock = Regex("""\w+\s*:\s*(Instant|ZoneId|Locale|Clock|TimeZone)\??\s*=(?!\s*(?:null\b|this\.))""")
        val ambient = Regex("""\b[A-Z]\w*\.now\(|\bClock\.system|\bsystemDefault\(|\bTimeZone\.getDefault\(|\bLocale\.getDefault\(|currentTimeMillis\(|nanoTime\(|getenv\b""")
        val hits = listOf("HeadacheSignals.kt", "HeadacheEvaluation.kt", "SwiftNumerics.kt").flatMap { name ->
            val source = File(root, "ringkit/src/main/kotlin/io/github/opencircuit/ringkit/$name")
            assertTrue(source.isFile, "missing source $name")
            StrippedSource(name, source.readText()).lines.withIndex()
                .filter { (_, line) -> defaultedClock.containsMatchIn(line) || ambient.containsMatchIn(line) }
                .map { "$name:${it.index + 1}: ${it.value.trim()}" }
        }
        assertEquals(emptyList(), hits, "a defaulted or ambient instant, zone, locale or clock")
        assertTrue(defaultedClock.containsMatchIn("fun f(now: Instant = Instant.EPOCH)"), "the default pattern bites")
        assertFalse(defaultedClock.containsMatchIn("val lastRingDataAt: Instant? = null,"), "\"none\" is not a clock")
        assertTrue(ambient.containsMatchIn("val t = Instant.now()") && ambient.containsMatchIn("ZoneId.systemDefault()"), "the ambient pattern bites")
    }

    @Test
    fun nothingReadsTheMachineTimeZoneOrLocale() {
        val rows = List(200) { i ->
            ScoredDay(
                t0.plusSeconds(86_400L * i), t0.plusSeconds(86_400L * i + 36_000), (i * 37) % 101,
                if (i % 10 == 0) Band.FLAGGED else Band.TYPICAL, headacheOnset = if (i % 7 == 0) t0.plusSeconds(86_400L * i + 50_000) else null,
            )
        }
        fun results(): List<Any?> = listOf(
            HeadacheSignals.assess(day()),
            HeadacheSignals.assess(day(rhr = 80.0, eff = 60.0).copy(lastRingDataAt = t0.minusSeconds(86_400))),
            HeadacheSignals.band(index = 30, priorIndices = List(40) { it }),
            HeadacheEvaluation.status(rows, now = t0.plusSeconds(86_400L * 201)),
            HeadacheEvaluation.metrics(rows, now = t0.plusSeconds(86_400L * 201), scope = HeadacheEvaluation.Scope.POST_UNLOCK),
            Feature.entries.map { it.rawValue },
        )
        val savedLocale = Locale.getDefault()
        val savedZone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.ROOT)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val reference = results()
            for ((locale, tz) in listOf(Locale.forLanguageTag("ar-EG") to "Pacific/Kiritimati", Locale.forLanguageTag("tr-TR") to "America/Santiago")) {
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
    fun theRanksUseTheSameSwiftSortAsSwiftSorted() {
        // One sort, two views: the permutation midranks reads is the one swiftSorted applies, bit for
        // bit, on lists with ties, signed zeros and NaN, on both sides of the 20-value insertion sort.
        val pool = doubleArrayOf(Double.NaN, -0.0, 0.0, 1.0, 2.0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.5)
        val rng = Random(20_261_003)
        repeat(2_000) {
            val xs = List(rng.nextInt(80)) { pool[rng.nextInt(pool.size)] }
            val order = swiftSortedIndices(xs.toDoubleArray())
            assertEquals(swiftSorted(xs).map { it.toRawBits() }, order.map { xs[it].toRawBits() }, "$xs")
            assertEquals(xs.indices.toSet(), order.toSet(), "a permutation of the positions")
        }
        // And Swift's == for optional doubles.
        assertTrue(ieeeEquals(0.0, -0.0) && ieeeEquals(null, null))
        assertTrue(!ieeeEquals(Double.NaN, Double.NaN) && !ieeeEquals(null, 0.0) && !ieeeEquals(0.0, null))
    }
}
