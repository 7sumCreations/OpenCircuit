package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * THE FROZEN "PROVEN HOLE" THAT LATER FILLS — the 2026-08-24 tester night, as a fixture: epoch-second
 * timestamps of when the ring was and was not recording, plus the stage labels the edit path produced.
 * No physiological payload. Nine records arrived after she saved (02:48:53 … 03:08:53, contiguous at
 * the 150 s cadence), so 1350 of the 14323 "proven-hole" seconds were measurable data the app held
 * under an hour later.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepProvenanceRederivationTests.swift
 * (@ b1c2fdd) — all 9 tests.
 */
class SleepProvenanceRederivationTest {

    private fun d(e: Long): Instant = Instant.ofEpochSecond(e)
    private fun sec(x: Duration): Double = SleepStaging.seconds(x)
    private fun total(segs: List<SleepSegment>): Duration = segs.fold(Duration.ZERO) { acc, s -> acc.plus(s.duration) }

    // 2026-08-24 Europe/Paris, from the export.
    private val assertedFill: SleepSegment
        get() = SleepSegment(d(1_787_532_317), d(1_787_546_640), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED) // 02:45:17 → 06:44:00

    /** The nine records that arrived AFTER she saved, as coverage. */
    private val grownArchive: MeasuredCoverage
        get() = MeasuredCoverage.ofRecordDates(
            listOf(
                d(1_787_532_533), d(1_787_532_683), d(1_787_532_833), d(1_787_532_983),
                d(1_787_533_133), d(1_787_533_283), d(1_787_533_433), d(1_787_533_583),
                d(1_787_533_733),
            ),
            Duration.ofSeconds(150),
        )

    /** The archive as it stood when she pressed Save: nothing past 02:42:47. */
    private val archiveAtSave: MeasuredCoverage
        get() = MeasuredCoverage.ofRecordDates(listOf(d(1_787_532_167)), Duration.ofSeconds(150))

    // MARK: the defect

    @Test
    fun herFrozenHoleIsScoredAgainstTheArchiveThatEXISTEDWhenSheSaved() {
        // The precondition: at Save time the whole 14323 s span genuinely was a proven hole.
        assertNull(SleepProvenanceRederivation.upgraded(listOf(assertedFill), archiveAtSave), "with only the 02:42:47 record in hand there is nothing to upgrade")
        assertEquals(Duration.ofSeconds(14_323), assertedFill.duration, "14323 s, exactly as her export carries it")
    }

    @Test
    fun theLaterRecordsReclaim1350SecondsOfTheHole() {
        val out = assertNotNull(SleepProvenanceRederivation.upgraded(listOf(assertedFill), grownArchive))
        val reclaimed = out.filter { it.provenance == SleepProvenance.ASSERTED_OVER_MEASURED }
        assertEquals(1, reclaimed.size, "the nine records are contiguous — one merged span")
        assertEquals(d(1_787_532_533), reclaimed.first().start, "02:48:53, the first late record")
        assertEquals(d(1_787_533_883), reclaimed.first().end, "03:11:23, 150 s past the last one")
        assertEquals(Duration.ofSeconds(1350), total(reclaimed), "1350 s of the 'proven hole' was measurable data we held under an hour later")

        assertEquals(1350.0, SleepProvenanceRederivation.upgradedAsleepSeconds(before = listOf(assertedFill), after = out), 0.5)
    }

    @Test
    fun theWEARERSWINDOWIsPreservedToTheSecond() {
        val out = assertNotNull(SleepProvenanceRederivation.upgraded(listOf(assertedFill), grownArchive))
        // Extend-only means the LABELS change and nothing else. Her edges, her stage, her total.
        assertEquals(assertedFill.start, out.minOf { it.start }, "her asserted onset moved")
        assertEquals(assertedFill.end, out.maxOf { it.end }, "her asserted wake moved")
        assertTrue(out.all { it.stage == SleepStage.ASLEEP_CORE }, "a stage was rewritten")
        assertEquals(sec(assertedFill.duration), sec(total(out)), 0.5, "the pieces must tile the span exactly — no minute added or dropped")
        assertEquals(
            sec(SleepStaging.totalAsleep(listOf(assertedFill))),
            sec(SleepStaging.totalAsleep(out)),
            0.5,
            "the displayed night is untouched (clause 1)",
        )
    }

    @Test
    fun aSecondPassOverTheSameArchiveIsANoOp() {
        val once = assertNotNull(SleepProvenanceRederivation.upgraded(listOf(assertedFill), grownArchive))
        assertNull(SleepProvenanceRederivation.upgraded(once, grownArchive), "null is what stops every drain re-writing the row and the health store")
    }

    // MARK: the direction of travel — this must not be able to resurrect the retention shrink

    @Test
    fun nothingAlreadyMEASUREDCanEverBeDowngraded() {
        // The 403.0 → 0.0 failure is a SHRINK. This pass cannot express one.
        val night = listOf(
            SleepSegment(d(1_787_520_167), d(1_787_532_287), SleepStage.ASLEEP_CORE),
            SleepSegment(d(1_787_517_919), d(1_787_520_167), SleepStage.AWAKE, SleepProvenance.ASSERTED_OVER_MEASURED),
            SleepSegment(d(1_787_532_287), d(1_787_532_317), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED_COVERAGE_UNKNOWN),
        )
        // An archive that has rolled two days past this night.
        val rolled = MeasuredCoverage.ofRecordDates(listOf(d(1_787_720_000), d(1_787_720_150)), Duration.ofSeconds(150))
        assertNull(SleepProvenanceRederivation.upgraded(night, rolled), "an archive that cannot speak about this night must change nothing")
        assertNull(SleepProvenanceRederivation.upgraded(night, MeasuredCoverage.EMPTY))

        // …and even an archive that DOES cover the night leaves these three as they are: none is asserted.
        val dense = MeasuredCoverage(listOf(DateInterval(d(1_787_517_919), d(1_787_546_640))))
        assertNull(SleepProvenanceRederivation.upgraded(night, dense), "only a PROVEN hole is re-scored; a measured or unknown label is left alone")
    }

    @Test
    fun groundThatIsSTILLEmptyStaysAsserted() {
        // Her 12907 s tail (03:11:23 → 06:44:00) still holds no records, and must still be her own account.
        val out = assertNotNull(SleepProvenanceRederivation.upgraded(listOf(assertedFill), grownArchive))
        val stillAsserted = out.filter { it.provenance == SleepProvenance.ASSERTED }
        assertEquals(14_323.0 - 1350, sec(total(stillAsserted)), 0.5)
        assertEquals(d(1_787_546_640), stillAsserted.last().end, "her 06:44 wake is still asserted")

        val b = SleepProvenanceBreakdown(out)
        assertEquals(12_973.0, b.assertedAsleep, 0.5)
        assertEquals(1350.0, b.measuredAsleep, 0.5)
        assertEquals(14_323.0, b.displayedAsleep, 0.5, "the card total never moves")
    }

    // MARK: end to end on her real night — re-derive, then publish

    /**
     * HER STORED HYPNOGRAM, VERBATIM from the export (21 segments: timestamps + stage labels +
     * provenance codes; no physiological payload). It carries no in-bed layer because the export
     * strips one, so do not quote coverage or in-bed minutes off it.
     */
    private val herStoredNight: List<SleepSegment>
        get() {
            fun s(a: Long, b: Long, stage: SleepStage, p: SleepProvenance = SleepProvenance.MEASURED) = SleepSegment(d(a), d(b), stage, p)
            return listOf(
                s(1_787_517_919, 1_787_520_167, SleepStage.AWAKE, SleepProvenance.ASSERTED_OVER_MEASURED),
                s(1_787_520_167, 1_787_521_367, SleepStage.ASLEEP_CORE),
                s(1_787_521_367, 1_787_521_667, SleepStage.ASLEEP_REM),
                s(1_787_521_667, 1_787_521_817, SleepStage.ASLEEP_CORE),
                s(1_787_521_817, 1_787_523_017, SleepStage.ASLEEP_REM),
                s(1_787_523_017, 1_787_523_317, SleepStage.ASLEEP_CORE),
                s(1_787_523_317, 1_787_523_467, SleepStage.AWAKE),
                s(1_787_523_467, 1_787_523_767, SleepStage.ASLEEP_CORE),
                s(1_787_523_767, 1_787_524_217, SleepStage.ASLEEP_DEEP),
                s(1_787_524_217, 1_787_524_967, SleepStage.ASLEEP_CORE),
                s(1_787_524_967, 1_787_525_117, SleepStage.AWAKE),
                s(1_787_525_117, 1_787_525_417, SleepStage.ASLEEP_CORE),
                s(1_787_525_417, 1_787_525_867, SleepStage.ASLEEP_DEEP),
                s(1_787_525_867, 1_787_526_167, SleepStage.ASLEEP_CORE),
                s(1_787_526_167, 1_787_526_317, SleepStage.AWAKE),
                s(1_787_526_317, 1_787_528_717, SleepStage.ASLEEP_CORE),
                s(1_787_528_717, 1_787_529_167, SleepStage.ASLEEP_REM),
                s(1_787_529_167, 1_787_529_467, SleepStage.ASLEEP_CORE),
                s(1_787_529_467, 1_787_532_287, SleepStage.ASLEEP_DEEP),
                s(1_787_532_287, 1_787_532_317, SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED_OVER_MEASURED),
                s(1_787_532_317, 1_787_546_640, SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED),
            )
        }

    @Test
    fun theStoredSplitReproducesHerExportedProvenanceSummary() {
        // If this drifts, every "before" number below is unmoored — so pin it to the export.
        val b = SleepProvenanceBreakdown(herStoredNight)
        assertEquals(11_700.0, b.measuredAsleep, 0.5, "measuredAsleepSec, as exported")
        assertEquals(14_323.0, b.assertedAsleep, 0.5, "assertedAsleepSec, as exported")
        assertEquals(434.0, b.displayedAsleep / 60, 0.5, "the 434-minute card headline")
    }

    @Test
    fun afterRederivationHerNightReachesHealthWholeWithTheAssertedPartTagged() {
        val upgraded = assertNotNull(SleepProvenanceRederivation.upgraded(herStoredNight, grownArchive))
        val b = SleepProvenanceBreakdown(upgraded)
        assertEquals(13_050.0, b.measuredAsleep, 0.5, "11700 + the 1350 s reclaimed")
        assertEquals(12_973.0, b.assertedAsleep, 0.5, "14323 − 1350")
        assertEquals(434.0, b.displayedAsleep / 60, 0.5, "her card total is the same before and after — only the SPLIT moved")

        // …and what the health store receives: the whole 434 minutes, 216 of them as her own entry.
        val publication = upgraded.healthPublication
        assertEquals(434.0, sec(SleepStaging.totalAsleep(publication.published)) / 60, 0.5, "the night she corrected reaches Health in full — the report this fixes")
        assertEquals(216.2, sec(SleepStaging.totalAsleep(publication.userEntered)) / 60, 0.2)
        assertEquals(217.5, sec(SleepStaging.totalAsleep(publication.measured)) / 60, 0.2)
        assertTrue(publication.withheld.isEmpty())
        assertTrue(upgraded.withheldSpans.isEmpty(), "a published span reported as withheld duplicates the night on re-edit")

        // Without the re-derivation the same night publishes the same 434 minutes, but 239 of them are
        // attributed to her rather than 216 — the 1350 s the ring HAD recorded filed under her name.
        val frozen = herStoredNight.healthPublication
        assertEquals(238.7, sec(SleepStaging.totalAsleep(frozen.userEntered)) / 60, 0.2)
    }

    @Test
    fun anUnEDITEDNightIsNeverTouched() {
        // Staging emits only measured segments, so the ordinary path has no asserted label to find.
        val staged = listOf(SleepSegment(d(1_787_520_167), d(1_787_532_287), SleepStage.ASLEEP_CORE))
        assertNull(SleepProvenanceRederivation.upgraded(staged, MeasuredCoverage(listOf(DateInterval(d(1_787_520_167), d(1_787_532_287))))))
    }
}
