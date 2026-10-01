package io.github.opencircuit.ringkit

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The byte-identity net for the strict vitals scope. `measuredHRVRMSSD` / `measuredRespiratoryRate`
 * recover HRV and RR from `0x12`/`0x13` ACTIVITY epochs so they reach the health store; the strict
 * `hrvRMSSD` / `respiratoryRate` accessors stay sleep-vitals-scoped because sleep detection,
 * staging, naps and stress read that scope as the ring's "I am measuring sleep" MODE flag. Every
 * test builds the same synthetic night twice — once with plausible HRV/RR bytes on the quiet
 * activity epochs, once with those two bytes ZEROED — and asserts the sleep pipeline cannot tell
 * them apart. Synthetic records only.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/StrictVitalsPinningTests.swift
 * (@ b1c2fdd) — all 10 tests. Naps are judged in a named zone (upstream reads the device calendar):
 * America/New_York for the pins, which compare the two inputs, and Asia/Tokyo for the false-nap
 * test, where its block is daytime (see that test).
 */
class StrictVitalsPinningTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")

    // :25-46 — one 23-byte 0x4c record. [8] == 0x12 makes it an ACTIVITY epoch.
    private fun rec(counter: Long, hr: Int, hrv: Int, rr: Int, tag: Int, motion: List<Int>, tail: List<Int>): BulkRecord {
        val b = ByteArray(23)
        b[0] = (counter shr 24).toByte(); b[1] = (counter shr 16).toByte()
        b[2] = (counter shr 8).toByte(); b[3] = counter.toByte()
        b[4] = hr.toByte(); b[5] = hrv.toByte()
        b[6] = 0x05 // confidence — NOT 0x0c, so the idle template never matches
        b[7] = rr.toByte(); b[8] = tag.toByte(); b[9] = 0x0a
        for (i in 0 until 5) { b[10 + i] = motion[i].toByte(); b[15 + i] = tail[i].toByte() }
        return assertNotNull(BulkRecord.of(b))
    }

    /** :52-55 — an awake-but-quiet evening/morning epoch: tag 0x12, zero tail, VARYING [10:15]. */
    private fun quietActivity(counter: Long, hr: Int, m: Int): BulkRecord =
        rec(counter, hr, hrv = 58, rr = 121, tag = 0x12,
            motion = listOf(m, (m + 9) and 0xFF, (m + 3) and 0xFF, (m + 14) and 0xFF, (m + 6) and 0xFF), tail = listOf(0, 0, 0, 0, 0))

    // :57-60
    private fun sleepVitals(counter: Long, hr: Int, hrv: Int, spo2: Int): BulkRecord =
        rec(counter, hr, hrv, rr = 121, tag = spo2, motion = listOf(1, 1, 1, 1, 1), tail = listOf(0, 0, 0, 0, 0))

    /** :65-88 — sleep-vitals core, bracketed and interrupted by QUIET activity epochs. */
    private fun night(): List<BulkRecord> {
        val out = mutableListOf<BulkRecord>()
        var c = 0x0c220000L
        fun step(): Long { val v = c; c = (c + 150) and 0xFFFF_FFFFL; return v }
        for (i in 0 until 16) out += quietActivity(step(), hr = 68, m = 20 + (i * 7) % 40) // awake evening
        for (i in 0 until 40) out += sleepVitals(step(), hr = 52 + i % 5, hrv = 60 + (i * 3) % 25, spo2 = 96 + i % 3)
        for (i in 0 until 4) out += quietActivity(step(), hr = 56, m = 12 + i * 5) // mid-night quiet activity
        for (i in 0 until 60) out += sleepVitals(step(), hr = 50 + i % 6, hrv = 64 + (i * 5) % 30, spo2 = 95 + i % 4)
        for (i in 0 until 16) out += quietActivity(step(), hr = 72, m = 25 + (i * 11) % 45) // awake morning
        return out
    }

    /** :93-100 — the same records with [5] and [7] zeroed on every ACTIVITY epoch. */
    private fun zeroedOnActivity(records: List<BulkRecord>): List<BulkRecord> = records.map { r ->
        if (r.layout != BulkRecord.Layout.ACTIVITY) {
            r
        } else {
            val b = r.raw
            b[5] = 0; b[7] = 0
            assertNotNull(BulkRecord.of(b))
        }
    }

    // MARK: anti-vacuity

    @Test
    fun fixtureActuallyExercisesTheRecovery() { // :106-122
        val a = night()
        val b = zeroedOnActivity(night())
        assertEquals(a.size, b.size)
        val recovered = a.count { it.layout == BulkRecord.Layout.ACTIVITY && it.measuredHRVRMSSD != null }
        assertEquals(36, recovered, "36 quiet activity epochs carry recoverable HRV")
        assertEquals(0, b.count { it.layout == BulkRecord.Layout.ACTIVITY && it.measuredHRVRMSSD != null })
        // …and the sample path DOES see the difference.
        val ha = BulkSleep.samples(a).count { it.kind == MetricKind.HRV_SDNN }
        val hb = BulkSleep.samples(b).count { it.kind == MetricKind.HRV_SDNN }
        assertEquals(36, ha - hb, "recovered HRV reaches the health-store sample path")
        val ra = BulkSleep.samples(a).count { it.kind == MetricKind.RESPIRATORY_RATE }
        val rb = BulkSleep.samples(b).count { it.kind == MetricKind.RESPIRATORY_RATE }
        assertEquals(36, ra - rb, "recovered RR reaches the health-store sample path")
        // The STRICT accessors, by contrast, see nothing at all.
        assertEquals(a.mapNotNull { it.hrvRMSSD }, b.mapNotNull { it.hrvRMSSD })
        assertEquals(a.mapNotNull { it.respiratoryRate }, b.mapNotNull { it.respiratoryRate })
    }

    // MARK: the pins

    @Test
    fun sleepVitalTimelineUnchanged() { // :126-131
        assertEquals(
            BulkSleep.sleepVitalTimeline(night()),
            BulkSleep.sleepVitalTimeline(zeroedOnActivity(night())),
            "sleepVitalTimeline feeds the sleep-vitals rescue — it must never see a recovered activity-epoch HRV",
        )
    }

    @Test
    fun mainSleepDetectionUnchanged() { // :133-137
        assertEquals(BulkSleep.mainSleep(night()), BulkSleep.mainSleep(zeroedOnActivity(night())), "the detected in-bed window must not move")
    }

    @Test
    fun coarseSleepSegmentsUnchanged() { // :139-142
        assertEquals(BulkSleep.sleepSegments(night()), BulkSleep.sleepSegments(zeroedOnActivity(night())))
    }

    @Test
    fun stagingUnchangedAtDefaultTuning() { // :144-147
        assertEquals(SleepStaging.classify(night()), SleepStaging.classify(zeroedOnActivity(night())))
    }

    /** The default tuning has `rrVarWeight == 0`; pin one with BOTH variability weights live. */
    @Test
    fun stagingUnchangedWithHRVAndRRVariabilityWeightsLive() { // :153-158
        val t = SleepStaging.Tuning(hrvVarWeight = 1.0, rrVarWeight = 1.0)
        assertTrue(t.rrVarWeight > 0, "guard against a future default change hiding this")
        assertEquals(SleepStaging.classify(night(), tuning = t), SleepStaging.classify(zeroedOnActivity(night()), tuning = t))
    }

    @Test
    fun napDetectionUnchanged() { // :160-164
        val a = night()
        val b = zeroedOnActivity(night())
        assertEquals(
            NapDetection.naps(a, mainSleep = BulkSleep.mainSleep(a), zone = zone),
            NapDetection.naps(b, mainSleep = BulkSleep.mainSleep(b), zone = zone),
        )
    }

    @Test
    fun sleepStressUnchanged() { // :166-172
        assertEquals(
            SleepStress.overnightScore(night()),
            SleepStress.overnightScore(zeroedOnActivity(night())),
            "the stress median is built from the STRICT hrvRMSSD",
        )
        assertEquals(SleepStress.stateDurations(night()), SleepStress.stateDurations(zeroedOnActivity(night())))
    }

    /** `averageHRByStage` filters on `layout == SLEEP_VITALS` directly; it consumes the staged segments the pins above protect. */
    @Test
    fun sleepDetailMetricsUnchanged() { // :177-183
        val a = night()
        val b = zeroedOnActivity(night())
        assertEquals(
            SleepDetailMetrics.averageHRByStage(a, SleepStaging.classify(a)),
            SleepDetailMetrics.averageHRByStage(b, SleepStaging.classify(b)),
        )
    }

    // MARK: the false-nap gate still bites

    /**
     * Naps gate false naps on the SLEEP-VITALS SHARE of a still block, read straight off `layout`. A
     * sedentary daytime still block of quiet `0x12` epochs carries plausible HRV and RR, and must
     * STILL produce zero naps.
     *
     * The block runs 05:12–07:42 UTC. Judged in New York (01:12 local) it is overnight and never a nap
     * candidate, so the assertion would hold whatever the share gate did — measured on the pinned
     * Swift build, the same block tagged sleep-vitals is no nap in New York either, but is one in
     * Tokyo (14:12 local). The day is therefore judged in Asia/Tokyo, where only the share gate
     * can reject it.
     */
    @Test
    fun sedentaryQuietActivityBlockIsStillNotANap() { // :191-204
        val daytime = ZoneId.of("Asia/Tokyo")
        val out = mutableListOf<BulkRecord>()
        var c = 0x0c230000L
        repeat(60) { // 2.5 h of still, quiet, awake-at-a-desk epochs
            out += rec(c, hr = 66, hrv = 58, rr = 121, tag = 0x12, motion = listOf(1, 1, 1, 1, 1), tail = listOf(0, 0, 0, 0, 0))
            c = (c + 150) and 0xFFFF_FFFFL
        }
        assertTrue(out.all { it.layout == BulkRecord.Layout.ACTIVITY })
        assertTrue(out.all { it.measuredHRVRMSSD == 58 }, "HRV IS recoverable here")
        assertTrue(out.all { it.hrvRMSSD == null }, "…but the sleep-vitals share stays 0")
        assertTrue(
            NapDetection.naps(out, mainSleep = null, zone = daytime).isEmpty(),
            "a sedentary daytime block must never become a nap",
        )
    }
}
