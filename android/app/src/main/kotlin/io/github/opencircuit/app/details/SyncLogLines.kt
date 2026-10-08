package io.github.opencircuit.app.details

import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.ringkit.SyncMeasurement.CapacityKind
import io.github.opencircuit.ringkit.SyncMeasurement.CapacityVerdict
import io.github.opencircuit.ringkit.SyncMeasurement.Continuity
import io.github.opencircuit.ringkit.SyncMeasurement.ContinuityKind
import io.github.opencircuit.ringkit.SyncMeasurement.InconclusiveReason
import io.github.opencircuit.store.SyncLogChannel
import io.github.opencircuit.store.SyncLogEntry
import java.time.Duration
import java.time.Instant

/**
 * The sync log as Connection details words it (PORTING.md D-273): the "Last sync continuity" and
 * "Ring capacity" rows, and one line per sync and per channel, newest sync first — what the owner
 * copies from the phone to answer how the real ring behaves. Counts, UTC times and the ring's
 * `0x82` bytes only; ASCII digits whatever the phone's locale.
 */
internal object SyncLogLines {

    /** Whether the last sync joined the one before, from [log] (oldest first). */
    fun continuity(log: List<SyncLogEntry>): String {
        val last = log.lastOrNull() ?: return NO_SYNC
        val gaps = last.channels.mapNotNull { c ->
            c.continuity?.takeIf { it.kind == ContinuityKind.GAP }?.let { "${span(it.gapSeconds ?: 0)} missing (${c.label} channel)" }
        }
        val kinds = last.channels.mapNotNull { it.continuity?.kind }
        return when {
            gaps.isNotEmpty() -> gaps.joinToString("; ")
            kinds.any { it == ContinuityKind.CONTIGUOUS || it == ContinuityKind.OVERLAP } -> "No gap since last sync"
            kinds.any { it == ContinuityKind.FIRST_SYNC } -> "First sync"
            else -> "No records in the last sync"
        }
    }

    /**
     * What the log says about the ring's storage: the newest decisive verdict (overwrites / stops)
     * any kept sync gave; else the longest lower bound; else the last sync's verdict.
     */
    fun capacity(log: List<SyncLogEntry>): String {
        if (log.isEmpty()) return NO_SYNC
        val verdicts = log.mapNotNull { it.capacity }
        val decisive = verdicts.lastOrNull { it.kind == CapacityKind.OVERWRITES_OLDEST || it.kind == CapacityKind.STOPS_WHEN_FULL }
        val bound = verdicts.filter { it.kind == CapacityKind.LOWER_BOUND }.maxByOrNull { it.span ?: Duration.ZERO }
        val shown = decisive ?: bound ?: log.last().capacity ?: return "not measured"
        return words(shown)
    }

    /** One line per sync and one per channel, newest sync first; "none yet" with no sync, "not available" with no ring. */
    fun lines(log: List<SyncLogEntry>?): List<String> {
        if (log == null) return listOf(NOT_AVAILABLE)
        if (log.isEmpty()) return listOf("none yet")
        return log.asReversed().flatMap { e -> listOf(header(e)) + e.channels.map(::channelLine) + tail(e) }
    }

    private fun header(e: SyncLogEntry): String {
        val by = if (e.heldBackBy.isEmpty()) "" else " by ${e.heldBackBy.joinToString(", ")}"
        val paused = if (e.paused) " · paused" else ""
        val took = millis(Duration.between(e.startedAt, e.finishedAt).toMillis())
        return "${e.finishedAt} · ${e.outcome} · ${e.recordsStored} stored · ${e.heldBack} held back$by · $took$paused"
    }

    private fun channelLine(c: SyncLogChannel): String {
        val acks = c.syncAcks.joinToString(" ").ifEmpty { "none" }
        val fallback = when {
            !c.openFallback -> "no"
            c.fallbackHelped == true -> "re-auth, answered"
            else -> "re-auth, no answer"
        }
        val first = c.firstCounter
        val last = c.lastCounter
        val range = if (first != null && last != null) "${dateOf(first)} → ${dateOf(last)}" else "no counters"
        val countdown = c.firstCountdown?.let { "countdown $it → ${c.lastCountdown}" } ?: "countdown none"
        val gap = c.pageGapP50Millis?.let { "gap p50 ${millis(it)} max ${millis(c.pageGapMaxMillis ?: it)}" } ?: "gap none"
        val ack = c.ackLatencyP50Millis?.let { "ack p50 $it ms p95 ${c.ackLatencyP95Millis ?: it} ms" } ?: "ack none"
        return "  ${c.label} · 0x82 $acks · fallback $fallback · 4c×${c.pages4c} 47×${c.pages47} 4d×${c.pages4d} · ${c.records} records · $range · " +
            "$countdown · exit ${c.exitReason?.rawValue ?: "none"} · end ${if (c.endSeen) "yes" else "no"} · rounds ${c.rounds} · " +
            "${c.durationMillis?.let(::millis) ?: "duration none"} · $gap · $ack · re-offers ${c.reoffers} · status replies ${c.statusReplies} · " +
            continuityWords(c.continuity)
    }

    private fun tail(e: SyncLogEntry): String {
        val days = e.recordsPerDay.entries.joinToString(", ") { "${it.key} ${it.value}" }.ifEmpty { "none" }
        val charges = e.chargeMarkers.joinToString(", ").ifEmpty { "none" }
        val oldest = e.oldestRecord
        val newest = e.newestRecord
        val span = if (oldest != null && newest != null) "$oldest → $newest" else "no records"
        return "  capacity ${e.capacity?.let(::words) ?: "not measured"} · records $span · per day $days · charge markers $charges · " +
            "nights ${e.nightsStaged} staged, ${e.nightsWaiting} waiting · ${e.droppedAfterBound} past the bound · " +
            "${e.pagesUnacknowledged} pages unacknowledged · ${e.undeliveredFrames} frames undelivered · firmware ${e.firmware ?: "not read"}"
    }

    private fun words(v: CapacityVerdict): String {
        val span = v.span?.let { "${it.toDays()} d ${it.toHoursPart()} h" } ?: "unknown"
        return when (v.kind) {
            CapacityKind.LOWER_BOUND -> "at least $span"
            CapacityKind.OVERWRITES_OLDEST -> "about $span — overwrites its oldest records when full"
            CapacityKind.STOPS_WHEN_FULL -> "about $span — stops recording when full"
            CapacityKind.INCONCLUSIVE -> "inconclusive — " + when (v.reason) {
                InconclusiveReason.NO_RECORDS -> "no records"
                InconclusiveReason.NOT_COMPLETE -> "the sync did not drain every channel"
                InconclusiveReason.SPARSE -> "the ring was off or not worn"
                InconclusiveReason.GAP_UNEXPLAINED -> "records are missing before this sync, cause unknown"
                null -> "no reason recorded"
            }
        }
    }

    private fun continuityWords(c: Continuity?): String = when (c?.kind) {
        null -> "continuity none"
        ContinuityKind.CONTIGUOUS -> "contiguous"
        ContinuityKind.OVERLAP -> "overlap"
        ContinuityKind.GAP -> "gap ${span(c.gapSeconds ?: 0)}"
        ContinuityKind.FIRST_SYNC -> "first sync"
        ContinuityKind.NO_RECORDS -> "no records"
    }

    /** "2 h 3 min", "45 min", "0 min". */
    private fun span(seconds: Long): String {
        val minutes = seconds.coerceAtLeast(0) / 60
        return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
    }

    /** "6.1 s" below a minute, "1 min 5 s" above. */
    private fun millis(ms: Long): String =
        if (ms < 60_000) "${ms / 1_000}.${(ms % 1_000) / 100} s" else "${ms / 60_000} min ${(ms % 60_000) / 1_000} s"

    private fun dateOf(counter: Long): Instant = Instant.ofEpochSecond(Command.SYNC_EPOCH + counter)

    private const val NO_SYNC = "no sync yet"
    private const val NOT_AVAILABLE = "not available"
}
