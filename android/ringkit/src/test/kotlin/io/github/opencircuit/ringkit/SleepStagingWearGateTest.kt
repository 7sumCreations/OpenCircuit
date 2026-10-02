package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.truncate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Port of upstream SleepStagingWearGateTests.swift (@ b1c2fdd), all 8 tests: the skin-temperature
 * WEAR GATE on the STAGED path.
 *
 * The defect it pins: the staged classifier once called `mainSleep` without the night's temperature
 * samples while the coarse segments and night selection passed them, so an off-wrist / charging block
 * (perfectly still, which the motion detector calls sleep) reached the hypnogram. The evidence here
 * is a SYNTHETIC unworn block over a synthetic night. Wake is expressed as ELEVATED HR, never as a
 * motion value (a constant motion byte de-floors to "still"). Every literal is typed from upstream.
 */
class SleepStagingWearGateTest {

    // Fixtures

    private fun counterBytes(b: ByteArray, counter: Long) {
        b[0] = (counter shr 24).toByte(); b[1] = ((counter shr 16) and 0xFF).toByte()
        b[2] = ((counter shr 8) and 0xFF).toByte(); b[3] = (counter and 0xFF).toByte()
    }

    /** A still epoch carrying sleep-vitals HRV (`SLEEP_VITALS` template). */
    private fun vrec(counter: Long, hr: Int, hrv: Int = 55): BulkRecord {
        val b = ByteArray(23)
        counterBytes(b, counter)
        b[4] = hr.toByte(); b[5] = hrv.toByte(); b[8] = 0x62
        for (k in 0 until 5) b[10 + k] = 1
        return BulkRecord.of(b)!!
    }

    /** A still epoch on the `ACTIVITY` template — "no SpO2 in this epoch", not movement. */
    private fun qrec(counter: Long, hr: Int): BulkRecord {
        val b = ByteArray(23)
        counterBytes(b, counter)
        b[4] = hr.toByte(); b[8] = 0x12
        for (k in 0 until 5) b[10 + k] = 1
        return BulkRecord.of(b)!!
    }

    private val firstCounter = 1_000_000L

    /** A plain still night that stages as real sleep with no temperature evidence; the morning is an HR rise. */
    private fun stillNight(epochs: Int = 160, riseAt: Int = 130, sleepHR: Int = 54, wakeHR: Int = 80, from: Long? = null): List<BulkRecord> {
        val recs = mutableListOf<BulkRecord>()
        var c = from ?: firstCounter
        for (i in 0 until epochs) {
            val hr = if (i >= riseAt) wakeHR else sleepHR
            recs += if (i % 2 == 0) vrec(c, hr = hr) else qrec(c, hr = hr)
            c += BulkRecord.EPOCH_SECONDS.toLong()
        }
        return recs
    }

    private fun date(counter: Long): Instant = Instant.ofEpochSecond(counter + Command.SYNC_EPOCH)

    /** Temperature samples every 5 minutes across [records]' whole span. */
    private fun temps(over: List<BulkRecord>, celsius: Double): List<TemperatureSample> {
        val lo = over.firstOrNull()?.date() ?: return emptyList()
        val hi = over.last().date()
        val out = mutableListOf<TemperatureSample>()
        var t = lo
        while (!t.isAfter(hi)) {
            out += TemperatureSample(t, celsius)
            t = t.plusSeconds(300)
        }
        return out
    }

    /** Swift's `Int((x / 60).rounded())`: to nearest, ties away from zero. */
    private fun asleepMinutes(segs: List<SleepSegment>): Int {
        val d: Duration = SleepStaging.totalAsleep(segs)
        val m = (d.seconds + d.nano / 1e9) / 60
        val t = truncate(m)
        return (if (abs(m - t) >= 0.5) t + sign(m) else t).toInt()
    }

    // The gate is LIVE on the staged path

    /** THE REGRESSION TEST: the ring reads 20 °C through the entire block — on a charger, not a finger. */
    @Test
    fun testColdBlockIsNotStagedAsANight() {
        val recs = stillNight()
        val warm = SleepStaging.classify(recs)
        assertTrue(asleepMinutes(warm) > 120, "fixture must stage as a real night before the gate can be shown to remove it")

        val cold = SleepStaging.classify(recs, temperatures = temps(over = recs, celsius = 20.0))
        assertEquals(
            0, asleepMinutes(cold),
            "an off-wrist block must not reach the hypnogram — the staged path ignored `temperatures:` before #194",
        )
    }

    /** The gate must NOT fire on a worn night ("pass temperatures and always reclassify" fails this one). */
    @Test
    fun testWornTemperaturesLeaveTheNightExactlyAsStagedWithoutThem() {
        val recs = stillNight()
        val none = SleepStaging.classify(recs)
        val worn = SleepStaging.classify(recs, temperatures = temps(over = recs, celsius = 34.0))
        assertEquals(none, worn, "a worn night must be byte-identical to the temperature-free replay")
        assertTrue(asleepMinutes(worn) > 120)
    }

    /** ABSENCE OF DATA IS NOT EVIDENCE OF BEING UNWORN: cold samples outside the block leave the night alone. */
    @Test
    fun testColdSamplesOutsideTheBlockDoNotDropTheNight() {
        val recs = stillNight()
        val before = date(firstCounter).minusSeconds(6 * 3600)
        val outside = (0 until 60).map { TemperatureSample(before.plusSeconds(it * 300L), 20.0) }
        assertEquals(
            SleepStaging.classify(recs), SleepStaging.classify(recs, temperatures = outside),
            "no coverage inside the block ⇒ trust the motion verdict, unchanged",
        )
    }

    /** An EMPTY set is the ungated call, and must be byte-identical. */
    @Test
    fun testEmptyTemperaturesAreByteIdenticalToTheUngatedStaging() {
        val recs = stillNight()
        assertEquals(SleepStaging.classify(recs), SleepStaging.classify(recs, temperatures = emptyList()))
    }

    // The multi-fragment (stitched) path

    /** A night handed off across two drains is staged per run; every fragment must be gated. */
    @Test
    fun testStitchedNightAlsoHonoursTheWearGate() {
        // Two runs separated by a hole larger than `gravityMaxGap` (20 min), so `contiguousFragments`
        // splits them. Each run is long enough to stage on its own.
        val a = stillNight(epochs = 90, riseAt = 80, from = firstCounter)
        val gapStart = firstCounter + 90L * BulkRecord.EPOCH_SECONDS + 3600
        val b = stillNight(epochs = 90, riseAt = 80, from = gapStart)
        val recs = a + b
        assertEquals(2, BulkSleep.contiguousFragments(recs).size, "fixture must actually be stitched")

        assertTrue(asleepMinutes(SleepStaging.classify(recs)) > 120)
        assertEquals(
            0, asleepMinutes(SleepStaging.classify(recs, temperatures = temps(over = recs, celsius = 20.0))),
            "every fragment must be gated, not just a single-fragment night",
        )
    }

    // The kill switch

    /** `stagedWearGate = false` restores the ungated staging byte-identically, even with cold samples. */
    @Test
    fun testKillSwitchRestoresTheUngatedStagingByteIdentically() {
        val recs = stillNight()
        val off = SleepStaging.classify(
            recs, temperatures = temps(over = recs, celsius = 20.0), tuning = SleepStaging.Tuning(stagedWearGate = false),
        )
        assertEquals(SleepStaging.classify(recs), off, "the escape hatch must reproduce the temperature-free staging exactly")
    }

    @Test
    fun testTheGateIsOnByDefault() {
        assertTrue(SleepStaging.Tuning.DEFAULT.stagedWearGate, "#194 ships the gate ENABLED; the flag exists to turn it off, not on")
    }

    // The two paths must AGREE

    /** The coarse segmentation and the staged hypnogram must not disagree about whether the ring was worn. */
    @Test
    fun testCoarseAndStagedPathsAgreeThatAColdBlockIsNotSleep() {
        val recs = stillNight()
        val cold = temps(over = recs, celsius = 20.0)

        val coarse = BulkSleep.sleepSegments(recs, temperatures = cold)
        val staged = SleepStaging.classify(recs, temperatures = cold)

        assertTrue(coarse.all { it.stage != SleepStage.ASLEEP_CORE }, "coarse path has always honoured the gate")
        assertEquals(0, asleepMinutes(staged), "staged path must now agree with it")

        // …and they must still agree on a WORN night, in the other direction.
        val warm = temps(over = recs, celsius = 34.0)
        assertTrue(BulkSleep.sleepSegments(recs, temperatures = warm).any { it.stage == SleepStage.ASLEEP_CORE })
        assertTrue(asleepMinutes(SleepStaging.classify(recs, temperatures = warm)) > 120)
    }
}
