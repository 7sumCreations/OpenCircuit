package io.github.opencircuit.app.sync

import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.ringkit.CommitPlanner
import io.github.opencircuit.ringkit.FirmwareInfo
import io.github.opencircuit.ringkit.HistoryChannelOutcome
import io.github.opencircuit.ringkit.HistoryDrainPlan
import io.github.opencircuit.ringkit.RingGeneration
import io.github.opencircuit.ringkit.SyncMeasurement
import io.github.opencircuit.store.SyncLogChannel
import io.github.opencircuit.store.SyncLogEntry
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * The sync log's rules, pure (PORTING.md D-273): a finished sync's report as its log entry, which
 * channels held records back and why, how old the oldest record still on the ring may be, and the
 * ring's model. The zone and the firmware are passed in; nothing reads the machine's.
 */
object SyncLogEntries {

    /** The channels a foreground sync plans to drain, in order (`sleep`, `all-day`). */
    val PLANNED: List<String> =
        HistoryDrainPlan.steps(inBackground = false, allDayOnly = false, sportEnabled = false, now = Instant.EPOCH, nightWindowEnd = null).map { it.label }

    /** Why a channel held records back. */
    enum class HoldReason {
        /** The sync stopped before it opened the channel. */
        NOT_REACHED,

        /** The channel was opened and delivered no record (it did not answer, or was cut before its first page). */
        NO_ANSWER,

        /** The channel delivered records but was cut before its end. */
        NOT_FINISHED,
    }

    /** One channel holding records back, and why. */
    data class HeldBy(val label: String, val reason: HoldReason)

    /**
     * [report] as its log entry. [previous]: the log's earlier entries, oldest first — each
     * channel's continuity is measured against the last counter it delivered in them, a page is a
     * re-offer when it brought nothing new or nothing past that counter, and the capacity verdict
     * knows whether this is the first sync with records. [zone]: where a local day starts.
     * [firmware]: the version the ring reported, or null.
     */
    fun of(report: SyncReport, previous: List<SyncLogEntry>, zone: ZoneId, firmware: String?): SyncLogEntry {
        val channels = report.channels.map { c ->
            val before = previous.flatMap { e -> e.channels.filter { it.label == c.label } }.mapNotNull { it.lastCounter }.maxOrNull()
            val first = c.rounds.firstOrNull()?.startedAt
            val last = c.rounds.lastOrNull()?.finishedAt
            SyncLogChannel(
                label = c.label,
                channel = c.channel,
                verdict = c.verdict,
                syncAcks = c.syncAcks,
                openFallback = c.openFallback == OpenFallback.REAUTH,
                fallbackHelped = c.fallbackHelped,
                firstCountdown = c.firstCountdown,
                lastCountdown = c.lastCountdown,
                pages4c = c.rounds.sumOf { it.page4CCount },
                pages47 = c.rounds.sumOf { it.page47Count },
                pages4d = c.rounds.sumOf { it.page4DCount ?: 0 },
                records = c.records,
                firstCounter = c.firstCounter,
                lastCounter = c.lastCounter,
                exitReason = c.rounds.lastOrNull()?.exitReason,
                endSeen = c.rounds.any { it.endMarkerCount > 0 },
                rounds = c.rounds.size,
                durationMillis = if (first != null && last != null) Duration.between(first, last).toMillis() else null,
                pageGapP50Millis = SyncMeasurement.percentile(c.pageGapsMillis, 50),
                pageGapMaxMillis = c.pageGapsMillis.maxOrNull(),
                ackLatencyP50Millis = SyncMeasurement.percentile(c.ackLatenciesMillis, 50),
                ackLatencyP95Millis = SyncMeasurement.percentile(c.ackLatenciesMillis, 95),
                reoffers = c.pages.count { it.added == 0 || (before != null && it.newest <= before) },
                statusReplies = c.statusReplies,
                drainedThrough = drainedThrough(c, last ?: report.finishedAt),
                continuity = SyncMeasurement.continuity(before, c.firstCounter),
            )
        }
        val dates = report.counters.map(::dateOf)
        val span = if (dates.isEmpty()) null else SyncMeasurement.SyncSpan(dates.min(), dates.max(), report.counters.size)
        val firstSync = previous.none { e -> e.channels.any { it.lastCounter != null } }
        val generation = FirmwareInfo(version = firmware.orEmpty()).generation
        val commit = report.commit
        val entry = SyncLogEntry(
            startedAt = report.startedAt,
            finishedAt = report.finishedAt,
            outcome = report.outcome.name,
            paused = report.paused,
            firmware = firmware,
            channels = channels,
            recordsStored = commit?.records ?: 0,
            heldBack = commit?.heldBack ?: 0,
            nightsStaged = commit?.nightsStaged ?: 0,
            nightsWaiting = commit?.nightsWaiting ?: 0,
            droppedAfterBound = commit?.droppedAfterBound ?: 0,
            pagesUnacknowledged = report.pagesUnacknowledged,
            undeliveredFrames = report.undeliveredFrames,
            oldestRecord = span?.oldest,
            newestRecord = span?.newest,
            recordsPerDay = dates.groupingBy { it.atZone(zone).toLocalDate().toString() }.eachCount().toSortedMap(),
            chargeMarkers = report.channels.flatMap { it.chargeMarkers }.distinct().sorted(),
            capacity = SyncMeasurement.capacity(
                span = span,
                complete = report.outcome == SyncOutcome.COMPLETE,
                gapBefore = channels.any { it.continuity?.kind == SyncMeasurement.ContinuityKind.GAP },
                firstSync = firstSync,
                now = report.finishedAt,
                generation = generation,
            ),
        )
        val planned = report.planned.ifEmpty { PLANNED }
        return entry.copy(heldBackBy = if (entry.heldBack > 0) holding(planned, channels) else emptyList())
    }

    /**
     * The channels [entry]'s commit held records back for, with why: the least drained of the
     * planned channels (a channel the sync learned nothing about holds everything back; else the
     * one drained through the oldest record), as the commit decided it (PORTING.md D-267).
     */
    fun heldBackBy(entry: SyncLogEntry): List<HeldBy> {
        if (entry.heldBack <= 0) return emptyList()
        return entry.heldBackBy.map { label ->
            val c = entry.channels.firstOrNull { it.label == label }
            val reason = when {
                c == null -> HoldReason.NOT_REACHED
                c.lastCounter == null -> HoldReason.NO_ANSWER
                else -> HoldReason.NOT_FINISHED
            }
            HeldBy(label, reason)
        }
    }

    /**
     * How old the oldest record still on the ring may be: for each planned channel, how far the
     * latest sync that learned anything drained it — or, for a channel no kept sync drained, the
     * oldest kept sync's start — and the least of those. Null with no log.
     */
    fun oldestOnRing(entries: List<SyncLogEntry>): Instant? {
        if (entries.isEmpty()) return null
        val since = entries.minOf { it.startedAt }
        return PLANNED.minOf { label ->
            entries.flatMap { e -> e.channels.filter { it.label == label } }.mapNotNull { it.drainedThrough }.maxOrNull() ?: since
        }
    }

    /** The ring's model: from the firmware it reports now, else from the latest logged sync that recorded one. */
    fun generation(liveFirmware: String?, entries: List<SyncLogEntry>): RingGeneration {
        val version = liveFirmware?.takeIf(String::isNotEmpty) ?: entries.lastOrNull { !it.firmware.isNullOrEmpty() }?.firmware
        return FirmwareInfo(version = version.orEmpty()).generation
    }

    /** The planned channels (labels) holding records back: those the commit's bound came from (the same rule as the controller's). */
    private fun holding(planned: List<String>, channels: List<SyncLogChannel>): List<String> {
        val drained = planned.map { label ->
            val c = channels.firstOrNull { it.label == label }
            label to CommitPlanner.drained(c?.verdict, c?.lastCounter)
        }
        val least = CommitPlanner.leastDrained(drained.map { it.second })
        if (least == CommitPlanner.Drained.Everything) return emptyList()
        return drained.filter { it.second == least }.map { it.first }
    }

    /** How far the ring is drained on [c]: to [end] when it ended complete or empty, else to its newest record; null when it told nothing. */
    private fun drainedThrough(c: ChannelReport, end: Instant): Instant? = when {
        c.verdict == HistoryChannelOutcome.COMPLETE || c.verdict == HistoryChannelOutcome.EMPTY -> end
        else -> c.lastCounter?.let(::dateOf)
    }

    private fun dateOf(counter: Long): Instant = Instant.ofEpochSecond(Command.SYNC_EPOCH + counter)
}
