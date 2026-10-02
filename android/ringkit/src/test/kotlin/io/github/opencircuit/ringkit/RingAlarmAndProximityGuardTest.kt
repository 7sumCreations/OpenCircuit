package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.RingProximity.Band
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards for what the Kotlin alarm and proximity port adds or could lose relative to Swift: the alarm's
 * block reasons keep upstream's raw names (matched exactly) and the proximity bands their order and
 * labels; the constants and defaults are typed from upstream; the alarm is an immutable value whose
 * weekday set is copied in and read-only, whose doubles compare by IEEE `==` as Swift's synthesized
 * `Equatable` does, and which changes only through `copy`; no zone or clock has a default and the three
 * sources read none; nothing reads the machine's zone or locale. The hostile inputs are in
 * `RingAlarmAndProximityHazardTest`.
 */
class RingAlarmAndProximityGuardTest {

    @Test
    fun rawNamesOrderLabelsConstantsAndDefaultsAreUpstreams() {
        // RingAlarm.swift:106-111, typed from upstream, in declaration order.
        assertEquals(listOf("ringOnCharger", "ringUnsupported", "linkNotReady", "ringBusy"), RingAlarmBlock.entries.map { it.rawValue })
        for (b in RingAlarmBlock.entries) assertEquals(b, RingAlarmBlock.fromRawValue(b.rawValue))
        // Swift's `init?(rawValue:)` is exact: re-cased, padded, misspelt, look-alike or fullwidth names are no case.
        for (bad in listOf("RingOnCharger", "ringoncharger", "ringBusy ", " linkNotReady", "ring_busy", "ringВusy", "ｒingBusy", "", "RING_BUSY")) {
            assertNull(RingAlarmBlock.fromRawValue(bad), "\"$bad\"")
        }
        // RingProximity.swift:25-41: five cases, weakest first, labels verbatim.
        assertEquals(listOf("SEARCHING", "FAR", "NEARBY", "CLOSE", "VERY_CLOSE"), Band.entries.map { it.name })
        assertEquals(listOf("Searching…", "Far", "Nearby", "Close", "Very close"), Band.entries.map { it.label })
        // Constants (RingAlarm.swift:122, :129; RingProximity.swift:19, :21) and the alarm's defaults (:60-67).
        assertEquals(listOf(900.0, 300.0, -59.0, 2.5), listOf(RingAlarmSchedule.DEFAULT_GRACE, RingAlarmSchedule.DEFAULT_WARM_UP, RingProximity.TX_POWER_AT_1M, RingProximity.PATH_LOSS_EXPONENT))
        val a = RingAlarm()
        assertEquals(
            listOf<Any>(false, 7, 0, emptySet<Int>(), VibrationPattern.NOTIFICATION, 3, 4.0, true),
            listOf(a.isEnabled, a.hour, a.minute, a.weekdays, a.pattern, a.burstCount, a.burstSpacing, a.backupNotification),
        )
    }

    @Test
    fun theWeekdaySetIsCopiedInAndReadOnly() {
        // A Swift `Set` is a value: the caller's later change never reaches the alarm, and the alarm's set
        // cannot be changed through it.
        val callers = mutableSetOf(2, 4)
        val a = RingAlarm(isEnabled = true, weekdays = callers)
        callers += 6
        callers -= 2
        assertEquals(setOf(2, 4), a.weekdays)
        assertTrue(a.repeats(2))
        assertFalse(a.repeats(6))
        @Suppress("UNCHECKED_CAST")
        val asMutable = a.weekdays as? MutableSet<Int>
        if (asMutable != null) {
            assertFailsWith<UnsupportedOperationException> { asMutable.add(7) }
            assertFailsWith<UnsupportedOperationException> { asMutable.clear() }
        }
        assertEquals(setOf(2, 4), a.weekdays)
        // A copy shares nothing the original can see.
        val b = a.copy(weekdays = setOf(1))
        assertEquals(setOf(2, 4), a.weekdays)
        assertEquals(setOf(1), b.weekdays)
    }

    @Test
    fun theAlarmComparesAsSwiftDoesAndChangesOnlyThroughCopy() {
        for (type in listOf(RingAlarm::class.java, RingAlarmDecision.Fire::class.java, RingAlarmDecision.Missed::class.java)) {
            assertEquals(emptyList(), type.methods.filter { it.name.startsWith("set") }.map { it.name }, "no setters on ${type.simpleName}")
        }
        // Swift's `==` on doubles: −0.0 equals 0.0 (and hashes alike), NaN is unequal even to itself. A set
        // compares by its members, whatever order it was given in.
        assertEquals(RingAlarm(burstSpacing = 0.0), RingAlarm(burstSpacing = -0.0))
        assertEquals(RingAlarm(burstSpacing = 0.0).hashCode(), RingAlarm(burstSpacing = -0.0).hashCode())
        assertNotEquals(RingAlarm(burstSpacing = Double.NaN), RingAlarm(burstSpacing = Double.NaN))
        assertEquals(RingAlarm(weekdays = linkedSetOf(5, 1, 3)), RingAlarm(weekdays = linkedSetOf(1, 3, 5)))
        assertEquals(RingAlarm(weekdays = linkedSetOf(5, 1, 3)).hashCode(), RingAlarm(weekdays = setOf(1, 3, 5)).hashCode())
        val base = RingAlarm(isEnabled = true, hour = 6, minute = 45, weekdays = setOf(2, 3), pattern = VibrationPattern.LONG, burstCount = 4, burstSpacing = 6.0, backupNotification = false)
        for (changed in listOf(
            base.copy(isEnabled = false), base.copy(hour = 7), base.copy(minute = 46), base.copy(weekdays = setOf(2)),
            base.copy(pattern = VibrationPattern.NOTIFICATION), base.copy(burstCount = 5), base.copy(burstSpacing = 6.5), base.copy(backupNotification = true),
        )) {
            assertNotEquals(base, changed)
        }
        assertEquals(base, base.copy())
        assertEquals(listOf<Any>(true, 6, 45, setOf(2, 3), VibrationPattern.LONG, 4, 6.0, false), listOf(base.isEnabled, base.hour, base.minute, base.weekdays, base.pattern, base.burstCount, base.burstSpacing, base.backupNotification))
        // The decision compares its lateness the same way.
        val t = Instant.parse("2026-09-01T07:00:00Z")
        assertEquals(RingAlarmDecision.Fire(t, 0.0), RingAlarmDecision.Fire(t, -0.0))
        assertEquals(RingAlarmDecision.Fire(t, 0.0).hashCode(), RingAlarmDecision.Fire(t, -0.0).hashCode())
        assertNotEquals(RingAlarmDecision.Fire(t, Double.NaN), RingAlarmDecision.Fire(t, Double.NaN))
        assertNotEquals<RingAlarmDecision>(RingAlarmDecision.Fire(t, 0.0), RingAlarmDecision.Missed(t))
    }

    @Test
    fun noZoneOrClockParameterHasADefaultAndNoSourceLineReadsOne() {
        // The only defaulted parameters are upstream's settings (the warm-up window and the grace) and `copy`.
        // Every scheduling rule takes exactly one required ZoneId and a required instant.
        val schedule = RingAlarmSchedule::class.java
        assertEquals(listOf("decide\$default", "warmUpTarget\$default"), schedule.declaredMethods.filter { it.name.endsWith("\$default") }.map { it.name }.sorted())
        assertEquals(listOf("copy\$default"), RingAlarm::class.java.declaredMethods.filter { it.name.endsWith("\$default") }.map { it.name })
        for (name in listOf("warmUpTarget", "mostRecentOccurrence", "nextOccurrence", "decide")) {
            val m = schedule.declaredMethods.single { it.name == name }
            assertEquals(1, m.parameterTypes.count { it == ZoneId::class.java }, "$name takes one zone")
            assertTrue(m.parameterTypes.contains(Instant::class.java), "$name takes an instant")
        }

        val root = File(assertNotNull(System.getProperty("opencircuit.androidRoot"), "system property opencircuit.androidRoot is not set"))
        val defaultedClock = Regex("""\w+\s*:\s*(Instant|ZoneId|Locale|Clock|TimeZone)\??\s*=(?!\s*(?:null\b|this\.))""")
        val ambient = Regex("""\b[A-Z]\w*\.now\(|\bClock\.system|\bsystemDefault\(|\bTimeZone\.getDefault\(|\bLocale\.getDefault\(|currentTimeMillis\(|nanoTime\(|getenv\b|\.format\(""")
        val hits = listOf("RingAlarm.kt", "RingProximity.kt", "CalendarDay.kt").flatMap { name ->
            val source = File(root, "ringkit/src/main/kotlin/io/github/opencircuit/ringkit/$name")
            assertTrue(source.isFile, "missing source $name")
            StrippedSource(name, source.readText()).lines.withIndex()
                .filter { (_, line) -> defaultedClock.containsMatchIn(line) || ambient.containsMatchIn(line) }
                .map { "$name:${it.index + 1}: ${it.value.trim()}" }
        }
        assertEquals(emptyList(), hits, "a defaulted or ambient instant, zone, locale or clock, or locale-reading formatting")
        assertTrue(defaultedClock.containsMatchIn("    fun decide(alarm: RingAlarm, now: Instant, zone: ZoneId = ZoneOffset.UTC)"), "the default pattern bites")
        assertFalse(defaultedClock.containsMatchIn("lastHandledAt: Instant?, zone: ZoneId,"), "a required zone is not a default")
        assertTrue(ambient.containsMatchIn("return \"≈ %d ft\".format(n)"), "the formatting pattern bites")
    }

    @Test
    fun nothingReadsTheMachineTimeZoneOrLocale() {
        val zone = ZoneId.of("America/New_York")
        val t0 = Instant.parse("2026-03-07T12:00:00Z")
        val alarms = listOf(RingAlarm(isEnabled = true, hour = 2, minute = 30), RingAlarm(isEnabled = true, hour = 7, minute = 0, weekdays = setOf(1, 7)))
        fun results(): List<Any?> = (0 until 48).flatMap { i ->
            val now = t0.plusSeconds(3_600L * i)
            alarms.map { a ->
                listOf(
                    RingAlarmSchedule.mostRecentOccurrence(now, a, zone),
                    RingAlarmSchedule.nextOccurrence(now, a, zone),
                    RingAlarmSchedule.decide(a, now, lastHandledAt = null, zone = zone),
                    RingAlarmSchedule.warmUpTarget(a, now, zone),
                )
            }
        } + (-100..0).map { RingProximity.distanceText(it) } + (-100..0).map { RingProximity.band(it).label } +
            alarms.map { it.toString() } + RingAlarmBlock.entries.map { it.rawValue }
        val savedLocale = Locale.getDefault()
        val savedZone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.ROOT)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val reference = results()
            assertTrue(reference.contains("≈ 12 ft") && reference.contains("Right here"), "the texts are in the comparison")
            for ((locale, tz) in listOf(Locale.forLanguageTag("ar-EG") to "Pacific/Kiritimati", Locale.forLanguageTag("hi-IN-u-nu-deva") to "Australia/Lord_Howe", Locale.forLanguageTag("tr-TR") to "America/Santiago")) {
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
