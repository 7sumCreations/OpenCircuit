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
 * default and no source line reads one; nothing reads the machine's zone or locale. For the
 * overnight-signals notification: its set, category, window and words typed from upstream; its copy a
 * value, its lists its own, its set read-only; its casing locale-free under a foreign default locale;
 * and "no number in the copy" asked in Swift's sense of a number, which is wider than `isDigit`. The
 * hostile inputs are in `HealthAlertsHazardTest`.
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
        // The overnight-signals notification: upstream's tuning and limit only (the data class's own
        // `copy` aside); the zone of the window, the day key and the decision is required.
        assertEquals(
            listOf("candidates\$default", "topSignals\$default"),
            HeadacheSignsNotifications::class.java.declaredMethods.filter { it.name.endsWith("\$default") }.map { it.name }.sorted(),
        )

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
                HeadacheSignsNotifications.withinDeliveryWindow(now, zone),
                HeadacheSignsNotifications.dayKey(now, zone),
                HeadacheSignsNotifications.candidates(true, HeadacheSignals.Band.FLAGGED, null, 30, false, now, emptyMap(), zone = zone),
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

    // MARK: the overnight-signals notification

    @Test
    fun theOvernightNotificationsNamesWindowAndWordsAreUpstreams() {
        // HealthAlerts.swift :535, :540, :556-557, :646-656, :696-700, typed from upstream.
        assertEquals(listOf(HealthNotification.HEADACHE_SIGNS), HeadacheSignsNotifications.NOTIFICATION_SET.toList())
        assertEquals("headache.signs", HeadacheSignsNotifications.CATEGORY_IDENTIFIER)
        assertEquals(HealthNotification.HEADACHE_SIGNS.rawValue, HeadacheSignsNotifications.CATEGORY_IDENTIFIER, "the category and the raw name agree")
        assertEquals(listOf(420, 1_260), listOf(HeadacheSignsNotifications.EARLIEST_MINUTES, HeadacheSignsNotifications.LATEST_MINUTES))
        assertEquals(
            listOf(
                "sleep efficiency", "daytime heart rate", "heart rate variability", "resting heart rate", "time awake in bed", "sleep duration",
                "bedtime", "skin temperature", "cycle phase",
            ),
            HeadacheSignals.Feature.entries.map(HeadacheSignsNotifications::plainName),
        )
        assertEquals(
            listOf("last night", "over the past two days", "last night", "last night", "last night", "last night", "last night", "last night", "last night"),
            HeadacheSignals.Feature.entries.map(HeadacheSignsNotifications::timeframe),
        )
        assertEquals("last night", HeadacheSignsNotifications.NIGHTLY_PHRASE)
    }

    @Test
    fun theOvernightNotificationsValuesAndListsAreItsOwn() {
        // The copy is a value: no setters, equal by content, a copy never changes the original.
        val text = HeadacheSignsNotifications.copy(listOf(HeadacheSignals.Feature.HRV_DEVIATION))
        assertEquals(emptyList(), HeadacheSignsNotifications.Text::class.java.methods.filter { it.name.startsWith("set") }.map { it.name })
        assertEquals(text, HeadacheSignsNotifications.Text(text.title, text.body))
        assertEquals("Last night was unusual for you", text.copy(body = "x").title)
        assertEquals(text, HeadacheSignsNotifications.copy(listOf(HeadacheSignals.Feature.HRV_DEVIATION)))
        // Every list handed back is the function's own: the caller editing what it passed in afterwards
        // changes nothing (Swift's arrays and dictionaries are values).
        val features = mutableListOf(HeadacheSignals.Feature.AROUSAL_LETDOWN)
        val wording = HeadacheSignsNotifications.copy(features)
        val shares = mutableMapOf(HeadacheSignals.Feature.HRV_DEVIATION to 0.2, HeadacheSignals.Feature.SCHEDULE_SHIFT to 0.1)
        val top = HeadacheSignsNotifications.topSignals(shares)
        val candidates = mutableListOf(HealthNotification.HEADACHE_SIGNS)
        val ledger = mutableMapOf<HealthNotification, Long>()
        val fresh = HeadacheSignsNotifications.freshForDay(candidates, 20_260_720L, ledger)
        val raised = HeadacheSignsNotifications.candidates(true, HeadacheSignals.Band.FLAGGED, null, 30, false, t0, ledger, zone = ZoneId.of("UTC"))
        features[0] = HeadacheSignals.Feature.HRV_DEVIATION
        shares.clear()
        candidates.clear()
        ledger[HealthNotification.HEADACHE_SIGNS] = Long.MAX_VALUE
        assertEquals("Your recent signals stood out", wording.title)
        assertEquals(listOf(HeadacheSignals.Feature.HRV_DEVIATION, HeadacheSignals.Feature.SCHEDULE_SHIFT), top)
        assertEquals(listOf(HealthNotification.HEADACHE_SIGNS), fresh)
        assertEquals(listOf(HealthNotification.HEADACHE_SIGNS), raised)
        // The set cannot be edited through a cast.
        @Suppress("UNCHECKED_CAST")
        assertFailsWith<UnsupportedOperationException> { (HeadacheSignsNotifications.NOTIFICATION_SET as MutableSet<HealthNotification>).add(HealthNotification.FEVER) }
        assertEquals(1, HeadacheSignsNotifications.NOTIFICATION_SET.size)
    }

    @Test
    fun aForeignDefaultLocaleNeverChangesTheCopy() {
        // Swift's `uppercased()` reads no locale. Under a Turkish or Azeri default a locale-reading upper
        // case dots an `i` ("idle" → "İdle"), and Lithuanian keeps extra dots — none of it may reach the copy.
        val features = HeadacheSignals.Feature.entries
        val signals = listOf(emptyList<HeadacheSignals.Feature>()) + features.map { listOf(it) } + features.flatMap { a -> features.map { b -> listOf(a, b) } }
        fun results(): List<Any> = signals.map { HeadacheSignsNotifications.copy(it) } +
            listOf(HeadacheSignsNotifications.sentenceCased("idle"), HeadacheSignsNotifications.sentenceCased("ıdle"), HeadacheSignsNotifications.sentenceCased(""))
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ROOT)
            val reference = results()
            assertEquals(listOf("Idle", "Idle", ""), reference.takeLast(3))
            for (tag in listOf("tr-TR", "az-AZ", "lt-LT", "ar-EG", "el-GR")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals(reference, results(), tag)
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun noCopyCarriesANumberInSwiftsSenseWhichIsWiderThanIsDigit() {
        // Upstream's copy test asks Swift's `Character.isNumber`: a Unicode number category or a numeric
        // ideograph — superscripts, fractions, Roman numerals and 八 are numbers, the letter A is not
        // (Java's `getNumericValue` reads it as 10). Kotlin's `isDigit` misses most of them.
        val numbers = listOf(0x37, 0x663, 0xB2, 0xBD, 0x216B, 0x3007, 0x516B, 0x4E00, 0x1D7E0)
        val notNumbers = listOf(0x41, 0xFF21, 0x61, 0x25, 0x20, 0x2014, 0xE9, 0x5B57)
        assertEquals(numbers.map { true }, numbers.map(::isSwiftNumber), "number scalars")
        assertEquals(notNumbers.map { false }, notNumbers.map(::isSwiftNumber), "letters and punctuation")
        assertEquals(listOf(0xB2, 0xBD, 0x216B, 0x3007, 0x516B, 0x4E00), numbers.filter { !Character.isDigit(it) }, "what isDigit misses")
        assertEquals(10, Character.getNumericValue(0x41), "why getNumericValue is not the class either")
        assertTrue("x²".containsSwiftNumber())
        assertFalse("estimate — not a forecast".containsSwiftNumber())
        // Every copy the notification can produce (no signal, each single, every ordered pair) carries none.
        val features = HeadacheSignals.Feature.entries
        val signals = listOf(emptyList<HeadacheSignals.Feature>()) + features.map { listOf(it) } + features.flatMap { a -> features.map { b -> listOf(a, b) } }
        for (s in signals) {
            val (title, body) = HeadacheSignsNotifications.copy(s)
            assertFalse(title.containsSwiftNumber() || body.containsSwiftNumber(), "a number in: $title / $body")
        }
    }
}
