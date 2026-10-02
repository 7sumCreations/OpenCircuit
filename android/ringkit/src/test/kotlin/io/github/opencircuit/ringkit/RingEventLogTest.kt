package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `0x50` event log and the ring's own activity sessions decoded from it.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/RingEventLogTests.swift
 * (@ b1c2fdd), 15 of 15 tests. The two frames are the ring's own bytes from upstream's
 * 2026-09-27 diagnostics bundle — event markers only, no health values. Not ported here: the
 * ledger test's JSON round-trip lines `:102-103` (the stored form is decided with the storage
 * design). The three alert-gate tests `:181`, `:200`, `:211` (with `walkHR()`, `:176-179`) arrived
 * with the alert policy they drive (`HealthAlertEvaluator`).
 */
class RingEventLogTest {

    /** :10-13 — 2026-09-27T15:24:09Z, the frame that closed the post-walk drain. */
    private val walkFrame = bytes(
        0x50, 0x00, 0x00,
        0x10, 0x0f, 0x0c, 0xad, 0xf5, 0x55,
        0x10, 0x0a, 0x0c, 0xad, 0xfc, 0xcb,
    )

    /** :14-21 — 2026-09-26T20:55:25Z, two `0x15` entries then two `0x10` start/end pairs. */
    private val eveningFrame = bytes(
        0x50, 0x00, 0x00,
        0x15, 0x21, 0x0c, 0xac, 0xe3, 0x0a,
        0x15, 0x12, 0x0c, 0xac, 0xe3, 0x46,
        0x10, 0x0f, 0x0c, 0xac, 0xea, 0x53,
        0x10, 0x0a, 0x0c, 0xac, 0xed, 0x8c,
        0x10, 0x0f, 0x0c, 0xac, 0xf5, 0x27,
        0x10, 0x0a, 0x0c, 0xac, 0xf8, 0x42,
    )

    /** :23 — upstream parses with `ISO8601DateFormatter`. */
    private fun utc(s: String): Instant = Instant.parse(s)

    private fun hours(h: Long): Duration = Duration.ofHours(h)

    // MARK: decode

    // :27
    @Test
    fun decodesWalkFrameToTheRingsOwnStartAndEnd() {
        val events = assertNotNull(RingEventLog.decode(walkFrame))
        assertEquals(listOf(0x10, 0x10), events.map { it.type })
        assertEquals(listOf(0x0f, 0x0a), events.map { it.value })
        // 10:52:05 and 11:23:55 EDT — the walk the wearer reported as 10:40–11:15.
        assertEquals(utc("2026-09-27T14:52:05Z"), events[0].date)
        assertEquals(utc("2026-09-27T15:23:55Z"), events[1].date)
    }

    // :36
    @Test
    fun decodesMultiEntryFrameAndPairsOnlyActivityMarkers() {
        val events = assertNotNull(RingEventLog.decode(eveningFrame))
        assertEquals(6, events.size)
        val sessions = RingEventLog.activitySessions(events, now = utc("2026-09-27T00:00:00Z"))
        assertEquals(2, sessions.size, "the 0x15 entries are not activity")
        assertEquals(utc("2026-09-26T19:52:51Z"), sessions[0].start)
        assertEquals(utc("2026-09-26T20:06:36Z"), sessions[0].end)
        assertEquals(utc("2026-09-26T20:39:03Z"), sessions[1].start)
        assertEquals(utc("2026-09-26T20:52:18Z"), sessions[1].end)
    }

    // :47
    @Test
    fun rejectsNonEventShapes() {
        assertNull(RingEventLog.decode(bytes(0x50, 0x00, 0x00)), "no entries")
        assertNull(RingEventLog.decode(walkFrame.copyOf(walkFrame.size - 1)), "partial entry")
        assertNull(RingEventLog.decode(bytes(0x4c) + walkFrame.copyOfRange(1, walkFrame.size)), "wrong opcode")
        // The legacy 12-byte cursor report is not a whole number of entries.
        assertNull(RingEventLog.decode(bytes(0x50, 0x00, 0x00, 0x15, 0x0c, 0x22, 0xaa, 0xe4, 0x0c, 0x22, 0xac, 0xb5)))
    }

    // MARK: pairing edge cases

    /** :58 */
    private fun ev(v: Int, cursor: Long) = RingEvent(type = 0x10, value = v, cursor = cursor)

    // :60
    @Test
    fun openStartRunsToNowAndOrphanEndIsDropped() {
        val base = 0x0cad_0000L
        val now = RingEvent(type = 0, value = 0, cursor = base + 3000).date
        val sessions = RingEventLog.activitySessions(
            listOf(
                ev(0x0a, base), // end whose start fell off the log → dropped
                ev(0x0f, base + 1000), // still in progress
            ),
            now = now,
        )
        assertEquals(1, sessions.size)
        assertEquals(ev(0x0f, base + 1000).date, sessions[0].start)
        assertEquals(now, sessions[0].end)
    }

    // :72
    @Test
    fun openSessionIsCappedSoALostEndCannotSilenceForDays() {
        val start = ev(0x0f, 0x0cad_0000L)
        val now = start.date + hours(30)
        val sessions = RingEventLog.activitySessions(listOf(start), now = now)
        assertEquals(start.date + RingEventLog.OPEN_SESSION_CAP, sessions.firstOrNull()?.end)
    }

    // :79
    @Test
    fun duplicateLogResendsCollapse() {
        val events = assertNotNull(RingEventLog.decode(walkFrame))
        assertEquals(1, RingEventLog.activitySessions(events + events, now = utc("2026-09-28T00:00:00Z")).size)
    }

    // :84 — upstream passes `Date()`; the result is empty for any instant, so a fixed one is used.
    @Test
    fun sleepPairSharingTheTypeIsNotActivity() {
        assertTrue(
            RingEventLog.activitySessions(listOf(ev(0x07, 100), ev(0x08, 5000)), now = utc("2026-09-27T00:00:00Z")).isEmpty(),
        )
    }

    // MARK: ledger

    // :90 — without the JSON round trip of :102-103 (stored form not ported).
    @Test
    fun ledgerKeepsOnlyActivityMarkersDedupesAndPrunes() {
        val ledger = RingActivityEventLedger()
        val now = utc("2026-09-27T15:30:00Z")
        val walk = assertNotNull(RingEventLog.decodeFrame(walkFrame))
        ledger.merge(walk, ring = "A", now = now)
        ledger.merge(walk, ring = "A", now = now)
        ledger.merge(assertNotNull(RingEventLog.decodeFrame(eveningFrame)), ring = "A", now = now)
        assertEquals(6, ledger.events["A"]?.size, "2 walk + 4 evening activity markers; 0x15 dropped; resend deduped")
        assertEquals(3, ledger.sessions(now = now).size)
        ledger.merge(
            RingEventLog.Frame(hiddenCount = 0, events = emptyList()),
            ring = "A",
            now = now + RingActivityEventLedger.RETENTION - hours(1),
        )
        assertEquals(2, ledger.events["A"]?.size, "evening markers aged out, the walk's kept")
    }

    // MARK: review findings (2026-09-27)

    // :111 — an OVERFLOWED log: `50 00 0f` + 40 entries, 243 bytes. The visible entries still
    // decode, and the overflow is recorded so diagnostics can say the gate is blind.
    @Test
    fun overflowedFrameDecodesAndIsRecorded() {
        val list = mutableListOf(0x50, 0x00, 0x0f)
        for (i in 0 until 40) list += listOf(0x10, if (i % 2 == 0) 0x0f else 0x0a, 0x0c, 0xad, i, 0x00)
        val raw = bytes(*list.toIntArray())
        assertEquals(243, raw.size)
        val frame = assertNotNull(RingEventLog.decodeFrame(raw))
        assertEquals(15, frame.hiddenCount)
        assertEquals(40, frame.events.size)
        val ledger = RingActivityEventLedger()
        val now = frame.events.last().date + Duration.ofSeconds(60)
        ledger.merge(frame, ring = "A", now = now)
        assertEquals(15, ledger.overflow["A"]?.hidden)
        ledger.merge(assertNotNull(RingEventLog.decodeFrame(walkFrame)), ring = "A", now = now)
        assertNull(ledger.overflow["A"], "a cleared log clears the flag")
    }

    // :128 — a start whose end was lost, followed by a later complete pair, must NOT merge into one
    // 10 h session; the first closes at the second start, capped. Upstream passes `Date()` as now;
    // every marker here is closed, so a fixed instant gives the same result.
    @Test
    fun repeatedStartClosesTheEarlierSessionAndEverySessionIsCapped() {
        val t = 0x0cad_0000L
        val now = utc("2026-09-28T00:00:00Z")
        val sessions = RingEventLog.activitySessions(
            listOf(ev(0x0f, t), ev(0x0f, t + 10 * 3600), ev(0x0a, t + 10 * 3600 + 20 * 60)),
            now = now,
        )
        assertEquals(2, sessions.size)
        for ((a, b) in sessions) {
            assertTrue(Duration.between(a, b) <= RingEventLog.OPEN_SESSION_CAP)
        }
        assertEquals(ev(0x0f, t + 10 * 3600).date, sessions[1].start)
        // A CLOSED but absurdly long pair is capped too.
        val long = RingEventLog.activitySessions(listOf(ev(0x0f, t), ev(0x0a, t + 20 * 3600)), now = now)
        assertEquals(RingEventLog.OPEN_SESSION_CAP, long.firstOrNull()?.let { Duration.between(it.start, it.end) })
    }

    // :144 — while overflowed the same frozen frame arrives every few minutes: it must not rewrite
    // the ledger each time. And a ring that never reports again ages out.
    @Test
    fun repeatedOverflowIsStableAndRetiredRingsAgeOut() {
        val t = 0x0cad_0000L
        val now = ev(0x0f, t).date
        val ledger = RingActivityEventLedger()
        ledger.merge(RingEventLog.Frame(hiddenCount = 15, events = listOf(ev(0x0f, t))), ring = "A", now = now)
        val once = ledger.copy() // upstream: `let once = ledger` (a struct copy)
        ledger.merge(
            RingEventLog.Frame(hiddenCount = 15, events = listOf(ev(0x0f, t))),
            ring = "A",
            now = now + Duration.ofSeconds(180),
        )
        assertEquals(once, ledger)
        ledger.merge(
            RingEventLog.Frame(hiddenCount = 0, events = emptyList()),
            ring = "B",
            now = now + RingActivityEventLedger.RETENTION + Duration.ofSeconds(60),
        )
        assertNull(ledger.events["A"])
        assertNull(ledger.overflow["A"])
    }

    // :159 — two rings' markers never pair with each other.
    @Test
    fun markersArePairedPerRing() {
        val t = 0x0cad_0000L
        val ledger = RingActivityEventLedger()
        val now = ev(0x0a, t + 7200).date
        ledger.merge(RingEventLog.Frame(hiddenCount = 0, events = listOf(ev(0x0f, t))), ring = "A", now = now) // A still active
        ledger.merge(
            RingEventLog.Frame(hiddenCount = 0, events = listOf(ev(0x0f, t + 600), ev(0x0a, t + 1200))),
            ring = "B",
            now = now,
        )
        val sessions = ledger.sessions(now = now)
        assertEquals(2, sessions.size)
        assertTrue(sessions.any { it.start == ev(0x0f, t).date && it.end == now }, "A runs open to now")
        assertTrue(sessions.any { it.start == ev(0x0f, t + 600).date && it.end == ev(0x0a, t + 1200).date })
    }

    // MARK: the alert gate (synthetic HR)

    /**
     * :176-179 — a synthetic walk: HR ≥ 100 every 150 s from 10:40 to 11:17:30 EDT — elevated from the
     * first minute, i.e. the full 12-min recognition lag the real walk showed — no steps observed (the
     * ring was silent), ring session 10:52:05 → 11:23:55 from [walkFrame].
     */
    private fun walkHR(): List<HRSample> {
        val start = utc("2026-09-27T14:40:00Z")
        return (0 until 16).map { HRSample(bpm = 105, start = start.plusSeconds(it * 150L)) }
    }

    // :181
    @Test
    fun ringActivitySessionSuppressesTheWalkAlarm() {
        val thresholds = HealthAlertThresholds()
        // Without the ring's verdict the gate has no evidence and the walk alarms — the bug.
        assertEquals(
            listOf(HealthNotification.ELEVATED_HR_INACTIVE),
            HealthAlertEvaluator.evaluate(hr = walkHR(), spo2 = emptyList(), inactiveHR = walkHR(), thresholds = thresholds).map { it.notification },
        )

        val sessions = RingEventLog.activitySessions(assertNotNull(RingEventLog.decode(walkFrame)), now = utc("2026-09-27T15:30:00Z"))
        val gated = HealthAlertEvaluator.nonExercising(walkHR(), activeIntervals = HealthAlertEvaluator.ringActivityIntervals(sessions))
        // Only the 10:40:00 reading precedes `start − lead` (10:42:05); alone it is no run.
        assertEquals(listOf(utc("2026-09-27T14:40:00Z")), gated.map { it.start })
        assertTrue(HealthAlertEvaluator.evaluate(hr = gated, spo2 = emptyList(), inactiveHR = gated, thresholds = thresholds).isEmpty())
    }

    // :200 — the lead is load-bearing whenever HR is up ≥ 10 min before the ring's stamp (the real
    // walk crossed 100 bpm only ~7 min before it, so it would NOT have alarmed on its head alone).
    @Test
    fun withoutTheLeadTheUnrecognisedHeadStillAlarms() {
        val sessions = RingEventLog.activitySessions(assertNotNull(RingEventLog.decode(walkFrame)), now = utc("2026-09-27T15:30:00Z"))
        val gated = HealthAlertEvaluator.nonExercising(walkHR(), activeIntervals = HealthAlertEvaluator.ringActivityIntervals(sessions, lead = 0.0))
        assertEquals(105, HealthAlertEvaluator.elevatedHRInactive(gated, thresholdBpm = 100, minDuration = 10 * 60.0)?.bpm)
    }

    // :211 — safety: a resting run OUTSIDE any ring session (beyond the lead and the recovery pad)
    // still fires — the ring's verdict only removes readings it covers.
    @Test
    fun restingRunOutsideTheSessionStillAlerts() {
        val sessions = RingEventLog.activitySessions(assertNotNull(RingEventLog.decode(walkFrame)), now = utc("2026-09-27T20:00:00Z"))
        val restStart = utc("2026-09-27T16:30:00Z") // 12:30 EDT, an hour after the session
        val rest = (0 until 6).map { HRSample(bpm = 110, start = restStart.plusSeconds(it * 150L)) }
        val gated = HealthAlertEvaluator.nonExercising(walkHR() + rest, activeIntervals = HealthAlertEvaluator.ringActivityIntervals(sessions))
        assertEquals(rest, gated.drop(1), "the whole resting run survives the gate")
        assertNotNull(HealthAlertEvaluator.elevatedHRInactive(gated, thresholdBpm = 100, minDuration = 10 * 60.0))
    }
}
