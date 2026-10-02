package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Port of upstream ObservedGapAbsorbTests.swift (@ b1c2fdd) — the OBSERVED-GAP GUARD on the
 * backward cluster chain of `BulkSleep.latestNightRecords` (`OBSERVED_GAP_ABSORB_COVERAGE_CUT`).
 *
 * The case it targets, measured upstream on a real night: an awake-but-STILL 71-min evening block
 * clears the minimum sleep duration, is admitted as a night by the midpoint rule, and the backward
 * chain bridges the 43-min gap to the real night — dragging the in-bed start two hours early. That
 * gap holds 17 of an expected 17.2 records: observed awake time, not the missing-drain hole the
 * chain was written for. The default is 0.95 = ON; 0 is the one-constant revert, pinned below.
 * Fixtures are built on an explicit zone's wall clock, the zone the selector is given.
 */
class ObservedGapAbsorbTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val base: Instant = Instant.ofEpochSecond(1_780_000_000) // ~2026, after the sync epoch

    private fun record(date: Instant, still: Boolean, seed: Int = 0): BulkRecord {
        val counter = date.epochSecond - Command.SYNC_EPOCH
        val b = ByteArray(BulkRecord.LENGTH)
        b[0] = (counter shr 24).toByte(); b[1] = (counter shr 16).toByte()
        b[2] = (counter shr 8).toByte(); b[3] = counter.toByte()
        if (still) {
            for (k in 10 until 15) b[k] = 1
        } else {
            // Elevated AND varied — a constant plateau de-floors to STILL (the flat-motion fixture trap).
            val jitter = seed % 5
            b[10] = (8 + jitter).toByte(); b[11] = 12; b[12] = (20 + jitter).toByte(); b[13] = 6; b[14] = 10
        }
        return BulkRecord.of(b)!!
    }

    private fun still(start: Instant, end: Instant): List<BulkRecord> =
        (0 until Duration.between(start, end).seconds step 150).map { record(start.plusSeconds(it), still = true) }

    private fun moving(start: Instant, end: Instant): List<BulkRecord> =
        (0 until Duration.between(start, end).seconds step 150).mapIndexed { i, s -> record(start.plusSeconds(s), still = false, seed = i) }

    /** Local `hour:min` on the day containing [base]: the overnight rule is a LOCAL-midpoint rule. */
    private fun at(hour: Int, min: Int = 0, dayOffset: Int = 0): Instant =
        base.atZone(zone).toLocalDate().atStartOfDay(zone).plusDays(dayOffset.toLong()).toInstant().plusSeconds((hour * 60L + min) * 60)

    /**
     * The measured shape: a still 20:30→21:40 evening block (midpoint 21:05, clearing the 21:00 cliff
     * by five minutes), 35 min of RECORDED movement, then the real night 22:15→06:00. Both blocks
     * are "overnight" by the midpoint rule, so without the guard the chain bridges them.
     */
    private fun eveningShapedUnion(): List<BulkRecord> =
        still(at(20, 30), at(21, 40)) + moving(at(21, 40), at(22, 15)) + still(at(22, 15), at(6, 0, dayOffset = 1))

    /** The first bout with a long trailing tail: 6 h 15 m night, a fully observed hour awake, a 2 h 15 m tail. */
    private fun shortTailUnion(): List<BulkRecord> =
        still(at(20, 30), at(2, 45, dayOffset = 1)) +
            moving(at(2, 45, dayOffset = 1), at(3, 45, dayOffset = 1)) +
            still(at(3, 45, dayOffset = 1), at(6, 0, dayOffset = 1))

    /** A genuinely fragmented night whose LATER bout is the longer one. */
    private fun midNightBoutUnion(): List<BulkRecord> =
        still(at(22, 0), at(1, 0, dayOffset = 1)) +
            moving(at(1, 0, dayOffset = 1), at(1, 45, dayOffset = 1)) +
            still(at(1, 45, dayOffset = 1), at(7, 0, dayOffset = 1))

    private fun firstKept(scoped: List<BulkRecord>): Instant? = scoped.minOfOrNull { it.date() }

    private fun latest(
        records: List<BulkRecord>,
        cut: Double = BulkSleep.OBSERVED_GAP_ABSORB_COVERAGE_CUT,
        reanchor: Boolean = BulkSleep.DECLINED_BRIDGE_MAY_REANCHOR,
    ) = BulkSleep.latestNightRecords(records, zone = zone, observedGapCoverageCut = cut, declinedBridgeMayReanchor = reanchor)

    // The kill switch

    /**
     * 0 IS THE OFF SWITCH AND IT IS BYTE-IDENTICAL TO THE PRE-GUARD CODE — asserted on a fixture the
     * shipped default DOES change, so it cannot go vacuous.
     */
    @Test
    fun testZeroCutIsByteIdenticalToPreGuardScoping() {
        val union = eveningShapedUnion()

        // OFF: pre-guard behaviour — the evening block IS absorbed, so the slice reaches back to it.
        val off = assertNotNull(firstKept(latest(union, cut = 0.0)))
        assertTrue(
            !off.isAfter(at(20, 30)),
            "at cut 0 the pre-guard chain must still absorb the evening block — if this stops being true the kill-switch test has gone vacuous",
        )

        // ON at the shipped default: it must NOT be absorbed. This is what makes the pair meaningful.
        assertTrue(
            BulkSleep.OBSERVED_GAP_ABSORB_COVERAGE_CUT > 0,
            "the shipped default is expected to be ON; if it is reverted to 0, flip this test back to a plain no-op assertion",
        )
        val shipped = assertNotNull(firstKept(latest(union)))
        assertTrue(shipped.isAfter(at(21, 40)), "at the shipped default the evening block must be declined")
    }

    /**
     * The shipped default must be REACHABLE at every gap length the guard may judge: with off-grid
     * endpoints a fully observed gap reaches `⌈L⌉/L`, which exceeds 1.0, so every cut ≤ 1.0 is reachable.
     */
    @Test
    fun testShippedDefaultIsReachableAtEveryJudgeableGapLength() {
        val cadence = BulkRecord.EPOCH_SECONDS.toDouble()
        val cut = BulkSleep.OBSERVED_GAP_ABSORB_COVERAGE_CUT
        assertTrue(cut <= 1.0, "a cut above 1.0 would need a super-complete gap")

        for (gapMinutes in listOf(7.5, 10.0, 20.0, 30.0, 43.0, 60.0, 360.0)) {
            val gap = gapMinutes * 60.0
            if (!(gap > BulkSleep.ONSET_CONTIGUITY_GAP.seconds.toDouble())) continue
            var maxInterior = 0
            for (step in 0 until cadence.toInt()) {
                var count = 0
                var t = step.toDouble()
                while (t < gap) {
                    if (t > 0) count += 1
                    t += cadence
                }
                maxInterior = maxOf(maxInterior, count)
            }
            val achievable = maxInterior.toDouble() / (gap / cadence)
            assertTrue(
                achievable >= cut,
                "cut $cut is unreachable at a $gapMinutes min gap (max achievable $achievable) — the guard would be inert there for an arithmetic reason, not a physical one",
            )
        }
    }

    /** A negative cut is also OFF, so a mis-set flag degrades to the pre-guard behaviour. */
    @Test
    fun testNegativeCutIsAlsoOff() {
        val union = eveningShapedUnion()
        assertEquals(latest(union, cut = 0.0).map { it.counter }, latest(union, cut = -1.0).map { it.counter })
    }

    // What it does when enabled

    /** ENABLED, the densely-observed gap is not bridged and the evening block stays out. */
    @Test
    fun testEnabledCutDeclinesADenselyObservedGap() {
        val on = assertNotNull(firstKept(latest(eveningShapedUnion(), cut = 0.5)))
        assertTrue(on.isAfter(at(21, 40)), "the evening block must NOT be absorbed once the guard is on")
    }

    /**
     * THE CASE THE CHAIN EXISTS FOR MUST SURVIVE: two fragments of one night separated by an EMPTY
     * HOLE. Coverage is 0, so the earlier fragment is still stitched in, at every cut.
     */
    @Test
    fun testEnabledCutStillStitchesAnUnobservedHole() {
        val union = still(at(22, 0), at(1, 0, dayOffset = 1)) + // fragment 1
            still(at(4, 0, dayOffset = 1), at(7, 0, dayOffset = 1)) // fragment 2, 3 h hole between
        for (cut in listOf(0.1, 0.25, 0.5, 0.75, 0.9, 1.0)) {
            val first = assertNotNull(firstKept(latest(union, cut = cut)), "cut $cut")
            assertTrue(!first.isAfter(at(22, 0)), "cut $cut: a multi-drain hole must still be stitched")
        }
    }

    /**
     * THE COST, ASSERTED RATHER THAN HIDDEN: a genuinely fragmented night (asleep 22:00, up and
     * moving 01:00→01:45 with the ring recording, asleep again until 07:00) is indistinguishable
     * from the evening block by this discriminator. With the guard ON the first bout is dropped.
     */
    @Test
    fun testEnabledCutAlsoDropsARealMidNightBout() {
        val union = midNightBoutUnion()
        val off = latest(union, cut = 0.0)
        val on = latest(union) // shipped default
        assertTrue(!firstKept(off)!!.isAfter(at(22, 0)), "the pre-guard chain keeps the first bout")
        assertTrue(firstKept(on)!!.isAfter(at(1, 0, dayOffset = 1)), "the guard drops it — documented cost, not a surprise")
    }

    // The declined-bridge re-anchor (`DECLINED_BRIDGE_MAY_REANCHOR`)

    /**
     * THE REAL TESTER SHAPE (measured upstream on a Gen 2 night): a LONG first bout, a completely
     * observed hour awake, then a SHORTER trailing bout. The tail anchors, the guard declines the
     * bridge back, and without the re-anchor the SHORT bout stands alone as "the night". The guard
     * may separate two bouts, but it may never make the SMALLER bout the night — and the two bouts
     * are still NOT bridged.
     */
    @Test
    fun testDeclinedBridgeReanchorsOntoTheLongerOrphanedBout() {
        val union = shortTailUnion()

        val master = assertNotNull(firstKept(latest(union, reanchor = false)))
        assertTrue(
            master.isAfter(at(2, 45, dayOffset = 1)),
            "the fixture must reproduce the defect with the re-anchor off, or this test is vacuous: the short tail should be all that survives",
        )

        val fixed = latest(union)
        val fixedFirst = assertNotNull(firstKept(fixed))
        assertTrue(!fixedFirst.isAfter(at(20, 30).plusSeconds(150)), "the re-anchor must return the LONGER first bout")
        // …and it must still NOT bridge: the observed awake hour stays outside the night, so the
        // slice must end before the tail (the +30 min margin is why this compares against 03:45).
        assertTrue(
            fixed.maxOf { it.date() }.isBefore(at(3, 45, dayOffset = 1)),
            "the re-anchor must not become a back-door bridge across measured awake time",
        )
    }

    /**
     * THE BOUND THAT KEEPS THE RE-ANCHOR FROM EATING THE PREVIOUS NIGHT (measured upstream: a first
     * draft without `MAX_INTRA_NIGHT_GAP` re-anchored a real night onto the night before). The
     * re-anchor may only rescue a block the GUARD orphaned — never one the DISTANCE rule rejected.
     */
    @Test
    fun testReanchorNeverReachesBackPastMaxIntraNightGap() {
        // A long previous night, a full day of worn records, then a shorter real night.
        val union = still(at(1, 0), at(8, 54)) + // 7 h 54 m, previous night
            moving(at(8, 54), at(22, 45)) + // all-day worn coverage
            still(at(22, 45), at(2, 41, dayOffset = 1)) // 3 h 56 m, the real night

        val scoped = latest(union)
        val first = assertNotNull(firstKept(scoped))
        assertTrue(
            first.isAfter(at(20, 0)),
            "the real night must survive — a re-anchor onto the previous night is the ANCHOR EVICTION failure the selector is built to prevent",
        )
        assertEquals(
            latest(union, reanchor = false).map { it.date() },
            scoped.map { it.date() },
            "with the distance bound respected, this shape must be identical either way",
        )
    }

    /** The re-anchor switch off must be byte-identical to the pre-re-anchor code — asserted on the fixture the default DOES change. */
    @Test
    fun testReanchorKillSwitchIsByteIdentical() {
        val union = shortTailUnion()
        val off = latest(union, reanchor = false)
        val on = latest(union)
        assertNotEquals(off.map { it.date() }, on.map { it.date() }, "if these ever match, this kill-switch test has gone vacuous")
        assertTrue(BulkSleep.DECLINED_BRIDGE_MAY_REANCHOR, "the shipped default is expected to be ON; if it is reverted, flip this test")
    }

    /**
     * The re-anchor is strictly NARROWER than reverting the guard: when the LATER bout is the longer
     * one, nothing changes (that remains a known, unfixed cost of the guard).
     */
    @Test
    fun testReanchorDoesNotFireWhenTheLaterBoutIsLonger() {
        val union = midNightBoutUnion()
        assertEquals(
            latest(union, reanchor = false).map { it.date() },
            latest(union).map { it.date() },
            "a longer later bout is not a smaller-bout promotion — leave it alone",
        )
    }

    /** A gap at or below the 450 s contiguity floor is detector granularity, never judged. */
    @Test
    fun testShortDetectorSplitIsNeverJudged() {
        val union = still(at(22, 0), at(1, 0, dayOffset = 1)) +
            moving(at(1, 0, dayOffset = 1), at(1, 5, dayOffset = 1)) + // 300 s < 450 s
            still(at(1, 5, dayOffset = 1), at(7, 0, dayOffset = 1))
        for (cut in listOf(0.1, 0.5, 1.0)) {
            val first = assertNotNull(firstKept(latest(union, cut = cut)), "cut $cut")
            assertTrue(!first.isAfter(at(22, 0)), "cut $cut: a sub-450 s split must never be treated as a gap")
        }
    }
}
