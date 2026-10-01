package io.github.opencircuit.ringkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which banked epochs still owe their vitals samples: select / mark / retire.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/StrandedEpochLedgerTests.swift
 * (@ b1c2fdd), all 8 tests. Counters are unsigned 32-bit values held in a `Long`.
 */
class StrandedEpochLedgerTest {

    /** :6-14 — a record with a given counter; the other 19 bytes are irrelevant to selection. */
    private fun record(counter: Long, fill: Int = 0x0a): BulkRecord {
        val b = ByteArray(BulkRecord.LENGTH) { fill.toByte() }
        b[0] = ((counter ushr 24) and 0xFF).toByte()
        b[1] = ((counter ushr 16) and 0xFF).toByte()
        b[2] = ((counter ushr 8) and 0xFF).toByte()
        b[3] = (counter and 0xFF).toByte()
        return BulkRecord.of(b)!!
    }

    private fun run(n: Int, from: Long) = (0 until n).map { record(from + it * 150L) }

    // :19 — the healthy ring: nothing owed, so a drain folds in nothing.
    @Test
    fun emptyLedgerSelectsNothing() {
        val archive = run(5, 1000)
        assertTrue(StrandedEpochLedger.select(archive = archive, ledger = emptySet(), alreadyHeld = emptySet()).isEmpty())
    }

    // :24
    @Test
    fun selectsOnlyLedgeredRecords() {
        val archive = run(5, 1000)
        val selected = StrandedEpochLedger.select(archive = archive, ledger = setOf(1000L, 1300L), alreadyHeld = emptySet())
        assertEquals(listOf(1000L, 1300L), selected.map { it.counter })
    }

    // :32 — a record the drain already holds must never be folded in a second time.
    @Test
    fun excludesRecordsTheDrainAlreadyHolds() {
        val archive = run(5, 1000)
        val selected = StrandedEpochLedger.select(
            archive = archive,
            ledger = setOf(1000L, 1150L, 1300L),
            alreadyHeld = setOf(1150L),
        )
        assertEquals(listOf(1000L, 1300L), selected.map { it.counter })
    }

    // :43 — a ledger entry the archive no longer holds is simply not selected.
    @Test
    fun ledgerEntryMissingFromArchiveIsIgnored() {
        val archive = listOf(record(1000))
        val selected = StrandedEpochLedger.select(archive = archive, ledger = setOf(1000L, 999_999L), alreadyHeld = emptySet())
        assertEquals(listOf(1000L), selected.map { it.counter })
    }

    // :51
    @Test
    fun markIsIdempotent() {
        var ledger = StrandedEpochLedger.mark(ledger = emptySet(), banked = listOf(1000L, 1150L))
        ledger = StrandedEpochLedger.mark(ledger = ledger, banked = listOf(1150L, 1300L))
        assertEquals(setOf(1000L, 1150L, 1300L), ledger)
    }

    // :61 — THE idle trap: a committed epoch retires even when it produced no samples.
    @Test
    fun retireIsUnconditionalSoSampleLessEpochsCannotLoopForever() {
        val ledger = setOf(1000L, 1150L, 1300L)
        val after = StrandedEpochLedger.retire(ledger = ledger, committed = listOf(1000L, 1150L, 1300L))
        assertTrue(after.isEmpty(), "a committed epoch must retire even when it produced no samples")
    }

    // :68
    @Test
    fun retireLeavesUncommittedEntriesStanding() {
        val after = StrandedEpochLedger.retire(ledger = setOf(1000L, 1150L, 1300L), committed = listOf(1000L))
        assertEquals(setOf(1150L, 1300L), after)
    }

    // :75 — bank → select → commit → retire leaves nothing owed; a second drain re-selects nothing.
    @Test
    fun bankSelectCommitRetireConverges() {
        val archive = run(4, 2000)
        var ledger = StrandedEpochLedger.mark(ledger = emptySet(), banked = archive.map { it.counter })

        val firstPass = StrandedEpochLedger.select(archive = archive, ledger = ledger, alreadyHeld = emptySet())
        assertEquals(4, firstPass.size, "a stranded night must be offered to the next drain")

        ledger = StrandedEpochLedger.retire(ledger = ledger, committed = firstPass.map { it.counter })
        assertTrue(ledger.isEmpty())

        val secondPass = StrandedEpochLedger.select(archive = archive, ledger = ledger, alreadyHeld = emptySet())
        assertTrue(secondPass.isEmpty(), "re-hydration must not repeat once committed")
    }
}
