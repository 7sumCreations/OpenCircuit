package io.github.opencircuit.store.codec

import io.github.opencircuit.ringkit.CursorSpan
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * The stored form of a detected-workout span ledger (the resolved spans, the notified spans):
 * `[{"end":…,"start":…}, …]`, in the ledger's own order.
 *
 * Upstream's synthesized Codable on `CursorSpan`, two `UInt32`s, read as a list with `try?`
 * (ios/OpenCircuit/BLE/RingSession.swift:3168 @ b1c2fdd): a value outside 0…0xFFFFFFFF, or any one
 * bad span, makes the whole ledger unreadable. An end before its start is kept as stored.
 */
object CursorSpanListCodec {

    fun encode(spans: List<CursorSpan>): String = JsonArray(
        spans.map { s -> jsonObjectOf("end" to JsonPrimitive(s.end), "start" to JsonPrimitive(s.start)) },
    ).toString()

    fun decode(text: String): Decoded<List<CursorSpan>> = readStored(text) { root ->
        root.array().map { e ->
            val o = e.obj()
            CursorSpan(start = o.required("start").uInt32(), end = o.required("end").uInt32())
        }
    }
}
