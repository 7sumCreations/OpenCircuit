package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the channel order of one drain pass, the gate that confines a resume hint to the plain
 * foreground plan, and the resume hint's capture / identity / expiry policy.
 * [matchesTheReplacedInlineLogicForEveryInput] re-implements upstream's old inline expression and
 * compares across the whole input space.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/HistoryDrainPlanTests.swift
 * (@ b1c2fdd): all 38 tests. The ring identities of `:295-296` are the same UUID strings, held as
 * `String` (Android identifies a ring by its address string, not a `UUID`).
 */
class HistoryDrainPlanTest {

    private val now = Instant.ofEpochSecond(1_785_030_000)

    private fun labels(steps: List<HistoryDrainPlan.Step>): List<String> = steps.map { it.label }

    private fun plan(
        inBackground: Boolean = false,
        allDayOnly: Boolean = false,
        sportEnabled: Boolean = false,
        nightWindowEnd: Instant? = null,
        resumeHint: HistoryDrainPlan.Step? = null,
    ): List<String> = labels(
        HistoryDrainPlan.steps(
            inBackground = inBackground, allDayOnly = allDayOnly, sportEnabled = sportEnabled,
            now = now, nightWindowEnd = nightWindowEnd, resumeHint = resumeHint,
        ),
    )

    /** A wake instant that puts `now` squarely inside the morning catch-up window. */
    private val inCatchUp: Instant get() = now.minusSeconds(1800)

    private val sleep = HistoryDrainPlan.SLEEP_STEP
    private val allDay = HistoryDrainPlan.ALL_DAY_STEP
    private val sport = HistoryDrainPlan.SPORT_STEP

    // MARK: Order

    // :31
    @Test
    fun foregroundDrainsSleepFirst() {
        assertEquals(listOf("sleep", "all-day"), plan())
    }

    // :35
    @Test
    fun foregroundAppendsSportLastWhenEnabled() {
        assertEquals(listOf("sleep", "all-day", "sport"), plan(sportEnabled = true))
    }

    // :39 — the bounded background window exists to refresh today's vitals.
    @Test
    fun ordinaryBackgroundDrainsAllDayFirst() {
        assertEquals(listOf("all-day", "sleep"), plan(inBackground = true))
    }

    // :44
    @Test
    fun backgroundNeverDrainsSportEvenWhenEnabled() {
        assertEquals(listOf("all-day", "sleep"), plan(inBackground = true, sportEnabled = true))
    }

    // :48 — within the catch-up window after wake, the night's backlog outranks everything.
    @Test
    fun backgroundMorningCatchUpDrainsSleepFirst() {
        assertEquals(listOf("sleep", "all-day"), plan(inBackground = true, nightWindowEnd = now.minusSeconds(3600)))
    }

    // :54 — nightWindow.end is TONIGHT's: sinceWake is negative and must not count.
    @Test
    fun wakeWindowInTheFutureIsNotAMorningCatchUp() {
        assertEquals(listOf("all-day", "sleep"), plan(inBackground = true, nightWindowEnd = now.plusSeconds(3600)))
    }

    // :60
    @Test
    fun wakeLongPastIsNotAMorningCatchUp() {
        assertEquals(listOf("all-day", "sleep"), plan(inBackground = true, nightWindowEnd = now.minusSeconds(9 * 3600)))
    }

    // :65 — the foreground was unconditionally sleep-first.
    @Test
    fun foregroundIsSleepFirstRegardlessOfTheWakeWindow() {
        for (wake in listOf(null, now.minusSeconds(3600), now.plusSeconds(3600), now.minusSeconds(9 * 3600))) {
            assertEquals(listOf("sleep", "all-day"), plan(nightWindowEnd = wake))
        }
    }

    // MARK: the workout prime must never walk the sleep resume pointer

    // :77
    @Test
    fun allDayOnlyTouchesOnlyTheAllDayChannel() {
        for (background in listOf(true, false)) {
            for (sportOn in listOf(true, false)) {
                for (wake in listOf(null, now.minusSeconds(3600))) {
                    assertEquals(
                        listOf("all-day"),
                        plan(inBackground = background, allDayOnly = true, sportEnabled = sportOn, nightWindowEnd = wake),
                        "allDayOnly must never schedule sleep or sport (bg=$background sport=$sportOn)",
                    )
                }
            }
        }
    }

    // MARK: Structural invariants

    // :92
    @Test
    fun everyPlanDrainsBothVitalsChannelsExactlyOnce() {
        for (background in listOf(true, false)) {
            for (sportOn in listOf(true, false)) {
                for (wake in listOf(null, now.minusSeconds(3600), now.plusSeconds(3600))) {
                    val l = plan(inBackground = background, sportEnabled = sportOn, nightWindowEnd = wake)
                    assertEquals(1, l.count { it == "sleep" })
                    assertEquals(1, l.count { it == "all-day" })
                    if (sportOn && !background) {
                        assertEquals("sport", l.last(), "sport must always be drained last")
                    } else {
                        assertFalse(l.contains("sport"))
                    }
                }
            }
        }
    }

    // :109
    @Test
    fun stepsCarryTheCorrectWireChannelSelectors() {
        assertEquals(Command.SYNC_CHANNEL_SLEEP, sleep.channel)
        assertEquals(Command.SYNC_CHANNEL_ALL_DAY, allDay.channel)
        assertEquals(Command.SYNC_CHANNEL_SPORT, sport.channel)
    }

    // MARK: resuming — one-shot resume for a channel cut off by session replacement

    // :117
    @Test
    fun resumingMovesTheHintedStepToTheFront() {
        assertEquals(listOf("all-day", "sleep", "sport"), labels(HistoryDrainPlan.resuming(allDay, listOf(sleep, allDay, sport))))
    }

    // :123
    @Test
    fun resumingIsANoOpWhenTheHintedStepIsAlreadyFirst() {
        assertEquals(listOf("sleep", "all-day"), labels(HistoryDrainPlan.resuming(sleep, listOf(sleep, allDay))))
    }

    // :129 — a stale hint must not inject a channel the prime deliberately excludes.
    @Test
    fun resumingIsANoOpWhenTheHintedStepIsNotInThePlan() {
        val primePlan = listOf(allDay)
        assertEquals(listOf("all-day"), labels(HistoryDrainPlan.resuming(sleep, primePlan)))
        assertEquals(listOf("all-day"), labels(HistoryDrainPlan.resuming(sport, primePlan)))
    }

    // MARK: The resume hint is GATED to the plain foreground plan

    // :149 — positive control: the gate must not be vacuous.
    @Test
    fun resumeHintReordersThePlainForegroundPlan() {
        assertEquals(listOf("all-day", "sleep"), plan(resumeHint = allDay))
        assertEquals(listOf("sport", "sleep", "all-day"), plan(sportEnabled = true, resumeHint = sport))
    }

    // :156
    @Test
    fun resumeHintIsANoOpForTheBackgroundPlan() {
        assertEquals(listOf("all-day", "sleep"), plan(inBackground = true, resumeHint = sleep))
        assertEquals(listOf("all-day", "sleep"), plan(inBackground = true, sportEnabled = true, resumeHint = sleep))
    }

    // :165
    @Test
    fun resumeHintIsANoOpForTheMorningCatchUpPlan() {
        assertEquals(listOf("sleep", "all-day"), plan(inBackground = true, nightWindowEnd = inCatchUp))
        assertEquals(listOf("sleep", "all-day"), plan(inBackground = true, nightWindowEnd = inCatchUp, resumeHint = allDay))
    }

    // :174
    @Test
    fun resumeHintIsANoOpForTheAllDayOnlyPrime() {
        for (hint in listOf(sleep, allDay, sport)) {
            assertEquals(listOf("all-day"), plan(allDayOnly = true, resumeHint = hint), "prime admitted a resume hint: ${hint.label}")
            assertEquals(listOf("all-day"), plan(inBackground = true, allDayOnly = true, resumeHint = hint))
        }
    }

    // :184 — the gate may only ever permute: never add, drop or duplicate a channel.
    @Test
    fun resumeHintNeverChangesWHICHChannelsAPassOpens() {
        val wakes = listOf(null, inCatchUp, now.minusSeconds(99_999), now.plusSeconds(3600))
        val hints = listOf(null, sleep, allDay, sport)
        for (inBackground in listOf(true, false)) {
            for (allDayOnly in listOf(true, false)) {
                for (sportEnabled in listOf(true, false)) {
                    for (wake in wakes) {
                        val baseline = plan(inBackground, allDayOnly, sportEnabled, wake).sorted()
                        for (hint in hints) {
                            val hinted = plan(inBackground, allDayOnly, sportEnabled, wake, hint).sorted()
                            assertEquals(
                                baseline, hinted,
                                "hint changed the channel SET: bg=$inBackground prime=$allDayOnly sport=$sportEnabled hint=${hint?.label ?: "nil"}",
                            )
                        }
                    }
                }
            }
        }
    }

    // :208 — wherever a hint changes anything, inBackground and allDayOnly are both false.
    @Test
    fun onlyThePlainForegroundPlanIsEverReorderedByAHint() {
        val wakes = listOf(null, inCatchUp, now.minusSeconds(99_999))
        for (inBackground in listOf(true, false)) {
            for (allDayOnly in listOf(true, false)) {
                for (sportEnabled in listOf(true, false)) {
                    for (wake in wakes) {
                        val baseline = plan(inBackground, allDayOnly, sportEnabled, wake)
                        for (hint in listOf(sleep, allDay, sport)) {
                            val hinted = plan(inBackground, allDayOnly, sportEnabled, wake, hint)
                            if (hinted != baseline) {
                                assertFalse(inBackground, "a hint reordered a BACKGROUND plan")
                                assertFalse(allDayOnly, "a hint reordered the allDayOnly prime")
                            }
                        }
                    }
                }
            }
        }
    }

    // :234 — the parameter is additive: no hint produces exactly the unhinted plan.
    @Test
    fun noResumeHintIsByteIdenticalToTheUnhintedPlan() {
        val wakes = listOf(null, inCatchUp, now.minusSeconds(99_999))
        for (inBackground in listOf(true, false)) {
            for (allDayOnly in listOf(true, false)) {
                for (sportEnabled in listOf(true, false)) {
                    for (wake in wakes) {
                        val withParam = HistoryDrainPlan.steps(
                            inBackground = inBackground, allDayOnly = allDayOnly, sportEnabled = sportEnabled,
                            now = now, nightWindowEnd = wake, resumeHint = null,
                        )
                        val withoutParam = HistoryDrainPlan.steps(
                            inBackground = inBackground, allDayOnly = allDayOnly, sportEnabled = sportEnabled,
                            now = now, nightWindowEnd = wake,
                        )
                        assertEquals(withoutParam, withParam)
                    }
                }
            }
        }
    }

    // MARK: TeardownReason — WHICH teardowns may capture a resume hint

    // :262
    @Test
    fun onlyGenuineChurnTeardownsCaptureAResumeHint() {
        assertTrue(HistoryDrainPlan.TeardownReason.LINK_DROPPED.capturesResumeHint)
        assertTrue(HistoryDrainPlan.TeardownReason.SESSION_REPLACED.capturesResumeHint)
    }

    // :267 — a deliberate stop must not queue a reorder for a future connect.
    @Test
    fun userDisconnectDoesNotCaptureAResumeHint() {
        assertFalse(HistoryDrainPlan.TeardownReason.USER_DISCONNECTED.capturesResumeHint)
    }

    // :273 — ring A's in-flight channel is not evidence about ring B.
    @Test
    fun ringSwitchDoesNotCaptureAResumeHint() {
        assertFalse(HistoryDrainPlan.TeardownReason.SWITCHING_RING.capturesResumeHint)
    }

    // :279 — the normal end of every bounded background read: nothing was cut off.
    @Test
    fun boundedBackgroundReadEndDoesNotCaptureAResumeHint() {
        assertFalse(HistoryDrainPlan.TeardownReason.BACKGROUND_READ_ENDED.capturesResumeHint)
    }

    // :284 — pins the whole table, so a NEW reason cannot default into capturing.
    @Test
    fun exactlyTwoTeardownReasonsCapture() {
        val capturing = HistoryDrainPlan.TeardownReason.entries.filter { it.capturesResumeHint }
        assertEquals(setOf("linkDropped", "sessionReplaced"), capturing.map { it.rawValue }.toSet())
    }

    // MARK: ResumeHint identity + TTL

    // :295-296
    private val ringA = "00000000-0000-0000-0000-0000000000A1"
    private val ringB = "00000000-0000-0000-0000-0000000000B2"

    private fun hint(step: HistoryDrainPlan.Step = allDay, from: String, at: Instant): HistoryDrainPlan.ResumeHint =
        HistoryDrainPlan.ResumeHint(step = step, peripheralID = from, capturedAt = at)

    // :304
    @Test
    fun aFreshHintFromTheSameRingIsAccepted() {
        val h = hint(from = ringA, at = now)
        assertEquals(allDay, h.step(forPeripheral = ringA, at = now.plusSeconds(5)))
    }

    // :310
    @Test
    fun aHintFromRingAIsRefusedByASessionOnRingB() {
        val h = hint(from = ringA, at = now)
        assertNull(h.step(forPeripheral = ringB, at = now.plusSeconds(1)), "ring A's interrupted channel must never be applied to ring B")
    }

    // :316 — `ttl + 0.001` s is one millisecond past the boundary.
    @Test
    fun anExpiredHintIsRefused() {
        val ttl = HistoryDrainPlan.ResumeHint.TIME_TO_LIVE
        val h = hint(from = ringA, at = now)
        assertNotNull(h.step(forPeripheral = ringA, at = now.plus(ttl)), "the TTL boundary itself is inclusive")
        assertNull(h.step(forPeripheral = ringA, at = now.plus(ttl).plusMillis(1)))
        assertNull(h.step(forPeripheral = ringA, at = now.plusSeconds(86_400)), "a day-old hint must not land on the next connect")
    }

    // :326 — a backwards clock step makes the age untrustworthy.
    @Test
    fun aHintFromTheFutureIsRefused() {
        val h = hint(from = ringA, at = now.plusSeconds(60))
        assertNull(h.step(forPeripheral = ringA, at = now))
    }

    // MARK: afterTeardown — the whole capture/clear/keep policy

    private fun afterTeardown(
        reason: HistoryDrainPlan.TeardownReason,
        inFlight: HistoryDrainPlan.Step? = allDay,
        ring: String? = null,
        standing: HistoryDrainPlan.ResumeHint? = null,
        at: Instant? = null,
    ): HistoryDrainPlan.ResumeHint? = HistoryDrainPlan.ResumeHint.afterTeardown(
        reason, inFlight = inFlight, peripheralID = ring ?: ringA, at = at ?: now, standing = standing,
    )

    // :346
    @Test
    fun aChurnTeardownCapturesTheInFlightChannel() {
        val captured = afterTeardown(HistoryDrainPlan.TeardownReason.LINK_DROPPED)
        assertEquals(allDay, captured?.step)
        assertEquals(ringA, captured?.peripheralID)
        assertEquals(now, captured?.capturedAt)
    }

    // :353 — THE REGRESSION: the ordinary reconnect's second teardown must not erase the first's hint.
    @Test
    fun aCapturingTeardownWithNothingInFlightKEEPSTheStandingHint() {
        val standing = hint(from = ringA, at = now)
        val after = afterTeardown(HistoryDrainPlan.TeardownReason.SESSION_REPLACED, inFlight = null, standing = standing, at = now.plusSeconds(2))
        assertEquals(standing, after, "didConnect's teardown erased didDisconnect's hint")
    }

    // :365
    @Test
    fun aNonChurnTeardownCLEARSAStandingHint() {
        val standing = hint(from = ringA, at = now)
        for (reason in listOf(
            HistoryDrainPlan.TeardownReason.SWITCHING_RING, HistoryDrainPlan.TeardownReason.USER_DISCONNECTED,
            HistoryDrainPlan.TeardownReason.BACKGROUND_READ_ENDED,
        )) {
            assertNull(afterTeardown(reason, inFlight = sleep, standing = standing), "${reason.rawValue} left a hint standing")
            assertNull(afterTeardown(reason, inFlight = null, standing = standing), "${reason.rawValue} left a hint standing")
        }
    }

    // :375
    @Test
    fun aRingSwitchCannotHandRingAsHintToRingB() {
        val standing = hint(from = ringA, at = now)
        val afterSwitch = afterTeardown(HistoryDrainPlan.TeardownReason.SWITCHING_RING, inFlight = allDay, ring = ringA, standing = standing)
        assertNull(afterSwitch)
        assertNull(standing.step(forPeripheral = ringB, at = now))
    }

    // :385 — no targeted ring: capture is impossible, but that is no reason to destroy what we hold.
    @Test
    fun aCapturingTeardownWithNoTargetedRingKeepsTheStandingHint() {
        val standing = hint(from = ringA, at = now)
        assertEquals(
            standing,
            HistoryDrainPlan.ResumeHint.afterTeardown(
                HistoryDrainPlan.TeardownReason.LINK_DROPPED, inFlight = sleep, peripheralID = null, at = now, standing = standing,
            ),
        )
    }

    // :397
    @Test
    fun aFreshCaptureSupersedesAnOlderStandingHint() {
        val stale = hint(sleep, from = ringA, at = now.minusSeconds(300))
        val fresh = afterTeardown(HistoryDrainPlan.TeardownReason.LINK_DROPPED, inFlight = allDay, standing = stale)
        assertEquals(allDay, fresh?.step)
        assertEquals(now, fresh?.capturedAt)
    }

    // :404 — the TTL outlasts the longest single reconnect backoff step and expires well inside a
    // drain cadence. Reads the ported `ReconnectBackoff.DELAYS`, as upstream reads `delays`, so a
    // retuned backoff that outgrows the TTL fails here.
    @Test
    fun theTimeToLiveCoversTheWorstReconnectBackoffWithMargin() {
        val worstBackoff = ReconnectBackoff.DELAYS.max()
        assertTrue(HistoryDrainPlan.ResumeHint.TIME_TO_LIVE > worstBackoff)
        assertTrue(HistoryDrainPlan.ResumeHint.TIME_TO_LIVE < HistoryDrainPlan.DEFAULT_MORNING_CATCH_UP_WINDOW)
    }

    // :416 — PARITY with the exact inline logic this type replaced, over the whole input space.
    @Test
    fun matchesTheReplacedInlineLogicForEveryInput() {
        val window = HistoryDrainPlan.DEFAULT_MORNING_CATCH_UP_WINDOW
        val wakes = listOf(
            null, now.minusSeconds(1), now.minusSeconds(3600), now.minus(window), now.minus(window).minusSeconds(1),
            now, now.plusSeconds(1), now.plusSeconds(3600),
        )
        for (inBackground in listOf(true, false)) {
            for (allDayOnly in listOf(true, false)) {
                for (sportEnabled in listOf(true, false)) {
                    for (wake in wakes) {
                        // --- verbatim transcription of the old inline block ---
                        val morningCatchUp = if (!inBackground || wake == null) {
                            false
                        } else {
                            val sinceWake = Duration.between(wake, now)
                            !sinceWake.isNegative && sinceWake <= window
                        }
                        val sleepFirst = !inBackground || morningCatchUp
                        val expected = mutableListOf<String>()
                        if (sleepFirst && !allDayOnly) expected += "sleep"
                        expected += "all-day"
                        if (!sleepFirst && !allDayOnly) expected += "sleep"
                        if (!inBackground && !allDayOnly && sportEnabled) expected += "sport"
                        // --- end transcription ---

                        assertEquals(
                            expected, plan(inBackground, allDayOnly, sportEnabled, wake),
                            "drift: bg=$inBackground allDayOnly=$allDayOnly sport=$sportEnabled wake=$wake",
                        )
                    }
                }
            }
        }
    }
}
