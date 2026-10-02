package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.BatteryTTE.Sample
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
 * Guards for what the Kotlin battery port adds or could lose relative to Swift: `Sample`'s value
 * semantics (upstream's struct); histories copied in and handed back read-only, never sharing the
 * caller's list; every constant and default equal to upstream's literal; no default clock — every
 * `now` and `at` is required, and nothing reads the machine's time zone or locale.
 */
class BatteryGuardTest {

    private val t0: Instant = Instant.parse("2026-08-17T00:00:00Z")
    private fun s(pct: Int, seconds: Long): Sample = Sample(pct, t0.plusSeconds(seconds))

    @Test
    fun aSampleIsAnImmutableValue() {
        assertEquals(emptyList(), Sample::class.java.methods.filter { it.name.startsWith("set") }.map { it.name }, "no setters")
        assertEquals(s(80, 60), s(80, 60))
        assertEquals(s(80, 60).hashCode(), s(80, 60).hashCode())
        assertNotEquals(s(80, 60), s(79, 60))
        assertNotEquals(s(80, 60), s(80, 61))
        assertNotEquals(Sample(80, t0), Sample(80, t0.plusNanos(1)), "times compare to the nanosecond")
        val a = s(80, 0)
        val b = a.copy(percent = 79)
        assertEquals(80, a.percent)
        assertEquals(79, b.percent)
        assertEquals(a.at, b.at)
    }

    @Test
    fun historiesAreCopiedInAndHandedBackReadOnly() {
        // Swift arrays copy on assignment; a Kotlin list is a shared reference. Every path of both
        // folds — append, reset, jitter, an unreadable reading, pruning, unplugging — must hand back
        // its own read-only list and leave the caller's untouched.
        val input = mutableListOf(s(80, 0), s(79, 3_600))
        val snapshot = input.toList()
        val outs = listOf(
            BatteryTTE.record(input, percent = 78, at = t0.plusSeconds(7_200), charging = false), // append
            BatteryTTE.record(input, percent = 79, at = t0.plusSeconds(7_200), charging = false), // jitter
            BatteryTTE.record(input, percent = 90, at = t0.plusSeconds(7_200), charging = false), // missed charge
            BatteryTTE.record(input, percent = 255, at = t0.plusSeconds(7_200), charging = false), // not a reading
            BatteryTTE.record(input, percent = 78, at = t0.plusSeconds(7_200), charging = false, cap = 1), // pruned
            BatteryTTE.recordCharge(input, percent = 81, at = t0.plusSeconds(7_200), charging = true), // append
            BatteryTTE.recordCharge(input, percent = 79, at = t0.plusSeconds(7_200), charging = true), // ignored
            BatteryTTE.recordCharge(input, percent = 255, at = t0.plusSeconds(7_200), charging = true), // not a reading
            BatteryTTE.recordCharge(input, percent = 81, at = t0.plusSeconds(7_200), charging = false), // unplugged
        )
        assertEquals(snapshot, input, "the caller's list is never changed")
        input.clear()
        for (out in outs) {
            // A read-only list refuses the add; Kotlin's shared empty list refuses the cast itself.
            val refused = runCatching { (out as MutableList<Sample>).add(s(1, 1)) }.exceptionOrNull()
            assertTrue(refused is UnsupportedOperationException || refused is ClassCastException, "a handed-back history accepted an add: $out")
        }
        // Clearing the caller's list after the call changed nothing it was given back.
        assertEquals(listOf(80, 79, 78), outs[0].map { it.percent })
        assertEquals(listOf(80, 79), outs[1].map { it.percent })
        assertEquals(listOf(80, 79), outs[3].map { it.percent })
        assertEquals(listOf(80, 79), outs[7].map { it.percent })
        // The estimates read their input once and never change it.
        val samples = mutableListOf(s(90, 3_600), s(100, 0))
        BatteryTTE.timeToEmpty(samples, now = t0)
        BatteryTTE.timeToFull(samples, now = t0)
        assertEquals(listOf(90, 100), samples.map { it.percent }, "sorting works on a copy")
    }

    @Test
    fun constantsAndDefaultsAreUpstreams() {
        assertEquals(0..100, BatteryTTE.READABLE_PERCENT)
        assertEquals(60, BatteryTTE.DEFAULT_CAP)
        assertEquals(14.0 * 86_400, BatteryTTE.DISCHARGE_MAX_AGE_SECONDS)
        assertEquals(6.0 * 3_600, BatteryTTE.CHARGE_MAX_AGE_SECONDS)
        assertEquals(3L, BatteryTTE.RESET_STEP)
        assertEquals(2.0, BatteryTTE.MIN_CHANGE)
        assertEquals(50.0, BatteryTTE.MAX_DISCHARGE_PER_HOUR)
        assertEquals(300.0, BatteryTTE.MAX_CHARGE_PER_HOUR)
        // The defaults are those values: a 14-day-old discharge sample is kept, one a second older is
        // pruned; likewise 6 hours for the charge history; the 61st most recent sample goes.
        val now = t0.plusSeconds(14 * 86_400)
        assertEquals(listOf(90, 89), BatteryTTE.record(listOf(s(90, 0)), percent = 89, at = now, charging = false).map { it.percent })
        assertEquals(listOf(89), BatteryTTE.record(listOf(s(90, -1)), percent = 89, at = now, charging = false).map { it.percent })
        val chargeNow = t0.plusSeconds(6 * 3_600)
        assertEquals(listOf(60, 61), BatteryTTE.recordCharge(listOf(s(60, 0)), percent = 61, at = chargeNow, charging = true).map { it.percent })
        assertEquals(listOf(61), BatteryTTE.recordCharge(listOf(s(60, -1)), percent = 61, at = chargeNow, charging = true).map { it.percent })
        val sixty = (0 until 60).map { s(100 - it, it * 60L) }
        assertEquals(60, BatteryTTE.record(sixty, percent = 1, at = t0.plusSeconds(3_600), charging = false).size)
        assertEquals(99, BatteryTTE.record(sixty, percent = 1, at = t0.plusSeconds(3_600), charging = false).first().percent)
        // The default target is 100.
        val charging = listOf(s(60, 0), s(70, 1_800))
        assertEquals(BatteryTTE.timeToFull(charging, now = t0, target = 100), BatteryTTE.timeToFull(charging, now = t0))
    }

    @Test
    fun noClockParameterHasADefaultAndNoSourceLineReadsOne() {
        // The only defaulted parameters are upstream's: `cap` and `maxAge` on the two folds and `target`
        // on the time to full. A defaulted `now` or `at` would bring the device clock back.
        val defaulted = BatteryTTE::class.java.declaredMethods.filter { it.name.endsWith("\$default") }.map { it.name }.sorted()
        assertEquals(listOf("record\$default", "recordCharge\$default", "timeToFull\$default"), defaulted)
        val root = File(assertNotNull(System.getProperty("opencircuit.androidRoot"), "system property opencircuit.androidRoot is not set"))
        val source = File(root, "ringkit/src/main/kotlin/io/github/opencircuit/ringkit/BatteryTTE.kt")
        val defaultedClock = Regex("""\w+\s*:\s*(Instant|ZoneId|Locale|Clock|TimeZone)\??\s*=""")
        val hits = StrippedSource(source.name, source.readText()).lines.withIndex()
            .filter { (_, line) -> defaultedClock.containsMatchIn(line) }.map { "BatteryTTE.kt:${it.index + 1}: ${it.value.trim()}" }
        assertEquals(emptyList(), hits, "a defaulted instant, zone, locale or clock")
        assertTrue(defaultedClock.containsMatchIn("fun f(now: Instant = Instant.EPOCH)"), "the source pattern bites")
        // Every public function that takes an instant (upstream's `now` and `at`) is one of these five.
        val takesInstant = BatteryTTE::class.java.declaredMethods
            .filter { m -> !m.name.endsWith("\$default") && m.parameterTypes.contains(Instant::class.java) && java.lang.reflect.Modifier.isPublic(m.modifiers) }
            .map { it.name }.sorted()
        assertEquals(listOf("estimatedDepletionDate", "record", "recordCharge", "timeToEmpty", "timeToFull"), takesInstant)
    }

    @Test
    fun nothingReadsTheMachineTimeZoneOrLocale() {
        val history = listOf(s(90, 0), s(88, 7_200), s(85, 14_400))
        val charge = listOf(s(40, 0), s(45, 600))
        fun results(): List<Any?> = listOf(
            BatteryTTE.timeToEmpty(history, now = t0), BatteryTTE.estimatedDepletionDate(history, now = t0),
            BatteryTTE.timeToFull(charge, now = t0), BatteryTTE.record(history, percent = 84, at = t0.plusSeconds(20_000), charging = false),
            BatteryTTE.recordCharge(charge, percent = 47, at = t0.plusSeconds(900), charging = true),
        )
        val savedLocale = Locale.getDefault()
        val savedZone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.ROOT)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val reference = results()
            for ((locale, tz) in listOf(Locale.forLanguageTag("ar-EG") to "Pacific/Kiritimati", Locale.forLanguageTag("hi-IN-u-nu-deva") to "America/Santiago")) {
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
