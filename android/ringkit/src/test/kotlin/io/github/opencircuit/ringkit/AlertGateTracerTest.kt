package io.github.opencircuit.ringkit

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Slice-end tracer for the health-alert gate: the ring's own `0x50` event frame, decoded and banked
 * after a drain, decides hours later — after quiet hours — whether a run of elevated heart rate is a
 * walk (suppressed) or a resting episode (alerted), and the quiet-hours gate holds the alert inside
 * the window and releases it after. Every step is the ported code, in the order the app calls it:
 *
 * `RingEventLog.decodeFrame` → `RingActivityEventLedger.merge` (at the drain) → `sessions` (at the
 * alert pass) → `HealthAlertEvaluator.ringActivityIntervals` → `nonExercising` → `evaluate`
 * (`elevatedHRInactive`) → `NotificationGate.filter`, with the HR window sized by
 * `HealthAlertLookback.instantLookback`.
 *
 * The frame is upstream's own 2026-09-27 walk frame (`RingEventLogTests.swift:10-13`): session
 * 10:52:05 → 11:23:55 New York time. The heart rate is synthetic. Zone: New York, the frame's own.
 */
class AlertGateTracerTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private fun at(h: Int, m: Int, s: Int = 0): Instant = LocalDateTime.of(2026, 9, 27, h, m, s).atZone(zone).toInstant()

    /** Upstream's walk frame, verbatim: `50 00 00` + start `0c adf555` + end `0c adfccb`. */
    private val walkFrame = bytes(
        0x50, 0x00, 0x00,
        0x10, 0x0f, 0x0c, 0xad, 0xf5, 0x55,
        0x10, 0x0a, 0x0c, 0xad, 0xfc, 0xcb,
    )

    /** The walk: 105 bpm every 150 s from 10:40 to 11:17:30 — elevated from its first minute. */
    private val walkHR = (0 until 16).map { HRSample(bpm = 105, start = at(10, 40).plusSeconds(it * 150L)) }

    /** A resting episode an hour after the walk: 110 bpm every 150 s from 12:30 to 12:42:30. */
    private val restHR = (0 until 6).map { HRSample(bpm = 110, start = at(12, 30).plusSeconds(it * 150L)) }

    /** Quiet hours 12:00–14:00, so the resting episode falls inside them and the pass at 14:00 is after. */
    private val quiet = QuietHours(enabled = true, startMinutes = 12 * 60, endMinutes = 14 * 60)

    /** One alert pass at [now], as the app runs it: look back, gate on the ring's sessions, evaluate, filter. */
    private fun alertPass(ledger: RingActivityEventLedger, now: Instant): List<HealthNotification> {
        val since = assertNotNull(addingSeconds(now, -HealthAlertLookback.instantLookback(quiet)))
        val hr = (walkHR + restHR).filter { it.start >= since && it.start <= now }
        val gated = HealthAlertEvaluator.nonExercising(hr, HealthAlertEvaluator.ringActivityIntervals(ledger.sessions(now)))
        val candidates = HealthAlertEvaluator.evaluate(hr = gated, spo2 = emptyList(), inactiveHR = gated, thresholds = HealthAlertThresholds())
            .map { it.notification }
        return NotificationGate().filter(candidates, now = now, lastFired = emptyMap(), quietHours = quiet, zone = zone)
    }

    @Test
    fun theRingsWalkIsSuppressedAndTheRestingRunIsHeldThroughQuietHoursThenReleased() {
        // The drain at 11:24 decodes the frame and banks its two markers.
        val frame = assertNotNull(RingEventLog.decodeFrame(walkFrame))
        val ledger = RingActivityEventLedger()
        ledger.merge(frame, ring = "ring", now = at(11, 24))
        assertEquals(listOf(RingEventLog.ActivitySession(at(10, 52, 5), at(11, 23, 55))), ledger.sessions(at(14, 0)))

        // Without the ring's verdict the walk alone alarms (the bug the gate exists for)…
        val bare = HealthAlertEvaluator.evaluate(hr = walkHR, spo2 = emptyList(), inactiveHR = walkHR, thresholds = HealthAlertThresholds())
        assertEquals(listOf(HealthNotification.ELEVATED_HR_INACTIVE), bare.map { it.notification })

        // …with it, the walk is suppressed (only its 10:40 head precedes start − lead) and only the
        // resting run is a candidate: 110 bpm, its tenth minute reached at 12:40:00.
        val gated = HealthAlertEvaluator.nonExercising(walkHR + restHR, HealthAlertEvaluator.ringActivityIntervals(ledger.sessions(at(14, 0))))
        assertEquals(listOf(at(10, 40)) + restHR.map { it.start }, gated.map { it.start })
        val hits = HealthAlertEvaluator.evaluate(hr = gated, spo2 = emptyList(), inactiveHR = gated, thresholds = HealthAlertThresholds())
        assertEquals(listOf(HealthAlertHit(HealthNotification.ELEVATED_HR_INACTIVE, 110.0, at(12, 40))), hits)

        // Inside quiet hours (13:00) the gate holds it; at 13:59 still; at 14:00 (the end is exclusive) it
        // is released — and the look-back, widened by the quiet span, still reaches the 12:30 run.
        assertEquals(emptyList(), alertPass(ledger, at(13, 0)))
        assertEquals(emptyList(), alertPass(ledger, at(13, 59)))
        assertEquals(listOf(HealthNotification.ELEVATED_HR_INACTIVE), alertPass(ledger, at(14, 0)))
        // A walk-only day never alerts, inside or after quiet hours.
        val walkOnly = RingActivityEventLedger()
        walkOnly.merge(frame, ring = "ring", now = at(11, 24))
        val walkGated = HealthAlertEvaluator.nonExercising(walkHR, HealthAlertEvaluator.ringActivityIntervals(walkOnly.sessions(at(14, 0))))
        assertTrue(HealthAlertEvaluator.evaluate(walkGated, emptyList(), walkGated, HealthAlertThresholds()).isEmpty())
    }

    @Test
    fun theWalksMarkersOutliveTheLongestLookBack() {
        // The worst quiet-hours window (23 h 59 min) widens the look-back to 35 h 59 min: an alert pass
        // that late still finds the walk's session in the ledger, re-merged by later drains.
        val worst = QuietHours(enabled = true, startMinutes = 12 * 60, endMinutes = 11 * 60 + 59)
        val frame = assertNotNull(RingEventLog.decodeFrame(walkFrame))
        val ledger = RingActivityEventLedger()
        ledger.merge(frame, ring = "ring", now = at(11, 24))
        val late = assertNotNull(addingSeconds(at(10, 40), HealthAlertLookback.instantLookback(worst)))
        ledger.merge(RingEventLog.Frame(hiddenCount = 0, events = emptyList()), ring = "ring", now = late) // a later, empty drain
        assertEquals(listOf(RingEventLog.ActivitySession(at(10, 52, 5), at(11, 23, 55))), ledger.sessions(late))
        val gated = HealthAlertEvaluator.nonExercising(walkHR, HealthAlertEvaluator.ringActivityIntervals(ledger.sessions(late)))
        assertEquals(listOf(at(10, 40)), gated.map { it.start })
    }
}
