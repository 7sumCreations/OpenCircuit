package io.github.opencircuit.ringkit

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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards for what the Kotlin reminder port adds or could lose relative to Swift: the three raw names in
 * upstream's order, equal to the notification ledger's reminder names (one de-dupe namespace), matched
 * exactly; the defaults typed from upstream; every reminder is immutable, compares its doubles by IEEE
 * `==` as Swift's synthesized `Equatable` does, and changes only through `copy`; no zone or clock has a
 * default and the source reads none; nothing reads the machine's zone or locale. The hostile inputs are
 * in `ReminderEngineHazardTest`.
 */
class ReminderGuardTest {

    @Test
    fun rawNamesOrderAndDefaultsAreUpstreams() {
        // ReminderEngine.swift:15-19, typed from upstream, in declaration order.
        assertEquals(listOf("reminder.sedentary", "reminder.wear", "reminder.bedtime"), ReminderKind.entries.map { it.rawValue })
        // The same names the notification ledger keys its reminder cases by (HealthAlerts.swift), so a
        // reminder routed through the shared gate de-dupes against its own stamp.
        assertEquals(
            ReminderKind.entries.map { it.rawValue },
            listOf(HealthNotification.SEDENTARY_REMINDER, HealthNotification.WEAR_REMINDER, HealthNotification.BEDTIME_REMINDER).map { it.rawValue },
        )
        // Swift's `init?(rawValue:)` is exact: a stored name that is re-cased, padded, misspelt or written
        // in look-alike or fullwidth letters is no reminder.
        for (k in ReminderKind.entries) assertEquals(k, ReminderKind.fromRawValue(k.rawValue))
        for (bad in listOf("Reminder.sedentary", "reminder.sedentary ", " reminder.wear", "reminder.Wear", "reminder.bedtimes", "reminder_wear", "reminder.wеar", "ｒeminder.wear", "", "sedentary", "SEDENTARY")) {
            assertNull(ReminderKind.fromRawValue(bad), "\"$bad\"")
        }
        // Defaults (:65-67, :156-157, :219), typed from upstream.
        val s = SedentaryReminder()
        assertEquals(listOf<Any>(3_000.0, 480, 1_260), listOf(s.interval, s.activeStartMinutes, s.activeEndMinutes))
        val w = WearReminder()
        assertEquals(listOf(3_600.0, 14_400.0), listOf(w.noDataInterval, w.chargerGrace))
        assertEquals(30, BedtimeReminder().minutesBefore)
    }

    @Test
    fun remindersAreImmutableValuesThatCompareAsSwiftDoes() {
        for (type in listOf(SedentaryReminder::class.java, WearReminder::class.java, BedtimeReminder::class.java)) {
            assertEquals(emptyList(), type.methods.filter { it.name.startsWith("set") }.map { it.name }, "no setters on ${type.simpleName}")
        }
        // Swift's `==` on doubles: −0.0 equals 0.0 (and hashes alike), NaN is unequal even to itself.
        assertEquals(SedentaryReminder(interval = 0.0), SedentaryReminder(interval = -0.0))
        assertEquals(SedentaryReminder(interval = 0.0).hashCode(), SedentaryReminder(interval = -0.0).hashCode())
        assertNotEquals(SedentaryReminder(interval = Double.NaN), SedentaryReminder(interval = Double.NaN))
        assertNotEquals(SedentaryReminder(), SedentaryReminder(activeEndMinutes = 1_261))
        assertNotEquals(SedentaryReminder(), SedentaryReminder(activeStartMinutes = 479))
        assertEquals(WearReminder(chargerGrace = 0.0), WearReminder(chargerGrace = -0.0))
        assertEquals(WearReminder(chargerGrace = 0.0).hashCode(), WearReminder(chargerGrace = -0.0).hashCode())
        assertNotEquals(WearReminder(noDataInterval = Double.NaN), WearReminder(noDataInterval = Double.NaN))
        assertNotEquals(WearReminder(), WearReminder(chargerGrace = 3_600.0))
        assertEquals(BedtimeReminder(30), BedtimeReminder())
        assertNotEquals(BedtimeReminder(31), BedtimeReminder())
        // A copy never changes the original (upstream's `var` fields change a copy of the struct).
        val s = SedentaryReminder()
        val changed = s.copy(interval = 600.0, activeEndMinutes = 1_320)
        assertEquals(listOf<Any>(3_000.0, 1_260), listOf(s.interval, s.activeEndMinutes))
        assertEquals(SedentaryReminder(600.0, 480, 1_320), changed)
        val w = WearReminder()
        assertEquals(listOf(3_600.0, 7_200.0), listOf(w.noDataInterval, w.copy(chargerGrace = 7_200.0).chargerGrace))
        assertEquals(14_400.0, w.chargerGrace)
        val b = BedtimeReminder()
        assertEquals(5, b.copy(minutesBefore = 5).minutesBefore)
        assertEquals(30, b.minutesBefore)
    }

    @Test
    fun noZoneOrClockParameterHasADefaultAndNoSourceLineReadsOne() {
        // The defaulted parameters are upstream's: the reminders' settings, the suppression inputs (the
        // charger byte, the stamps, the link, the sleep window), and `copy`. The zone and `now` are required.
        val types = listOf(SedentaryReminder::class.java, WearReminder::class.java, BedtimeReminder::class.java)
        val defaults = types.associate { type -> type.simpleName to type.declaredMethods.filter { it.name.endsWith("\$default") }.map { it.name }.sorted() }
        assertEquals(
            mapOf(
                "SedentaryReminder" to listOf("copy\$default", "shouldFire\$default"),
                "WearReminder" to listOf("copy\$default", "shouldFire\$default"),
                "BedtimeReminder" to listOf("copy\$default"),
            ),
            defaults,
        )
        // Each zone-reading rule takes a ZoneId; the wear rule reads no time of day and takes none.
        val zoneParams = types.associate { type ->
            type.simpleName to type.declaredMethods.single { it.name == "shouldFire" }.parameterTypes.count { it == ZoneId::class.java }
        }
        assertEquals(mapOf("SedentaryReminder" to 1, "WearReminder" to 0, "BedtimeReminder" to 1), zoneParams)

        val root = File(assertNotNull(System.getProperty("opencircuit.androidRoot"), "system property opencircuit.androidRoot is not set"))
        val defaultedClock = Regex("""\w+\s*:\s*(Instant|ZoneId|Locale|Clock|TimeZone)\??\s*=(?!\s*(?:null\b|this\.))""")
        val ambient = Regex("""\b[A-Z]\w*\.now\(|\bClock\.system|\bsystemDefault\(|\bTimeZone\.getDefault\(|\bLocale\.getDefault\(|currentTimeMillis\(|nanoTime\(|getenv\b""")
        val name = "ReminderEngine.kt"
        val source = File(root, "ringkit/src/main/kotlin/io/github/opencircuit/ringkit/$name")
        assertTrue(source.isFile, "missing source $name")
        val hits = StrippedSource(name, source.readText()).lines.withIndex()
            .filter { (_, line) -> defaultedClock.containsMatchIn(line) || ambient.containsMatchIn(line) }
            .map { "$name:${it.index + 1}: ${it.value.trim()}" }
        assertEquals(emptyList(), hits, "a defaulted or ambient instant, zone, locale or clock")
        assertTrue(defaultedClock.containsMatchIn("        zone: ZoneId = ZoneId.of(\"UTC\"),"), "the default pattern bites")
        assertFalse(defaultedClock.containsMatchIn("        lastOffFingerAt: Instant? = null,"), "\"none\" is not a clock")
    }

    @Test
    fun nothingReadsTheMachineTimeZoneOrLocale() {
        val zone = ZoneId.of("Asia/Kolkata")
        val t0 = Instant.parse("2026-06-17T00:00:00Z")
        fun results(): List<List<Any?>> = (0 until 96).map { i ->
            val now = t0.plusSeconds(900L * i)
            listOf(
                SedentaryReminder().shouldFire(lastActivityAt = t0.minusSeconds(3_600), now = now, lastRingDataAt = now.minusSeconds(60), zone = zone),
                BedtimeReminder().shouldFire(now = now, bedMinutes = 23 * 60, wakeMinutes = 7 * 60, zone = zone),
                BedtimeReminder(90).shouldFire(now = now, bedMinutes = 60, wakeMinutes = 7 * 60, zone = zone),
                WearReminder().shouldFire(lastRingDataAt = t0, now = now, everConnected = true),
                ReminderKind.entries.map { it.rawValue },
                SedentaryReminder().toString(),
                WearReminder().toString(),
            )
        }
        val savedLocale = Locale.getDefault()
        val savedZone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.ROOT)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val reference = results()
            assertTrue(reference.any { it[0] == true } && reference.any { it[0] == false }, "the sedentary rule answers both ways over the day")
            assertTrue(reference.any { it[1] == true } && reference.any { it[2] == true }, "both bedtime windows open over the day")
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
