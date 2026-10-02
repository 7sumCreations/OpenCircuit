package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ring's activity markers must outlive the alert look-back. Quiet hours DROP an alert candidate
 * rather than queue it, so the alert pass after quiet hours looks back up to
 * [HealthAlertLookback.instantLookback] for crossings; a crossing during a walk is suppressed only if
 * the walk's ring session is still on record — and the session's START marker can sit the 4 h
 * open-session cap plus the 10 min lead and the 10 min recovery pad before the oldest reading the
 * look-back can reach. The ledger keeps markers for [RingActivityEventLedger.RETENTION]; if that were
 * shorter, the walk recorded before quiet hours would stop suppressing the "elevated heart rate while
 * inactive" alarm the moment quiet hours end.
 *
 * Kotlin-only. Every bound here is computed from the ported constants and functions, never typed, and
 * every quiet-hours window there is is swept: 2 × 1 440 × 1 440 settings.
 */
class AlertLookbackRetentionTest {

    private fun seconds(d: Duration): Double = d.seconds + d.nano / 1e9

    /** The oldest start-marker age, in seconds, the alert gate can need under [quiet]. */
    private fun need(quiet: QuietHours): Double =
        HealthAlertLookback.instantLookback(quiet) + seconds(RingEventLog.OPEN_SESSION_CAP) +
            HealthAlertEvaluator.RING_ACTIVITY_LEAD + HealthAlertEvaluator.RECOVERY_PAD

    @Test
    fun retentionCoversTheAlertLookBackForEveryQuietHoursWindow() {
        val retention = seconds(RingActivityEventLedger.RETENTION)
        var worst = Double.NEGATIVE_INFINITY
        var worstAt: QuietHours? = null
        var checked = 0
        for (enabled in listOf(false, true)) for (start in 0 until 1_440) for (end in 0 until 1_440) {
            val q = QuietHours(enabled = enabled, startMinutes = start, endMinutes = end)
            val n = need(q)
            if (n > retention) throw AssertionError("RETENTION ${RingActivityEventLedger.RETENTION} is shorter than the $n s the alert gate needs under $q")
            if (n > worst) {
                worst = n
                worstAt = q
            }
            checked += 1
        }
        assertEquals(2 * 1_440 * 1_440, checked)
        // The worst window is one that suppresses all but a minute of the day (span 23 h 59 min).
        assertEquals(1_439 * 60.0, worstAt?.suppressedSpan)
        assertEquals(HealthAlertLookback.BASE_INSTANT_LOOKBACK + 1_439 * 60.0, HealthAlertLookback.instantLookback(worstAt!!))
        assertTrue(worst <= retention, "worst case $worst s within $retention s")
        // Stored minutes outside the day reduce to the same spans (the span is taken modulo a day), so
        // no stored setting needs more than the swept worst case.
        for (s in listOf(Int.MIN_VALUE, -1_441, -1, 1_440, 2_879, Int.MAX_VALUE)) for (e in listOf(Int.MIN_VALUE, -1, 0, 1_439, 1_440, Int.MAX_VALUE)) {
            assertTrue(need(QuietHours(enabled = true, startMinutes = s, endMinutes = e)) <= worst, "stored $s → $e")
        }
    }

    @Test
    fun aMarkerAtTheWorstCaseAgeIsStillOnRecordWhenTheGateAsks() {
        // At the trigger: a walk whose start marker is exactly the worst-case age at the alert pass is
        // still banked by the ledger's own merge and comes back as a session; a marker one second older
        // than RETENTION is pruned by the same merge.
        val worst = need(QuietHours(enabled = true, startMinutes = 1, endMinutes = 0))
        val now = Instant.parse("2026-06-18T07:00:00Z")
        val cursorNow = now.epochSecond - Command.SYNC_EPOCH
        val oldestNeeded = RingEvent(type = RingEventLog.ACTIVITY_TYPE, value = RingEventLog.ACTIVITY_START, cursor = cursorNow - worst.toLong())
        val itsEnd = RingEvent(type = RingEventLog.ACTIVITY_TYPE, value = RingEventLog.ACTIVITY_END, cursor = cursorNow - worst.toLong() + 3_600)
        val tooOld = RingEvent(type = RingEventLog.ACTIVITY_TYPE, value = RingEventLog.ACTIVITY_START, cursor = cursorNow - RingActivityEventLedger.RETENTION.seconds - 1)

        val ledger = RingActivityEventLedger()
        ledger.merge(RingEventLog.Frame(hiddenCount = 0, events = listOf(tooOld, oldestNeeded, itsEnd)), ring = "A", now = now)
        assertEquals(listOf(oldestNeeded, itsEnd), ledger.events["A"])
        val sessions = ledger.sessions(now = now)
        assertEquals(listOf(RingEventLog.ActivitySession(oldestNeeded.date, itsEnd.date)), sessions)
        // …and it still gates: a reading inside the walk is dropped, one past the recovery pad is kept.
        val during = HRSample(130, oldestNeeded.date.plusSeconds(60))
        val after = HRSample(130, itsEnd.date.plusSeconds(HealthAlertEvaluator.RECOVERY_PAD.toLong() + 1))
        assertEquals(listOf(after), HealthAlertEvaluator.nonExercising(listOf(during, after), HealthAlertEvaluator.ringActivityIntervals(sessions)))
    }
}
