package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * Guards for what Kotlin adds to the ported provenance layer, where Swift's types guaranteed it for
 * free: every constant equals upstream's literal; values hold their own lists (Swift arrays copy on
 * assignment) and have no setters; a breakdown compares by value including its tuning (Swift's
 * synthesized `Equatable` reads every stored property); the health-store partition is idempotent; and
 * no answer or sentence reads the machine's zone, locale or clock.
 */
class SleepProvenanceGuardTest {

    private val t0: Instant = Instant.ofEpochSecond(1_700_000_000)
    private fun at(m: Long): Instant = t0.plusSeconds(m * 60)
    private fun noSetters(type: Class<*>): List<String> = type.methods.filter { it.name.startsWith("set") }.map { it.name }

    /** A night with every provenance, an in-bed layer, a hole and a reversed segment. */
    private val mixed: List<SleepSegment>
        get() = listOf(
            SleepSegment(at(0), at(480), SleepStage.IN_BED),
            SleepSegment(at(480), at(600), SleepStage.IN_BED, SleepProvenance.ASSERTED),
            SleepSegment(at(0), at(60), SleepStage.AWAKE, SleepProvenance.ASSERTED_OVER_MEASURED),
            SleepSegment(at(60), at(400), SleepStage.ASLEEP_CORE),
            SleepSegment(at(400), at(480), SleepStage.ASLEEP_DEEP, SleepProvenance.ASSERTED_COVERAGE_UNKNOWN),
            SleepSegment(at(480), at(600), SleepStage.ASLEEP_REM, SleepProvenance.ASSERTED),
            SleepSegment(at(700), at(650), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED),
        )

    @Test
    fun constantsEqualUpstreamLiterals() {
        assertEquals(300.0, BedtimeProvenance.CONTINUOUS_TOLERANCE_SECONDS) // BedtimeProvenance.swift:48
        assertEquals(1800.0, BedtimeProvenance.PRIOR_EVIDENCE_WINDOW_SECONDS) // :54, 30 * 60
        assertEquals(300.0, WakeProvenance.CONTINUOUS_TOLERANCE_SECONDS) // WakeProvenance.swift:63, aliased
        assertEquals(3600.0, WakeProvenance.MATERIAL_GAP_SECONDS) // :90, 60 * 60
        assertEquals(300.0, WakeProvenance.RESUME_RUN_MAX_SECONDS) // :139, aliased to the tolerance
        val tuning = SleepProvenanceBreakdown.Tuning.DEFAULT // SleepProvenanceBreakdown.swift:85-87
        assertEquals(10_800.0, tuning.minCoveredInBedForEfficiency)
        assertEquals(0.75, tuning.minCoverageForScore)
        assertEquals(true, tuning.withholdingEnabled)
        assertEquals(SleepProvenanceBreakdown.Tuning(10_800.0, 0.75, false), SleepProvenanceBreakdown.Tuning.NEVER_WITHHOLD) // :95
        // ExportCoverage.swift: cadence = the 150 s epoch, minimum gap = two epochs (a 300 s step is not a gap, 301 s is).
        val samples = listOf(t0, t0.plusSeconds(300), t0.plusSeconds(601))
        assertEquals(
            ExportCoverage.assess(samples, t0, t0.plusSeconds(601), cadence = 150.0, minGap = 300.0),
            ExportCoverage.assess(samples, t0, t0.plusSeconds(601)),
        )
        assertEquals(1, ExportCoverage.assess(samples, t0, t0.plusSeconds(601)).gaps.size)
    }

    @Test
    fun valuesHaveNoSettersAndHoldTheirOwnLists() {
        for (type in listOf(
            SleepProvenanceBreakdown::class.java, SleepProvenanceBreakdown.Tuning::class.java, SleepProvenanceBreakdown.Minutes::class.java,
            SleepHealthPublication::class.java, WakeProvenance.Stoppage::class.java, ExportCoverage.Assessment::class.java, ExportCoverage.Gap::class.java,
            BedtimeProvenance.Verdict.ResumedAfterGap::class.java, WakeProvenance.Verdict.StoppedThenResumed::class.java,
        )) {
            assertEquals(emptyList(), noSetters(type), type.simpleName)
        }

        val seg = SleepSegment(at(0), at(60), SleepStage.ASLEEP_CORE)
        val source = arrayListOf(seg)
        val publication = SleepHealthPublication(source, source, source, source)
        source.clear()
        assertEquals(SleepHealthPublication(listOf(seg), listOf(seg), listOf(seg), listOf(seg)), publication, "changing the lists a publication was built from never changes it")
        assertFailsWith<UnsupportedOperationException> { (publication.published as MutableList<SleepSegment>).clear() }
        assertFailsWith<UnsupportedOperationException> { (publication.userEntered as MutableList<SleepSegment>).add(seg) }

        val night = ArrayList(mixed)
        val partition = night.healthPublication
        night.clear()
        assertEquals(mixed, partition.published, "the partition keeps its own copy of the night it split")

        val gap = ExportCoverage.Gap(t0, t0.plusSeconds(600))
        val gaps = arrayListOf(gap)
        val assessment = ExportCoverage.Assessment(t0, t0.plusSeconds(600), 4, 0, 0.0, gaps, 600.0)
        gaps.clear()
        assertEquals(listOf(gap), assessment.gaps)
        assertFailsWith<UnsupportedOperationException> { (assessment.gaps as MutableList<ExportCoverage.Gap>).add(gap) }
    }

    @Test
    fun aBreakdownComparesByValueIncludingItsTuning() {
        val a = SleepProvenanceBreakdown(mixed)
        val b = SleepProvenanceBreakdown(ArrayList(mixed))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, SleepProvenanceBreakdown(mixed, SleepProvenanceBreakdown.Tuning.NEVER_WITHHOLD), "same totals, different tuning: different values, as upstream")
        assertNotEquals(a, SleepProvenanceBreakdown(mixed.dropLast(2)))
        // A copied tuning never changes the shared default.
        val tighter = SleepProvenanceBreakdown.Tuning.DEFAULT.copy(minCoverageForScore = 0.9)
        assertEquals(0.9, tighter.minCoverageForScore)
        assertEquals(0.75, SleepProvenanceBreakdown.Tuning.DEFAULT.minCoverageForScore)
    }

    @Test
    fun theHealthStorePartitionIsIdempotent() {
        for (night in listOf(mixed, mixed.reversed(), emptyList(), mixed.filter { it.provenance.isProvenUnmeasured })) {
            val once = night.healthPublication
            val twice = once.published.healthPublication
            assertEquals(once, twice, "publishing what was published changes nothing")
            assertEquals(night, once.published, "the write set is the night, in order")
            assertEquals(once.userEntered, night.healthUserEntered)
            assertEquals(night.healthPublishable, night.healthPublishable.healthPublishable)
            assertEquals(night.withheldSpans, night.healthPublishable.withheldSpans)
            assertEquals(once.measured.size + once.userEntered.size, once.published.size, "every segment lands in exactly one written bucket")
        }
    }

    /** No zone is involved and no clock is read: no answer, and no sentence, moves with the machine's zone or locale. */
    @Test
    fun nothingReadsTheMachineZoneLocaleOrClock() {
        val far = Instant.ofEpochSecond(10_000_000_000_000_000)
        val near = Instant.ofEpochSecond(-10_000_000_000_000_000)
        fun results(): List<Any?> = listOf(
            BedtimeProvenance.classify(t0, t0.minusMillis(300_001), t0.minusSeconds(86_400)),
            WakeProvenance.stoppage(t0, listOf(t0.plusSeconds(60), t0.plusSeconds(210), t0.plusSeconds(12_810)), t0.minusSeconds(86_400)),
            SleepProvenanceBreakdown(mixed),
            SleepProvenanceBreakdown(mixed).withheldReason,
            SleepProvenanceBreakdown(mixed).minutes,
            SleepProvenanceBreakdown(List(3) { SleepSegment(near, far, SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED) }).withheldReason,
            mixed.healthPublication,
            SleepProvenanceRederivation.upgraded(mixed, MeasuredCoverage.ofRecordDates(listOf(at(500), at(502)), Duration.ofSeconds(150))),
            ExportCoverage.assess(listOf(t0, t0.plusSeconds(150), t0.plusSeconds(1200)), t0, t0.plusSeconds(1500)),
        )

        val savedLocale = Locale.getDefault()
        val savedZone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.ROOT)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val baseline = results()
            assertEquals("1000000000000000 min of this night's 1000000000000000 min in-bed window holds no ring data", baseline[5])
            for ((locale, tz) in listOf(
                Locale.forLanguageTag("ar-EG") to "Pacific/Kiritimati",
                Locale.forLanguageTag("fa-IR") to "Asia/Tehran",
                Locale.forLanguageTag("hi-IN-u-nu-deva") to "America/St_Johns",
                Locale.forLanguageTag("tr-TR") to "Asia/Kathmandu",
            )) {
                Locale.setDefault(locale)
                TimeZone.setDefault(TimeZone.getTimeZone(tz))
                assertEquals(baseline, results(), "default locale $locale, default zone $tz")
            }
        } finally {
            Locale.setDefault(savedLocale)
            TimeZone.setDefault(savedZone)
        }
    }
}
