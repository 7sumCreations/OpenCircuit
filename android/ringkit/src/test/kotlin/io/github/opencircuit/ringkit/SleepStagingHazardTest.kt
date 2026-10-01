package io.github.opencircuit.ringkit

import org.junit.jupiter.api.Timeout
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kotlin-only hostile-input checks for sleep staging (`SleepStaging.classify`, its summary helpers,
 * `PersonalBaseline.fromRecentDeepHR`, the directly driven offset and cadence passes, and
 * `BulkSleep.stagedSegments`): empty, single-record and single-epoch nights; heart-rate bytes 0 and
 * 255; HRV and respiratory bytes 255; reversed, duplicated and far-future counters; a week-long
 * night (time-bounded); non-finite personal baselines; segment lists that run backwards; NaN and
 * infinite smoothed heart rates in the offset scan and the cadence confirmation; tuning values
 * upstream cannot run with; and the machine zone that upstream's own selection-based vectors depend
 * on. Kept out of the upstream-port classes so their counts stay exact.
 *
 * Every expected value here was measured on upstream's pinned Swift build with the same records,
 * so each test pins upstream's outcome — except where upstream traps (crashes); those inputs are
 * bounded or rejected here, and the test says so.
 */
class SleepStagingHazardTest {

    private val step = 150L
    private val base = 0x0c220000L // 2026-06-13, the counter upstream's staging tests start from

    private fun counterBytes(b: ByteArray, counter: Long) {
        b[0] = (counter shr 24).toByte(); b[1] = (counter shr 16).toByte()
        b[2] = (counter shr 8).toByte(); b[3] = counter.toByte()
    }

    /** A sleep-vitals epoch: HR `[4]`, HRV `[5]`, RR×8 `[7]`, SpO2 98 `[8]`, a uniform motion byte. */
    private fun vrec(counter: Long, hr: Int, hrv: Int = 0, rrByte: Int = 0, motion: Int = 1): BulkRecord {
        val b = ByteArray(BulkRecord.LENGTH)
        counterBytes(b, counter)
        b[4] = hr.toByte(); b[5] = hrv.toByte(); b[7] = rrByte.toByte(); b[8] = 0x62
        for (k in 10 until 15) b[k] = motion.toByte()
        return BulkRecord.of(b)!!
    }

    /** An activity epoch (`[8]` = 0x12), constant high motion, no vitals. */
    private fun arec(counter: Long, motion: Int = 0x14): BulkRecord {
        val b = ByteArray(BulkRecord.LENGTH)
        counterBytes(b, counter)
        b[8] = 0x12
        for (k in 10 until 15) b[k] = motion.toByte()
        return BulkRecord.of(b)!!
    }

    /** 12 activity epochs, [core] sleep-vitals epochs built by index, 12 activity epochs. */
    private fun night(
        core: Int = 120,
        hrv: (Int) -> Int = { 0 },
        rrByte: (Int) -> Int = { 0 },
        hr: (Int) -> Int,
    ): List<BulkRecord> {
        val out = mutableListOf<BulkRecord>()
        var c = base
        repeat(12) { out += arec(c); c += step }
        for (k in 0 until core) { out += vrec(c, hr(k), hrv(k), rrByte(k)); c += step }
        repeat(12) { out += arec(c); c += step }
        return out
    }

    /** Segments as upstream's probe printed them: `stage start end`, epoch seconds. */
    private fun shape(segs: List<SleepSegment>): List<String> =
        segs.map { "${it.stage.rawValue} ${it.start.epochSecond} ${it.end.epochSecond}" }

    private val refNight = listOf(
        "inBed 1781350396 1781368006", "awake 1781350396 1781350516", "asleepDeep 1781350516 1781368006",
    )
    private val cycNight = listOf(
        "inBed 1781350396 1781368606", "awake 1781350396 1781350516", "asleepDeep 1781350516 1781368606",
    )
    private fun cyc() = night(hrv = { if (it % 2 == 0) 60 else 40 }) { if (it % 3 == 0) 52 else 58 }

    // Archive shape

    @Test
    fun emptySingleRecordAndTwoRecordInputsYieldNoNight() {
        assertEquals(emptyList(), SleepStaging.classify(emptyList()))
        assertEquals(emptyList(), SleepStaging.classify(listOf(vrec(base, 50))))
        assertEquals(emptyList(), SleepStaging.classify(listOf(vrec(base, 50), vrec(base + step, 50))))
        assertEquals(emptyList(), BulkSleep.stagedSegments(emptyList()))
    }

    @Test
    fun aSingleSleepVitalsEpochAmongConstantMotionStagesAsUpstream() {
        // A constant activity byte de-floors to "still" at any level, so the detector finds a block;
        // the lone HR reading is forward-filled over the epochs after it (measured upstream).
        val recs = mutableListOf<BulkRecord>()
        var c = base
        repeat(30) { recs += arec(c); c += step }
        recs += vrec(c, 50); c += step
        repeat(30) { recs += arec(c); c += step }
        assertEquals(
            listOf("inBed 1781348416 1781357536", "awake 1781348416 1781352916", "asleepDeep 1781352916 1781357536"),
            shape(SleepStaging.classify(recs)),
        )
    }

    // Vitals bytes

    @Test
    fun heartRateBytesZeroAnd255ReadAsMissingNotAsHeartRates() {
        assertEquals(refNight, shape(SleepStaging.classify(night { 50 })), "reference night")
        assertEquals(emptyList(), SleepStaging.classify(night { 0 }), "every HR byte 0: no rows, no night")
        assertEquals(emptyList(), SleepStaging.classify(night { 255 }), "every HR byte 255: no rows, no night")
        assertEquals(
            refNight, shape(SleepStaging.classify(night { if (it % 2 == 0) 50 else 255 })),
            "a 255 between readings is forward-filled over, exactly like a missing reading",
        )
        assertEquals(emptyList(), SleepStaging.classify(night { if (it == 0) 50 else 0 }), "one HR at onset only")
        assertEquals(emptyList(), SleepStaging.classify(night { if (it == 119) 50 else 0 }), "one HR at the end only")
    }

    @Test
    fun hrvAndRespiratoryBytes255StageAsUpstream() {
        val vit255 = night(hrv = { 255 }, rrByte = { 255 }) { if (it % 3 == 0) 52 else 58 }
        val expected = listOf(
            "inBed 1781350396 1781368606", "awake 1781350396 1781350516",
            "asleepCore 1781350516 1781367616", "asleepDeep 1781367616 1781368606",
        )
        assertEquals(expected, shape(SleepStaging.classify(vit255)))
        assertEquals(expected, shape(SleepStaging.classify(vit255, tuning = SleepStaging.Tuning(rrVarWeight = 1.0))))
        // The same HR with no HRV and no RR: the sleep-vitals flag never fires, so the block ends earlier.
        val vit0 = night { if (it % 3 == 0) 52 else 58 }
        assertEquals(
            listOf(
                "inBed 1781350396 1781368006", "awake 1781350396 1781350516",
                "asleepCore 1781350516 1781367616", "asleepDeep 1781367616 1781368006",
            ),
            shape(SleepStaging.classify(vit0)),
        )
    }

    // Counter order

    @Test
    fun reversedDuplicatedAndFarFutureCountersStageAsUpstream() {
        val recs = cyc()
        assertEquals(cycNight, shape(SleepStaging.classify(recs)))
        assertEquals(cycNight, shape(SleepStaging.classify(recs.reversed())), "reversed archive: same night")
        assertEquals(
            listOf(
                "inBed 1781348416 1781369986", "awake 1781348416 1781350216", "asleepCore 1781350216 1781350366",
                "asleepDeep 1781350366 1781368216", "awake 1781368216 1781368666", "asleepDeep 1781368666 1781369986",
            ),
            shape(SleepStaging.classify(recs.flatMap { listOf(it, it) })),
            "every record twice: upstream stages the doubled timeline as it is (deduplication is the archive's job)",
        )
        val far = recs + vrec(0xFFFF_FFFFL, 50)
        assertEquals(cycNight, shape(SleepStaging.classify(far)), "a far-future record is its own fragment and stages nothing")
        assertEquals(cycNight, shape(BulkSleep.stagedSegments(far)))
    }

    // Device zone

    @Test
    fun selectedThenStagedNightDependsOnTheNamedZoneExactlyAsUpstream() {
        // Upstream's restless-morning fixture (6 h still, then 100 min of alternating still
        // sleep-vitals and moving activity epochs) runs 14:46–22:26 UTC. Selection judges "overnight"
        // in the device zone, so the same records stage differently by zone — measured on the pinned
        // build with the process zone set: GMT keeps only the 51-record tail and stages nothing; New
        // York keeps all 184 and stages 459 asleep minutes.
        val recs = mutableListOf<BulkRecord>()
        var c = 10_000L
        repeat(144) { recs += vrec(c, 55, hrv = 60); c += step }
        for (i in 0 until 40) {
            recs += if (i % 2 == 0) vrec(c, 55, hrv = 60, motion = 2) else arec(c, motion = 60)
            c += step
        }
        fun asleepMinutes(zone: java.time.ZoneId): Pair<Int, Double> {
            val scoped = BulkSleep.latestNightRecords(recs, zone)
            val asleep = SleepStaging.totalAsleep(SleepStaging.classify(scoped))
            return scoped.size to asleep.seconds / 60.0
        }
        assertEquals(51 to 0.0, asleepMinutes(java.time.ZoneOffset.UTC))
        assertEquals(51 to 0.0, asleepMinutes(java.time.ZoneId.of("Europe/Paris")))
        assertEquals(184 to 459.0, asleepMinutes(java.time.ZoneId.of("America/New_York")))
        assertEquals(184 to 459.0, asleepMinutes(java.time.ZoneId.of("Asia/Tokyo")))

        // Upstream's mid-night restless fixture (3 h still, 40 min restless, 3 h still): the morning
        // softening leaves it alone in New York (379.5 min either way) but not in Los Angeles, where
        // selection scopes it differently (211.5 with the softening, 182.5 without) — measured.
        val mid = mutableListOf<BulkRecord>()
        c = 10_000L
        repeat(72) { mid += vrec(c, 55, hrv = 60); c += step }
        for (i in 0 until 16) {
            mid += if (i % 2 == 0) vrec(c, 55, hrv = 60, motion = 2) else arec(c, motion = 60)
            c += step
        }
        repeat(72) { mid += vrec(c, 55, hrv = 60); c += step }
        fun midAsleep(zone: java.time.ZoneId, halfWindow: Int): Double = SleepStaging.totalAsleep(
            SleepStaging.classify(
                BulkSleep.latestNightRecords(mid, zone),
                tuning = SleepStaging.Tuning(motionAwakeVitalsHalfWindow = halfWindow),
            ),
        ).seconds / 60.0
        val newYork = java.time.ZoneId.of("America/New_York")
        val losAngeles = java.time.ZoneId.of("America/Los_Angeles")
        assertEquals(listOf(379.5, 379.5), listOf(midAsleep(newYork, 3), midAsleep(newYork, 0)))
        assertEquals(listOf(211.5, 182.5), listOf(midAsleep(losAngeles, 3), midAsleep(losAngeles, 0)))
    }

    // Night length

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    fun weekLongNightFinishesAndStagesAsUpstream() {
        val recs = mutableListOf<BulkRecord>()
        var c = base
        repeat(12) { recs += arec(c); c += step }
        for (k in 0 until 7 * 576) { recs += vrec(c, if (k % 3 == 0) 52 else 58, hrv = 55); c += step }
        repeat(12) { recs += arec(c); c += step }
        val segs = SleepStaging.classify(recs)
        assertEquals(4, segs.size)
        assertEquals("inBed 1781350396 1781955406", shape(segs).first())
        val s = SleepStaging.summary(segs)
        assertEquals(
            listOf(605_010L, 120L, 603_900L, 990L, 0L),
            listOf(s.inBed, s.awake, s.light, s.deep, s.rem).map { it.seconds },
        )
        assertEquals(SleepStaging.Minutes(10084, 2, 10065, 17, 0, 10082), s.minutes)
        assertEquals(
            SleepStaging.SleepInterval(Instant.ofEpochSecond(1781350516), Instant.ofEpochSecond(1781955406)),
            SleepStaging.sleepWindow(segs),
        )
    }

    // Personal baseline

    @Test
    fun nonFiniteBaselinesNeverAdmitAnUnsupportedDeep() {
        val elevated = night(hrv = { 55 }) { 70 }
        val deep = listOf("inBed 1781350396 1781368606", "awake 1781350396 1781350516", "asleepDeep 1781350516 1781368606")
        val core = listOf("inBed 1781350396 1781368606", "awake 1781350396 1781350516", "asleepCore 1781350516 1781368606")
        assertEquals(deep, shape(SleepStaging.classify(elevated)), "no baseline")
        assertEquals(core, shape(SleepStaging.classify(elevated, baseline = SleepStaging.PersonalBaseline(Double.NaN))))
        assertEquals(deep, shape(SleepStaging.classify(elevated, baseline = SleepStaging.PersonalBaseline(Double.POSITIVE_INFINITY))))
        assertEquals(core, shape(SleepStaging.classify(elevated, baseline = SleepStaging.PersonalBaseline(Double.NEGATIVE_INFINITY))))
    }

    @Test
    fun baselineFactoryEdges() {
        assertEquals(60.0, SleepStaging.PersonalBaseline.fromRecentDeepHR(listOf(-5, -1, 0, 60), minNights = 1)?.deepSleepHR)
        assertEquals(5.0, SleepStaging.PersonalBaseline.fromRecentDeepHR(listOf(Int.MAX_VALUE, 3, 5))?.deepSleepHR)
        // The two central values are converted before they are added, so the sum cannot wrap.
        assertEquals(
            2_147_483_646.5,
            SleepStaging.PersonalBaseline.fromRecentDeepHR(listOf(Int.MAX_VALUE, Int.MAX_VALUE - 1), minNights = 2)?.deepSleepHR,
        )
        // Upstream traps ("Index out of range") when no valid night remains and minNights <= 0;
        // bounded here: no baseline.
        assertNull(SleepStaging.PersonalBaseline.fromRecentDeepHR(emptyList(), minNights = 0))
        assertNull(SleepStaging.PersonalBaseline.fromRecentDeepHR(listOf(0, -3), minNights = -1))
    }

    // Summary helpers

    @Test
    fun summaryHelpersOnHostileSegmentListsMatchUpstream() {
        val t = Instant.ofEpochSecond(1_781_000_000)
        fun seg(from: Long, to: Long, stage: SleepStage) = SleepSegment(t.plusSeconds(from), t.plusSeconds(to), stage)
        fun secs(s: SleepStaging.Summary) = listOf(s.inBed, s.awake, s.light, s.deep, s.rem).map { it.seconds }

        val empty = SleepStaging.summary(emptyList())
        assertEquals(listOf(0L, 0L, 0L, 0L, 0L), secs(empty))
        assertEquals(0.0, empty.efficiency)
        assertNull(SleepStaging.sleepWindow(emptyList()))
        assertTrue(SleepStaging.stageTotals(emptyList()).isEmpty())

        val onlyInBed = SleepStaging.summary(listOf(seg(0, 3600, SleepStage.IN_BED)))
        assertEquals(listOf(3600L, 0L, 0L, 0L, 0L), secs(onlyInBed))
        assertEquals(SleepStaging.Minutes(60, 0, 0, 0, 0, 0), onlyInBed.minutes)

        // Segments whose end is before their start: negative durations sum as they are; a negative
        // in-bed total falls back to the staged sum. Minutes round half AWAY from zero, as Swift's
        // rounded(): -150 s is -2.5 min -> -3, never -2.
        val reversed = listOf(seg(0, -3600, SleepStage.IN_BED), seg(0, -150, SleepStage.AWAKE), seg(0, -90, SleepStage.ASLEEP_CORE))
        val r = SleepStaging.summary(reversed)
        assertEquals(listOf(-240L, -150L, -90L, 0L, 0L), secs(r))
        assertEquals(0.0, r.efficiency)
        assertEquals(SleepStaging.Minutes(-4, -3, -2, 0, 0, -2), r.minutes)
        assertEquals(-90L, SleepStaging.totalAsleep(reversed).seconds)
        assertEquals(SleepStaging.SleepInterval(t, t.plusSeconds(-90)), SleepStaging.sleepWindow(reversed))
        assertEquals(
            mapOf(SleepStage.AWAKE to Duration.ofSeconds(-150), SleepStage.ASLEEP_CORE to Duration.ofSeconds(-90)),
            SleepStaging.stageTotals(reversed),
        )

        val half = SleepStaging.summary(listOf(seg(0, 150, SleepStage.AWAKE), seg(0, -150, SleepStage.ASLEEP_DEEP), seg(0, 450, SleepStage.ASLEEP_REM)))
        assertEquals(listOf(450L, 150L, 0L, -150L, 450L), secs(half))
        assertEquals(0.6666666666666666, half.efficiency)
        assertEquals(SleepStaging.Minutes(8, 3, 0, -3, 8, 5), half.minutes)

        val noInBed = SleepStaging.summary(listOf(seg(0, 1500, SleepStage.ASLEEP_CORE), seg(1500, 1800, SleepStage.AWAKE)))
        assertEquals(listOf(1800L, 300L, 1500L, 0L, 0L), secs(noInBed))
        assertEquals(0.8333333333333334, noInBed.efficiency)
        assertEquals(SleepStaging.Minutes(30, 5, 25, 0, 0, 25), noInBed.minutes)
    }

    // Directly driven passes

    @Test
    fun pointOfNoReturnScanTreatsNonFiniteHeartRatesAsSwiftsMinDoes() {
        // Swift's min(x, y) is `y < x ? y : x`: a NaN on either side never wins, so a NaN epoch inside
        // or at the end of the risen tail does not stop the suffix scan. Kotlin's minOf would
        // propagate NaN and cut nothing (or less). Measured upstream: all three cut epochs 40..49.
        fun cut(smHR: List<Double>): List<Int> {
            val awake = BooleanArray(50)
            SleepStaging.markPointOfNoReturnOffset(awake, smHR, floor = 50.0, margin = 3.0, tuning = SleepStaging.Tuning.DEFAULT)
            return awake.indices.filter { awake[it] }
        }
        val tail = (40..49).toList()
        assertEquals(tail, cut(List(40) { 50.0 } + Double.NaN + List(9) { 80.0 }), "NaN at the tail's first epoch")
        assertEquals(tail, cut(List(40) { 50.0 } + List(9) { 80.0 } + Double.NaN), "NaN at the last epoch")
        assertEquals(tail, cut(List(40) { 50.0 } + List(10) { Double.POSITIVE_INFINITY }), "+Inf tail")
    }

    @Test
    fun cadenceWakeConfirmationTreatsNonFiniteHeartRatesAsSwiftsMinDoes() {
        // The HR no-return confirmation reads Swift's `Sequence.min()` of the suffix: it starts from
        // the FIRST element and only a later value that compares smaller replaces it. So a NaN first
        // stays NaN (never above the bar → decline) while a NaN later is skipped (cut), +Inf first
        // is replaced by the real minimum, and -Inf anywhere wins. Kotlin's minOf would propagate
        // the later NaN and decline. Measured upstream (quiet run 0..99, rise 62 bpm over a 50 + 4
        // bar): NaN first → none; NaN at 110 → 100..119; +Inf first → 100..119; -Inf at 110 → none;
        // all NaN → none.
        val cadence = MutableList(120) { SleepStaging.CadenceStep.VIOLATION }
        cadence[0] = SleepStaging.CadenceStep.UNKNOWN
        for (i in 1..99) cadence[i] = SleepStaging.CadenceStep.ALTERNATING
        fun cut(patch: Map<Int, Double>): List<Int> {
            val hr = MutableList(120) { if (it >= 100) 62.0 else 52.0 }
            for ((k, v) in patch) hr[k] = v
            val awake = BooleanArray(120)
            SleepStaging.markCadenceWakeOffset(awake, cadence, hr, floor = 50.0, margin = 4.0, tuning = SleepStaging.Tuning.DEFAULT)
            return awake.indices.filter { awake[it] }
        }
        val tail = (100 until 120).toList()
        assertEquals(emptyList(), cut(mapOf(100 to Double.NaN)), "NaN first in the suffix")
        assertEquals(tail, cut(mapOf(110 to Double.NaN)), "NaN later in the suffix")
        assertEquals(tail, cut(mapOf(100 to Double.POSITIVE_INFINITY)), "+Inf first in the suffix")
        assertEquals(emptyList(), cut(mapOf(110 to Double.NEGATIVE_INFINITY)), "-Inf later in the suffix")
        assertEquals(emptyList(), cut((100 until 120).associateWith { Double.NaN }), "an all-NaN suffix")
    }

    @Test
    fun wanderingPedestalNightSelectsAndStagesByTheNamedZoneExactlyAsUpstream() {
        // Upstream's FR04RaisedPrimaryFloorTests :314 vector goes through latestNightRecords(from:),
        // which reads the machine's zone. Measured on the pinned build (magnitude channel on): New
        // York, Los Angeles and UTC select 201 records (counters 207668748..207698748) and stage 21
        // segments; Tokyo and Kolkata select 354 records (207618048..207670998), reaching back into
        // the awake day, and stage nothing — so upstream's own test fails there. The port names
        // its zone and reproduces each outcome.
        val on = BulkSleep.MotionChannelPolicy(magnitudeChannelEnabled = true)
        fun outcome(zone: String): Triple<Int, String, Int> {
            val recs = FR04RaisedPrimaryFloorTest().wanderingPedestalNight(includeDay = true) // fresh noise seed each time
            val sel = BulkSleep.latestNightRecords(recs, java.time.ZoneId.of(zone), motionPolicy = on)
            return Triple(sel.size, "${sel.first().counter}..${sel.last().counter}", BulkSleep.stagedSegments(sel, motionPolicy = on).size)
        }
        for (zone in listOf("America/New_York", "America/Los_Angeles", "UTC")) {
            assertEquals(Triple(201, "207668748..207698748", 21), outcome(zone), zone)
        }
        for (zone in listOf("Asia/Tokyo", "Asia/Kolkata")) {
            assertEquals(Triple(354, "207618048..207670998", 0), outcome(zone), zone)
        }
    }

    @Test
    fun cadenceStepsOnDuplicateReversedAndMismatchedInputs() {
        val t = Instant.ofEpochSecond(1_781_000_000)
        val times = listOf(0L, 0L, 150L, 100L, 400L, 100_000L).map { t.plusSeconds(it) }
        val layouts = listOf(
            BulkRecord.Layout.SLEEP_VITALS, BulkRecord.Layout.SLEEP_VITALS, BulkRecord.Layout.ACTIVITY,
            BulkRecord.Layout.ACTIVITY, BulkRecord.Layout.SLEEP_VITALS, BulkRecord.Layout.ACTIVITY,
        )
        // A zero or backwards step counts as one epoch (max(1, round(dt / 150))), as upstream.
        assertEquals(
            listOf(
                SleepStaging.CadenceStep.UNKNOWN, SleepStaging.CadenceStep.VIOLATION, SleepStaging.CadenceStep.ALTERNATING,
                SleepStaging.CadenceStep.VIOLATION, SleepStaging.CadenceStep.VIOLATION, SleepStaging.CadenceStep.UNKNOWN,
            ),
            SleepStaging.cadenceSteps(times, layouts),
        )
        assertEquals(emptyList(), SleepStaging.cadenceSteps(times, layouts.dropLast(1)))
    }

    // Tuning upstream cannot run with

    @Test
    fun tuningThatUpstreamTrapsOnIsRejectedOrBounded() {
        // Upstream converts `q × (n − 1)` to Int and traps on a non-finite percentile; a negative
        // half window builds a reversed range and traps. Rejected at construction here.
        assertFailsWith<IllegalArgumentException> { SleepStaging.Tuning(deepHRPercentile = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { SleepStaging.Tuning(remHRPercentile = Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { SleepStaging.Tuning.DEFAULT.copy(sleepFloorPercentile = Double.NEGATIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { SleepStaging.Tuning(variabilityHalfWindow = -1) }
        assertFailsWith<IllegalArgumentException> { SleepStaging.Tuning(hrWakeHalfWindow = -1) }
        // An Int.MAX_VALUE half window overflows `i + half` and traps upstream; bounded here to a
        // whole-night window, which stages exactly as upstream does with a 10 000-epoch one. (This
        // night carries no HRV, so the vitals window has nothing to find at any width.)
        val recs = night { if (it % 3 == 0) 52 else 58 }
        val wide = listOf("inBed 1781350396 1781368006", "awake 1781350396 1781350516", "asleepDeep 1781350516 1781368006")
        assertEquals(wide, shape(SleepStaging.classify(recs, tuning = SleepStaging.Tuning(variabilityHalfWindow = 10_000, hrWakeHalfWindow = 10_000))))
        assertEquals(
            wide,
            shape(
                SleepStaging.classify(
                    recs,
                    tuning = SleepStaging.Tuning(
                        variabilityHalfWindow = Int.MAX_VALUE, hrWakeHalfWindow = Int.MAX_VALUE, motionAwakeVitalsHalfWindow = Int.MAX_VALUE,
                    ),
                ),
            ),
        )
    }
}
