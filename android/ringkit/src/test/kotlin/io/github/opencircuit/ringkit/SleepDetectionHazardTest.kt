package io.github.opencircuit.ringkit

import org.junit.jupiter.api.Timeout
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kotlin-only hostile-input checks for the sleep detector and the main-sleep path: what the
 * upstream vectors never feed in (non-finite motion, unsorted or duplicate times, gates fed
 * extreme readings, exact threshold boundaries, empty and week-long record runs, a far-future
 * counter). Kept out of the upstream-port classes so their counts stay exact.
 *
 * Where upstream's behaviour was measured on the pinned Swift build, the test pins the same
 * outcome; where Kotlin deliberately differs, the test says so and `PORTING.md` records why.
 */
class SleepDetectionHazardTest {

    private val base: Instant = Instant.ofEpochSecond(1_700_000_000)
    private fun at(minutes: Int): Instant = base.plusSeconds(minutes * 60L)
    private val stillMinutes = (0 until 300 step 5).toList()

    private fun motion(minutes: List<Int>, value: (Int) -> Float): List<MotionSample> =
        minutes.mapIndexed { i, m -> MotionSample(at(m), value(i)) }

    private fun sleeps(periods: List<ActivityPeriod>) = periods.filter { it.activity == Activity.SLEEP }

    // detectFromMotion — input shape

    @Test
    fun emptyAndSingleSampleMotionYieldNoPeriods() {
        assertEquals(emptyList(), ActivityPeriod.detectFromMotion(emptyList()))
        assertEquals(emptyList(), ActivityPeriod.detectFromMotion(listOf(MotionSample(base, 1f))))
        assertEquals(emptyList(), ActivityPeriod.detectFromMotion(emptyList(), temperatureSamples = emptyList()))
    }

    @Test
    fun nonFiniteLowMotionNeverManufacturesSleep() {
        // Upstream reads NaN and -Inf motion as STILL (Swift's max(0, NaN) is 0), so a timeline of
        // garbage readings becomes a 5-hour sleep block. Here they read as movement.
        assertTrue(sleeps(ActivityPeriod.detectFromMotion(motion(stillMinutes) { Float.NaN })).isEmpty(), "all-NaN motion is not sleep")
        assertTrue(
            sleeps(ActivityPeriod.detectFromMotion(motion(stillMinutes) { Float.NEGATIVE_INFINITY })).isEmpty(),
            "all -Inf motion is not sleep",
        )
    }

    @Test
    fun unwornSentinelsAndPositiveInfinityReadAsActive() {
        for (v in listOf(Float.MAX_VALUE, Float.POSITIVE_INFINITY)) {
            val p = ActivityPeriod.detectFromMotion(motion(stillMinutes) { v })
            assertEquals(listOf(ActivityPeriod(Activity.ACTIVE, at(0), at(295))), p, "constant $v reads as one active run")
        }
    }

    @Test
    fun unsortedAndDuplicateTimesDetectAsTheSortedTimeline() {
        val sorted = motion(stillMinutes) { 1f }
        val expected = listOf(ActivityPeriod(Activity.SLEEP, at(0), at(295)))
        assertEquals(expected, ActivityPeriod.detectFromMotion(sorted))
        assertEquals(expected, ActivityPeriod.detectFromMotion(sorted.reversed()), "reversed input is sorted first")
        assertEquals(expected, ActivityPeriod.detectFromMotion(sorted.flatMap { listOf(it, it) }), "duplicate times")
    }

    // detectFromMotion — gates fed extreme readings

    @Test
    fun heartRateGateThresholdDoesNotWrapAtIntMax() {
        // floor + margin overflows a 32-bit Int here (upstream's 64-bit Int would trap). The
        // threshold is computed without wrapping, so a block at that HR is NOT above it.
        val hr = stillMinutes.map { HeartRateSample(at(it), Int.MAX_VALUE) }
        val gated = ActivityPeriod.detectFromMotion(motion(stillMinutes) { 1f }, temperatureSamples = emptyList(), heartRateSamples = hr)
        assertEquals(listOf(ActivityPeriod(Activity.SLEEP, at(0), at(295))), gated)
    }

    @Test
    fun sleepVitalsRescueThresholdDoesNotWrapAtIntMax() {
        // Still to 180, restless after: the rescue must extend through the restless tail, because
        // every HR equals the floor. A wrapped threshold would reject every window and stop at 180.
        val m = motion((0 until 180 step 5).toList()) { 1f } +
            (180 until 300 step 5).mapIndexed { i, t -> MotionSample(at(t), if (i % 2 == 0) 2f else 260f) }
        val hr = (0 until 300 step 5).map { HeartRateSample(at(it), Int.MAX_VALUE) }
        val hrv = (0 until 290 step 5).map { at(it) }
        val rescued = ActivityPeriod.detectFromMotion(m, temperatureSamples = emptyList(), heartRateSamples = hr, sleepVitalTimes = hrv)
        val end = assertNotNull(sleeps(rescued).maxOfOrNull { it.end })
        assertTrue(Duration.between(base, end).toMinutes() > 270, "rescue extends the tail at an Int.MAX floor (end ${Duration.between(base, end)})")
    }

    @Test
    fun nanTemperaturesLeaveTheMotionVerdictUnchanged() {
        val temps = stillMinutes.map { TemperatureSample(at(it), Double.NaN) }
        assertEquals(
            listOf(ActivityPeriod(Activity.SLEEP, at(0), at(295))),
            ActivityPeriod.detectFromMotion(motion(stillMinutes) { 1f }, temperatureSamples = temps),
            "a NaN median is not below the wear threshold, as upstream",
        )
    }

    // detectFromGravity

    @Test
    fun gravityEmptySingleReversedAndNonFinite() {
        fun g(minutes: IntRange, v: Gravity?) = minutes.map { GravitySample(at(it), v) }
        assertEquals(emptyList(), ActivityPeriod.detectFromGravity(emptyList()))
        assertEquals(emptyList(), ActivityPeriod.detectFromGravity(g(0..0, Gravity(0f, 0f, 1f))))
        // Upstream does not sort gravity input; a reversed timeline yields no period at all.
        assertEquals(emptyList(), ActivityPeriod.detectFromGravity(g(0 until 120, Gravity(0f, 0f, 1f)).reversed()))
        for (v in listOf(Gravity(Float.NaN, 0f, 1f), Gravity(Float.POSITIVE_INFINITY, 0f, 1f))) {
            assertEquals(listOf(ActivityPeriod(Activity.ACTIVE, at(0), at(119))), ActivityPeriod.detectFromGravity(g(0 until 120, v)), "$v")
        }
    }

    // mainSleepBlock — exact boundaries

    @Test
    fun mainSleepBlockBoundaries() {
        val hour = Duration.ofHours(1)
        fun sleep(fromH: Long, toH: Long) = ActivityPeriod(Activity.SLEEP, base.plus(hour.multipliedBy(fromH)), base.plus(hour.multipliedBy(toH)))
        assertNull(ActivityPeriod.mainSleepBlock(emptyList()))
        assertNull(ActivityPeriod.mainSleepBlock(listOf(sleep(0, 1))), "exactly the minimum duration is not enough")
        // A gap of exactly the maximum pause is NOT bridged; of two equal clusters the first wins.
        assertEquals(sleep(0, 2), ActivityPeriod.mainSleepBlock(listOf(sleep(0, 2), sleep(3, 5))))
        assertEquals(sleep(0, 5), ActivityPeriod.mainSleepBlock(listOf(sleep(0, 2), sleep(3, 5)), maxPause = hour.plusSeconds(1)))
        // A period that ends before it starts has a negative span and never qualifies.
        assertNull(ActivityPeriod.mainSleepBlock(listOf(sleep(5, 0))))
    }

    // BulkSleep.mainSleep / sleepSegments

    private fun rec(counter: Long, motion: Int, sub: Int): BulkRecord {
        val b = ByteArray(BulkRecord.LENGTH)
        b[0] = (counter shr 24).toByte(); b[1] = (counter shr 16).toByte()
        b[2] = (counter shr 8).toByte(); b[3] = counter.toByte()
        b[8] = sub.toByte()
        for (k in 0 until 5) b[10 + k] = motion.toByte()
        return assertNotNull(BulkRecord.of(b))
    }

    private fun night(start: Long = 0x0c220000L, still: Int = 216): List<BulkRecord> {
        val out = mutableListOf<BulkRecord>()
        var c = start
        val active = intArrayOf(0x0a, 0x28, 0x50)
        for (i in 0 until 20) { out += rec(c, active[i % 3], 0x12); c += 150 }
        repeat(still) { out += rec(c, 0x01, 0x62); c += 150 }
        for (i in 0 until 20) { out += rec(c, active[i % 3], 0x12); c += 150 }
        return out
    }

    @Test
    fun emptyRecordsYieldNoNight() {
        assertNull(BulkSleep.mainSleep(emptyList()))
        assertEquals(emptyList(), BulkSleep.sleepSegments(emptyList()))
    }

    @Test
    fun unsortedRecordsGiveTheSortedNight() {
        val recs = night()
        val block = assertNotNull(BulkSleep.mainSleep(recs))
        assertEquals(1781351596L to 1781383606L, block.start.epochSecond to block.end.epochSecond, "the plain night, as upstream")
        assertEquals(block, BulkSleep.mainSleep(recs.reversed()), "reversed records")
        assertEquals(BulkSleep.sleepSegments(recs), BulkSleep.sleepSegments(recs.shuffled(java.util.Random(7))), "shuffled records")
    }

    @Test
    fun duplicatedRecordsCountOnceInTheMainBlock() {
        // The detector's stillness window counts SAMPLES, so in upstream every record twice halves its
        // time span and the block edges move out by 90 s (measured on the pinned Swift build:
        // 1781351506..1781383696 against the single night's 1781351596..1781383606). Here a record
        // counts once per counter, the first copy kept (an owner decision, PORTING D-70), so a doubled
        // night is the single night.
        val single = night()
        val block = assertNotNull(BulkSleep.mainSleep(single))
        assertEquals(1781351596L to 1781383606L, block.start.epochSecond to block.end.epochSecond)
        val doubled = single.flatMap { listOf(it, it) }.toMutableList()
        val before = doubled.toList()
        assertEquals(block, BulkSleep.mainSleep(doubled), "every record twice")
        assertEquals(BulkSleep.sleepSegments(single), BulkSleep.sleepSegments(doubled), "segments of every record twice")
        val redrained = single + single.subList(100, 180) // a page delivered again, at the end
        assertEquals(block, BulkSleep.mainSleep(redrained), "a re-drained stretch")
        assertEquals(BulkSleep.sleepSegments(single), BulkSleep.sleepSegments(redrained))
        assertEquals(before, doubled, "the caller's list is left alone")
    }

    @Test
    fun aDuplicatedCounterKeepsItsFirstCopy() {
        // Same counter, different bytes: the first copy is the one detection reads. A moving copy of
        // every epoch (the night's own active pattern), delivered AFTER the night, changes nothing;
        // delivered first, it wins.
        val single = night()
        val active = intArrayOf(0x0a, 0x28, 0x50)
        val movingCopies = single.mapIndexed { i, r -> rec(r.counter, active[i % 3], 0x12) }
        assertEquals(BulkSleep.mainSleep(single), BulkSleep.mainSleep(single + movingCopies), "the night's own copies come first")
        assertNull(BulkSleep.mainSleep(movingCopies + single), "the moving copies come first, so there is no night")
    }

    @Test
    fun farFutureCounterDoesNotJoinTheNight() {
        val recs = night()
        val block = assertNotNull(BulkSleep.mainSleep(recs))
        val far = rec(0xFFFF_FFFFL, 0x01, 0x62)
        assertEquals(block, BulkSleep.mainSleep(recs + far), "one far-future epoch is its own run, not part of the night")
        val inBed = BulkSleep.sleepSegments(recs + far).filter { it.stage == SleepStage.IN_BED }
        assertEquals(listOf(Pair(block.start, block.end)), inBed.map { it.start to it.end })
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    fun weekLongStillRunFinishesAndIsOneBlock() {
        // 7 days of contiguous still epochs: no quadratic blow-up, and (as upstream) no split.
        val recs = (0 until 7 * 576).map { rec(0x0c220000L + it * 150L, 0x01, 0x62) }
        val block = assertNotNull(BulkSleep.mainSleep(recs))
        assertEquals(recs.first().date(), block.start)
        assertEquals(recs.last().date().plusSeconds(120), block.end)
        assertEquals(1, BulkSleep.sleepSegments(recs).count { it.stage == SleepStage.IN_BED })
    }

    @Test
    fun allActiveRecordsYieldNoNight() {
        val active = intArrayOf(0x0a, 0x28, 0x50)
        val busy = (0 until 200).map { rec(0x0c220000L + it * 150L, active[it % 3], 0x12) }
        assertNull(BulkSleep.mainSleep(busy))
        assertEquals(emptyList(), BulkSleep.sleepSegments(busy))
    }

    // onsetIsUnobserved

    @Test
    fun onsetIsUnobservedEdges() {
        val block = DateInterval(base, base.plus(Duration.ofHours(2)))
        assertTrue(BulkSleep.onsetIsUnobserved(block, emptyList()), "no record at all is an unbounded hole")
        val after = rec(0xFFFF_FFFFL, 0x01, 0x62)
        assertTrue(BulkSleep.onsetIsUnobserved(block, listOf(after), epoch = base.epochSecond), "a far-future record is not before the block")
        // A record exactly the required hole (7 h - 2 h = 5 h) before the block is NOT enough: the hole must exceed it.
        val fiveHoursBefore = rec(0L, 0x01, 0x62)
        val epoch = base.epochSecond - 5 * 3600
        assertFalse(BulkSleep.onsetIsUnobserved(block, listOf(fiveHoursBefore), epoch = epoch))
        assertTrue(BulkSleep.onsetIsUnobserved(block, listOf(fiveHoursBefore), epoch = epoch - 1))
    }
}
