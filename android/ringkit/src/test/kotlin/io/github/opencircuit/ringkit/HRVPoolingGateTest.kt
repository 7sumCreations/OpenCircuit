package io.github.opencircuit.ringkit

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The run-level HRV pooling gate. On every Gen-2 / Gen-3 archive the two record templates measure
 * the same HRV, so pooling the activity-epoch HRV is correct and must be preserved; on one device
 * family the activity template runs ~13–20 ms LOW. `BulkSleep.hrvPooling` decides from the run's
 * own data. This suite pins the three verdicts, the calibrated constants, the default-closed choice,
 * and that ONLY the recovered activity-epoch HRV is gated.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/HRVPoolingGateTests.swift
 * (@ b1c2fdd) — 11 of 12 tests. `:279` (`testGateNeverTouchesStrictAccessors`) pins the sleep
 * pipeline (segments, staging, naps, stress) and ports with it.
 *
 * Synthetic records only. Fixture trap kept from upstream: the canonical sleep-vitals hex elsewhere
 * in the suite has a MOVING `[15:20]` tail, so every sleep-vitals epoch here zeroes `[15:20]`
 * unless a test deliberately probes the symmetric quiet gate.
 */
class HRVPoolingGateTest {

    // :29-51
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

    // :55-58 — a QUIET activity epoch: tag 0x12 and a zero tail; its [10:15] still varies.
    private fun quietActivity(counter: Long, hr: Int, hrv: Int, m: Int): BulkRecord =
        rec(counter, hr, hrv.coerceIn(0, 255), rr = 121, tag = 0x12,
            motion = listOf(m, (m + 9) and 0xFF, (m + 3) and 0xFF, (m + 14) and 0xFF, (m + 6) and 0xFF), tail = listOf(0, 0, 0, 0, 0))

    // :60-65
    private fun sleepVitals(counter: Long, hr: Int, hrv: Int, spo2: Int, moving: Boolean = false): BulkRecord =
        rec(counter, hr, hrv.coerceIn(0, 255), rr = 121, tag = spo2, motion = listOf(1, 1, 1, 1, 1),
            tail = if (moving) listOf(0x2a, 0xa0, 0, 0, 0x90) else listOf(0, 0, 0, 0, 0))

    // :71-100 — evening activity, the night, morning activity, from overlapping HRV distributions.
    private fun night(activityHRVDelta: Int = 0, activityCount: Int = 54, sleepCount: Int = 100, movingSleepVitals: Boolean = false): List<BulkRecord> {
        val out = mutableListOf<BulkRecord>()
        var c = 0x0c220000L
        fun step(): Long { val v = c; c = (c + 150) and 0xFFFF_FFFFL; return v }
        val eveningAct = activityCount / 2
        val morningAct = activityCount - eveningAct
        for (i in 0 until eveningAct) {
            out += quietActivity(step(), hr = 68, hrv = 60 + (i * 7) % 25 + activityHRVDelta, m = 20 + (i * 7) % 40)
        }
        for (i in 0 until sleepCount) {
            out += sleepVitals(step(), hr = 52 + i % 5, hrv = 60 + (i * 3) % 25, spo2 = 96 + i % 3, moving = movingSleepVitals)
        }
        for (i in 0 until morningAct) {
            out += quietActivity(step(), hr = 72, hrv = 60 + ((i + eveningAct) * 7) % 25 + activityHRVDelta, m = 25 + (i * 11) % 45)
        }
        return out
    }

    private fun hrvCount(s: List<QuantitySample>) = s.count { it.kind == MetricKind.HRV_SDNN }
    private fun values(s: List<QuantitySample>, k: MetricKind) = s.filter { it.kind == k }.map { it.value }

    private fun poison(records: MutableList<BulkRecord>): Int {
        var replaced = 0
        for (i in records.indices) {
            if (records[i].layout != BulkRecord.Layout.ACTIVITY) continue
            if (replaced * 10 >= 54) break
            if (i % 5 == 0) {
                val b = records[i].raw; b[5] = 200.toByte(); records[i] = assertNotNull(BulkRecord.of(b))
                replaced += 1
            }
        }
        return replaced
    }

    @Test
    fun fixtureActuallyExercisesTheRecovery() { // :119-128
        val r = night()
        val sv = r.count { it.layout == BulkRecord.Layout.SLEEP_VITALS && it.hrvRMSSD != null }
        val act = r.count { it.layout == BulkRecord.Layout.ACTIVITY && it.measuredHRVRMSSD != null }
        assertEquals(100, sv, "100 sleep-vitals HRV epochs")
        assertEquals(54, act, "54 quiet activity epochs carry recoverable HRV")
        assertTrue(act >= BulkSleep.HRV_POOLING_MIN_EPOCHS, "activity pool must be judgeable")
        assertTrue(sv >= BulkSleep.HRV_POOLING_MIN_EPOCHS, "sleep-vitals pool must be judgeable")
        assertEquals(sv + act, hrvCount(BulkSleep.samples(r)), "ungated = pooled")
    }

    @Test
    fun agreeingRunPoolsRecoveredHRV() { // :132-142
        val r = night(activityHRVDelta = 0)
        assertEquals(BulkSleep.HRVPooling.AGREE, BulkSleep.hrvPooling(r))
        val shift = BulkSleep.hrvShift(
            r.filter { it.layout == BulkRecord.Layout.ACTIVITY }.mapNotNull { it.measuredHRVRMSSD },
            r.mapNotNull { it.hrvRMSSD },
        )
        assertTrue(abs(shift) <= BulkSleep.HRV_POOLING_NOISE_FLOOR_MS)
        assertEquals(BulkSleep.samples(r), BulkSleep.samples(r, calibratedBy = r), "an agreeing run must be byte-identical to the ungated output")
    }

    @Test
    fun disagreeingRunSuppressesOnlyActivityHRV() { // :145-165
        val r = night(activityHRVDelta = -15)
        assertEquals(BulkSleep.HRVPooling.DISAGREE, BulkSleep.hrvPooling(r))
        val ungated = BulkSleep.samples(r)
        val gated = BulkSleep.samples(r, calibratedBy = r)
        val strict = r.mapNotNull { it.hrvRMSSD }.map { it.toDouble() }
        assertEquals(154, hrvCount(ungated))
        assertEquals(100, hrvCount(gated), "only the sleep-vitals half survives")
        assertEquals(strict, values(gated, MetricKind.HRV_SDNN), "the suppressed run emits EXACTLY the strict sleep-vitals HRV population")
        assertEquals(values(ungated, MetricKind.RESPIRATORY_RATE), values(gated, MetricKind.RESPIRATORY_RATE), "respiratory rate is not gated")
        assertEquals(values(ungated, MetricKind.HEART_RATE), values(gated, MetricKind.HEART_RATE))
        assertEquals(values(ungated, MetricKind.SPO2), values(gated, MetricKind.SPO2))
    }

    @Test
    fun thinCalibrationYieldsNoEvidenceAndSuppresses() { // :171-179
        val thin = night(activityCount = 19 * 2, sleepCount = 19)
        assertEquals(19, thin.count { it.layout == BulkRecord.Layout.SLEEP_VITALS && it.hrvRMSSD != null })
        assertEquals(BulkSleep.HRVPooling.NO_EVIDENCE, BulkSleep.hrvPooling(thin), "19 < the minimum epochs")
        val gated = BulkSleep.samples(thin, calibratedBy = thin)
        assertEquals(19, hrvCount(gated), "no evidence => sleep-vitals only")
        assertTrue(hrvCount(gated) < hrvCount(BulkSleep.samples(thin)))
    }

    @Test
    fun minEpochsBoundaryIsExact() { // :182-188
        assertEquals(BulkSleep.HRVPooling.NO_EVIDENCE, BulkSleep.hrvPooling(night(activityCount = 19 * 2, sleepCount = 19)))
        assertEquals(BulkSleep.HRVPooling.AGREE, BulkSleep.hrvPooling(night(activityCount = 20 * 2, sleepCount = 20)))
        assertEquals(BulkSleep.HRVPooling.DISAGREE, BulkSleep.hrvPooling(night(activityHRVDelta = -15, activityCount = 20 * 2, sleepCount = 20)))
    }

    @Test
    fun nilCalibrationLeavesGateInert() { // :192-200
        val r = night(activityHRVDelta = -15)
        assertEquals(BulkSleep.HRVPooling.DISAGREE, BulkSleep.hrvPooling(r))
        assertEquals(BulkSleep.samples(r), BulkSleep.samples(r, calibratedBy = null))
        assertEquals(154, hrvCount(BulkSleep.samples(r, calibratedBy = null)), "null => pre-gate behaviour, INCLUDING on a disagreeing run")
    }

    @Test
    fun movingSleepVitalsAreNotInTheReferencePool() { // :206-213
        val r = night(movingSleepVitals = true)
        assertEquals(0, r.count { it.layout == BulkRecord.Layout.SLEEP_VITALS && it.motionIntensityTailIsZero })
        assertTrue(r.mapNotNull { it.hrvRMSSD }.size > BulkSleep.HRV_POOLING_MIN_EPOCHS, "the strict accessor still sees them")
        assertEquals(BulkSleep.HRVPooling.NO_EVIDENCE, BulkSleep.hrvPooling(r))
    }

    @Test
    fun hodgesLehmannKnownAnswers() { // :217-225
        assertEquals(0.0, BulkSleep.hrvShift(listOf(1, 2, 3), listOf(1, 2, 3)), 1e-9, "identical pools => zero shift")
        // a=[10,20] b=[1,2] => diffs 9,8,19,18 => sorted 8,9,18,19 => median (9+18)/2.
        assertEquals(13.5, BulkSleep.hrvShift(listOf(10, 20), listOf(1, 2)), 1e-9)
        assertEquals(-7.0, BulkSleep.hrvShift(listOf(5), listOf(12)), 1e-9)
        assertEquals(0.0, BulkSleep.hrvShift(emptyList(), listOf(1, 2)), "empty side => 0, never a crash")
        assertEquals(0.0, BulkSleep.hrvShift(listOf(1, 2), emptyList()))
    }

    @Test
    fun shiftIsRobustToOutliers() { // :229-250
        val r = night(activityHRVDelta = 0).toMutableList()
        val replaced = poison(r)
        assertTrue(replaced >= 5, "at least 10 % of the activity pool was poisoned")
        assertEquals(BulkSleep.HRVPooling.AGREE, BulkSleep.hrvPooling(r), "Hodges-Lehmann absorbs a 10 % outlier mass")
        val d = night(activityHRVDelta = -15).toMutableList()
        poison(d)
        assertEquals(BulkSleep.HRVPooling.DISAGREE, BulkSleep.hrvPooling(d))
    }

    @Test
    fun strideCapIsVerdictNeutral() { // :253-273
        val big = night(activityCount = 1600, sleepCount = 2400)
        assertTrue(
            big.count { it.layout == BulkRecord.Layout.ACTIVITY } > BulkSleep.HRV_POOLING_SAMPLE_CAP,
            "the activity pool must exceed the cap or this test is vacuous",
        )
        val strided = big.indices.step(10).map { big[it] }
        assertEquals(BulkSleep.HRVPooling.AGREE, BulkSleep.hrvPooling(big))
        assertEquals(BulkSleep.hrvPooling(big), BulkSleep.hrvPooling(strided))

        val bigD = night(activityHRVDelta = -15, activityCount = 1600, sleepCount = 2400)
        val stridedD = bigD.indices.step(10).map { bigD[it] }
        assertEquals(BulkSleep.HRVPooling.DISAGREE, BulkSleep.hrvPooling(bigD))
        assertEquals(BulkSleep.hrvPooling(bigD), BulkSleep.hrvPooling(stridedD))

        val a = big.filter { it.layout == BulkRecord.Layout.ACTIVITY }.mapNotNull { it.measuredHRVRMSSD }
        val b = big.mapNotNull { it.hrvRMSSD }
        assertEquals(BulkSleep.hrvShift(a, b), BulkSleep.hrvShift(a, b))
        assertEquals(0.0, BulkSleep.hrvShift(a, a), 1e-9, "self-shift is 0 even when capped")
    }

    @Test
    fun thresholdAndMinEpochsAreTheMeasuredValues() { // :326-332
        assertEquals(9.0, BulkSleep.HRV_POOLING_NOISE_FLOOR_MS, "midpoint of the measured [5.0, 13.0) separation")
        assertEquals(20, BulkSleep.HRV_POOLING_MIN_EPOCHS, "the separation gap saturates here")
        assertEquals(512, BulkSleep.HRV_POOLING_SAMPLE_CAP)
    }
}
