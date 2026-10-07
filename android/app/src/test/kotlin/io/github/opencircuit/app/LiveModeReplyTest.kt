package io.github.opencircuit.app

import io.github.opencircuit.app.live.LiveMode
import io.github.opencircuit.app.live.MeasureEvidence
import io.github.opencircuit.app.live.MeasureFailure
import io.github.opencircuit.app.session.RingSessionController
import io.github.opencircuit.ble.LinkState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ring answers every `06 xx 00` mode write with `86 <status> <xor>` (PROTOCOL.md §4). On a real
 * Gen 2 a first-session heart-rate measure gave no reading while SpO₂ worked, and the app had
 * dropped every `0x86` answer unread, so nobody could tell whether the ring had refused the mode.
 *
 * Now the answer is read: a refusal (`86 fd 7b`, upstream's "not ready") puts the ring back to idle
 * with `06 00 00`, waits 1 s and writes the entry once more (upstream's way into a mode the ring
 * would not switch to directly: `ios/OpenCircuit/BLE/RingSession.swift:2614-2621` @ b1c2fdd). A
 * second refusal ends the measure with its own reason. A "no reading" carries what the ring sent.
 */
class LiveModeReplyTest {

    private fun TestScope.authenticatedSession(link: TimedRingLink): RingSessionController {
        val session = RingSessionController(link, backgroundScope, monotonicMillis = { testScheduler.currentTime }, log = {})
        session.connect()
        link.fake.setState(LinkState.Authenticated)
        advanceTo(START)
        link.clear() // the keepalive's first d0 00 00
        return session
    }

    /** Answers the mode writes from [modeAnswers] in turn, and `06 00 00` with an accept. */
    private fun TimedRingLink.answerModes(vararg modeAnswers: String) {
        val queue = ArrayDeque(modeAnswers.toList())
        replyTo = { written ->
            when (written) {
                Wire.MODE_EXIT -> ModeReplies.ACCEPTED
                Wire.HR_MODE, Wire.SPO2_MODE -> queue.removeFirstOrNull()
                else -> null
            }
        }
    }

    @Test
    fun aRefusedHeartRateModeIsResetToIdleAndTheEntryIsWrittenOnceMore() = runTest {
        val link = timedLink()
        link.answerModes(ModeReplies.REFUSED, ModeReplies.ACCEPTED)
        val session = authenticatedSession(link)

        session.liveMeasure.start(LiveMode.HEART_RATE)
        advanceTo(START + 4_000)

        assertEquals(
            listOf(
                TimedWrite(START, Wire.STATUS_QUERY),
                TimedWrite(START + 250, Wire.HR_MODE),
                TimedWrite(START + 250, Wire.MODE_EXIT),
                TimedWrite(START + 1_250, Wire.STATUS_QUERY),
                TimedWrite(START + 1_500, Wire.HR_MODE),
                TimedWrite(START + 1_750, Wire.FETCH),
                TimedWrite(START + 4_000, Wire.POLL),
            ),
            link.writes,
        )
        TestFrames.realHeartRateRead.forEach(link.fake::emitFrame)
        testScheduler.runCurrent()
        val state = session.liveMeasure.state.value
        assertTrue(session.liveMeasure.isMeasuring.value)
        assertTrue(state.settledHeartRate != null, "the re-entered mode measures")
        assertEquals(listOf(0xfd, 0x00), state.evidence?.modeReplies)
        assertEquals(1, state.evidence?.resets)
        assertNull(session.dispatcher.counts.value.unhandled[0x86], "0x86 is handled now")
    }

    @Test
    fun aSecondRefusalEndsTheHeartRateMeasureWithItsOwnReason() = runTest {
        val link = timedLink()
        link.answerModes(ModeReplies.REFUSED, ModeReplies.REFUSED)
        val session = authenticatedSession(link)

        session.liveMeasure.start(LiveMode.HEART_RATE)
        advanceTo(START + 1_500)

        assertFalse(session.liveMeasure.isMeasuring.value)
        assertEquals(MeasureFailure.ModeRejected(LiveMode.HEART_RATE, 0xfd), session.liveMeasure.state.value.heartRate.failure)
        advanceTo(START + 10_000)
        // After these, only the keepalive's status refresh (`d0 00 00`) follows a measure's end.
        assertEquals(
            listOf(Wire.STATUS_QUERY, Wire.HR_MODE, Wire.MODE_EXIT, Wire.STATUS_QUERY, Wire.HR_MODE),
            link.writes.take(5).map { it.hex },
        )
        assertEquals(setOf(Wire.STATUS_QUERY), link.writes.drop(5).map { it.hex }.toSet())
        assertTrue(link.writes.none { it.hex == Wire.FETCH || it.hex == Wire.POLL }, "nothing of the entry after the second refusal")
        assertEquals(1, link.timesOf(Wire.MODE_EXIT).size, "one reset only")
    }

    @Test
    fun spo2IsHandledTheSameWay() = runTest {
        val link = timedLink()
        link.answerModes(ModeReplies.REFUSED, ModeReplies.REFUSED)
        val session = authenticatedSession(link)

        session.liveMeasure.start(LiveMode.SPO2)
        advanceTo(START + 1_500)

        assertEquals(listOf(START + 250L), link.timesOf(Wire.MODE_EXIT))
        assertEquals(MeasureFailure.ModeRejected(LiveMode.SPO2, 0xfd), session.liveMeasure.state.value.spo2.failure)
        assertFalse(session.liveMeasure.isMeasuring.value)
    }

    @Test
    fun anAcceptedModeIsRecordedAndNeverResets() = acceptedWithoutReset(ModeReplies.ACCEPTED)

    @Test
    fun alreadyInTheModeCountsAsAccepted() = acceptedWithoutReset(ModeReplies.ALREADY)

    private fun acceptedWithoutReset(answer: String) = runTest {
        val link = timedLink()
        link.answerModes(answer)
        val session = authenticatedSession(link)

        session.liveMeasure.start(LiveMode.HEART_RATE)
        advanceTo(START + 10_000)

        assertTrue(link.writes.none { it.hex == Wire.MODE_EXIT }, "no reset after $answer")
        assertEquals(listOf(answer.substring(2, 4).toInt(16)), session.liveMeasure.state.value.evidence?.modeReplies)
        assertTrue(session.liveMeasure.isMeasuring.value)
    }

    @Test
    fun noReadingCarriesWhatTheRingSent() = runTest {
        val link = timedLink()
        link.answerModes(ModeReplies.ACCEPTED)
        val session = authenticatedSession(link)

        session.liveMeasure.start(LiveMode.HEART_RATE)
        advanceTo(START + 5_000)
        repeat(10) { link.fake.emitFrame(TestFrames.heartRateWarmUp) }
        advanceTo(START + 750 + 90_000)

        val expected = MeasureEvidence(LiveMode.HEART_RATE, liveFrames = 10, unusableFrames = 10, modeReplies = listOf(0x00), resets = 0)
        assertEquals(MeasureFailure.NoReading(expected), session.liveMeasure.state.value.heartRate.failure)
        assertEquals(expected, session.liveMeasure.state.value.evidence, "kept after the measure for Connection details")
    }

    @Test
    fun aModeAnswerWithNoMeasureRunningChangesNothing() = runTest {
        val link = timedLink()
        val session = authenticatedSession(link)

        link.fake.emitFrame(hex(ModeReplies.REFUSED))
        advanceTo(START + 5_000)

        assertTrue(link.writes.none { it.hex == Wire.MODE_EXIT })
        assertFalse(session.liveMeasure.isMeasuring.value)
        assertNull(session.dispatcher.counts.value.unhandled[0x86])
    }

    @Test
    fun theRingsAnswerToTheAuthReplyIsNotCountedAsUnhandled() = runTest {
        val link = timedLink()
        val session = authenticatedSession(link)

        // `81 01 …`: the ring's answer to the link's `01 01 …` auth reply (PROTOCOL.md §5.7);
        // the link answers `81 00` challenges itself and passes the rest on.
        link.fake.emitFrame(hex("8101aabbcc"))
        testScheduler.runCurrent()

        assertNull(session.dispatcher.counts.value.unhandled[0x81])
    }

    private companion object {
        const val START = 1_000L
    }
}
