package io.github.opencircuit.store.codec

import io.github.opencircuit.ringkit.SleepProvenance
import io.github.opencircuit.ringkit.SleepProvenanceBreakdown
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import io.github.opencircuit.ringkit.SleepStaging
import io.github.opencircuit.ringkit.healthPublishable
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A sleep segment's stored form: provenance optional on read, omitted when measured, and an
 * unreadable label costing the label, never the segment.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepProvenanceTests.swift
 * (@ b1c2fdd), the six stored-form tests of `SleepSegmentCodableProvenanceTests` (`:461`, `:471`,
 * `:479`) and `SleepSegmentProvenanceDecodeTests` (`:501`, `:518`, `:528`); the file's other 29
 * tests are in `:ringkit`'s `SleepProvenanceTest`. Upstream's literal JSON is translated instant
 * for instant: dates there are seconds since 2001-01-01, written here as the same instant in epoch
 * milliseconds (757382400 → 1735689600000; 700000000 → 1678307200000). `:528` decodes a single
 * segment; the stored form is the array, so it round-trips a one-segment array.
 */
class SleepProvenanceStoredFormTest {

    private fun ms(epochMs: Long): Instant = Instant.ofEpochMilli(epochMs)

    @Test
    fun legacyJsonWithoutProvenanceDecodes() {
        val json = """[{"start":1735689600000,"end":1735693200000,"stage":"asleepCore"}]"""
        val out = readable(SleepSegmentCodec.decode(json))
        assertEquals(1, out.size)
        assertEquals(SleepProvenance.MEASURED, out.first().provenance)
    }

    @Test
    fun measuredSegmentsEncodeWithoutTheProvenanceKey() {
        val seg = SleepSegment(start = ms(0), end = ms(60_000), stage = SleepStage.AWAKE)
        val text = SleepSegmentCodec.encode(listOf(seg))
        assertFalse(text.contains("provenance"), "a measured segment's stored form carries no provenance key")
    }

    @Test
    fun assertedSegmentRoundTrips() {
        val seg = SleepSegment(start = ms(0), end = ms(60_000), stage = SleepStage.ASLEEP_CORE, provenance = SleepProvenance.ASSERTED)
        assertEquals(listOf(seg), readable(SleepSegmentCodec.decode(SleepSegmentCodec.encode(listOf(seg)))))
    }

    @Test
    fun anUnrecognisedProvenanceDegradesToCoverageUnknownInsteadOfFailingTheArray() {
        val json = """
            [{"start": 1678307200000, "end": 1678310800000, "stage": "asleepCore", "provenance": "fromTheFuture"},
             {"start": 1678310800000, "end": 1678314400000, "stage": "asleepDeep"}]
        """.trimIndent()
        val segs = readable(SleepSegmentCodec.decode(json))
        assertEquals(2, segs.size, "one unreadable label must not drop two hours of sleep")
        assertEquals(listOf(SleepProvenance.ASSERTED_COVERAGE_UNKNOWN, SleepProvenance.MEASURED), segs.map { it.provenance })
        // Still published — the degrade costs a caveat, never the user's sleep.
        assertEquals(7200.0, SleepStaging.totalAsleep(segs.healthPublishable).seconds.toDouble(), 1.0)
        // …but never counted as a measurement.
        assertEquals(3600.0, SleepProvenanceBreakdown(segs).measuredAsleep, 1.0)
    }

    @Test
    fun aProvenanceOfTheWrongTypeCostsOnlyTheLabel() {
        val json = """
            [{"start": 1678307200000, "end": 1678310800000, "stage": "asleepCore", "provenance": 3},
             {"start": 1678310800000, "end": 1678314400000, "stage": "asleepDeep"}]
        """.trimIndent()
        val segs = readable(SleepSegmentCodec.decode(json))
        assertEquals(2, segs.size, "a malformed label must not drop two hours of sleep")
        assertEquals(listOf(SleepProvenance.ASSERTED_COVERAGE_UNKNOWN, SleepProvenance.MEASURED), segs.map { it.provenance })
    }

    @Test
    fun everyKnownProvenanceStillRoundTrips() {
        val t = ms(700_000_000_000L)
        for (p in SleepProvenance.entries) {
            val seg = SleepSegment(start = t, end = t.plusSeconds(600), stage = SleepStage.AWAKE, provenance = p)
            val back = readable(SleepSegmentCodec.decode(SleepSegmentCodec.encode(listOf(seg))))
            assertEquals(listOf(seg), back, "${p.rawValue} did not survive a round trip")
        }
    }
}
