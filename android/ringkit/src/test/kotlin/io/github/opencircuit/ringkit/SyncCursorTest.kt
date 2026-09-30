package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Per-metric forward-only sync watermark: re-syncs push only samples strictly newer than the
 * last one written for that metric, and a staged selection leaves the cursor untouched until the
 * caller adopts it after a durable save.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SyncCursorTests.swift (@ b1c2fdd)
 * — 7 of 8 tests. Not ported: `testCursorRoundTripsThroughCodable` (:76); the cursor's stored
 * form belongs to the persistence layer, which has not been built yet.
 */
class SyncCursorTest {
    private val t0 = Instant.ofEpochSecond(1000)
    private val t1 = Instant.ofEpochSecond(2000)
    private val t2 = Instant.ofEpochSecond(3000)

    private fun hr(start: Instant, value: Double) = QuantitySample(kind = MetricKind.HEART_RATE, start = start, value = value)

    @Test
    fun freshCursorTreatsEverythingAsNew() { // :9-13
        val c = SyncCursor()
        assertNull(c.last(MetricKind.HEART_RATE))
        assertTrue(c.isNew(MetricKind.HEART_RATE, t0))
    }

    @Test
    fun selectNewSortsAndAdvancesPerKind() { // :15-26
        val c = SyncCursor()
        val fresh = c.selectNew(
            listOf(
                hr(t1, 60.0),
                hr(t0, 58.0),
                QuantitySample(kind = MetricKind.SPO2, start = t1, value = 0.97),
            ),
        )
        assertEquals(3, fresh.size)
        assertEquals(t0, fresh.first().start) // sorted
        assertEquals(t1, c.last(MetricKind.HEART_RATE))
        assertEquals(t1, c.last(MetricKind.SPO2)) // independent
    }

    @Test
    fun resyncDropsSeenKeepsNewerAndNeverGoesBackward() { // :28-38
        val c = SyncCursor()
        c.selectNew(listOf(hr(t1, 60.0)))
        val again = c.selectNew(
            listOf(
                hr(t1, 61.0), // == cursor
                hr(t2, 62.0), // newer
            ),
        )
        assertEquals(listOf(t2), again.map { it.start })
        c.advance(MetricKind.HEART_RATE, to = t0)
        assertEquals(t2, c.last(MetricKind.HEART_RATE))
    }

    @Test
    fun selectNewStagedDoesNotMutateUntilApplied() { // :40-48
        val c = SyncCursor()
        val (fresh, advanced) = c.selectNewStaged(listOf(hr(t1, 60.0), hr(t0, 58.0)))
        assertEquals(listOf(t0, t1), fresh.map { it.start }) // sorted, both fresh
        assertNull(c.last(MetricKind.HEART_RATE)) // original cursor untouched
        assertEquals(t1, advanced.last(MetricKind.HEART_RATE)) // advance only on the returned copy
    }

    @Test
    fun stagedAdvanceIgnoredLeavesSamplesRetriable() { // :50-56
        // Simulate a failed commit: stage the advance but DON'T adopt `advanced`. The original
        // cursor must still treat the same samples as new so they're retried.
        val c = SyncCursor()
        c.selectNewStaged(listOf(hr(t1, 60.0)))
        assertTrue(c.isNew(MetricKind.HEART_RATE, t1))
    }

    @Test
    fun advancedKindsReportsOnlyMovedCursors() { // :58-67
        val base = SyncCursor()
        // Upstream `var moved = base` copies a Swift struct; the Kotlin cursor copies explicitly.
        val moved = base.copy()
        moved.selectNew(
            listOf(
                hr(t1, 60.0),
                QuantitySample(kind = MetricKind.SPO2, start = t1, value = 0.97),
            ),
        )
        assertEquals(setOf(MetricKind.HEART_RATE, MetricKind.SPO2), moved.advancedKinds(since = base).toSet())
        assertTrue(moved.advancedKinds(since = moved).isEmpty()) // no diff with self
    }

    @Test
    fun unitsMatchHealthKitMapping() { // :69-73
        assertEquals("fraction", MetricKind.SPO2.unit)
        assertEquals("ms", MetricKind.HRV_SDNN.unit)
        assertEquals("degC", MetricKind.TEMPERATURE.unit)
    }
}
