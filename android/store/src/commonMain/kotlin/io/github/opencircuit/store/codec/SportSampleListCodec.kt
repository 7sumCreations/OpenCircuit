package io.github.opencircuit.store.codec

import io.github.opencircuit.ringkit.HistoricalSportFrame
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * The stored form of the buffered 10-second sport records:
 * `[{"auxiliary":[…],"cursor":…,"heartRate":…,"steps":…}, …]`, a missing heart rate left out.
 *
 * Upstream's synthesized Codable on `HistoricalSportFrame.Sample`, read as a list with `try?`
 * (ios/OpenCircuit/BLE/RingSession.swift:924 @ b1c2fdd). `cursor` is an unsigned 32-bit value and
 * `auxiliary` a list of bytes; a heart rate outside the plausible band is kept as stored. A heart
 * rate or step count past 32 bits (Swift's `Int` is 64-bit), or any one bad sample, makes the whole
 * list unreadable.
 */
object SportSampleListCodec {

    fun encode(samples: List<HistoricalSportFrame.Sample>): String = JsonArray(
        samples.map { s ->
            jsonObjectOf(
                "auxiliary" to JsonArray(s.auxiliary.map { JsonPrimitive(it.toInt() and 0xff) }),
                "cursor" to JsonPrimitive(s.cursor),
                "heartRate" to s.heartRate?.let(::JsonPrimitive),
                "steps" to JsonPrimitive(s.steps),
            )
        },
    ).toString()

    fun decode(text: String): Decoded<List<HistoricalSportFrame.Sample>> = readStored(text) { root ->
        root.array().map { e ->
            val o = e.obj()
            val aux = o.required("auxiliary").array().map { it.uInt8() }
            HistoricalSportFrame.Sample(
                cursor = o.required("cursor").uInt32(),
                heartRate = o.optional("heartRate")?.int(),
                steps = o.required("steps").int(),
                auxiliary = ByteArray(aux.size) { aux[it].toByte() },
            )
        }
    }
}
