package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Ported from upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/ActiveEnergyLedgerTests.swift
// (@ b1c2fdd): both of its classes, every test, in upstream order.

/**
 * Per-bucket active-energy accounting. Health stores SUM active energy, so a double write is
 * PERMANENT in the user's store — every assertion here is ultimately about that.
 */
class ActiveEnergyLedgerTest {

    private val day: Instant = Instant.ofEpochSecond(1_753_660_800)
    private val width = 15 * 60.0

    private fun at(hours: Double): Instant = day.plusMillis(Math.round(hours * 3_600_000))

    private fun bucket(hour: Double, kcal: Double) =
        Calories.EnergyBucket(start = at(hour), end = at(hour).plusSeconds(width.toLong()), hrKcal = kcal, stepKcal = 0.0, elevatedMinutes = 0.0)

    // MARK: Writing

    @Test
    fun testFirstFlushWritesEveryBucketInItsOwnWindow() {
        val buckets = listOf(bucket(hour = 8.0, kcal = 40.0), bucket(hour = 9.0, kcal = 25.0))
        val plan = ActiveEnergyLedger.plan(buckets = buckets, watermarks = emptyList(), dayStart = day, now = at(12.0))
        assertEquals(2, plan.writes.size)
        assertEquals(65.0, plan.totalKcal, 1e-9)
        assertEquals(at(8.0), plan.writes[0].start)
        assertEquals(at(8.0).plusSeconds(width.toLong()), plan.writes[0].end)
        assertEquals(25.0, plan.writes[1].kcal, 1e-9)
    }

    @Test
    fun testAlreadyWrittenBucketsAreNotRewritten() {
        val buckets = listOf(bucket(hour = 8.0, kcal = 40.0))
        val first = ActiveEnergyLedger.plan(buckets = buckets, watermarks = emptyList(), dayStart = day, now = at(12.0))
        val second = ActiveEnergyLedger.plan(buckets = buckets, watermarks = first.watermarks, dayStart = day, now = at(12.0))
        assertTrue(second.writes.isEmpty())
        assertEquals(first.watermarks, second.watermarks)
    }

    @Test
    fun testAGrowingBucketWritesOnlyItsIncrement() {
        val first = ActiveEnergyLedger.plan(buckets = listOf(bucket(hour = 8.0, kcal = 40.0)), watermarks = emptyList(), dayStart = day, now = at(12.0))
        val second = ActiveEnergyLedger.plan(
            buckets = listOf(bucket(hour = 8.0, kcal = 62.0)), watermarks = first.watermarks, dayStart = day, now = at(12.0),
        )
        assertEquals(22.0, second.totalKcal, 1e-9)
    }

    /**
     * A late drain delivers data for a bucket EARLIER than ones already written. Addressing marks by
     * ordinal (not array position) is what stops the whole afternoon being re-paid.
     */
    @Test
    fun testALateEarlierBucketPaysOnlyItsOwnIncrement() {
        val afternoon = listOf(bucket(hour = 14.0, kcal = 30.0), bucket(hour = 15.0, kcal = 20.0))
        val first = ActiveEnergyLedger.plan(buckets = afternoon, watermarks = emptyList(), dayStart = day, now = at(16.0))
        assertEquals(50.0, first.totalKcal, 1e-9)

        val withMorning = listOf(bucket(hour = 9.0, kcal = 12.0)) + afternoon
        val second = ActiveEnergyLedger.plan(buckets = withMorning, watermarks = first.watermarks, dayStart = day, now = at(16.0))
        assertEquals(1, second.writes.size)
        assertEquals(12.0, second.totalKcal, 1e-9)
        assertEquals(at(9.0), second.writes.firstOrNull()?.start)
    }

    // MARK: The aggregate gate

    /**
     * A per-bucket floor would strand light constant walking forever — the reported bug in miniature.
     * The gate is on the day's pending SUM.
     */
    @Test
    fun testManySubKcalBucketsAccumulateAndThenWriteTogether() {
        // Two 0.4 kcal buckets = 0.8 for the day: under the gate, so nothing is written AND nothing is
        // marked — the kcal is still owed.
        val held = ActiveEnergyLedger.plan(
            buckets = (0 until 2).map { bucket(hour = 10 + it * 0.25, kcal = 0.4) },
            watermarks = emptyList(), dayStart = day, now = at(16.0),
        )
        assertTrue(held.writes.isEmpty())
        assertTrue(held.watermarks.isEmpty())

        // A third arrives: the day crosses 1.0 and ALL THREE are written, none stranded.
        val released = ActiveEnergyLedger.plan(
            buckets = (0 until 3).map { bucket(hour = 10 + it * 0.25, kcal = 0.4) },
            watermarks = held.watermarks, dayStart = day, now = at(16.0),
        )
        assertEquals(3, released.writes.size)
        assertEquals(1.2, released.totalKcal, 1e-9)
    }

    @Test
    fun testAggregateAboveTheGateWritesEveryBucketIncludingSubKcalOnes() {
        val small = (0 until 4).map { bucket(hour = 10 + it * 0.25, kcal = 0.4) }
        val plan = ActiveEnergyLedger.plan(buckets = small, watermarks = emptyList(), dayStart = day, now = at(16.0))
        assertEquals(4, plan.writes.size)
        assertEquals(1.6, plan.totalKcal, 1e-9)
    }

    @Test
    fun testBelowGateLeavesEveryMarkUntouchedSoTheKcalStaysOwed() {
        val plan = ActiveEnergyLedger.plan(
            buckets = listOf(bucket(hour = 10.0, kcal = 0.5)), watermarks = listOf(3.0, 4.0), dayStart = day, now = at(16.0), carry = 7.0,
        )
        assertTrue(plan.writes.isEmpty())
        assertEquals(listOf(3.0, 4.0), plan.watermarks)
        assertEquals(7.0, plan.carryRemaining)
        assertEquals(0.0, plan.workoutConsumed)
    }

    // MARK: Clamping to now

    @Test
    fun testBucketInProgressIsClampedToNow() {
        val now = at(9.0).plusSeconds(300) // 5 min into the 09:00 bucket
        val plan = ActiveEnergyLedger.plan(buckets = listOf(bucket(hour = 9.0, kcal = 5.0)), watermarks = emptyList(), dayStart = day, now = now)
        assertEquals(now, plan.writes.firstOrNull()?.end)
        assertTrue(plan.writes.first().end > plan.writes.first().start)
    }

    @Test
    fun testFutureBucketIsSkippedAndStaysOwed() {
        val plan = ActiveEnergyLedger.plan(buckets = listOf(bucket(hour = 20.0, kcal = 9.0)), watermarks = emptyList(), dayStart = day, now = at(12.0))
        assertTrue(plan.writes.isEmpty())
        assertTrue(plan.watermarks.isEmpty())
    }

    // MARK: Debt

    @Test
    fun testWorkoutCreditIsConsumedFromTheOldestIncrementsAndOnlyOnce() {
        val buckets = listOf(bucket(hour = 8.0, kcal = 30.0), bucket(hour = 9.0, kcal = 40.0))
        val plan = ActiveEnergyLedger.plan(
            buckets = buckets, watermarks = emptyList(), dayStart = day, now = at(12.0), uncreditedWorkoutKcal = 45.0,
        )
        assertEquals(45.0, plan.workoutConsumed, 1e-9)
        assertEquals(25.0, plan.totalKcal, 1e-9) // 70 earned − 45 already in Health
        assertEquals(1, plan.writes.size)
        assertEquals(at(9.0), plan.writes.firstOrNull()?.start)

        // Marks advanced by the FULL increments, so the credit cannot be applied twice.
        val again = ActiveEnergyLedger.plan(buckets = buckets, watermarks = plan.watermarks, dayStart = day, now = at(12.0))
        assertTrue(again.writes.isEmpty())
    }

    @Test
    fun testCarryIsConsumedBeforeWorkoutCredit() {
        val plan = ActiveEnergyLedger.plan(
            buckets = listOf(bucket(hour = 8.0, kcal = 100.0)), watermarks = emptyList(), dayStart = day, now = at(12.0),
            carry = 30.0, uncreditedWorkoutKcal = 20.0,
        )
        assertEquals(0.0, plan.carryRemaining, 1e-9)
        assertEquals(20.0, plan.workoutConsumed, 1e-9)
        assertEquals(50.0, plan.totalKcal, 1e-9)
    }

    // MARK: Upgrade-day seeding

    /**
     * The tester's case: mid-afternoon upgrade with 390 kcal already in Health against a day now
     * attributed at 429. Exactly the 39 kcal difference must be written, in the AFTERNOON buckets
     * where it was earned — never the whole 429 again.
     */
    @Test
    fun testSeedingWritesOnlyTheDifferenceAndPlacesItLate() {
        val buckets = listOf(bucket(hour = 8.0, kcal = 200.0), bucket(hour = 13.0, kcal = 190.0), bucket(hour = 17.0, kcal = 39.0))
        val seeded = ActiveEnergyLedger.seed(buckets = buckets, legacyWrittenKcal = 390.0, dayStart = day)
        assertEquals(0.0, seeded.carry, 1e-9)

        val plan = ActiveEnergyLedger.plan(buckets = buckets, watermarks = seeded.watermarks, dayStart = day, now = at(19.0), carry = seeded.carry)
        assertEquals(39.0, plan.totalKcal, 1e-9)
        assertEquals(1, plan.writes.size)
        assertEquals(at(17.0), plan.writes.firstOrNull()?.start)
    }

    /**
     * The legacy mark can EXCEED today's attributed total (a workout credit was already netted, or the
     * old high-water mark latched high). The excess must become carry, not a negative write.
     */
    @Test
    fun testSeedingExcessBecomesCarryAndSuppressesLaterWrites() {
        val buckets = listOf(bucket(hour = 8.0, kcal = 200.0), bucket(hour = 13.0, kcal = 229.0))
        val seeded = ActiveEnergyLedger.seed(buckets = buckets, legacyWrittenKcal = 500.0, dayStart = day)
        assertEquals(71.0, seeded.carry, 1e-9)

        val plan = ActiveEnergyLedger.plan(buckets = buckets, watermarks = seeded.watermarks, dayStart = day, now = at(19.0), carry = seeded.carry)
        assertTrue(plan.writes.isEmpty())

        // A later 100 kcal bucket pays only what survives the remaining 71 kcal of carry.
        val grown = buckets + bucket(hour = 18.0, kcal = 100.0)
        val next = ActiveEnergyLedger.plan(buckets = grown, watermarks = seeded.watermarks, dayStart = day, now = at(19.0), carry = seeded.carry)
        assertEquals(29.0, next.totalKcal, 1e-9)
        assertEquals(0.0, next.carryRemaining, 1e-9)
    }

    @Test
    fun testSeedingANewDayIsANoOp() {
        val buckets = listOf(bucket(hour = 8.0, kcal = 40.0))
        val seeded = ActiveEnergyLedger.seed(buckets = buckets, legacyWrittenKcal = 0.0, dayStart = day)
        assertEquals(0.0, seeded.carry)
        assertEquals(0.0, seeded.watermarks.fold(0.0) { a, b -> a + b })
    }

    // MARK: Long days

    /**
     * A clock fall-back day is 25 h. Ordinals are elapsed seconds from local midnight, so late buckets
     * simply extend the array rather than going out of range.
     */
    @Test
    fun testOrdinalsExtendPastTwentyFourHours() {
        val late = Calories.EnergyBucket(start = at(24.5), end = at(24.75), hrKcal = 10.0, stepKcal = 0.0, elevatedMinutes = 0.0)
        val plan = ActiveEnergyLedger.plan(buckets = listOf(late), watermarks = emptyList(), dayStart = day, now = at(25.0))
        assertEquals(10.0, plan.totalKcal, 1e-9)
        assertEquals(99, plan.watermarks.size) // ordinal 98 (24.5 h / 15 min) + 1
    }
}

/** Regressions from the pre-release adversarial review of the attribution change. */
class ActiveEnergyLedgerReviewRegressionTest {

    private val day: Instant = Instant.ofEpochSecond(1_753_660_800)
    private val width = 15 * 60.0

    private fun at(hours: Double): Instant = day.plusMillis(Math.round(hours * 3_600_000))

    private fun bucket(hour: Double, kcal: Double) =
        Calories.EnergyBucket(start = at(hour), end = at(hour).plusSeconds(width.toLong()), hrKcal = kcal, stepKcal = 0.0, elevatedMinutes = 0.0)

    /**
     * REVIEW BLOCKER. A bucket's attributed energy can FALL between flushes — a later drain inserts an
     * earlier HR point that re-prices a piece across a bucket edge. With per-bucket high-water marks
     * alone, the fall stranded its inflated mark while the bucket that GAINED paid in full, so the
     * Health store drifted permanently high. It must net out.
     */
    @Test
    fun testEnergyMovingBetweenBucketsPaysTheNetNotTheGross() {
        val before = listOf(bucket(hour = 12.0, kcal = 3.847), bucket(hour = 12.25, kcal = 53.864))
        val first = ActiveEnergyLedger.plan(buckets = before, watermarks = emptyList(), dayStart = day, now = at(13.0))
        assertEquals(57.711, first.totalKcal, 0.01)

        // The re-priced day: 12:00 gains 8.984, 12:15 loses 2.563. Net rise is 6.421.
        val after = listOf(bucket(hour = 12.0, kcal = 12.831), bucket(hour = 12.25, kcal = 51.301))
        val second = ActiveEnergyLedger.plan(
            buckets = after, watermarks = first.watermarks, dayStart = day, now = at(14.0), savedKcal = first.totalKcal,
        )
        assertEquals(6.421, second.totalKcal, 0.01)

        // What the store holds must equal what the day is now worth — not the sum of gross rises.
        val held = first.totalKcal + second.totalKcal
        assertEquals(after.fold(0.0) { a, b -> a + b.activeKcal }, held, 0.01)
    }

    /**
     * The netted overpayment has to SURVIVE a flush that writes nothing, or it is forgotten and the
     * same kcal is paid again on the next rise.
     */
    @Test
    fun testAFallWithNoOffsettingRiseIsHandedBackAsCarry() {
        val before = listOf(bucket(hour = 9.0, kcal = 40.0))
        val first = ActiveEnergyLedger.plan(buckets = before, watermarks = emptyList(), dayStart = day, now = at(10.0))

        val shrunk = listOf(bucket(hour = 9.0, kcal = 25.0))
        val second = ActiveEnergyLedger.plan(
            buckets = shrunk, watermarks = first.watermarks, dayStart = day, now = at(11.0), savedKcal = first.totalKcal,
        )
        assertTrue(second.writes.isEmpty())
        assertEquals(15.0, second.carryRemaining, 1e-9)
        assertEquals(25.0, second.watermarks.firstOrNull { it > 0 })

        // A later 15 kcal rise is fully absorbed by that debt — the store stays at 40, the day's peak.
        val regrown = shrunk + bucket(hour = 16.0, kcal = 15.0)
        val third = ActiveEnergyLedger.plan(
            buckets = regrown, watermarks = second.watermarks, dayStart = day, now = at(17.0),
            carry = second.carryRemaining, savedKcal = first.totalKcal,
        )
        assertTrue(third.writes.isEmpty())
    }

    /**
     * REVIEW MAJOR. A store recovery rebuilds the day from fewer rows while the marks survive in
     * storage, and the residual step reconciliation re-places the day's energy in a DIFFERENT bucket.
     * Without a day-total backstop that pays the same kcal twice — and the store SUMS, so it can never
     * be taken back.
     */
    @Test
    fun testDayTotalBackstopBlocksRepaymentAfterEnergyIsRelocated() {
        val morning = (0 until 4).map { bucket(hour = 8 + it * 0.25, kcal = 30.0) }
        val first = ActiveEnergyLedger.plan(buckets = morning, watermarks = emptyList(), dayStart = day, now = at(13.0))
        assertEquals(120.0, first.totalKcal, 1e-9)

        // Store wiped: the same ~120 kcal day is rebuilt as ONE bucket at a different ordinal.
        val rebuilt = listOf(bucket(hour = 13.75, kcal = 118.0))
        val second = ActiveEnergyLedger.plan(
            buckets = rebuilt, watermarks = first.watermarks, dayStart = day, now = at(14.0), savedKcal = first.totalKcal,
        )
        assertTrue(second.writes.isEmpty(), "the store already holds 120 for a day now worth 118 — nothing may be re-paid")
    }

    @Test
    fun testBackstopClampsAPartialWriteRatherThanDroppingIt() {
        val buckets = listOf(bucket(hour = 9.0, kcal = 100.0))
        val plan = ActiveEnergyLedger.plan(buckets = buckets, watermarks = emptyList(), dayStart = day, now = at(10.0), savedKcal = 70.0)
        assertEquals(30.0, plan.totalKcal, 1e-9)
    }

    /**
     * An empty bucket set means "no data yet", not "the day lost everything" — otherwise the first
     * flush of a morning would net the whole previous state into carry.
     */
    @Test
    fun testEmptyBucketsAreNotReadAsATotalLoss() {
        val seeded = ActiveEnergyLedger.plan(buckets = listOf(bucket(hour = 9.0, kcal = 40.0)), watermarks = emptyList(), dayStart = day, now = at(10.0))
        val empty = ActiveEnergyLedger.plan(buckets = emptyList(), watermarks = seeded.watermarks, dayStart = day, now = at(11.0), savedKcal = 40.0)
        assertEquals(seeded.watermarks, empty.watermarks)
        assertEquals(0.0, empty.carryRemaining)
    }
}
