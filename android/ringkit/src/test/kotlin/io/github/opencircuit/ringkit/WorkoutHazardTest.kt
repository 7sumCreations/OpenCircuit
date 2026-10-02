package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kotlin-only hostile-input checks for the workout engines and the detected-workout inbox: what the
 * upstream vectors never feed in. Sport records and their cursors come off the ring and are stored
 * between launches; the dismissed and announced cursor spans are stored bookkeeping; HR samples,
 * windows, distances and the profile come from the phone. So here the cursors arrive at both ends of
 * their unsigned 32-bit range, the frames arrive truncated, corrupt, empty and oversized (built on the
 * raw byte path), the settings arrive NaN, infinite, zero and negative, and the samples arrive
 * duplicated, tied and out of order. Kept out of the upstream-port classes so their counts stay exact.
 *
 * Every upstream outcome quoted below was measured on the pinned Swift build (Swift 6.3.2). Where the
 * port deliberately differs the test says so, and `PORTING.md` records why.
 */
class WorkoutHazardTest {

    private val epoch = Command.SYNC_EPOCH
    private fun at(cursor: Long): Instant = Instant.ofEpochSecond(epoch + cursor)
    private fun rec(cursor: Long, hr: Int?, steps: Int) = HistoricalSportFrame.Sample(cursor = cursor, heartRate = hr, steps = steps)
    private fun bout(first: Long, n: Int, hr: Int = 90, steps: Int = 10) = (0 until n).map { rec(first + it * 10L, hr, steps) }
    private fun shape(s: HistoricalSportFrame.Sample) = "${s.cursor}:${s.heartRate}:${s.steps}"

    private val t0: Instant = Instant.ofEpochSecond(0)
    private fun t(seconds: Double): Instant = addingSeconds(t0, seconds)!!
    private val profile = UserProfile(age = 30, weightKg = 70.0, heightCm = 170.0, sex = BiologicalSex.MALE)

    /** A raw `0x4d` page: header `4d 00 <countdown>`, the given payload bytes, then the XOR trailer. */
    private fun page(payload: List<Int>, countdown: Int = 0): ByteArray {
        val body = listOf(0x4d, 0x00, countdown) + payload
        return (body + body.fold(0) { x, b -> x xor b }).map { it.toByte() }.toByteArray()
    }

    private fun record(cursor: Long, bpm: Int, steps: Int, aux: List<Int> = listOf(1, 2, 3, 4, 5)): List<Int> =
        listOf((cursor shr 24).toInt() and 0xff, (cursor shr 16).toInt() and 0xff, (cursor shr 8).toInt() and 0xff, cursor.toInt() and 0xff, bpm, steps) + aux

    // MARK: cursor spans

    @Test
    fun aLegacyCursorWidenedByARetentionUpstreamCannotConvertNeverTraps() {
        // Upstream's `cursor &+ UInt32(max(retention, 0))` (AutomaticWorkoutDetection.swift:99) traps on a
        // NaN, an infinite or a ≥ 2³² retention ("Double value cannot be converted to UInt32"). The port
        // converts as `UInt32(clamping:)` would, after truncation: NaN reads as no widening, anything past
        // the top saturates at 0xFFFFFFFF; the wrapping add stays exactly upstream's.
        assertEquals(CursorSpan(1000, 1000), CursorSpan.migratedFromLegacyCursor(1000, retention = Double.NaN)) // upstream traps
        assertEquals(CursorSpan(1000, 999), CursorSpan.migratedFromLegacyCursor(1000, retention = Double.POSITIVE_INFINITY)) // upstream traps
        assertEquals(CursorSpan(1000, 999), CursorSpan.migratedFromLegacyCursor(1000, retention = 4_294_967_296.0)) // upstream traps
        assertEquals(CursorSpan(1000, 999), CursorSpan.migratedFromLegacyCursor(1000, retention = 1e20)) // upstream traps
        // Measured upstream, kept:
        assertEquals(CursorSpan(1000, 999), CursorSpan.migratedFromLegacyCursor(1000, retention = 4_294_967_295.9))
        assertEquals(CursorSpan(1000, 1000), CursorSpan.migratedFromLegacyCursor(1000, retention = Double.NEGATIVE_INFINITY))
        assertEquals(CursorSpan(1000, 1000), CursorSpan.migratedFromLegacyCursor(1000, retention = -5.0))
        assertEquals(CursorSpan(1000, 1001), CursorSpan.migratedFromLegacyCursor(1000, retention = 1.9))
        assertEquals(CursorSpan(1000, 173_800), CursorSpan.migratedFromLegacyCursor(1000))
        // Near the top of the cursor range the end wraps, masked to 32 bits as upstream's `&+` wraps.
        assertEquals(CursorSpan(4_294_967_040, 172_544), CursorSpan.migratedFromLegacyCursor(0xFFFF_FF00))
    }

    @Test
    fun aWindowSpanClampsAtTheEpochAndAtTheTopOfTheCursorRange() {
        // `UInt32(clamping: Int64(max(s, 0).rounded(.down)))` (AutomaticWorkoutDetection.swift:110-112).
        // Upstream traps converting a date past 2⁶³ s to `Int64`; no `Instant` is that far, so the port's
        // ends of time clamp like Foundation's distant dates (measured: 0 … 0xFFFFFFFF).
        assertEquals(CursorSpan(0, 0), CursorSpan(window = DateInterval(Instant.ofEpochSecond(0), at(-100))))
        assertEquals(CursorSpan(0, 1000), CursorSpan(window = DateInterval(addingSeconds(at(0), -0.5)!!, addingSeconds(at(1000), 0.999)!!)))
        assertEquals(CursorSpan(0, 0xFFFF_FFFF), CursorSpan(window = DateInterval(at(0), Instant.ofEpochSecond(10_000_000_000))))
        assertEquals(CursorSpan(0, 0xFFFF_FFFF), CursorSpan(window = DateInterval(Instant.MIN, Instant.MAX)))
        assertEquals(CursorSpan(1000, 1000), CursorSpan(window = DateInterval(at(1000), at(1000))))
        // Kept difference (the exact-elapsed-time rule): 100 ns before a whole cursor second is the
        // previous cursor here; upstream's Double of seconds since 1970 cannot hold it and reads 1000.
        val justBefore = at(1000).minusNanos(100)
        assertEquals(CursorSpan(999, 1000), CursorSpan(window = DateInterval(justBefore, at(1000))))
    }

    @Test
    fun cursorsOutsideTheUnsigned32BitRangeCannotBeBuilt() {
        // Swift's `UInt32` makes these unrepresentable; the port rejects them at construction.
        assertFailsWith<IllegalArgumentException> { rec(-1, 80, 0) }
        assertFailsWith<IllegalArgumentException> { rec(0x1_0000_0000, 80, 0) }
        assertFailsWith<IllegalArgumentException> { CursorSpan(-1, 0) }
        assertFailsWith<IllegalArgumentException> { CursorSpan(0, 0x1_0000_0000) }
        assertFailsWith<IllegalArgumentException> { CursorSpan.migratedFromLegacyCursor(0x1_0000_0000) }
        assertEquals(at(0xFFFF_FFFF), rec(0xFFFF_FFFF, null, 0).endDate)
        // An inverted span compares as upstream's does (measured: true, false, true).
        assertTrue(CursorSpan(10, 5).overlaps(CursorSpan(0, 100)))
        assertFalse(CursorSpan(10, 5).overlaps(CursorSpan(point = 7)))
        assertTrue(CursorSpan(point = 0).overlaps(CursorSpan(point = 0)))
    }

    @Test
    fun aRetentionUpstreamCannotReadNeverErasesTheSpanLedger() {
        val now = at(3_000_000)
        val spans = listOf(CursorSpan(0, 10), CursorSpan(2_000_000, 2_900_000), CursorSpan(2_999_000, 2_999_500), CursorSpan(3_100_000, 3_200_000))
        // Upstream with a NaN retention compares every span against a NaN horizon and drops them all
        // (measured: []) — the caller would save an empty dismissed / announced ledger and every reviewed
        // bout would come back. The port keeps every span, as upstream's own inbox keeps every sample.
        assertEquals(spans, spans.prunedToRelevant(now = now, retention = Double.NaN))
        // Measured upstream, kept:
        assertEquals(spans, spans.prunedToRelevant(now = now, retention = Double.POSITIVE_INFINITY))
        assertEquals(spans, spans.prunedToRelevant(now = now, retention = 1e300))
        assertEquals(spans.drop(1), spans.prunedToRelevant(now = now, retention = 100_000.0))
        for (r in listOf(Double.NEGATIVE_INFINITY, -5.0, 0.0)) assertEquals(spans.takeLast(1), spans.prunedToRelevant(now = now, retention = r), "retention $r")
        assertEquals(emptyList(), emptyList<CursorSpan>().prunedToRelevant(now = now))
    }

    // MARK: the inbox

    @Test
    fun inboxRetentionEdgesFollowUpstream() {
        val now = at(3_000_000)
        val old = bout(1_000_000, 70) + bout(2_900_000, 70)
        fun counts(r: Double, at: Instant = now) = AutomaticWorkoutInbox.rebuild(existing = old, incoming = emptyList(), resolvedSpans = emptyList(), now = at, retention = r)
            .let { it.samples.size to it.candidates.size }
        // Measured upstream: a NaN cutoff keeps everything (Foundation's `>=` against a NaN date is true).
        assertEquals(140 to 2, counts(Double.NaN))
        assertEquals(140 to 2, counts(Double.POSITIVE_INFINITY))
        for (r in listOf(Double.NEGATIVE_INFINITY, -5.0, 0.0)) assertEquals(0 to 0, counts(r), "retention $r")
        assertEquals(70 to 1, counts(AutomaticWorkoutInbox.RETENTION))
        assertEquals(140 to 2, counts(AutomaticWorkoutInbox.RETENTION, at = at(0)), "a clock before every sample keeps them all")
    }

    @Test
    fun duplicatedCursorsPreferAValidHeartRateThenTheLaterCopy() {
        val dup = listOf(rec(100, null, 1), rec(100, null, 2), rec(200, 80, 3), rec(200, null, 4), rec(300, 81, 5), rec(300, 82, 6))
        val state = AutomaticWorkoutInbox.rebuild(existing = dup, incoming = emptyList(), resolvedSpans = emptyList(), now = at(1000))
        assertEquals(listOf("100:null:2", "200:80:3", "300:82:6"), state.samples.map(::shape)) // measured
        val groups = AutomaticWorkoutDetector.detect(
            samples = listOf(rec(100, null, 1), rec(100, null, 2), rec(110, 80, 3), rec(110, null, 4)),
            minimumDuration = 1.0, minimumCoverage = 0.0,
        )
        assertEquals(listOf(listOf("100:null:2", "110:80:3")), groups.map { g -> g.samples.map(::shape) }) // measured
    }

    // MARK: the detector

    @Test
    fun detectorSettingsFollowUpstreamAtEveryEdge() {
        val b = bout(10_000, 60)
        // `min(max(minimumCoverage, 0), 1)` (:221) with Swift's NaN-ignoring min / max: a NaN coverage
        // reaches the comparison and nothing passes. Measured:
        assertEquals(0, AutomaticWorkoutDetector.detect(samples = b, minimumCoverage = Double.NaN).size)
        for (c in listOf(-1.0, 2.0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.99, 1.0)) {
            assertEquals(1, AutomaticWorkoutDetector.detect(samples = b, minimumCoverage = c).size, "coverage $c")
        }
        for (d in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0, 600.0000001)) {
            assertEquals(0, AutomaticWorkoutDetector.detect(samples = b, minimumDuration = d).size, "duration $d")
        }
        for (d in listOf(1.0, 590.0, 600.0)) assertEquals(1, AutomaticWorkoutDetector.detect(samples = b, minimumDuration = d).size, "duration $d")
        for (g in listOf(Double.NaN, -1.0, 0.0, 9.999)) {
            assertEquals(List(60) { 1 }, AutomaticWorkoutDetector.detect(samples = b, minimumDuration = 1.0, maximumGap = g).map { it.samples.size }, "gap $g")
        }
        for (g in listOf(10.0, Double.POSITIVE_INFINITY)) {
            assertEquals(listOf(60), AutomaticWorkoutDetector.detect(samples = b, minimumDuration = 1.0, maximumGap = g).map { it.samples.size }, "gap $g")
        }
        assertEquals(emptyList(), AutomaticWorkoutDetector.detect(samples = emptyList()))
        assertEquals(listOf(10_000L to 60), AutomaticWorkoutDetector.detect(samples = b.reversed()).map { it.id to it.samples.size })
    }

    @Test
    fun cursorsAtBothEndsOfTheRangeDetectWithoutWrapping() {
        val c = AutomaticWorkoutDetector.detect(samples = listOf(rec(0xFFFF_FFFF, 80, 1), rec(0, 80, 1)), minimumDuration = 1.0)
        // Measured: start / end 1577793590 / 1577793600 (id 0) and 5872760885 / 5872760895 (id 4294967295).
        assertEquals(listOf(0L, 4_294_967_295L), c.map { it.id })
        assertEquals(listOf(at(-10), at(4_294_967_285)), c.map { it.start })
        assertEquals(listOf(at(0), at(0xFFFF_FFFF)), c.map { it.end })
        assertEquals(listOf(10.0, 10.0), c.map { it.duration })
        assertEquals(listOf(CursorSpan(point = 0), CursorSpan(point = 0xFFFF_FFFF)), c.map { it.cursorSpan })
    }

    @Test
    fun stepSumsAreTakenIn64Bits() {
        // Upstream sums steps in Swift's 64-bit `Int`; the port sums in `Long`, so no list of records wraps.
        val huge = AutomaticWorkoutDetector.detect(samples = bout(10_000, 60, steps = Int.MAX_VALUE)).single()
        assertEquals(60L * Int.MAX_VALUE, huge.steps)
        assertEquals(AutomaticWorkoutDetector.SuggestedKind.RUNNING, huge.suggestedKind)
        val negative = AutomaticWorkoutDetector.detect(samples = bout(10_000, 60, steps = -50)).single()
        assertEquals(-3000L, negative.steps) // measured: -3000, no suggestion
        assertNull(negative.suggestedKind)
        val window = DateInterval(at(0), at(2000))
        assertEquals(2L * Int.MAX_VALUE, WorkoutBufferedSportFill.fill(captured = emptyList(), buffered = listOf(rec(1000, 80, Int.MAX_VALUE), rec(1010, 80, Int.MAX_VALUE)), window = window).steps)
        val prepared = AutomaticWorkoutConfirmation.prepare(candidate = huge, sport = WorkoutSportType.RUNNING_OUTDOOR, profile = profile)
        assertEquals(60L * Int.MAX_VALUE, prepared.summary.steps)
    }

    @Test
    fun aCandidateBuiltByHandFollowsUpstream() {
        val empty = AutomaticWorkoutDetector.Candidate(start = t0, end = t0, samples = emptyList(), suggestedKind = null)
        assertEquals(0L, empty.id)
        assertEquals(CursorSpan(0, 0), empty.cursorSpan)
        assertNull(empty.averageHeartRate)
        assertNull(empty.maximumHeartRate)
        val mixed = AutomaticWorkoutDetector.Candidate(start = t0, end = t0, samples = listOf(rec(1, 81, 0), rec(2, 80, 0), rec(3, null, 0)), suggestedKind = null)
        assertEquals(80.5, mixed.averageHeartRate)
    }

    // MARK: zones

    @Test
    fun theHeldZoneCapFollowsUpstreamAtEveryEdge() {
        val samples = listOf(HRSample(150, t0, t(2.0)), HRSample(150, t(100.0), t(102.0)))
        fun total(cap: Double) = HRZoneClassifier.timeInZonesHeld(hrSamples = samples, maxHR = 200, sessionEnd = t(130.0), maxGapSeconds = cap).totalZoneSeconds
        // `min(max(held, 0), cap)` (WorkoutSession.swift:214): Swift's min ignores a NaN cap, so NaN holds
        // every gap uncapped. Measured:
        assertEquals(130.0, total(Double.NaN))
        assertEquals(130.0, total(Double.POSITIVE_INFINITY))
        assertEquals(0.0, total(-5.0))
        assertEquals(0.0, total(0.0))
        val late = HRZoneClassifier.timeInZonesHeld(hrSamples = listOf(HRSample(150, t(50.0))), maxHR = 200, sessionEnd = t0)
        assertEquals(0.0, late.totalZoneSeconds, "a session end before the reading holds nothing")
    }

    @Test
    fun tiedStartsKeepTheirGivenOrder() {
        // Upstream's `sorted { $0.start < $1.start }` (:211) is stable: of two readings stamped alike the
        // first given is held 0 s and the second takes the hold. Measured: [0, 0, 10, 0, 10] and [10, 0, 10, 0, 0].
        fun zones(order: List<HRSample>) = HRZoneClassifier.timeInZonesHeld(hrSamples = order, maxHR = 200, sessionEnd = t(20.0))
            .let { listOf(it.warmUpSeconds, it.fatBurnSeconds, it.aerobicSeconds, it.anaerobicSeconds, it.extremeSeconds) }
        assertEquals(listOf(0.0, 0.0, 10.0, 0.0, 10.0), zones(listOf(HRSample(100, t(10.0)), HRSample(190, t(10.0)), HRSample(150, t0))))
        assertEquals(listOf(10.0, 0.0, 10.0, 0.0, 0.0), zones(listOf(HRSample(190, t(10.0)), HRSample(100, t(10.0)), HRSample(150, t0))))
    }

    @Test
    fun zoneInputsAtTheEndsFollowUpstream() {
        // Measured: extreme, nil, nil, nil, nil (99 / 199 is 49.7 %), fat burning.
        assertEquals(HRZone.EXTREME, HRZoneClassifier.zone(bpm = Int.MAX_VALUE, maxHR = 1))
        assertNull(HRZoneClassifier.zone(bpm = 1, maxHR = Int.MAX_VALUE))
        assertNull(HRZoneClassifier.zone(bpm = -1, maxHR = 200))
        assertNull(HRZoneClassifier.zone(bpm = 100, maxHR = -200))
        assertNull(HRZoneClassifier.zone(bpm = 99, maxHR = 199))
        assertEquals(HRZone.FAT_BURN, HRZoneClassifier.zone(bpm = 61, maxHR = 100))
        assertEquals(0.0, HRZoneClassifier.timeInZones(hrSamples = listOf(HRSample(150, t(10.0), t0)), maxHR = 200).totalZoneSeconds, "end before start")
    }

    @Test
    fun aBreakdownBuiltByHandFollowsUpstream() {
        val negative = WorkoutZoneBreakdown(warmUpSeconds = -10.0, fatBurnSeconds = 30.0)
        assertEquals(listOf(-0.5, 1.5), listOf(negative.fraction(HRZone.WARM_UP), negative.fraction(HRZone.FAT_BURN))) // measured
        val unreadable = WorkoutZoneBreakdown(warmUpSeconds = Double.NaN, fatBurnSeconds = 30.0)
        assertEquals(listOf(0.0, 0.0), listOf(unreadable.fraction(HRZone.WARM_UP), unreadable.fraction(HRZone.FAT_BURN))) // NaN total: 0
        val infinite = WorkoutZoneBreakdown(warmUpSeconds = Double.POSITIVE_INFINITY, fatBurnSeconds = 30.0)
        assertTrue(infinite.fraction(HRZone.WARM_UP).isNaN()) // measured: ∞ / ∞
        assertEquals(0.0, infinite.fraction(HRZone.FAT_BURN))
        assertEquals(0.0, WorkoutZoneBreakdown().fraction(HRZone.AEROBIC), "total 0")
    }

    // MARK: the session aggregator

    @Test
    fun theFormulaMaxHRIsBoundedForAnyStoredAge() {
        // `max(220 − max(age, 1), 1)`: in 64 bits upstream, and no Kotlin `Int` age overflows it. Measured:
        // a 100 bpm reading for 10 s lands in no zone for ages ≤ 1 (max HR 219) and in extreme for ages ≥ 219 (max HR 1).
        for (age in listOf(Int.MIN_VALUE, -5, 0, 1)) assertEquals(0.0, tenSecondsAt100(age).totalZoneSeconds, "age $age")
        for (age in listOf(219, 220, 1000, Int.MAX_VALUE)) assertEquals(10.0, tenSecondsAt100(age).extremeSeconds, "age $age")
    }

    private fun tenSecondsAt100(age: Int): WorkoutZoneBreakdown {
        val a = WorkoutSessionAggregator(startDate = t0, userAge = age)
        a.add(HRSample(100, t0, t(10.0)))
        return a.finalize(sport = WorkoutSportType.OTHER, endDate = t(10.0), distanceMeters = null, hasRoute = false, profile = profile).zoneBreakdown
    }

    @Test
    fun hostileReadingsAndAnEndBeforeTheStartFollowUpstream() {
        val a = WorkoutSessionAggregator(startDate = t0, userAge = 30)
        a.add(HRSample(-7, t0))
        a.add(HRSample(2, t0))
        val s = a.finalize(sport = WorkoutSportType.OTHER, endDate = t(-60.0), distanceMeters = null, hasRoute = false, profile = profile)
        assertEquals(-2, s.avgHR) // measured: Swift's `/` truncates toward zero, as Kotlin's does
        assertEquals(2, s.maxHR)
        assertEquals(0.0, s.estimatedActiveKcal)
        assertEquals(-60.0, s.durationSeconds)
    }

    @Test
    fun distanceAndProfileEdgesFollowUpstream() {
        val hrKcal = 14.222060229445509 // measured: 150 bpm for 60 s, this profile
        for ((d, alone, withHR) in listOf(
            Triple(Double.NaN, null, hrKcal),
            Triple(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY),
            Triple(Double.NEGATIVE_INFINITY, null, hrKcal),
            Triple(-1.0, null, hrKcal),
            Triple(0.0, null, hrKcal),
            Triple(1e-300, 3.5e-302, hrKcal),
        )) {
            val a = WorkoutSessionAggregator(startDate = t0, userAge = 30)
            assertEquals(alone, a.finalize(WorkoutSportType.OTHER, t(60.0), d, false, profile).estimatedActiveKcal, "distance $d alone")
            a.add(HRSample(150, t0))
            assertEquals(withHR, a.finalize(WorkoutSportType.OTHER, t(60.0), d, false, profile).estimatedActiveKcal, "distance $d with HR")
        }
        val a = WorkoutSessionAggregator(startDate = t0, userAge = 30)
        a.add(HRSample(150, t0))
        // Swift's `[hr, distance].max()` keeps the first unless a later one compares greater: a NaN
        // distance estimate (NaN weight) loses to the HR estimate's 0. Measured: 0.0, +inf, 0.0.
        assertEquals(0.0, a.finalize(WorkoutSportType.OTHER, t(60.0), 1000.0, false, profile.copy(weightKg = Double.NaN)).estimatedActiveKcal)
        assertEquals(Double.POSITIVE_INFINITY, a.finalize(WorkoutSportType.OTHER, t(60.0), 1000.0, false, profile.copy(weightKg = Double.POSITIVE_INFINITY)).estimatedActiveKcal)
        assertEquals(0.0, a.finalize(WorkoutSportType.OTHER, t(60.0), 1000.0, false, profile.copy(weightKg = Double.NEGATIVE_INFINITY)).estimatedActiveKcal)
    }

    @Test
    fun theLiveEstimateHoldsItsHighWaterAcrossHostileReads() {
        val a = WorkoutSessionAggregator(startDate = t0, userAge = 30)
        a.add(HRSample(150, t0))
        // Measured in this order: 0.0, 0.0, 14.222060229445509, +inf, +inf.
        assertEquals(0.0, a.liveActiveKcal(profile, asOf = t(-60.0)), "asOf before the start")
        assertEquals(0.0, a.liveActiveKcal(profile.copy(weightKg = Double.NaN), asOf = t(60.0)))
        assertEquals(14.222060229445509, a.liveActiveKcal(profile, asOf = t(60.0)))
        assertEquals(Double.POSITIVE_INFINITY, a.liveActiveKcal(profile.copy(weightKg = Double.POSITIVE_INFINITY), asOf = t(60.0)))
        assertEquals(Double.POSITIVE_INFINITY, a.liveActiveKcal(profile, asOf = t(120.0)))
    }

    @Test
    fun oneAggregatorFinalizesRepeatedlyAndItsCopyIsIndependent() {
        val a = WorkoutSessionAggregator(startDate = t0, userAge = 30)
        a.add(HRSample(120, t0, t(5.0)))
        val first = a.finalize(WorkoutSportType.OTHER, t(60.0), null, false, profile)
        assertEquals(first, a.finalize(WorkoutSportType.OTHER, t(60.0), null, false, profile), "finalize does not consume the session")
        val snapshot = a.copy()
        a.add(HRSample(160, t(30.0)))
        assertEquals(2, a.collectedSamples.size) // measured: adding after finalize keeps going (2, avg 140)
        assertEquals(140, a.currentAvgHR)
        assertEquals(listOf(120), snapshot.collectedSamples.map { it.bpm }, "a copy does not see the original's later readings")
        snapshot.add(HRSample(60, t(40.0)))
        snapshot.backfill(listOf(HRSample(70, t(50.0))), DateInterval(t0, t(600.0)))
        assertEquals(listOf(120, 160), a.collectedSamples.map { it.bpm }, "nor the original the copy's")
        // The high-water mark is copied too, then moves separately.
        val live = WorkoutSessionAggregator(startDate = t0, userAge = 30).apply { add(HRSample(150, t0)) }
        val peak = live.liveActiveKcal(profile, asOf = t(600.0))
        val twin = live.copy()
        assertEquals(peak, twin.liveActiveKcal(profile, asOf = t(60.0)))
        val listed = a.collectedSamples
        a.add(HRSample(99, t(70.0)))
        assertEquals(2, listed.size, "a list read earlier is a snapshot")
    }

    // MARK: merges and fills

    @Test
    fun theBackfillWindowIsClosedAndTheLaterDuplicateWins() {
        val window = DateInterval(t0, t(600.0))
        val merged = WorkoutHRBackfill.merge(
            captured = listOf(HRSample(1, t(5.0)), HRSample(2, t(5.0))),
            stored = listOf(HRSample(3, t0), HRSample(4, t(600.0)), HRSample(5, t(600.0)), HRSample(6, t(-0.001)), HRSample(7, t(9000.0))),
            window = window,
        )
        assertEquals(listOf(3, 2, 5), merged.map { it.bpm }) // measured
        assertEquals(listOf(9), WorkoutHRBackfill.merge(captured = listOf(HRSample(9, t(9000.0))), stored = emptyList(), window = window).map { it.bpm })
    }

    @Test
    fun liveCursorCoverageNeverWrapsBelowZero() {
        // `record.cursor >= $0 && liveFrameCursors.contains(record.cursor - $0)` (WorkoutSession.swift:543)
        // never subtracts past zero. Masked to 32 bits instead, record 3 minus 4 would read 0xFFFFFFFF and
        // a live frame there would cover it. Measured (cursors used, HR):
        val window = DateInterval(addingSeconds(at(0), -100.0)!!, at(2000))
        val buffered = listOf(rec(3, 80, 1), rec(0, 81, 1), rec(9, 82, 1))
        fun used(live: Set<Long>) = WorkoutBufferedSportFill.fill(captured = emptyList(), buffered = buffered, window = window, liveFrameCursors = live)
            .let { it.cursors.sorted() to it.hrSamples.map { s -> s.bpm } }
        assertEquals(listOf(0L, 3L, 9L) to listOf(81, 80, 82), used(setOf(0xFFFF_FFFFL)))
        assertEquals(listOf(0L, 3L, 9L) to listOf(81, 80, 82), used(setOf(0xFFFF_FFF7L)))
        assertEquals(listOf(0L, 3L, 9L) to listOf(81, 80, 82), used(setOf(0xFFFF_FFFCL)))
        assertEquals(emptyList<Long>() to emptyList(), used(setOf(0L)))
        assertEquals(listOf(0L) to listOf(81), used(setOf(3L)))
        assertEquals(listOf(0L, 3L) to listOf(81, 80), used(setOf(4L)))
    }

    @Test
    fun fillEdgesFollowUpstream() {
        val window = DateInterval(at(0), at(2000))
        // A record's own 10 s interval must lie inside the window, both ends closed. Measured: [10, 2000].
        assertEquals(setOf(10L, 2000L), WorkoutBufferedSportFill.fill(emptyList(), listOf(rec(10, 80, 1), rec(2000, 81, 1), rec(2010, 82, 1), rec(9, 83, 1)), window).cursors)
        // A captured reading ending within 2 s of the interval covers it, both ends closed. Measured.
        assertEquals(setOf(1100L), WorkoutBufferedSportFill.fill(listOf(HRSample(1, at(980), at(988))), listOf(rec(1000, 80, 1), rec(1100, 81, 1)), window).cursors)
        assertEquals(setOf(1000L), WorkoutBufferedSportFill.fill(listOf(HRSample(1, at(980), addingSeconds(at(987), 0.999)!!)), listOf(rec(1000, 80, 1)), window).cursors)
        assertEquals(emptySet(), WorkoutBufferedSportFill.fill(listOf(HRSample(1, at(980), at(1002))), listOf(rec(1000, 80, 1)), window).cursors)
        // A stored record's HR is not re-validated, and a record repeated in the buffer is used twice
        // (kept as upstream: the caller passes the inbox's de-duplicated records). Measured: [81, 82, 300], 14.
        val f = WorkoutBufferedSportFill.fill(emptyList(), listOf(rec(3, 80, 1), rec(1000, 81, 2), rec(1000, 82, 3), rec(1010, null, 4), rec(1020, 300, 5)), window, liveFrameCursors = setOf(0xFFFF_FFFFL))
        assertEquals(listOf(81, 82, 300), f.hrSamples.map { it.bpm })
        assertEquals(14L, f.steps)
        assertEquals(setOf(1000L, 1010L, 1020L), f.cursors)
    }

    // MARK: frames, on the raw byte path

    @Test
    fun hostileFramesAreRejectedAndAnEmptyPageIsEmpty() {
        assertEquals(emptyList(), HistoricalSportFrame.decode(page(emptyList()))) // measured: [] (not nil)
        assertNull(HistoricalSportFrame.decode(byteArrayOf(0x4d, 0x00, 0x4d))) // too short
        assertNull(HistoricalSportFrame.decode(byteArrayOf(0x4e, 0x00, 0x00, 0x4e))) // wrong opcode, valid XOR
        assertNull(HistoricalSportFrame.decode(page(List(12) { 1 }))) // a payload that is not whole records
        val good = page(record(100, 80, 12) + record(110, 81, 13))
        assertEquals(2, assertNotNull(HistoricalSportFrame.decode(good)).size)
        val corrupt = good.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0xff).toByte() }
        assertNull(HistoricalSportFrame.decode(corrupt))
        val truncated = page(record(100, 80, 12) + record(110, 81, 13).take(6)) // XOR-valid, last record cut short
        assertNull(HistoricalSportFrame.decode(truncated))
        assertNull(HistoricalSportFrame.decode(ByteArray(0)))
    }

    @Test
    fun anOversizedPageDecodesEveryRecordAtTheTopOfTheCursorRange() {
        val payload = (0 until 5000).flatMap { record(0xFFFF_0000L + it, 0xdd, 0xff) }
        val samples = assertNotNull(HistoricalSportFrame.decode(page(payload)))
        // Measured: 5000 records, first cursor 4294901760, HR nil (221 is outside the valid range),
        // 255 steps, auxiliary [1, 2, 3, 4, 5]; last cursor 4294906759, ending at 5872700359 s.
        assertEquals(5000, samples.size)
        assertEquals(4_294_901_760L, samples.first().cursor)
        assertNull(samples.first().heartRate)
        assertEquals(255, samples.first().steps)
        assertEquals(listOf(1, 2, 3, 4, 5), samples.first().auxiliary.map { it.toInt() and 0xff })
        assertEquals(4_294_906_759L, samples.last().cursor)
        assertEquals(Instant.ofEpochSecond(5_872_700_359), samples.last().endDate)
    }

    @Test
    fun aDecodedSampleDoesNotShareTheCallersBytes() {
        val frame = page(record(100, 80, 12))
        val sample = assertNotNull(HistoricalSportFrame.decode(frame)).single()
        frame.fill(0)
        assertEquals(listOf(1, 2, 3, 4, 5), sample.auxiliary.map { it.toInt() })
        sample.auxiliary[0] = 99
        assertEquals(1, sample.auxiliary[0].toInt(), "the auxiliary bytes are read out as a copy")
        val aux = byteArrayOf(7, 8)
        val built = HistoricalSportFrame.Sample(cursor = 1, heartRate = null, steps = 0, auxiliary = aux)
        aux[0] = 0
        assertEquals(7, built.auxiliary[0].toInt(), "and copied in")
        assertEquals(built, HistoricalSportFrame.Sample(cursor = 1, heartRate = null, steps = 0, auxiliary = byteArrayOf(7, 8)), "compared by content")
    }

    // MARK: announcement and confirmation

    @Test
    fun theAnnouncementAgeFollowsUpstream() {
        val c = AutomaticWorkoutDetector.detect(samples = bout(10_000, 60)).single()
        // Measured: NaN false, −1 false, 0 true, +∞ true; long before the end true; exactly 24 h true.
        assertFalse(AutomaticWorkoutAnnouncement.shouldAnnounce(c, announcedSpans = emptyList(), now = c.end, maxAge = Double.NaN))
        assertFalse(AutomaticWorkoutAnnouncement.shouldAnnounce(c, announcedSpans = emptyList(), now = c.end, maxAge = -1.0))
        assertTrue(AutomaticWorkoutAnnouncement.shouldAnnounce(c, announcedSpans = emptyList(), now = c.end, maxAge = 0.0))
        assertTrue(AutomaticWorkoutAnnouncement.shouldAnnounce(c, announcedSpans = emptyList(), now = c.end, maxAge = Double.POSITIVE_INFINITY))
        assertTrue(AutomaticWorkoutAnnouncement.shouldAnnounce(c, announcedSpans = emptyList(), now = c.start.minusSeconds(1_000_000_000)))
        assertTrue(AutomaticWorkoutAnnouncement.shouldAnnounce(c, announcedSpans = emptyList(), now = c.end.plusSeconds(86_400)))
        // Kept difference (the exact-elapsed-time rule): 1 ns past the age is past it here; upstream's
        // Date doubles cannot hold the nanosecond.
        assertFalse(AutomaticWorkoutAnnouncement.shouldAnnounce(c, announcedSpans = emptyList(), now = c.end.plusSeconds(86_400).plusNanos(1)))
    }

    @Test
    fun confirmingACandidateWithoutHRAndAHostileOneFollowsUpstream() {
        val noHR = AutomaticWorkoutDetector.detect(samples = (0 until 60).map { rec(20_000L + it * 10, null, 0) }, minimumCoverage = 0.0).single()
        val p = AutomaticWorkoutConfirmation.prepare(candidate = noHR, sport = WorkoutSportType.WALKING_OUTDOOR, profile = profile)
        assertNull(p.summary.avgHR) // measured: nil, nil, steps nil, 0 samples
        assertNull(p.summary.estimatedActiveKcal)
        assertNull(p.summary.steps)
        assertEquals(emptyList(), p.heartRateSamples)
        // A candidate built by hand with its end before its start and its records descending.
        val hostile = AutomaticWorkoutDetector.Candidate(start = at(500), end = at(100), samples = listOf(rec(300, 90, 7), rec(200, 100, 8)), suggestedKind = null)
        val h = AutomaticWorkoutConfirmation.prepare(hostile, WorkoutSportType.YOGA, UserProfile(age = -40, weightKg = 70.0, heightCm = 170.0, sex = BiologicalSex.FEMALE))
        // Measured: avg 95, 0.0 kcal, 0 zone seconds, 15 steps, −400 s, the first HR sample starting at cursor 290.
        assertEquals(95, h.summary.avgHR)
        assertEquals(0.0, h.summary.estimatedActiveKcal)
        assertEquals(0.0, h.summary.zoneBreakdown.totalZoneSeconds)
        assertEquals(15L, h.summary.steps)
        assertEquals(-400.0, h.summary.durationSeconds)
        assertEquals(at(290), h.heartRateSamples.first().start)
    }
}
