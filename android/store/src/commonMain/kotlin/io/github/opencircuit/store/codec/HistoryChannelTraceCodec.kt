package io.github.opencircuit.store.codec

import io.github.opencircuit.ringkit.HistoryChannelExitReason
import io.github.opencircuit.ringkit.HistoryChannelTrace
import kotlinx.serialization.json.JsonPrimitive

/**
 * The stored form of one history-channel trace (the diagnostics of one channel open): one JSON
 * object, keys in alphabetical order, dates as epoch milliseconds, absent optionals left out.
 *
 * Upstream's synthesized Codable (`HistorySyncAssessment.swift:81-139`). Ten keys are required —
 * `label`, `channel`, `startedAt`, `sawSyncAck`, `sawEmptyHistorySignal`, `page4CCount`,
 * `page47Count`, `endMarkerCount`, `recordsAtStart`, `recordsAtEnd` — and a trace missing any of
 * them is unreadable. The other ten are optional, so a trace written before a field existed reads
 * that field as null, which classifies exactly as the code did before it existed. `channel`,
 * `syncAckFlag` and the two opcodes are bytes; a counter past 32 bits makes the trace unreadable.
 */
object HistoryChannelTraceCodec {

    fun encode(trace: HistoryChannelTrace): String = jsonObjectOf(
        "channel" to JsonPrimitive(trace.channel),
        "endMarkerCount" to JsonPrimitive(trace.endMarkerCount),
        "exitReason" to trace.exitReason?.let { JsonPrimitive(it.rawValue) },
        "fetchNudges" to trace.fetchNudges?.let(::JsonPrimitive),
        "finishedAt" to trace.finishedAt?.json(),
        "firstOpcode" to trace.firstOpcode?.let(::JsonPrimitive),
        "label" to JsonPrimitive(trace.label),
        "lastOpcode" to trace.lastOpcode?.let(::JsonPrimitive),
        "openWriteFailed" to trace.openWriteFailed?.let(::JsonPrimitive),
        "page47Count" to JsonPrimitive(trace.page47Count),
        "page4CCount" to JsonPrimitive(trace.page4CCount),
        "page4DCount" to trace.page4DCount?.let(::JsonPrimitive),
        "recordsAtEnd" to JsonPrimitive(trace.recordsAtEnd),
        "recordsAtStart" to JsonPrimitive(trace.recordsAtStart),
        "reopenRound" to trace.reopenRound?.let(::JsonPrimitive),
        "sawEmptyHistorySignal" to JsonPrimitive(trace.sawEmptyHistorySignal),
        "sawSyncAck" to JsonPrimitive(trace.sawSyncAck),
        "sportSampleCount" to trace.sportSampleCount?.let(::JsonPrimitive),
        "startedAt" to trace.startedAt.json(),
        "syncAckFlag" to trace.syncAckFlag?.let(::JsonPrimitive),
    ).toString()

    fun decode(text: String): Decoded<HistoryChannelTrace> = readStored(text) { root ->
        val o = root.obj()
        val t = HistoryChannelTrace(
            label = o.required("label").string(),
            channel = o.required("channel").uInt8(),
            startedAt = o.required("startedAt").instant(),
        )
        t.finishedAt = o.optional("finishedAt")?.instant()
        t.sawSyncAck = o.required("sawSyncAck").bool()
        t.syncAckFlag = o.optional("syncAckFlag")?.uInt8()
        t.sawEmptyHistorySignal = o.required("sawEmptyHistorySignal").bool()
        t.openWriteFailed = o.optional("openWriteFailed")?.bool()
        t.fetchNudges = o.optional("fetchNudges")?.int()
        t.reopenRound = o.optional("reopenRound")?.int()
        t.page4CCount = o.required("page4CCount").int()
        t.page47Count = o.required("page47Count").int()
        t.page4DCount = o.optional("page4DCount")?.int()
        t.sportSampleCount = o.optional("sportSampleCount")?.int()
        t.endMarkerCount = o.required("endMarkerCount").int()
        t.recordsAtStart = o.required("recordsAtStart").int()
        t.recordsAtEnd = o.required("recordsAtEnd").int()
        t.firstOpcode = o.optional("firstOpcode")?.uInt8()
        t.lastOpcode = o.optional("lastOpcode")?.uInt8()
        t.exitReason = o.optional("exitReason")?.enumOf(HistoryChannelExitReason.entries) { it.rawValue }
        t
    }
}
