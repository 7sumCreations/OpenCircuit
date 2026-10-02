package io.github.opencircuit.ringkit

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/CyclePeriodMirrorTests.swift (@ b1c2fdd),
 * 18 of 18, each test named after upstream's with its line. SYNTHETIC-ONLY tests for the period mirror's
 * bounds (the open-period auto-extension cap and the "nothing new" gate). Upstream already fixes its dates
 * and a UTC calendar ("a test that leans on `Date()` and `.current` would pass or fail depending on the
 * hour it ran"); both stay as upstream wrote them, the calendar as the required zone. A day count is a
 * `Long` (Swift's 64-bit `Int`). That the mirror reads the zone it is given is shown by
 * `CyclePredictorClockChangeTest` (New York) and `CyclePredictorHazardTest` (zones that skip midnight).
 */
class CyclePeriodMirrorTest {

    // MARK: Helpers

    private val cal: ZoneId = ZoneOffset.UTC

    /** 2025-07-11 00:00:00 UTC — the same anchor the app-target menstrual-flow tests use. */
    private val start: Instant = Instant.ofEpochSecond(1_752_192_000L)

    private fun day(n: Int): Instant = start.atZone(cal).plusDays(n.toLong()).toInstant()

    private fun dayCount(end: Instant?, today: Instant): Long =
        CyclePredictor.periodMirrorDayCount(start = start, end = end, today = today, zone = cal)

    private val cap: Long = CyclePredictor.MAX_AUTO_EXTEND_PERIOD_DAYS.toLong()

    // MARK: The cap — an open period stops extending

    /**
     * The tester's actual complaint: "once a period has started, the app keeps syncing the following days
     * as period days even after it has ended". An open period must stop growing.
     */
    @Test
    fun testOpenPeriodStopsExtendingAtTheCap() { // :39
        // Day 1 through day 8 are the cap's own span, so each is still mirrored in full.
        assertEquals(1L, dayCount(end = null, today = day(0)))
        assertEquals(7L, dayCount(end = null, today = day(6)))
        assertEquals(cap, dayCount(end = null, today = day(7)))

        // Past it, elapsed days no longer add samples — this is the unbounded growth being fixed.
        for (elapsed in listOf(8, 9, 20, 400)) {
            assertEquals(cap, dayCount(end = null, today = day(elapsed)), "an open period must not still be extending $elapsed days in")
        }
    }

    /**
     * The cap is a bound on the LAST DAY, not a rolling window: it must never walk forward and start
     * dropping early days that Apple Health already holds.
     */
    @Test
    fun testCapNeverRetractsDaysAlreadyWritten() { // :55
        var previous = 0L
        for (elapsed in 0..30) {
            val count = dayCount(end = null, today = day(elapsed))
            assertTrue(count >= previous, "day $elapsed lost an earlier day")
            previous = count
        }
        assertEquals(
            day(CyclePredictor.MAX_AUTO_EXTEND_PERIOD_DAYS - 1),
            CyclePredictor.openPeriodAutoExtendLastDay(start = start, today = day(99), zone = cal),
        )
    }

    /** A start date in the future asserts nothing at all. */
    @Test
    fun testFutureStartMirrorsNothing() { // :68
        assertEquals(0L, dayCount(end = null, today = day(-1)))
        assertEquals(0L, dayCount(end = day(3), today = day(-1)))
    }

    // MARK: The cap must NOT touch an explicitly-ended period

    /**
     * A logged end date is the user's own statement, so the cap may never shorten it — not even well past
     * the prolonged-bleeding threshold the cap is derived from.
     */
    @Test
    fun testExplicitlyEndedPeriodIsUntouchedByTheCap() { // :77
        // 14 days, logged: nearly double the cap, and every day still mirrored.
        assertEquals(14L, dayCount(end = day(13), today = day(30)))
        // A 30-day logged period likewise survives intact.
        assertEquals(30L, dayCount(end = day(29), today = day(60)))
        // Ordinary lengths are unchanged too.
        assertEquals(5L, dayCount(end = day(4), today = day(30)))
    }

    /**
     * Finalized behaviour is otherwise exactly what it was: through the logged end, clamped to today so a
     * future end never asserts days that haven't happened.
     */
    @Test
    fun testFinalizedPeriodStillClampsToToday() { // :88
        assertEquals(4L, dayCount(end = day(9), today = day(3)))
        assertEquals(day(3), CyclePredictor.periodMirrorLastDay(start = start, end = day(9), today = day(3), zone = cal))
    }

    /**
     * The clamp-to-today above is the ONE case where a finalized entry legitimately has more to write
     * later, so it must not be reported as up to date while it is still short.
     */
    @Test
    fun testFinalizedPeriodWithFutureEndStaysUnsettledUntilItsDaysElapse() { // :97
        assertTrue(upToDate(written = 4, end = day(9), today = day(3)), "4 of the 4 elapsed days are written — nothing to do right now")
        assertFalse(upToDate(written = 4, end = day(9), today = day(4)), "a fifth day has elapsed and must still be added")
        assertTrue(upToDate(written = 10, end = day(9), today = day(30)))
    }

    // MARK: The "nothing new" gate — a flush with nothing to say writes NOTHING

    private fun upToDate(written: Int, end: Instant?, today: Instant): Boolean =
        CyclePredictor.periodMirrorIsUpToDate(writtenSampleCount = written, start = start, end = end, today = today, zone = cal)

    /**
     * The churn defect. Same day, same content, already written → the flush must skip. Before the fix this
     * returned the entry on every foreground activation, sync completion, BLE wake-drain and BGTask, each
     * one deleting and rewriting the whole span.
     */
    @Test
    fun testAlreadyMirroredEntryReportsUpToDate() { // :115
        assertTrue(upToDate(written = 3, end = null, today = day(2)))
        assertTrue(upToDate(written = 5, end = day(4), today = day(9)))
    }

    /**
     * ...and stays skipped indefinitely once the open period is capped, which is what stops the per-flush
     * churn from simply resuming after day 8.
     */
    @Test
    fun testCappedOpenPeriodStaysUpToDateForever() { // :122
        val capped = CyclePredictor.MAX_AUTO_EXTEND_PERIOD_DAYS
        for (elapsed in listOf(7, 8, 30, 365)) {
            assertTrue(upToDate(written = capped, end = null, today = day(elapsed)), "a capped open period must never re-drive a write (day $elapsed)")
        }
    }

    /** The gate must still let a genuinely new day through, or the fix would freeze the mirror. */
    @Test
    fun testNewDayOnAnOpenPeriodIsNotUpToDate() { // :131
        assertFalse(upToDate(written = 3, end = null, today = day(3)))
        assertFalse(upToDate(written = 1, end = null, today = day(7)))
    }

    /** A never-written entry is never "up to date", even though 0 == 0 would say so numerically. */
    @Test
    fun testUnwrittenEntryIsNeverUpToDate() { // :137
        assertFalse(upToDate(written = 0, end = null, today = day(0)))
        assertFalse(upToDate(written = 0, end = day(4), today = day(9)))
    }

    /**
     * A mid-flush crash leaves stale + fresh tracked together (2N for an N-day span). That count mismatch
     * is what re-drives the entry and cleans the duplicate up, so it must NOT read as up to date.
     */
    @Test
    fun testInterruptedFlushDuplicateStateIsNotUpToDate() { // :145
        assertFalse(upToDate(written = 6, end = null, today = day(2)), "3 fresh + 3 stale must re-drive so the duplicate is removed")
    }

    /** The cap-reached signal that drives the UI copy. */
    @Test
    fun testOpenPeriodCapReachedFlag() { // :151
        assertFalse(CyclePredictor.openPeriodHasReachedAutoExtendCap(start = start, today = day(6), zone = cal))
        assertTrue(CyclePredictor.openPeriodHasReachedAutoExtendCap(start = start, today = day(7), zone = cal))
        assertTrue(CyclePredictor.openPeriodHasReachedAutoExtendCap(start = start, today = day(40), zone = cal))
    }

    // MARK: Write-first / delete-after ordering

    /**
     * A MODEL of the app's menstrual-flow flush ordering, not a test of that method itself (it needs a live
     * health store, so the app owns its behaviour). What is pinned here is the ORDERING INVARIANT the method
     * was reshaped to satisfy, stepped through with a kill at every point: every sample present in Health is
     * always nameable by the row that wrote it, and Health is never emptier than it started.
     */
    @Test
    fun testWriteFirstDeleteAfterLeavesNoUntrackedOrphan() { // :175
        val stale = listOf("A", "B") // 2 days already in Health
        val fresh = listOf("C", "D", "E") // 3 days being written now

        // The production step order: record(stale+fresh) → save → delete(stale) → record(fresh).
        for (killAfterStep in 0..4) {
            val inHealth = stale.toMutableSet()
            var tracked = stale.toSet()

            if (killAfterStep >= 1) tracked = (stale + fresh).toSet() // record together
            if (killAfterStep >= 2) inHealth.addAll(fresh) // save
            if (killAfterStep >= 3) inHealth.removeAll(stale.toSet()) // delete stale
            if (killAfterStep >= 4) tracked = fresh.toSet() // record fresh alone

            assertTrue(
                tracked.containsAll(inHealth),
                "killed after step $killAfterStep: ${inHealth - tracked} is in Health but named by nothing — the user could never delete it",
            )
            assertTrue(inHealth.size >= minOf(stale.size, fresh.size), "killed after step $killAfterStep: Health went emptier")
            assertFalse(inHealth.isEmpty(), "killed after step $killAfterStep: Health emptied")
        }
    }

    /**
     * Recording the fresh identifiers BEFORE the save is what makes every written sample nameable, but it
     * hands the failure path a leak: a save that throws leaves identifiers tracked for samples that never
     * existed, and the next attempt appends another generation on top. The rollback bounds it at one
     * generation.
     */
    @Test
    fun testFailedSaveRollsTrackingBackInsteadOfGrowingUnbounded() { // :204
        val stale = listOf("A", "B", "C")
        var tracked = stale.toSet()

        // Ten consecutive failed flushes, each recording ahead of a save that then throws.
        repeat(10) {
            val fresh = List(3) { UUID.randomUUID().toString() }
            tracked = (tracked.toList() + fresh).toSet() // record before save
            // save throws → roll back to the pre-attempt set
            tracked = stale.toSet()
        }
        assertEquals(stale.toSet(), tracked, "a failing save must not accumulate tracked identifiers")

        // Without the rollback the same ten flushes would track 33 identifiers for 3 real samples.
        var unrolled = stale.toSet()
        repeat(10) {
            unrolled = (unrolled.toList() + List(3) { UUID.randomUUID().toString() }).toSet()
        }
        assertEquals(33, unrolled.size, "the leak this rollback closes is real, not theoretical")
    }

    /**
     * The same walk over the OLD delete-first order, kept as the counter-example: it is here to prove the
     * invariant above actually discriminates, rather than being true of any ordering.
     */
    @Test
    fun testDeleteFirstOrderingWouldEmptyHealthMidFlush() { // :227
        val stale = listOf("A", "B")
        val fresh = listOf("C", "D", "E")
        val inHealth = stale.toMutableSet()

        inHealth.removeAll(stale.toSet()) // delete first — the window the reshape removes
        assertTrue(inHealth.isEmpty(), "delete-first genuinely empties Health before the replacement exists")
        inHealth.addAll(fresh)
        assertEquals(fresh.toSet(), inHealth)
    }

    // MARK: The anti-retraction floor — the cap may STOP the app, never UNDO it

    /**
     * THE BLOCKER THIS FLOOR EXISTS FOR, caught in adversarial review before it shipped. The cap was
     * retroactive on the UPGRADE path: a period left open before the change arrives with N tracked samples
     * (N ≫ 8); the first flush after upgrade rebuilt only the capped 8 and deleted all N — a net loss of
     * N − 8 days from the wearer's record. Stopping the app from ADDING days is the fix; taking back days it
     * already wrote is a different act entirely, and not one this app may perform.
     */
    @Test
    fun testTheCapNeverRetractsADayAlreadyInHealth() { // :252
        // 20 days elapsed, 20 already mirrored: every one survives.
        assertEquals(20L, CyclePredictor.periodMirrorDayCount(start = start, end = null, today = day(19), alreadyCoveredDays = 20, zone = cal))
        // …and it still does not GROW past what is covered — day 21 is not added.
        assertEquals(20L, CyclePredictor.periodMirrorDayCount(start = start, end = null, today = day(25), alreadyCoveredDays = 20, zone = cal))
        // A never-written period is capped normally: the floor is inert at 0.
        assertEquals(cap, CyclePredictor.periodMirrorDayCount(start = start, end = null, today = day(19), alreadyCoveredDays = 0, zone = cal))
    }

    /**
     * The floor must never push the mirror into the FUTURE, which is the one thing neither the cap nor the
     * floor may do. A stale/oversized tracking array is clamped to today.
     */
    @Test
    fun testTheFloorNeverAssertsADayThatHasNotHappened() { // :267
        assertEquals(3L, CyclePredictor.periodMirrorDayCount(start = start, end = null, today = day(2), alreadyCoveredDays = 20, zone = cal))
    }

    /**
     * The up-to-date gate must agree with the floor, or a legacy over-cap period would be judged "stale" on
     * every flush and rewritten forever — the churn defect, resurrected by the fix.
     */
    @Test
    fun testALegacyOverCapPeriodIsReportedUpToDateOnceWritten() { // :274
        assertTrue(CyclePredictor.periodMirrorIsUpToDate(writtenSampleCount = 20, start = start, end = null, today = day(25), zone = cal))
    }
}
