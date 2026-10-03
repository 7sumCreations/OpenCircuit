package io.github.opencircuit.store.codec

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * The stored form of the stranded-epoch ledger (counters banked to the archive whose samples have
 * not reached the store yet): `[<counter>, …]`, ascending, so one ledger always has one stored text.
 *
 * Upstream stores a `Set<UInt32>` as an array of numbers and reads each with `uint32Value`
 * (ios/OpenCircuit/Store/EpochArchiveStore.swift @ b1c2fdd), which wraps a value past 32 bits onto
 * a different counter. Here a counter outside 0…0xFFFFFFFF, a fraction or a non-number makes the
 * ledger unreadable. A repeated counter reads once.
 */
object StrandedCountersCodec {

    fun encode(counters: Set<Long>): String = JsonArray(counters.sorted().map(::JsonPrimitive)).toString()

    fun decode(text: String): Decoded<Set<Long>> = readStored(text) { root ->
        root.array().mapTo(LinkedHashSet()) { it.uInt32() }
    }
}
