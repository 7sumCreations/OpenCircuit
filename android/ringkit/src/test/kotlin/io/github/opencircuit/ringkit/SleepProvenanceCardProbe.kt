package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Prints the full before/after card and health-store payload for the two tester nights, from the
 * committed fixtures (no corpus needed) — the evidence artifact for the provenance change.
 *
 * IT ALSO ASSERTS: a print-only test reports "passed" having checked nothing. [report] returns the
 * numbers the change turns on — the shipped card it must reproduce, the asleep-minutes written as the
 * wearer's own entry, the asleep-minutes retracted from the store (0 since the 2026-08-24 reversal,
 * kept so a regression back to withholding cannot pass silently) — and the caller pins all of them.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepProvenanceCardProbe.swift
 * (@ b1c2fdd) — its 1 test. Numbers in the printed report use `Locale.ROOT`.
 */
class SleepProvenanceCardProbe {

    private fun d(e: Long): Instant = Instant.ofEpochSecond(e)
    private fun sec(x: Duration): Double = SleepStaging.seconds(x)
    private fun f(fmt: String, v: Double): String = String.format(Locale.ROOT, fmt, v)

    private data class Report(val shippedAsleepMin: Long, val userEnteredAsleepMin: Double, val retractedAsleepMin: Double, val cardNotice: String?)

    private fun report(name: String, base: List<SleepSegment>, times: SleepEdit.Times, coverage: MeasuredCoverage): Report {
        val off = SleepEdit.recompute(base, times, coverage = null)
        val on = SleepEdit.recompute(base, times, coverage = coverage)
        val sOff = SleepStaging.summary(off)
        val mOff = sOff.minutes
        val b = SleepProvenanceBreakdown(on)
        val m = b.minutes

        fun score(s: SleepStaging.Summary): Int = SleepScore.composite(
            SleepScore.CompositeInput(
                totalAsleep = sec(s.totalAsleep), timeAwake = sec(s.awake), efficiency = s.efficiency,
                deep = sec(s.deep), light = sec(s.light), rem = sec(s.rem),
            ),
        ).score
        val healthOff = sec(SleepStaging.totalAsleep(off)) / 60
        val healthOn = sec(SleepStaging.totalAsleep(on.healthPublishable)) / 60
        val healthUserEntered = sec(SleepStaging.totalAsleep(on.healthUserEntered)) / 60
        val inBedOff = off.filter { it.stage == SleepStage.IN_BED }.fold(0.0) { acc, s -> acc + sec(s.duration) } / 60
        val inBedOn = on.healthPublishable.filter { it.stage == SleepStage.IN_BED }.fold(0.0) { acc, s -> acc + sec(s.duration) } / 60
        // The sleep-card line the wearer reads, rendered from the very breakdown the health filter is
        // driven by (`true`: the sleep-sharing case, i.e. what the tester actually sees).
        val notice = SleepEditedNightNotice.line(measuredAsleep = b.measuredAsleep, assertedAsleep = b.assertedAsleep, mirrorsSleepToHealth = true)

        println(
            """

            ================ $name ================
            BEFORE (master / kill switch)
              card      asleep ${mOff.asleep}  awake ${mOff.awake}  inBed ${mOff.inBed}
              stages    light ${mOff.light}  deep ${mOff.deep}  rem ${mOff.rem}
              eff       ${f("%.4f", sOff.efficiency)}
              score     ${score(sOff)}
              -> Health asleep ${f("%.1f", healthOff)} min · inBed ${f("%.1f", inBedOff)} min
            AFTER (provenance)
              card      asleep ${mOff.asleep} = ${m.measuredAsleep} measured + ${m.assertedAsleep} asserted
                        awake ${mOff.awake}  inBed ${m.inBed} (covered ${m.coveredInBed})
              stages    light ${m.light}  deep ${m.deep}  rem ${m.rem}   [measured only]
              eff       ${b.efficiency?.let { f("%.4f", it) } ?: "WITHHELD (—)"} over covered ground
              score     ${if (b.isScorable) score(sOff).toString() else "WITHHELD (—)"}
              coverage  ${f("%.4f", b.coverageFraction)}  longest gap ${f("%.1f", b.longestUnmeasuredGap / 60)} min
              -> Health asleep ${f("%.1f", healthOn)} min · inBed ${f("%.1f", inBedOn)} min
              USER-ENTERED ${f("%.1f", healthUserEntered)} asleep-min written as hers
              RETRACTED  ${f("%.1f", healthOff - healthOn)} asleep-min no longer written
              reason     ${b.withheldReason ?: "—"}
              CARD LINE  ${notice ?: "— (silent)"}
            """.trimIndent(),
        )

        // The card the user sees must not change — clause 1.
        assertEquals(mOff.asleep, SleepStaging.summary(on).minutes.asleep, name)
        // …and the in-bed claim must survive to the store in full.
        assertEquals(inBedOff, inBedOn, 0.01, "$name: the in-bed claim was dropped")
        // The unmeasured half may not ship without the line that names it.
        assertNotNull(notice, "$name: part of this night is asserted with nothing on the card saying so")
        return Report(mOff.asleep, healthUserEntered, healthOff - healthOn, notice)
    }

    @Test
    fun bothTesterNightsReproduceTheShippedCardAndLabelTheAssertedSleep() {
        fun s(a: Long, b: Long, st: SleepStage) = SleepSegment(d(a), d(b), st)
        fun iv(a: Long, b: Long) = DateInterval(d(a), d(b))

        val n0818 = report(
            "R2_2026-08-18  (shipped: 403 asleep / 36 awake / 0.9180 / score 71)",
            base = listOf(
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
            ),
            times = SleepEdit.Times(inBedStart = d(1_787_001_840), sleepOnset = d(1_787_004_000), sleepWake = d(1_787_028_180)),
            coverage = MeasuredCoverage(
                listOf(
                    iv(1_786_960_827, 1_786_965_327), iv(1_786_965_331, 1_786_984_681),
                    iv(1_786_989_748, 1_786_996_648), iv(1_786_996_649, 1_786_999_542),
                    iv(1_786_999_587, 1_787_012_637), iv(1_787_012_702, 1_787_013_452),
                    iv(1_787_027_937, 1_787_029_137),
                ),
            ),
        )

        val n0817 = report(
            "R2_2026-08-17  (shipped: 246 asleep / 245 awake / 0.5008 / score 19)",
            base = listOf(
                s(1_786_921_006, 1_786_927_154, SleepStage.IN_BED),
                s(1_786_921_006, 1_786_921_456, SleepStage.ASLEEP_CORE),
                s(1_786_921_456, 1_786_921_906, SleepStage.ASLEEP_REM),
                s(1_786_921_906, 1_786_923_106, SleepStage.ASLEEP_CORE),
                s(1_786_923_106, 1_786_924_306, SleepStage.ASLEEP_DEEP),
                s(1_786_924_306, 1_786_925_806, SleepStage.ASLEEP_CORE),
                s(1_786_925_806, 1_786_926_556, SleepStage.ASLEEP_REM),
                s(1_786_926_556, 1_786_927_154, SleepStage.ASLEEP_DEEP),
            ),
            times = SleepEdit.Times(inBedStart = d(1_786_912_426), sleepOnset = d(1_786_927_140), sleepWake = d(1_786_941_900)),
            coverage = MeasuredCoverage(
                listOf(
                    iv(1_786_921_006, 1_786_926_856), iv(1_786_927_034, 1_786_927_184),
                    iv(1_786_941_771, 1_786_948_971), iv(1_786_948_977, 1_786_960_827),
                ),
            ),
        )

        // Pin the headline numbers so this file cannot decay into a print-only pass. Re-baselined
        // upstream 2026-08-24: the SAME 241.4 and 243.1 asleep-minutes are still identified as the
        // wearer's account; they are now written tagged instead of subtracted.
        assertEquals(403L, n0818.shippedAsleepMin, "must reproduce the tester's stored card")
        assertEquals(246L, n0817.shippedAsleepMin, "must reproduce the tester's stored card")
        assertEquals(241.4, n0818.userEnteredAsleepMin, 0.2)
        assertEquals(243.1, n0817.userEnteredAsleepMin, 0.2)
        assertEquals(484.5, n0818.userEnteredAsleepMin + n0817.userEnteredAsleepMin, 0.4, "484.5 asleep-minutes across the two nights reach the store as her own entry")
        assertEquals(0.0, n0818.retractedAsleepMin, 0.01, "nothing is retracted any more — and withheldSpans must agree, or every re-edit duplicates the night")
        assertEquals(0.0, n0817.retractedAsleepMin, 0.01)

        // …and pin the exact card line for each, verbatim. A silent reword is a regression.
        assertEquals(
            "We kept the times you set. The ring recorded 2h 42m of the sleep above; for " +
                "the other 4h 1m we have your account, not a measurement. Both reach Apple " +
                "Health; your part is marked there as entered by you.",
            n0818.cardNotice,
        )
        assertEquals(
            "We kept the times you set. The ring recorded 3 minutes of the sleep above; " +
                "for the other 4h 3m we have your account, not a measurement. Both reach " +
                "Apple Health; your part is marked there as entered by you.",
            n0817.cardNotice,
        )
    }
}
