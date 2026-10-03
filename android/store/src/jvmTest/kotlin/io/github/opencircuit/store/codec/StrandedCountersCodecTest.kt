package io.github.opencircuit.store.codec

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The stored form of the stranded-epoch ledger: counters banked to the archive whose samples have
 * not reached the store yet.
 *
 * Kotlin-only. Upstream stores the `Set<UInt32>` as an array of numbers and reads it with
 * `as? [NSNumber]` then `uint32Value` (ios/OpenCircuit/Store/EpochArchiveStore.swift @ b1c2fdd),
 * which silently wraps a value past 32 bits onto another counter; here such a value, a fraction or
 * a non-number makes the ledger unreadable. The counters are written ascending, so one ledger
 * always has one stored text.
 */
class StrandedCountersCodecTest {

    @Test
    fun theStoredFormIsTheCountersInAscendingOrder() {
        assertEquals("[1,5,4294967295]", StrandedCountersCodec.encode(linkedSetOf(5L, 4_294_967_295L, 1L)))
        assertEquals("[]", StrandedCountersCodec.encode(emptySet()))
    }

    @Test
    fun countersRoundTripAndARepeatReadsOnce() {
        val counters = setOf(0L, 150L, 4_294_967_295L)
        assertEquals(counters, readable(StrandedCountersCodec.decode(StrandedCountersCodec.encode(counters))))
        assertEquals(setOf(7L), readable(StrandedCountersCodec.decode("[7,7]")))
    }

    @Test
    fun aCounterOutsideUnsigned32BitsOrNotANumberMakesTheLedgerUnreadable() {
        for (raw in listOf("[4294967296]", "[-1]", "[1.5]", "[\"7\"]", "[null]", "[true]", "{}", "7")) {
            assertUnreadable(StrandedCountersCodec.decode(raw), raw)
        }
    }
}
