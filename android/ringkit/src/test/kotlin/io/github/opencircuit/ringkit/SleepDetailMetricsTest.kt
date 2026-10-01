package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.SleepDetailMetrics.MovementLevel
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Per-stage average HR and the 2.5-min, 3-level movement timeline. Synthetic records only.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepDetailMetricsTests.swift
 * (@ b1c2fdd) — all 5 tests.
 */
class SleepDetailMetricsTest {

    // :8-15 — a sleep-vitals epoch with an explicit 5-sample motion array.
    private fun rec(counter: Long, hr: Int, motionBytes: List<Int>): BulkRecord {
        val b = ByteArray(23)
        b[0] = (counter shr 24).toByte(); b[1] = (counter shr 16).toByte()
        b[2] = (counter shr 8).toByte(); b[3] = counter.toByte()
        b[4] = hr.toByte(); b[8] = 0x62
        for (k in 0 until 5) b[10 + k] = motionBytes[k].toByte()
        return assertNotNull(BulkRecord.of(b))
    }

    // :18-20 — a UNIFORM motion byte (a constant run = still at any level).
    private fun rec(counter: Long, hr: Int, motion: Int = 1): BulkRecord = rec(counter, hr, List(5) { motion })

    private val step = 150L

    @Test
    fun averageHRByStage() { // :24-49
        fun t(c: Long): Instant = Instant.ofEpochSecond(c + 1_577_793_600L)
        // Two deep epochs @ 50/52, two REM epochs @ 64/66.
        var c = 1000L
        val deepStart = c
        val r0 = rec(c, hr = 50); c += step
        val r1 = rec(c, hr = 52); c += step
        val deepEnd = c
        val remStart = c
        val r2 = rec(c, hr = 64); c += step
        val r3 = rec(c, hr = 66); c += step
        val remEnd = c + step

        val segs = listOf(
            SleepSegment(t(deepStart), t(remEnd), SleepStage.IN_BED),
            SleepSegment(t(deepStart), t(deepEnd), SleepStage.ASLEEP_DEEP),
            SleepSegment(t(remStart), t(remEnd), SleepStage.ASLEEP_REM),
        )
        val byStage = SleepDetailMetrics.averageHRByStage(listOf(r0, r1, r2, r3), segs)
        assertEquals(51, byStage[SleepStage.ASLEEP_DEEP])
        assertEquals(65, byStage[SleepStage.ASLEEP_REM])
        assertNull(byStage[SleepStage.ASLEEP_CORE], "no light epochs → omitted")
        assertNull(byStage[SleepStage.IN_BED], "inBed excluded so stages don't double-count")
    }

    /**
     * The "all-orange" regression: a CONSTANT motion run is the ring's still/placeholder filler at
     * ANY level — Gen-2 `01`, Gen-3 `0f` (= 15), a drifted idle — and must read still, never active.
     */
    @Test
    fun constantRunsAreStillAtAnyLevel() { // :54-61
        var c = 0L
        val recs = mutableListOf<BulkRecord>()
        for (v in listOf(1, 15, 20, 39)) { recs += rec(c, hr = 55, motion = v); c += step }
        val m = SleepDetailMetrics.movement(recs)
        assertEquals(listOf(MovementLevel.STILL, MovementLevel.STILL, MovementLevel.STILL, MovementLevel.STILL), m.map { it.level })
        assertTrue(m.all { it.magnitude == 0 }, "a constant run has zero intra-epoch energy")
    }

    /** Movement = how far the 5 sub-samples rise above the epoch's OWN minimum; explicit cut. */
    @Test
    fun movementLevels() { // :65-76
        var c = 0L
        val still = rec(c, hr = 55, motionBytes = listOf(1, 1, 1, 1, 1)); c += step // no variation → still
        val light = rec(c, hr = 55, motionBytes = listOf(1, 1, 1, 4, 1)); c += step // energy 3
        val active = rec(c, hr = 55, motionBytes = listOf(10, 40, 15, 50, 20)) // energy 85

        val m = SleepDetailMetrics.movement(listOf(still, light, active), activeThreshold = 20)
        assertEquals(listOf(MovementLevel.STILL, MovementLevel.LIGHT, MovementLevel.ACTIVE), m.map { it.level })
        assertEquals(0, m[0].magnitude)
        assertEquals(3, m[1].magnitude)
        assertEquals(85, m[2].magnitude)
    }

    /** With no explicit threshold the light/active split is the 80th percentile of the night's OWN moving energies. */
    @Test
    fun derivedActiveCutUsesNightDistribution() { // :80-89
        var c = 0L
        val recs = mutableListOf<BulkRecord>()
        // energies 2, 4, 6, 8, 80 ; idx = round(4×0.8) = 3 → cut = 8 ; ≥8 → active
        for (a in listOf(listOf(1, 3, 1, 1, 1), listOf(1, 5, 1, 1, 1), listOf(1, 7, 1, 1, 1), listOf(1, 9, 1, 1, 1), listOf(1, 81, 1, 1, 1))) {
            recs += rec(c, hr = 55, motionBytes = a); c += step
        }
        val m = SleepDetailMetrics.movement(recs)
        assertEquals(
            listOf(MovementLevel.LIGHT, MovementLevel.LIGHT, MovementLevel.LIGHT, MovementLevel.ACTIVE, MovementLevel.ACTIVE),
            m.map { it.level },
        )
    }

    @Test
    fun movementSummaryCounts() { // :91-105
        var c = 0L
        val recs = mutableListOf<BulkRecord>()
        repeat(6) { recs += rec(c, hr = 55, motion = 1); c += step } // still (energy 0)
        repeat(3) { recs += rec(c, hr = 55, motionBytes = listOf(1, 1, 1, 4, 1)); c += step } // light (energy 3)
        recs += rec(c, hr = 55, motionBytes = listOf(5, 50, 5, 5, 5)) // active (energy 45)

        val s = SleepDetailMetrics.movementSummary(recs, activeThreshold = 20)
        assertEquals(6, s.still)
        assertEquals(3, s.light)
        assertEquals(1, s.active)
        assertEquals(10, s.total)
        assertEquals(10, s.levels.size)
        assertEquals(0.4, s.movementFraction, 1e-9)
    }
}
