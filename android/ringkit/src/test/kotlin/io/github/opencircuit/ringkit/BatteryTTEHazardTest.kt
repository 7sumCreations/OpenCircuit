package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.BatteryTTE.Sample
import java.time.Instant
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kotlin-only hostile-input checks for the battery estimates: what the upstream vectors never feed
 * in. The percent is wire-derived (the descriptor's battery byte) and the two histories are stored
 * and read back, so here a percent arrives outside 0…100 — as a reading and as a stored sample —
 * samples arrive unsorted, with duplicated times or across the far ends of time, the history cap and
 * age are zero, negative or not a number, the charge target is outside 0…100, and `now` lies before
 * the samples. Kept out of the upstream-port class so its count stays exact.
 *
 * Every upstream outcome quoted below was measured on the pinned Swift build (Swift 6.3.2). Where the
 * port deliberately differs the test says so, and `PORTING.md` records why.
 */
class BatteryTTEHazardTest {

    private val t0: Instant = Instant.parse("2026-08-17T00:00:00Z")

    /** A sample [hours] after [t0] (upstream's test helper builds them the same way). */
    private fun s(pct: Int, hours: Double): Sample = Sample(pct, t0.plusMillis(Math.round(hours * 3_600_000)))
    private fun at(hours: Double): Instant = t0.plusMillis(Math.round(hours * 3_600_000))
    private fun percents(h: List<Sample>): List<Int> = h.map { it.percent }

    private val outOfRange = listOf(101, 255, -1, -5, Int.MAX_VALUE, Int.MIN_VALUE)
    private val noSamples: List<Sample> = emptyList()
    private val noPercents: List<Int> = emptyList()

    @Test
    fun aReadingOutsideZeroToOneHundredLeavesTheDischargeHistoryUnchanged() {
        // Upstream folds any Int in. Measured: 255, 101 or Int32.max while not charging is a "missed
        // charge" — the whole discharge history is replaced by [255]; −5 or Int32.min is a discharge
        // step and is appended; any of them while charging replaces the history. A descriptor byte the
        // decoder would never pass (0xFF) thus wipes a day of slope and stores a corrupt sample.
        // Here a percent outside 0…100 is not a reading: the history comes back unchanged.
        val base = listOf(s(80, 0.0), s(79, 1.0))
        for (pct in outOfRange) {
            for (charging in listOf(false, true)) {
                assertEquals(base, BatteryTTE.record(base, percent = pct, at = at(2.0), charging = charging), "reading $pct charging $charging")
            }
        }
        // The ends of the range are readings, as upstream: 0 is a discharge step, 100 a missed charge.
        assertEquals(listOf(80, 79, 0), percents(BatteryTTE.record(base, percent = 0, at = at(2.0), charging = false)))
        assertEquals(listOf(100), percents(BatteryTTE.record(base, percent = 100, at = at(2.0), charging = false)))
        assertEquals(listOf(0), percents(BatteryTTE.record(base, percent = 0, at = at(2.0), charging = true)))

        // Property: over seeded histories, any out-of-range reading changes nothing.
        val rng = Random(0x5BA77E5L)
        repeat(2_000) {
            val history = (0 until rng.nextInt(6)).map { k -> s(rng.nextInt(101), k.toDouble()) }
            val pct = outOfRange[rng.nextInt(outOfRange.size)]
            assertEquals(history, BatteryTTE.record(history, percent = pct, at = at(7.0), charging = rng.nextBoolean()))
        }
    }

    @Test
    fun aReadingOutsideZeroToOneHundredLeavesTheChargeHistoryUnchanged() {
        // Upstream measured: 255 and 101 while charging are appended as genuine rises (time to full then
        // reads 0 — "already full"); −5 is a ≥ 3 pp drop and resets the history to [−5].
        val charge = listOf(s(60, 0.0), s(62, 0.1))
        for (pct in outOfRange) {
            assertEquals(charge, BatteryTTE.recordCharge(charge, percent = pct, at = at(0.2), charging = true), "reading $pct")
            // Not charging still clears the charge history first, as upstream (its first guard).
            assertEquals(noSamples, BatteryTTE.recordCharge(charge, percent = pct, at = at(0.2), charging = false), "reading $pct unplugged")
        }
        assertEquals(listOf(60, 62, 100), percents(BatteryTTE.recordCharge(charge, percent = 100, at = at(0.2), charging = true)))
        assertEquals(listOf(59), percents(BatteryTTE.recordCharge(charge, percent = 59, at = at(0.2), charging = true)))
    }

    @Test
    fun estimatesIgnoreStoredSamplesOutsideZeroToOneHundred() {
        // Upstream measured: [250 @ 0 h, 80 @ 10 h] → 16 941.18 s to empty (a 17 %/h slope from a
        // corrupt sample); [90, 85, 101 @ 1.5 h, 80 @ 2 h] → 6 857.14 s (the 101 restarts the window);
        // time to full of [60, 255 @ 0.5 h] → 0 ("already full"), of [−5, 60 @ 0.5 h, 62 @ 0.6 h] →
        // 1 225.07 s. Here a stored sample outside 0…100 is not a sample.
        assertNull(BatteryTTE.timeToEmpty(listOf(s(250, 0.0), s(80, 10.0)), now = t0))
        assertEquals(57_600.0, BatteryTTE.timeToEmpty(listOf(s(90, 0.0), s(85, 1.0), s(101, 1.5), s(80, 2.0)), now = t0))
        assertNull(BatteryTTE.estimatedDepletionDate(listOf(s(250, 0.0), s(80, 10.0)), now = t0))
        assertNull(BatteryTTE.timeToFull(listOf(s(60, 0.0), s(255, 0.5)), now = t0))
        assertEquals(6_840.0, BatteryTTE.timeToFull(listOf(s(-5, 0.0), s(60, 0.5), s(62, 0.6)), now = t0))
        // Upstream answers nothing for [90, 80, −5 @ 2 h] (the window ends below 0); here the −5 is left
        // out and [90, 80] is a clean 10 %/h slope.
        assertEquals(28_800.0, BatteryTTE.timeToEmpty(listOf(s(90, 0.0), s(80, 1.0), s(-5, 2.0)), now = t0))
        // Upstream answers nothing here either (the rate guard trips); here nothing readable is left.
        assertNull(BatteryTTE.timeToEmpty(listOf(s(Int.MAX_VALUE, 0.0), s(Int.MIN_VALUE, 1.0)), now = t0))
        assertNull(BatteryTTE.timeToFull(listOf(s(Int.MIN_VALUE, 0.0), s(Int.MAX_VALUE - 5, 1.0)), now = t0, target = Int.MAX_VALUE))

        // Property: over seeded readable histories in any order, inserting unreadable samples anywhere
        // changes no estimate — every answer equals the answer for the readable samples alone.
        val rng = Random(0xE57L)
        var answered = 0
        repeat(3_000) {
            val readable = (0 until 2 + rng.nextInt(10)).map { Sample(rng.nextInt(101), t0.plusSeconds(60L * rng.nextInt(1_440))) }
            val mixed = readable.toMutableList()
            repeat(1 + rng.nextInt(3)) { mixed.add(rng.nextInt(mixed.size + 1), Sample(outOfRange[rng.nextInt(outOfRange.size)], t0.plusSeconds(60L * rng.nextInt(1_440)))) }
            val tte = BatteryTTE.timeToEmpty(readable, now = t0)
            assertEquals(tte, BatteryTTE.timeToEmpty(mixed, now = t0), "$mixed")
            assertEquals(BatteryTTE.estimatedDepletionDate(readable, now = t0), BatteryTTE.estimatedDepletionDate(mixed, now = t0))
            for (target in listOf(100, 80)) {
                assertEquals(BatteryTTE.timeToFull(readable, now = t0, target = target), BatteryTTE.timeToFull(mixed, now = t0, target = target), "$mixed")
            }
            if (tte != null) answered++
        }
        assertTrue(answered > 100, "the property must see real estimates, saw $answered")
    }

    @Test
    fun aFullBatteryOutsideZeroToOneHundredNeverFires() {
        // Upstream measured: 101, 255 and Int32.max all fire the "battery full" notification
        // (percent >= 100). Here only a reading of exactly 100 is full.
        assertTrue(BatteryTTE.justReachedFull(percent = 100, inferredCharging = true, wasFull = false))
        for (pct in listOf(101, 255, Int.MAX_VALUE, 99, 0, -5, Int.MIN_VALUE)) {
            assertFalse(BatteryTTE.justReachedFull(percent = pct, inferredCharging = true, wasFull = false), "percent $pct")
        }
    }

    @Test
    fun aNegativeHistoryCapKeepsNothingInsteadOfTrapping() {
        // Upstream measured: a cap below zero TRAPS in both folds ("Can't take a suffix of negative
        // length from a collection", exit 133), even on an empty history with Int.min. A cap of 0 keeps
        // nothing; here every negative cap keeps nothing too.
        val long = listOf(s(90, 0.0), s(89, 1.0), s(88, 2.0))
        for (cap in listOf(-1, -60, Int.MIN_VALUE, 0)) {
            assertEquals(noSamples, BatteryTTE.record(long, percent = 87, at = at(3.0), charging = false, cap = cap), "cap $cap")
            assertEquals(noSamples, BatteryTTE.record(noSamples, percent = 87, at = at(3.0), charging = false, cap = cap), "cap $cap, empty")
            assertEquals(noSamples, BatteryTTE.recordCharge(listOf(s(60, 0.0)), percent = 62, at = at(0.1), charging = true, cap = cap), "charge cap $cap")
        }
        assertEquals(listOf(87), percents(BatteryTTE.record(long, percent = 87, at = at(3.0), charging = false, cap = 1)))
        assertEquals(listOf(88, 87), percents(BatteryTTE.record(long, percent = 87, at = at(3.0), charging = false, cap = 2)))
        assertEquals(listOf(90, 89, 88, 87), percents(BatteryTTE.record(long, percent = 87, at = at(3.0), charging = false, cap = Int.MAX_VALUE)))
        assertEquals(listOf(64), percents(BatteryTTE.recordCharge(listOf(s(60, 0.0), s(62, 0.1)), percent = 64, at = at(0.2), charging = true, cap = 1)))
    }

    @Test
    fun percentComparisonsAreSixtyFourBitAsUpstreams() {
        // Upstream's Int is 64-bit. Measured: a stored last sample of Int32.min and a reading of 50 is a
        // ≥ 3 pp rise — the history resets to [50]. In 32-bit arithmetic 50 − Int.MIN wraps negative
        // and the corrupt sample would be kept.
        assertEquals(listOf(50), percents(BatteryTTE.record(listOf(s(Int.MIN_VALUE, 0.0)), percent = 50, at = at(1.0), charging = false)))
        // The charge fold, measured: a stored Int32.max then 50 is a ≥ 3 pp drop → [50]; a stored
        // Int32.min then 50 is a rise → appended.
        assertEquals(listOf(50), percents(BatteryTTE.recordCharge(listOf(s(Int.MAX_VALUE, 0.0)), percent = 50, at = at(1.0 / 60), charging = true)))
        assertEquals(listOf(Int.MIN_VALUE, 50), percents(BatteryTTE.recordCharge(listOf(s(Int.MIN_VALUE, 0.0)), percent = 50, at = at(1.0 / 60), charging = true)))
        // A stored 250 then 80 is a discharge step, appended (the estimate then ignores the 250).
        assertEquals(listOf(250, 80), percents(BatteryTTE.record(listOf(s(250, 0.0)), percent = 80, at = at(1.0), charging = false)))
        // Time to full toward the largest Int target, measured: 386 547 043 860 s, exact in 64 bits.
        assertEquals(386_547_043_860.0, BatteryTTE.timeToFull(listOf(s(60, 0.0), s(70, 0.5)), now = t0, target = Int.MAX_VALUE))
    }

    @Test
    fun historyAgesOfAnyValueBehaveAsUpstream() {
        // Measured: an age of 0 keeps the new reading only; −1 or −∞ keeps nothing (the cutoff lies after
        // the reading); NaN keeps everything (a NaN cutoff compares false, and upstream keeps a sample
        // unless it is before the cutoff); +∞ and 1e300 keep everything.
        val long = listOf(s(90, 0.0), s(89, 1.0), s(88, 2.0))
        fun aged(age: Double) = percents(BatteryTTE.record(long, percent = 87, at = at(3.0), charging = false, maxAge = age))
        assertEquals(listOf(87), aged(0.0))
        assertEquals(noPercents, aged(-1.0))
        assertEquals(listOf(88, 87), aged(3_600.0))
        assertEquals(listOf(90, 89, 88, 87), aged(Double.NaN))
        assertEquals(listOf(90, 89, 88, 87), aged(Double.POSITIVE_INFINITY))
        assertEquals(noPercents, aged(Double.NEGATIVE_INFINITY))
        assertEquals(listOf(90, 89, 88, 87), aged(1e300))
        val charge = listOf(s(60, 0.0), s(62, 0.1))
        fun chargeAged(age: Double) = percents(BatteryTTE.recordCharge(charge, percent = 64, at = at(0.2), charging = true, maxAge = age))
        assertEquals(noPercents, chargeAged(-1.0))
        assertEquals(listOf(60, 62, 64), chargeAged(Double.NaN))
        assertEquals(listOf(60, 62, 64), chargeAged(Double.POSITIVE_INFINITY))
    }

    @Test
    fun theDepletionDateSaturatesAtTheEndOfTimeInsteadOfThrowing() {
        // Two samples 6e16 s apart: upstream measured 2.94e18 s to empty and a depletion date
        // 2.94e18 s after 1970 — far past the last instant `java.time` holds (≈ 3.16e16 s), where
        // `Instant.plusSeconds` would throw. Here the date saturates at Instant.MAX.
        val span = listOf(Sample(100, Instant.ofEpochSecond(-30_000_000_000_000_000L)), Sample(98, Instant.ofEpochSecond(30_000_000_000_000_000L)))
        assertEquals(2.9400000000000005e18, BatteryTTE.timeToEmpty(span, now = t0))
        assertEquals(Instant.MAX, BatteryTTE.estimatedDepletionDate(span, now = t0))
        // A `now` near the end of time saturates too; a `now` before the samples is simply now + the
        // time to empty, as upstream (measured: ten 365-day years before t0 → 315 327 600 s before t0).
        val clean = listOf(s(100, 0.0), s(90, 1.0))
        assertEquals(Instant.MAX, BatteryTTE.estimatedDepletionDate(clean, now = Instant.MAX.minusSeconds(10)))
        assertEquals(t0.minusSeconds(315_327_600), BatteryTTE.estimatedDepletionDate(clean, now = t0.minusSeconds(10L * 365 * 86_400)))
        assertEquals(t0.plusSeconds(32_400), BatteryTTE.estimatedDepletionDate(clean, now = t0))
    }

    @Test
    fun duplicatedAndUnsortedTimesSortStablyAsUpstream() {
        // Upstream sorts by time with Swift's sort, which keeps equal times in input order (measured):
        // two readings sharing an instant are order-sensitive.
        assertEquals(36_000.0, BatteryTTE.timeToEmpty(listOf(s(90, 0.0), s(85, 1.0), s(80, 1.0), s(75, 2.0)), now = t0))
        assertEquals(27_000.0, BatteryTTE.timeToEmpty(listOf(s(90, 0.0), s(80, 1.0), s(85, 1.0), s(75, 2.0)), now = t0))
        val many = (0 until 40).map { i -> s(90 - i, (i / 10).toDouble()) }
        assertEquals(14_123.076923076922, BatteryTTE.timeToEmpty(many, now = t0))
        assertNull(BatteryTTE.timeToEmpty(many.reversed(), now = t0))
        val order = listOf(3, 17, 0, 39, 22, 8, 31, 12, 5, 27, 1, 36, 14, 20, 9, 33, 25, 2, 38, 11, 30, 6, 18, 24, 35, 4, 15, 28, 10, 37, 21, 7, 32, 13, 26, 19, 34, 16, 29, 23)
        assertNull(BatteryTTE.timeToEmpty(order.map { many[it] }, now = t0))
        assertEquals(57_600.0, BatteryTTE.timeToEmpty(listOf(s(80, 2.0), s(90, 0.0), s(85, 1.0)), now = t0))
        assertNull(BatteryTTE.timeToEmpty(listOf(s(90, 1.0), s(80, 1.0)), now = t0), "no time passed")
        assertEquals(1_350.0, BatteryTTE.timeToFull(listOf(s(60, 0.0), s(65, 0.1), s(62, 0.1), s(70, 0.2)), now = t0))
        assertEquals(2_160.0, BatteryTTE.timeToFull(listOf(s(60, 0.0), s(62, 0.1), s(65, 0.1), s(70, 0.2)), now = t0))
        // Fewer than two samples: nothing, before any sorting.
        assertNull(BatteryTTE.timeToEmpty(noSamples, now = t0))
        assertNull(BatteryTTE.timeToFull(listOf(s(60, 0.0)), now = t0))
    }

    @Test
    fun theEstimatesGuardsAndTargetsAtTheirBoundsAsUpstream() {
        // Measured on the pinned build.
        assertEquals(3_600.0, BatteryTTE.timeToEmpty(listOf(s(100, 0.0), s(50, 1.0)), now = t0), "exactly 50 %/h is plausible")
        assertNull(BatteryTTE.timeToEmpty(listOf(s(100, 0.0), s(49, 1.0)), now = t0), "51 %/h is a charger event")
        assertEquals(140_400.0, BatteryTTE.timeToEmpty(listOf(s(80, 0.0), s(78, 1.0)), now = t0), "exactly 2 pp is enough")
        assertNull(BatteryTTE.timeToEmpty(listOf(s(20, 0.0), s(0, 10.0)), now = t0), "an empty battery has no time to empty")
        assertEquals(600.0, BatteryTTE.timeToFull(listOf(Sample(0, t0), Sample(50, t0.plusSeconds(600))), now = t0), "exactly 300 %/h")
        assertNull(BatteryTTE.timeToFull(listOf(Sample(0, t0), Sample(51, t0.plusSeconds(600))), now = t0), "306 %/h")
        // A target outside 0…100 is the caller's: kept as upstream (no crash, no wrap).
        val charging = listOf(s(60, 0.0), s(70, 0.5))
        for ((target, expected) in listOf(100 to 5_400.0, 150 to 14_400.0, 101 to 5_580.0, 70 to 0.0, 69 to 0.0, 0 to 0.0, -5 to 0.0, Int.MIN_VALUE to 0.0)) {
            assertEquals(expected, BatteryTTE.timeToFull(charging, now = t0, target = target), "target $target")
        }
    }

    @Test
    fun readingsAcrossTheClockBehaveAsUpstream() {
        // Measured: a reading dated before the last sample is still a discharge step, appended at the
        // end; a reading a thousand years ahead prunes the whole history by age (the cutoff is 14 days
        // before it); an equal reading at the same time is jitter and changes nothing.
        val long = listOf(s(90, 0.0), s(89, 1.0), s(88, 2.0))
        val earlier = BatteryTTE.record(long, percent = 87, at = at(-5.0), charging = false)
        assertEquals(long + s(87, -5.0), earlier)
        assertEquals(listOf(s(87, 1_000.0 * 365 * 24)), BatteryTTE.record(long, percent = 87, at = at(1_000.0 * 365 * 24), charging = false))
        assertEquals(long, BatteryTTE.record(long, percent = 88, at = at(2.0), charging = false))
    }
}
