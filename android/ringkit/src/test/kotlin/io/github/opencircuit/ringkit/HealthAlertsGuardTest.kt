package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.HealthAlertEvaluator.ActivityInterval
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
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Guards for what the Kotlin alert port adds or could lose relative to Swift: the 13 raw names in
 * upstream's order (the order the gate delivers in, the names the ledgers are keyed by); every value
 * type is immutable, compares its doubles by IEEE `==` as Swift's synthesized `Equatable` does, and
 * changes only through `copy`; every list handed back is the function's own (Swift's arrays are
 * values), and the per-night set is read-only; the defaults are upstream's; no zone or clock has a
 * default and no source line reads one; nothing reads the machine's zone or locale. The hostile inputs
 * are in `HealthAlertsHazardTest`.
 */
class HealthAlertsGuardTest {

    private val t0: Instant = Instant.parse("2026-06-17T12:00:00Z")

    @Test
    fun rawNamesOrderAndDefaultsAreUpstreams() {
        // HealthAlerts.swift:22-46, typed from upstream, in declaration order.
        assertEquals(
            listOf(
                "highHR", "lowSpO2", "elevatedHRInactive", "skinTempRise", "skinTempDrop", "skinTempFluctuationRise",
                "skinTempFluctuationDrop", "fever", "reminder.sedentary", "reminder.wear", "reminder.bedtime",
                "battery.chargingComplete", "headache.signs",
            ),
            HealthNotification.entries.map { it.rawValue },
        )
        assertEquals(HealthNotification.entries.size, HealthNotification.entries.map { it.rawValue }.toSet().size, "raw names are unique")
        // :458-460, in upstream's order.
        assertEquals(
            listOf(
                HealthNotification.SKIN_TEMP_RISE, HealthNotification.SKIN_TEMP_DROP, HealthNotification.SKIN_TEMP_FLUCTUATION_RISE,
                HealthNotification.SKIN_TEMP_FLUCTUATION_DROP, HealthNotification.FEVER,
            ),
            TempFeverNotifications.NOTIFICATION_SET.toList(),
        )
        // Defaults (:57, :95, :153-163, :354, :377, :406; the app's :207), typed from upstream.
        assertEquals(QuietHours(enabled = false, startMinutes = 1_320, endMinutes = 420), QuietHours())
        assertEquals(7_200.0, NotificationGate().renotifyInterval)
        val t = HealthAlertThresholds()
        assertEquals(
            listOf<Any>(true, 120, true, 90, 2, 1_800.0, 1_200.0, true, 100, 600.0, 300.0),
            listOf(
                t.highHREnabled, t.highHRBpm, t.lowSpO2Enabled, t.lowSpO2Percent, t.lowSpO2MinReadings, t.lowSpO2Window, t.lowSpO2MaxGap,
                t.elevatedHREnabled, t.elevatedHRBpm, t.elevatedSustained, t.elevatedMaxGap,
            ),
        )
        assertEquals(
            listOf(1_800.0, 600.0, 600.0, 43_200.0),
            listOf(
                HealthAlertEvaluator.MAX_ACTIVITY_WINDOW, HealthAlertEvaluator.RING_ACTIVITY_LEAD, HealthAlertEvaluator.RECOVERY_PAD,
                HealthAlertLookback.BASE_INSTANT_LOOKBACK,
            ),
        )
    }

    @Test
    fun valuesAreImmutableAndCompareAsSwiftDoes() {
        val types = listOf(
            QuietHours::class.java, NotificationGate::class.java, SpO2Reading::class.java, HealthAlertThresholds::class.java,
            HealthAlertHit::class.java, ActivityInterval::class.java, StepWindow::class.java,
        )
        for (type in types) assertEquals(emptyList(), type.methods.filter { it.name.startsWith("set") }.map { it.name }, "no setters on ${type.simpleName}")

        // Swift's `==` on doubles: −0.0 equals 0.0 (and hashes alike), NaN is unequal even to itself.
        assertEquals(NotificationGate(0.0), NotificationGate(-0.0))
        assertEquals(NotificationGate(0.0).hashCode(), NotificationGate(-0.0).hashCode())
        assertNotEquals(NotificationGate(Double.NaN), NotificationGate(Double.NaN))
        assertEquals(HealthAlertThresholds(lowSpO2Window = 0.0), HealthAlertThresholds(lowSpO2Window = -0.0))
        assertEquals(HealthAlertThresholds(lowSpO2Window = 0.0).hashCode(), HealthAlertThresholds(lowSpO2Window = -0.0).hashCode())
        assertNotEquals(HealthAlertThresholds(elevatedMaxGap = Double.NaN), HealthAlertThresholds(elevatedMaxGap = Double.NaN))
        assertNotEquals(HealthAlertThresholds(), HealthAlertThresholds(lowSpO2MinReadings = 3))
        assertNotEquals(HealthAlertThresholds(), HealthAlertThresholds(highHREnabled = false))
        val hit = HealthAlertHit(HealthNotification.HIGH_HR, 0.0, t0)
        assertEquals(hit, hit.copy(value = -0.0))
        assertEquals(hit.hashCode(), hit.copy(value = -0.0).hashCode())
        assertNotEquals(hit.copy(value = Double.NaN), hit.copy(value = Double.NaN))
        assertNotEquals(hit, hit.copy(notification = HealthNotification.LOW_SPO2))
        // A copy never changes the original.
        val th = HealthAlertThresholds()
        val changed = th.copy(highHRBpm = 150, lowSpO2Window = 60.0)
        assertEquals(listOf<Any>(120, 1_800.0), listOf(th.highHRBpm, th.lowSpO2Window))
        assertEquals(listOf<Any>(150, 60.0), listOf(changed.highHRBpm, changed.lowSpO2Window))
        val gate = NotificationGate()
        val faster = gate.copy(renotifyInterval = 60.0)
        assertEquals(listOf(7_200.0, 60.0), listOf(gate.renotifyInterval, faster.renotifyInterval))
        val q = QuietHours(enabled = true)
        assertEquals(QuietHours(enabled = true, startMinutes = 60, endMinutes = 420), q.copy(startMinutes = 60))
        assertEquals(1_320, q.startMinutes)
    }

    @Test
    fun everyListHandedBackIsTheFunctionsOwn() {
        // Swift's arrays are values: what a function returns cannot change when the caller later edits
        // the array it passed in. Each call here is fed a MutableList that is edited afterwards.
        val hr = mutableListOf(HRSample(130, t0), HRSample(105, t0.plusSeconds(300)))
        val noActivity = HealthAlertEvaluator.nonExercising(hr, activeIntervals = emptyList())
        val someActivity = HealthAlertEvaluator.nonExercising(hr, listOf(ActivityInterval(t0.plusSeconds(10_000), t0.plusSeconds(10_001))))
        val candidates = mutableListOf(HealthNotification.FEVER, HealthNotification.HIGH_HR)
        val filtered = NotificationGate().filter(candidates, t0, emptyMap(), QuietHours(), ZoneId.of("UTC"))
        val fresh = TempFeverNotifications.freshForNight(candidates, 20_260_617L, emptyMap())
        val steps = mutableListOf(StepWindow(t0, t0.plusSeconds(60), 10))
        val intervals = HealthAlertEvaluator.activeStepIntervals(steps)
        hr.clear()
        candidates.clear()
        steps.clear()
        assertEquals(2, noActivity.size, "nonExercising with no intervals hands back its own list")
        assertNotSame<List<HRSample>>(hr, noActivity)
        assertEquals(2, someActivity.size)
        assertEquals(listOf(HealthNotification.HIGH_HR, HealthNotification.FEVER), filtered)
        assertEquals(listOf(HealthNotification.FEVER, HealthNotification.HIGH_HR), fresh)
        assertEquals(1, intervals.size)
        // The per-night set cannot be edited through a cast.
        @Suppress("UNCHECKED_CAST")
        assertFailsWith<UnsupportedOperationException> { (TempFeverNotifications.NOTIFICATION_SET as MutableSet<HealthNotification>).add(HealthNotification.HIGH_HR) }
        assertEquals(5, TempFeverNotifications.NOTIFICATION_SET.size)
    }

    @Test
    fun noZoneOrClockParameterHasADefaultAndNoSourceLineReadsOne() {
        // The defaulted parameters are upstream's: thresholds, windows, gaps, the lead and pad, the
        // ledger and the SpO₂ cut ("none"). The zone is required everywhere upstream defaults its calendar.
        val evaluatorDefaults = HealthAlertEvaluator::class.java.declaredMethods.filter { it.name.endsWith("\$default") }.map { it.name }.sorted()
        assertEquals(
            listOf(
                "activeStepIntervals\$default", "elevatedHRInactive\$default", "evaluate\$default", "lowSpO2\$default",
                "nonExercising\$default", "ringActivityIntervals\$default",
            ),
            evaluatorDefaults,
        )
        for (type in listOf(QuietHours::class.java, NotificationGate::class.java, TempFeverNotifications::class.java, HealthAlertLookback::class.java)) {
            val defaults = type.declaredMethods.filter { it.name.endsWith("\$default") }.map { it.name }
            assertEquals(emptyList(), defaults.filter { !it.startsWith("copy") }, "only copy has defaults on ${type.simpleName}")
        }

        val root = File(assertNotNull(System.getProperty("opencircuit.androidRoot"), "system property opencircuit.androidRoot is not set"))
        val defaultedClock = Regex("""\w+\s*:\s*(Instant|ZoneId|Locale|Clock|TimeZone)\??\s*=(?!\s*(?:null\b|this\.))""")
        val ambient = Regex("""\b[A-Z]\w*\.now\(|\bClock\.system|\bsystemDefault\(|\bTimeZone\.getDefault\(|\bLocale\.getDefault\(|currentTimeMillis\(|nanoTime\(|getenv\b""")
        val name = "HealthAlerts.kt"
        val source = File(root, "ringkit/src/main/kotlin/io/github/opencircuit/ringkit/$name")
        assertTrue(source.isFile, "missing source $name")
        val hits = StrippedSource(name, source.readText()).lines.withIndex()
            .filter { (_, line) -> defaultedClock.containsMatchIn(line) || ambient.containsMatchIn(line) }
            .map { "$name:${it.index + 1}: ${it.value.trim()}" }
        assertEquals(emptyList(), hits, "a defaulted or ambient instant, zone, locale or clock")
        assertTrue(defaultedClock.containsMatchIn("fun f(zone: ZoneId = ZoneId.of(\"UTC\"))"), "the default pattern bites")
        assertFalse(defaultedClock.containsMatchIn("since: Instant? = null,"), "\"none\" is not a clock")
    }

    @Test
    fun nothingReadsTheMachineTimeZoneOrLocale() {
        val zone = ZoneId.of("Asia/Kolkata")
        val quiet = QuietHours(enabled = true, startMinutes = 22 * 60, endMinutes = 7 * 60)
        fun results(): List<Any?> = (0 until 48).map { i ->
            val now = t0.plusSeconds(1_800L * i)
            listOf(
                quiet.contains(now, zone),
                NotificationGate().filter(HealthNotification.entries, now, mapOf(HealthNotification.FEVER to t0), quiet, zone),
                TempFeverNotifications.dayKey(now, zone),
                HealthNotification.entries.map { it.rawValue },
                HealthAlertThresholds().toString(),
            )
        }
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
