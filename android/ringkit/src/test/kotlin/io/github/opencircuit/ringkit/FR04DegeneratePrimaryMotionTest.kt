package io.github.opencircuit.ringkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The non-expressive primary motion channel (RingConn Gen 2 Air, FW FR04.009): the `[10:15]`
 * channel idles at 25–45 with a FIXED two-level step inside every epoch (slots 0–1 ≈ 27.6, slots
 * 2–4 ≈ 34.9) plus ±2 noise. The rolling floor cancels a flat plateau at any level, but not a step
 * inside one epoch, so `motionSource` must fall through to the `[15:20]` intensity tail.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/FR04DegeneratePrimaryMotionTests.swift
 * (@ b1c2fdd) — 10 of 13 tests. The three that need sleep detection and staging (`:103`, `:115`,
 * `:228`) port with the night half of `BulkSleep`.
 *
 * All data is SYNTHETIC: it reproduces the failure shape, not a person's night. Every fixture
 * number is typed from upstream. The noise generator is upstream's deterministic xorshift; JUnit
 * builds a fresh instance per test, as XCTest does, so each test starts from the same seed.
 */
class FR04DegeneratePrimaryMotionTest {

    private val step = BulkRecord.EPOCH_SECONDS.toLong()

    // :20-24 — deterministic small noise, never `random`, so the suite never flakes.
    private var seed: ULong = 0x9E3779B97F4A7C15uL

    private fun jitter(span: Int): Int {
        seed = seed xor (seed shl 13); seed = seed xor (seed shr 7); seed = seed xor (seed shl 17)
        return (seed % (2 * span + 1).toULong()).toInt() - span
    }

    // :26-37
    private fun record(counter: Long, hr: Int, primary: List<Int>, tail: List<Int>, sleepVitals: Boolean): BulkRecord {
        val b = ByteArray(BulkRecord.LENGTH)
        b[0] = (counter shr 24).toByte(); b[1] = (counter shr 16).toByte()
        b[2] = (counter shr 8).toByte(); b[3] = counter.toByte()
        b[4] = hr.toByte()
        if (sleepVitals) { b[5] = 45; b[7] = 120; b[8] = 96 } else { b[8] = 0x12 }
        for (i in 0 until 5) { b[10 + i] = primary[i].toByte(); b[15 + i] = tail[i].toByte() }
        return assertNotNull(BulkRecord.of(b))
    }

    // :39-45 — the FR04 still shape: a fixed two-level step, ±2 noise, and a ZERO intensity tail.
    private fun steppedStillEpoch(c: Long, hr: Int = 58): BulkRecord {
        val base = listOf(27, 27, 34, 34, 35)
        return record(c, hr, primary = base.map { maxOf(0, it + jitter(2)) }, tail = listOf(0, 0, 0, 0, 0), sleepVitals = true)
    }

    // :47-54 — an awake epoch: large varying primary counts AND a non-zero tail.
    private fun awakeEpoch(c: Long, i: Int): BulkRecord {
        val shapes = listOf(listOf(103, 98, 206, 229, 206), listOf(224, 242, 221, 230, 211),
            listOf(255, 254, 254, 247, 255), listOf(49, 54, 35, 55, 37))
        val tails = listOf(listOf(80, 90, 120, 60, 70), listOf(140, 130, 150, 120, 110),
            listOf(200, 190, 210, 180, 170), listOf(40, 55, 35, 60, 45))
        return record(c, hr = 92, primary = shapes[i % 4], tail = tails[i % 4], sleepVitals = false)
    }

    // :56-64 — 12 awake epochs, ~150 stepped-still epochs (6.25 h), 12 awake epochs.
    private fun fr04Night(stillEpochs: Int = 150): List<BulkRecord> {
        var c = 0x0c50_0000L
        val out = mutableListOf<BulkRecord>()
        for (i in 0 until 12) { out += awakeEpoch(c, i); c += step }
        repeat(stillEpochs) { out += steppedStillEpoch(c); c += step }
        for (i in 0 until 12) { out += awakeEpoch(c, i); c += step }
        return out
    }

    // :66-77 — a CONSTANT primary run at `level` (Gen 2 `1`, Gen 3 `15`) and the same tails.
    private fun flatNight(level: Int): List<BulkRecord> {
        var c = 0x0c50_0000L
        val out = mutableListOf<BulkRecord>()
        for (i in 0 until 12) { out += awakeEpoch(c, i); c += step }
        repeat(150) {
            out += record(c, hr = 58, primary = List(5) { level }, tail = listOf(0, 0, 0, 0, 0), sleepVitals = true); c += step
        }
        for (i in 0 until 12) { out += awakeEpoch(c, i); c += step }
        return out
    }

    // :79-92 — Gen-3's drifting floor: constant WITHIN each epoch, stepping BETWEEN plateaus.
    private fun driftingNight(): List<BulkRecord> {
        var c = 0x0c50_0000L
        val out = mutableListOf<BulkRecord>()
        for (i in 0 until 12) { out += awakeEpoch(c, i); c += step }
        for (level in listOf(16, 24, 39)) {
            repeat(50) {
                out += record(c, hr = 58, primary = List(5) { level }, tail = listOf(0, 0, 0, 0, 0), sleepVitals = true); c += step
            }
        }
        for (i in 0 until 12) { out += awakeEpoch(c, i); c += step }
        return out
    }

    private fun List<BulkRecord>.worn() = filter { it.layout != BulkRecord.Layout.IDLE }

    @Test
    fun fixedIntraEpochStepIsJudgedNonExpressive() { // :95-101
        val recs = fr04Night()
        assertTrue(
            BulkSleep.primaryMotionIsDegenerate(recs.worn()),
            "a fixed intra-epoch step that never resolves stillness is a non-expressive channel",
        )
        assertEquals(BulkSleep.MotionSource.IntensityTail(degenerate = true), BulkSleep.motionSource(recs))
    }

    @Test
    fun gen2FlatFloorIsNotJudgedNonExpressive() { // :126-131
        val recs = flatNight(level = 1)
        assertFalse(BulkSleep.primaryMotionIsDegenerate(recs.worn()))
        assertEquals(BulkSleep.MotionSource.Primary, BulkSleep.motionSource(recs), "a Gen-2 flat `01` night keeps the primary channel")
    }

    @Test
    fun gen3FlatFloorIsNotJudgedNonExpressive() { // :133-137
        val recs = flatNight(level = 15)
        assertFalse(BulkSleep.primaryMotionIsDegenerate(recs.worn()))
        assertEquals(BulkSleep.MotionSource.Primary, BulkSleep.motionSource(recs))
    }

    @Test
    fun gen3DriftingFloorIsNotJudgedNonExpressive() { // :139-144
        val recs = driftingNight()
        assertFalse(
            BulkSleep.primaryMotionIsDegenerate(recs.worn()),
            "the Gen-3 drift is BETWEEN epochs; each epoch is still a constant run",
        )
        assertEquals(BulkSleep.MotionSource.Primary, BulkSleep.motionSource(recs))
    }

    @Test
    fun constantFillerBranchStaysNonDegenerate() { // :147-157
        var c = 0x0c4f_0000L
        val out = mutableListOf<BulkRecord>()
        for (i in 0 until 120) {
            val tail = if (i == 40) listOf(0, 0, 32, 0, 0) else if (i == 41) listOf(0, 16, 32, 0, 0) else listOf(0, 0, 0, 0, 0)
            out += record(c, hr = 55, primary = listOf(1, 1, 1, 1, 1), tail = tail, sleepVitals = true); c += step
        }
        assertEquals(
            BulkSleep.MotionSource.IntensityTail(degenerate = false),
            BulkSleep.motionSource(out),
            "the 2026-07-12 constant-filler shape keeps the p80 seam, byte for byte",
        )
    }

    @Test
    fun slotOrderConsistency() { // :160-186
        var c = 0x0c50_0000L
        val fixedStep = List(40) { steppedStillEpoch(c).also { c += step } }
        assertTrue(BulkSleep.slotOrderConsistency(fixedStep) >= BulkSleep.DEGENERATE_MIN_SLOT_ORDER_FRACTION)

        val flat = List(40) {
            record(c, hr = 58, primary = listOf(15, 15, 15, 15, 15), tail = listOf(0, 0, 0, 0, 0), sleepVitals = true).also { c += step }
        }
        assertEquals(0.0, BulkSleep.slotOrderConsistency(flat), 1e-9, "a constant run ties every comparison")

        val noisy = List(200) {
            record(c, hr = 58, primary = List(5) { 30 + jitter(2) }, tail = listOf(0, 0, 0, 0, 0), sleepVitals = true).also { c += step }
        }
        assertTrue(
            BulkSleep.slotOrderConsistency(noisy) < BulkSleep.DEGENERATE_MIN_SLOT_ORDER_FRACTION,
            "independent per-slot noise has no phase-locked ordering",
        )
    }

    @Test
    fun shortRunIsNeverJudged() { // :190-194
        val recs = fr04Night(stillEpochs = 20)
        assertFalse(BulkSleep.primaryMotionIsDegenerate(recs.worn()))
        assertEquals(BulkSleep.MotionSource.Primary, BulkSleep.motionSource(recs))
    }

    @Test
    fun degenerateShapeWithNoTailMovementStaysOnPrimary() { // :198-206
        var c = 0x0c50_0000L
        val recs = List(150) { steppedStillEpoch(c).also { c += step } }
        assertTrue(BulkSleep.primaryMotionIsDegenerate(recs))
        assertEquals(BulkSleep.MotionSource.Primary, BulkSleep.motionSource(recs))
    }

    @Test
    fun otsuSeamSitsBelowTheQuantileOnABroadPool() { // :213-217
        val ramp = List(184) { 1 + (899 * it) / 183 }
        val quantile = ramp[Math.round((ramp.size - 1) * 0.80).toInt()]
        assertTrue(BulkSleep.otsuIntensityCut(ramp) < quantile)
    }

    @Test
    fun otsuNeverSplitsATie() { // :219-222
        assertEquals(64, BulkSleep.otsuIntensityCut(listOf(64, 64)))
        assertEquals(7, BulkSleep.otsuIntensityCut(listOf(7)))
    }
}
