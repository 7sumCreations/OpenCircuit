package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kotlin-only checks of what Swift's types guaranteed the event-log and diagnostics types for
 * free: `UInt8` / `UInt32` fields cannot leave their range, a `struct` or `[UInt8]` copies on
 * assignment, and two values compare by content.
 */
class DiagnosticsGuardTest {

    private val t = 0x0cad_0000L

    private fun ev(v: Int, cursor: Long) = RingEvent(type = 0x10, value = v, cursor = cursor)

    // --- RingEvent: UInt8 / UInt32 ranges ---

    @Test
    fun ringEventKeepsItsUnsignedRanges() {
        RingEvent(type = 0, value = 0, cursor = 0)
        RingEvent(type = 0xFF, value = 0xFF, cursor = 0xFFFF_FFFFL)
        assertFailsWith<IllegalArgumentException> { RingEvent(type = 0x100, value = 0, cursor = 0) }
        assertFailsWith<IllegalArgumentException> { RingEvent(type = -1, value = 0, cursor = 0) }
        assertFailsWith<IllegalArgumentException> { RingEvent(type = 0, value = 0x100, cursor = 0) }
        assertFailsWith<IllegalArgumentException> { RingEvent(type = 0, value = -1, cursor = 0) }
        assertFailsWith<IllegalArgumentException> { RingEvent(type = 0, value = 0, cursor = 0x1_0000_0000L) }
        assertFailsWith<IllegalArgumentException> { RingEvent(type = 0, value = 0, cursor = -1) }
    }

    @Test
    fun decodeReadsEveryByteUnsigned() {
        val frame = assertNotNull(RingEventLog.decodeFrame(bytes(0x50, 0x00, 0xff, 0xf0, 0x80, 0xff, 0xff, 0xff, 0xff)))
        assertEquals(255, frame.hiddenCount)
        assertEquals(listOf(RingEvent(type = 0xf0, value = 0x80, cursor = 0xFFFF_FFFFL)), frame.events)
        assertEquals(Instant.ofEpochSecond(Command.SYNC_EPOCH + 0xFFFF_FFFFL), frame.events[0].date)
        // A frame whose second byte is not 0x00 is not an event frame.
        assertNull(RingEventLog.decodeFrame(bytes(0x50, 0x01, 0x00, 0x10, 0x0f, 0, 0, 0, 1)))
    }

    // --- RingEventLog.Frame: its own list, compared by value ---

    @Test
    fun frameKeepsItsOwnEventList() {
        val source = mutableListOf(ev(0x0f, t))
        val frame = RingEventLog.Frame(hiddenCount = 0, events = source)
        source += ev(0x0a, t + 60)
        assertEquals(1, frame.events.size)
        assertEquals(RingEventLog.Frame(0, listOf(ev(0x0f, t))), frame)
        assertEquals(RingEventLog.Frame(0, listOf(ev(0x0f, t))).hashCode(), frame.hashCode())
        assertNotEquals(RingEventLog.Frame(1, listOf(ev(0x0f, t))), frame)
    }

    // --- RingActivityEventLedger: a mutating struct, here a single-owner class with copy() ---

    @Test
    fun ledgerCopyNeverMovesWithTheOriginal() {
        val now = ev(0x0f, t).date
        val a = RingActivityEventLedger()
        a.merge(RingEventLog.Frame(0, listOf(ev(0x0f, t))), ring = "A", now = now)
        val b = a.copy()
        a.merge(RingEventLog.Frame(4, listOf(ev(0x0a, t + 600))), ring = "A", now = now)
        assertEquals(listOf(ev(0x0f, t)), b.events["A"])
        assertNull(b.overflow["A"])
        assertEquals(2, a.events["A"]?.size)
        assertNotEquals(a, b)
    }

    @Test
    fun ledgerReadsAreFreshAndConstructionDoesNotAlias() {
        val now = ev(0x0f, t).date
        val seed = mutableMapOf("A" to listOf(ev(0x0f, t)))
        val ledger = RingActivityEventLedger(events = seed)
        seed["B"] = listOf(ev(0x0f, t))
        assertEquals(setOf("A"), ledger.events.keys)
        @Suppress("UNCHECKED_CAST")
        (ledger.events as MutableMap<String, List<RingEvent>>).clear()
        @Suppress("UNCHECKED_CAST")
        (ledger.overflow as MutableMap<String, RingActivityEventLedger.Overflow>)["A"] = RingActivityEventLedger.Overflow(9, now)
        assertEquals(setOf("A"), ledger.events.keys)
        assertNull(ledger.overflow["A"])
    }

    /** One ledger driven through many frames, as one app run would, carries no stale state. */
    @Test
    fun oneLedgerAcrossManyFramesKeepsEachRingsStateApart() {
        val now = ev(0x0a, t + 7200).date
        val ledger = RingActivityEventLedger()
        ledger.merge(RingEventLog.Frame(15, listOf(ev(0x0f, t))), ring = "A", now = now)
        ledger.merge(RingEventLog.Frame(0, listOf(ev(0x0f, t + 600), ev(0x0a, t + 1200))), ring = "B", now = now)
        ledger.merge(RingEventLog.Frame(15, listOf(ev(0x0a, t + 3600))), ring = "A", now = now + Duration.ofMinutes(3))
        assertEquals(RingActivityEventLedger.Overflow(15, now), ledger.overflow["A"], "same hidden count: not re-stamped")
        ledger.merge(RingEventLog.Frame(16, emptyList()), ring = "A", now = now + Duration.ofMinutes(6))
        assertEquals(RingActivityEventLedger.Overflow(16, now + Duration.ofMinutes(6)), ledger.overflow["A"], "changed count: re-stamped")
        assertNull(ledger.overflow["B"])
        assertEquals(
            listOf(
                RingEventLog.ActivitySession(ev(0x0f, t).date, ev(0x0a, t + 3600).date),
                RingEventLog.ActivitySession(ev(0x0f, t + 600).date, ev(0x0a, t + 1200).date),
            ),
            ledger.sessions(now),
        )
    }

    @Test
    fun ledgerWindowIncludesBothEdges() {
        val now = Instant.ofEpochSecond(Command.SYNC_EPOCH + t + 100_000)
        fun at(i: Instant) = ev(0x0f, i.epochSecond - Command.SYNC_EPOCH)
        val oldest = at(now - RingActivityEventLedger.RETENTION)
        val tooOld = at(now - RingActivityEventLedger.RETENTION - Duration.ofSeconds(1))
        val latest = at(now + Duration.ofHours(1))
        val tooNew = at(now + Duration.ofHours(1) + Duration.ofSeconds(1))
        val ledger = RingActivityEventLedger()
        ledger.merge(RingEventLog.Frame(0, listOf(tooNew, latest, tooOld, oldest)), ring = "A", now = now)
        assertEquals(listOf(oldest, latest), ledger.events["A"])
    }

    @Test
    fun aSessionStartingAfterNowIsNotOpenedToNow() {
        val start = ev(0x0f, t)
        assertTrue(RingEventLog.activitySessions(listOf(start), now = start.date - Duration.ofSeconds(1)).isEmpty())
        assertEquals(
            listOf(RingEventLog.ActivitySession(start.date, start.date)),
            RingEventLog.activitySessions(listOf(start), now = start.date),
        )
    }

    // --- CapturedFrame: a [UInt8]-built value with a UInt8 opcode ---

    @Test
    fun capturedFrameIsIndependentOfTheCallersArray() {
        val raw = bytes(0xff, 0x00, 0x80)
        val f = CapturedFrame(Instant.EPOCH, raw)
        raw[0] = 0x4c
        raw[2] = 0x01
        assertEquals(0xff, f.opcode, "opcode read unsigned and kept")
        assertEquals("ff 00 80", f.hex)
        assertEquals(3, f.byteCount)
        assertEquals(CapturedFrame(Instant.EPOCH, bytes(0xff, 0x00, 0x80)), f)
        assertEquals(CapturedFrame(Instant.EPOCH, bytes(0xff, 0x00, 0x80)).hashCode(), f.hashCode())
        assertNotEquals(CapturedFrame(Instant.EPOCH.plusSeconds(1), bytes(0xff, 0x00, 0x80)), f)
    }

    @Test
    fun emptyCapturedFrameHasOpcodeZero() {
        val f = CapturedFrame(Instant.EPOCH, ByteArray(0))
        assertEquals(0, f.opcode)
        assertEquals("", f.hex)
        assertEquals(0, f.byteCount)
    }

    // --- HistoryFrameCapture: a mutating struct, here a single-owner class with copy() ---

    @Test
    fun captureCopyAndReadsAreIndependent() {
        val t0 = Instant.parse("2026-06-13T07:00:00Z")
        val seed = mutableListOf(CapturedFrame(t0, bytes(0x4c, 0x01)))
        val a = HistoryFrameCapture(seed)
        seed.clear()
        assertEquals(1, a.count, "construction does not alias the caller's list")
        val b = a.copy()
        a.recordIfRelevant(bytes(0x50, 0x00), at = t0)
        assertEquals(1, b.count)
        assertEquals(2, a.count)
        (a.frames as MutableList<CapturedFrame>).clear()
        assertEquals(2, a.count, "frames is a fresh list per read")
        b.clear()
        assertEquals(2, a.count)
        assertEquals(HistoryFrameCapture(listOf(CapturedFrame(t0, bytes(0x4c, 0x01)))), HistoryFrameCapture(listOf(CapturedFrame(t0, bytes(0x4c, 0x01)))))
    }

    @Test
    fun capKeepsExactlyTheNewest() {
        val frames = (0 until HistoryFrameCapture.CAP).map { CapturedFrame(Instant.ofEpochSecond(it.toLong()), bytes(0x4c)) }
        val cap = HistoryFrameCapture(frames)
        assertEquals(HistoryFrameCapture.CAP, cap.count, "exactly at the cap: nothing dropped")
        assertEquals(Instant.EPOCH, cap.frames.first().date)
        cap.recordIfRelevant(bytes(0x4c), at = Instant.ofEpochSecond(9_999))
        assertEquals(HistoryFrameCapture.CAP, cap.count)
        assertEquals(Instant.ofEpochSecond(1), cap.frames.first().date, "only the single oldest frame dropped")
        assertEquals(Instant.ofEpochSecond(9_999), cap.frames.last().date)
    }

    // --- EpochArchiveDiagnostics: the offset label ---

    private fun recAt(at: Instant): BulkRecord {
        val c = at.epochSecond - Command.SYNC_EPOCH
        val b = ByteArray(23)
        b[0] = (c ushr 24).toByte(); b[1] = (c ushr 16).toByte(); b[2] = (c ushr 8).toByte(); b[3] = c.toByte()
        return BulkRecord.of(b)!!
    }

    private fun spanLine(zone: ZoneId, at: Instant): String =
        EpochArchiveDiagnostics.report(listOf(recAt(at)), zone = zone).lines()[1]

    @Test
    fun offsetLabelTruncatesTowardZeroAndFollowsTheLastEpoch() {
        val jan = Instant.parse("2026-01-15T12:00:00Z")
        assertEquals("Epochs: 1   span: 01-15 12:00 → 01-15 12:00 (UTC+0)", spanLine(ZoneOffset.UTC, jan))
        assertEquals("Epochs: 1   span: 01-15 17:30 → 01-15 17:30 (UTC+5)", spanLine(ZoneOffset.ofHoursMinutes(5, 30), jan))
        assertEquals("Epochs: 1   span: 01-15 08:30 → 01-15 08:30 (UTC-3)", spanLine(ZoneOffset.ofHoursMinutes(-3, -30), jan))
        assertEquals("Epochs: 1   span: 01-15 07:00 → 01-15 07:00 (UTC-5)", spanLine(ZoneId.of("America/New_York"), jan))
        assertEquals(
            "Epochs: 1   span: 07-15 08:00 → 07-15 08:00 (UTC-4)",
            spanLine(ZoneId.of("America/New_York"), Instant.parse("2026-07-15T12:00:00Z")),
        )
    }

    // --- DiagnosticsFrameImport: Swift's `UInt8(_, radix: 16)` reads ASCII hex digits only ---

    @Test
    fun hexBytesAreAsciiOnlyAsSwiftParsesThem() {
        val stamp = "2026-08-04T12:56:16Z"
        assertEquals(
            listOf(0x4c, 0x01, 0xab, 0x0f),
            assertNotNull(DiagnosticsFrameImport.pageBytesFromLine("$stamp  0x4c  4b  4c 01 aB +f")).map { it.toInt() and 0xFF },
        )
        // Fullwidth digits and letters are hex digits to Java's `Character.digit` but not to Swift.
        assertNull(DiagnosticsFrameImport.pageBytesFromLine("$stamp  0x4c  4b  4c \uFF10\uFF11 02 03"))
        assertNull(DiagnosticsFrameImport.pageBytesFromLine("$stamp  0x4c  4b  4c 01 \uFF21\uFF22 03"))
        assertNull(DiagnosticsFrameImport.pageBytesFromLine("$stamp  0x4c  4b  4c 01 02 \u0661\u0662"))

        assertEquals("AD", DiagnosticsFrameImport.sourceRingFromDiagnosticsText("MAC: ··:··:··:··:··:AD").macSuffix)
        assertNull(DiagnosticsFrameImport.sourceRingFromDiagnosticsText("MAC: ··:··:··:··:··:\uFF21\uFF24").macSuffix)
    }
}
