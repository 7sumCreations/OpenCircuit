package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.SyncMeasurement.CapacityKind
import io.github.opencircuit.ringkit.SyncMeasurement.InconclusiveReason
import io.github.opencircuit.ringkit.SyncMeasurement.SyncSpan
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What one complete sync says about how much the ring keeps and what it does when full. The rule's
 * numbers are typed here from the approved rule, not read from the code: one record per 150 s
 * (576 a day), a span sparser than half of that is inconclusive; "fresh" is a newest record at most
 * 30 min old; a ring is judged full only over a dense span of at least 4 days; a ring that stopped
 * recording shows at least a day since its newest record; a first sync shows overwriting when its
 * span reaches the model's nominal capacity less a day (Gen 2 / Air 7 days, Gen 3 10).
 */
class CapacityVerdictTest {

    private val now: Instant = Instant.parse("2026-10-08T09:00:00Z")
    private val day: Duration = Duration.ofDays(1)

    /** A dense span: one record every 150 s from [oldest] to [newest] inclusive. */
    private fun dense(oldest: Instant, newest: Instant): SyncSpan =
        SyncSpan(oldest, newest, records = (Duration.between(oldest, newest).seconds / 150 + 1).toInt())

    private fun verdict(
        span: SyncSpan?,
        complete: Boolean = true,
        gapBefore: Boolean = false,
        firstSync: Boolean = false,
        generation: RingGeneration = RingGeneration.GEN2,
    ) = SyncMeasurement.capacity(span, complete, gapBefore, firstSync, now, generation)

    @Test
    fun noRecordsIsInconclusive() {
        val v = verdict(span = null)
        assertEquals(CapacityKind.INCONCLUSIVE, v.kind)
        assertEquals(InconclusiveReason.NO_RECORDS, v.reason)
        assertNull(v.span)
    }

    @Test
    fun aSyncThatDidNotDrainEveryChannelIsInconclusive() {
        val v = verdict(dense(now.minus(day.multipliedBy(3)), now), complete = false)
        assertEquals(CapacityKind.INCONCLUSIVE, v.kind)
        assertEquals(InconclusiveReason.NOT_COMPLETE, v.reason)
    }

    @Test
    fun exactlyHalfTheExpectedRecordsIsDenseEnoughOneFewerIsSparse() {
        // Two days less one epoch: 1,152 epochs expected; 576 is exactly half.
        val oldest = now.minus(day.multipliedBy(2)).plusSeconds(150)
        val half = verdict(SyncSpan(oldest, now, records = 576))
        assertEquals(CapacityKind.LOWER_BOUND, half.kind)
        assertEquals(Duration.ofSeconds(2 * 86_400 - 150), half.span)

        val sparse = verdict(SyncSpan(oldest, now, records = 575))
        assertEquals(CapacityKind.INCONCLUSIVE, sparse.kind)
        assertEquals(InconclusiveReason.SPARSE, sparse.reason)
    }

    @Test
    fun aFreshDenseSpanWithNoGapIsALowerBound() {
        val v = verdict(dense(now.minus(day.multipliedBy(3)), now.minusSeconds(60)))
        assertEquals(CapacityKind.LOWER_BOUND, v.kind)
        assertEquals(Duration.ofSeconds(3 * 86_400 - 60), v.span)
        assertNull(v.reason)
    }

    @Test
    fun aGapBeforeAFreshDenseSpanOfFourDaysMeansTheRingOverwritesItsOldest() {
        val atFour = verdict(dense(now.minus(day.multipliedBy(4)), now), gapBefore = true)
        assertEquals(CapacityKind.OVERWRITES_OLDEST, atFour.kind)
        assertEquals(day.multipliedBy(4), atFour.span)

        // One epoch short of four days: the gap may just be the ring left off a finger.
        val short = verdict(dense(now.minus(day.multipliedBy(4)).plusSeconds(150), now), gapBefore = true)
        assertEquals(CapacityKind.INCONCLUSIVE, short.kind)
        assertEquals(InconclusiveReason.GAP_UNEXPLAINED, short.reason)
    }

    @Test
    fun freshMeansAtMostThirtyMinutesOld() {
        val oldest = now.minus(day.multipliedBy(5))
        val fresh = verdict(dense(oldest, now.minus(Duration.ofMinutes(30))), gapBefore = true)
        assertEquals(CapacityKind.OVERWRITES_OLDEST, fresh.kind)

        val stale = verdict(dense(oldest, now.minus(Duration.ofMinutes(30)).minusSeconds(1)), gapBefore = true)
        assertEquals(CapacityKind.INCONCLUSIVE, stale.kind)
        assertEquals(InconclusiveReason.GAP_UNEXPLAINED, stale.reason)
    }

    @Test
    fun aDenseFourDaysEndingADayAgoWithNoGapMeansTheRingStoppedWhenFull() {
        val newest = now.minus(day)
        val stopped = verdict(dense(newest.minus(day.multipliedBy(4)), newest))
        assertEquals(CapacityKind.STOPS_WHEN_FULL, stopped.kind)
        assertEquals(day.multipliedBy(4), stopped.span)

        // Less than a day since the newest record: a ring on its charger looks the same.
        val charging = verdict(dense(newest.plusSeconds(1).minus(day.multipliedBy(4)), newest.plusSeconds(1)))
        assertEquals(CapacityKind.LOWER_BOUND, charging.kind)

        // A day old but only three days long: not enough to call the ring full.
        val short = verdict(dense(newest.minus(day.multipliedBy(3)), newest))
        assertEquals(CapacityKind.LOWER_BOUND, short.kind)
    }

    @Test
    fun aFirstSyncReachingTheNominalCapacityLessADayShowsOverwriting() {
        // Gen 2: nominal 7 days → 6 days.
        val gen2 = verdict(dense(now.minus(day.multipliedBy(6)), now), firstSync = true)
        assertEquals(CapacityKind.OVERWRITES_OLDEST, gen2.kind)
        val gen2Short = verdict(dense(now.minus(day.multipliedBy(6)).plusSeconds(150), now), firstSync = true)
        assertEquals(CapacityKind.LOWER_BOUND, gen2Short.kind)

        // Gen 3: nominal 10 days → 9 days; six days is only a lower bound.
        val gen3Six = verdict(dense(now.minus(day.multipliedBy(6)), now), firstSync = true, generation = RingGeneration.GEN3)
        assertEquals(CapacityKind.LOWER_BOUND, gen3Six.kind)
        val gen3Nine = verdict(dense(now.minus(day.multipliedBy(9)), now), firstSync = true, generation = RingGeneration.GEN3)
        assertEquals(CapacityKind.OVERWRITES_OLDEST, gen3Nine.kind)

        // Not a first sync: the same span with no gap is only a lower bound.
        assertEquals(CapacityKind.LOWER_BOUND, verdict(dense(now.minus(day.multipliedBy(6)), now)).kind)
    }

    @Test
    fun aSingleRecordIsALowerBoundOfNothing() {
        val v = verdict(SyncSpan(now, now, records = 1))
        assertEquals(CapacityKind.LOWER_BOUND, v.kind)
        assertEquals(Duration.ZERO, v.span)
    }
}
