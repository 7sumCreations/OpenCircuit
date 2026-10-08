package io.github.opencircuit.store.codec

import io.github.opencircuit.ringkit.HistoryChannelExitReason
import io.github.opencircuit.ringkit.HistoryChannelOutcome
import io.github.opencircuit.ringkit.SyncMeasurement
import io.github.opencircuit.store.SyncLogChannel
import io.github.opencircuit.store.SyncLogEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration

/**
 * The stored form of one sync log entry: one JSON object, dates as epoch milliseconds, absent
 * optionals left out, lists and counts written even when empty or zero. `startedAt`, `finishedAt`
 * and `outcome` are required; a missing count reads as 0, a missing list as empty. Enums are
 * stored by their raw string (the ported `HistoryChannelOutcome` / `HistoryChannelExitReason`
 * values, the measurement's names); an unknown one makes the entry unreadable. Kotlin-only
 * (PORTING.md D-273): upstream keeps no sync log.
 */
internal object SyncLogCodec {

    fun encode(e: SyncLogEntry): String = jsonObjectOf(
        "capacity" to e.capacity?.let(::capacity),
        "channels" to JsonArray(e.channels.map(::channel)),
        "chargeMarkers" to JsonArray(e.chargeMarkers.map { it.json() }),
        "droppedAfterBound" to JsonPrimitive(e.droppedAfterBound),
        "finishedAt" to e.finishedAt.json(),
        "firmware" to e.firmware?.let(::JsonPrimitive),
        "heldBack" to JsonPrimitive(e.heldBack),
        "heldBackBy" to JsonArray(e.heldBackBy.map(::JsonPrimitive)),
        "newestRecord" to e.newestRecord?.json(),
        "nightsStaged" to JsonPrimitive(e.nightsStaged),
        "nightsWaiting" to JsonPrimitive(e.nightsWaiting),
        "oldestRecord" to e.oldestRecord?.json(),
        "outcome" to JsonPrimitive(e.outcome),
        "pagesUnacknowledged" to JsonPrimitive(e.pagesUnacknowledged),
        "paused" to JsonPrimitive(e.paused),
        "recordsPerDay" to JsonObject(e.recordsPerDay.toSortedMap().mapValues { JsonPrimitive(it.value) }),
        "recordsStored" to JsonPrimitive(e.recordsStored),
        "startedAt" to e.startedAt.json(),
        "undeliveredFrames" to JsonPrimitive(e.undeliveredFrames),
    ).toString()

    fun decode(text: String): Decoded<SyncLogEntry> = readStored(text) { root ->
        val o = root.obj()
        SyncLogEntry(
            startedAt = o.required("startedAt").instant(),
            finishedAt = o.required("finishedAt").instant(),
            outcome = o.required("outcome").string(),
            paused = o.optional("paused")?.bool() ?: false,
            firmware = o.optional("firmware")?.string(),
            channels = o.optional("channels")?.array()?.map(::channelOf).orEmpty(),
            recordsStored = o.count("recordsStored"),
            heldBack = o.count("heldBack"),
            heldBackBy = o.optional("heldBackBy")?.array()?.map { it.string() }.orEmpty(),
            nightsStaged = o.count("nightsStaged"),
            nightsWaiting = o.count("nightsWaiting"),
            droppedAfterBound = o.count("droppedAfterBound"),
            pagesUnacknowledged = o.count("pagesUnacknowledged"),
            undeliveredFrames = o.count("undeliveredFrames"),
            oldestRecord = o.optional("oldestRecord")?.instant(),
            newestRecord = o.optional("newestRecord")?.instant(),
            recordsPerDay = o.optional("recordsPerDay")?.obj()?.mapValues { it.value.int() }.orEmpty(),
            chargeMarkers = o.optional("chargeMarkers")?.array()?.map { it.instant() }.orEmpty(),
            capacity = o.optional("capacity")?.let(::capacityOf),
        )
    }

    private fun channel(c: SyncLogChannel): JsonObject = jsonObjectOf(
        "ackLatencyP50Millis" to c.ackLatencyP50Millis?.let(::JsonPrimitive),
        "ackLatencyP95Millis" to c.ackLatencyP95Millis?.let(::JsonPrimitive),
        "channel" to JsonPrimitive(c.channel),
        "continuity" to c.continuity?.let { jsonObjectOf("gapSeconds" to it.gapSeconds?.let(::JsonPrimitive), "kind" to JsonPrimitive(it.kind.name)) },
        "drainedThrough" to c.drainedThrough?.json(),
        "durationMillis" to c.durationMillis?.let(::JsonPrimitive),
        "endSeen" to JsonPrimitive(c.endSeen),
        "exitReason" to c.exitReason?.let { JsonPrimitive(it.rawValue) },
        "fallbackHelped" to c.fallbackHelped?.let(::JsonPrimitive),
        "firstCountdown" to c.firstCountdown?.let(::JsonPrimitive),
        "firstCounter" to c.firstCounter?.let(::JsonPrimitive),
        "label" to JsonPrimitive(c.label),
        "lastCountdown" to c.lastCountdown?.let(::JsonPrimitive),
        "lastCounter" to c.lastCounter?.let(::JsonPrimitive),
        "openFallback" to JsonPrimitive(c.openFallback),
        "pageGapMaxMillis" to c.pageGapMaxMillis?.let(::JsonPrimitive),
        "pageGapP50Millis" to c.pageGapP50Millis?.let(::JsonPrimitive),
        "pages47" to JsonPrimitive(c.pages47),
        "pages4c" to JsonPrimitive(c.pages4c),
        "pages4d" to JsonPrimitive(c.pages4d),
        "records" to JsonPrimitive(c.records),
        "reoffers" to JsonPrimitive(c.reoffers),
        "rounds" to JsonPrimitive(c.rounds),
        "statusReplies" to JsonPrimitive(c.statusReplies),
        "syncAcks" to JsonArray(c.syncAcks.map(::JsonPrimitive)),
        "verdict" to c.verdict?.let { JsonPrimitive(it.rawValue) },
    )

    private fun channelOf(element: JsonElement): SyncLogChannel {
        val o = element.obj()
        return SyncLogChannel(
            label = o.required("label").string(),
            channel = o.required("channel").uInt8(),
            verdict = o.optional("verdict")?.enumOf(HistoryChannelOutcome.entries) { it.rawValue },
            syncAcks = o.optional("syncAcks")?.array()?.map { it.string() }.orEmpty(),
            openFallback = o.optional("openFallback")?.bool() ?: false,
            fallbackHelped = o.optional("fallbackHelped")?.bool(),
            firstCountdown = o.optional("firstCountdown")?.int(),
            lastCountdown = o.optional("lastCountdown")?.int(),
            pages4c = o.count("pages4c"),
            pages47 = o.count("pages47"),
            pages4d = o.count("pages4d"),
            records = o.count("records"),
            firstCounter = o.optional("firstCounter")?.long(),
            lastCounter = o.optional("lastCounter")?.long(),
            exitReason = o.optional("exitReason")?.enumOf(HistoryChannelExitReason.entries) { it.rawValue },
            endSeen = o.optional("endSeen")?.bool() ?: false,
            rounds = o.count("rounds"),
            durationMillis = o.optional("durationMillis")?.long(),
            pageGapP50Millis = o.optional("pageGapP50Millis")?.long(),
            pageGapMaxMillis = o.optional("pageGapMaxMillis")?.long(),
            ackLatencyP50Millis = o.optional("ackLatencyP50Millis")?.long(),
            ackLatencyP95Millis = o.optional("ackLatencyP95Millis")?.long(),
            reoffers = o.count("reoffers"),
            statusReplies = o.count("statusReplies"),
            drainedThrough = o.optional("drainedThrough")?.instant(),
            continuity = o.optional("continuity")?.obj()?.let { c ->
                SyncMeasurement.Continuity(
                    kind = c.required("kind").enumOf(SyncMeasurement.ContinuityKind.entries) { it.name },
                    gapSeconds = c.optional("gapSeconds")?.long(),
                )
            },
        )
    }

    private fun capacity(v: SyncMeasurement.CapacityVerdict): JsonObject = jsonObjectOf(
        "kind" to JsonPrimitive(v.kind.name),
        "reason" to v.reason?.let { JsonPrimitive(it.name) },
        "spanSeconds" to v.span?.let { JsonPrimitive(it.seconds) },
    )

    private fun capacityOf(element: JsonElement): SyncMeasurement.CapacityVerdict {
        val o = element.obj()
        return SyncMeasurement.CapacityVerdict(
            kind = o.required("kind").enumOf(SyncMeasurement.CapacityKind.entries) { it.name },
            span = o.optional("spanSeconds")?.long()?.let(Duration::ofSeconds),
            reason = o.optional("reason")?.enumOf(SyncMeasurement.InconclusiveReason.entries) { it.name },
        )
    }

    /** A count: 0 when absent. */
    private fun JsonObject.count(key: String): Int = optional(key)?.int() ?: 0
}
