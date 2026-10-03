package io.github.opencircuit.store.codec

import io.github.opencircuit.store.PendingSleepReconcile
import kotlinx.serialization.json.JsonArray

/**
 * The stored form of the queue of sleep edits waiting to reach Health:
 * `[{"night":<ms>,"inBedStart":<ms>,"sleepOnset":<ms>,"sleepWake":<ms>,"segments":[…]}, …]`, the
 * segments in [SleepSegmentCodec]'s form, oldest item first.
 *
 * Upstream's `PendingSleepReconcile` is synthesized Codable read with `try? JSONDecoder().decode`
 * (ios/OpenCircuit/Store/LocalStore.swift:566-572, :596-601 @ b1c2fdd), so one bad item or segment
 * makes the whole queue unreadable. The dates are whole epoch milliseconds, as in every stored form
 * of this store (upstream: seconds since 2001).
 */
internal object PendingSleepReconcileCodec {

    fun encode(queue: List<PendingSleepReconcile>): String = JsonArray(
        queue.map { item ->
            jsonObjectOf(
                "night" to item.night.json(),
                "inBedStart" to item.inBedStart.json(),
                "sleepOnset" to item.sleepOnset.json(),
                "sleepWake" to item.sleepWake.json(),
                "segments" to SleepSegmentCodec.toJson(item.segments),
            )
        },
    ).toString()

    fun decode(text: String): Decoded<List<PendingSleepReconcile>> = readStored(text) { root ->
        root.array().map { e ->
            val o = e.obj()
            PendingSleepReconcile(
                night = o.required("night").instant(),
                inBedStart = o.required("inBedStart").instant(),
                sleepOnset = o.required("sleepOnset").instant(),
                sleepWake = o.required("sleepWake").instant(),
                segments = SleepSegmentCodec.fromJson(o.required("segments")),
            )
        }
    }
}
