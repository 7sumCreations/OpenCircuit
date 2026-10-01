package io.github.opencircuit.ringkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The staging decision that decides whether a drained night becomes a sleep summary. Deleting
 * either rule makes a test here fail: drop the `complete` requirement and
 * [partialDrainMayNotStageItsOwnSlice] fails; drop the adopted-record clause and
 * [adoptedOnlyNightIsRescuedFromTheArchive] and [completeDrainWithOnlyAdoptedRecordsStages] fail.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/HistoryCommitGateTests.swift
 * (@ b1c2fdd): all 8 tests.
 */
class HistoryCommitGateTest {

    private fun decide(outcome: HistoryChannelOutcome?, added: Int = 0, adopted: Int = 0): HistoryCommitGate.Decision =
        HistoryCommitGate.decide(outcome = outcome, recordsAdded = added, adoptedRecordCount = adopted)

    // :20 — rule 1, conservative staging.
    @Test
    fun completeDrainWithItsOwnRecordsStages() {
        assertEquals(HistoryCommitGate.Decision.STAGE, decide(HistoryChannelOutcome.COMPLETE, added = 34))
    }

    // :24 — a drain cut mid-stream must never overwrite a fuller stored night.
    @Test
    fun partialDrainMayNotStageItsOwnSlice() {
        for (outcome in listOf(
            HistoryChannelOutcome.PARTIAL, HistoryChannelOutcome.PPG_ONLY, HistoryChannelOutcome.NO_ACK,
            HistoryChannelOutcome.LINK_DOWN, HistoryChannelOutcome.EMPTY, HistoryChannelOutcome.SPORT_ONLY,
        )) {
            assertNotEquals(
                HistoryCommitGate.Decision.STAGE, decide(outcome, added = 174),
                "${outcome.rawValue} must not stage its own slice",
            )
        }
    }

    // :34 — the healthy periodic empty poll: nothing new, nothing adopted → no churn.
    @Test
    fun completeButEmptyDrainDoesNothing() {
        assertEquals(HistoryCommitGate.Decision.SKIP, decide(HistoryChannelOutcome.COMPLETE, added = 0, adopted = 0))
        assertEquals(HistoryCommitGate.Decision.SKIP, decide(HistoryChannelOutcome.EMPTY, added = 0, adopted = 0))
    }

    // :44 — rule 2: adopted records are fresh records; they reached the phone on this connection.
    @Test
    fun completeDrainWithOnlyAdoptedRecordsStages() {
        assertEquals(
            HistoryCommitGate.Decision.STAGE, decide(HistoryChannelOutcome.COMPLETE, added = 0, adopted = 174),
            "adopted records are fresh records — they reached the phone on this connection",
        )
    }

    // :52 — a whole night ACKed outside a drain, then an `.empty` sleep channel.
    @Test
    fun adoptedOnlyNightIsRescuedFromTheArchive() {
        assertEquals(HistoryCommitGate.Decision.RESTAGE_FROM_ARCHIVE, decide(HistoryChannelOutcome.EMPTY, added = 0, adopted = 189))
    }

    // :56
    @Test
    fun adoptedRecordsRescueEveryNonCompleteOutcome() {
        for (outcome in listOf(
            HistoryChannelOutcome.EMPTY, HistoryChannelOutcome.PARTIAL, HistoryChannelOutcome.PPG_ONLY,
            HistoryChannelOutcome.NO_ACK, HistoryChannelOutcome.LINK_DOWN, HistoryChannelOutcome.SPORT_ONLY,
        )) {
            assertEquals(
                HistoryCommitGate.Decision.RESTAGE_FROM_ARCHIVE, decide(outcome, adopted = 189),
                "${outcome.rawValue} still owes the user the night it already ACKed",
            )
        }
    }

    // :66 — no sleep trace at all, but adopted records are self-evidently real.
    @Test
    fun noSleepTraceStillRescuesAdoptedRecords() {
        assertEquals(HistoryCommitGate.Decision.RESTAGE_FROM_ARCHIVE, decide(null, adopted = 189))
        assertEquals(HistoryCommitGate.Decision.SKIP, decide(null, added = 0, adopted = 0))
    }

    // :73 — the rescue is the weaker action and never escalates to a full stage.
    @Test
    fun rescueNeverEscalatesToAFullStage() {
        assertNotEquals(HistoryCommitGate.Decision.STAGE, decide(HistoryChannelOutcome.PARTIAL, added = 174, adopted = 174))
        assertEquals(HistoryCommitGate.Decision.RESTAGE_FROM_ARCHIVE, decide(HistoryChannelOutcome.PARTIAL, added = 174, adopted = 174))
    }
}
