package io.github.opencircuit.ringkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A device-observed failure shape, synthetic: every primary `[10:15]` motion field is a constant
 * filler, but `[15:20]` still reports activity. The motion timeline, the movement chart and staging
 * must all read the tail; a quiet filler night and any real primary motion keep the primary path.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/MotionTailFallbackTests.swift
 * (@ b1c2fdd) — all 6 tests, with upstream's rescaled tail magnitudes (a 400-unit tail is real
 * movement against the absolute seam; a 48-unit tail is a stir).
 */
class MotionTailFallbackTest {

    private val step = BulkRecord.EPOCH_SECONDS.toLong()

    // :9-26
    private fun record(counter: Long, hr: Int = 55, primary: List<Int> = listOf(1, 1, 1, 1, 1), intensity: List<Int> = listOf(0, 0, 0, 0, 0)): BulkRecord {
        val bytes = ByteArray(BulkRecord.LENGTH)
        bytes[0] = (counter shr 24).toByte(); bytes[1] = (counter shr 16).toByte()
        bytes[2] = (counter shr 8).toByte(); bytes[3] = counter.toByte()
        bytes[4] = hr.toByte(); bytes[5] = 45; bytes[7] = 120; bytes[8] = 96
        for (index in 0 until 5) {
            bytes[10 + index] = primary[index].toByte()
            bytes[15 + index] = intensity[index].toByte()
        }
        return assertNotNull(BulkRecord.of(bytes))
    }

    // :28-34 — 120 epochs of constant primary filler; [movingEpochs] carry a tail.
    private fun fillerNight(movingEpochs: Map<Int, List<Int>> = emptyMap()): List<BulkRecord> {
        var counter = 0x0c4f_0000L
        return List(120) { index ->
            val r = record(counter, intensity = movingEpochs[index] ?: listOf(0, 0, 0, 0, 0))
            counter += step
            r
        }
    }

    @Test
    fun timelineUsesRepeatedEpochIntensityWhenPrimaryRunIsEntirelyFiller() { // :43-53
        val records = fillerNight(mapOf(40 to listOf(0, 0, 32, 0, 0), 41 to listOf(0, 200, 200, 0, 0))) // a stir; 400 — real movement
        assertTrue(BulkSleep.usesMotionIntensityFallback(records))

        val timeline = BulkSleep.motionTimeline(records)
        assertEquals(listOf(1f, 1f, 1f, 1f, 1f), timeline.subList(40 * 5, 40 * 5 + 5).map { it.movement })
        assertEquals(listOf(16f, 16f, 16f, 16f, 16f), timeline.subList(41 * 5, 41 * 5 + 5).map { it.movement })
    }

    /** The per-set rank artefact, pinned as REMOVED: small stirs alone read as light, not as an awakening. */
    @Test
    fun smallStirsAloneAreLightNotActive() { // :58-68
        val records = fillerNight(mapOf(40 to listOf(0, 0, 32, 0, 0), 41 to listOf(0, 16, 32, 0, 0)))
        val timeline = BulkSleep.motionTimeline(records)
        assertEquals(
            listOf(1f, 1f, 1f, 1f, 1f), timeline.subList(41 * 5, 41 * 5 + 5).map { it.movement },
            "a 48-unit tail is a stir; only a per-set rank could call it an awakening",
        )
        assertEquals(
            16f, BulkSleep.motionIntensityFallbackMagnitudes(records, degenerate = false, absoluteActiveCut = 0)[41],
            "fixture sanity: the legacy rank DID promote it — this test would be vacuous if the fixture no longer reproduced the artefact",
        )
    }

    @Test
    fun movementChartDoesNotReportAllZeroWhenIntensityStillHasMotion() { // :70-79
        val records = fillerNight(mapOf(20 to listOf(0, 0, 8, 0, 0), 50 to listOf(0, 16, 32, 0, 0), 90 to listOf(32, 64, 0, 0, 0)))
        val summary = SleepDetailMetrics.movementSummary(records)

        assertEquals(records.size, summary.total)
        assertEquals(3, summary.light + summary.active)
        assertTrue(summary.active > 0)
    }

    @Test
    fun consecutiveIntensityMovementProducesAnAwakeInterval() { // :84-92
        val records = fillerNight(mapOf(60 to listOf(0, 200, 200, 0, 0), 61 to listOf(0, 200, 200, 0, 0)))
        val summary = SleepStaging.summary(SleepStaging.classify(records)).minutes

        assertTrue(summary.awake > 0, "measured movement must prevent asleep == in-bed on a filler-motion night")
        assertTrue(summary.asleep < summary.inBed)
    }

    @Test
    fun zeroIntensityKeepsAQuietFillerNightOnPrimaryPath() { // :94-99
        val records = fillerNight()
        assertFalse(BulkSleep.usesMotionIntensityFallback(records))
        assertTrue(SleepDetailMetrics.movement(records).all { it.level == SleepDetailMetrics.MovementLevel.STILL })
    }

    @Test
    fun anyRealPrimaryMotionKeepsNormalSignalAuthoritative() { // :101-108
        val records = fillerNight(mapOf(20 to listOf(0, 0, 64, 0, 0), 21 to listOf(0, 0, 64, 0, 0))).toMutableList()
        records[10] = record(records[10].counter, primary = listOf(1, 1, 8, 1, 1), intensity = listOf(0, 0, 0, 0, 0))
        assertFalse(BulkSleep.usesMotionIntensityFallback(records))
    }
}
