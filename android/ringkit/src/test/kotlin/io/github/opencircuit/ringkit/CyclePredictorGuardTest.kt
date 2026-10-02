package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.CyclePredictor.CyclePrediction
import io.github.opencircuit.ringkit.CyclePredictor.CycleStats
import io.github.opencircuit.ringkit.CyclePredictor.PeriodEntry
import io.github.opencircuit.ringkit.CyclePredictor.SkinTempNight
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards for what the Kotlin cycle port adds or could lose relative to Swift: the constants typed from
 * upstream; every value immutable and comparing its doubles by IEEE `==`, as Swift's synthesized
 * `Equatable` does; a caller's list neither reordered nor kept; a day count in 64 bits (Swift's `Int`);
 * no zone or clock has a default, every function upstream defaulted to the current calendar takes one,
 * and the source reads none; nothing reads the machine's zone or locale. The hostile inputs are in
 * `CyclePredictorHazardTest`.
 */
class CyclePredictorGuardTest {

    private val t0: Instant = Instant.parse("2026-06-01T09:30:00Z")

    private fun at(days: Double): Instant = assertNotNull(addingSeconds(t0, days * 86_400))

    @Test
    fun constantsAreUpstreams() {
        // CyclePredictor.swift:24-67, typed from upstream.
        assertEquals(
            listOf<Any>(2, 14, 5, 21, 45, 0.2, 2, 8),
            listOf<Any>(
                CyclePredictor.MIN_PERIODS_FOR_PREDICTION, CyclePredictor.LUTEAL_PHASE_DAYS, CyclePredictor.FERTILE_WINDOW_DAYS_BEFORE_OVULATION,
                CyclePredictor.MIN_CYCLE_LENGTH_DAYS, CyclePredictor.MAX_CYCLE_LENGTH_DAYS, CyclePredictor.TEMP_RISE_CORROBORATION_C,
                CyclePredictor.TEMP_RISE_NIGHTS_REQUIRED, CyclePredictor.MAX_AUTO_EXTEND_PERIOD_DAYS,
            ),
        )
        // The roll-forward walks upstream's additions this far before jumping (2^20 cycles).
        assertEquals(1_048_576, CyclePredictor.MAX_ROLL_FORWARD_STEPS)
    }

    @Test
    fun valuesAreImmutableAndCompareTheirDoublesAsSwiftDoes() {
        for (type in listOf(PeriodEntry::class.java, SkinTempNight::class.java, CycleStats::class.java, CyclePrediction::class.java)) {
            assertEquals(emptyList(), type.methods.filter { it.name.startsWith("set") }.map { it.name }, "no setters on ${type.simpleName}")
        }
        // Swift's `==` on doubles: −0.0 equals 0.0 (and hashes alike), NaN is unequal even to itself.
        assertEquals(CycleStats(28.0, 1, 0.0), CycleStats(28.0, 1, -0.0))
        assertEquals(CycleStats(28.0, 1, 0.0).hashCode(), CycleStats(28.0, 1, -0.0).hashCode())
        assertNotEquals(CycleStats(Double.NaN, 1, null), CycleStats(Double.NaN, 1, null))
        assertNotEquals(CycleStats(28.0, 1, 5.0), CycleStats(28.0, 1, null))
        assertNotEquals(CycleStats(28.0, 1, null), CycleStats(28.0, 2, null))
        fun prediction(avg: Double, flag: Boolean = false) = CyclePrediction(at(28.0), at(33.0), at(9.0), at(14.0), at(14.0), avg, flag)
        assertEquals(prediction(0.0), prediction(-0.0))
        assertEquals(prediction(0.0).hashCode(), prediction(-0.0).hashCode())
        assertNotEquals(prediction(Double.NaN), prediction(Double.NaN))
        assertNotEquals(prediction(28.0), prediction(28.0, flag = true))
        assertEquals(SkinTempNight(t0, 0.0), SkinTempNight(t0, -0.0))
        assertEquals(SkinTempNight(t0, 0.0).hashCode(), SkinTempNight(t0, -0.0).hashCode())
        assertNotEquals(SkinTempNight(t0, Double.NaN), SkinTempNight(t0, Double.NaN))
        // A period entry is a value: equal by content, and a copy never changes the original.
        val e = PeriodEntry(t0, at(4.0))
        assertEquals(PeriodEntry(t0, at(4.0)), e)
        assertEquals(PeriodEntry(t0, null), e.copy(end = null))
        assertEquals(at(4.0), e.end)
    }

    @Test
    fun aCallersListIsNeitherReorderedNorKept() {
        // Upstream sorts a copy of its array argument; a Kotlin list is a shared reference.
        val periods = mutableListOf(PeriodEntry(at(56.0)), PeriodEntry(at(0.0), at(4.0)), PeriodEntry(at(28.0)))
        val nights = mutableListOf(SkinTempNight(at(70.0), 0.3), SkinTempNight(at(68.0), 0.4))
        val before = periods.toList()
        val stats = assertNotNull(CyclePredictor.cycleStats(periods))
        val p = assertNotNull(CyclePredictor.predict(periods, nights, now = at(56.0)))
        assertEquals(before, periods, "the caller's order is untouched")
        assertTrue(p.tempCorroborated)
        // Changing the lists afterwards changes neither answer already given.
        periods.clear()
        nights.clear()
        assertEquals(CycleStats(28.0, 2, 4.0), stats)
        assertTrue(p.tempCorroborated)
        assertEquals(at(84.0), p.nextPeriodStart)
    }

    @Test
    fun aDayCountIsTakenIn64Bits() {
        // Swift's `Int` is 64-bit. Between the first and last placeable years the span is about 7.3e11 days;
        // a 32-bit count would wrap.
        val utc = ZoneId.of("UTC")
        val first = Instant.parse("-999999998-01-01T00:00:00Z")
        val last = Instant.parse("+999999998-12-31T00:00:00Z")
        val count = CyclePredictor.periodMirrorDayCount(start = first, end = last, today = last, zone = utc)
        assertTrue(count > Int.MAX_VALUE.toLong(), "count $count")
        assertEquals(java.lang.Long.TYPE, CyclePredictor::class.java.methods.single { it.name == "periodMirrorDayCount" }.returnType)
    }

    @Test
    fun noZoneOrClockParameterHasADefaultAndNoSourceLineReadsOne() {
        val methods = CyclePredictor::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) && !it.name.endsWith("\$default") }
        // The defaulted parameters are upstream's: `skinTempDeviations` and `alreadyCoveredDays` (and the
        // entry's `end`). Each function upstream defaulted to `Calendar.current` (9) takes a required zone;
        // `predict` takes a required clock.
        assertEquals(
            listOf("openPeriodAutoExtendLastDay\$default", "periodMirrorDayCount\$default", "periodMirrorLastDay\$default", "predict\$default"),
            CyclePredictor::class.java.declaredMethods.filter { it.name.endsWith("\$default") }.map { it.name }.sorted(),
        )
        val zoned = methods.filter { m -> m.parameterTypes.contains(ZoneId::class.java) }.map { it.name }.toSet()
        assertEquals(
            setOf(
                "isLoggedPeriodDay", "isInPredictedPeriod", "isInFertileWindow", "isOvulationDay", "openPeriodAutoExtendLastDay",
                "periodMirrorLastDay", "periodMirrorDayCount", "periodMirrorIsUpToDate", "openPeriodHasReachedAutoExtendCap",
            ),
            zoned,
        )
        assertEquals(listOf(java.util.List::class.java, java.util.List::class.java, Instant::class.java), methods.single { it.name == "predict" }.parameterTypes.toList())

        val root = File(assertNotNull(System.getProperty("opencircuit.androidRoot"), "system property opencircuit.androidRoot is not set"))
        // A fixed literal instant (Foundation's reference date) is a constant, not a clock.
        val defaultedClock = Regex("""\w+\s*:\s*(Instant|ZoneId|Locale|Clock|TimeZone)\??\s*=(?!\s*(?:null\b|this\.|Instant\.ofEpochSecond\(\s*[\d_]+L?\s*\)))""")
        val ambient = Regex("""\b[A-Z]\w*\.now\(|\bClock\.system|\bsystemDefault\(|\bTimeZone\.getDefault\(|\bLocale\.getDefault\(|currentTimeMillis\(|nanoTime\(|getenv\b""")
        val name = "CyclePredictor.kt"
        val source = File(root, "ringkit/src/main/kotlin/io/github/opencircuit/ringkit/$name")
        assertTrue(source.isFile, "missing source $name")
        val hits = StrippedSource(name, source.readText()).lines.withIndex()
            .filter { (_, line) -> defaultedClock.containsMatchIn(line) || ambient.containsMatchIn(line) }
            .map { "$name:${it.index + 1}: ${it.value.trim()}" }
        assertEquals(emptyList(), hits, "a defaulted or ambient instant, zone, locale or clock")
        assertTrue(defaultedClock.containsMatchIn("    fun predict(periods: List<PeriodEntry>, now: Instant = Instant.EPOCH)"), "the default pattern bites")
        assertFalse(defaultedClock.containsMatchIn("    data class PeriodEntry(val start: Instant, val end: Instant? = null)"), "\"none\" is not a clock")
        assertFalse(defaultedClock.containsMatchIn("    private val REFERENCE_DATE: Instant = Instant.ofEpochSecond(978_307_200L)"), "a literal instant is not a clock")
        assertTrue(defaultedClock.containsMatchIn("    fun f(now: Instant = Instant.ofEpochSecond(seconds))"), "a computed instant still is")
    }

    @Test
    fun nothingReadsTheMachineTimeZoneOrLocale() {
        val zone = ZoneId.of("Asia/Kolkata")
        val periods = listOf(PeriodEntry(at(0.0), at(4.3)), PeriodEntry(at(27.6)), PeriodEntry(at(55.9), at(60.0)))
        fun results(): List<Any?> {
            val p = CyclePredictor.predict(periods, listOf(SkinTempNight(at(70.0), 0.3)), now = at(56.0))!!
            return (0 until 96).map { i ->
                val t = t0.plusSeconds(3_600L * 23 * i)
                listOf(
                    CyclePredictor.isLoggedPeriodDay(t, periods, zone), CyclePredictor.isInPredictedPeriod(t, p, zone),
                    CyclePredictor.isInFertileWindow(t, p, zone), CyclePredictor.isOvulationDay(t, p, zone),
                    CyclePredictor.periodMirrorDayCount(start = at(0.0), end = null, today = t, zone = zone),
                    CyclePredictor.openPeriodAutoExtendLastDay(start = at(0.0), today = t, zone = zone),
                    CyclePredictor.openPeriodHasReachedAutoExtendCap(start = at(0.0), today = t, zone = zone),
                )
            } + listOf(p.toString(), CyclePredictor.cycleStats(periods).toString(), SkinTempNight(t0, 0.25).toString())
        }
        val savedLocale = Locale.getDefault()
        val savedZone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.ROOT)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val reference = results()
            @Suppress("UNCHECKED_CAST")
            val rows = reference.take(96) as List<List<Any?>>
            assertTrue(rows.any { it[0] == true } && rows.any { it[1] == true } && rows.any { it[2] == true } && rows.any { it[3] == true }, "every classifier answers yes somewhere")
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
}
