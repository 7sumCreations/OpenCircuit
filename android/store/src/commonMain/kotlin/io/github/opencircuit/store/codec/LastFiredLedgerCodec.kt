package io.github.opencircuit.store.codec

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/**
 * The stored form of a last-fired ledger, keyed by a raw name (a `SyncAlert` or a
 * `HealthNotification`): `{"<raw>":<epoch ms>, …}`, or for the per-night ledger
 * `{"<raw>":<night key>, …}`, names in ascending order.
 *
 * Upstream keeps these as property-list dictionaries and reads them with a whole-dictionary cast
 * (ios/OpenCircuit/Observability/ObservabilityStore.swift:196-207,
 * ios/OpenCircuit/Health/HealthNotificationCenter.swift:145-185 @ b1c2fdd): a value of the wrong
 * type fails the cast, so the whole ledger is unreadable (its owner reads it as empty); a name this
 * build does not know, or a value of 0 or less, is skipped and the rest kept. Here every value is a
 * whole number (epoch milliseconds, or the night key), so a fraction is the wrong type.
 */
object LastFiredLedgerCodec {

    fun <K> encode(ledger: Map<K, Instant>, raw: (K) -> String): String =
        write(ledger.mapValues { (_, at) -> at.toEpochMilli() }, raw)

    fun <K> decode(text: String, entries: List<K>, raw: (K) -> String): Decoded<Map<K, Instant>> =
        read(text, entries, raw) { Instant.ofEpochMilli(it) }

    fun <K> encodeNights(ledger: Map<K, Long>, raw: (K) -> String): String = write(ledger, raw)

    fun <K> decodeNights(text: String, entries: List<K>, raw: (K) -> String): Decoded<Map<K, Long>> =
        read(text, entries, raw) { it }

    private fun <K> write(ledger: Map<K, Long>, raw: (K) -> String): String =
        JsonObject(ledger.entries.associate { (k, v) -> raw(k) to JsonPrimitive(v) as JsonElement }.toSortedMap()).toString()

    private fun <K, V> read(text: String, entries: List<K>, raw: (K) -> String, value: (Long) -> V): Decoded<Map<K, V>> =
        readStored(text) { root ->
            val byRaw = entries.associateBy(raw)
            // Every value is read first: one of the wrong type makes the whole ledger unreadable,
            // even under a name that would have been skipped (upstream's cast sees them all).
            val values = root.obj().mapValues { (_, v) -> v.long() }
            buildMap {
                for ((name, v) in values) {
                    val key = byRaw[name] ?: continue
                    if (v > 0) put(key, value(v))
                }
            }
        }
}
