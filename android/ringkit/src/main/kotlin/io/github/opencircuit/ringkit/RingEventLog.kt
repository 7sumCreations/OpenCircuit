package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant

// Ring event log carried by `0x50` frames (docs/PROTOCOL.md §5.5.1) — and the ring's OWN activity
// sessions decoded from it. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/RingEventLog.swift:21-192 (@ b1c2fdd), without the
// ledger's stored form (`defaultsKey` / `load` / `save`, `:146-158`, and every `Codable`
// conformance): the stored form is decided with the storage design.
//
// WHY THIS EXISTS. The ring decides for itself when the wearer is active: while it is, it stops
// its ~2.5-min unsolicited pushes (the link stays UP), so a suspended app observes no steps and
// receives the whole bout later as back-filled `0x4c` history. An HR alert gate then sees
// HR ≥ 100 with no concurrent steps and fires "elevated heart rate while inactive" on a walk. The
// ring's own start/stop markers for those bouts ride in the `0x50` frame received at the end of
// every drain — this file turns them into intervals the alert gate can honour.

/**
 * One 6-byte entry of a `0x50` frame: `[type][value][cursor:4 BE]`, cursor in
 * [Command.SYNC_EPOCH] seconds. 🟢 layout; the meaning of each (type, value) is tagged on the
 * [RingEventLog] constants.
 *
 * [type] and [value] are 0–255 and [cursor] 0–0xFFFFFFFF (Swift's `UInt8` / `UInt32`); anything
 * else throws [IllegalArgumentException].
 */
data class RingEvent(val type: Int, val value: Int, val cursor: Long) {
    init {
        require(type in 0..0xFF) { "type must be 0..255: $type" }
        require(value in 0..0xFF) { "value must be 0..255: $value" }
        require(cursor in 0..0xFFFF_FFFFL) { "cursor must be unsigned 32-bit: $cursor" }
    }

    val date: Instant get() = Instant.ofEpochSecond(Command.SYNC_EPOCH + cursor)
}

object RingEventLog {
    const val OPCODE = 0x50
    const val ENTRY_LENGTH = 6

    /**
     * Activity-session markers (type `0x10`). 🟡 PROBABLE, from upstream's 2026-09-27 bundle and
     * that day's system log: `0f`→`0a` brackets a user-confirmed walk, and the ring resumed its
     * pushes 8 s after the `0a`. The `07`/`08` pairs sharing the type sit in the overnight window,
     * so they are NOT treated as activity here (🔴 guess: sleep markers).
     */
    const val ACTIVITY_TYPE = 0x10
    const val ACTIVITY_START = 0x0f
    const val ACTIVITY_END = 0x0a

    /**
     * Longest an UNCLOSED session may run. A POLICY bound, not a measurement: the longest closed
     * pair in upstream's corpus is 68 min, so 4 h covers a long hike with margin while capping
     * what a lost end marker can suppress.
     */
    val OPEN_SESSION_CAP: Duration = Duration.ofHours(4)

    /**
     * One decoded `0x50` event frame. [hiddenCount] (`[2]`, 🟡) counts entries the ring holds but
     * did NOT show: non-zero means the log overflowed the 40-entry frame, so what is shown is the
     * OLDEST 40 and every newer marker is not on the wire. [events] is the frame's own copy.
     */
    class Frame(val hiddenCount: Int, events: List<RingEvent>) {
        val events: List<RingEvent> = events.toList()

        override fun equals(other: Any?): Boolean =
            other is Frame && hiddenCount == other.hiddenCount && events == other.events

        override fun hashCode(): Int = 31 * hiddenCount + events.hashCode()

        override fun toString(): String = "Frame(hiddenCount=$hiddenCount, events=$events)"
    }

    /** One activity session, both ends inclusive (upstream's `(Date, Date)` tuple). */
    data class ActivitySession(val start: Instant, val end: Instant)

    /**
     * Decode a `0x50` event frame, or null when it is not one. NO XOR trailer (§5.5): after
     * `50 00 <hidden>` the payload must be a whole number of 6-byte entries. Of the legacy shapes
     * `EpochRecord.parseEndOfHistory` handles, the 8- and 12-byte ones are not whole entries and
     * return null; the 9-byte `15 <sub> <cursor>` one IS a single entry and decodes as one.
     */
    fun decodeFrame(frame: ByteArray): Frame? {
        if (frame.size < 3 + ENTRY_LENGTH || frame.u8(0) != OPCODE || frame.u8(1) != 0x00) return null
        val payloadSize = frame.size - 3
        if (payloadSize % ENTRY_LENGTH != 0) return null
        val events = (3 until frame.size step ENTRY_LENGTH).map { o ->
            val cursor = (frame.u8(o + 2).toLong() shl 24) or (frame.u8(o + 3).toLong() shl 16) or
                (frame.u8(o + 4).toLong() shl 8) or frame.u8(o + 5).toLong()
            RingEvent(type = frame.u8(o), value = frame.u8(o + 1), cursor = cursor)
        }
        return Frame(hiddenCount = frame.u8(2), events = events)
    }

    /** The entries of a `0x50` event frame (see [decodeFrame]). */
    fun decode(frame: ByteArray): List<RingEvent>? = decodeFrame(frame)?.events

    /**
     * The ring's activity sessions from `0x10` start/end markers of ONE ring (pair per ring —
     * interleaving two rings' markers would pair A's start with B's end).
     *
     * EVERY session is capped at [OPEN_SESSION_CAP], closed or not. A start followed by another
     * start means the first one's end was lost: the first is closed at the second (still capped).
     * A start with no later end runs to [now] (capped). An end with no open start is DROPPED.
     * Duplicates collapse. Only ever used to SUPPRESS, so an unpaired marker costs at most a missed
     * suppression.
     */
    fun activitySessions(events: List<RingEvent>, now: Instant): List<ActivitySession> {
        val markers = events.filter { isActivityMarker(it) }.distinct().sortedBy { it.cursor }
        val sessions = mutableListOf<ActivitySession>()
        fun close(s: Instant, e: Instant) {
            val capped = s + OPEN_SESSION_CAP
            sessions += ActivitySession(s, if (e < capped) e else capped)
        }
        var open: Instant? = null
        for (m in markers) {
            if (m.value == ACTIVITY_START) {
                open?.let { close(it, m.date) }
                open = m.date
            } else {
                open?.let {
                    close(it, m.date)
                    open = null
                }
            }
        }
        open?.let { if (it <= now) close(it, now) }
        return sessions
    }

    internal fun isActivityMarker(e: RingEvent): Boolean =
        e.type == ACTIVITY_TYPE && (e.value == ACTIVITY_START || e.value == ACTIVITY_END)
}

/**
 * De-duplicated activity markers across drains, per ring. One `0x50` shows at most 40 entries and
 * the log is cleared from time to time (§5.5.1), so markers are banked as they are seen.
 *
 * OWNERSHIP. Upstream is a Swift `mutating struct`. Here it is a mutable class owned by one
 * holder: sharing the reference shares the ledger, [copy] gives an independent one, [events] and
 * [overflow] hand out fresh read-only maps on every read, and two ledgers compare by content.
 */
class RingActivityEventLedger(
    events: Map<String, List<RingEvent>> = emptyMap(),
    overflow: Map<String, Overflow> = emptyMap(),
) {
    /** The latest overflow state seen for a ring: `hidden` > 0 means newer markers are not visible. */
    data class Overflow(val hidden: Int, val seenAt: Instant)

    companion object {
        /**
         * How long a marker is kept. The alert look-back ([HealthAlertLookback.instantLookback]) reaches
         * back at most 35 h 59 min (12 h plus quiet hours of up to 23 h 59 min), and a session's start
         * marker can sit the 4 h [RingEventLog.OPEN_SESSION_CAP] plus the 10 min lead and the 10 min
         * recovery pad before the oldest reading it must still suppress — 40 h 19 min in all. So 48 h
         * keeps every marker the alert gate can ask about, and bounds the stored blob.
         * `AlertLookbackRetentionTest` pins this over every quiet-hours window.
         */
        val RETENTION: Duration = Duration.ofHours(48)

        /** Markers more than this far in the future are implausible and dropped. */
        private val FUTURE_TOLERANCE: Duration = Duration.ofHours(1)
    }

    private val held: MutableMap<String, List<RingEvent>> =
        events.mapValuesTo(LinkedHashMap()) { it.value.toList() }
    private val flags: MutableMap<String, Overflow> = LinkedHashMap(overflow)

    /** Activity markers keyed by ring identifier. A fresh map on every read. */
    val events: Map<String, List<RingEvent>> get() = LinkedHashMap(held)

    /** The latest overflow state per ring. A fresh map on every read. */
    val overflow: Map<String, Overflow> get() = LinkedHashMap(flags)

    /**
     * Bank one decoded frame from [ring]: its activity markers (dropping duplicates and anything
     * older than [RETENTION] or more than an hour in the future) and its overflow state.
     */
    fun merge(frame: RingEventLog.Frame, ring: String, now: Instant) {
        val keep = frame.events.filter { RingEventLog.isActivityMarker(it) }
        val lo = now - RETENTION
        val hi = now + FUTURE_TOLERANCE
        held[ring] = ((held[ring] ?: emptyList()) + keep).distinct()
            .filter { it.date >= lo && it.date <= hi }
            .sortedBy { it.cursor }
        // Re-stamp only when the hidden count CHANGES: while overflowed the ring answers every
        // keepalive with the same frozen frame, and re-stamping would re-save on each one.
        if (frame.hiddenCount == 0) {
            flags.remove(ring)
        } else if (flags[ring]?.hidden != frame.hiddenCount) {
            flags[ring] = Overflow(hidden = frame.hiddenCount, seenAt = now)
        }
        // Other rings are only pruned here, so a retired ring's markers and overflow flag would
        // otherwise live forever.
        for (other in held.keys.toList()) {
            if (other == ring) continue
            val kept = held.getValue(other).filter { it.date >= lo }
            if (kept.isEmpty()) held.remove(other) else held[other] = kept
        }
        flags.entries.removeIf { (other, o) -> other != ring && o.seenAt < lo }
    }

    /** Every ring's activity sessions currently on record, each ring paired on its own. */
    fun sessions(now: Instant): List<RingEventLog.ActivitySession> =
        held.keys.sorted().flatMap { RingEventLog.activitySessions(held.getValue(it), now) }

    /** An independent copy: merging into either ledger never changes the other. */
    fun copy(): RingActivityEventLedger = RingActivityEventLedger(held, flags)

    override fun equals(other: Any?): Boolean =
        other is RingActivityEventLedger && held == other.held && flags == other.flags

    override fun hashCode(): Int = 31 * held.hashCode() + flags.hashCode()

    override fun toString(): String = "RingActivityEventLedger(events=$held, overflow=$flags)"
}
