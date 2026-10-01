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
 * (@ b1c2fdd), 12 of 15 tests. The two frames are the ring's own bytes from upstream's
 * 2026-09-27 diagnostics bundle — event markers only, no health values. Not ported here: the
 * ledger test's JSON round-trip lines `:102-103` (the stored form is decided with the storage
 * design), and the three alert-gate tests `:181`, `:200`, `:211`, which drive upstream's
 * `HealthAlertEvaluator` (`HealthAlerts.swift`, ported with the alert engines).
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
}
