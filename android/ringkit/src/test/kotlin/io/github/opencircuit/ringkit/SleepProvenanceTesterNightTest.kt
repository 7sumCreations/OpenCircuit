package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// THE TWO DEVICE-PROVEN TESTER NIGHTS, COMMITTED AS FIXTURES: epoch-second TIMESTAMPS of when the
// ring was and was not recording, and the STAGE LABELS the shipped staging pipeline produced. No
// physiological payload and no raw capture. Each test runs BOTH arms on identical inputs: the OFF arm
// (`coverage = null`, the kill switch) reproduces the shipped card, and every test asserts the old
// number is wrong and the new one right.
//
// Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepProvenanceTesterNightTests.swift
// (@ b1c2fdd) — both classes, all 14 tests.

private fun d(e: Long): Instant = Instant.ofEpochSecond(e)
private fun sec(x: Duration): Double = SleepStaging.seconds(x)
private fun iv(a: Long, b: Long) = DateInterval(d(a), d(b))
private val asleepStages = setOf(SleepStage.ASLEEP_CORE, SleepStage.ASLEEP_DEEP, SleepStage.ASLEEP_REM)

class SleepProvenanceTesterNight0818Test {

    // A Gen 2 Air FR04.009 night, Europe/Paris, 388 records, app build 45. Stored by the app: 403 asleep
    // / 36 awake / efficiency 0.9179954441913439 / score 71, on a night whose own exported coverage
    // fraction is 0.377.

    /** The seven merged spans the ring actually recorded across. The sixth ends 02:37:32 and the seventh begins 06:38:57. */
    private val coverage: MeasuredCoverage
        get() = MeasuredCoverage(
            listOf(
                iv(1_786_960_827, 1_786_965_327), // 12:00:27 -> 13:15:27
                iv(1_786_965_331, 1_786_984_681), // 13:15:31 -> 18:38:01
                iv(1_786_989_748, 1_786_996_648), // 20:02:28 -> 21:57:28
                iv(1_786_996_649, 1_786_999_542), // 21:57:29 -> 22:45:42
                iv(1_786_999_587, 1_787_012_637), // 22:46:27 -> 02:23:57
                iv(1_787_012_702, 1_787_013_452), // 02:25:02 -> 02:37:32
                iv(1_787_027_937, 1_787_029_137), // 06:38:57 -> 06:58:57   <- after the hole
            ),
        )

    /** What staging produced: in-bed 22:24:25 -> 02:37:02, 253 in-bed / 249 asleep / 3 awake. */
    private val stagedBase: List<SleepSegment>
        get() {
            fun s(a: Long, b: Long, st: SleepStage) = SleepSegment(d(a), d(b), st)
            return listOf(
                s(1_786_998_265, 1_787_013_422, SleepStage.IN_BED),
                s(1_786_998_265, 1_786_998_342, SleepStage.AWAKE),
                s(1_786_998_342, 1_786_998_792, SleepStage.ASLEEP_CORE),
                s(1_786_998_792, 1_786_999_092, SleepStage.ASLEEP_REM),
                s(1_786_999_092, 1_787_002_737, SleepStage.ASLEEP_CORE),
                s(1_787_002_737, 1_787_003_637, SleepStage.ASLEEP_REM),
                s(1_787_003_637, 1_787_003_821, SleepStage.ASLEEP_CORE),
                s(1_787_003_821, 1_787_003_937, SleepStage.AWAKE),
                s(1_787_003_937, 1_787_005_437, SleepStage.ASLEEP_CORE),
                s(1_787_005_437, 1_787_006_037, SleepStage.ASLEEP_DEEP),
                s(1_787_006_037, 1_787_007_987, SleepStage.ASLEEP_CORE),
                s(1_787_007_987, 1_787_009_037, SleepStage.ASLEEP_DEEP),
                s(1_787_009_037, 1_787_009_487, SleepStage.ASLEEP_CORE),
                s(1_787_009_487, 1_787_009_937, SleepStage.ASLEEP_REM),
                s(1_787_009_937, 1_787_010_237, SleepStage.ASLEEP_CORE),
                s(1_787_010_237, 1_787_011_137, SleepStage.ASLEEP_DEEP),
                s(1_787_011_137, 1_787_011_287, SleepStage.ASLEEP_CORE),
                s(1_787_011_287, 1_787_012_037, SleepStage.ASLEEP_REM),
                s(1_787_012_037, 1_787_012_337, SleepStage.ASLEEP_CORE),
                s(1_787_012_337, 1_787_013_002, SleepStage.ASLEEP_REM),
                s(1_787_013_002, 1_787_013_422, SleepStage.ASLEEP_CORE),
            )
        }

    /** What the tester dragged: in bed 23:24, asleep from 00:00, awake at 06:43 (+02:00). */
    private val times: SleepEdit.Times
        get() = SleepEdit.Times(inBedStart = d(1_787_001_840), sleepOnset = d(1_787_004_000), sleepWake = d(1_787_028_180))

    private val off: List<SleepSegment> get() = SleepEdit.recompute(stagedBase, times, coverage = null)
    private val on: List<SleepSegment> get() = SleepEdit.recompute(stagedBase, times, coverage = coverage)

    // MARK: the defect, reproduced

    @Test
    fun offArmReproducesTheShippedCardToSixteenDigits() {
        val m = SleepStaging.summary(off).minutes
        assertEquals(403L, m.asleep, "the app stored 403 asleep-minutes for this night")
        assertEquals(36L, m.awake)
        assertEquals(0.9179954441913439, SleepStaging.summary(off).efficiency, 1e-15, "the tester's own export carries this efficiency to 16 digits")
    }

    @Test
    fun masterEmitsOneInventedBlockOverTheFourHourHole() {
        // The single segment at the heart of the defect: {asleepCore, 02:37:02 -> 06:43:00}.
        val invented = off.filter { it.stage == SleepStage.ASLEEP_CORE && it.start == d(1_787_013_422) && it.end == d(1_787_028_180) }
        assertEquals(1, invented.size, "the 246-minute fill must still be emitted — display is honoured")
        assertEquals(Duration.ofSeconds(14_758), invented.first().duration, "14758 s, exactly as exported")

        // …and with the kill switch it is indistinguishable from measured sleep.
        assertTrue(off.all { it.provenance == SleepProvenance.MEASURED })
        assertFalse(off.containsAssertedTime)
    }

    // MARK: the fix

    @Test
    fun theInventedBlockIsTaggedAssertedAndTheDisplayIsUNCHANGED() {
        // Clause 1: an assertion wins for display.
        assertEquals(SleepStaging.summary(off).minutes.asleep, SleepStaging.summary(on).minutes.asleep, "the fix must not silently shorten the user's night")

        val b = SleepProvenanceBreakdown(on)
        assertEquals(403.0, b.displayedAsleep / 60, 0.5)

        // Clause 3: but almost none of it is measured.
        assertEquals(241.4, b.assertedAsleep / 60, 0.2, "241.4 of the 403 displayed asleep-minutes are over ground holding no records")
        assertEquals(161.6, b.measuredAsleep / 60, 0.2)
        assertTrue(b.longestUnmeasuredGap > 4 * 3600 - 60, "the 02:37 -> 06:39 hole is over four hours long")
    }

    @Test
    fun efficiencyIsRecomputedOverCoveredGroundOnly() {
        val b = SleepProvenanceBreakdown(on)
        assertEquals(0.448, b.coverageFraction, 0.002)
        val eff = b.efficiency ?: -1.0
        assertEquals(0.8223, eff, 0.001, "0.8223 over ground the ring saw, versus the 0.9180 the app shipped")
        assertTrue(eff < SleepStaging.summary(off).efficiency, "the honest number must be lower than the one built on the invented block")
    }

    @Test
    fun theScoreIsWithheldOnThisNight() {
        // 55 % of this in-bed window holds no data; time asleep is the score's dominant factor.
        assertFalse(SleepProvenanceBreakdown(on).isScorable)
        assertNotNull(SleepProvenanceBreakdown(on).withheldReason)
    }

    @Test
    fun perStageMinutesExcludeTheInventedBlock() {
        val offLight = SleepStaging.summary(off).minutes.light
        val b = SleepProvenanceBreakdown(on)
        assertEquals(329L, offLight, "the app stored 329 light-minutes for this night")
        assertEquals(88.0, b.minutes.light.toDouble(), 2.0, "measured light only — the invented core block is gone from the breakdown")
        assertEquals(43.0, b.minutes.deep.toDouble(), 1.0, "measured deep is untouched")
        assertEquals(31.0, b.minutes.rem.toDouble(), 1.0, "measured REM is untouched")
    }

    /** The number the sleep card's hatch is sized by: measured Light plus asserted Light rebuild the drawn bar. */
    @Test
    fun theAssertedLightIsTheHatchedShare() {
        val b = SleepProvenanceBreakdown(on)
        assertEquals(241.4, b.assertedLight / 60, 0.2, "the 246-minute fill lands in Light, and it is hers")
        assertEquals(0.0, b.assertedDeep)
        assertEquals(0.0, b.assertedREM)
        assertEquals(
            SleepStaging.summary(off).minutes.light.toDouble(),
            b.measuredLight / 60 + b.assertedLight / 60,
            1.5,
            "88 measured + 241 asserted is the 329 the app stored and the card draws",
        )
    }

    // MARK: the health store

    /**
     * Re-baselined upstream 2026-08-24: every asleep sample over the hole must be in the USER-ENTERED
     * bucket — it fails both if one is missing and if one is untagged.
     */
    @Test
    fun theAssertedSleepReachesHealthTaggedAndTheInBedClaimSurvives() {
        val publication = on.healthPublication

        // No PLAIN asleep sample may overlap the hole.
        val hole = iv(1_787_013_452, 1_787_027_937)
        for (seg in publication.measured.filter { it.stage in asleepStages }) {
            assertFalse(seg.start < hole.end && seg.end > hole.start, "an UNTAGGED asleep sample overlaps the 4 h hole: $seg")
        }

        // …the user's in-bed claim is still written.
        val inBedEnd = publication.published.filter { it.stage == SleepStage.IN_BED }.maxOfOrNull { it.end }
        assertEquals(d(1_787_028_180), inBedEnd, "the in-bed envelope must still reach 06:43 — dropping it discards a user claim")

        // …and the store's time asleep now matches the card, with 241.4 of its minutes attributed to her.
        val removed = (sec(SleepStaging.totalAsleep(off)) - sec(SleepStaging.totalAsleep(publication.published))) / 60
        assertEquals(0.0, removed, 0.01, "nothing is retracted from the write any more")
        val tagged = sec(SleepStaging.totalAsleep(publication.userEntered)) / 60
        assertEquals(241.4, tagged, 0.2, "241.4 asleep-minutes reach the health store as the wearer's own entry")
        assertTrue(on.withheldSpans.isEmpty(), "withheld ground drives a DELETE exclusion — publishing while still reporting these spans as withheld duplicates the night on every re-edit")
    }

    // MARK: the measurement survives

    @Test
    fun theRingsOwnStagingIsRecoverableAlongsideTheEdit() {
        // 58.3 min of recorded sleep dropped outside the in-bed window and 36.0 min painted awake: the
        // edit output cannot carry them, which is why the recorded hypnogram is persisted separately.
        val recordedSleep = SleepStaging.sleepWindow(stagedBase)
        assertEquals(d(1_786_998_342), recordedSleep?.onset, "22:25:42 — 58.3 min before the edit's in-bed start")
        assertTrue(assertNotNull(recordedSleep?.onset) < times.inBedStart)

        // The edit output has no segment before 23:24, so this measurement is unreachable from it.
        assertNull(on.minOfOrNull { it.start }?.takeIf { it < times.inBedStart })
        // …and the recorded hypnogram round-trips through the codec unchanged, provenance included.
        assertEquals(stagedBase, SleepHypnogramCodec.decode(SleepHypnogramCodec.encode(stagedBase)))
    }

    // MARK: the export's coverage number cannot see this night's hole

    /**
     * The DETECTED window ends where the records end, so the four-hour hole begins one instant AFTER it
     * closes and coverage measured over it reports a flawless night. Move only the right edge to a wake
     * the recording did not define and the same records score barely half.
     */
    @Test
    fun coverageInTheDetectedWindowCannotSeeTheFourHourHole() {
        val witness = ArrayList<Instant>()
        for (span in coverage.intervals) {
            var t = span.start
            while (t < span.end) {
                witness += t
                t = t.plusSeconds(150)
            }
        }
        val detectedStart = d(1_786_998_265) // 22:24:25 — stagedBase's in-bed start
        val detectedEnd = d(1_787_013_422) // 02:37:02 — and its end, i.e. the last record

        val detected = ExportCoverage.assess(witness, detectedStart, detectedEnd)
        assertEquals(1.0, detected.coverageFraction, 1e-9, "1.0000 on the night the app invented 246 minutes of sleep")
        assertTrue(detected.gaps.isEmpty(), "and not one gap, because the hole is outside")

        val againstHerWake = ExportCoverage.assess(witness, detectedStart, times.sleepWake)
        assertTrue(againstHerWake.coverageFraction < 0.60, "the same records, one denominator the recording did not choose")
        assertEquals(1, againstHerWake.gaps.size)
        assertTrue((againstHerWake.gaps.firstOrNull()?.seconds ?: 0.0) > 4 * 3600, "over four hours, and it was invisible to the number we published")
    }
}

class SleepProvenanceTesterNight0817Test {

    // The same ring, the night before. Stored: 246 asleep / 245 awake / eff 0.5008 / score 19. Her
    // asserted 246-minute sleep window contains ONE epoch, so 100 % of the displayed sleep total is
    // invented — while the 102.5 min the ring actually staged is displayed as awake.

    private val coverage: MeasuredCoverage
        get() = MeasuredCoverage(
            listOf(
                iv(1_786_921_006, 1_786_926_856), // 00:56:46 -> 02:34:16
                iv(1_786_927_034, 1_786_927_184), // 02:37:14 -> 02:39:44   <- the ONE epoch
                iv(1_786_941_771, 1_786_948_971), // 06:42:51 -> 08:42:51
                iv(1_786_948_977, 1_786_960_827), // 08:42:57 -> 12:00:27
            ),
        )

    private val stagedBase: List<SleepSegment>
        get() {
            fun s(a: Long, b: Long, st: SleepStage) = SleepSegment(d(a), d(b), st)
            return listOf(
                s(1_786_921_006, 1_786_927_154, SleepStage.IN_BED),
                s(1_786_921_006, 1_786_921_456, SleepStage.ASLEEP_CORE),
                s(1_786_921_456, 1_786_921_906, SleepStage.ASLEEP_REM),
                s(1_786_921_906, 1_786_923_106, SleepStage.ASLEEP_CORE),
                s(1_786_923_106, 1_786_924_306, SleepStage.ASLEEP_DEEP),
                s(1_786_924_306, 1_786_925_806, SleepStage.ASLEEP_CORE),
                s(1_786_925_806, 1_786_926_556, SleepStage.ASLEEP_REM),
                s(1_786_926_556, 1_786_927_154, SleepStage.ASLEEP_DEEP),
            )
        }

    /** 22:33:46 the previous evening, "asleep" from 02:39:00, awake 06:45:00. */
    private val times: SleepEdit.Times
        get() = SleepEdit.Times(inBedStart = d(1_786_912_426), sleepOnset = d(1_786_927_140), sleepWake = d(1_786_941_900))

    @Test
    fun theEntireDisplAYEDSleepTotalIsAnAssertion() {
        val on = SleepEdit.recompute(stagedBase, times, coverage = coverage)
        val b = SleepProvenanceBreakdown(on)
        assertEquals(246.0, b.displayedAsleep / 60, 0.5, "the card still says 246 min")
        assertEquals(2.9, b.measuredAsleep / 60, 0.2, "one 150 s epoch — 1.0 % of the asserted window")
        assertEquals(243.1, b.assertedAsleep / 60, 0.2)
        assertNull(b.efficiency, "102 min of covered in-bed is not enough ground for a ratio")
        assertFalse(b.isScorable)
    }

    @Test
    fun theNAIVEVETOWouldProduceAWorseNumberThanTheBug() {
        // Why provenance instead of refusing the fill: a data-availability veto would preserve 14 SECONDS.
        val recorded = assertNotNull(SleepStaging.sleepWindow(stagedBase.filter { it.stage != SleepStage.IN_BED }))
        val preservedStart = maxOf(times.sleepOnset, recorded.onset)
        val preservedEnd = minOf(times.sleepWake, recorded.wake)
        assertEquals(14.0, sec(Duration.between(preservedStart, preservedEnd)), 0.5, "fourteen seconds — this is what a data-availability veto would preserve")
    }

    @Test
    fun theRelabelledMeasuredSleepIsVisibleAsADisagreement() {
        // 102.5 min of 97.6 %-covered sleep is painted awake by the edit, tagged asserted-over-measured.
        val on = SleepEdit.recompute(stagedBase, times, coverage = coverage)
        val contested = on.filter { it.stage == SleepStage.AWAKE && it.provenance == SleepProvenance.ASSERTED_OVER_MEASURED }
        val contestedMin = contested.fold(0.0) { acc, s -> acc + sec(s.duration) } / 60
        assertEquals(100.0, contestedMin, 1.0, "~100 min of the awake paint sits on ground the ring recorded")

        // And the ring's own reading survives verbatim in the recorded hypnogram.
        val deep = stagedBase.filter { it.stage == SleepStage.ASLEEP_DEEP }.fold(0.0) { acc, s -> acc + sec(s.duration) } / 60
        assertEquals(30.0, deep, 0.5, "30 minutes of measured deep sleep, recoverable")
    }

    /** Re-baselined upstream 2026-08-24: the store receives all 246 minutes, 243.1 of them tagged as hers. */
    @Test
    fun theAssertedSleepIsWrittenAsHerOwnEntryOnThisNight() {
        val on = SleepEdit.recompute(stagedBase, times, coverage = coverage)
        val publication = on.healthPublication
        assertEquals(246.0, sec(SleepStaging.totalAsleep(publication.published)) / 60, 0.5, "the night she asserted reaches the health store in full")
        assertEquals(243.1, sec(SleepStaging.totalAsleep(publication.userEntered)) / 60, 0.2, "243.1 of those minutes are her account, and are tagged as such")
        assertEquals(2.9, sec(SleepStaging.totalAsleep(publication.measured)) / 60, 0.2, "only 2.9 asleep-minutes go in as an unqualified measurement")
        // The in-bed claim survives in full: 22:33:46 -> 06:45:00.
        val published = publication.published
        assertEquals(d(1_786_912_426), published.filter { it.stage == SleepStage.IN_BED }.minOfOrNull { it.start })
        assertEquals(d(1_786_941_900), published.filter { it.stage == SleepStage.IN_BED }.maxOfOrNull { it.end })
    }
}
