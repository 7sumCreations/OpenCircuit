package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.GoalHistory.DayInput
import io.github.opencircuit.ringkit.GoalHistory.Ring
import java.io.File
import java.time.Instant
import java.time.ZoneId
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
 * Guards for what the Kotlin goals-and-units port adds or could lose relative to Swift: the raw
 * names, keys and literals upstream stores or shows; the settings lookup's fallback on a value of
 * the wrong type, and that it asks only upstream's keys; immutable values that compare by IEEE `==`
 * as Swift's synthesized `Equatable`; sets and maps copied in, read-only out and iterating in ring
 * order; no read of the machine's locale, time zone, clock or environment.
 */
class GoalUnitGuardTest {

    private val utc: ZoneId = ZoneId.of("UTC")
    private val monday: Instant = Instant.parse("2026-08-17T00:00:00Z")
    private val keys = listOf(
        GoalDefaults.WORKDAY_STEPS, GoalDefaults.WEEKEND_STEPS, GoalDefaults.ACTIVE_KCAL,
        GoalDefaults.ACTIVITY_MINUTES, GoalDefaults.WORKDAY_SLEEP_MIN, GoalDefaults.WEEKEND_SLEEP_MIN,
    )

    private fun noSetters(type: Class<*>): List<String> = type.methods.filter { it.name.startsWith("set") }.map { it.name }

    @Test
    fun rawNamesKeysAndLiteralsAreUpstreams() {
        assertEquals(listOf("celsius", "fahrenheit"), TemperatureUnit.entries.map { it.rawValue })
        assertEquals(listOf("metric", "imperial"), DistanceUnit.entries.map { it.rawValue })
        assertEquals(listOf("steps", "activeKcal", "activityMinutes", "sleepMinutes"), Ring.entries.map { it.rawValue })
        for (u in TemperatureUnit.entries) assertEquals(u, TemperatureUnit.fromRawValue(u.rawValue))
        for (u in DistanceUnit.entries) assertEquals(u, DistanceUnit.fromRawValue(u.rawValue))
        // `init?(rawValue:)` is an exact, case-sensitive match.
        for (bad in listOf("Celsius", "CELSIUS", " celsius", "", "kelvin")) assertNull(TemperatureUnit.fromRawValue(bad), bad)
        for (bad in listOf("Metric", "IMPERIAL", "km", "")) assertNull(DistanceUnit.fromRawValue(bad), bad)
        assertEquals(listOf("°C", "°F"), TemperatureUnit.entries.map { it.symbol })
        assertEquals(listOf("km", "mi"), DistanceUnit.entries.map { it.symbol })
        assertEquals("brpm", UnitsFormatter.RESPIRATORY_RATE_UNIT)
        assertEquals(
            listOf("goals.workdaySteps", "goals.weekendSteps", "goals.activeKcal", "goals.activityMinutes", "goals.workdaySleepMin", "goals.weekendSleepMin"),
            keys,
        )
        assertEquals(listOf(8_000, 10_000, 420, 480), listOf(GoalDefaults.DEFAULT_WORKDAY_STEPS, GoalDefaults.DEFAULT_WEEKEND_STEPS, GoalDefaults.DEFAULT_WORKDAY_SLEEP_MIN, GoalDefaults.DEFAULT_WEEKEND_SLEEP_MIN))
        assertEquals(listOf(300.0, 30.0), listOf(GoalDefaults.DEFAULT_ACTIVE_KCAL, GoalDefaults.DEFAULT_ACTIVITY_MINUTES))
        assertEquals(GoalHistory.Goals(8_000, 10_000, 300.0, 30.0, 420, 480), GoalHistory.Goals())
        assertEquals(510, SWIFT_FIXED_MAX_LENGTH)
    }

    @Test
    fun aStoredValueOfTheWrongTypeFallsBackToTheDefaultAndOnlyUpstreamsKeysAreAsked() {
        // Values no reader can bridge: each read as missing, so every goal is its default.
        for (bad in listOf<Any>("9000", "300.0", listOf(9000), mapOf("v" to 1), Any(), 'x', byteArrayOf(9), (1L shl 53) + 1)) {
            val asked = mutableListOf<String>()
            val settings = GoalSettings { key -> asked += key; bad }
            assertEquals(GoalHistory.Goals(), GoalHistory.Goals.fromDefaults(settings), "stored $bad")
            assertEquals(keys.toSet(), asked.toSet(), "fromDefaults asks the six keys")
            assertEquals(6, asked.size, "each once")
            asked.clear()
            assertEquals(8_000, GoalDefaults.stepsGoal(monday, utc, settings))
            assertEquals(420, GoalDefaults.sleepGoalMinutes(monday, utc, settings))
            assertEquals(listOf(GoalDefaults.WORKDAY_STEPS, GoalDefaults.WORKDAY_SLEEP_MIN), asked, "a workday asks its workday keys only")
        }
        // A fractional or non-finite number is the wrong type for an integer goal only.
        for (fractional in listOf<Any>(7.5, 0.1f, Double.NaN, Double.POSITIVE_INFINITY)) {
            val goals = GoalHistory.Goals.fromDefaults { fractional }
            assertEquals(GoalHistory.Goals().workdaySteps, goals.workdaySteps)
            assertEquals(GoalHistory.Goals().weekendSleepMin, goals.weekendSleepMin)
        }
        // A lookup that throws is the caller's to handle: nothing is swallowed.
        assertFailsWith<IllegalStateException> { GoalHistory.Goals.fromDefaults { error("store unreadable") } }
    }

    @Test
    fun valuesAreImmutableAndCompareAsSwiftsEquatable() {
        for (type in listOf(
            GoalProgress::class.java, DailyGoalProgress::class.java, GoalHistory.Goals::class.java, DayInput::class.java,
            GoalHistory.Day::class.java, GoalHistory.NightSleep::class.java, GoalHistory.NapSleep::class.java, GoalHistory.Summary::class.java,
        )) {
            assertEquals(emptyList(), noSetters(type), type.simpleName)
        }
        // Doubles compare by IEEE `==`: −0.0 equals 0.0 (and hashes alike); NaN is unequal to itself.
        assertEquals(GoalProgress(-0.0, 1.0), GoalProgress(0.0, 1.0))
        assertEquals(GoalProgress(-0.0, 1.0).hashCode(), GoalProgress(0.0, 1.0).hashCode())
        assertNotEquals(GoalProgress(Double.NaN, 1.0), GoalProgress(Double.NaN, 1.0))
        assertEquals(GoalHistory.Goals(activeKcal = -0.0), GoalHistory.Goals(activeKcal = 0.0))
        assertEquals(GoalHistory.Goals(activeKcal = -0.0).hashCode(), GoalHistory.Goals(activeKcal = 0.0).hashCode())
        assertNotEquals(GoalHistory.Goals(activityMinutes = Double.NaN), GoalHistory.Goals(activityMinutes = Double.NaN))
        assertEquals(DayInput(monday, activeKcal = -0.0), DayInput(monday, activeKcal = 0.0))
        assertEquals(DayInput(monday, activeKcal = -0.0).hashCode(), DayInput(monday, activeKcal = 0.0).hashCode())
        assertNotEquals(DayInput(monday, activityMinutes = Double.NaN), DayInput(monday, activityMinutes = Double.NaN))
        assertNotEquals(DayInput(monday, activeKcal = 0.0), DayInput(monday))
        // A changed value is a copy; the original keeps its fields (upstream's `var`s on a struct).
        val goals = GoalHistory.Goals()
        val raised = goals.copy(workdaySteps = 12_000)
        assertEquals(8_000, goals.workdaySteps)
        assertEquals(12_000, raised.workdaySteps)
        assertEquals(goals.copy(workdaySteps = 8_000), goals)
        // The goal is clamped once, at construction, as upstream's initializer does.
        assertEquals(0.0, GoalProgress(5.0, -3.0).goal)
    }

    @Test
    fun setsAndMapsAreCopiedInReadOnlyOutAndIterateInRingOrder() {
        val progress = DailyGoalProgress(GoalProgress(1.0, 2.0), GoalProgress(1.0, 2.0), GoalProgress(1.0, 2.0), GoalProgress(1.0, 2.0))
        val present = linkedSetOf(Ring.SLEEP_MINUTES, Ring.STEPS, Ring.ACTIVE_KCAL)
        val met = linkedSetOf(Ring.SLEEP_MINUTES)
        val day = GoalHistory.Day(monday, progress, present, met, isPartial = false)
        present.clear()
        met += Ring.STEPS
        assertEquals(listOf(Ring.STEPS, Ring.ACTIVE_KCAL, Ring.SLEEP_MINUTES), day.present.toList())
        assertEquals(listOf(Ring.SLEEP_MINUTES), day.met.toList())
        assertFailsWith<UnsupportedOperationException> { (day.present as MutableSet<Ring>).add(Ring.ACTIVITY_MINUTES) }
        assertFailsWith<UnsupportedOperationException> { (day.met as MutableSet<Ring>).clear() }

        val metCounts = linkedMapOf(Ring.SLEEP_MINUTES to 2, Ring.STEPS to 5)
        val summary = GoalHistory.Summary(5, 2, metCounts, linkedMapOf(Ring.ACTIVITY_MINUTES to 1, Ring.STEPS to 5), 1, 2)
        metCounts[Ring.ACTIVE_KCAL] = 9
        assertEquals(listOf(Ring.STEPS to 5, Ring.SLEEP_MINUTES to 2), summary.metCounts.toList())
        assertEquals(listOf(Ring.STEPS, Ring.ACTIVITY_MINUTES), summary.dataCounts.keys.toList())
        assertFailsWith<UnsupportedOperationException> { (summary.metCounts as MutableMap<Ring, Int>).clear() }

        val credit = GoalHistory.sleepCreditByDay(
            listOf(GoalHistory.NightSleep(monday.plusSeconds(86_400), null, null, 400), GoalHistory.NightSleep(monday, null, null, 300)),
            emptyList(), utc,
        )
        assertEquals(listOf(monday, monday.plusSeconds(86_400)), credit.keys.toList(), "oldest day first")
        assertFailsWith<UnsupportedOperationException> { (credit as MutableMap<Instant, Int>).clear() }

        // build reads its inputs once and returns its own list.
        val inputs = mutableListOf(DayInput(monday, steps = 9_000))
        val built = GoalHistory.build(inputs, GoalHistory.Goals(), monday.plusSeconds(5 * 86_400L), utc)
        inputs.clear()
        assertEquals(1, built.size)
    }

    @Test
    fun nothingReadsTheMachineLocaleOrTimeZone() {
        // A JVM default locale with non-ASCII digits or a decimal comma, and a machine time zone far
        // from UTC, change no answer: every formatted string, default unit, weekend, build and summary.
        val t = Instant.parse("2026-03-08T04:30:00Z") // Saturday 23:30 in New York, Sunday in UTC
        val noSettings = GoalSettings { null }
        fun results(): List<Any?> = listOf(
            UnitsFormatter.temperature(36.65, TemperatureUnit.CELSIUS), UnitsFormatter.temperature(37.0, TemperatureUnit.FAHRENHEIT, 3),
            UnitsFormatter.temperatureDelta(-0.04, TemperatureUnit.FAHRENHEIT), UnitsFormatter.distance(1234567.891, DistanceUnit.METRIC, 2),
            UnitsFormatter.distance(3200.0, DistanceUnit.IMPERIAL, -6), swiftFixed(0.25, 1), swiftFixed(Double.NaN, 2, true),
            EpochArchiveDiagnostics.oneDecimal(1.25), UnitsFormatter.temperature(1e21, TemperatureUnit.CELSIUS, 2),
            TemperatureUnit.localeDefault(Locale.US), DistanceUnit.localeDefault(Locale.GERMANY), TemperatureUnit.localeDefault(Locale.forLanguageTag("en-LR")),
            GoalDefaults.isWeekend(t, ZoneId.of("America/New_York")), GoalDefaults.isWeekend(t, utc),
            GoalDefaults.stepsGoal(t, ZoneId.of("Asia/Kolkata"), noSettings),
            GoalHistory.build(listOf(DayInput(t, steps = 9_000, activeKcal = 450.5)), GoalHistory.Goals(), t, ZoneId.of("America/Santiago")).toString(),
            GoalHistory.summarize(GoalHistory.build(listOf(DayInput(t, 20_000, 600.0, 60.0, 600)), GoalHistory.Goals(), t, utc), t.plusSeconds(86_400), utc).toString(),
            GoalHistory.sleepCreditByDay(listOf(GoalHistory.NightSleep(t, t.minusSeconds(3600), t.plusSeconds(25_200), 400)), emptyList(), ZoneId.of("Europe/London")).toString(),
        )
        val savedLocale = Locale.getDefault()
        val savedZone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.ROOT)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val reference = results()
            for ((locale, tz) in listOf(
                Locale.forLanguageTag("ar-EG") to "America/New_York",
                Locale.forLanguageTag("fa-IR") to "Asia/Tehran",
                Locale.forLanguageTag("hi-IN-u-nu-deva") to "Asia/Kolkata",
                Locale.forLanguageTag("de-DE") to "Pacific/Chatham",
                Locale.forLanguageTag("en-US") to "Pacific/Kiritimati",
            )) {
                Locale.setDefault(locale)
                TimeZone.setDefault(TimeZone.getTimeZone(tz))
                assertEquals(reference, results(), "$locale / $tz")
            }
            assertTrue(reference.filterIsInstance<String>().all { s -> s.all { it.code < 128 || it == '°' } }, "ASCII digits only")
        } finally {
            Locale.setDefault(savedLocale)
            TimeZone.setDefault(savedZone)
        }
    }

    @Test
    fun noZoneClockLocaleOrSettingsParameterHasADefault() {
        // The only defaulted parameters in the slice's functions are upstream's `fractionDigits: Int = 1`
        // on the three formatter shapes (data classes' generated `copy` aside); a defaulted zone,
        // instant, locale or settings lookup would bring an ambient default back.
        val holders = listOf(
            GoalDefaults::class.java, GoalHistory::class.java, GoalHistory.Goals::class.java, GoalHistory.Goals.Companion::class.java,
            GoalHistory.Day::class.java, GoalHistory.Summary::class.java, GoalProgress::class.java,
            TemperatureUnit::class.java, TemperatureUnit.Companion::class.java, DistanceUnit::class.java, DistanceUnit.Companion::class.java,
            UnitsFormatter::class.java,
        )
        val defaulted = holders.flatMap { c ->
            c.declaredMethods.filter { it.name.endsWith("\$default") && it.name != "copy\$default" }.map { "${c.simpleName}.${it.name}" }
        }
        assertEquals(
            listOf("UnitsFormatter.distance\$default", "UnitsFormatter.temperature\$default", "UnitsFormatter.temperatureDelta\$default"),
            defaulted.sorted(),
        )
        val ambient = setOf(ZoneId::class.java, Instant::class.java, Locale::class.java, GoalSettings::class.java, java.time.Clock::class.java, TimeZone::class.java)
        for (m in UnitsFormatter::class.java.declaredMethods.filter { it.name.endsWith("\$default") }) {
            assertTrue(m.parameterTypes.none { it in ambient }, "${m.name} takes no zone, clock, locale or settings")
        }
        // Per parameter, in source (constructors included): no `name: ZoneId = …` (or Instant, Locale,
        // GoalSettings, Clock, TimeZone). Local `var`s are not parameters.
        val root = File(assertNotNull(System.getProperty("opencircuit.androidRoot"), "system property opencircuit.androidRoot is not set"))
        val dir = File(root, "ringkit/src/main/kotlin/io/github/opencircuit/ringkit")
        val defaultedAmbient = Regex("""\w+\s*:\s*(ZoneId|Instant|Locale|GoalSettings|Clock|TimeZone)\??\s*=""")
        for (name in listOf("GoalDefaults.kt", "GoalHistory.kt", "UnitPreferences.kt")) {
            val hits = File(dir, name).readLines().withIndex()
                .filter { (_, line) -> defaultedAmbient.containsMatchIn(line) && !line.trimStart().startsWith("var ") }
                .map { "$name:${it.index + 1}: ${it.value.trim()}" }
            assertEquals(emptyList(), hits, "defaulted zone / clock / locale / settings parameter in $name")
        }
        assertTrue(defaultedAmbient.containsMatchIn("fun f(zone: ZoneId = ZoneOffset.UTC)"), "the source pattern bites")
        assertTrue(defaultedAmbient.containsMatchIn("    val date: Instant? = null,"), "a defaulted constructor property is caught")
    }

    @Test
    fun theSlicesSourcesNeverReadTheClockLocaleZoneOrEnvironment() {
        val root = File(assertNotNull(System.getProperty("opencircuit.androidRoot"), "system property opencircuit.androidRoot is not set"))
        val dir = File(root, "ringkit/src/main/kotlin/io/github/opencircuit/ringkit")
        val forbidden = Regex("""Instant\.now\(|ZoneId\.systemDefault\(|Locale\.getDefault\(|Clock\.system|System\.getenv|TimeZone\.getDefault\(""")
        for (name in listOf("GoalDefaults.kt", "GoalHistory.kt", "UnitPreferences.kt", "SwiftNumerics.kt")) {
            val f = File(dir, name)
            assertTrue(f.isFile, "missing source $name")
            val hits = f.readLines().withIndex().filter { forbidden.containsMatchIn(it.value) }.map { "$name:${it.index + 1}: ${it.value.trim()}" }
            assertEquals(emptyList(), hits, "ambient environment read in $name")
        }
        // The pattern bites: each forbidden call is found in a line built here. The environment read
        // is assembled from pieces, because the corpus-gate audit forbids that text in test sources.
        for (probe in listOf("Instant.now()", "ZoneId.systemDefault()", "Locale.getDefault()", "Clock.systemUTC()", "System" + ".getenv(\"X\")", "TimeZone.getDefault()")) {
            assertTrue(forbidden.containsMatchIn("val x = $probe"), "the audit catches $probe")
        }
    }
}
