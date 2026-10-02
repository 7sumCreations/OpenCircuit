package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Kotlin-only hostile-input checks for nap detection: an empty day, a single record, reversed,
 * duplicated and far-future records, a main night that touches, overlaps, contains or reverses the
 * nap, cold and NaN skin temperatures, HR bytes 0 and 255, the 15-minute floor, the sleep-vitals
 * share, both 2026 DST days and the zone the day is judged in. Kept out of the upstream-port class
 * so its count stays exact.
 *
 * Every expected value was measured on upstream's pinned Swift build with the same records (the
 * day anchored at 2026-06-15 14:00 New York unless stated; the device zone set to the zone named).
 */
class NapDetectionHazardTest {

    private val newYork: ZoneId = ZoneId.of("America/New_York")
    private val anchor = 1_781_546_400L // 2026-06-15 14:00 New York

    private fun rec(counter: Long, motion: Int, tag: Int = 0, hr: Int = 0): BulkRecord {
        val b = ByteArray(23)
        b[0] = (counter shr 24).toByte(); b[1] = (counter shr 16).toByte()
        b[2] = (counter shr 8).toByte(); b[3] = counter.toByte()
        for (k in 0 until 5) b[10 + k] = motion.toByte()
        b[8] = tag.toByte(); b[4] = hr.toByte()
        return assertNotNull(BulkRecord.of(b))
    }

    /** Active (motion 20) → still (motion 1, [tag]) → active, 150 s apart from counter 0. */
    private fun day(active: Int = 8, still: Int = 24, tag: Int = 0, hr: Int = 0): List<BulkRecord> {
        val recs = mutableListOf<BulkRecord>()
        var c = 0L
        repeat(active) { recs += rec(c, motion = 20, hr = hr); c += 150 }
        repeat(still) { recs += rec(c, motion = 1, tag = tag, hr = hr); c += 150 }
        repeat(active) { recs += rec(c, motion = 20, hr = hr); c += 150 }
        return recs
    }

    private fun t(s: Long): Instant = Instant.ofEpochSecond(s)

    /** `start..end asleep <seconds> [stage:start-end, …]` of each nap, as the measurement printed it. */
    private fun describe(naps: List<NapDetection.Nap>): List<String> = naps.map { n ->
        "${n.start.epochSecond}..${n.end.epochSecond} long=${n.isLongNap} asleep=${n.asleep.seconds} " +
            n.segments.joinToString(",") { "${it.stage.rawValue}:${it.start.epochSecond}-${it.end.epochSecond}" }
    }

    private val referenceNap = "1781547780..1781550990 long=false asleep=3090 inBed:1781547780-1781550990,asleepCore:1781547900-1781550990"

    private fun naps(records: List<BulkRecord>, main: ActivityPeriod? = null, temps: List<TemperatureSample> = emptyList(), epoch: Long = anchor, zone: ZoneId = newYork) =
        describe(NapDetection.naps(records, mainSleep = main, zone = zone, temperatures = temps, epoch = epoch))

    @Test
    fun referenceDayHasExactlyUpstreamsNap() {
        assertEquals(listOf(referenceNap), naps(day()))
    }

    @Test
    fun emptyAndSingleRecordDaysHaveNoNaps() {
        assertEquals(emptyList(), naps(emptyList()))
        assertEquals(emptyList(), naps(listOf(rec(0, motion = 1))))
    }

    @Test
    fun reversedAndFarFutureRecordsFindTheSameNap() {
        assertEquals(listOf(referenceNap), naps(day().reversed()), "the timeline is time-sorted before detection")
        assertEquals(listOf(referenceNap), naps(day() + rec(0xFFFF_FFFFL, motion = 1)), "a far-future counter never joins the nap")
    }

    @Test
    fun duplicatedRecordsCountOnceInANap() {
        // Upstream's detector counts samples, so every record twice widens the nap by 90 s at each end
        // (measured: 1781547690..1781551080 long=false asleep=3330). Here each counter is read once,
        // its first copy (PORTING D-70, as for the night), so a doubled day finds the single day's nap.
        val doubled = day().flatMap { listOf(it, it) }.toMutableList()
        val before = doubled.toList()
        assertEquals(listOf(referenceNap), naps(doubled), "every record twice")
        assertEquals(listOf(referenceNap), naps(day() + day().reversed()), "every record twice, the copies reversed")
        assertEquals(before, doubled, "the caller's list is left alone")
    }

    @Test
    fun mainNightOverlapBoundaries() {
        val start = t(1_781_547_780)
        val end = t(1_781_550_990)
        fun main(a: Instant, b: Instant, activity: Activity = Activity.SLEEP) = ActivityPeriod(activity, a, b)
        assertEquals(listOf(referenceNap), naps(day(), main(start.minusSeconds(3600), start)), "a main night ending exactly at the nap's start does not overlap")
        assertEquals(listOf(referenceNap), naps(day(), main(end, end.plusSeconds(3600))), "nor one starting exactly at its end")
        assertEquals(emptyList(), naps(day(), main(start.minusSeconds(3600), start.plusSeconds(1))), "one second of overlap drops the nap")
        assertEquals(emptyList(), naps(day(), main(start.plusSeconds(600), start.plusSeconds(900))), "a main block inside the nap drops it")
        assertEquals(listOf(referenceNap), naps(day(), main(end, start)), "a reversed main block overlaps nothing")
        assertEquals(emptyList(), naps(day(), main(start, end, Activity.ACTIVE)), "the main block's activity is not consulted")
    }

    @Test
    fun coldTemperaturesDropTheNapAndNanTemperaturesKeepIt() {
        fun temps(c: Double) = day().map { TemperatureSample(it.date(anchor), c) }
        assertEquals(emptyList(), naps(day(), temps = temps(22.0)), "an off-wrist block is not a nap")
        assertEquals(listOf(referenceNap), naps(day(), temps = temps(Double.NaN)), "a NaN median is not below the wear threshold")
    }

    @Test
    fun heartRateBytes0And255LeaveTheNapUnchanged() {
        assertEquals(listOf(referenceNap), naps(day(hr = 255)))
        assertEquals(listOf(referenceNap), naps(day(hr = 60)))
    }

    @Test
    fun theFifteenMinuteFloorIsExact() {
        assertEquals(emptyList(), naps(day(still = 8)), "8 still epochs: the block is 810 s")
        assertEquals(
            listOf("1781547780..1781548740 long=false asleep=960 inBed:1781547780-1781548740,asleepCore:1781547780-1781548740"),
            naps(day(still = 9)),
            "9 still epochs: 960 s, too short to stage → the whole window is asleep",
        )
        val long = NapDetection.naps(day(still = 72), mainSleep = null, zone = newYork, epoch = anchor).single()
        assertEquals(Duration.ofSeconds(10_410), long.duration)
        assertFalse(long.isLongNap, "10 410 s is under the 3 h long-nap mark")
        val exactly = NapDetection.Nap(t(0), t(3 * 3600), emptyList())
        assertTrue(exactly.isLongNap, "the long-nap mark is inclusive")
        assertFalse(NapDetection.Nap(t(0), t(3 * 3600 - 1), emptyList()).isLongNap)
    }

    @Test
    fun sleepVitalsShareGate() {
        fun mixed(sleepEvery3rdOnly: Boolean): List<BulkRecord> {
            val recs = mutableListOf<BulkRecord>()
            var c = 0L
            repeat(8) { recs += rec(c, motion = 20); c += 150 }
            for (i in 0 until 24) {
                val activity = if (sleepEvery3rdOnly) i % 3 != 0 else i % 3 == 0
                recs += rec(c, motion = 1, tag = if (activity) 0x12 else 0); c += 150
            }
            repeat(8) { recs += rec(c, motion = 20); c += 150 }
            return recs
        }
        assertEquals(listOf(referenceNap), naps(mixed(sleepEvery3rdOnly = false)), "two thirds sleep-vitals ≥ 0.35 → a nap")
        assertEquals(emptyList(), naps(mixed(sleepEvery3rdOnly = true)), "one third (0.33) < 0.35 → not a nap")
    }

    @Test
    fun dstDaysAndTheEveningAreJudgedInTheNamedZone() {
        assertEquals(
            listOf("1772994180..1772997390 long=false asleep=3090 inBed:1772994180-1772997390,asleepCore:1772994300-1772997390"),
            naps(day(), epoch = 1_772_992_800L), // 2026-03-08 14:00 New York (spring forward)
        )
        assertEquals(
            listOf("1793560980..1793564190 long=false asleep=3090 inBed:1793560980-1793564190,asleepCore:1793561100-1793564190"),
            naps(day(), epoch = 1_793_559_600L), // 2026-11-01 14:00 New York (fall back)
        )
        assertEquals(
            listOf("1781567580..1781570790 long=false asleep=3090 inBed:1781567580-1781570790,asleepCore:1781567700-1781570790"),
            naps(day(), epoch = anchor + 6 * 3600 - 1800), // 19:30 local: midpoint before 21:00 → still a nap
        )
    }

    @Test
    fun theSameDayIsANapOrANightDependingOnTheZone() {
        // 18:00 UTC is 14:00 in New York and 19:00 in London (daytime), 03:00 in Tokyo, 23:30 in Kolkata (night).
        assertEquals(listOf(referenceNap), naps(day(), zone = ZoneId.of("Europe/London")))
        assertEquals(emptyList(), naps(day(), zone = ZoneId.of("Asia/Tokyo")))
        assertEquals(emptyList(), naps(day(), zone = ZoneId.of("Asia/Kolkata")))
    }
}
