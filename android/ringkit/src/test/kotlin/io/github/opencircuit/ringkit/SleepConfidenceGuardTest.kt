package io.github.opencircuit.ringkit

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * Guards for what Kotlin adds to the ported sleep-confidence layer, where Swift's types guaranteed it
 * for free: every constant equals upstream's literal; values hold their own lists (Swift arrays copy on
 * assignment) and have no setters; values compare as Swift's synthesized `Equatable` does (`Double`
 * fields by IEEE `==`, measured on the pinned Swift build); and no answer or sentence reads the
 * machine's zone, locale or clock.
 */
class SleepConfidenceGuardTest {

    private val end: Instant = Instant.ofEpochSecond(1_787_013_422)
    private val start: Instant = end.minusMillis(15_157_002)
    private fun noSetters(type: Class<*>): List<String> = type.methods.filter { it.name.startsWith("set") }.map { it.name }

    private fun bothEdges(cut: Double = WakeProvenance.MATERIAL_GAP_SECONDS): SleepConfidence.Assessment =
        SleepConfidence.assess(
            asleep = 34_320.0, inBed = 34_740.0,
            coverage = SleepConfidence.Coverage(start, end, start.minusSeconds(14_478), end.plusSeconds(14_515), emptyList(), start.minusSeconds(14 * 86_400)),
            materialGapSeconds = cut,
        )

    @Test
    fun constantsEqualUpstreamLiterals() {
        assertEquals(18_000.0, SleepConfidence.MIN_NIGHT_FOR_FLAG) // SleepConfidence.swift:31, 5 * 3600
        assertEquals(0.95, SleepConfidence.IMPLAUSIBLE_EFFICIENCY) // :36
        assertEquals(1.15, SleepWindowCaption.CONTIGUOUS_TOLERANCE) // SleepWindowCaption.swift:57
        assertEquals(60.0, SleepWindowCaption.MINIMUM_LATENCY) // :74
        assertEquals(14_400.0, SleepWindowCaption.MAXIMUM_LATENCY) // :75, 4 * 3600
        assertEquals("recorded", ExportEngine.SleepEdgeProvenanceRow.DURATION_BASIS_RECORDED) // ExportEngine.swift:389
        assertEquals("edited", ExportEngine.SleepEdgeProvenanceRow.DURATION_BASIS_EDITED) // :392
        // SleepConfidenceCoverage.swift:222 — the default cut IS WakeProvenance's constant.
        assertEquals(WakeProvenance.MATERIAL_GAP_SECONDS, bothEdges().materialGapSeconds)
        val row = ExportEngine.SleepEdgeProvenanceRow(start, end, bothEdges())
        assertEquals(ExportEngine.SleepEdgeProvenanceRow.DURATION_BASIS_RECORDED, row.durationBasis)
        // The row built from the assessment says exactly what the assessment says (ExportEngine.swift:416-427).
        assertEquals(
            ExportEngine.SleepEdgeProvenanceRow(
                start, end, "resumedAfterGap", 14_478.0, "stoppedThenResumed", 14_515.0,
                listOf("noRecordingAfterWake", "noRecordingBeforeBedtime"), 3_600.0, "recorded",
            ),
            row,
        )
    }

    @Test
    fun valuesHaveNoSettersAndHoldTheirOwnLists() {
        for (type in listOf(
            SleepConfidence.Coverage::class.java, SleepConfidence.Assessment::class.java, SleepConfidence.Hint::class.java,
            SleepConfidence.Reason.NoRecordingAfterWake::class.java, SleepConfidence.Reason.NoRecordingBeforeBedtime::class.java,
            ExportEngine.SleepEdgeProvenanceRow::class.java,
        )) {
            assertEquals(emptyList(), noSetters(type), type.simpleName)
        }

        val series = arrayListOf(end.plusSeconds(60), end.plusSeconds(120))
        val coverage = SleepConfidence.Coverage(start, end, null, null, series, null)
        series.clear()
        assertEquals(listOf(end.plusSeconds(60), end.plusSeconds(120)), coverage.measurementsAfterEnd, "changing the list a coverage was built from never changes it")
        assertFailsWith<UnsupportedOperationException> { (coverage.measurementsAfterEnd as MutableList<Instant>).clear() }

        val a = bothEdges()
        assertFailsWith<UnsupportedOperationException> { (a.reasons as MutableList<SleepConfidence.Reason>).clear() }

        val names = arrayListOf("noRecordingAfterWake")
        val row = ExportEngine.SleepEdgeProvenanceRow(start, end, "witnessed", null, "stoppedThenResumed", 14_515.0, names, 3_600.0)
        names.clear()
        assertEquals(listOf("noRecordingAfterWake"), row.reasons)
        assertFailsWith<UnsupportedOperationException> { (row.reasons as MutableList<String>).add("x") }
    }

    /** Measured on the pinned build: NaN is unequal to itself, -0.0 equals 0.0 — in every value carrying a `Double`. */
    @Test
    fun valuesCompareAsSwiftsSynthesizedEquatable() {
        val nanCut = bothEdges(Double.NaN)
        assertNotEquals(nanCut, nanCut, "an assessment with a NaN cut is not equal to itself (Swift: false)")
        val zero = SleepConfidence.assess(1.0, 2.0, null, 0.0)
        val negativeZero = SleepConfidence.assess(1.0, 2.0, null, -0.0)
        assertEquals(zero, negativeZero, "0.0 and -0.0 cuts are equal (Swift: true)")
        assertEquals(zero.hashCode(), negativeZero.hashCode())

        val nanReason = SleepConfidence.Reason.NoRecordingAfterWake(end, Double.NaN)
        assertNotEquals<SleepConfidence.Reason>(nanReason, nanReason)
        val r0 = SleepConfidence.Reason.NoRecordingAfterWake(end, 0.0)
        val rm0 = SleepConfidence.Reason.NoRecordingAfterWake(end, -0.0)
        assertEquals(r0, rm0)
        assertEquals(r0.hashCode(), rm0.hashCode())
        val b0 = SleepConfidence.Reason.NoRecordingBeforeBedtime(end, 0.0)
        val bm0 = SleepConfidence.Reason.NoRecordingBeforeBedtime(end, -0.0)
        assertEquals(b0, bm0)
        assertEquals(b0.hashCode(), bm0.hashCode())
        assertEquals(SleepConfidence.Hint(r0, "sunrise", "x"), SleepConfidence.Hint(rm0, "sunrise", "x"))

        val nanRow = ExportEngine.SleepEdgeProvenanceRow(start, end, bothEdges(Double.NaN))
        assertNotEquals(nanRow, nanRow, "a row carrying a NaN cut is not equal to itself (Swift: false)")
        val row0 = ExportEngine.SleepEdgeProvenanceRow(start, end, "x", 0.0, "y", null, emptyList(), 0.0)
        val rowM0 = ExportEngine.SleepEdgeProvenanceRow(start, end, "x", -0.0, "y", null, emptyList(), -0.0)
        assertEquals(row0, rowM0, "0.0 and -0.0 gaps and cuts are equal (Swift: true)")
        assertEquals(row0.hashCode(), rowM0.hashCode())
        assertNotEquals(row0, ExportEngine.SleepEdgeProvenanceRow(start, end, "x", null, "y", null, emptyList(), 0.0), "absent is not zero")
    }

    /** No zone is involved and no clock is read: no answer, and no sentence, moves with the machine's zone or locale. */
    @Test
    fun nothingReadsTheMachineZoneLocaleOrClock() {
        val hhmm = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC)
        val ampm = DateTimeFormatter.ofPattern("h:mm a", Locale.US).withZone(ZoneOffset.UTC)
        val clock: (Instant) -> String = { hhmm.format(it) }
        val at: (Long) -> Instant = { Instant.ofEpochSecond(it) }
        fun results(): List<Any?> {
            val a = bothEdges(0.0)
            return listOf(
                SleepConfidence.classify(34_320.0, 34_740.0),
                a,
                SleepConfidence.hints(a, clock),
                SleepConfidence.hints(SleepConfidence.assess(34_320.0, 34_740.0, SleepConfidence.Coverage(start, end, start.minusSeconds(60), end.plusSeconds(60), emptyList(), null)), clock),
                listOf(0.0, 90.0, 3_630.0, 1e15, 5.534e20).map { SleepConfidence.approximateDuration(it) },
                SleepWindowCaption.line(at(1_440), at(31_740), at(0), 30_600.0) { ampm.format(it) },
                SleepWindowCaption.line(at(5_040), at(44_820), at(4_920), 15_660.0) { ampm.format(it) },
                SleepWindowCaption.line(at(0), at(86_400), at(-14_399), 86_400.0) { it.epochSecond.toString() },
                a.reasons.map { SleepConfidence.exportName(it) },
                ExportEngine.SleepEdgeProvenanceRow(start, end, a),
            )
        }

        val savedLocale = Locale.getDefault()
        val savedZone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.ROOT)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val baseline = results()
            assertEquals("Asleep 0–86400 · 240m to fall asleep", baseline[7])
            assertEquals("Asleep 12:24 AM–8:49 AM · 24m to fall asleep", baseline[5])
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
