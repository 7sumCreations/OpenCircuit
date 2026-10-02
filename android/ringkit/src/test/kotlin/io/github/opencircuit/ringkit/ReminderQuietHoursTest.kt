package io.github.opencircuit.ringkit

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Kotlin-only: reminders and alerts share ONE quiet-hours gate, tested at its trigger instants. A
 * sedentary reminder and a high-HR alert raised inside the user's quiet hours are held there and
 * released together when the window ends — re-derived on the next pass, since the gate drops a
 * candidate rather than queueing it, which is why the alert pass looks back by the quiet span. The
 * bedtime reminder is routed as upstream's app routes it (`HealthNotificationCenter.swift:736-747`): it
 * bypasses the quiet-hours mute (its own window usually lies inside the night's quiet hours) but keeps
 * the backoff.
 *
 * Every step is the ported code, in the order the app calls it: the reminder predicates, the alert
 * evaluator over the look-back window, then [NotificationGate.filter]. The heart rate is synthetic.
 * Zone: Asia/Kolkata (UTC+5:30 all year), chosen so the test bites — a gate or reminder reading UTC
 * would see the 12:00–14:00 quiet window as 06:30–08:30 and release (or never raise) everything here.
 */
class ReminderQuietHoursTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private fun at(h: Int, m: Int, s: Int = 0): Instant = LocalDateTime.of(2026, 6, 17, h, m, s).atZone(zone).toInstant()

    private val gate = NotificationGate()
    private val sedentary = SedentaryReminder()

    /** One app pass at [now]: the alert candidates, then the sedentary reminder, through the shared gate. */
    private fun pass(
        now: Instant,
        quiet: QuietHours,
        hr: List<HRSample>,
        lastActivityAt: Instant,
        lastFired: Map<HealthNotification, Instant>,
    ): List<HealthNotification> {
        val since = addingSeconds(now, -HealthAlertLookback.instantLookback(quiet))!!
        val window = hr.filter { it.start >= since && it.start <= now }
        val candidates = HealthAlertEvaluator.evaluate(window, emptyList(), window, HealthAlertThresholds(), lastFired).map { it.notification }.toMutableList()
        // Frames keep arriving (the ring is worn and connected); no step has landed since lastActivityAt.
        if (sedentary.shouldFire(lastActivityAt = lastActivityAt, now = now, lastRingDataAt = now.minusSeconds(60), zone = zone)) {
            candidates += HealthNotification.SEDENTARY_REMINDER
        }
        return gate.filter(candidates, now, lastFired, quiet, zone)
    }

    @Test
    fun aSedentaryReminderAndAHighHRAlertAreHeldThroughQuietHoursAndReleasedAfter() {
        val quiet = QuietHours(enabled = true, startMinutes = 12 * 60, endMinutes = 14 * 60)
        val lastStep = at(11, 30) // still since 11:30: the move reminder is due from 12:20
        val hr = listOf(HRSample(bpm = 130, start = at(12, 40))) // over the 120 bpm threshold, inside quiet hours
        val none = emptyMap<HealthNotification, Instant>()

        // Before quiet hours start the reminder is not yet due and nothing fires.
        assertEquals(emptyList(), pass(at(11, 59), quiet, hr, lastStep, none))
        // Inside quiet hours: the reminder is due from 12:20 and the alert from 12:40 — both held.
        assertEquals(emptyList(), pass(at(12, 20), quiet, hr, lastStep, none))
        assertEquals(emptyList(), pass(at(12, 40), quiet, hr, lastStep, none))
        assertEquals(emptyList(), pass(at(13, 59, 59), quiet, hr, lastStep, none))
        // The window's end is exclusive: at 14:00 both are released together, in declaration order.
        val released = pass(at(14, 0), quiet, hr, lastStep, none)
        assertEquals(listOf(HealthNotification.HIGH_HR, HealthNotification.SEDENTARY_REMINDER), released)

        // Delivered once: the app stamps both, and the backoff holds them on the next pass…
        val fired = released.associateWith { at(14, 0) }
        assertEquals(emptyList(), pass(at(14, 30), quiet, hr, lastStep, fired))
        // …and once it has passed, only the reminder comes back (still no step); the alert's reading is
        // older than its stamp, so it is not raised twice.
        assertEquals(listOf(HealthNotification.SEDENTARY_REMINDER), pass(at(16, 0), quiet, hr, lastStep, fired))

        // Without quiet hours the same day raises each at its own trigger instant.
        val off = QuietHours()
        assertEquals(listOf(HealthNotification.SEDENTARY_REMINDER), pass(at(12, 20), off, hr, lastStep, none))
        assertEquals(listOf(HealthNotification.HIGH_HR, HealthNotification.SEDENTARY_REMINDER), pass(at(12, 40), off, hr, lastStep, none))
    }

    @Test
    fun theBedtimeReminderBypassesQuietHoursButKeepsTheBackoffAsUpstream() {
        // The default quiet window (22:00–07:00) swallows a 23:00 bedtime's whole [22:30, 23:00) window.
        val quiet = QuietHours(enabled = true)
        val bedtime = BedtimeReminder(minutesBefore = 30)
        fun route(now: Instant, lastFired: Map<HealthNotification, Instant>): List<HealthNotification> {
            val candidates = if (bedtime.shouldFire(now = now, bedMinutes = 23 * 60, wakeMinutes = 7 * 60, zone = zone)) {
                listOf(HealthNotification.BEDTIME_REMINDER)
            } else {
                emptyList()
            }
            // Upstream's split: bedtime through the gate with quiet hours off; everything else under them.
            val others = candidates.filter { it != HealthNotification.BEDTIME_REMINDER }
            val bed = candidates.filter { it == HealthNotification.BEDTIME_REMINDER }
            return gate.filter(others, now, lastFired, quiet, zone) + gate.filter(bed, now, lastFired, QuietHours(enabled = false), zone)
        }
        assertEquals(emptyList(), route(at(22, 29), emptyMap()))
        assertEquals(listOf(HealthNotification.BEDTIME_REMINDER), route(at(22, 30), emptyMap()))
        // Through the user's quiet hours it would have been held — the bypass is what delivers it.
        assertEquals(emptyList(), gate.filter(listOf(HealthNotification.BEDTIME_REMINDER), at(22, 30), emptyMap(), quiet, zone))
        // At most once a night: fired at 22:30, the backoff holds it for the rest of the window.
        val fired = mapOf(HealthNotification.BEDTIME_REMINDER to at(22, 30))
        assertEquals(emptyList(), route(at(22, 59), fired))
        assertEquals(emptyList(), route(at(23, 0), emptyMap()))
    }
}
