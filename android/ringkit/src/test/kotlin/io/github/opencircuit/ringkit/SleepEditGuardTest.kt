package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Guards for what the Kotlin edit port adds or could lose relative to Swift: the constants (typed from
 * upstream's sources), value semantics of every edit value (Swift structs copy; here nothing has a
 * setter), fresh byte arrays out of the codec (a Swift `Data` is a value), inputs left untouched,
 * re-applying an edit exactly as upstream does, and no read of the machine's locale or time zone —
 * the codec and every edit answer the same under default locales that print non-ASCII digits.
 */
class SleepEditGuardTest {

    private val ref: Instant = Instant.ofEpochSecond(1_700_000_000)
    private fun at(h: Double): Instant = ref.plusSeconds((h * 3600).toLong())
    private fun seg(a: Double, b: Double, s: SleepStage) = SleepSegment(at(a), at(b), s)
    private fun off(t: Instant): Long = Duration.between(ref, t).seconds
    private fun describe(segs: List<SleepSegment>): String =
        segs.joinToString(" ") { "(${off(it.start)},${off(it.end)},${it.stage.rawValue},${it.provenance.rawValue})" }
    private fun noSetters(type: Class<*>): List<String> = type.methods.filter { it.name.startsWith("set") }.map { it.name }

    @Test
    fun constantsEqualUpstreamLiterals() {
        // SleepEdit.swift :17, :30, :51; Foundation's Date.distantPast as the pinned build prints it.
        assertEquals(Duration.ofHours(3), SleepEdit.EDIT_MARGIN)
        assertEquals(Duration.ofHours(6), SleepEdit.STRANDED_EDIT_MARGIN)
        assertEquals(Duration.ofHours(14), SleepEdit.DEFAULT_MAX_NIGHT_SPAN)
        assertEquals(-62_135_769_600L, SleepEdit.DISTANT_PAST.epochSecond)
        // NapEdit.swift :12, :15 — the minimum has one home, the auto-detector's.
        assertTrue(NapEdit.MIN_DURATION === NapDetection.MIN_NAP_DURATION)
        assertEquals(Duration.ofMinutes(15), NapEdit.MIN_DURATION)
        assertEquals(Duration.ofHours(6), NapEdit.MAX_DURATION)
        // SleepEditLabel.swift :63, :112
        assertEquals(3.0, SleepEditLabels.MINIMUM_CORRECTION_MINUTES)
        assertEquals(10, SleepEditLabels.MINIMUM_NIGHTS_TO_FIT)
    }

    /** Swift structs copy on assignment; here every edit value is immutable and compares by value. */
    @Test
    fun editValuesHaveNoSettersAndCompareByValue() {
        for (type in listOf(
            SleepEdit.Bounds::class.java, SleepEdit.RecordedWindow::class.java, SleepEdit.Window::class.java,
            SleepEdit.Times::class.java, SleepEdit.Invalid.TooShort::class.java, SleepEdit.Invalid.TooLong::class.java,
            NapEdit.Window::class.java, NapEdit.Invalid.TooShort::class.java, NapEdit.Invalid.TooLong::class.java,
            SleepEditLabel::class.java, SleepEditLabels.Accuracy::class.java,
        )) {
            assertEquals(emptyList(), noSetters(type), type.simpleName)
        }
        val stored = SleepEdit.RecordedWindow(at(3.0), at(9.0), at(3.5), at(8.5))
        val widened = stored.copy(inBedEnd = at(10.0))
        assertEquals(at(9.0), stored.inBedEnd, "a copy never changes its source")
        assertEquals(SleepEdit.RecordedWindow(at(3.0), at(9.0), at(3.5), at(8.5)), stored)
        assertTrue(widened != stored)
        assertEquals(SleepEdit.Times(at(0.0), at(1.0), at(8.0)), SleepEdit.Times(at(0.0), at(1.0), at(8.0)))
        assertEquals(SleepEdit.Invalid.TooLong(840), SleepEdit.Invalid.TooLong(840))
    }

    /** A Swift `Data` is a value: each encode is a new array, and decode keeps nothing of its input. */
    @Test
    fun theCodecHandsOutFreshArraysAndKeepsNothingOfItsInput() {
        val segs = listOf(seg(0.0, 1.0, SleepStage.ASLEEP_DEEP), seg(1.0, 2.0, SleepStage.ASLEEP_REM))
        val a = SleepHypnogramCodec.encode(segs)
        val b = SleepHypnogramCodec.encode(segs)
        assertNotSame(a, b)
        a[2] = '9'.code.toByte()
        assertContentEquals(b, SleepHypnogramCodec.encode(segs), "changing one encoding leaves the next alone")

        val stored = SleepHypnogramCodec.encode(segs)
        val decoded = SleepHypnogramCodec.decode(stored)
        stored.fill(0)
        assertEquals(segs, decoded, "a decoded night does not change when its stored bytes do")
    }

    /** Swift arrays are values: recompute neither sorts nor edits the caller's list, and is a pure function. */
    @Test
    fun recomputeLeavesItsInputAloneAndAnswersTheSameTwice() {
        val base = mutableListOf(seg(5.0, 8.0, SleepStage.ASLEEP_DEEP), seg(0.0, 3.0, SleepStage.ASLEEP_CORE), seg(0.0, 8.0, SleepStage.IN_BED))
        val before = base.toList()
        val coverage = MeasuredCoverage(listOf(DateInterval(at(0.0), at(6.0))))
        val w = SleepEdit.Window(at(-1.0), at(9.0))
        val t = SleepEdit.Times(at(-1.0), at(0.5), at(9.0))
        val first = listOf(SleepEdit.recompute(base, w, coverage = coverage), SleepEdit.recompute(base, t, coverage = coverage))
        assertEquals(before, base, "the caller's segments are unchanged, order included")
        assertEquals(first, listOf(SleepEdit.recompute(base, w, coverage = coverage), SleepEdit.recompute(base, t, coverage = coverage)))
    }

    /**
     * Re-applying an edit to its own result, as measured on the pinned build over the same grid of
     * windows: idempotent for nights without an interior gap (every window form, with and without
     * coverage; the three-time form without coverage), but not after a trim that cut into a night with
     * an interior gap — the trimmed edge becomes a new recording edge and is filled. An edit must be
     * applied to the ring's own segments, never to a previous edit's output.
     */
    @Test
    fun reapplyingAnEditMatchesUpstream() {
        val bases = listOf(
            emptyList(),
            listOf(seg(0.0, 8.0, SleepStage.ASLEEP_CORE)),
            listOf(seg(0.0, 8.0, SleepStage.IN_BED), seg(0.0, 8.0, SleepStage.ASLEEP_CORE)),
            listOf(seg(1.0, 2.5, SleepStage.ASLEEP_CORE), seg(2.5, 4.0, SleepStage.ASLEEP_DEEP), seg(4.0, 6.5, SleepStage.ASLEEP_REM)),
        )
        val coverages = listOf(null, MeasuredCoverage(listOf(DateInterval(at(0.0), at(6.0)))), MeasuredCoverage(listOf(DateInterval(at(2.0), at(5.0)))))
        var checked = 0
        for ((bi, base) in bases.withIndex()) for (c in coverages) for (s in listOf(-1.0, 0.0, 0.5, 3.5)) for (e in listOf(4.5, 7.0, 9.0)) {
            val w = SleepEdit.Window(at(s), at(e))
            val once = SleepEdit.recompute(base, w, coverage = c)
            assertEquals(once, SleepEdit.recompute(once, w, coverage = c), "window $s-$e on base $bi")
            checked++
            if (c == null && bi <= 2) {
                for (o in listOf(s, s + 0.5, 1.0).filter { it >= s && it < e }) {
                    val t = SleepEdit.Times(at(s), at(o), at(e))
                    val o1 = SleepEdit.recompute(base, t)
                    assertEquals(o1, SleepEdit.recompute(o1, t), "times $s/$o/$e on base $bi")
                    checked++
                }
            }
        }
        assertTrue(checked > 150, "the grid ran")

        val gapped = listOf(seg(0.0, 3.0, SleepStage.ASLEEP_CORE), seg(5.0, 8.0, SleepStage.ASLEEP_DEEP))
        val w = SleepEdit.Window(at(-1.0), at(4.5))
        val once = SleepEdit.recompute(gapped, w)
        assertEquals("(-3600,0,asleepCore,measured) (0,10800,asleepCore,measured)", describe(once))
        assertEquals(
            "(-3600,0,asleepCore,measured) (0,10800,asleepCore,measured) (10800,16200,asleepCore,measured)",
            describe(SleepEdit.recompute(once, w)),
        )
        val t = SleepEdit.Times(at(-1.0), at(-1.0), at(4.5))
        val o1 = SleepEdit.recompute(gapped, t)
        assertEquals("(-3600,16200,inBed,measured) (-3600,0,asleepCore,measured) (0,10800,asleepCore,measured)", describe(o1))
        assertEquals(
            "(-3600,16200,inBed,measured) (-3600,0,asleepCore,measured) (0,10800,asleepCore,measured) (10800,16200,asleepCore,measured)",
            describe(SleepEdit.recompute(o1, t)),
        )
    }

    /**
     * The codec writes and reads ASCII digits whatever the default locale, and no edit reads the
     * machine's zone: every answer is the same under default locales that print Arabic-Indic, Persian
     * and Devanagari digits (and a dotless-i locale) with far-flung default zones.
     */
    @Test
    fun nothingReadsTheMachineLocaleOrTimeZone() {
        val zone = ZoneId.of("America/New_York")
        val segs = listOf(
            SleepSegment(at(0.0), at(1.0), SleepStage.ASLEEP_DEEP),
            SleepSegment(at(1.0), at(2.5), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED_OVER_MEASURED),
            SleepSegment(Instant.ofEpochSecond(-31_000_000_000L), Instant.ofEpochSecond(-30_999_999_850L), SleepStage.AWAKE),
        )
        val june15 = Instant.ofEpochSecond(1_781_496_000) // 2026-06-15 00:00 New York
        fun results(): List<Any?> {
            val bytes = SleepHypnogramCodec.encode(segs)
            return listOf(
                String(bytes, Charsets.UTF_8),
                SleepHypnogramCodec.decode(bytes),
                SleepHypnogramCodec.decode("[[1700000000,1700000150,3],[1.7e9,1.70000015E9,2,1]]".toByteArray()),
                SleepEdit.bounds(at(0.0), at(1.75), dataCoverage = DateInterval(at(-3.0), at(2.0))),
                SleepEdit.validate(SleepEdit.Times(at(-6.0), at(-5.75), at(14.0)), at(0.0), at(1.75)),
                SleepEdit.isSamePickerMinute(at(2.0), at(2.0).plusSeconds(59), zone),
                NapEdit.validate(NapEdit.Window(june15.plusSeconds(14 * 3600), june15.plusSeconds(15 * 3600)), zone),
                NapEdit.validate(NapEdit.Window(june15.plusSeconds(2 * 3600), june15.plusSeconds(3 * 3600)), zone),
                SleepEditLabels.accuracy(listOf(SleepEditLabel(at(0.0), recordedWake = at(9.0), trueWake = at(8.25)))),
            )
        }
        val savedLocale = Locale.getDefault()
        val savedZone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.ROOT)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val reference = results()
            assertEquals("[[1700000000,1700003600,3],[1700003600,1700009000,2,2],[-31000000000,-30999999850,1]]", reference[0])
            assertEquals(NapEdit.Invalid.NotDaytime, reference[7], "fixture sanity: 02:00-03:00 is judged in New York")
            for ((locale, tz) in listOf(
                Locale.forLanguageTag("ar-EG") to "Pacific/Kiritimati",
                Locale.forLanguageTag("fa-IR") to "Asia/Tehran",
                Locale.forLanguageTag("hi-IN-u-nu-deva") to "America/St_Johns",
                Locale.forLanguageTag("tr-TR") to "Asia/Kathmandu",
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
}
