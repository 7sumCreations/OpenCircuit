package io.github.opencircuit.store.codec

import io.github.opencircuit.ringkit.SleepProvenance
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The stored form of a list of sleep segments (pending and staged segments, naps):
 * `[{"start":<ms>,"end":<ms>,"stage":"<raw>"}, …]`, with `"provenance":"<raw>"` only when the
 * segment is not measured.
 *
 * Upstream's hand-written `SleepSegment` Codable (`Metrics.swift:175-218`): leaving the label out
 * for a measured segment keeps a fully measured night in the form earlier builds wrote. On read, a
 * missing or `null` label is measured; any other label this build cannot read — an unknown string,
 * a number, an object — is [SleepProvenance.ASSERTED_COVERAGE_UNKNOWN] and the segment is kept:
 * a present label was written to mean something other than measured, and losing the label must
 * never lose the sleep. An unknown stage, or a missing start / end / stage, makes the whole list
 * unreadable, as in upstream.
 */
object SleepSegmentCodec {

    fun encode(segments: List<SleepSegment>): String = toJson(segments).toString()

    fun decode(text: String): Decoded<List<SleepSegment>> = readStored(text, ::fromJson)

    /** [segments] as the JSON array [encode] writes, for a stored form that nests a segment list. */
    internal fun toJson(segments: List<SleepSegment>): JsonArray = JsonArray(
        segments.map { s ->
            jsonObjectOf(
                "start" to s.start.json(),
                "end" to s.end.json(),
                "stage" to JsonPrimitive(s.stage.rawValue),
                "provenance" to if (s.provenance == SleepProvenance.MEASURED) null else JsonPrimitive(s.provenance.rawValue),
            )
        },
    )

    /** A nested segment list read by [decode]'s rules; only valid inside a [readStored] block. */
    internal fun fromJson(root: JsonElement): List<SleepSegment> =
        root.array().map { e ->
            val o = e.obj()
            SleepSegment(
                start = o.required("start").instant(),
                end = o.required("end").instant(),
                stage = o.required("stage").enumOf(SleepStage.entries) { it.rawValue },
                provenance = provenance(o),
            )
        }

    private fun provenance(o: JsonObject): SleepProvenance {
        // Missing and null are both "no label" (Swift checks decodeNil before reading the value).
        val v = o.optional("provenance") ?: return SleepProvenance.MEASURED
        val raw = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
        return SleepProvenance.entries.firstOrNull { it.rawValue == raw } ?: SleepProvenance.ASSERTED_COVERAGE_UNKNOWN
    }
}
