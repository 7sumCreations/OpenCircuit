package io.github.opencircuit.store.codec

import io.github.opencircuit.ringkit.SleepEdit
import kotlinx.serialization.json.JsonArray

/**
 * The stored form of a night's edit undo stack — the times each edit of the night replaced, the
 * first entry the ring's own window: `[{"inBedStart":<ms>,"sleepOnset":<ms>,"sleepWake":<ms>}, …]`,
 * oldest first.
 *
 * Upstream's `SleepEditPriorTimesOverlay.Snapshot` is synthesized Codable, written with
 * `JSONEncoder` and read with `try? JSONDecoder().decode` (ios/OpenCircuit/Store/LocalStore.swift:
 * 401-418 @ b1c2fdd), so a missing or mistyped time in any entry makes the whole stack unreadable.
 * The dates here are whole epoch milliseconds, as in every stored form of this store (upstream:
 * seconds since 2001).
 */
internal object PriorTimesCodec {

    fun encode(stack: List<SleepEdit.Times>): String = JsonArray(
        stack.map { t ->
            jsonObjectOf(
                "inBedStart" to t.inBedStart.json(),
                "sleepOnset" to t.sleepOnset.json(),
                "sleepWake" to t.sleepWake.json(),
            )
        },
    ).toString()

    fun decode(text: String): Decoded<List<SleepEdit.Times>> = readStored(text) { root ->
        root.array().map { e ->
            val o = e.obj()
            SleepEdit.Times(
                inBedStart = o.required("inBedStart").instant(),
                sleepOnset = o.required("sleepOnset").instant(),
                sleepWake = o.required("sleepWake").instant(),
            )
        }
    }
}
