package io.github.opencircuit.store.codec

import io.github.opencircuit.ringkit.BatteryTTE
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * The stored form of a battery history (the discharge and charge histories of each ring):
 * `[{"at":<ms>,"percent":80}, …]`.
 *
 * Upstream's synthesized Codable on `BatteryTTE.Sample`. A percent outside 0–100 is read as stored
 * (the estimator skips it); a fractional percent, one past 32 bits, or any one bad element makes
 * the whole history unreadable.
 */
object BatterySamplesCodec {

    fun encode(samples: List<BatteryTTE.Sample>): String = JsonArray(
        samples.map { s -> jsonObjectOf("at" to s.at.json(), "percent" to JsonPrimitive(s.percent)) },
    ).toString()

    fun decode(text: String): Decoded<List<BatteryTTE.Sample>> = readStored(text) { root ->
        root.array().map { e ->
            val o = e.obj()
            BatteryTTE.Sample(percent = o.required("percent").int(), at = o.required("at").instant())
        }
    }
}
