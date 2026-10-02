package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.TrendsEngine.DailyPoint
import io.github.opencircuit.ringkit.TrendsEngine.RollingAverages
import io.github.opencircuit.ringkit.TrendsEngine.Trend
import io.github.opencircuit.ringkit.TrendsRefreshPolicy.Reason
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards for what the Kotlin trends port adds or could lose relative to Swift: `DailyPoint` and
 * `RollingAverages` are immutable values that compare their doubles by IEEE `==` as Swift's
 * synthesized `Equatable` does; `Trend`'s raw names and `Reason`'s cases are upstream's, in order;
 * every constant and default equals upstream's literal; no clock or zone has a default and neither
 * source reads one; and nothing reads the machine's time zone or locale. The 64-bit sums and the
 * negative-window bound are pinned in `TrendsHazardTest`.
 */
class TrendsGuardTest {

    private val t0: Instant = Instant.parse("2026-03-01T05:00:00Z")

    @Test
    fun dailyPointAndRollingAveragesAreImmutableValuesComparedAsSwiftDoes() {
        for (type in listOf(DailyPoint::class.java, RollingAverages::class.java)) {
            assertEquals(emptyList(), type.methods.filter { it.name.startsWith("set") }.map { it.name }, "no setters on ${type.simpleName}")
        }
        val p = DailyPoint(date = t0, steps = 5_000, sleepHRAvg = 58.5, distanceM = 1_240.0)
        assertEquals(p, DailyPoint(date = t0, steps = 5_000, sleepHRAvg = 58.5, distanceM = 1_240.0))
        assertEquals(p.hashCode(), DailyPoint(date = t0, steps = 5_000, sleepHRAvg = 58.5, distanceM = 1_240.0).hashCode())
        assertNotEquals(p, p.copy(date = t0.plusNanos(1)), "dates compare to the nanosecond")
        assertNotEquals(p, p.copy(steps = 5_001))
        assertNotEquals(p, p.copy(sleepHRAvg = null))
        val q = p.copy(steps = 6_000)
        assertEquals(5_000, p.steps, "a copy never changes the original")
        assertEquals(6_000, q.steps)
        // Swift's `==` on doubles: −0.0 equals 0.0 (and hashes alike), NaN is unequal even to itself.
        assertEquals(p.copy(skinTempC = 0.0), p.copy(skinTempC = -0.0))
        assertEquals(p.copy(skinTempC = 0.0).hashCode(), p.copy(skinTempC = -0.0).hashCode())
        val nan = p.copy(dayRRAvg = Double.NaN)
        assertNotEquals(nan, nan.copy())
        @Suppress("ReplaceCallWithBinaryOperator")
        assertTrue(!nan.equals(nan), "a NaN point is unequal to itself, as Swift's")

        val a = TrendsEngine.rollingAverages(listOf(p))
        assertEquals(a, TrendsEngine.rollingAverages(listOf(p.copy())))
        val zeros = TrendsEngine.rollingAverages(listOf(DailyPoint(date = t0, steps = 0)))
        assertEquals(0.0, zeros.steps)
        assertEquals(zeros, zeros.copy(steps = -0.0))
        assertEquals(zeros.hashCode(), zeros.copy(steps = -0.0).hashCode())
        val nanAverages = zeros.copy(sleepHRAvg = Double.NaN)
        assertNotEquals(nanAverages, nanAverages.copy())
    }

    @Test
    fun rawNamesConstantsAndDefaultsAreUpstreams() {
        assertEquals(listOf("up", "down", "flat"), Trend.entries.map { it.rawValue })
        assertEquals(listOf("APPEARED", "FOREGROUNDED", "SYNC_FINISHED"), Reason.entries.map { it.name })
        assertEquals(29.0, TrendsEngine.MIN_VALID_HR)
        assertEquals(Duration.ofSeconds(60), TrendsRefreshPolicy.MIN_INTERVAL)

        // Default window 7: of ten days with steps 1…10, the last seven (4…10) average 7.
        val ten = (1..10).map { DailyPoint(date = t0.plusSeconds(86_400L * it), steps = it) }
        assertEquals(7.0, TrendsEngine.rollingAverages(ten).steps)
        // Default window 7 and minimum change 3 %: a week at 100 then a week at 103 is exactly 3 % —
        // flat (the change must exceed it); at 104 it is up, at 96 down.
        fun weeks(recent: Int) = List(14) { i -> DailyPoint(date = t0.plusSeconds(86_400L * i), steps = if (i < 7) 100 else recent) }
        assertEquals(Trend.FLAT, TrendsEngine.trend(weeks(103)) { it.steps?.toDouble() })
        assertEquals(Trend.UP, TrendsEngine.trend(weeks(104)) { it.steps?.toDouble() })
        assertEquals(Trend.DOWN, TrendsEngine.trend(weeks(96)) { it.steps?.toDouble() })
        // Default window 7 for regularity: an opposite bedtime eight nights back is outside it.
        val nights = listOf(600) + List(7) { 1_320 }
        assertEquals(100, TrendsEngine.sleepRegularity(nights))
        assertTrue(assertNotNull(TrendsEngine.sleepRegularity(nights, window = 8)) < 100)
    }

    @Test
    fun inputsAreNeverChanged() {
        val points = mutableListOf(DailyPoint(date = t0, steps = 1), DailyPoint(date = t0.plusSeconds(86_400), steps = 3))
        val snapshot = points.toList()
        val minutes = mutableListOf(1_300, 1_320, 1_340)
        val minutesSnapshot = minutes.toList()
        val avg = TrendsEngine.rollingAverages(points)
        TrendsEngine.trend(points) { it.steps?.toDouble() }
        TrendsEngine.sleepRegularity(minutes)
        assertEquals(snapshot, points)
        assertEquals(minutesSnapshot, minutes)
        // The averages are values: clearing the caller's list afterwards changes nothing they hold.
        points.clear()
        assertEquals(2.0, avg.steps)
    }

    @Test
    fun noClockParameterHasADefaultAndNoSourceLineReadsOne() {
        // The only defaulted parameters are upstream's windows and minimum change; `now` is required.
        val engineDefaults = TrendsEngine::class.java.declaredMethods.filter { it.name.endsWith("\$default") }.map { it.name }.sorted()
        assertEquals(listOf("rollingAverages\$default", "sleepRegularity\$default", "trend\$default"), engineDefaults)
        assertEquals(emptyList(), TrendsRefreshPolicy::class.java.declaredMethods.filter { it.name.endsWith("\$default") }.map { it.name })
        val reload = TrendsRefreshPolicy::class.java.declaredMethods.single { it.name == "shouldReload" }
        assertEquals(listOf(Reason::class.java, Instant::class.java, Instant::class.java), reload.parameterTypes.toList())

        val root = File(assertNotNull(System.getProperty("opencircuit.androidRoot"), "system property opencircuit.androidRoot is not set"))
        val defaultedClock = Regex("""\w+\s*:\s*(Instant|ZoneId|Locale|Clock|TimeZone)\??\s*=""")
        val ambient = Regex("""\b[A-Z]\w*\.now\(|\bClock\.system|\bsystemDefault\(|\bTimeZone\.getDefault\(|\bLocale\.getDefault\(|currentTimeMillis\(|nanoTime\(|getenv\b""")
        val hits = listOf("TrendsEngine.kt", "TrendsRefreshPolicy.kt").flatMap { name ->
            val source = File(root, "ringkit/src/main/kotlin/io/github/opencircuit/ringkit/$name")
            assertTrue(source.isFile, "missing source $name")
            StrippedSource(name, source.readText()).lines.withIndex()
                .filter { (_, line) -> defaultedClock.containsMatchIn(line) || ambient.containsMatchIn(line) }
                .map { "$name:${it.index + 1}: ${it.value.trim()}" }
        }
        assertEquals(emptyList(), hits, "a defaulted or ambient instant, zone, locale or clock")
        assertTrue(defaultedClock.containsMatchIn("fun f(now: Instant = Instant.EPOCH)"), "the default pattern bites")
        assertTrue(ambient.containsMatchIn("val t = Instant.now()") && ambient.containsMatchIn("ZoneId.systemDefault()"), "the ambient pattern bites")
    }

    @Test
    fun nothingReadsTheMachineTimeZoneOrLocale() {
        val points = (0 until 14).map { i -> DailyPoint(date = t0.plusSeconds(86_400L * i), steps = 4_000 + 300 * i, sleepHRAvg = 55.0 + i, skinTempC = 34.0 + i / 10.0) }
        fun results(): List<Any?> = listOf(
            TrendsEngine.rollingAverages(points),
            TrendsEngine.trend(points) { it.steps?.toDouble() },
            TrendsEngine.trend(points, window = 3) { it.skinTempC },
            TrendsEngine.sleepRegularity(listOf(1_380, 1_400, 1_430, 5, 20, 1_410, 1_395)),
            TrendsRefreshPolicy.shouldReload(Reason.APPEARED, lastLoadedAt = t0, now = t0.plusSeconds(59)),
            Trend.entries.map { it.rawValue },
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
}
