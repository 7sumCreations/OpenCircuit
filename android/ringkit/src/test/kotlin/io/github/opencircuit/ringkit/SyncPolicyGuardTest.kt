package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kotlin-only guarantees for the sync-policy files: what Swift's value types and `UInt8`s gave
 * upstream for free, every tuned constant pinned to the literal typed from upstream's source (never
 * read back from the Kotlin constant), the closed ends of the night-record window, and the
 * overnight-quiet gate driven through its real inputs rather than as a bare boolean.
 */
class SyncPolicyGuardTest {

    private val t0 = Instant.parse("2026-08-04T04:15:00Z")

    // MARK: HistoryChannelTrace — a struct upstream, a single-owner class here

    @Test
    fun aCopiedTraceNeverMovesWithTheOriginal() {
        val a = HistoryChannelTrace("sleep", 0x00, t0)
        a.page4CCount = 2
        a.firstOpcode = 0x82
        val b = a.copy()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())

        b.page4CCount = 5
        b.endMarkerCount = 1
        b.exitReason = HistoryChannelExitReason.END_MARKER
        assertEquals(2, a.page4CCount)
        assertEquals(0, a.endMarkerCount)
        assertNull(a.exitReason)
        assertNotEquals(a, b)
    }

    @Test
    fun twoTracesCompareByEveryField() {
        fun make() = HistoryChannelTrace("all-day", 0x03, t0).also { it.sawSyncAck = true; it.page4DCount = null }
        assertEquals(make(), make())
        assertNotEquals(make(), make().also { it.fetchNudges = 1 })
        assertNotEquals(make(), make().also { it.lastOpcode = 0x50 })
        assertNotEquals(make(), HistoryChannelTrace("all-day", 0x03, t0.plusSeconds(1)).also { it.sawSyncAck = true; it.page4DCount = null })
    }

    @Test
    fun traceByteFieldsKeepTheirUnsignedRange() {
        for (bad in listOf(-1, 0x100)) {
            assertFailsWith<IllegalArgumentException> { HistoryChannelTrace("sleep", bad, t0) }
            val t = HistoryChannelTrace("sleep", 0x00, t0)
            assertFailsWith<IllegalArgumentException> { t.syncAckFlag = bad }
            assertFailsWith<IllegalArgumentException> { t.firstOpcode = bad }
            assertFailsWith<IllegalArgumentException> { t.lastOpcode = bad }
        }
        val t = HistoryChannelTrace("sleep", 0xFF, t0)
        t.syncAckFlag = 0xFF
        t.firstOpcode = 0x00
        t.lastOpcode = 0xFF
        assertEquals(0xFF, t.channel)
        assertEquals(0xFF, t.syncAckFlag)
        t.firstOpcode = null
        assertNull(t.firstOpcode)
    }

    @Test
    fun durationIsUnknownUntilTheChannelFinishesAndRecordsAddedNeverGoesNegative() {
        val t = HistoryChannelTrace("sleep", 0x00, t0)
        assertNull(t.duration)
        t.finishedAt = t0.plusMillis(45_500)
        assertEquals(Duration.ofMillis(45_500), t.duration)

        t.recordsAtStart = 10
        t.recordsAtEnd = 4
        assertEquals(0, t.recordsAdded)
    }

    // MARK: HistoryDrainPlan value types

    @Test
    fun stepsAndHintsCompareByValueAndKeepTheChannelByteRange() {
        assertEquals(HistoryDrainPlan.Step(0x03, "all-day"), HistoryDrainPlan.ALL_DAY_STEP)
        assertFailsWith<IllegalArgumentException> { HistoryDrainPlan.Step(0x100, "x") }
        assertFailsWith<IllegalArgumentException> { HistoryDrainPlan.Step(-1, "x") }

        val a = HistoryDrainPlan.ResumeHint(HistoryDrainPlan.SLEEP_STEP, "F8:79:99:F7:03:AD", t0)
        assertEquals(a, HistoryDrainPlan.ResumeHint(HistoryDrainPlan.Step(0x00, "sleep"), "F8:79:99:F7:03:AD", t0))
        assertNull(a.step(forPeripheral = "f8:79:99:f7:03:ad", at = t0), "ring identity is an exact match")
    }

    @Test
    fun resumingNeverChangesTheCallersPlan() {
        val plan = mutableListOf(HistoryDrainPlan.SLEEP_STEP, HistoryDrainPlan.ALL_DAY_STEP)
        val resumed = HistoryDrainPlan.resuming(HistoryDrainPlan.ALL_DAY_STEP, plan)
        assertEquals(listOf(HistoryDrainPlan.ALL_DAY_STEP, HistoryDrainPlan.SLEEP_STEP), resumed)
        assertEquals(listOf(HistoryDrainPlan.SLEEP_STEP, HistoryDrainPlan.ALL_DAY_STEP), plan)
    }

    @Test
    fun theMorningCatchUpWindowIncludesBothEnds() {
        val now = Instant.ofEpochSecond(1_785_030_000)
        fun first(wake: Instant) =
            HistoryDrainPlan.steps(inBackground = true, allDayOnly = false, sportEnabled = false, now = now, nightWindowEnd = wake).first().label
        assertEquals("sleep", first(now), "the wake instant itself")
        assertEquals("sleep", first(now.minusSeconds(3 * 3600)), "exactly the window after wake")
        assertEquals("all-day", first(now.minusSeconds(3 * 3600).minusNanos(1)))
        assertEquals("all-day", first(now.plusNanos(1)))
    }

    // MARK: HistoryCommitGate.isNightRecord — upstream's explicit >= / <= are closed at both ends

    @Test
    fun aRecordExactlyOnTheNightWindowsEndsCountsAsNight() {
        val start = Instant.parse("2026-09-28T02:30:00Z")
        val end = Instant.parse("2026-09-28T13:30:00Z")
        val window = DateInterval(start, end)
        val margin = Duration.ofHours(2)

        assertTrue(HistoryCommitGate.isNightRecord(start, window), "exactly the window's start")
        assertTrue(HistoryCommitGate.isNightRecord(end.plus(margin), window), "exactly the end plus the late margin")
        assertFalse(HistoryCommitGate.isNightRecord(end.plus(margin).plusNanos(1), window))
        assertTrue(HistoryCommitGate.isNightRecord(start.minusSeconds(86_400), window), "the day-earlier window's start")
        assertTrue(HistoryCommitGate.isNightRecord(end.minusSeconds(86_400).plus(margin), window), "the day-earlier window's end plus margin")
        assertFalse(HistoryCommitGate.isNightRecord(start.minusNanos(1), window), "between yesterday's margin and tonight's start")

        assertTrue(HistoryCommitGate.isNightRecord(end, window, lateMargin = Duration.ZERO), "a zero margin still includes the end")
        assertFalse(HistoryCommitGate.isNightRecord(end.plusNanos(1), window, lateMargin = Duration.ZERO))
    }

    // MARK: every tuned constant, pinned to the literal typed from upstream's source

    @Test
    fun everySyncPolicyConstantMatchesUpstreamsLiteral() {
        assertEquals(Duration.ofSeconds(7200), HistoryCommitGate.OFF_CHANNEL_NIGHT_LATE_MARGIN)      // S/HistoryCommitGate.swift:64  2 * 3600
        assertEquals(3, DrainBudget.DEFAULT_QUIET_EXIT_THRESHOLD)                                   // S/DrainBudget.swift:42
        assertEquals(Duration.ofSeconds(4), DrainBankCadence.QUIET)                                 // S/DrainBankCadence.swift:38
        assertEquals(Duration.ofSeconds(8), DrainBankCadence.MAX_HOLD)                              // S/DrainBankCadence.swift:46
        assertEquals(1, DrainContinuation.MAX_NUDGES_WITHOUT_PROGRESS)                              // S/DrainContinuation.swift:31
        assertEquals(2, DrainContinuation.MAX_NUDGES_PER_ROUND)                                     // :36
        assertEquals(8, DrainContinuation.NUDGE_HEADROOM_TICKS)                                     // :40
        assertEquals(12, DrainContinuation.MAX_REOPEN_ROUNDS_FOREGROUND)                            // :47
        assertEquals(2, DrainContinuation.MAX_REOPEN_ROUNDS_BACKGROUND)                             // :48
        assertEquals(Duration.ofSeconds(15), DrainContinuation.MIN_BACKGROUND_TIME_FOR_REOPEN)      // :49
        assertEquals(Duration.ofSeconds(30 * 60), HistoryDrainCadence.interval(isNight = true, batterySaver = false))   // S/HistoryDrainCadence.swift:63
        assertEquals(Duration.ofSeconds(45 * 60), HistoryDrainCadence.interval(isNight = true, batterySaver = true))    // :63
        assertEquals(Duration.ofSeconds(60 * 60), HistoryDrainCadence.interval(isNight = false, batterySaver = false))  // :64
        assertEquals(Duration.ofSeconds(180 * 60), HistoryDrainCadence.interval(isNight = false, batterySaver = true))  // :64
        assertEquals(Duration.ofSeconds(3 * 3600), HistoryDrainPlan.DEFAULT_MORNING_CATCH_UP_WINDOW) // S/HistoryDrainPlan.swift:67
        assertEquals(Duration.ofSeconds(120), HistoryDrainPlan.ResumeHint.TIME_TO_LIVE)             // :248
        assertEquals(0x00, HistoryDrainPlan.SLEEP_STEP.channel)                                     // S/Opcodes.swift syncChannelSleep
        assertEquals(0x03, HistoryDrainPlan.ALL_DAY_STEP.channel)                                   // syncChannelAllDay
        assertEquals(0x02, HistoryDrainPlan.SPORT_STEP.channel)                                     // syncChannelSport
    }

    // MARK: the overnight-quiet gate, driven through its trigger inputs

    /**
     * A connected ring ticked every 15 min from 18:00 to 12:00 New York time, with the automatic
     * cadence fed its real inputs: `lastDrainAt` from the previous drain, `isNight` and
     * `inSleepWindow` from a 22:30–06:30 window. No automatic drain may run inside the window; the
     * first tick after it drains at once (the morning catch-up); a manual sync mid-window drains.
     */
    @Test
    fun theCadenceDrainsNothingOvernightAndCatchesUpAtWake() {
        val newYork = ZoneId.of("America/New_York")
        fun at(d: Int, h: Int, m: Int = 0): Instant = ZonedDateTime.of(2026, 9, d, h, m, 0, 0, newYork).toInstant()
        val sleepStart = at(27, 22, 30)
        val wake = at(28, 6, 30)
        fun inSleepWindow(t: Instant) = !t.isBefore(sleepStart) && t.isBefore(wake)

        var lastDrainAt: Instant? = at(27, 18, 0)
        val drains = mutableListOf<Instant>()
        var t = at(27, 18, 15)
        while (!t.isAfter(at(28, 12, 0))) {
            val night = inSleepWindow(t)
            val due = HistoryDrainCadence.isDue(lastDrainAt = lastDrainAt, now = t, isNight = night, batterySaver = false)
            if (HistoryDrainCadence.shouldDrain(manual = false, inSleepWindow = night, isDue = due)) {
                drains += t
                lastDrainAt = t
            }
            t = t.plus(Duration.ofMinutes(15))
        }

        assertTrue(drains.none { inSleepWindow(it) }, "an automatic drain ran inside the sleep window: $drains")
        assertEquals(listOf(at(27, 19, 0), at(27, 20, 0), at(27, 21, 0), at(27, 22, 0)), drains.filter { it.isBefore(sleepStart) })
        assertEquals(wake, drains.first { !it.isBefore(sleepStart) }, "the first tick after the window is the morning catch-up")
        assertEquals(listOf(wake, at(28, 7, 30), at(28, 8, 30), at(28, 9, 30), at(28, 10, 30), at(28, 11, 30)), drains.filter { !it.isBefore(wake) })

        // Mid-window, overdue by hours: automatic stays quiet, a manual sync drains.
        val threeAm = at(28, 3, 0)
        val overdue = HistoryDrainCadence.isDue(lastDrainAt = at(27, 22, 0), now = threeAm, isNight = true, batterySaver = false)
        assertTrue(overdue)
        assertFalse(HistoryDrainCadence.shouldDrain(manual = false, inSleepWindow = inSleepWindow(threeAm), isDue = overdue))
        assertTrue(HistoryDrainCadence.shouldDrain(manual = true, inSleepWindow = inSleepWindow(threeAm), isDue = overdue))
    }
}
