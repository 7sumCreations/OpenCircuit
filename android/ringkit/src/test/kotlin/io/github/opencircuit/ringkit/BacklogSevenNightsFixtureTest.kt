package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards `backlog-7-nights/backlog.txt`, the multi-night backlog a sync that has waited a week
 * drains: seven synthetic nights stitched into one counter-continuous run, idle records between
 * them. It is checked against its source mechanically — every night's records are byte-for-byte a
 * synthetic night of `sleep-differential/inputs.txt` moved in time, every other record is the idle
 * template — so the file can hold nothing but already-committed synthetic data.
 */
class BacklogSevenNightsFixtureTest {

    private fun load() = BacklogSevenNights.load()

    @Test
    fun theBacklogIsSevenNightsOnOneUnbrokenEpochGrid() {
        val b = load()

        assertEquals("UTC", b.zone.id)
        assertEquals(7, b.nights.size)
        assertEquals(3654, b.records.size)
        assertEquals(562, b.temps.size)
        b.records.zipWithNext().forEach { (a, c) -> assertEquals(150L, c.counter - a.counter, "gap after counter ${a.counter}") }
        assertEquals(Instant.parse("2026-06-07T21:55:11Z"), b.records.first().date())
        assertEquals(Instant.parse("2026-06-14T06:07:41Z"), b.records.last().date())
    }

    @Test
    fun everyNightIsItsSyntheticSourceMovedInTimeAndEveryOtherRecordIsIdle() {
        val b = load()
        val sources = SleepDifferentialFixtures.inputs().associateBy { it.id }
        var inNights = 0
        var tempsMatched = 0

        for ((id, first, last) in b.nights) {
            val source = assertNotNull(sources[id], id)
            assertTrue(id.startsWith("synthetic-") && source.shape == "normal", "$id is a synthetic normal night")
            val shift = first - source.records.first().counter
            val placed = b.records.filter { it.counter in first..last }
            assertEquals(source.records.size, placed.size, id)
            source.records.zip(placed).forEach { (s, p) ->
                assertEquals(s.counter + shift, p.counter, id)
                assertEquals(s.raw.drop(4), p.raw.drop(4), "$id: only the counter moves")
            }
            val moved = source.temps.map { TemperatureSample(it.time.plusSeconds(shift), it.celsius) }
            assertTrue(b.temps.containsAll(moved), "$id: its temperature samples move with it")
            tempsMatched += moved.size
            inNights += placed.size
        }
        assertEquals(b.temps.size, tempsMatched, "no temperature sample from anywhere else")
        val between = b.records.filter { r -> b.nights.none { (_, first, last) -> r.counter in first..last } }
        assertEquals(b.records.size - inNights, between.size)
        assertTrue(between.isNotEmpty() && between.all { it.layout == BulkRecord.Layout.IDLE }, "the days between nights are idle")
    }
}
