package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Guards for what the Kotlin night-bookkeeping port adds or could lose relative to Swift: the
 * constants (typed from upstream's sources), value semantics (Swift structs and arrays copy; here
 * nothing has a setter and a plan keeps its own read-only lists), no read of the machine's zone,
 * locale or clock (every answer and every sentence is identical under default locales that print
 * non-ASCII digits and far-flung default zones), the merge being idempotent and independent of
 * arrival order, and the precondition that makes upstream's conditional zero-score heal test bite.
 */
class NightBookkeepingGuardTest {

    private fun s(epoch: Long): Instant = Instant.ofEpochSecond(epoch)
    private fun noSetters(type: Class<*>): List<String> = type.methods.filter { it.name.startsWith("set") }.map { it.name }

    @Test
    fun constantsEqualUpstreamLiterals() {
        assertEquals(12, SleepNightKey.WAKE_WINDOW_END_HOUR) // SleepNightKey.swift:56
        assertEquals(Duration.ofMinutes(20), SleepHealthGate.SETTLE_MARGIN) // SleepHealthGate.swift:19, 20 * 60
        assertEquals(Duration.ofSeconds((4.75 * 3600).toLong()), SleepCaptureCoverage.RING_BUFFER) // SleepCaptureCoverage.swift:23
        assertEquals(Duration.ofMinutes(20), SleepCaptureCoverage.BUFFER_SLACK) // :27
        assertEquals(Duration.ofMinutes(90), SleepCaptureCoverage.MIN_MISSING_ONSET) // :30
        assertEquals(60.0, SleepEditedNightNotice.MIN_ASSERTED_ASLEEP) // SleepEditedNightNotice.swift:62
        assertEquals(1.0, SleepEditedNightNotice.NO_MEASURED_ASLEEP) // :70
        // SleepPersistOutcome.swift:28-47 — the eight cases, in upstream's order.
        assertEquals(
            listOf(
                "inserted", "updated", "keptFullerStoredNight", "keptManualEdit",
                "refusedNightKeyCollision", "noStagedSegments", "deferredNightKeyMigration", "failed",
            ),
            SleepPersistOutcome.entries.map { it.rawValue },
        )
    }

    /** Swift structs and arrays copy on assignment: here no value has a setter and a plan keeps its own lists. */
    @Test
    fun valuesHaveNoSettersAndAPlanKeepsItsOwnLists() {
        for (type in listOf(SleepNightRekeyPlan.Row::class.java, SleepNightRekeyPlan.Move::class.java, SleepNightRekeyPlan.Plan::class.java)) {
            assertEquals(emptyList(), noSetters(type), type.simpleName)
        }
        val move = SleepNightRekeyPlan.Move(s(0), s(86_400))
        val source = arrayListOf(move)
        val plan = SleepNightRekeyPlan.Plan(source, source)
        source.clear()
        assertEquals(listOf(move), plan.moves, "changing the list a plan was built from never changes the plan")
        assertEquals(listOf(move), plan.refused)
        assertFailsWith<UnsupportedOperationException> { (plan.moves as MutableList<SleepNightRekeyPlan.Move>).clear() }
        assertFailsWith<UnsupportedOperationException> { (plan.refused as MutableList<SleepNightRekeyPlan.Move>).add(move) }
        assertEquals(SleepNightRekeyPlan.Plan(listOf(move), emptyList()), SleepNightRekeyPlan.Plan(listOf(move), emptyList()), "plans compare by value")
    }

    /** Every zone is named and `now` is passed: no answer, and no sentence, moves with the machine's zone or locale. */
    @Test
    fun nothingReadsTheMachineZoneLocaleOrClock() {
        val ny = ZoneId.of("America/New_York")
        val havana = ZoneId.of("America/Havana")
        fun results(): List<Any?> = listOf(
            SleepNightKey.night(inBedStart = s(1_793_480_400), inBedEnd = s(1_793_509_200), zone = havana),
            SleepNightKey.endsInWakeWindow(s(1_793_509_200), havana),
            SleepNightKey.rekeyed(storedNight = s(1_785_816_000), inBedStart = s(1_785_896_760), inBedEnd = s(1_785_936_340), zone = ny),
            SleepNightRekeyPlan.plan(listOf(SleepNightRekeyPlan.Row(s(1_785_816_000), s(1_785_896_760), s(1_785_936_340))), ny),
            MissedNight.morningWake(now = s(1_773_011_400), bedMinutes = 1350, wakeMinutes = 390, zone = ny),
            MissedNight.status(now = s(1_773_011_400), bedMinutes = 1350, wakeMinutes = 390, nightWake = s(1_772_800_000), wakeKnown = true, lastSyncAt = s(1_773_011_000), zone = ny),
            MissedNight.endedToday(inBedEnd = s(1_772_969_400), nightKey = s(1_772_946_000), now = s(1_773_011_400), zone = ny),
            SleepEditedNightNotice.line(measuredAsleep = 9720.0, assertedAsleep = 14_460.0, mirrorsSleepToHealth = true),
            SleepEditedNightNotice.line(measuredAsleep = 0.0, assertedAsleep = 14_760.0, mirrorsSleepToHealth = false),
            SleepEditedNightNotice.duration(5.5e17),
            SleepCaptureCoverage.classify(capturedOnset = s(1_780_013_500), capturedInBed = Duration.ofHours(4), scheduledBedtime = s(1_780_000_000)),
            SleepHealthGate.isSettled(latestSegmentEnd = s(1_000_000), now = s(1_001_200)),
            SleepScoreHeal.healedScore(hypnogram = listOf(SleepSegment(s(0), s(25_200), SleepStage.IN_BED), SleepSegment(s(0), s(21_600), SleepStage.ASLEEP_DEEP))),
        )

        val savedLocale = Locale.getDefault()
        val savedZone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.ROOT)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val baseline = results()
            assertEquals(
                "We kept the times you set. The ring recorded 2h 42m of the sleep above; for the other 4h 1m we have your account, not a measurement. Both reach Apple Health; your part is marked there as entered by you.",
                baseline[7],
            )
            for ((locale, tz) in listOf(
                Locale.forLanguageTag("ar-EG") to "Pacific/Kiritimati",
                Locale.forLanguageTag("fa-IR") to "Asia/Tehran",
                Locale.forLanguageTag("hi-IN-u-nu-deva") to "America/St_Johns",
                Locale.forLanguageTag("tr-TR") to "Asia/Kathmandu",
            )) {
                Locale.setDefault(locale)
                TimeZone.setDefault(TimeZone.getTimeZone(tz))
                assertEquals(baseline, results(), "default locale $locale, default zone $tz")
            }
        } finally {
            Locale.setDefault(savedLocale)
            TimeZone.setDefault(savedZone)
        }
    }

    /**
     * Repeated syncs: re-delivering the stored slice never changes it, and any arrival order of the
     * same slices ends on the same stored night — the one with the most sleep, then the widest in-bed
     * span (every slice here has a positive span; asleep zero means unknown).
     */
    @Test
    fun theMergeIsIdempotentAndIndependentOfArrivalOrder() {
        val slices = listOf(3_600L, 18_000L, 28_800L).flatMap { inBed -> listOf(0L, 3_600L, 14_400L, 25_200L).map { inBed to it } }
        fun replaces(st: Pair<Long, Long>, n: Pair<Long, Long>) = SleepSummaryMerge.shouldReplace(
            storedInBed = Duration.ofSeconds(st.first),
            newInBed = Duration.ofSeconds(n.first),
            storedAsleep = Duration.ofSeconds(st.second),
            newAsleep = Duration.ofSeconds(n.second),
        )
        fun fold(order: List<Pair<Long, Long>>): Pair<Long, Long> {
            var stored = order.first()
            for (n in order.drop(1)) if (replaces(stored, n)) stored = n
            return stored
        }
        fun <T> permutations(xs: List<T>): List<List<T>> =
            if (xs.size <= 1) listOf(xs) else xs.flatMap { x -> permutations(xs - x).map { listOf(x) + it } }

        var folds = 0
        for (a in slices.indices) for (b in a + 1 until slices.size) for (c in b + 1 until slices.size) for (d in c + 1 until slices.size) {
            val set = listOf(slices[a], slices[b], slices[c], slices[d])
            val expected = set.maxWith(compareBy<Pair<Long, Long>>({ it.second }, { it.first }))
            for (order in permutations(set)) {
                val stored = fold(order)
                assertEquals(expected, stored, "arrival order $order")
                assertEquals(stored, fold(order + stored + stored), "re-delivering the stored slice twice changes nothing")
                folds++
            }
        }
        assertEquals(495 * 24, folds)
    }

    /**
     * Upstream's zero-score heal test asserts only `if raw == 0`. Pin that the composite really does
     * floor to 0 on its night (one second asleep in a 12 h bed), so that test can never pass vacuously.
     */
    @Test
    fun upstreamsZeroScoreHealVectorReallyFloors() {
        val t0 = s(1_700_000_000)
        val segs = listOf(
            SleepSegment(t0, t0.plusSeconds(43_200), SleepStage.IN_BED),
            SleepSegment(t0, t0.plusSeconds(43_199), SleepStage.AWAKE),
            SleepSegment(t0.plusSeconds(43_199), t0.plusSeconds(43_200), SleepStage.ASLEEP_CORE),
        )
        val sm = SleepScoreHeal.summary(segs)!!
        val raw = SleepScore.composite(
            SleepScore.CompositeInput(
                totalAsleep = SleepStaging.seconds(sm.totalAsleep),
                timeAwake = SleepStaging.seconds(sm.awake),
                efficiency = sm.efficiency,
                deep = SleepStaging.seconds(sm.deep),
                light = SleepStaging.seconds(sm.light),
                rem = SleepStaging.seconds(sm.rem),
            ),
        ).score
        assertEquals(0, raw)
        assertTrue(SleepScoreHeal.healedScore(hypnogram = segs) == null)
    }
}
