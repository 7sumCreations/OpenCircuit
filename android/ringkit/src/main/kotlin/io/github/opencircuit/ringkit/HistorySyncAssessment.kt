package io.github.opencircuit.ringkit

// Testable classification of one history-drain channel outcome. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/HistorySyncAssessment.swift:1-199 (@ b1c2fdd).
//
// The BLE session fills a [HistoryChannelTrace] incrementally while draining one channel; this
// pure layer turns that trace into a conservative success/failure verdict for downstream sleep
// persistence.
//
// OWNERSHIP. Upstream the trace is a Swift struct with many `var` fields (every assignment copies).
// Here it is a mutable class owned by ONE drain: sharing the reference shares the trace. Take
// [HistoryChannelTrace.copy] for an independent snapshot. Two traces compare by content.

import java.time.Duration
import java.time.Instant
import java.util.Locale

/** The verdict on one history-drain channel open. [rawValue] is upstream's persisted string. */
enum class HistoryChannelOutcome(val rawValue: String) {
    /**
     * ⚠️ MEANS "THIS CHANNEL OPEN ENDED CLEANLY" — pages arrived and the drain exited on a `0x50`
     * end-marker. Going quiet after pages with no `0x50` is [PARTIAL], never this (deviation D-43). It does NOT mean the RING IS EMPTY: the `0x50` reports
     * where the ring's resume pointer stood at that moment, and the ring keeps recording (upstream
     * saw a clean `complete` followed by two more records within five minutes). It is the one
     * outcome that unlocks [allowsSleepCommit], because a clean exit means what was pulled is
     * trustworthy to stage — not that everything was pulled.
     */
    COMPLETE("complete"),
    EMPTY("empty"),
    PARTIAL("partial"),
    PPG_ONLY("ppgOnly"),

    /**
     * Pages arrived, but every one was a `0x4d` sport record — no `0x4c` epoch, no `0x47` PPG. The
     * normal shape of a healthy sport-channel (`0x02`) drain. ⚠️ Not proof the sport channel was the
     * source: an OSA assessment pushes a brief `0x4d` burst at start, which can land on whatever
     * trace is open. Never allows a sleep commit.
     */
    SPORT_ONLY("sportOnly"),
    NO_ACK("noAck"),

    /**
     * The channel's sync-open never reached the wire — the link was down or half-open. Distinct
     * from [NO_ACK], which means the ring was asked and stayed silent: here WE never asked.
     */
    LINK_DOWN("linkDown"),

    /**
     * Still waiting on the ring (no ACK, no pages) when OUR OWN session was torn down. The ring is
     * exonerated; nothing aggregates this across passes (see [HistoryDrainPlan.resuming]).
     */
    CANCELLED("cancelled");

    /** Safe to re-stage/persist sleep from this channel. */
    val allowsSleepCommit: Boolean get() = this == COMPLETE
}

/** How one channel's drain loop ended. [rawValue] is upstream's persisted string. */
enum class HistoryChannelExitReason(val rawValue: String) {
    END_MARKER("endMarker"),
    QUIET_AFTER_PAGES("quietAfterPages"),
    QUIET_NO_PAGES("quietNoPages"),
    HARD_TIMEOUT("hardTimeout"),
    CANCELLED("cancelled"),

    /**
     * Abandoned because the link went unusable — the opens could not be written, or it dropped
     * mid-drain. Distinct from [HARD_TIMEOUT] / [QUIET_NO_PAGES], which waited out a live link.
     */
    LINK_UNUSABLE("linkUnusable"),
}

/**
 * What one drain saw on one channel. Byte-valued fields ([channel], [syncAckFlag], [firstOpcode],
 * [lastOpcode]) are `Int` 0–255 and are rejected outside that range, as Swift's `UInt8` could not
 * hold them.
 *
 * @param startedAt when this channel's open began. Upstream defaulted it to the wall clock; here
 *   the caller passes it, so the trace never reads a hidden clock.
 */
class HistoryChannelTrace(val label: String, channel: Int, val startedAt: Instant) {

    val channel: Int = requireByte(channel, "channel")

    var finishedAt: Instant? = null
    var sawSyncAck: Boolean = false

    var syncAckFlag: Int? = null
        set(value) {
            field = value?.let { requireByte(it, "syncAckFlag") }
        }

    /**
     * `0x82` byte[1] == `0xff` — the ring signals its history pointer is already at the end (🟡
     * probable). With no pages, the drain may exit early instead of waiting its full budget. It
     * affects the loop's exit timing only, never the classification.
     */
    var sawEmptyHistorySignal: Boolean = false

    /**
     * The sync-open commands never reached the wire. Drives [HistoryChannelOutcome.LINK_DOWN].
     * Nullable on purpose, as upstream: null means "not recorded", which classifies exactly as
     * the code did before the flag existed.
     */
    var openWriteFailed: Boolean? = null

    /** `07 00 00` re-asks sent at this channel's quiet exit ([DrainContinuation]). */
    var fetchNudges: Int? = null

    /** 0 for a channel's first open in a sync, n for its n-th immediate reopen ([DrainContinuation]). */
    var reopenRound: Int? = null

    var page4CCount: Int = 0
    var page47Count: Int = 0

    /**
     * `0x4d` pages seen on this channel, counted from the wire before decode. Zero on every trace
     * this code creates; null is reserved for a stored trace written before the counter existed
     * ("we counted and it was zero" vs "we never counted").
     */
    var page4DCount: Int? = 0

    /** Sport samples decoded from those `0x4d` pages. Same zero-on-creation contract. */
    var sportSampleCount: Int? = 0

    var endMarkerCount: Int = 0
    var recordsAtStart: Int = 0
    var recordsAtEnd: Int = 0

    var firstOpcode: Int? = null
        set(value) {
            field = value?.let { requireByte(it, "firstOpcode") }
        }

    var lastOpcode: Int? = null
        set(value) {
            field = value?.let { requireByte(it, "lastOpcode") }
        }

    var exitReason: HistoryChannelExitReason? = null

    val recordsAdded: Int get() = maxOf(recordsAtEnd - recordsAtStart, 0)

    /** Any history page at all, sport included. */
    val sawAnyPage: Boolean get() = page4CCount > 0 || page47Count > 0 || (page4DCount ?: 0) > 0

    /**
     * True when the first frame seen after this trace was installed was a `0x4c` data page: the
     * ring was already mid-handoff, so pages were in flight and uncounted before this drain existed.
     * A healthy open sees its own handshake first (`0x81` auth challenge or `0x82` sync-open ACK).
     */
    val openedOntoLiveStream: Boolean get() = firstOpcode == 0x4C

    /** `finishedAt - startedAt`, or null while the channel is still open. Upstream: `durationSeconds`. */
    val duration: Duration? get() = finishedAt?.let { Duration.between(startedAt, it) }

    /**
     * The verdict. Branch order is load-bearing: epoch pages outrank PPG, which outranks sport,
     * which outranks an ACK; any evidence that the ring answered outranks a stale write-failure
     * flag or a cancellation. A channel that never saw `0x4c` can never be [HistoryChannelOutcome.COMPLETE].
     */
    val outcome: HistoryChannelOutcome
        get() {
            if (page4CCount > 0 && endMarkerCount > 0) return HistoryChannelOutcome.COMPLETE
            if (page4CCount > 0) return HistoryChannelOutcome.PARTIAL
            if (page47Count > 0) return HistoryChannelOutcome.PPG_ONLY
            if ((page4DCount ?: 0) > 0) return HistoryChannelOutcome.SPORT_ONLY
            if (sawSyncAck) return HistoryChannelOutcome.EMPTY
            if (openWriteFailed == true) return HistoryChannelOutcome.LINK_DOWN
            if (exitReason == HistoryChannelExitReason.CANCELLED) return HistoryChannelOutcome.CANCELLED
            return HistoryChannelOutcome.NO_ACK
        }

    /** An independent copy: later changes to either trace never reach the other. */
    fun copy(): HistoryChannelTrace {
        val c = HistoryChannelTrace(label, channel, startedAt)
        c.finishedAt = finishedAt
        c.sawSyncAck = sawSyncAck
        c.syncAckFlag = syncAckFlag
        c.sawEmptyHistorySignal = sawEmptyHistorySignal
        c.openWriteFailed = openWriteFailed
        c.fetchNudges = fetchNudges
        c.reopenRound = reopenRound
        c.page4CCount = page4CCount
        c.page47Count = page47Count
        c.page4DCount = page4DCount
        c.sportSampleCount = sportSampleCount
        c.endMarkerCount = endMarkerCount
        c.recordsAtStart = recordsAtStart
        c.recordsAtEnd = recordsAtEnd
        c.firstOpcode = firstOpcode
        c.lastOpcode = lastOpcode
        c.exitReason = exitReason
        return c
    }

    private fun fields(): List<Any?> = listOf(
        label, channel, startedAt, finishedAt, sawSyncAck, syncAckFlag, sawEmptyHistorySignal,
        openWriteFailed, fetchNudges, reopenRound, page4CCount, page47Count, page4DCount,
        sportSampleCount, endMarkerCount, recordsAtStart, recordsAtEnd, firstOpcode, lastOpcode, exitReason,
    )

    /** Content equality over every field (upstream `Equatable`). */
    override fun equals(other: Any?): Boolean = other is HistoryChannelTrace && fields() == other.fields()

    override fun hashCode(): Int = fields().hashCode()

    override fun toString(): String =
        "HistoryChannelTrace(label=$label, channel=${String.format(Locale.ROOT, "%02x", channel)}, " +
            "pages4c=$page4CCount, pages47=$page47Count, pages4d=$page4DCount, " +
            "endMarkers=$endMarkerCount, exit=$exitReason)"

    private companion object {
        fun requireByte(value: Int, name: String): Int {
            require(value in 0..0xFF) { "$name must be a byte 0–255: $value" }
            return value
        }
    }
}
