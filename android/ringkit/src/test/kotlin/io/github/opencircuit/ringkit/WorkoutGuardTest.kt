package io.github.opencircuit.ringkit

import java.io.File
import java.time.Instant
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
 * Guards for what the Kotlin workout port adds or could lose relative to Swift: the raw names upstream
 * stores, matched exactly; the constants typed from upstream; every value immutable and comparing its
 * doubles by IEEE `==`, as Swift's synthesized `Equatable` does; the session aggregator owned by one
 * workout, its copy fully independent; ring cursors over the whole unsigned 32-bit range and never past
 * it; no clock has a default, every function upstream defaulted to the device clock takes one, and the
 * source reads none; nothing reads the machine's zone or locale. The hostile inputs are in
 * `WorkoutHazardTest` and `WorkoutHRGateAndRecoveryHazardTest`.
 */
class WorkoutGuardTest {

    private val t0: Instant = Instant.parse("2026-07-09T18:00:00Z")
    private val profile = UserProfile(age = 35, weightKg = 70.0, heightCm = 175.0, sex = BiologicalSex.MALE)

    @Test
    fun rawNamesAreUpstreamsAndMatchedExactly() {
        // The stored case names, in declaration order (WorkoutSession.swift, AutomaticWorkoutDetection.swift,
        // WorkoutSessionRecovery.swift).
        assertEquals(
            listOf(
                "walkingOutdoor", "runningOutdoor", "runningIndoor", "cyclingOutdoor", "cyclingIndoor",
                "rowing", "hiking", "strengthTraining", "yoga", "other",
            ),
            WorkoutSportType.entries.map { it.rawValue },
        )
        assertEquals(listOf(1, 2, 3, 4, 5), HRZone.entries.map { it.rawValue })
        assertEquals(listOf("walking", "running"), AutomaticWorkoutDetector.SuggestedKind.entries.map { it.rawValue })
        assertEquals(listOf("noObservedSpan", "endsInTheFuture"), WorkoutRecoveryRefusal.entries.map { it.rawValue })
        for (t in WorkoutSportType.entries) assertEquals(t, WorkoutSportType.fromRawValue(t.rawValue))
        for (z in HRZone.entries) assertEquals(z, HRZone.fromRawValue(z.rawValue))
        for (k in AutomaticWorkoutDetector.SuggestedKind.entries) assertEquals(k, AutomaticWorkoutDetector.SuggestedKind.fromRawValue(k.rawValue))
        for (r in WorkoutRecoveryRefusal.entries) assertEquals(r, WorkoutRecoveryRefusal.fromRawValue(r.rawValue))
        // Swift's `init?(rawValue:)` matches the exact string: re-cased, padded, look-alike and fullwidth
        // names, and an enum's Kotlin name, are no case.
        for (name in listOf("WalkingOutdoor", "WALKING_OUTDOOR", " yoga", "yoga ", "ｙｏｇａ", "Other", "OTHER", "hıking", "")) {
            assertNull(WorkoutSportType.fromRawValue(name), "'$name'")
        }
        for (name in listOf("NoObservedSpan", "NO_OBSERVED_SPAN", "endsinthefuture", "endsInTheFuture ")) {
            assertNull(WorkoutRecoveryRefusal.fromRawValue(name), "'$name'")
        }
        assertNull(AutomaticWorkoutDetector.SuggestedKind.fromRawValue("Walking"))
        assertNull(HRZone.fromRawValue(0))
        assertNull(HRZone.fromRawValue(6))
    }

    @Test
    fun constantsAreUpstreams() {
        // WorkoutHRGate.swift:29, WorkoutSession.swift (hold cap, live slack), AutomaticWorkoutDetection.swift
        // (opcode, record length, interval, minimum duration, retention, announcement age).
        assertEquals(
            listOf<Any>(6.0, 30.0, 2.0, 0x4d, 11, 10.0, 600.0, 172_800.0, 86_400.0),
            listOf<Any>(
                WorkoutHRGate.DEFAULT_MAX_AGE, HRZoneClassifier.DEFAULT_HOLD_CAP_SECONDS, WorkoutBufferedSportFill.LIVE_OVERLAP_SLACK,
                HistoricalSportFrame.OPCODE, HistoricalSportFrame.RECORD_LENGTH, HistoricalSportFrame.INTERVAL_SECONDS,
                AutomaticWorkoutDetector.MINIMUM_DURATION, AutomaticWorkoutInbox.RETENTION, AutomaticWorkoutAnnouncement.MAX_AGE,
            ),
        )
    }

    @Test
    fun valuesAreImmutableAndCompareTheirDoublesAsSwiftDoes() {
        val types = listOf(
            WorkoutSessionSnapshot::class.java, RecoveredWorkout::class.java, WorkoutRecoveryDecision.Discard::class.java,
            WorkoutRecoveryDecision.Offer::class.java, WorkoutZoneBreakdown::class.java, WorkoutSummary::class.java, CursorSpan::class.java,
            HistoricalSportFrame.Sample::class.java, AutomaticWorkoutDetector.Candidate::class.java, AutomaticWorkoutInbox.State::class.java,
            AutomaticWorkoutConfirmation.Prepared::class.java, WorkoutBufferedSportFill.Fill::class.java,
        )
        for (type in types) {
            assertEquals(emptyList(), type.methods.filter { it.name.startsWith("set") }.map { it.name }, "no setters on ${type.simpleName}")
        }
        // Swift's `==` on doubles (measured on the snapshot): −0.0 equals 0.0 (and hashes alike here), NaN is
        // unequal even to itself.
        fun snap(kcal: Double?) = WorkoutSessionSnapshot(WorkoutSportType.YOGA, t0, t0.plusSeconds(60), 3, kcal, 90, 99)
        assertEquals(snap(0.0), snap(-0.0))
        assertEquals(snap(0.0).hashCode(), snap(-0.0).hashCode())
        assertNotEquals(snap(Double.NaN), snap(Double.NaN))
        assertNotEquals(snap(1.0), snap(null))
        assertNotEquals(snap(1.0), snap(1.0).copy(maxHR = 100))
        fun recovered(kcal: Double?) = RecoveredWorkout(WorkoutSportType.YOGA, t0, t0.plusSeconds(60), 3, kcal)
        assertEquals(recovered(0.0), recovered(-0.0))
        assertEquals(recovered(0.0).hashCode(), recovered(-0.0).hashCode())
        assertNotEquals(recovered(Double.NaN), recovered(Double.NaN))
        assertEquals(WorkoutRecoveryDecision.Offer(recovered(0.0)), WorkoutRecoveryDecision.Offer(recovered(-0.0)))
        // A value's copy never changes the original.
        val original = snap(5.0)
        val later = original.copy(lastAliveAt = t0.plusSeconds(120), hrSampleCount = 4)
        assertEquals(t0.plusSeconds(60), original.lastAliveAt)
        assertEquals(3, original.hrSampleCount)
        assertEquals(4, later.hrSampleCount)
    }

    @Test
    fun theSessionAggregatorBelongsToOneWorkoutAndItsCopyIsIndependent() {
        // Upstream's aggregator is a main-actor class (one owner); a snapshot of it is a copy. Every piece of
        // its state - the readings and the live high-water mark - is the copy's own.
        val live = WorkoutSessionAggregator(startDate = t0, userAge = 35)
        for (k in 0 until 10) live.add(HRSample(bpm = 120, start = t0.plusSeconds(10L * k)))
        val highWater = live.liveActiveKcal(profile, asOf = t0.plusSeconds(600))
        val snapshot = live.copy()
        live.add(HRSample(bpm = 180, start = t0.plusSeconds(610)))
        assertEquals(10, snapshot.collectedSamples.size)
        assertEquals(120, snapshot.currentAvgHR)
        // The copy's high-water came with it and moves on its own: a later read on the copy at a shorter
        // elapsed time still shows it, and a read on the copy never raises the original's.
        assertEquals(highWater, snapshot.liveActiveKcal(profile, asOf = t0.plusSeconds(60)))
        val copyHigher = snapshot.liveActiveKcal(profile, asOf = t0.plusSeconds(7_200))
        assertTrue(copyHigher > highWater)
        assertTrue(live.liveActiveKcal(profile, asOf = t0.plusSeconds(611)) < copyHigher)
        // The reading list handed out is a snapshot, not a view: changing it changes nothing.
        @Suppress("UNCHECKED_CAST")
        val handedOut = live.collectedSamples as? MutableList<HRSample>
        handedOut?.clear()
        assertEquals(11, live.collectedSamples.size)
    }

    @Test
    fun cursorsSpanTheWholeUnsigned32BitRangeAndNoMore() {
        // A decoded cursor is an unsigned 32-bit value: bytes 80 00 00 00 and ff ff ff ff are 2³¹ and 2³² − 1,
        // never negative.
        fun page(cursors: List<Long>): ByteArray {
            val body = listOf(0x4d, 0x00, 0x00) + cursors.flatMap { c ->
                listOf((c shr 24).toInt() and 0xff, (c shr 16).toInt() and 0xff, (c shr 8).toInt() and 0xff, c.toInt() and 0xff, 90, 5, 0, 0, 0, 0, 0)
            }
            return (body + body.fold(0) { x, b -> x xor b }).map { it.toByte() }.toByteArray()
        }
        val decoded = assertNotNull(HistoricalSportFrame.decode(page(listOf(0L, 0x7FFF_FFFFL, 0x8000_0000L, 0xFFFF_FFFFL))))
        assertEquals(listOf(0L, 2_147_483_647L, 2_147_483_648L, 4_294_967_295L), decoded.map { it.cursor })
        assertEquals(Instant.ofEpochSecond(Command.SYNC_EPOCH + 4_294_967_295L), decoded.last().endDate)
        // Every type that holds a cursor takes the whole range and refuses anything outside it.
        for (c in listOf(0L, 0xFFFF_FFFFL)) {
            assertEquals(c, HistoricalSportFrame.Sample(cursor = c, heartRate = null, steps = 0).cursor)
            assertEquals(CursorSpan(c, c), CursorSpan(c))
        }
        for (c in listOf(-1L, 0x1_0000_0000L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { HistoricalSportFrame.Sample(cursor = c, heartRate = null, steps = 0) }
            assertFailsWith<IllegalArgumentException> { CursorSpan(c) }
            assertFailsWith<IllegalArgumentException> { CursorSpan(0, c) }
            assertFailsWith<IllegalArgumentException> { CursorSpan.migratedFromLegacyCursor(c) }
        }
        // A detected bout at the very top of the range keeps its cursors as they are.
        val top = (0 until 60).map { HistoricalSportFrame.Sample(cursor = 0xFFFF_FFFFL - 590 + it * 10L, heartRate = 100, steps = 20) }
        val bout = AutomaticWorkoutDetector.detect(top).single()
        assertEquals(CursorSpan(0xFFFF_FFFFL - 590, 0xFFFF_FFFFL), bout.cursorSpan)
        assertTrue(bout.cursorSpan.overlaps(CursorSpan(0xFFFF_FFFFL)))
        assertFalse(bout.cursorSpan.overlaps(CursorSpan(0)))
    }

    @Test
    fun noClockParameterHasADefaultAndNoSourceLineReadsOne() {
        // The defaulted parameters are upstream's settings (window, retention, ages, gaps, coverage, cap,
        // already-merged and live cursors, steps). Every function upstream defaulted to the device clock -
        // the inbox's `rebuild` and the recovery's `decide` - takes a required instant, as does every
        // other function that reads a time.
        fun defaulted(c: Class<*>) = c.declaredMethods.map { it.name }.filter { it.endsWith("\$default") }.sorted()
        assertEquals(listOf("shouldRecord\$default"), defaulted(WorkoutHRGate::class.java))
        assertEquals(emptyList(), defaulted(WorkoutSessionRecovery::class.java))
        assertEquals(listOf("rebuild\$default"), defaulted(AutomaticWorkoutInbox::class.java))
        assertEquals(listOf("shouldAnnounce\$default"), defaulted(AutomaticWorkoutAnnouncement::class.java))
        assertEquals(emptyList(), defaulted(AutomaticWorkoutConfirmation::class.java))
        assertEquals(listOf("finalize\$default"), defaulted(WorkoutSessionAggregator::class.java))
        val takesInstant = listOf(
            WorkoutHRGate::class.java, WorkoutSessionRecovery::class.java, AutomaticWorkoutInbox::class.java,
            AutomaticWorkoutAnnouncement::class.java, WorkoutSessionAggregator::class.java, HRZoneClassifier::class.java,
            Class.forName("io.github.opencircuit.ringkit.AutomaticWorkoutDetectionKt"),
        ).flatMap { c ->
            c.declaredMethods
                .filter { m -> java.lang.reflect.Modifier.isPublic(m.modifiers) && !m.name.endsWith("\$default") && m.parameterTypes.contains(Instant::class.java) }
                .map { "${c.simpleName}.${it.name}" }
        }.sorted()
        assertEquals(
            listOf(
                "AutomaticWorkoutAnnouncement.shouldAnnounce", "AutomaticWorkoutDetectionKt.prunedToRelevant", "AutomaticWorkoutInbox.rebuild",
                "HRZoneClassifier.timeInZonesHeld", "WorkoutHRGate.shouldRecord", "WorkoutSessionAggregator.finalize",
                "WorkoutSessionAggregator.liveActiveKcal", "WorkoutSessionRecovery.decide",
            ),
            takesInstant,
        )

        val root = File(assertNotNull(System.getProperty("opencircuit.androidRoot"), "system property opencircuit.androidRoot is not set"))
        val defaultedClock = Regex("""\w+\s*:\s*(Instant|ZoneId|Locale|Clock|TimeZone)\??\s*=(?!\s*null\b)""")
        val ambient = Regex("""\b[A-Z]\w*\.now\(|\bClock\.system|\bsystemDefault\(|\bTimeZone\.getDefault\(|\bLocale\.getDefault\(|currentTimeMillis\(|nanoTime\(|getenv\b""")
        val hits = listOf("WorkoutHRGate.kt", "WorkoutSessionRecovery.kt", "WorkoutSession.kt", "AutomaticWorkoutDetection.kt").flatMap { name ->
            val source = File(root, "ringkit/src/main/kotlin/io/github/opencircuit/ringkit/$name")
            assertTrue(source.isFile, "missing source $name")
            StrippedSource(name, source.readText()).lines.withIndex()
                .filter { (_, line) -> defaultedClock.containsMatchIn(line) || ambient.containsMatchIn(line) }
                .map { "$name:${it.index + 1}: ${it.value.trim()}" }
        }
        assertEquals(emptyList(), hits, "a defaulted or ambient instant, zone, locale or clock")
        assertTrue(defaultedClock.containsMatchIn("    fun decide(snapshot: WorkoutSessionSnapshot?, now: Instant = Instant.EPOCH)"), "the default pattern bites")
        assertTrue(ambient.containsMatchIn("val now = Instant.now()"), "the ambient pattern bites")
        assertFalse(defaultedClock.containsMatchIn("    val startDate: Instant,"), "a required instant is not a default")
    }

    @Test
    fun nothingReadsTheMachineTimeZoneOrLocale() {
        val samples = (0 until 70).map { HistoricalSportFrame.Sample(cursor = 200_000_000L + it * 10L, heartRate = 95 + it % 30, steps = 18) }
        fun results(): List<Any?> {
            val inbox = AutomaticWorkoutInbox.rebuild(emptyList(), samples, resolvedSpans = emptyList(), now = samples.last().endDate.plusSeconds(60))
            val candidate = inbox.candidates.single()
            val prepared = AutomaticWorkoutConfirmation.prepare(candidate, WorkoutSportType.WALKING_OUTDOOR, profile)
            val snapshot = WorkoutSessionSnapshot(WorkoutSportType.HIKING, t0, t0.plusSeconds(3_300), 120, 412.5, 131, 164)
            return listOf(
                candidate.toString(), candidate.suggestedKind, prepared.summary.toString(), prepared.summary.zoneBreakdown.toString(),
                WorkoutSessionRecovery.decide(snapshot, now = t0.plusSeconds(9 * 3600)).toString(),
                WorkoutHRGate.shouldRecord(t0, t0, null, t0.plusSeconds(5)), WorkoutSportType.HIKING.displayName,
                HRZone.FAT_BURN.displayName, snapshot.toString(),
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
