package io.github.opencircuit.app

import io.github.opencircuit.app.live.LivePoint
import io.github.opencircuit.app.live.LiveSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The live readout's buffer: the chart's points (the newest 120, none older than 90 s before the
 * newest) and the low–high of every reading in the measurement, which the chart's trimming must
 * not shrink. Upstream: `ios/OpenCircuit/Design/LivelineCharts.swift:206-223` @ b1c2fdd, and the
 * whole-measurement range of upstream 6e93d53.
 */
class LiveSessionTest {

    @Test
    fun keepsTheNewest120Points() {
        val session = LiveSession()
        // 130 readings 100 ms apart: all inside the 90 s window, so only the count trims.
        repeat(130) { i -> session.add(atMillis = i * 100L, value = 60 + i % 5) }

        val points = session.snapshot().points
        assertEquals(120, points.size)
        assertEquals(LivePoint(atMillis = 1_000L, value = 60), points.first(), "the 10 oldest were dropped")
        assertEquals(LivePoint(atMillis = 12_900L, value = 64), points.last())
    }

    @Test
    fun dropsPointsMoreThan90SecondsOlderThanTheNewest() {
        val session = LiveSession()
        session.add(atMillis = 0L, value = 70)
        session.add(atMillis = 1_000L, value = 71)
        session.add(atMillis = 90_000L, value = 72) // 0 is exactly 90 s older: kept
        assertEquals(listOf(0L, 1_000L, 90_000L), session.snapshot().points.map { it.atMillis })

        session.add(atMillis = 90_001L, value = 73) // now 0 is 90.001 s older: dropped

        assertEquals(listOf(1_000L, 90_000L, 90_001L), session.snapshot().points.map { it.atMillis })
    }

    @Test
    fun theRangeNeedsTwoReadingsAndCoversTheWholeMeasurement() {
        val session = LiveSession()
        assertNull(session.snapshot().range)
        session.add(atMillis = 0L, value = 48)
        assertNull(session.snapshot().range, "one reading spans nothing")

        // 200 more readings 2 s apart: the 48 leaves the chart (window and count) but not the range.
        repeat(200) { i -> session.add(atMillis = (i + 1) * 2_000L, value = 60 + i % 7) }

        val snapshot = session.snapshot()
        assertEquals(60, snapshot.points.minOf { it.value }, "the chart no longer holds the low")
        assertEquals(48..66, snapshot.range)
    }

    @Test
    fun resetStartsOverAndTheSameInstanceIsReusable() {
        val session = LiveSession()
        session.add(atMillis = 0L, value = 97)
        session.add(atMillis = 2_000L, value = 95)

        session.reset()
        assertEquals(emptyList(), session.snapshot().points)
        assertNull(session.snapshot().range)

        session.add(atMillis = 500_000L, value = 61)
        session.add(atMillis = 502_000L, value = 64)
        assertEquals(61..64, session.snapshot().range, "nothing from before the reset")
        assertEquals(2, session.snapshot().points.size)
    }

    @Test
    fun aSnapshotIsNotChangedByLaterReadings() {
        val session = LiveSession()
        session.add(atMillis = 0L, value = 60)
        val before = session.snapshot()

        session.add(atMillis = 2_000L, value = 90)

        assertEquals(1, before.points.size)
        assertNull(before.range)
    }
}
