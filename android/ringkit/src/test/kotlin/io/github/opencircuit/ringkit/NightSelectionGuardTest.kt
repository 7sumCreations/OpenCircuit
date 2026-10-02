package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What the Kotlin port of night selection must guarantee that Swift's types guaranteed for free:
 * every tuning constant equals upstream's literal (printed by the pinned Swift build); "overnight"
 * is judged in the zone the caller names, never the machine's; the caller's record list is neither
 * aliased nor reordered (Swift arrays are values); and the guard's ratio keeps sub-second precision
 * (Swift's `TimeInterval` is a `Double`, `java.time.Duration` splits seconds and nanos).
 */
class NightSelectionGuardTest {

    private fun rec(t: Instant, still: Boolean, seed: Int = 0): BulkRecord {
        val counter = t.epochSecond - Command.SYNC_EPOCH
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

    private fun run(from: Instant, to: Instant, still: Boolean): List<BulkRecord> =
        (0 until Duration.between(from, to).seconds step 150).mapIndexed { i, s -> rec(from.plusSeconds(s), still, i) }

    private val day: Instant = Instant.parse("2026-06-10T00:00:00Z")
    private fun utcAt(h: Int): Instant = day.plusSeconds(h * 3600L)

    /**
     * Two still blocks between moving stretches. 10:00–13:00 UTC is 06:00–09:00 in New York (overnight
     * there, daytime in UTC); 15:00–17:00 UTC is midday in New York but overnight in Ho Chi Minh and
     * Kiritimati — so a selector that judged ANY step in the machine's zone would anchor on the
     * wrong block there.
     */
    private val zoneSensitive: List<BulkRecord> =
        run(utcAt(6), utcAt(10), still = false) + run(utcAt(10), utcAt(13), still = true) +
            run(utcAt(13), utcAt(15), still = false) + run(utcAt(15), utcAt(17), still = true) +
            run(utcAt(17), utcAt(19), still = false)

    @Test
    fun selectionConstantsEqualUpstreamLiterals() {
        // Printed by the pinned Swift build: maxIntraNightGap 21600.0, maxNightSpan 50400.0,
        // morningContinuationMaxGap 3600.0, observedGapAbsorbCoverageCut 0.95,
        // declinedBridgeMayReanchor true, NapDetection.minNapDuration 900.0.
        assertEquals(Duration.ofSeconds(21_600), BulkSleep.MAX_INTRA_NIGHT_GAP)
        assertEquals(Duration.ofSeconds(50_400), BulkSleep.MAX_NIGHT_SPAN)
        assertEquals(Duration.ofSeconds(3_600), BulkSleep.MORNING_CONTINUATION_MAX_GAP)
        assertEquals(0.95, BulkSleep.OBSERVED_GAP_ABSORB_COVERAGE_CUT)
        assertTrue(BulkSleep.DECLINED_BRIDGE_MAY_REANCHOR)
        assertEquals(Duration.ofSeconds(900), NapDetection.MIN_NAP_DURATION)
        // Upstream defines the morning gap AS the pause the main-sleep bridge uses; the two must agree.
        assertEquals(ActivityPeriod.MAX_SLEEP_PAUSE, BulkSleep.MORNING_CONTINUATION_MAX_GAP)
    }

    @Test
    fun overnightIsJudgedInTheNamedZoneNeverTheMachines() {
        val ny = ZoneId.of("America/New_York")
        // Premise: the zone matters for this archive, so the check below cannot pass vacuously.
        assertNotEquals(
            BulkSleep.latestNightRecords(zoneSensitive, zone = ny),
            BulkSleep.latestNightRecords(zoneSensitive, zone = ZoneOffset.UTC),
            "New York finds a night here, UTC does not",
        )
        fun underDefault(id: String): List<BulkRecord> {
            val saved = TimeZone.getDefault()
            return try {
                TimeZone.setDefault(TimeZone.getTimeZone(id))
                BulkSleep.latestNightRecords(zoneSensitive, zone = ny)
            } finally {
                TimeZone.setDefault(saved)
            }
        }
        val underUtc = underDefault("UTC")
        assertEquals(underUtc, underDefault("Pacific/Kiritimati"))
        assertEquals(underUtc, underDefault("Asia/Ho_Chi_Minh"))
        assertTrue(underUtc.size < zoneSensitive.size, "the New York night is scoped")
    }

    @Test
    fun theCallersListIsNeitherAliasedNorReordered() {
        val ny = ZoneId.of("America/New_York")
        for (zone in listOf(ny, ZoneOffset.UTC)) { // a night found, and the no-night fallback
            val input = zoneSensitive.toMutableList()
            val result = BulkSleep.latestNightRecords(input, zone = zone)
            val snapshot = result.toList()
            input.clear()
            assertEquals(snapshot, result, "$zone: clearing the caller's list must not change the result")

            val reversed = zoneSensitive.reversed().toMutableList()
            val before = reversed.toList()
            BulkSleep.latestNightRecords(reversed, zone = zone)
            assertEquals(before, reversed, "$zone: the caller's order is left alone (sorting works on a copy)")
        }
    }

    @Test
    fun guardRatioKeepsSubSecondGapPrecision() {
        // A gap of 450.5 s holding three records reads 3 / (450.5 / 150) = 0.99889, as Swift's
        // Double arithmetic gives; dropping the half second would read exactly 1.0.
        val end = Instant.ofEpochSecond(1_780_000_000)
        val start = end.plusSeconds(450).plusMillis(500)
        val times = listOf(end.plusSeconds(100), end.plusSeconds(250), end.plusSeconds(400))
        assertFalse(BulkSleep.bridgeIsDeclined(start, end, times, cut = 0.9995), "0.99889 is under 0.9995")
        assertTrue(BulkSleep.bridgeIsDeclined(start, end, times, cut = 0.9988), "0.99889 clears 0.9988")
        // And half a second over the judging floor is judged at all.
        assertTrue(BulkSleep.bridgeIsDeclined(start, end, times, cut = 0.5))
        assertFalse(BulkSleep.bridgeIsDeclined(end.plusSeconds(450), end, times, cut = 0.5), "exactly 450 s is never judged")
    }
}
