package io.github.opencircuit.store.codec

import io.github.opencircuit.ringkit.SleepProvenance
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The sleep-segment list's stored form beyond upstream's six tests.
 *
 * Kotlin-only. Upstream's hand-written decoder (`Metrics.swift:182-207` @ b1c2fdd) reads a missing
 * or `null` provenance as measured and any other unreadable value as coverage-unknown — measured on
 * Swift 6.3.2 for `"fromTheFuture"`, `3`, `{}`, `true`, `""` and `null`. An unknown stage or a
 * missing start / end / stage fails the whole array, as Foundation does.
 */
class SleepSegmentCodecTest {

    private fun ms(v: Long): Instant = Instant.ofEpochMilli(v)

    private fun oneSegment(provenanceJson: String): String =
        """[{"start":0,"end":60000,"stage":"awake","provenance":$provenanceJson}]"""

    @Test
    fun aNullProvenanceReadsAsMeasured() {
        // Swift's decodeNil check: an explicit null is "no label", like a missing key.
        assertEquals(SleepProvenance.MEASURED, readable(SleepSegmentCodec.decode(oneSegment("null"))).single().provenance)
    }

    @Test
    fun anExplicitKnownLabelIsRead() {
        assertEquals(SleepProvenance.MEASURED, readable(SleepSegmentCodec.decode(oneSegment("\"measured\""))).single().provenance)
        assertEquals(SleepProvenance.ASSERTED, readable(SleepSegmentCodec.decode(oneSegment("\"asserted\""))).single().provenance)
    }

    @Test
    fun everyOtherUnreadableLabelCostsOnlyTheLabel() {
        for (p in listOf("{}", "true", "\"\"", "[]", "\"Measured\"", "1.5", "\"assertedcoverageunknown\"")) {
            val segs = readable(SleepSegmentCodec.decode(oneSegment(p)))
            assertEquals(SleepSegment(ms(0), ms(60_000), SleepStage.AWAKE, SleepProvenance.ASSERTED_COVERAGE_UNKNOWN), segs.single(), p)
        }
    }

    @Test
    fun anUnknownStageFailsTheWholeArray() {
        val raw = """[{"start":0,"end":60000,"stage":"awake"},{"start":60000,"end":120000,"stage":"nap"}]"""
        assertUnreadable(SleepSegmentCodec.decode(raw), raw)
    }

    @Test
    fun aMissingStartEndOrStageFailsTheWholeArray() {
        for (raw in listOf(
            """[{"end":60000,"stage":"awake"}]""",
            """[{"start":0,"stage":"awake"}]""",
            """[{"start":0,"end":60000}]""",
            """[{"start":0,"end":60000,"stage":null}]""",
            """{"start":0,"end":60000,"stage":"awake"}""",
        )) {
            assertUnreadable(SleepSegmentCodec.decode(raw), raw)
        }
    }

    @Test
    fun theStoredFormIsEpochMillisecondsWithTheLabelOnlyWhenNotMeasured() {
        val segs = listOf(
            SleepSegment(ms(0), ms(60_000), SleepStage.AWAKE),
            SleepSegment(ms(60_000), ms(90_000), SleepStage.ASLEEP_REM, SleepProvenance.ASSERTED_OVER_MEASURED),
        )
        assertEquals(
            """[{"start":0,"end":60000,"stage":"awake"},""" +
                """{"start":60000,"end":90000,"stage":"asleepREM","provenance":"assertedOverMeasured"}]""",
            SleepSegmentCodec.encode(segs),
        )
        assertEquals("[]", SleepSegmentCodec.encode(emptyList()))
        assertEquals(emptyList(), readable(SleepSegmentCodec.decode("[]")))
    }

    @Test
    fun aSubMillisecondInstantIsWrittenCutTowardThePast() {
        val seg = SleepSegment(Instant.ofEpochSecond(-1L, 999_999_999), Instant.ofEpochSecond(0L, 1_999_999), SleepStage.AWAKE)
        val back = readable(SleepSegmentCodec.decode(SleepSegmentCodec.encode(listOf(seg)))).single()
        assertEquals(ms(-1), back.start)
        assertEquals(ms(1), back.end)
    }
}
