package io.github.opencircuit.ringkit

import org.junit.jupiter.api.Timeout
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Kotlin-only hostile-input checks for the active-energy write ledger: what the upstream vectors
 * never feed in. The saved watermarks, carry, workout credit and saved total are STORED values the
 * caller reads back on the next flush, so here they arrive NaN, infinite, negative, longer or
 * shorter than the day; bucket energy arrives NaN, infinite, negative or summing past the largest
 * double; bucket widths arrive tiny, sub-second, astronomically wide or infinite; buckets arrive
 * duplicated, unsorted, reversed, before the day, past its 26-hour span and thirty million years out.
 * Kept out of the upstream-port classes so their counts stay exact.
 *
 * The rule under test: energy is planned once. A corrupt stored value is never trusted into a write —
 * the plan writes nothing and hands the state back unchanged — and nothing here may crash, hang or
 * allocate without bound. Every upstream outcome quoted below was measured on the pinned Swift build
 * (Swift 6.3.2); where Kotlin deliberately differs the test says so, and `PORTING.md` records why.
 */
class ActiveEnergyLedgerHazardTest {

    private val day: Instant = Instant.ofEpochSecond(1_753_660_800)
    private fun at(hours: Double): Instant = day.plusMillis(Math.round(hours * 3_600_000))
    private fun bucket(hour: Double, kcal: Double, width: Double = 900.0) =
        Calories.EnergyBucket(start = at(hour), end = addingSeconds(at(hour), width)!!, hrKcal = kcal, stepKcal = 0.0, elevatedMinutes = 0.0)
    private val base = listOf(bucket(8.0, 40.0), bucket(9.0, 25.0))
    private fun zeros(n: Int, then: Double): List<Double> = List(n) { 0.0 } + then
    private fun bits(xs: List<Double>): List<Long> = xs.map { it.toRawBits() }

    private fun plan(
        buckets: List<Calories.EnergyBucket>,
        marks: List<Double>,
        now: Instant = at(12.0),
        carry: Double = 0.0,
        credit: Double = 0.0,
        saved: Double = 0.0,
        width: Double = 900.0,
    ) = ActiveEnergyLedger.plan(buckets, marks, dayStart = day, now = now, carry = carry, uncreditedWorkoutKcal = credit, savedKcal = saved, bucketSeconds = width)

    /** The plan that writes nothing and hands back exactly the state it was given. */
    private fun assertUnchanged(p: ActiveEnergyLedger.Plan, marks: List<Double>, carry: Double, why: String) {
        assertTrue(p.writes.isEmpty(), "$why: nothing is written, got ${p.writes}")
        assertEquals(bits(marks), bits(p.watermarks), "$why: the watermarks come back as given")
        assertEquals(carry.toRawBits(), p.carryRemaining.toRawBits(), "$why: the carry comes back as given")
        assertEquals(0.0, p.workoutConsumed, "$why: no workout credit is consumed")
    }

    private fun writes(p: ActiveEnergyLedger.Plan): List<Triple<Long, Long, Double>> =
        p.writes.map { Triple(secondsBetween(day, it.start).toLong(), secondsBetween(day, it.end).toLong(), it.kcal) }

    @Test
    fun ordinalAtWidthsOutsideTheGridAndInstantsFarFromTheDay() {
        // A width that is not positive gives 0, as upstream (measured: 0, -1, NaN); infinite gives 0.
        for (w in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) assertEquals(0L, ActiveEnergyLedger.ordinal(at(5.0), day, w), "width $w")
        assertEquals(0L, ActiveEnergyLedger.ordinal(at(-5.0), day, Double.POSITIVE_INFINITY))
        // Floor, not truncation: one second before the day is ordinal -1; the edge belongs to the next bucket.
        assertEquals(-1L, ActiveEnergyLedger.ordinal(day.minusSeconds(1), day, 900.0))
        assertEquals(0L, ActiveEnergyLedger.ordinal(day.plusMillis(899_750), day, 900.0))
        assertEquals(1L, ActiveEnergyLedger.ordinal(day.plusSeconds(900), day, 900.0))
        // Swift's Int is 64-bit: a far instant's ordinal leaves 32 bits (measured on the pinned build).
        assertEquals(33_333_331_384_821L, ActiveEnergyLedger.ordinal(Instant.ofEpochSecond(30_000_000_000_000_000), day, 900.0))
        assertEquals(29_999_998_246_339_200L, ActiveEnergyLedger.ordinal(Instant.ofEpochSecond(30_000_000_000_000_000), day, 1.0))
        assertEquals(-30_000_001_753_660_800L, ActiveEnergyLedger.ordinal(Instant.ofEpochSecond(-30_000_000_000_000_000), day, 1.0))
        assertEquals(93_600_000L, ActiveEnergyLedger.ordinal(at(26.0), day, 1e-3))
        assertEquals(0L, ActiveEnergyLedger.ordinal(at(26.0), day, 1e9))
        // Upstream traps converting 1e-300 s's quotient to Int (measured); the port saturates instead.
        assertEquals(Long.MAX_VALUE, ActiveEnergyLedger.ordinal(at(1.0), day, 1e-300))
        assertEquals(Long.MIN_VALUE, ActiveEnergyLedger.ordinal(at(-1.0), day, Double.MIN_VALUE))
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun aBucketWidthOutsideOneSecondToOneBillionSecondsPlansNothing() {
        // Upstream sizes the watermark array by ordinal: measured, a 1 ms width put a bucket at 1 h in
        // slot 3.6 million, 1e-300 s traps on the Int conversion, and 0.5 s builds 57 601 marks for an
        // 08:00 bucket. 2e9 s and infinity fold every bucket of the day into slot 0. The port's grid is
        // the daily estimate's — one second to one billion seconds — and outside it the plan writes
        // nothing and hands the state back. At 1e-6 s this bucket would need 91.8 billion slots.
        for (w in listOf(1e-6, 1e-300, Double.MIN_VALUE, 0.5, 0.999, 1.000000001e9, 2e9, Double.POSITIVE_INFINITY)) {
            assertUnchanged(plan(listOf(bucket(25.5, 40.0, width = 1.0)), listOf(3.0), carry = 7.0, width = w, now = at(26.0)), listOf(3.0), 7.0, "width $w")
        }
        // One second is the finest grid: a bucket late in the day still plans, with one mark per second.
        val fine = plan(listOf(bucket(25.5, 40.0, width = 1.0)), emptyList(), now = at(26.0), width = 1.0)
        assertEquals(listOf(Triple(91_800L, 91_801L, 40.0)), writes(fine))
        assertEquals(91_801, fine.watermarks.size)
        // One billion seconds is the widest: the whole day is slot 0, as upstream folds it.
        assertEquals(listOf(Triple(28_800L, 29_700L, 40.0)), writes(plan(listOf(bucket(8.0, 40.0)), emptyList(), width = 1e9)))
        // The seed declines the same widths the way upstream declines a width of 0: everything is carry.
        for (w in listOf(1e-6, 1e-300, 0.5, 2e9, Double.POSITIVE_INFINITY, 0.0, Double.NaN)) {
            val s = ActiveEnergyLedger.seed(listOf(bucket(25.5, 40.0, width = 1.0)), legacyWrittenKcal = 50.0, dayStart = day, bucketSeconds = w)
            assertEquals(emptyList(), s.watermarks, "width $w")
            assertEquals(50.0, s.carry, "width $w")
        }
    }

    @Test
    fun bucketsPastTheDaysSpanAreSkippedLikeBucketsBeforeIt() {
        // The daily estimate places energy at most 26 h past the day start. Upstream extends the marks to
        // any ordinal: a 30 h bucket is written and grows the array to 121 (measured), one ten years out
        // to 350 401, and one at 3e16 s fails to allocate 2.7e14 bytes. The port skips a bucket past the
        // day's span exactly as upstream skips one before the day: not this day's energy.
        val late = plan(listOf(bucket(30.0, 40.0), bucket(9.0, 25.0)), emptyList(), now = at(31.0))
        assertEquals(listOf(Triple(32_400L, 33_300L, 25.0)), writes(late))
        assertEquals(37, late.watermarks.size)
        // The last ordinal of the span (26 h, slot 104) still belongs to the day; the next does not.
        assertEquals(listOf(Triple(93_600L, 94_500L, 10.0)), writes(plan(listOf(bucket(26.0, 10.0)), emptyList(), now = at(27.0))))
        assertTrue(plan(listOf(bucket(26.25, 10.0)), emptyList(), now = at(27.0)).writes.isEmpty())
        val far = Calories.EnergyBucket(Instant.ofEpochSecond(30_000_000_000_000_000), Instant.ofEpochSecond(30_000_000_000_000_900), 40.0, 0.0, 0.0)
        val tenYears = bucket(24.0 * 365 * 10, 40.0)
        for (b in listOf(far, tenYears)) {
            val p = plan(listOf(b, bucket(9.0, 25.0)), emptyList(), now = Instant.MAX)
            assertEquals(listOf(Triple(32_400L, 33_300L, 25.0)), writes(p))
            assertEquals(37, p.watermarks.size)
        }
        // The seed skips the same buckets; upstream's 30 h bucket took 40 of the 50 legacy kcal.
        val seeded = ActiveEnergyLedger.seed(listOf(bucket(30.0, 40.0), bucket(-1.0, 7.0)), legacyWrittenKcal = 50.0, dayStart = day)
        assertEquals(emptyList(), seeded.watermarks)
        assertEquals(50.0, seeded.carry)
    }

    @Test
    fun anUnreadableStoredWatermarkIsNeverTrustedIntoAWrite() {
        // Upstream (measured): a NaN mark on the 08:00 slot strands that bucket and keeps the NaN; a mark
        // of -40 there writes 65 kcal into a 40 kcal bucket (the day-total clamp is all that stops more,
        // and only when the caller passes the saved total); -infinity writes 65 and stores NaN; +infinity
        // turns into infinite debt. The port treats any NaN, infinite or negative mark as a corrupt record.
        val corrupt = listOf(
            zeros(32, Double.NaN), zeros(32, -40.0), zeros(32, Double.NEGATIVE_INFINITY), zeros(32, Double.POSITIVE_INFINITY),
            listOf(Double.NaN), zeros(50, -7.0), zeros(50, Double.NaN), zeros(36, -1e-300),
        )
        for (marks in corrupt) {
            assertUnchanged(plan(base, marks), marks, 0.0, "marks $marks")
            assertUnchanged(plan(base, marks, saved = 40.0), marks, 0.0, "marks $marks with a saved total")
        }
        // Signed zero is a readable zero: the day writes normally.
        assertEquals(65.0, plan(base, zeros(32, -0.0)).totalKcal)
        assertEquals(65.0, plan(base, List(37) { -0.0 }).totalKcal)
    }

    @Test
    fun anUnreadableCarryWorkoutCreditOrSavedTotalWritesNothing() {
        // Upstream (measured): a NaN carry, credit or saved total counts as 0 and the day is written in
        // full (65 kcal) — the debt or the saved total that could not be read is simply forgotten. An
        // infinite one writes nothing. The port writes nothing for any NaN or infinite value.
        for (x in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertUnchanged(plan(base, emptyList(), carry = x), emptyList(), x, "carry $x")
            assertUnchanged(plan(base, emptyList(), credit = x), emptyList(), 0.0, "credit $x")
            assertUnchanged(plan(base, emptyList(), saved = x), emptyList(), 0.0, "saved $x")
        }
        // A negative carry, credit or saved total counts as zero, as upstream (measured: 65 kcal written).
        assertEquals(65.0, plan(base, emptyList(), carry = -5.0).totalKcal)
        assertEquals(65.0, plan(base, emptyList(), credit = -5.0).totalKcal)
        assertEquals(0.0, plan(base, emptyList(), credit = -5.0).workoutConsumed)
        assertEquals(65.0, plan(base, emptyList(), saved = -5.0).totalKcal)
    }

    @Test
    fun bucketEnergyThatIsNotAFiniteNonNegativeNumberWritesNothing() {
        // Upstream (measured): a NaN bucket writes nothing (the day total is NaN); +infinity writes an
        // infinite sample; -infinity writes nothing; -10 kcal at 08:00 cuts the 09:00 write to 15 through
        // the day-total clamp; two 1e308 buckets write a day whose total is infinite.
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -10.0, -Double.MIN_VALUE)) {
            assertUnchanged(plan(listOf(bucket(8.0, bad), bucket(9.0, 25.0)), emptyList()), emptyList(), 0.0, "bucket $bad")
        }
        assertUnchanged(plan(listOf(bucket(8.0, 1e308), bucket(8.25, 1e308)), emptyList()), emptyList(), 0.0, "a day total past the largest double")
        assertUnchanged(plan(listOf(bucket(8.0, 1e308), bucket(8.0, 1e308)), emptyList()), emptyList(), 0.0, "one slot past the largest double")
        // A hostile bucket the plan does not read (before the day) blocks nothing, as upstream.
        assertEquals(listOf(Triple(32_400L, 33_300L, 25.0)), writes(plan(listOf(bucket(-1.0, Double.NaN), bucket(9.0, 25.0)), emptyList())))
        // Finite energy is planned as upstream, however large (the writer range-checks it).
        assertEquals(1e308, plan(listOf(bucket(8.0, 1e308)), emptyList()).totalKcal)
        assertEquals(65.0, plan(listOf(bucket(8.0, 40.0), bucket(8.5, -0.0), bucket(9.0, 25.0)), emptyList()).totalKcal)
    }

    @Test
    fun storedWatermarksLongerOrShorterThanTheDayKeepUpstreamsMeaning() {
        // A positive mark past the day's buckets is energy that left the day: upstream nets it into debt
        // (measured: 33 + 25 written for a 7 kcal mark at slot 50), which only ever reduces a write.
        val longer = plan(base, zeros(50, 7.0))
        assertEquals(listOf(Triple(28_800L, 29_700L, 33.0), Triple(32_400L, 33_300L, 25.0)), writes(longer))
        assertEquals(51, longer.watermarks.size)
        assertEquals(0.0, longer.watermarks[50])
        assertEquals(0.0, longer.carryRemaining)
        val zeros = plan(base, List(200) { 0.0 })
        assertEquals(65.0, zeros.totalKcal)
        assertEquals(200, zeros.watermarks.size, "a longer record keeps its length")
        val shorter = plan(base, listOf(0.0, 0.0, 0.0))
        assertEquals(65.0, shorter.totalKcal)
        assertEquals(37, shorter.watermarks.size, "a shorter record is extended with zeros")
    }

    @Test
    fun duplicatedUnsortedReversedAndOutOfReachBucketsAsUpstream() {
        // Two buckets on one slot add up (one write of 80); the window is the later one's, as upstream.
        assertEquals(listOf(Triple(28_800L, 29_700L, 80.0)), writes(plan(listOf(bucket(8.0, 40.0), bucket(8.0, 40.0)), emptyList())))
        assertEquals(listOf(Triple(28_800L, 29_700L, 80.0)), writes(plan(listOf(bucket(8.0, 40.0, width = 600.0), bucket(8.0, 40.0)), emptyList())))
        assertEquals(writes(plan(base, emptyList())), writes(plan(base.reversed(), emptyList())))
        // A bucket that ends before it starts has no window to write in: nothing, and nothing marked.
        val reversed = Calories.EnergyBucket(at(8.0), at(7.0), 40.0, 0.0, 0.0)
        assertUnchanged(plan(listOf(reversed), emptyList()), emptyList(), 0.0, "reversed bucket")
        // Before the day (also a bucket straddling midnight, slot -1): skipped.
        assertEquals(listOf(Triple(32_400L, 33_300L, 25.0)), writes(plan(listOf(bucket(-1.0, 40.0), bucket(9.0, 25.0)), emptyList())))
        assertEquals(listOf(Triple(32_400L, 33_300L, 25.0)), writes(plan(listOf(bucket(-0.1, 40.0), bucket(9.0, 25.0)), emptyList())))
        // A clock before the day, or at a bucket's start: the energy stays owed.
        assertUnchanged(plan(base, emptyList(), now = at(-1.0)), emptyList(), 0.0, "now before the day")
        assertUnchanged(plan(listOf(bucket(8.0, 40.0)), emptyList(), now = at(8.0)), emptyList(), 0.0, "now at the bucket's start")
        // An unreadable minimum write is never reached (NaN compares false), as upstream.
        val p = ActiveEnergyLedger.plan(listOf(bucket(8.0, 40.0)), emptyList(), dayStart = day, now = at(12.0), minWriteKcal = Double.NaN)
        assertUnchanged(p, emptyList(), 0.0, "NaN minimum write")
    }

    @Test
    fun aRePlanNeverRewritesEnergyAndEveryHandedBackStateIsReadable() {
        // Seeded random days, each flushed twelve times while its buckets grow, fall, move and appear,
        // with a workout credit now and then; each plan's state is committed before the next. Properties
        // of every answer: every write is a finite positive amount over a non-empty window; the total
        // written never exceeds the largest day total seen; the state handed back is always one the next
        // plan accepts (so the fail-closed rule can never wedge a valid day); and planning the same inputs
        // again after a commit writes nothing.
        val rng = Random(20_261_002)
        var plans = 0
        var wrote = 0
        repeat(300) { dayIndex ->
            val kcal = HashMap<Int, Double>()
            var marks: List<Double> = emptyList()
            var carry = 0.0
            var saved = 0.0
            var credited = 0.0
            var maxTotal = 0.0
            for (flush in 1..12) {
                repeat(rng.nextInt(1, 6)) {
                    val o = rng.nextInt(0, 104)
                    kcal[o] = when (rng.nextInt(10)) {
                        0 -> 0.0
                        1 -> (kcal[o] ?: 0.0) * rng.nextDouble()
                        else -> (kcal[o] ?: 0.0) + rng.nextDouble() * 30
                    }
                }
                val buckets = kcal.entries.shuffled(rng).map { (o, k) ->
                    Calories.EnergyBucket(day.plusSeconds(o * 900L), day.plusSeconds(o * 900L + 900), k, 0.0, 0.0)
                }
                val now = day.plusSeconds(flush * 7_800L)
                val workout = if (dayIndex % 3 == 0 && flush >= 6) 45.0 else 0.0
                val p = ActiveEnergyLedger.plan(buckets, marks, day, now, carry, workout - credited, saved)
                plans++
                val dayTotal = kcal.toSortedMap().values.fold(0.0) { a, b -> a + b }
                maxTotal = maxOf(maxTotal, dayTotal)
                for (w in p.writes) {
                    assertTrue(w.kcal.isFinite() && w.kcal > 0 && w.start < w.end && w.end <= now, "day $dayIndex flush $flush: $w")
                }
                assertTrue(p.watermarks.all { it.isFinite() && it >= 0 } && p.carryRemaining.isFinite() && p.carryRemaining >= 0, "day $dayIndex flush $flush: state")
                marks = p.watermarks
                carry = p.carryRemaining
                saved += p.totalKcal
                credited += p.workoutConsumed
                if (p.writes.isNotEmpty()) wrote++
                assertTrue(saved <= maxTotal + 1e-9, "day $dayIndex flush $flush: wrote $saved of a day never worth more than $maxTotal")
                val again = ActiveEnergyLedger.plan(buckets, marks, day, now, carry, workout - credited, saved)
                assertTrue(again.writes.isEmpty(), "day $dayIndex flush $flush: the same inputs planned twice wrote ${again.writes}")
            }
        }
        assertTrue(wrote > plans / 4, "the sweep must exercise writing: $wrote of $plans plans wrote")
    }

    @Test
    fun seedAddsBucketsSharingASlotSoTheNextPlanNeverRepays() {
        // Upstream overwrites the slot: two 40 kcal buckets on one slot with 80 kcal already in Health
        // seed a mark of 40, and the next plan writes those 40 kcal a second time (measured). The port
        // adds them: the mark is 80 and the plan writes nothing.
        val two = listOf(bucket(8.0, 40.0), bucket(8.0, 40.0))
        val seeded = ActiveEnergyLedger.seed(two, legacyWrittenKcal = 80.0, dayStart = day)
        assertEquals(80.0, seeded.watermarks[32])
        assertEquals(33, seeded.watermarks.size)
        assertEquals(0.0, seeded.carry)
        assertTrue(plan(two, seeded.watermarks, carry = seeded.carry).writes.isEmpty())
        // Three on one slot with only part of their energy written: the slot holds what was consumed.
        val three = ActiveEnergyLedger.seed(two + bucket(8.0, 10.0), legacyWrittenKcal = 70.0, dayStart = day)
        assertEquals(70.0, three.watermarks[32])
        assertEquals(20.0, plan(two + bucket(8.0, 10.0), three.watermarks, carry = three.carry).totalKcal)
        // Unsorted input seeds chronologically, as upstream (measured: 40 then 10 of 50).
        val unsorted = ActiveEnergyLedger.seed(base.reversed(), legacyWrittenKcal = 50.0, dayStart = day)
        assertEquals(40.0, unsorted.watermarks[32])
        assertEquals(10.0, unsorted.watermarks[36])
    }

    @Test
    fun seedOfAnUnreadableLegacyTotalOrBucketPlacesNothingItCannotRead() {
        // A NaN legacy total: upstream reads it as 0 and the next plan writes the whole day again
        // (measured: 65 kcal). The port reads it as covering the day, as upstream already treats an
        // infinite one: every bucket full, infinite debt, nothing more written that day.
        for (legacy in listOf(Double.NaN, Double.POSITIVE_INFINITY)) {
            val s = ActiveEnergyLedger.seed(base, legacyWrittenKcal = legacy, dayStart = day)
            assertEquals(40.0, s.watermarks[32], "legacy $legacy")
            assertEquals(25.0, s.watermarks[36], "legacy $legacy")
            assertEquals(Double.POSITIVE_INFINITY, s.carry, "legacy $legacy")
            assertTrue(plan(base + bucket(10.0, 30.0), s.watermarks, carry = s.carry).writes.isEmpty(), "legacy $legacy")
        }
        // A negative legacy total counts as nothing written, as upstream.
        val negative = ActiveEnergyLedger.seed(base, legacyWrittenKcal = -5.0, dayStart = day)
        assertEquals(0.0, negative.watermarks.sum())
        assertEquals(0.0, negative.carry)
        // Bucket energy it cannot read: upstream (measured) stores a NaN mark and NaN carry for NaN, a
        // mark of -10 and a carry of 35 for -10 kcal, and fills 50 of an infinite bucket. The port places
        // nothing and carries the whole legacy total as debt — upstream's answer for an unusable width.
        for (bad in listOf(Double.NaN, -10.0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val s = ActiveEnergyLedger.seed(listOf(bucket(8.0, bad), bucket(9.0, 25.0)), legacyWrittenKcal = 50.0, dayStart = day)
            assertEquals(emptyList(), s.watermarks, "bucket $bad")
            assertEquals(50.0, s.carry, "bucket $bad")
        }
        // Nothing to place: an empty day carries everything, as upstream.
        assertEquals(50.0, ActiveEnergyLedger.seed(emptyList(), legacyWrittenKcal = 50.0, dayStart = day).carry)
    }
}
