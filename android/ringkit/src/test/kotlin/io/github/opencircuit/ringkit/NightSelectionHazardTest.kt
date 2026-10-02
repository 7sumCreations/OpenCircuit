package io.github.opencircuit.ringkit

import org.junit.jupiter.api.Timeout
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Kotlin-only hostile-input checks for night selection (`BulkSleep.latestNightRecords` and the
 * observed-gap guard's `bridgeIsDeclined`): empty and single-record archives, unsorted, duplicated
 * and far-future counters, cuts and gaps that are NaN, infinite, negative or above one, week-long
 * archives (time-bounded), DST nights, and the guard's exact boundaries. Kept out of the
 * upstream-port classes so their counts stay exact.
 *
 * Every expected value here was measured on upstream's pinned Swift build with the same records
 * (`count first last` of the returned slice), so each test pins upstream's outcome — except where
 * a test says it pins a deliberate, owner-approved improvement (PORTING D-69 onward); there the
 * upstream value is quoted beside the new one.
 */
class NightSelectionHazardTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val base: Instant = Instant.ofEpochSecond(1_780_000_000)
    private val dayStart: Instant = LocalDate.ofInstant(base, utc).atStartOfDay(utc).toInstant()

    private fun at(hour: Int, minute: Int = 0, day: Int = 0): Instant = dayStart.plus(Duration.ofDays(day.toLong())).plusSeconds(hour * 3600L + minute * 60L)

    private fun rec(counter: Long, still: Boolean, seed: Int = 0): BulkRecord {
        val b = ByteArray(BulkRecord.LENGTH)
        b[0] = (counter shr 24).toByte(); b[1] = (counter shr 16).toByte()
        b[2] = (counter shr 8).toByte(); b[3] = counter.toByte()
        if (still) {
            for (k in 10 until 15) b[k] = 1
        } else {
            val j = seed % 5
            b[10] = (8 + j).toByte(); b[11] = 12; b[12] = (20 + j).toByte(); b[13] = 6; b[14] = 10
        }
        return BulkRecord.of(b)!!
    }

    private fun counter(t: Instant): Long = t.epochSecond - Command.SYNC_EPOCH

    private fun still(from: Instant, to: Instant): List<BulkRecord> =
        (0 until Duration.between(from, to).seconds step 150).map { rec(counter(from.plusSeconds(it)), still = true) }

    private fun moving(from: Instant, to: Instant): List<BulkRecord> =
        (0 until Duration.between(from, to).seconds step 150).mapIndexed { i, s -> rec(counter(from.plusSeconds(s)), still = false, seed = i) }

    /** The slice as upstream's probe printed it: count, first counter, last counter. */
    private fun shape(r: List<BulkRecord>): Triple<Int, Long?, Long?> = Triple(r.size, r.firstOrNull()?.counter, r.lastOrNull()?.counter)

    private fun select(
        records: List<BulkRecord>,
        zone: ZoneId = utc,
        cut: Double = BulkSleep.OBSERVED_GAP_ABSORB_COVERAGE_CUT,
        morningGap: Duration = BulkSleep.MORNING_CONTINUATION_MAX_GAP,
        reanchor: Boolean = BulkSleep.DECLINED_BRIDGE_MAY_REANCHOR,
    ) = BulkSleep.latestNightRecords(
        records,
        zone = zone,
        observedGapCoverageCut = cut,
        morningContinuationGap = morningGap,
        declinedBridgeMayReanchor = reanchor,
    )

    private val nightOne = still(at(2), at(2).plusSeconds(72 * 150))
    private val nightTwo = still(at(2, day = 1), at(2, day = 1).plusSeconds(72 * 150))
    private val twoNights = nightTwo + nightOne

    // Archive shape

    @Test
    fun emptyAndSingleRecordArchivesAreReturnedAsIs() {
        assertEquals(emptyList(), select(emptyList()))
        val one = rec(counter(at(2)), still = true)
        assertEquals(listOf(one), select(listOf(one)))
    }

    @Test
    fun unsortedAndReversedArchivesSelectTheSameNight() {
        val expected = Triple(72, 202_226_400L, 202_237_050L)
        assertEquals(expected, shape(select(twoNights)))
        assertEquals(expected, shape(select(twoNights.reversed())))
        assertEquals(select(twoNights), select(twoNights.shuffled(java.util.Random(11))))
    }

    @Test
    fun duplicatedRecordsAreKeptTwiceExactlyAsUpstream() {
        // Selection filters records by time; it does not deduplicate (the archive merge does).
        // Upstream measured: 144 records, both copies of each of the 72.
        assertEquals(Triple(144, 202_226_400L, 202_237_050L), shape(select(twoNights.flatMap { listOf(it, it) })))
    }

    @Test
    fun farFutureCounterNeverJoinsTheNight() {
        val far = rec(0xFFFF_FFFFL, still = true)
        assertEquals(select(twoNights), select(twoNights + far), "a far-future epoch is outside the night window")
        assertEquals(listOf(far), select(listOf(far)), "alone it is no night, so the archive comes back unchanged")
    }

    // Hostile tuning values

    private val evening = still(at(20, 30), at(21, 40)) + moving(at(21, 40), at(22, 15)) + still(at(22, 15), at(6, 0, day = 1))

    @Test
    fun nanInfiniteAndUnreachableCutsBehaveExactlyLikeTheGuardOff() {
        val off = select(evening, cut = 0.0)
        assertEquals(Triple(228, 202_206_600L, 202_240_650L), shape(off))
        assertEquals(Triple(196, 202_211_400L, 202_240_650L), shape(select(evening)), "the shipped cut declines the evening")
        for (cut in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1.2, -1.0)) {
            assertEquals(off, select(evening, cut = cut), "cut $cut")
        }
    }

    @Test
    fun aNanCutAlsoSwitchesTheReanchorOff() {
        val shortTail = still(at(20, 30), at(2, 45, day = 1)) + moving(at(2, 45, day = 1), at(3, 45, day = 1)) + still(at(3, 45, day = 1), at(6, 0, day = 1))
        assertEquals(Triple(161, 202_206_600L, 202_230_600L), shape(select(shortTail)))
        assertEquals(Triple(228, 202_206_600L, 202_240_650L), shape(select(shortTail, cut = Double.NaN)))
        assertEquals(Triple(64, 202_231_200L, 202_240_650L), shape(select(shortTail, reanchor = false)))
    }

    @Test
    fun aDeclinedBridgeStopsTheChain() {
        // Three overnight blocks: A (75 min) — an EMPTY 2 h hole — B (75 min) — a fully observed
        // awake hour — C (4 h, the anchor). The bridge C→B is declined. Upstream `continue`s past it,
        // so the chain still reaches A across the unobserved hole and the window pulls B's records
        // back in (measured on the pinned build: 180 records from A's start, identical to the guard
        // switched off). Here a declined bridge ends the chain (an owner decision, PORTING D-69):
        // the night is C alone, with its 30 min margin.
        val a = still(at(21), at(22, 15))
        val b = still(at(0, 15, day = 1), at(1, 30, day = 1))
        val leap = a + b + moving(at(1, 30, day = 1), at(2, 30, day = 1)) + still(at(2, 30, day = 1), at(6, 30, day = 1))
        val night = select(leap)
        assertEquals(106, night.size, "upstream's leapfrog returned 180")
        assertTrue(night.none { it in a || it in b }, "neither block behind the declined bridge joins the night")
        assertEquals(Triple(180, 202_208_400L, 202_242_450L), shape(select(leap, cut = 0.0)), "with the guard off the chain still reaches A")
    }

    @Test
    fun aBridgeThatIsNotDeclinedStillChainsAcrossAnEmptyHole() {
        // The break is the only change: the same archive with B's awake hour left UNOBSERVED (an
        // empty hole, so the bridge C→B is not declined) still chains C → B → A across both holes,
        // exactly as upstream.
        val a = still(at(21), at(22, 15))
        val b = still(at(0, 15, day = 1), at(1, 30, day = 1))
        val c = still(at(2, 30, day = 1), at(6, 30, day = 1))
        val night = select(a + b + c)
        assertEquals(select(a + b + c, cut = 0.0), night, "no declined bridge, so the guard changes nothing")
        assertTrue(night.containsAll(a) && night.containsAll(b), "the whole cluster is kept")
    }

    @Test
    fun nonPositiveMorningGapsSwitchTheAbsorbOffAndAHugeOneChangesNothing() {
        val morning = still(at(2), at(8, 30)) + moving(at(8, 30), at(8, 45)) + still(at(8, 45), at(10, 30))
        val off = Triple(167, 202_140_000L, 202_164_900L)
        assertEquals(Triple(204, 202_140_000L, 202_170_450L), shape(select(morning)))
        assertEquals(off, shape(select(morning, morningGap = Duration.ZERO)))
        assertEquals(off, shape(select(morning, morningGap = Duration.ofHours(-1))))
        assertEquals(select(morning), select(morning, morningGap = Duration.ofSeconds(1_000_000_000_000L)))
    }

    // Week-long archives: bounded time, and one night out

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    fun weekLongStillRunFinishesAndIsCutToOneNightSpan() {
        val week = (0 until 7 * 576).map { rec(0x0c22_0000L + it * 150L, still = true) }
        assertEquals(Triple(348, 204_107_416L, 204_159_466L), shape(select(week)))
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    fun weekOfNightsAndDaysFinishesAndSelectsTheLastNight() {
        val week = (0 until 7).flatMap { d -> still(at(22, day = d), at(6, day = d + 1)) + moving(at(6, day = d + 1), at(22, day = d + 1)) }
        assertEquals(Triple(213, 202_728_900L, 202_760_700L), shape(select(week)))
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    fun weekOfManyNightCandidatesFinishes() {
        // 70-minute still blocks every 110 minutes for 7 days: every block clears the minimum sleep
        // duration, so the cluster and re-anchor loops see the most candidates a week can hold.
        val many = mutableListOf<BulkRecord>()
        var s = at(0)
        while (s.isBefore(at(0, day = 7))) {
            many += still(s, s.plusSeconds(70 * 60)) + moving(s.plusSeconds(70 * 60), s.plusSeconds(110 * 60))
            s = s.plusSeconds(110 * 60)
        }
        assertEquals(4048, many.size)
        assertEquals(Triple(49, 202_731_900L, 202_739_100L), shape(select(many)))
    }

    // Zones

    @Test
    fun nightsAcrossBothNewYorkDstChangesMatchUpstream() {
        val ny = ZoneId.of("America/New_York")
        fun local(date: LocalDate, h: Int, m: Int = 0): Instant = date.atTime(LocalTime.of(h, m)).atZone(ny).toInstant()
        val cases = listOf(
            LocalDate.of(2026, 3, 7) to listOf(Triple(189, 195_145_500L, 195_173_700L), Triple(48, 195_181_200L, 195_188_250L)),
            LocalDate.of(2026, 10, 31) to listOf(Triple(237, 215_705_100L, 215_740_500L), Triple(48, 215_748_000L, 215_755_050L)),
        )
        for ((day, expected) in cases) {
            val next = day.plusDays(1)
            val night = moving(local(day, 18), local(day, 22, 30)) + still(local(day, 22, 30), local(next, 6, 30)) + moving(local(next, 6, 30), local(next, 12))
            assertEquals(expected[0], shape(select(night, zone = ny)), "night of $day")
            val tailEnd = local(next, 11)
            val tail = moving(local(day, 8), local(day, 18)) + still(tailEnd.minusSeconds(7200), tailEnd)
            assertEquals(expected[1], shape(select(tail, zone = ny)), "truncated tail after $day")
        }
    }

    // bridgeIsDeclined boundaries

    @Test
    fun bridgeIsDeclinedBoundaries() {
        val e = base
        fun times(from: Long, to: Long, step: Long = 150) = (from until to step step).map { e.plusSeconds(it) }
        fun declined(gapSeconds: Long, recordTimes: List<Instant>, cut: Double) =
            BulkSleep.bridgeIsDeclined(e.plusSeconds(gapSeconds), e, recordTimes, cut)

        assertFalse(declined(450, times(1, 450), 0.5), "a gap of exactly the contiguity floor is never judged")
        assertTrue(declined(451, times(1, 451), 0.5), "one second more is judged")
        assertFalse(BulkSleep.bridgeIsDeclined(e, e.plusSeconds(3600), times(-100, 4000), 0.1), "a negative gap is never declined")
        assertTrue(declined(3600, times(75, 3600), 0.95), "a fully observed hour")
        assertFalse(declined(3600, times(75, 3600), Double.NaN), "a NaN cut is off")
        assertFalse(declined(3600, times(75, 3600), Double.POSITIVE_INFINITY), "an infinite cut never fires")
        assertFalse(declined(3600, listOf(e, e.plusSeconds(3600)), 0.01), "records exactly on the endpoints are not inside the gap")
        assertFalse(declined(3600, emptyList(), 0.95), "an empty hole is the stitch's own case")
        val half = times(75, 3600, step = 300)
        assertFalse(declined(3600, half, 0.95), "a half-observed hour")
        // Duplicated times count twice, upstream too: a half-observed hour with every record doubled
        // reads as complete. The archive merge deduplicates before selection ever sees it.
        assertTrue(declined(3600, half + half, 0.95), "duplicates inflate coverage exactly as upstream")
    }
}
