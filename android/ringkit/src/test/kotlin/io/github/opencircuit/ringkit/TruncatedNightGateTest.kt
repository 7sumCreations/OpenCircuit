package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Port of upstream TruncatedNightGateTests.swift (@ b1c2fdd) — the TRUNCATED-NIGHT correction to
 * the overnight gate. A night can reach us as a TAIL only (a missed overnight drain, a re-pair, the
 * ring off the finger for the first half); the observed midpoint then drifts towards morning and
 * the plain midpoint rule discards the whole night. Measured upstream on a real Gen 2 Air archive
 * whose night ended 08-02 11:21 local: 2 h, 3 h and 4 h tails were DISCARDED, 4.75 h and 6 h passed.
 *
 * Three pieces, tested in this order: (1) `SleepWindow.isOvernightBlock(..., onsetIsUnobserved)` —
 * pure date math, NOT safe alone; (2) `BulkSleep.onsetIsUnobserved` — the presumed data must
 * genuinely be MISSING; (3) the call site, `BulkSleep.latestNightRecords` — the correction runs
 * only when the plain rule accepted nothing. The call-site block is the important half.
 */
class TruncatedNightGateTest {

    /** Fixed UTC zone for the PURE date-math tests. */
    private val utc: ZoneId = ZoneOffset.UTC

    private fun date(iso: String): Instant = Instant.parse(iso)

    /** The measured night's wake: 08-02 11:21. */
    private fun wake(): Instant = date("2026-08-02T11:21:00Z")

    /** A retained tail of [hours], i.e. the night as the archive actually holds it. */
    private fun tail(hours: Double): Pair<Instant, Instant> {
        val end = wake()
        return end.minusSeconds((hours * 3600).toLong()) to end
    }

    // 1. pure date math — the measured table

    /** 2 h tail (09:21 → 11:21): plain midpoint 10:21 DISCARDED; presumed 7 h onset gives 07:51 → accepted. */
    @Test
    fun testTwoHourTailIsAcceptedWhenOnsetIsUnobserved() {
        val (s, e) = tail(2.0)
        assertFalse(SleepWindow.isOvernightBlock(s, e, utc), "precondition: the plain rule discards this night")
        assertTrue(SleepWindow.isOvernightBlock(s, e, onsetIsUnobserved = true, zone = utc), "a 2 h tail whose onset was never recorded is still overnight")
    }

    /** 3 h tail (08:21 → 11:21), observed midpoint 09:51 → was discarded. */
    @Test
    fun testThreeHourTailIsAcceptedWhenOnsetIsUnobserved() {
        val (s, e) = tail(3.0)
        assertFalse(SleepWindow.isOvernightBlock(s, e, utc))
        assertTrue(SleepWindow.isOvernightBlock(s, e, onsetIsUnobserved = true, zone = utc))
    }

    /** 4 h tail (07:21 → 11:21), observed midpoint 09:21 → was discarded (the boundary case). */
    @Test
    fun testFourHourTailIsAcceptedWhenOnsetIsUnobserved() {
        val (s, e) = tail(4.0)
        assertFalse(SleepWindow.isOvernightBlock(s, e, utc))
        assertTrue(SleepWindow.isOvernightBlock(s, e, onsetIsUnobserved = true, zone = utc))
    }

    /** 4.75 h tail (06:36 → 11:21), observed midpoint 08:58 — the plain rule already passed it. */
    @Test
    fun testFullBufferTailPassedBeforeAndAfter() {
        val (s, e) = tail(4.75)
        assertTrue(SleepWindow.isOvernightBlock(s, e, utc), "midpoint 08:58 — the plain rule already accepted this")
        assertTrue(SleepWindow.isOvernightBlock(s, e, onsetIsUnobserved = true, zone = utc))
    }

    /** 6 h tail (05:21 → 11:21), observed midpoint 08:21 — accepted before and after. */
    @Test
    fun testSixHourTailPassedBeforeAndAfter() {
        val (s, e) = tail(6.0)
        assertTrue(SleepWindow.isOvernightBlock(s, e, utc))
        assertTrue(SleepWindow.isOvernightBlock(s, e, onsetIsUnobserved = true, zone = utc))
    }

    /** An afternoon nap (12:00 → 14:30): presumed onset 07:30, midpoint 11:00 → still REJECTED. */
    @Test
    fun testAfternoonNapStillRejected() {
        assertFalse(
            SleepWindow.isOvernightBlock(date("2026-08-02T12:00:00Z"), date("2026-08-02T14:30:00Z"), onsetIsUnobserved = true, zone = utc),
            "presumed start 07:30 → midpoint 11:00 is daytime",
        )
    }

    /** An evening block (19:00 → 22:00): presumed onset 15:00, midpoint 18:30 → still REJECTED. */
    @Test
    fun testEveningBlockStillRejected() {
        assertFalse(
            SleepWindow.isOvernightBlock(date("2026-08-02T19:00:00Z"), date("2026-08-02T22:00:00Z"), onsetIsUnobserved = true, zone = utc),
            "presumed start 15:00 → midpoint 18:30 is evening, not night",
        )
    }

    /**
     * THE DATE MATH IS NOT A SAFETY GATE. Once a block is shorter than the presumed span the rule
     * degenerates to "accept iff the wake falls in [00:30, 12:30)", so a 10:29 → 12:29 desk morning
     * is accepted BY THE ARITHMETIC. What rejects it is `onsetIsUnobserved` and the two-pass filter.
     */
    @Test
    fun testDateMathAloneCannotRejectASedentaryMorning() {
        val start = date("2026-08-02T10:29:00Z")
        val end = date("2026-08-02T12:29:00Z")
        assertFalse(SleepWindow.isOvernightBlock(start, end, utc), "observed midpoint 11:29 → the plain rule rejects it")
        assertTrue(
            SleepWindow.isOvernightBlock(start, end, onsetIsUnobserved = true, zone = utc),
            "date math alone accepts it — which is exactly why the flag must be earned",
        )
        // The date math is a function of `end` alone once the block is shorter than the presumed span.
        for (durationMinutes in listOf(61, 90, 120, 240)) {
            val s = end.minusSeconds(durationMinutes * 60L)
            assertTrue(SleepWindow.isOvernightBlock(s, end, onsetIsUnobserved = true, zone = utc), "dur=${durationMinutes}m: duration-blind by construction")
        }
    }

    /**
     * REGRESSION GUARD for the design itself: an early-evening night (20:30 → 23:30) has observed
     * midpoint 22:00 (accepted) but presumed-onset midpoint 20:00 (not overnight). Judging the
     * presumed midpoint ALONE would reject a night the plain rule accepted.
     */
    @Test
    fun testEarlyEveningOnsetAtLeadingEdgeStillAccepted() {
        val start = date("2026-08-01T20:30:00Z")
        val end = date("2026-08-01T23:30:00Z")
        assertTrue(SleepWindow.isOvernightBlock(start, end, utc), "precondition: the plain rule accepts this (midpoint 22:00)")
        assertFalse(
            SleepWindow.isOvernightBlock(end.minus(SleepWindow.PRESUMED_TRUNCATED_NIGHT_SPAN), end, utc),
            "precondition: the PRESUMED-onset midpoint alone (20:00) would be rejected",
        )
        assertTrue(
            SleepWindow.isOvernightBlock(start, end, onsetIsUnobserved = true, zone = utc),
            "the OR keeps it accepted — a plain substitution would have regressed it",
        )
    }

    /** NEVER LESS ACCEPTING: over a dense sweep, the corrected form accepts everything the plain form accepts. */
    @Test
    fun testNeverLessAcceptingThanThePlainRule() {
        val base = date("2026-08-02T00:00:00Z")
        for (startMinutes in 0 until 24 * 60 step 7) {
            for (durationMinutes in 30..12 * 60 step 13) {
                val start = base.plusSeconds(startMinutes * 60L)
                val end = start.plusSeconds(durationMinutes * 60L)
                if (SleepWindow.isOvernightBlock(start, end, utc)) {
                    assertTrue(
                        SleepWindow.isOvernightBlock(start, end, onsetIsUnobserved = true, zone = utc),
                        "start=${startMinutes}m dur=${durationMinutes}m: the correction must never reject what the plain rule accepted",
                    )
                }
            }
        }
    }

    /** IDENTITY WHEN THE ONSET WAS OBSERVED: with the flag false the corrected form equals the plain form everywhere. */
    @Test
    fun testIdentityWhenOnsetWasObserved() {
        val base = date("2026-08-02T00:00:00Z")
        for (startMinutes in 0 until 24 * 60 step 3) {
            for (durationMinutes in 30..14 * 60 step 11) {
                val start = base.plusSeconds(startMinutes * 60L)
                val end = start.plusSeconds(durationMinutes * 60L)
                assertEquals(
                    SleepWindow.isOvernightBlock(start, end, utc),
                    SleepWindow.isOvernightBlock(start, end, onsetIsUnobserved = false, zone = utc),
                    "start=${startMinutes}m dur=${durationMinutes}m must delegate unchanged",
                )
            }
        }
    }

    /** A block ALREADY longer than the presumed span keeps its own (earlier) start — the `min`, not a `max`. */
    @Test
    fun testLongBlockKeepsItsOwnStart() {
        val start = date("2026-08-01T22:00:00Z")
        val end = date("2026-08-02T08:00:00Z")
        assertEquals(
            SleepWindow.isOvernightBlock(start, end, utc),
            SleepWindow.isOvernightBlock(start, end, onsetIsUnobserved = true, zone = utc),
            "10 h > 7 h presumed span → the presumption is inert",
        )
    }

    // 2. the record-side predicate — BulkSleep.onsetIsUnobserved

    /** Records at the real 150 s cadence; [slots] supplies `[10:15]` for epoch `i` (default flat `1`, still). */
    private fun records(
        start: Instant,
        count: Int,
        epoch: Long = Command.SYNC_EPOCH,
        slots: (Int) -> IntArray = { IntArray(5) { 1 } },
    ): List<BulkRecord> {
        val base = start.epochSecond - epoch
        return (0 until count).mapNotNull { i ->
            val counter = base + i * BulkRecord.EPOCH_SECONDS
            val b = ByteArray(BulkRecord.LENGTH)
            b[0] = (counter shr 24).toByte(); b[1] = (counter shr 16).toByte()
            b[2] = (counter shr 8).toByte(); b[3] = counter.toByte()
            b[4] = 60 // HR
            b[5] = 40 // HRV
            b[7] = 14 // RR
            b[8] = 96 // SpO2 % ⇒ sleep vitals
            b[9] = 0x0b
            val m = slots(i)
            for (k in 10 until 15) b[k] = m[k - 10].toByte()
            BulkRecord.of(b)
        }
    }

    /**
     * AWAKE epochs. A CONSTANT high motion value is NOT "moving": the detector de-floors the channel
     * against a rolling p10, so a flat plateau at any level reads STILL. Awake motion varies WITHIN
     * the epoch and BETWEEN epochs.
     */
    private fun awakeRecords(start: Instant, count: Int, epoch: Long = Command.SYNC_EPOCH): List<BulkRecord> {
        val shape = intArrayOf(8, 62, 19, 77, 34)
        return records(start, count, epoch) { i ->
            val lift = (i % 7) * 6
            IntArray(5) { k -> (shape[(k + i) % 5] + lift) and 0xff }
        }
    }

    /** Contiguous recording running INTO the onset ⇒ the onset WAS captured, so no presumption. */
    @Test
    fun testContiguousRunBeforeOnsetMeansObserved() {
        val onset = date("2026-08-02T09:21:00Z")
        val end = date("2026-08-02T11:21:00Z")
        val before = records(onset.minusSeconds(4 * 3600), 96)
        val block = records(onset, 48)
        assertFalse(BulkSleep.onsetIsUnobserved(DateInterval(onset, end), before + block), "the epoch immediately before the onset exists → the onset was captured")
    }

    /** A full day of YESTERDAY's epochs, a multi-hour HOLE, then the tail: the HOLE is what this predicate looks for. */
    @Test
    fun testHoleBeforeOnsetMeansUnobserved() {
        val onset = date("2026-08-02T09:21:00Z")
        val end = date("2026-08-02T11:21:00Z")
        // Yesterday 07:21 → 20:00, then nothing until the tail: a ~13.4 h hole (needed: 7 − 2 = 5 h).
        val yesterday = records(date("2026-08-01T07:21:00Z"), 304)
        val block = records(onset, 48)
        val union = yesterday + block
        assertTrue(BulkSleep.onsetIsUnobserved(DateInterval(onset, end), union))
        assertTrue(
            SleepWindow.isOvernightBlock(onset, end, onsetIsUnobserved = BulkSleep.onsetIsUnobserved(DateInterval(onset, end), union), zone = utc),
        )
    }

    /** No record at all before the block (first-ever sync, wiped archive) ⇒ an unbounded hole. */
    @Test
    fun testNoRecordBeforeOnsetMeansUnobserved() {
        val onset = date("2026-08-02T09:21:00Z")
        val end = date("2026-08-02T11:21:00Z")
        assertTrue(BulkSleep.onsetIsUnobserved(DateInterval(onset, end), records(onset, 48)))
    }

    /**
     * A late-morning doze behind a 600 s gap presumes ~6 h of a night that is demonstrably PRESENT in
     * the archive, so the presumption is refused (a real 600 s leading-edge gap measured upstream).
     */
    @Test
    fun testShortHoleCannotPresumeALongOnset() {
        val onset = date("2026-08-02T10:29:00Z")
        val end = date("2026-08-02T12:29:00Z")
        val before = run(onset.minusSeconds(6 * 3600), onset, 600)
        val block = records(onset, 48)
        val previous = before.maxOf { it.date() }
        assertEquals(600L, Duration.between(previous, onset).seconds, "precondition: exactly a 600 s hole")
        assertFalse(BulkSleep.onsetIsUnobserved(DateInterval(onset, end), before + block), "a 2 h block presumes 5 h of absence; only 600 s is missing")
    }

    /** The requirement SCALES: the same 3 h hole is enough for a 4 h tail (needs 3 h), not a 2 h tail (needs 5 h). */
    @Test
    fun testRequiredHoleScalesWithTheTailLength() {
        val end = date("2026-08-02T11:21:00Z")
        for ((hours, holeIsEnough) in listOf(2L to false, 4L to true)) {
            val onset = end.minusSeconds(hours * 3600)
            // One epoch past the requirement, because the predicate is strictly `>`.
            val hole = 3 * 3600L + BulkRecord.EPOCH_SECONDS
            val before = run(onset.minusSeconds(27 * 3600), onset, hole)
            val previous = before.maxOf { it.date() }
            assertEquals(hole, Duration.between(previous, onset).seconds, "precondition: the same hole for both tails")
            assertEquals(
                holeIsEnough,
                BulkSleep.onsetIsUnobserved(DateInterval(onset, end), before),
                "$hours h tail claims ${7 - hours} h is missing; ~3 h is available",
            )
        }
    }

    /** The comparison is strictly `>`: a hole of exactly the claim is NOT enough; one epoch more is. */
    @Test
    fun testRequiredHoleIsStrictlyGreaterThanTheClaim() {
        val end = date("2026-08-02T11:21:00Z")
        val onset = end.minusSeconds(4 * 3600) // claims exactly 3 h is missing
        for ((hole, expected) in listOf(3 * 3600L to false, 3 * 3600L + BulkRecord.EPOCH_SECONDS to true)) {
            val before = run(onset.minusSeconds(27 * 3600), onset, hole)
            assertEquals(expected, BulkSleep.onsetIsUnobserved(DateInterval(onset, end), before), "a hole of exactly the claim is NOT enough; one epoch more is")
        }
    }

    /** A record run from [start] whose LAST epoch sits exactly [hole] seconds before [onset]. */
    private fun run(start: Instant, onset: Instant, hole: Long): List<BulkRecord> {
        val last = onset.minusSeconds(hole)
        val n = (Duration.between(start, last).seconds / BulkRecord.EPOCH_SECONDS).toInt() + 1
        return records(last.minusSeconds((n - 1).toLong() * BulkRecord.EPOCH_SECONDS), maxOf(1, n))
    }

    /** The constants are load-bearing; assert them so a change is a deliberate, test-visible act. */
    @Test
    fun testConstants() {
        assertEquals(Duration.ofSeconds(7 * 3600), SleepWindow.PRESUMED_TRUNCATED_NIGHT_SPAN)
        assertEquals(Duration.ofSeconds(450), BulkSleep.ONSET_CONTIGUITY_GAP)
    }

    // 3. THE CALL SITE — BulkSleep.latestNightRecords
    //
    // Upstream reads the device calendar here and builds its fixtures on it; this port passes an
    // explicit zone and builds the fixtures on that zone's wall clock.

    private val localZone: ZoneId = ZoneId.of("America/New_York")

    /** Local wall clock `hour:minute`, [dayOffset] days from a fixed reference day. */
    private fun local(dayOffset: Int, hour: Int, minute: Int = 0): Instant {
        val reference = Instant.ofEpochSecond(1_780_000_000)
        val t = reference.atZone(localZone).toLocalDate().plusDays(dayOffset.toLong()).atTime(hour, minute).atZone(localZone)
        assertEquals(hour, t.hour, "fixture wall clock must be exact")
        return t.toInstant()
    }

    /** Still (asleep-looking) epochs spanning [from] → [to] at the 150 s cadence. */
    private fun still(from: Instant, to: Instant): List<BulkRecord> =
        records(from, maxOf(0, (Duration.between(from, to).seconds / BulkRecord.EPOCH_SECONDS).toInt()))

    /** Moving (awake) epochs spanning [from] → [to]. */
    private fun moving(from: Instant, to: Instant): List<BulkRecord> =
        awakeRecords(from, maxOf(0, (Duration.between(from, to).seconds / BulkRecord.EPOCH_SECONDS).toInt()))

    private fun latest(records: List<BulkRecord>) = BulkSleep.latestNightRecords(records, zone = localZone)

    /**
     * The fixture itself is load-bearing: `moving` must detect as ACTIVE and `still` as SLEEP,
     * asserted on the detector DIRECTLY (a check through the selector was measured vacuous upstream).
     */
    @Test
    fun testFixturesProduceTheMotionTheyClaim() {
        fun periods(r: List<BulkRecord>): List<ActivityPeriod> = ActivityPeriod.detectFromMotion(
            BulkSleep.motionTimeline(r),
            temperatureSamples = emptyList(),
            heartRateSamples = BulkSleep.heartRateTimeline(r),
            sleepVitalTimes = BulkSleep.sleepVitalTimeline(r),
        )
        val awake = periods(moving(local(0, 4), local(0, 9)))
        assertFalse(awake.isEmpty(), "the awake fixture must produce periods at all")
        assertTrue(awake.all { it.activity != Activity.SLEEP }, "awake fixtures must NOT detect as sleep — got ${awake.map { it.activity }}")
        val asleep = periods(still(local(-1, 23), local(0, 6)))
        assertTrue(asleep.any { it.activity == Activity.SLEEP }, "still fixtures must detect as sleep — got ${asleep.map { it.activity }}")
        // The trap itself, pinned: a CONSTANT high motion value detects as SLEEP, not active.
        val flat = records(local(0, 4), 120) { IntArray(5) { 20 } }
        assertTrue(periods(flat).any { it.activity == Activity.SLEEP }, "a flat motion plateau reads STILL after de-flooring — this is the trap")
    }

    /** 1. THE MOTIVATING BUG: yesterday's epochs, a multi-hour hole, then a 2 h tail ending late morning. */
    @Test
    fun testCallSiteRecoversATruncatedTail() {
        val tailRecords = still(local(0, 9, 21), local(0, 11, 21))
        val union = moving(local(-1, 8), local(-1, 18)) + tailRecords

        val scoped = latest(union)

        assertTrue(scoped.size < union.size, "a night was found, so the input is scoped — not returned wholesale")
        assertEquals(tailRecords, scoped, "exactly the truncated night is returned; yesterday is excluded")
    }

    /**
     * 2. MONOTONICITY AT THE CALL SITE — a REAL fully-observed night (21:00 → 05:00) plus a morning
     * block (10:20 → 12:20) that WOULD qualify for the correction on its own. The two-pass filter
     * must never consult the correction here: the night, whole, morning block excluded.
     */
    @Test
    fun testCallSiteIsUnchangedWhenARealNightExists() {
        val nightStart = local(-1, 21)
        val morningStart = local(0, 10, 20)
        val morningEnd = local(0, 12, 20)
        val nightRecords = still(nightStart, local(0, 5))
        val union = nightRecords + still(morningStart, morningEnd)

        assertTrue(
            BulkSleep.onsetIsUnobserved(DateInterval(morningStart, morningEnd), union),
            "precondition: a 5 h 20 m hole clears the 5 h a 2 h block must show",
        )

        val scoped = latest(union)

        assertEquals(nightRecords, scoped, "the real night is returned WHOLE and the morning block is excluded")
        assertEquals(nightStart, scoped.minOf { it.date() }, "the 21:00 head must not be clipped")
    }

    /** 3. A block behind only a SHORT hole (600 s) must NOT fire the correction. */
    @Test
    fun testCallSiteDoesNotFireBehindAShortHole() {
        val morningEnd = local(0, 12, 20)
        val morningStart = local(0, 10, 20)
        val union = moving(local(0, 4), morningStart.minusSeconds(600)) + still(morningStart, morningEnd)

        assertFalse(BulkSleep.onsetIsUnobserved(DateInterval(morningStart, morningEnd), union), "precondition: only 600 s is missing, 5 h is claimed")

        assertEquals(union.sortedBy { it.counter }, latest(union), "no overnight block → the fallback: the input, unchanged")
    }

    /** 4. The same archive as test 1 but with CONTIGUOUS pre-onset records: nothing is presumed. */
    @Test
    fun testCallSiteIsUnchangedWhenPreOnsetRecordsAreContiguous() {
        val onset = local(0, 9, 21)
        val wake = local(0, 11, 21)
        val union = moving(local(0, 4), onset) + still(onset, wake)

        assertFalse(BulkSleep.onsetIsUnobserved(DateInterval(onset, wake), union), "precondition: recording runs into the onset")

        assertEquals(union.sortedBy { it.counter }, latest(union), "no overnight block → the fallback: the input, unchanged")
    }
}
