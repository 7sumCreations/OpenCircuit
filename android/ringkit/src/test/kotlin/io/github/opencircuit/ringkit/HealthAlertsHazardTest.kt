package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.HealthAlertEvaluator.ActivityInterval
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kotlin-only hostile-input checks for the health-alert policy: what the upstream vectors never feed
 * in. HR and SpO₂ readings come off the wire, the ring's activity sessions come from `0x50` frames,
 * the thresholds, quiet-hours minutes and the `lastFired` ledger are stored values, and the windows,
 * gaps, leads and pads are caller values. So here windows arrive negative, NaN or infinite, minutes
 * arrive outside the day and at the ends of `Int`, readings arrive tied, unsorted, duplicated or
 * before Foundation's distant past, the clock arrives at the ends of `Instant` and across clock
 * changes, a stored `lastFired` arrives in the future, and a stored raw name arrives misspelt. For the
 * overnight-signals notification the stored frozen-day count, unlock floor and per-day ledger arrive at
 * the ends of their ranges, the shares it names arrive NaN, infinite, signed-zero or negative with a
 * limit below zero, and the clock arrives across clock changes and at the ends of time. Kept out of the
 * upstream-port classes so their counts stay exact.
 *
 * Every upstream outcome quoted below was measured on the pinned Swift build (Swift 6.3.2). Where the
 * port deliberately differs the test says so, and `PORTING.md` records why.
 */
class HealthAlertsHazardTest {

    private val t0: Instant = Instant.parse("2026-06-17T12:00:00Z")
    private val utc: ZoneId = ZoneOffset.UTC
    private fun sp(p: Int, s: Long) = SpO2Reading(percent = p, time = t0.plusSeconds(s))
    private fun hr(b: Int, s: Long) = HRSample(bpm = b, start = t0.plusSeconds(s))
    private fun offsets(xs: List<HRSample>) = xs.map { it.start.epochSecond - t0.epochSecond }

    // MARK: low SpO₂

    @Test
    fun aNegativeSpO2WindowQualifiesNoRun() {
        // Upstream traps ("Index out of range", exit 133) on [88 @ 0 s, 87 @ 300 s], on three readings
        // 300 s apart and on three readings at one instant, all with window −1 (and −∞): its sliding
        // span walks `first` past the reading it is checking. Wherever upstream does answer — a run
        // shorter than the minimum, measured: nil — no run can qualify either, since a span of negative
        // length holds no reading. So a negative window qualifies no run: nil.
        val runs = listOf(
            listOf(sp(88, 0), sp(87, 300)),
            listOf(sp(88, 0), sp(87, 300), sp(86, 600)),
            listOf(sp(88, 0), sp(87, 0), sp(86, 0)),
            listOf(sp(88, 0), sp(97, 300)),
        )
        for (run in runs) for (w in listOf(-1.0, -1e-300, -1e9, Double.NEGATIVE_INFINITY, -Double.MIN_VALUE)) {
            assertNull(HealthAlertEvaluator.lowSpO2(run, thresholdPercent = 90, window = w), "window $w over $run")
        }
        // The kill-switch (one reading is enough) never reads the window, as upstream: the worst reading.
        assertEquals(86, HealthAlertEvaluator.lowSpO2(runs[1], thresholdPercent = 90, minReadings = 1, window = -1.0)?.percent)
        // A property over random nights: any negative window, any minimum of at least 2 → nil.
        val rng = Random(20_261_003)
        repeat(2_000) {
            val night = List(rng.nextInt(12)) { sp(70 + rng.nextInt(25), if (rng.nextBoolean()) rng.nextInt(3_600).toLong() else 0L) }
            val w = -rng.nextDouble() * 10_000.0 - Double.MIN_VALUE
            assertNull(HealthAlertEvaluator.lowSpO2(night, thresholdPercent = 90, minReadings = 2 + rng.nextInt(4), window = w), "$night window $w")
        }
    }

    @Test
    fun spO2WindowGapCountAndOrderEdgesFollowUpstream() {
        val run = listOf(sp(88, 0), sp(87, 300), sp(86, 600))
        fun low(window: Double = 1_800.0, maxGap: Double = 1_200.0, minReadings: Int = 2) =
            HealthAlertEvaluator.lowSpO2(run, thresholdPercent = 90, minReadings = minReadings, window = window, maxGap = maxGap)?.percent
        // Measured upstream: a NaN or infinite window lets every run qualify, window 0 none of these.
        assertEquals(listOf(86, null, 86), listOf(low(window = Double.NaN), low(window = 0.0), low(window = Double.POSITIVE_INFINITY)))
        // A NaN or infinite gap never splits the run; a gap of 0 or −1 splits every reading off.
        assertEquals(
            listOf(86, null, null, 86),
            listOf(low(maxGap = Double.NaN), low(maxGap = 0.0), low(maxGap = -1.0), low(maxGap = Double.POSITIVE_INFINITY)),
        )
        // Minimum counts at the ends of Int: one or fewer is the kill-switch; four of three never qualifies.
        assertEquals(
            listOf(86, 86, 86, 86, 86, null, null),
            listOf(Int.MIN_VALUE, -1, 0, 1, 3, 4, Int.MAX_VALUE).map { low(minReadings = it) },
        )
        // Two readings at one instant qualify inside a zero-length window (88).
        assertEquals(88, HealthAlertEvaluator.lowSpO2(listOf(sp(88, 0), sp(88, 0)), thresholdPercent = 90, window = 0.0)?.percent)
        // Percents outside 0…100: 0 and below are the "no reading" sentinel, anything else counts (88).
        assertEquals(88, HealthAlertEvaluator.lowSpO2(listOf(sp(-5, 0), sp(150, 0), sp(88, 0)), thresholdPercent = 200, minReadings = 1)?.percent)
        // Unsorted readings are put in time order (86); tied depths name the EARLIEST (300 s).
        assertEquals(86, HealthAlertEvaluator.lowSpO2(listOf(sp(86, 600), sp(88, 0), sp(87, 300)), thresholdPercent = 90)?.percent)
        assertEquals(t0.plusSeconds(300), HealthAlertEvaluator.lowSpO2(listOf(sp(86, 600), sp(86, 300), sp(87, 0)), thresholdPercent = 90)?.time)
        // A cut at the end of time leaves nothing new.
        assertNull(HealthAlertEvaluator.lowSpO2(run, thresholdPercent = 90, since = Instant.MAX))
    }

    @Test
    fun aMissingLastFiredIsFoundationsDistantPast() {
        // Upstream reads a missing `lastFired` as `Date.distantPast` (−62 135 769 600 s), so a reading
        // dated before it is never fresh. Measured: highHR with lastFired distantPast → 1 hit; a sample
        // at −7e10 s with no lastFired → 0 hits. The port uses the same instant, so `Instant`'s own,
        // deeper past changes nothing.
        val t = HealthAlertThresholds()
        assertEquals(1, HealthAlertEvaluator.evaluate(listOf(hr(130, 0)), emptyList(), emptyList(), t, mapOf(HealthNotification.HIGH_HR to SleepEdit.DISTANT_PAST)).size)
        val ancient = HRSample(bpm = 130, start = Instant.ofEpochSecond(-70_000_000_000L))
        assertEquals(emptyList(), HealthAlertEvaluator.evaluate(listOf(ancient), emptyList(), listOf(ancient), t))
        assertEquals(emptyList(), HealthAlertEvaluator.evaluate(listOf(HRSample(130, Instant.MIN)), emptyList(), emptyList(), t))
        val ancientLow = SpO2Reading(85, Instant.ofEpochSecond(-70_000_000_000L))
        assertNull(HealthAlertEvaluator.lowSpO2(listOf(ancientLow), thresholdPercent = 90, minReadings = 1))
        assertNull(HealthAlertEvaluator.lowSpO2(listOf(ancientLow, ancientLow), thresholdPercent = 90))
        // One second after it is fresh.
        val justAfter = SleepEdit.DISTANT_PAST.plusSeconds(1)
        assertEquals(1, HealthAlertEvaluator.evaluate(listOf(HRSample(130, justAfter)), emptyList(), emptyList(), t).size)
    }

    // MARK: the activity gate

    @Test
    fun aNaNLeadOrPadWidensNothing() {
        // Upstream: a NaN lead or pad makes a `Date(NaN)` bound, and Foundation's `>=` / `<=` (defined
        // as "not <") are TRUE against it, so the interval swallows every reading on that side. Measured
        // over HR at 0 s, 300 s and 900 s: session [300 s, 600 s] with lead NaN → [] (every reading
        // dropped), interval [0 s, 300 s] with pad NaN → []. That silences a genuine resting crossing
        // on no evidence — the direction the gate must never fail in. Here a NaN lead or pad is the
        // nearest valid one, 0: the interval is the session (or step window) itself.
        val s3 = listOf(hr(150, 0), hr(150, 300), hr(150, 900))
        val session = listOf(RingEventLog.ActivitySession(t0.plusSeconds(300), t0.plusSeconds(600)))
        val withNaNLead = HealthAlertEvaluator.ringActivityIntervals(session, lead = Double.NaN)
        assertEquals(HealthAlertEvaluator.ringActivityIntervals(session, lead = 0.0), withNaNLead)
        assertEquals(listOf(0L), offsets(HealthAlertEvaluator.nonExercising(s3, withNaNLead)), "lead 0, default pad: 300 s and 900 s fall in [300 s, 1 200 s]")
        val walk = listOf(ActivityInterval(t0, t0.plusSeconds(300)))
        assertEquals(listOf(900L), offsets(HealthAlertEvaluator.nonExercising(s3, walk, pad = Double.NaN)), "pad 0: only [0 s, 300 s] is excluded")
        assertEquals(HealthAlertEvaluator.nonExercising(s3, walk, pad = 0.0), HealthAlertEvaluator.nonExercising(s3, walk, pad = Double.NaN))

        // Infinite leads and pads mean what they say, as upstream (measured): +∞ lead → [] ; −∞ lead → all
        // kept; a pad of −300 s narrows [0 s, 300 s] to [0 s, 0 s] → 300 s and 900 s kept; a −∞ pad
        // ends the interval before every reading → all kept.
        assertEquals(emptyList(), HealthAlertEvaluator.nonExercising(s3, HealthAlertEvaluator.ringActivityIntervals(session, lead = Double.POSITIVE_INFINITY)))
        assertEquals(s3, HealthAlertEvaluator.nonExercising(s3, HealthAlertEvaluator.ringActivityIntervals(session, lead = Double.NEGATIVE_INFINITY)))
        assertEquals(listOf(300L, 900L), offsets(HealthAlertEvaluator.nonExercising(s3, walk, pad = -300.0)))
        assertEquals(s3, HealthAlertEvaluator.nonExercising(s3, walk, pad = Double.NEGATIVE_INFINITY))
    }

    @Test
    fun reversedAndExtremeWindowsFollowUpstream() {
        // A step window that ends before it starts, with steps, is narrower than the cap and kept (1);
        // a NaN cap keeps nothing (0); a negative cap drops even a zero-width window (0). Measured upstream.
        val reversed = StepWindow(start = t0.plusSeconds(600), end = t0, delta = 5)
        assertEquals(listOf(ActivityInterval(t0.plusSeconds(600), t0)), HealthAlertEvaluator.activeStepIntervals(listOf(reversed)))
        assertEquals(emptyList(), HealthAlertEvaluator.activeStepIntervals(listOf(reversed), maxActivityWindow = Double.NaN))
        assertEquals(emptyList(), HealthAlertEvaluator.activeStepIntervals(listOf(StepWindow(t0, t0, 1)), maxActivityWindow = -1.0))
        // A negative delta is no movement.
        assertEquals(emptyList(), HealthAlertEvaluator.activeStepIntervals(listOf(StepWindow(t0, t0.plusSeconds(60), -40))))
        // A reversed interval [600 s, 0 s] with a 600 s pad covers no reading at 0 / 300 / 900 s (measured).
        val s3 = listOf(hr(150, 0), hr(150, 300), hr(150, 900))
        assertEquals(s3, HealthAlertEvaluator.nonExercising(s3, listOf(ActivityInterval(t0.plusSeconds(600), t0)), pad = 600.0))
        // Sessions and samples at the ends of Instant: the lead and pad saturate instead of throwing.
        val ends = listOf(RingEventLog.ActivitySession(Instant.MIN, Instant.MIN), RingEventLog.ActivitySession(Instant.MAX, Instant.MAX))
        val widened = HealthAlertEvaluator.ringActivityIntervals(ends)
        assertEquals(listOf(Instant.MIN, Instant.MAX.minusSeconds(600)), widened.map { it.start })
        val justPastThePad = HRSample(150, Instant.MIN.plusSeconds(601))
        assertEquals(listOf(justPastThePad), HealthAlertEvaluator.nonExercising(listOf(justPastThePad), widened))
        assertEquals(emptyList(), HealthAlertEvaluator.nonExercising(listOf(HRSample(150, Instant.MAX)), widened))
    }

    // MARK: tie and order rules

    @Test
    fun tiesKeepUpstreamsOrder() {
        // highHR names the FIRST of two equal peaks (measured: the one at 60 s).
        assertEquals(t0.plusSeconds(60), HealthAlertEvaluator.highHR(listOf(hr(130, 0), hr(140, 60), hr(140, 120), hr(120, 180)), thresholdBpm = 120)?.start)
        assertNull(HealthAlertEvaluator.highHR(emptyList(), thresholdBpm = Int.MIN_VALUE))
        assertEquals(Int.MIN_VALUE, HealthAlertEvaluator.highHR(listOf(hr(Int.MIN_VALUE, 0)), thresholdBpm = Int.MIN_VALUE)?.bpm)

        // The sustained rule sorts by time STABLY, as Swift's sort: a reading below the threshold at the
        // same instant as an elevated one resets the run only if it comes after it. Measured (300 s
        // duration): [105 @ 0, 105 @ 300, 70 @ 300, 105 @ 600] fires at 300 s; [105 @ 0, 70 @ 300,
        // 105 @ 300, 105 @ 600] at 600 s.
        assertEquals(t0.plusSeconds(300), HealthAlertEvaluator.elevatedHRInactive(listOf(hr(105, 0), hr(105, 300), hr(70, 300), hr(105, 600)), 100, 300.0)?.start)
        assertEquals(t0.plusSeconds(600), HealthAlertEvaluator.elevatedHRInactive(listOf(hr(105, 0), hr(70, 300), hr(105, 300), hr(105, 600)), 100, 300.0)?.start)
        // Forty readings over five instants: Swift's sort order (measured, bpm by position) and the hit.
        val big = List(40) { i -> hr(if (i % 3 == 0) 70 else 105, ((i * 7) % 5) * 150L) }
        assertEquals(
            "70,105,105,70,105,105,70,105,70,105,105,70,105,105,70,105,105,70,105,105,70,105,105,70,105,70,105,105,70,105,105,70,105,105,70,105,105,70,105,105",
            big.sortedBy { it.start }.joinToString(",") { it.bpm.toString() },
        )
        val hit = HealthAlertEvaluator.elevatedHRInactive(big, 100, 150.0)
        assertEquals(105 to 300L, hit?.let { it.bpm to it.start.epochSecond - t0.epochSecond })
    }

    @Test
    fun hostileSustainedRuleSettingsFollowUpstream() {
        // Measured over [105 @ 0, 105 @ 300, 105 @ 600]: duration NaN → nil, −1 → 0 s, 0 → 0 s, +∞ → nil;
        // gap NaN (never breaks) → 600 s, gap −1 (breaks at every reading) → nil.
        val run = listOf(hr(105, 0), hr(105, 300), hr(105, 600))
        fun at(d: Double, g: Double) = HealthAlertEvaluator.elevatedHRInactive(run, 100, d, g)?.start?.let { it.epochSecond - t0.epochSecond }
        assertEquals(
            listOf(null, 0L, 0L, 600L, null, null),
            listOf(at(Double.NaN, 300.0), at(-1.0, 300.0), at(0.0, 300.0), at(600.0, Double.NaN), at(600.0, -1.0), at(Double.POSITIVE_INFINITY, 300.0)),
        )
    }

    // MARK: quiet hours

    @Test
    fun quietHoursMinutesOutsideTheDayFollowUpstream() {
        // Stored minutes are taken as they are; the span is computed in 64 bits, as Swift's Int, so the
        // ends of Int never wrap. Measured upstream (seconds): a 32-bit difference would wrap the first
        // pair to −1 and answer 86 340 instead of 15 300.
        val spans = listOf(
            Int.MIN_VALUE to Int.MAX_VALUE, Int.MAX_VALUE to Int.MIN_VALUE, -60 to 1_500, 1_439 to 0, 0 to 1_439, 2_000 to -2_000, -1 to 1,
        ).map { (s, e) -> QuietHours(enabled = true, startMinutes = s, endMinutes = e).suppressedSpan }
        assertEquals(listOf(15_300.0, 71_100.0, 7_200.0, 60.0, 86_340.0, 19_200.0, 120.0), spans)
        // Every pair of stored minutes gives a span in 0…86 340 s, a whole minute (a sample of the Int range).
        val rng = Random(7)
        repeat(100_000) {
            val q = QuietHours(enabled = true, startMinutes = rng.nextInt(), endMinutes = rng.nextInt())
            val span = q.suppressedSpan
            assertTrue(span in 0.0..86_340.0 && span % 60.0 == 0.0, "$q → $span")
            assertEquals(Math.floorMod(q.endMinutes.toLong() - q.startMinutes, 1_440L) * 60.0, span)
        }

        // contains at 02:00, 12:00 and 00:00 UTC, measured: minutes outside the day compare as numbers.
        fun at(h: Int) = Instant.parse("2026-06-17T${h.toString().padStart(2, '0')}:00:00Z")
        val table = listOf(-60 to 420, 1_320 to 2_000, 2_000 to 3_000, Int.MIN_VALUE to Int.MAX_VALUE, 1_440 to 0, 0 to 1_440).map { (s, e) ->
            val q = QuietHours(enabled = true, startMinutes = s, endMinutes = e)
            listOf(q.contains(at(2), utc), q.contains(at(12), utc), q.contains(at(0), utc))
        }
        assertEquals(
            listOf(
                listOf(true, false, true), listOf(false, false, false), listOf(false, false, false),
                listOf(true, true, true), listOf(false, false, false), listOf(true, true, true),
            ),
            table,
        )
    }

    @Test
    fun quietHoursReadTheZonesWallClockAcrossClockChanges() {
        // Quiet 01:00–02:00 in New York, measured upstream: spring forward (8 Mar 2026, 02:00 → 03:00)
        // and fall back (1 Nov 2026, the 01:00 hour twice) — both 01:30s are quiet.
        val ny = ZoneId.of("America/New_York")
        val q = QuietHours(enabled = true, startMinutes = 60, endMinutes = 120)
        val answers = listOf(
            "2026-03-08T06:30:00Z", "2026-03-08T07:00:00Z", "2026-03-08T07:30:00Z",
            "2026-11-01T05:30:00Z", "2026-11-01T06:30:00Z", "2026-11-01T07:30:00Z",
        ).map { q.contains(Instant.parse(it), ny) }
        assertEquals(listOf(true, false, false, true, true, false), answers)
    }

    @Test
    fun quietHoursAtTheEndsOfInstantNeverThrow() {
        // An instant `java.time` cannot place in the zone is not inside quiet hours: the alert is not
        // held on a clock the port cannot read. That is the first and last year of `Instant`'s range
        // (years ±1 000 000 000, beyond the ±999 999 999 a local date-time holds) in every zone, and the
        // last placeable hours once a zone's offset pushes them over. The last and first placeable UTC
        // instants answer by their wall clock (23:59 and 00:00).
        val allDay = QuietHours(enabled = true, startMinutes = 0, endMinutes = 1_439)
        val lastMinute = QuietHours(enabled = true, startMinutes = 1_439, endMinutes = 0)
        val lastPlaceable = java.time.LocalDateTime.MAX.toInstant(ZoneOffset.UTC)
        val firstPlaceable = java.time.LocalDateTime.MIN.toInstant(ZoneOffset.UTC)
        assertTrue(lastMinute.contains(lastPlaceable, utc))
        assertFalse(allDay.contains(lastPlaceable, utc))
        assertTrue(allDay.contains(firstPlaceable, utc))
        assertFalse(allDay.contains(lastPlaceable, ZoneId.of("Pacific/Kiritimati")), "+14:00 pushes it past the last local year")
        // (A zone's rules that far back are its oldest offset: New York's local mean time, −4:56:02.)
        assertFalse(allDay.contains(firstPlaceable, ZoneId.of("America/New_York")), "−4:56:02 pushes it before the first")
        assertFalse(allDay.contains(firstPlaceable, ZoneOffset.ofHours(-11)), "−11:00 pushes it before the first")
        assertFalse(lastMinute.contains(Instant.MAX, utc))
        assertFalse(allDay.contains(Instant.MIN, utc))
        for (z in listOf(utc, ZoneId.of("America/New_York"), ZoneId.of("Asia/Kolkata"), ZoneId.of("Pacific/Kiritimati"))) {
            for (t in listOf(Instant.MIN, Instant.MAX, firstPlaceable, lastPlaceable)) {
                NotificationGate().filter(HealthNotification.entries, t, mapOf(HealthNotification.FEVER to t), allDay, z)
            }
        }
    }

    // MARK: the gate and its stored ledger

    @Test
    fun aFutureLastFiredHoldsTheAlertUntilRealTimeCatchesUp() {
        // KEPT AS UPSTREAM. The ledger stamps the wall clock at delivery, so a future stamp only exists
        // after the phone's clock ran ahead and was set back. Measured: a stamp 1 h, 2 h or 70 years
        // ahead holds the alert until stamp + the 2 h backoff — not before (stamp + 7 199 s), exactly
        // then (stamp + 7 200 s). The hold is the clock error plus the usual backoff, never more; and
        // `evaluate`'s recency cut drops every reading up to the stamp (measured: 0 hits).
        val gate = NotificationGate()
        val off = QuietHours(enabled = false)
        for (ahead in listOf(3_600L, 7_200L, 86_400L * 365 * 70)) {
            val fired = mapOf(HealthNotification.HIGH_HR to t0.plusSeconds(ahead))
            assertEquals(
                listOf(false, false, true),
                listOf(t0, t0.plusSeconds(ahead + 7_199), t0.plusSeconds(ahead + 7_200)).map { gate.shouldFire(HealthNotification.HIGH_HR, it, fired, off, utc) },
                "stamp $ahead s ahead",
            )
        }
        assertEquals(
            emptyList(),
            HealthAlertEvaluator.evaluate(listOf(hr(130, 0)), emptyList(), emptyList(), HealthAlertThresholds(), mapOf(HealthNotification.HIGH_HR to t0.plusSeconds(3_600))),
        )
        // A stamp at the end of time never throws.
        assertFalse(gate.shouldFire(HealthNotification.HIGH_HR, t0, mapOf(HealthNotification.HIGH_HR to Instant.MAX), off, utc))
        assertTrue(gate.shouldFire(HealthNotification.HIGH_HR, Instant.MAX, mapOf(HealthNotification.HIGH_HR to Instant.MIN), off, utc))
    }

    @Test
    fun backoffEdgesAndDuplicateCandidatesFollowUpstream() {
        // Measured (fired now; fired 1e9 s ago): backoff NaN → fires, fires; −1 → fires, fires; 0 → fires,
        // fires; +∞ → held, held (an infinite backoff never re-arms).
        val off = QuietHours(enabled = false)
        val answers = listOf(Double.NaN, -1.0, 0.0, Double.POSITIVE_INFINITY).map { iv ->
            val g = NotificationGate(renotifyInterval = iv)
            listOf(t0, t0.minusSeconds(1_000_000_000)).map { g.shouldFire(HealthNotification.HIGH_HR, t0, mapOf(HealthNotification.HIGH_HR to it), off, utc) }
        }
        assertEquals(listOf(listOf(true, true), listOf(true, true), listOf(true, true), listOf(false, false)), answers)
        // Duplicated candidates fire once each, in declaration order (measured: [highHR, fever]).
        val dup = listOf(HealthNotification.FEVER, HealthNotification.FEVER, HealthNotification.HIGH_HR, HealthNotification.HIGH_HR)
        assertEquals(listOf(HealthNotification.HIGH_HR, HealthNotification.FEVER), NotificationGate().filter(dup, t0, emptyMap(), off, utc))
    }

    // MARK: the temperature / fever routing and its per-night ledger

    @Test
    fun dayKeysAre64BitAndAgreeWithUpstreamFromTheGregorianSwitchOn() {
        // Night keys are `year * 10 000 + month * 100 + day`. Measured upstream (UTC; New York): from
        // 1582-10-15 on the keys agree — including past year 214 748, where a 32-bit key would wrap.
        val ny = ZoneId.of("America/New_York")
        val measured = listOf(
            -12_219_292_800L to (15_821_015L to null), // 1582-10-15 00:00 UTC (New York's local date is the 14th — see below)
            -12_219_206_400L to (15_821_016L to 15_821_015L),
            -2_208_988_800L to (19_000_101L to 18_991_231L),
            0L to (19_700_101L to 19_691_231L),
            1_781_697_600L to (20_260_617L to 20_260_617L),
            41_024_448_000L to (32_700_105L to 32_700_104L),
            10_000_000_000_000L to (3_188_570_520L to 3_188_570_520L),
        )
        for ((s, keys) in measured) {
            val t = Instant.ofEpochSecond(s)
            assertEquals(keys.first, TempFeverNotifications.dayKey(t, utc), "UTC @ $s")
            keys.second?.let { assertEquals(it, TempFeverNotifications.dayKey(t, ny), "New York @ $s") }
        }
        // KEPT DIFFERENCE: before 1582-10-15 Foundation's Gregorian calendar switches to the Julian one
        // (measured: 1582-10-14 UTC → 15821004, New York's 1582-10-14 evening → 15821004), and far before
        // year 1 it returns garbage (−1e13 s → 47130101). Here the calendar is the proleptic Gregorian
        // one throughout: a ring's dates start in 2000.
        assertEquals(15_821_014L, TempFeverNotifications.dayKey(Instant.ofEpochSecond(-12_219_379_200L), utc))
        assertEquals(15_821_014L, TempFeverNotifications.dayKey(Instant.ofEpochSecond(-12_219_292_800L), ny))
        assertTrue(assertNotNull(TempFeverNotifications.dayKey(Instant.ofEpochSecond(-10_000_000_000_000L), utc)) < 0L, "a negative year keys below zero")
        // Both 2026 New York clock changes key the zone's own calendar day (measured: 20260308, 20261101).
        val dst = listOf(
            "2026-03-08T06:30:00Z", "2026-03-08T07:00:00Z", "2026-03-08T07:30:00Z",
            "2026-11-01T05:30:00Z", "2026-11-01T06:30:00Z", "2026-11-01T07:30:00Z",
        ).map { TempFeverNotifications.dayKey(Instant.parse(it), ny) }
        assertEquals(listOf(20_260_308L, 20_260_308L, 20_260_308L, 20_261_101L, 20_261_101L, 20_261_101L), dst)
        // An instant java.time cannot place in the zone has no key (null), in every zone; nothing throws.
        for (z in listOf(utc, ny, ZoneId.of("Pacific/Kiritimati"))) {
            assertNull(TempFeverNotifications.dayKey(Instant.MAX, z))
            assertNull(TempFeverNotifications.dayKey(Instant.MIN, z))
        }
    }

    @Test
    fun thePerNightLedgerComparesKeysAsNumbersAndKeepsDuplicates() {
        // Measured: [fever, fever, highHR] for night 20260617 with fever last notified 20260616 → all
        // three kept, in order (a candidate not in the ledger always passes).
        val cands = listOf(HealthNotification.FEVER, HealthNotification.FEVER, HealthNotification.HIGH_HR)
        assertEquals(cands, TempFeverNotifications.freshForNight(cands, night = 20_260_617L, lastNotifiedNight = mapOf(HealthNotification.FEVER to 20_260_616L)))
        // Stored keys at the ends of Long: only a strictly newer night passes.
        val f = listOf(HealthNotification.FEVER)
        assertEquals(emptyList(), TempFeverNotifications.freshForNight(f, Long.MIN_VALUE, mapOf(HealthNotification.FEVER to Long.MAX_VALUE)))
        assertEquals(emptyList(), TempFeverNotifications.freshForNight(f, Long.MAX_VALUE, mapOf(HealthNotification.FEVER to Long.MAX_VALUE)))
        assertEquals(f, TempFeverNotifications.freshForNight(f, Long.MAX_VALUE, mapOf(HealthNotification.FEVER to Long.MIN_VALUE)))
        assertEquals(f, TempFeverNotifications.freshForNight(f, 0L, mapOf(HealthNotification.FEVER to -1L)))
        // Routing: every combination of the four flags and the fever flag raises exactly the flags set,
        // in declaration order, all inside the temperature / fever set.
        for (bits in 0 until 32) {
            val flags = SkinTempBaseline.AnomalyFlags(bits and 1 != 0, bits and 2 != 0, bits and 4 != 0, bits and 8 != 0)
            val out = TempFeverNotifications.notifications(flags, feverSuspected = bits and 16 != 0)
            val expected = listOf(
                HealthNotification.SKIN_TEMP_RISE, HealthNotification.SKIN_TEMP_DROP, HealthNotification.SKIN_TEMP_FLUCTUATION_RISE,
                HealthNotification.SKIN_TEMP_FLUCTUATION_DROP, HealthNotification.FEVER,
            ).filterIndexed { i, _ -> bits and (1 shl i) != 0 }
            assertEquals(expected, out, "bits $bits")
            assertTrue(TempFeverNotifications.NOTIFICATION_SET.containsAll(out))
        }
    }

    // MARK: the overnight-signals notification

    @Test
    fun theDeliveryWindowReadsTheZonesWallClock() {
        // 07:00 inclusive to 21:00 exclusive, by the wall clock in the zone given, seconds ignored.
        // Measured upstream (UTC): 06:59:59 no, 07:00 yes, 20:59:59 yes, 21:00 no, 00:00 no.
        val day = listOf("06:59:59", "07:00:00", "20:59:59", "21:00:00", "00:00:00")
            .map { HeadacheSignsNotifications.withinDeliveryWindow(Instant.parse("2026-07-20T${it}Z"), utc) }
        assertEquals(listOf(false, true, true, false, false), day)
        // Measured upstream in New York across both 2026 clock changes: the zone's own wall clock opens
        // and closes the window (01:30 EST, 05:59 EST / 06:59 EDT, 07:00 EDT, 07:59 EDT, 20:59 EDT,
        // 21:00 EDT; 01:30 EDT, 01:30 EST, 06:59 EST, 07:00 EST, 20:59 EST, 21:00 EST).
        val ny = ZoneId.of("America/New_York")
        val dst = listOf(
            "2026-03-08T06:30:00Z", "2026-03-08T10:59:00Z", "2026-03-08T11:00:00Z", "2026-03-08T11:59:00Z",
            "2026-03-09T00:59:00Z", "2026-03-09T01:00:00Z", "2026-11-01T05:30:00Z", "2026-11-01T06:30:00Z",
            "2026-11-01T11:59:00Z", "2026-11-01T12:00:00Z", "2026-11-02T01:59:00Z", "2026-11-02T02:00:00Z",
        ).map { HeadacheSignsNotifications.withinDeliveryWindow(Instant.parse(it), ny) }
        assertEquals(listOf(false, false, true, true, true, false, false, false, false, true, true, false), dst)
        // Far instants, measured upstream (UTC): Foundation's distant past and distant future are
        // midnight (outside); 1e13 s is 17:46:40 (inside, day key 3188570520), 8 h later outside.
        val far = listOf(SleepEdit.DISTANT_PAST, Instant.ofEpochSecond(64_092_211_200L), Instant.ofEpochSecond(10_000_000_000_000L), Instant.ofEpochSecond(10_000_000_028_800L))
        assertEquals(listOf(false, false, true, false), far.map { HeadacheSignsNotifications.withinDeliveryWindow(it, utc) })
        assertEquals(3_188_570_520L, HeadacheSignsNotifications.dayKey(far[2], utc))
        // KEPT DIFFERENCE: before about 4713 BC (Julian day 0) Foundation's time of day stops being the
        // wall clock (measured over 400 000 random instants: no disagreement after it); −1e13 s is
        // 06:13:20 here, outside the window, where upstream answers inside.
        assertFalse(HeadacheSignsNotifications.withinDeliveryWindow(Instant.ofEpochSecond(-10_000_000_000_000L), utc))
        assertTrue(HeadacheSignsNotifications.withinDeliveryWindow(Instant.ofEpochSecond(-9_999_999_971_200L), utc))
        // An instant java.time cannot place in the zone is outside the window and has no day key, so the
        // notification is never raised on a clock the port cannot read; nothing throws.
        for (z in listOf(utc, ny, ZoneId.of("Pacific/Kiritimati"))) {
            for (t in listOf(Instant.MIN, Instant.MAX)) {
                assertFalse(HeadacheSignsNotifications.withinDeliveryWindow(t, z))
                assertNull(HeadacheSignsNotifications.dayKey(t, z))
                assertEquals(emptyList(), overnight(now = t, zone = z))
            }
        }
    }

    @Test
    fun topSignalsDropUnreadableAndNonPositiveSharesAndClampTheLimit() {
        // Measured upstream: a NaN share is dropped (NaN > 0 is false); infinite shares tie and break by
        // declaration order, ahead of 1e308; −0.0, −1 and −∞ are dropped, the smallest subnormal kept.
        fun top(w: Map<HeadacheSignals.Feature, Double>, limit: Int = 2) = HeadacheSignsNotifications.topSignals(w, limit).map { it.rawValue }
        assertEquals(listOf("scheduleShift"), top(mapOf(HeadacheSignals.Feature.HRV_DEVIATION to Double.NaN, HeadacheSignals.Feature.SCHEDULE_SHIFT to 0.08)))
        assertEquals(
            listOf("hrvDeviation", "scheduleShift", "sleepEfficiencyDrop"),
            top(
                mapOf(
                    HeadacheSignals.Feature.SLEEP_EFFICIENCY_DROP to 1e308, HeadacheSignals.Feature.SCHEDULE_SHIFT to Double.POSITIVE_INFINITY,
                    HeadacheSignals.Feature.HRV_DEVIATION to Double.POSITIVE_INFINITY,
                ),
                3,
            ),
        )
        assertEquals(
            listOf("restingHRDeviation"),
            top(
                mapOf(
                    HeadacheSignals.Feature.HRV_DEVIATION to -0.0, HeadacheSignals.Feature.SCHEDULE_SHIFT to -1.0,
                    HeadacheSignals.Feature.RESTING_HR_DEVIATION to Double.MIN_VALUE, HeadacheSignals.Feature.SKIN_TEMP_DEVIATION to Double.NEGATIVE_INFINITY,
                ),
                3,
            ),
        )
        // A limit below zero takes nothing, as 0 does (upstream's `max(0, limit)`); Int.MAX takes every
        // ring-derived contributor, the calendar lookup never.
        val two = mapOf(HeadacheSignals.Feature.HRV_DEVIATION to 0.1, HeadacheSignals.Feature.SCHEDULE_SHIFT to 0.2, HeadacheSignals.Feature.PERIMENSTRUAL to 9.0)
        for (limit in listOf(-1, Int.MIN_VALUE, 0)) assertEquals(emptyList(), top(two, limit), "limit $limit")
        assertEquals(listOf("scheduleShift", "hrvDeviation"), top(two, Int.MAX_VALUE))
        assertEquals(emptyList(), top(mapOf(HeadacheSignals.Feature.PERIMENSTRUAL to 1.0)))
        // Every feature on one share, the map built in REVERSE declaration order: declaration order wins.
        val all = HeadacheSignals.Feature.entries.reversed().associateWith { 0.5 }
        assertEquals(
            listOf(
                "sleepEfficiencyDrop", "arousalLetdown", "hrvDeviation", "restingHRDeviation", "sleepFragmentation", "sleepDurationDeviation",
                "scheduleShift", "skinTempDeviation",
            ),
            top(all, 100),
        )
    }

    @Test
    fun theOvernightCandidateFollowsUpstreamOnHostileCountsTuningAndLedgers() {
        // Measured upstream at 2026-07-20 08:00 UTC, a flagged band, enabled, not retired, unsuppressed.
        val fires = listOf(HealthNotification.HEADACHE_SIGNS)
        // Stored frozen-day counts and a hostile unlock floor compare as plain numbers.
        assertEquals(emptyList(), overnight(frozenDayCount = Int.MIN_VALUE))
        assertEquals(emptyList(), overnight(frozenDayCount = -1))
        assertEquals(fires, overnight(frozenDayCount = Int.MAX_VALUE))
        assertEquals(fires, overnight(frozenDayCount = 0, tuning = HeadacheSignals.Tuning(minDaysForBanding = 0)))
        assertEquals(fires, overnight(frozenDayCount = -1, tuning = HeadacheSignals.Tuning(minDaysForBanding = -5)))
        assertEquals(fires, overnight(frozenDayCount = Int.MAX_VALUE, tuning = HeadacheSignals.Tuning(minDaysForBanding = Int.MAX_VALUE)))
        assertEquals(emptyList(), overnight(tuning = HeadacheSignals.Tuning(minDaysForBanding = Int.MAX_VALUE)))
        // The stored per-day ledger: only a strictly newer day passes; another notification's entry never holds it.
        assertEquals(emptyList(), overnight(ledger = mapOf(HealthNotification.HEADACHE_SIGNS to Long.MAX_VALUE)))
        assertEquals(fires, overnight(ledger = mapOf(HealthNotification.HEADACHE_SIGNS to Long.MIN_VALUE)))
        assertEquals(emptyList(), overnight(ledger = mapOf(HealthNotification.HEADACHE_SIGNS to 20_260_720L)))
        assertEquals(fires, overnight(ledger = mapOf(HealthNotification.HEADACHE_SIGNS to 20_260_719L)))
        assertEquals(fires, overnight(ledger = mapOf(HealthNotification.FEVER to 20_260_720L)))
        // Foundation's distant future and distant past are midnight: outside the window.
        assertEquals(emptyList(), overnight(now = Instant.ofEpochSecond(64_092_211_200L)))
        assertEquals(emptyList(), overnight(now = SleepEdit.DISTANT_PAST))
        // The per-day filter keeps order and duplicates, as the per-night one (measured).
        assertEquals(
            listOf(HealthNotification.HEADACHE_SIGNS, HealthNotification.HEADACHE_SIGNS),
            HeadacheSignsNotifications.freshForDay(
                listOf(HealthNotification.HEADACHE_SIGNS, HealthNotification.HEADACHE_SIGNS, HealthNotification.FEVER),
                day = 5L,
                lastNotifiedDay = mapOf(HealthNotification.FEVER to 5L),
            ),
        )
    }

    @Test
    fun everyCopyIsUpstreamsWordForWord() {
        // Upstream's copy for no signal, each single signal and every ordered pair (91 in all, a repeated
        // signal included), joined as "title|body" lines: SHA-256 measured on the pinned build. The longest
        // body is 179 characters.
        val features = HeadacheSignals.Feature.entries
        val signals = listOf(emptyList<HeadacheSignals.Feature>()) + features.map { listOf(it) } + features.flatMap { a -> features.map { b -> listOf(a, b) } }
        val copies = signals.map { HeadacheSignsNotifications.copy(it) }
        val joined = copies.joinToString("\n") { "${it.title}|${it.body}" }
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(joined.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(91, copies.size)
        assertEquals("b4d135fcbe7d01610f331b723fede6a20d4a4db97f82fc5933358dfad44d28bf", sha)
        assertEquals(179, copies.maxOf { it.body.codePointCount(0, it.body.length) })
        // Whole strings, measured: the daytime term alone, the calendar lookup (named when passed in, as
        // upstream — `topSignals` never passes it), a repeated signal, a mixed pair, and a third signal ignored.
        val tail = " (estimate). That is what we measured — it is not a forecast."
        assertEquals(
            HeadacheSignsNotifications.Text("Your recent signals stood out", "Daytime heart rate drifted furthest from your usual range over the past two days$tail"),
            HeadacheSignsNotifications.copy(listOf(HeadacheSignals.Feature.AROUSAL_LETDOWN)),
        )
        assertEquals(
            HeadacheSignsNotifications.Text("Last night was unusual for you", "Cycle phase drifted furthest from your usual range last night$tail"),
            HeadacheSignsNotifications.copy(listOf(HeadacheSignals.Feature.PERIMENSTRUAL)),
        )
        assertEquals(
            HeadacheSignsNotifications.Text("Your recent signals stood out", "Daytime heart rate and daytime heart rate drifted furthest from your usual range over the past two days$tail"),
            HeadacheSignsNotifications.copy(listOf(HeadacheSignals.Feature.AROUSAL_LETDOWN, HeadacheSignals.Feature.AROUSAL_LETDOWN)),
        )
        assertEquals(
            HeadacheSignsNotifications.Text(
                "Last night was unusual for you",
                "Daytime heart rate over the past two days and cycle phase last night drifted furthest from your usual range$tail",
            ),
            HeadacheSignsNotifications.copy(listOf(HeadacheSignals.Feature.AROUSAL_LETDOWN, HeadacheSignals.Feature.PERIMENSTRUAL, HeadacheSignals.Feature.HRV_DEVIATION)),
        )
        assertEquals(
            HeadacheSignsNotifications.Text("Last night was unusual for you", "Heart rate variability and resting heart rate drifted furthest from your usual range last night$tail"),
            HeadacheSignsNotifications.copy(listOf(HeadacheSignals.Feature.HRV_DEVIATION, HeadacheSignals.Feature.RESTING_HR_DEVIATION, HeadacheSignals.Feature.AROUSAL_LETDOWN)),
        )
    }

    /** The shipped decision at 2026-07-20 08:00 UTC with every gate set to "would fire", varying one thing. */
    private fun overnight(
        frozenDayCount: Int = 30,
        now: Instant = Instant.parse("2026-07-20T08:00:00Z"),
        ledger: Map<HealthNotification, Long> = emptyMap(),
        tuning: HeadacheSignals.Tuning = HeadacheSignals.Tuning(),
        zone: ZoneId = utc,
    ): List<HealthNotification> = HeadacheSignsNotifications.candidates(
        enabled = true, band = HeadacheSignals.Band.FLAGGED, suppressedBy = null, frozenDayCount = frozenDayCount, retired = false,
        now = now, lastNotifiedDay = ledger, tuning = tuning, zone = zone,
    )

    @Test
    fun aStoredRawNameIsMatchedExactly() {
        // The ledgers are keyed by raw name. A stored name is matched character for character, as
        // Swift's `init?(rawValue:)`: case, whitespace and look-alike letters never match, so a corrupt
        // key reads as no entry (the alert is not held by it) rather than as a neighbouring alert.
        for (n in HealthNotification.entries) assertEquals(n, HealthNotification.fromRawValue(n.rawValue))
        for (bad in listOf("HighHR", "highhr", "HIGHHR", " highHR", "highHR ", "highHR\u0000", "reminder.Sedentary", "reminder_sedentary", "", "hıghHR", "ｆｅｖｅｒ")) {
            assertNull(HealthNotification.fromRawValue(bad), "\"$bad\"")
        }
    }
}
