package io.github.opencircuit.store.codec

import io.github.opencircuit.store.MirroredNight
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * The stored form of the Health Connect record ids written for a night: `["oc:sleep:…", …]`.
 *
 * Upstream keeps the night's HealthKit sample UUIDs as a `UserDefaults` string array
 * (ios/OpenCircuit/Store/LocalStore.swift:486-520 @ b1c2fdd); here the same key holds our own
 * clientRecordIds. One element that is not a string makes the whole list unreadable.
 */
internal object RecordIdListCodec {
    fun encode(ids: List<String>): String = JsonArray(ids.map { JsonPrimitive(it) }).toString()

    fun decode(text: String): Decoded<List<String>> = readStored(text) { root -> root.array().map { it.string() } }
}

/**
 * The stored form of what was last written to Health Connect for an unedited night:
 * `{"signature":"<hex>","spanStart":<ms>,"spanEnd":<ms>}`.
 *
 * Upstream's `MirroredNightRecord` is synthesized Codable (LocalStore.swift:530-562 @ b1c2fdd), so a
 * missing or mistyped field makes it unreadable; the dates here are whole epoch milliseconds.
 */
internal object MirroredNightCodec {
    fun encode(record: MirroredNight): String = jsonObjectOf(
        "signature" to JsonPrimitive(record.signature),
        "spanStart" to record.spanStart.json(),
        "spanEnd" to record.spanEnd.json(),
    ).toString()

    fun decode(text: String): Decoded<MirroredNight> = readStored(text) { root ->
        val o = root.obj()
        MirroredNight(
            signature = o.required("signature").string(),
            spanStart = o.required("spanStart").instant(),
            spanEnd = o.required("spanEnd").instant(),
        )
    }
}
