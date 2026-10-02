package io.github.opencircuit.ringkit

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Kotlin-only hostile-input checks for the three reminders: what the upstream vectors never feed in.
 * The activity, frame, off-finger and worn-evidence stamps come from the ring and the phone's clock and
 * are stored between launches; the interval, active window, bedtime, wake time and lead are stored
 * settings. So here the stamps arrive in the future and exactly one interval old, the settings arrive
 * NaN, infinite, zero, negative, outside the day and at the ends of `Int`, and the clock arrives across
 * both 2026 New York clock changes and at the ends of time. Kept out of the upstream-port class so its
 * count stays exact.
 *
 * Every upstream outcome quoted below was measured on the pinned Swift build (Swift 6.3.2). Where the
 * port deliberately differs the test says so, and `PORTING.md` records why.
 */
class ReminderEngineHazardTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val newYork: ZoneId = ZoneId.of("America/New_York")
    private val kolkata: ZoneId = ZoneId.of("Asia/Kolkata")

    /** 2024-01-15 00:00 UTC, the day of upstream's own tests. */
    private val midnight: Instant = Instant.parse("2024-01-15T00:00:00Z")
    private val ten: Instant = Instant.parse("2024-01-15T10:00:00Z")
    private fun minute(m: Int): Instant = midnight.plusSeconds(m * 60L)
    private fun s(t: Instant, seconds: Long): Instant = t.plusSeconds(seconds)

    /** The minutes of the day each bedtime row is asked at, in UTC. */
    private val asked = listOf(0, 600, 1349, 1350, 1379, 1380, 1439)

    private fun bedtimeRow(bed: Int, before: Int, wake: Int): List<Boolean> =
        asked.map { BedtimeReminder(minutesBefore = before).shouldFire(now = minute(it), bedMinutes = bed, wakeMinutes = wake, zone = utc) }

    private fun sedentary(interval: Double, start: Int = 480, end: Int = 1260) =
        SedentaryReminder(interval = interval, activeStartMinutes = start, activeEndMinutes = end)

    // MARK: bedtime

    @Test
    fun theBedtimeWindowStartIsTakenIn64Bits() {
        // Upstream's `(bedMinutes - minutesBefore + 1440) % 1440` is Swift's 64-bit `Int`: it traps only
        // past 64 bits, which no Kotlin `Int` setting reaches. Taken in 32 bits the same expression wraps —
        // Int.MAX − Int.MIN would read −1, so the first row would fire at 23:59 only. Measured upstream
        // at 00:00, 10:00, 22:29, 22:30, 22:59, 23:00 and 23:59 UTC:
        val rows = mapOf(
            Triple(Int.MAX_VALUE, Int.MIN_VALUE, 0) to listOf(false, true, true, true, true, true, true),
            Triple(Int.MAX_VALUE, -1, 0) to listOf(false, true, true, true, true, true, true),
            Triple(Int.MIN_VALUE, Int.MAX_VALUE, 0) to List(7) { true },
            Triple(Int.MIN_VALUE, 0, 0) to List(7) { true },
            Triple(Int.MIN_VALUE, 0, 1) to List(7) { true },
            Triple(Int.MAX_VALUE, Int.MAX_VALUE, 0) to List(7) { true },
            Triple(1_380, Int.MIN_VALUE, 420) to listOf(false, true, true, true, true, false, false),
            Triple(1_380, Int.MAX_VALUE, 420) to listOf(true, true, true, true, true, false, false),
        )
        for ((setting, expected) in rows) {
            val (bed, before, wake) = setting
            assertEquals(expected, bedtimeRow(bed, before, wake), "bed $bed, before $before, wake $wake")
        }
    }

    @Test
    fun bedtimeSettingsOutsideTheDayFollowUpstream() {
        // The stored bedtime and lead compare as plain numbers, with Swift's truncating `%` (a negative
        // start stays negative), and the window is empty when it opens where it closes. Measured:
        val rows = mapOf(
            Triple(0, 2_000, 7) to List(7) { false }, // start −560, end 0: nothing in the day lies between
            Triple(1_500, 30, 420) to listOf(false, true, true, true, true, true, true), // [30, 1500)
            Triple(1_380, -30, 420) to listOf(true, true, true, true, true, false, true), // [1410, 1380) wraps
            Triple(1_380, 0, 420) to List(7) { false }, // zero lead: empty
            Triple(1_380, 1_440, 420) to List(7) { false }, // a whole day's lead: empty
            Triple(1_380, 2_880, 420) to listOf(true, true, true, true, true, false, false), // [−60, 1380)
            Triple(-60, 30, 420) to listOf(false, false, false, true, true, true, true), // [1350, −60) wraps
            Triple(600, 30, 600) to List(7) { false }, // bed == wake: not configured
            Triple(2_040, 30, 600) to listOf(false, true, true, true, true, true, true), // [570, 2040)
        )
        for ((setting, expected) in rows) {
            val (bed, before, wake) = setting
            assertEquals(expected, bedtimeRow(bed, before, wake), "bed $bed, before $before, wake $wake")
        }
    }

    // MARK: sedentary

    @Test
    fun hostileSedentarySettingsFollowUpstream() {
        // Measured at 10:00 UTC unless named.
        assertFalse(sedentary(Double.NaN).shouldFire(lastActivityAt = s(ten, -1_000_000), now = ten, zone = utc), "NaN interval: never due")
        assertFalse(sedentary(Double.NaN).shouldFire(lastActivityAt = s(ten, -3_600), now = ten, lastOffFingerAt = ten, zone = utc))
        assertFalse(sedentary(Double.POSITIVE_INFINITY).shouldFire(lastActivityAt = s(ten, -1_000_000_000), now = ten, zone = utc))
        assertTrue(sedentary(Double.NEGATIVE_INFINITY).shouldFire(lastActivityAt = s(ten, 1_000_000_000), now = ten, zone = utc))
        assertTrue(sedentary(0.0).shouldFire(lastActivityAt = ten, now = ten, zone = utc), "interval 0: due at once")
        assertFalse(sedentary(0.0).shouldFire(lastActivityAt = s(ten, 1), now = ten, zone = utc))
        assertTrue(sedentary(-1.0).shouldFire(lastActivityAt = ten.plusMillis(500), now = ten, zone = utc))
        // Exactly one interval: due; an off-finger stamp exactly one interval old no longer suppresses;
        // a last frame exactly one interval old does.
        assertTrue(sedentary(3_000.0).shouldFire(lastActivityAt = s(ten, -3_000), now = ten, zone = utc))
        assertTrue(sedentary(3_000.0).shouldFire(lastActivityAt = s(ten, -9_000), now = ten, lastOffFingerAt = s(ten, -3_000), zone = utc))
        assertFalse(sedentary(3_000.0).shouldFire(lastActivityAt = s(ten, -9_000), now = ten, lastRingDataAt = s(ten, -3_000), zone = utc))
        // The active window does not wrap: an inverted one is empty (10:00 and 23:30), one wider than the
        // day is always open, an empty one never.
        val late = Instant.parse("2024-01-15T23:30:00Z")
        assertFalse(sedentary(3_000.0, 1_260, 480).shouldFire(lastActivityAt = s(ten, -9_000), now = ten, zone = utc))
        assertFalse(sedentary(3_000.0, 1_260, 480).shouldFire(lastActivityAt = s(late, -9_000), now = late, zone = utc))
        assertTrue(sedentary(3_000.0, -100, 5_000).shouldFire(lastActivityAt = s(midnight, -9_000), now = midnight, zone = utc))
        assertTrue(sedentary(3_000.0, Int.MIN_VALUE, Int.MAX_VALUE).shouldFire(lastActivityAt = s(midnight, -9_000), now = midnight, zone = utc))
        assertFalse(sedentary(3_000.0, 600, 600).shouldFire(lastActivityAt = s(ten, -9_000), now = ten, zone = utc))
        // Window edges: 07:59 out, 08:00 in, 20:59 and 20:59:59 in (seconds are ignored), 21:00 out.
        val edges = listOf(479, 480, 1_259, 1_260).map { sedentary(3_000.0).shouldFire(lastActivityAt = s(minute(it), -9_000), now = minute(it), zone = utc) }
        assertEquals(listOf(false, true, true, false), edges)
        assertTrue(sedentary(3_000.0).shouldFire(lastActivityAt = s(minute(1_259), -8_941), now = s(minute(1_259), 59), zone = utc))
    }

    // MARK: future stamps (the counterpart of the alert gate's future `lastFired`)

    @Test
    fun aFutureStampFollowsUpstream() {
        // Upstream clamps the stamps that SUPPRESS (off-finger, last frame, worn evidence, the charger's
        // last frame) with `max(0, …)`, so a future one is "just now". The stamps that make a reminder DUE
        // (the sedentary rule's last activity, the wear rule's last frame) are not clamped: a future one is
        // a negative gap, "not yet". Measured:
        assertFalse(sedentary(3_000.0).shouldFire(lastActivityAt = s(ten, 3_600), now = ten, zone = utc), "future activity: not due")
        assertTrue(sedentary(3_000.0).shouldFire(lastActivityAt = s(ten, -3_600), now = ten, lastRingDataAt = s(ten, 3_600), zone = utc), "a future frame is fresh: no silence")
        assertFalse(sedentary(0.0).shouldFire(lastActivityAt = s(ten, -3_600), now = ten, lastRingDataAt = s(ten, 3_600), zone = utc), "clamped to 0 ≥ 0: silent")
        assertFalse(sedentary(3_000.0).shouldFire(lastActivityAt = s(ten, -10_800), now = ten, lastOffFingerAt = s(ten, 1_800), zone = utc))
        val wear = WearReminder(noDataInterval = 3_600.0)
        assertFalse(wear.shouldFire(lastRingDataAt = s(ten, 7_200), now = ten, everConnected = true), "future frame: not due")
        assertFalse(WearReminder(noDataInterval = 0.0).shouldFire(lastRingDataAt = s(ten, 7_200), now = ten, everConnected = true))
        assertTrue(WearReminder(noDataInterval = -1.0).shouldFire(lastRingDataAt = ten.plusMillis(500), now = ten, everConnected = true))
        assertFalse(wear.shouldFire(lastRingDataAt = s(ten, 1_000_000_000), now = ten, everConnected = true, lastKnownOnCharger = true))
        assertFalse(wear.shouldFire(lastRingDataAt = s(ten, -7_200), now = ten, everConnected = true, lastWornEvidenceAt = s(ten, 1_000_000_000)))
        assertTrue(WearReminder(noDataInterval = 0.0).shouldFire(lastRingDataAt = s(ten, -7_200), now = ten, everConnected = true, lastWornEvidenceAt = s(ten, 10)))

        // Clamping the due-making stamps too would change nothing for any positive interval: a future
        // stamp and a stamp of exactly `now` give the same answer ("not due"). Swept over random settings.
        val rng = Random(20_261_003)
        repeat(20_000) {
            val interval = 1e-9 + rng.nextDouble() * 200_000
            val ahead = 1 + rng.nextInt(1_000_000_000).toLong()
            val now = s(midnight, rng.nextInt(86_400).toLong())
            val r = sedentary(interval, rng.nextInt(1_440), rng.nextInt(1_440) + 1)
            assertEquals(r.shouldFire(lastActivityAt = now, now = now, zone = utc), r.shouldFire(lastActivityAt = s(now, ahead), now = now, zone = utc))
            assertFalse(r.shouldFire(lastActivityAt = s(now, ahead), now = now, zone = utc))
            val w = WearReminder(noDataInterval = interval)
            assertEquals(w.shouldFire(lastRingDataAt = now, now = now, everConnected = true), w.shouldFire(lastRingDataAt = s(now, ahead), now = now, everConnected = true))
        }
    }

    // MARK: wear

    @Test
    fun hostileWearSettingsFollowUpstream() {
        val longAgo = s(ten, -1_000_000)
        // NaN interval: never due on a frame, still due with no frame at all, never suppressed by evidence.
        assertFalse(WearReminder(noDataInterval = Double.NaN).shouldFire(lastRingDataAt = longAgo, now = ten, everConnected = true))
        assertTrue(WearReminder(noDataInterval = Double.NaN).shouldFire(lastRingDataAt = null, now = ten, everConnected = true))
        assertFalse(WearReminder(noDataInterval = Double.NaN).shouldFire(lastRingDataAt = longAgo, now = ten, everConnected = true, lastWornEvidenceAt = ten))
        // NaN grace never suppresses; an infinite one always does.
        assertTrue(WearReminder(chargerGrace = Double.NaN).shouldFire(lastRingDataAt = s(ten, -7_200), now = ten, everConnected = true, lastKnownOnCharger = true))
        assertFalse(WearReminder(chargerGrace = Double.POSITIVE_INFINITY).shouldFire(lastRingDataAt = s(ten, -1_000_000_000), now = ten, everConnected = true, lastKnownOnCharger = true))
        // Exactly one interval: due; worn evidence exactly one interval old and a charger frame exactly one
        // grace old no longer suppress.
        val wear = WearReminder()
        assertTrue(wear.shouldFire(lastRingDataAt = s(ten, -3_600), now = ten, everConnected = true))
        assertTrue(wear.shouldFire(lastRingDataAt = s(ten, -7_200), now = ten, everConnected = true, lastWornEvidenceAt = s(ten, -3_600)))
        assertTrue(wear.shouldFire(lastRingDataAt = s(ten, -14_400), now = ten, everConnected = true, lastKnownOnCharger = true))
        // Never connected: never, with or without a frame.
        assertEquals(listOf(false, false), listOf(null, longAgo).map { wear.shouldFire(lastRingDataAt = it, now = ten, everConnected = false) })
    }

    // MARK: the clock

    @Test
    fun remindersReadTheZonesWallClockAcrossClockChanges() {
        // New York, 2026: 01:59 EST and 03:00 / 03:30 EDT on 8 March (02:xx does not exist), 01:30 EST that
        // morning; on 1 November 01:30 EDT, 01:30 EST (the repeated hour) and 02:00 EST. Measured upstream
        // with a New York calendar:
        val instants = listOf(1_772_953_140L, 1_772_953_200L, 1_772_955_000L, 1_772_951_400L, 1_793_511_000L, 1_793_514_600L, 1_793_516_400L)
            .map { Instant.ofEpochSecond(it) }
        val rows = mapOf(
            150 to 30 to listOf(false, false, false, false, false, false, true), // [02:00, 02:30): skipped in March
            120 to 30 to listOf(true, false, false, true, true, true, false), // [01:30, 02:00): both 01:30s in November
            180 to 60 to listOf(false, false, false, false, false, false, true), // [02:00, 03:00)
        )
        for ((setting, expected) in rows) {
            val (bed, before) = setting
            assertEquals(expected, instants.map { BedtimeReminder(before).shouldFire(now = it, bedMinutes = bed, wakeMinutes = 420, zone = newYork) }, "bed $bed, before $before")
        }
        // The sedentary window 01:00–03:01 holds 01:59 EST, 03:00 EDT and both 01:30s.
        val sed = sedentary(3_000.0, 60, 181)
        assertEquals(List(4) { true }, listOf(0, 1, 4, 5).map { sed.shouldFire(lastActivityAt = s(instants[it], -9_000), now = instants[it], zone = newYork) })
        // The zone given is the one read: the first instant is 06:59 in UTC, outside the window.
        assertFalse(sed.shouldFire(lastActivityAt = s(instants[0], -9_000), now = instants[0], zone = utc))
    }

    @Test
    fun theEndsOfTimeNeverThrowAndAnUnplaceableClockRaisesNoReminder() {
        // Far instants java.time CAN place agree with upstream's time of day (measured): 1e13 s is 17:46 UTC
        // and 23:16 in Kolkata; Foundation's distant past is 00:00 UTC and 05:53 in Kolkata (its oldest offset).
        val far = Instant.ofEpochSecond(10_000_000_000_000L)
        assertTrue(sedentary(1.0, 1_066, 1_067).shouldFire(lastActivityAt = s(far, -10), now = far, zone = utc))
        assertTrue(sedentary(1.0, 1_396, 1_397).shouldFire(lastActivityAt = s(far, -10), now = far, zone = kolkata))
        val distantPast = Instant.ofEpochSecond(-62_135_596_800L)
        assertTrue(BedtimeReminder(1).shouldFire(now = distantPast, bedMinutes = 1, wakeMinutes = 420, zone = utc))
        assertTrue(BedtimeReminder(1).shouldFire(now = distantPast, bedMinutes = 354, wakeMinutes = 420, zone = kolkata))

        // KEPT DIFFERENCE: an instant java.time cannot place in the zone (past the last local date-time
        // ahead of UTC, before the first behind it; `Instant.MAX` / `MIN` in every zone) raises no reminder,
        // even with settings that are always due. Upstream's Date reaches those years and Foundation reads
        // some time of day there (measured: 00:00 UTC, 05:30 Kolkata near year 1 000 000 000).
        val unplaceable = listOf(
            LocalDateTime.MAX.toInstant(ZoneOffset.UTC).plusSeconds(1) to utc,
            LocalDateTime.MAX.toInstant(ZoneOffset.UTC) to kolkata,
            LocalDateTime.MIN.toInstant(ZoneOffset.UTC) to newYork,
            Instant.MAX to utc,
            Instant.MIN to utc,
        )
        val alwaysDue = sedentary(-1.0, Int.MIN_VALUE, Int.MAX_VALUE)
        for ((t, zone) in unplaceable) {
            assertFalse(alwaysDue.shouldFire(lastActivityAt = Instant.MIN, now = t, zone = zone), "sedentary at $t in $zone")
            assertFalse(BedtimeReminder(0).shouldFire(now = t, bedMinutes = Int.MIN_VALUE, wakeMinutes = 0, zone = zone), "bedtime at $t in $zone")
            // The wear rule reads no time of day: it still answers there.
            assertTrue(WearReminder(noDataInterval = 0.0).shouldFire(lastRingDataAt = t, now = t, everConnected = true), "wear at $t")
        }
        // The same always-due settings fire on any placeable instant, so the false above is the clock's.
        assertTrue(alwaysDue.shouldFire(lastActivityAt = Instant.MIN, now = ten, zone = kolkata))
        assertTrue(BedtimeReminder(0).shouldFire(now = ten, bedMinutes = Int.MIN_VALUE, wakeMinutes = 0, zone = kolkata))
    }

    @Test
    fun elapsedTimeIsMeasuredExactly() {
        // KEPT DIFFERENCE (as the trends refresh policy and the alert gate): the gap is an exact Instant
        // difference. Upstream subtracts Date doubles, which near 2026 cannot tell 1 ns apart (measured at
        // 2026-08-10 10:00 UTC: 1 ns short of the interval reads as the whole interval, due; 1 µs short does
        // not). Here 1 ns short is short.
        val base = Instant.parse("2026-08-10T10:00:00Z")
        val sed = sedentary(3_000.0)
        assertFalse(sed.shouldFire(lastActivityAt = s(base, -3_000).plusNanos(1), now = base, zone = utc))
        assertFalse(sed.shouldFire(lastActivityAt = s(base, -3_000).plusNanos(1_000), now = base, zone = utc))
        assertTrue(sed.shouldFire(lastActivityAt = s(base, -3_000), now = base, zone = utc))
        val wear = WearReminder(noDataInterval = 3_600.0)
        assertFalse(wear.shouldFire(lastRingDataAt = s(base, -3_600).plusNanos(1), now = base, everConnected = true))
        assertTrue(wear.shouldFire(lastRingDataAt = s(base, -3_600), now = base, everConnected = true))
    }
}
