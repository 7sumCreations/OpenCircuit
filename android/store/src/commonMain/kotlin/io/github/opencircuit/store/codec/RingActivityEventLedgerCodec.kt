package io.github.opencircuit.store.codec

import io.github.opencircuit.ringkit.RingActivityEventLedger
import io.github.opencircuit.ringkit.RingEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The stored form of the activity-event ledger:
 * `{"events":{"<ring>":[{"cursor":…,"type":…,"value":…}]},"overflow":{"<ring>":{"hidden":…,"seenAt":<ms>}}}`,
 * rings in ascending order so one ledger always has one stored text.
 *
 * Upstream's synthesized Codable on `RingActivityEventLedger` and `RingEvent`. Both keys are
 * required; an event's `type` and `value` are bytes and its `cursor` an unsigned 32-bit value; a
 * `hidden` count past 32 bits, or any one bad event, makes the whole ledger unreadable.
 */
object RingActivityEventLedgerCodec {

    fun encode(ledger: RingActivityEventLedger): String = jsonObjectOf(
        "events" to JsonObject(
            ledger.events.toSortedMap().mapValues { (_, events) ->
                JsonArray(
                    events.map { e ->
                        jsonObjectOf("cursor" to JsonPrimitive(e.cursor), "type" to JsonPrimitive(e.type), "value" to JsonPrimitive(e.value))
                    },
                )
            },
        ),
        "overflow" to JsonObject(
            ledger.overflow.toSortedMap().mapValues { (_, o) ->
                jsonObjectOf("hidden" to JsonPrimitive(o.hidden), "seenAt" to o.seenAt.json())
            },
        ),
    ).toString()

    fun decode(text: String): Decoded<RingActivityEventLedger> = readStored(text) { root ->
        val o = root.obj()
        val events = o.required("events").obj().mapValues { (_, list) ->
            list.array().map { e ->
                val ev = e.obj()
                RingEvent(type = ev.required("type").uInt8(), value = ev.required("value").uInt8(), cursor = ev.required("cursor").uInt32())
            }
        }
        val overflow = o.required("overflow").obj().mapValues { (_, v) ->
            val ov = v.obj()
            RingActivityEventLedger.Overflow(hidden = ov.required("hidden").int(), seenAt = ov.required("seenAt").instant())
        }
        RingActivityEventLedger(events, overflow)
    }
}
