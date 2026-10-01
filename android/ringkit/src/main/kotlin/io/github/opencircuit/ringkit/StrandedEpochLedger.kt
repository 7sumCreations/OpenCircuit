package io.github.opencircuit.ringkit

// Which banked epochs still owe their vitals samples to the store. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/StrandedEpochLedger.swift:23-52 (@ b1c2fdd).
//
// THE PROBLEM. The EpochArchive banks RAW records the moment they are acked, but their
// HR/HRV/SpO2/RR samples only reach the store when a drain COMMITS. A drain that banks and then
// dies leaves the two out of step, and the forward-only `SyncCursor` means nothing ever comes back
// for those epochs. The sync cursor cannot answer "which epochs are unpersisted" — it is a shared
// high-water mark that other writers push past genuinely unpersisted history — so an explicit
// ledger of epoch counters is kept instead. It is exact and order-independent.
//
// Counters are unsigned 32-bit values held in a `Long`; [mark] rejects anything outside
// 0 … 0xFFFFFFFF, the range upstream's `Set<UInt32>` could hold.

object StrandedEpochLedger {

    private const val UINT32_MAX = 0xFFFF_FFFFL

    /** Add newly banked-without-persisting counters. Idempotent. */
    fun mark(ledger: Set<Long>, banked: Iterable<Long>): Set<Long> {
        for (c in banked) require(c in 0..UINT32_MAX) { "epoch counter must be unsigned 32-bit: $c" }
        return ledger + banked
    }

    /**
     * Retire counters that a commit has now run through `persist`.
     *
     * Retire UNCONDITIONALLY, not only when a sample was produced. An idle (unworn/charging)
     * record decodes no HR, HRV or RR, so a yield-based rule would re-select it on every drain
     * forever — and because the archive prunes by AGE while these are the NEWEST records,
     * retention could never clear them either.
     */
    fun retire(ledger: Set<Long>, committed: Iterable<Long>): Set<Long> = ledger - committed.toSet()

    /**
     * The archive records a drain should fold into its own buffer so they ride its single
     * `persist`. [alreadyHeld] excludes records the drain has in hand (e.g. adopted orphans): the
     * archive dedups by counter but the store's ingest has no within-batch dedup, so a duplicate
     * here would insert the same epoch twice.
     */
    fun select(archive: List<BulkRecord>, ledger: Set<Long>, alreadyHeld: Set<Long>): List<BulkRecord> {
        if (ledger.isEmpty()) return emptyList()
        return archive.filter { it.counter in ledger && it.counter !in alreadyHeld }
    }
}
