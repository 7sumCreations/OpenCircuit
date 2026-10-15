package io.github.opencircuit.app

import io.github.opencircuit.app.details.ConnectionDetailsPresenter
import io.github.opencircuit.app.details.DetailsInput
import io.github.opencircuit.ringkit.HistoryChannelExitReason
import io.github.opencircuit.ringkit.HistoryChannelOutcome
import io.github.opencircuit.ringkit.SyncMeasurement.CapacityKind
import io.github.opencircuit.ringkit.SyncMeasurement.CapacityVerdict
import io.github.opencircuit.ringkit.SyncMeasurement.Continuity
import io.github.opencircuit.ringkit.SyncMeasurement.ContinuityKind
import io.github.opencircuit.ringkit.SyncMeasurement.InconclusiveReason
import io.github.opencircuit.store.SyncLogChannel
import io.github.opencircuit.store.SyncLogEntry
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Connection details shows the sync log (newest first), whether the last sync joined the one
 * before (continuity) and what it says about the ring's storage (the capacity verdict) — the rows
 * the owner copies from the phone to answer how the real ring behaves. Counts, times and the
 * ring's `0x82` bytes only. Record times are the real page's counters (`0c22a16b` is
 * 2026-06-13T22:28:59Z; eleven epochs later, `0c22a7dd`, is 22:56:29Z).
 */
class ConnectionDetailsSyncLogTest {

    private val t0: Instant = Instant.parse("2026-10-08T09:00:00Z")

    private fun channel(label: String, continuity: Continuity?) = SyncLogChannel(
        label = label,
        channel = if (label == "sleep") 0 else 3,
        verdict = HistoryChannelOutcome.COMPLETE,
        syncAcks = listOf("82000082"),
        firstCountdown = 6,
        lastCountdown = 0,
        pages4c = 2,
        records = 12,
        firstCounter = 0x0C22A16BL,
        lastCounter = 0x0C22A7DDL,
        exitReason = HistoryChannelExitReason.END_MARKER,
        endSeen = true,
        rounds = 1,
        durationMillis = 6_100,
        pageGapP50Millis = 2_600,
        pageGapMaxMillis = 2_600,
        ackLatencyP50Millis = 12,
        ackLatencyP95Millis = 31,
        continuity = continuity,
    )

    private fun entry(
        sleep: Continuity? = Continuity(ContinuityKind.CONTIGUOUS, 0),
        allDay: Continuity? = Continuity(ContinuityKind.OVERLAP, -150),
        capacity: CapacityVerdict = CapacityVerdict(CapacityKind.LOWER_BOUND, Duration.ofHours(74), null),
        finishedAt: Instant = t0,
    ) = SyncLogEntry(
        startedAt = finishedAt.minusMillis(12_500),
        finishedAt = finishedAt,
        outcome = "COMPLETE",
        channels = listOf(channel("sleep", sleep), channel("all-day", allDay)),
        recordsStored = 24,
        capacity = capacity,
    )

    private fun rows(log: List<SyncLogEntry>?) = ConnectionDetailsPresenter.present(DetailsInput(syncLog = log)).rows.associate { it.label to it.value }

    @Test
    fun withNoRingThereIsNothingAndWithNoSyncItSaysSo() {
        assertEquals("not available", rows(null)["Last sync continuity"])
        assertEquals("not available", rows(null)["Ring capacity"])
        assertEquals("no sync yet", rows(emptyList())["Last sync continuity"])
        assertEquals("no sync yet", rows(emptyList())["Ring capacity"])
        assertEquals(listOf("none yet"), ConnectionDetailsPresenter.present(DetailsInput(syncLog = emptyList())).syncLog)
    }

    @Test
    fun aSyncThatJoinsOrOverlapsTheLastOneHasNoGap() {
        assertEquals("No gap since last sync", rows(listOf(entry()))["Last sync continuity"])
    }

    @Test
    fun aGapSaysHowMuchIsMissingAndOnWhichChannel() {
        // The gap figure is already past the 150 s the next record was due in.
        val gap = entry(allDay = Continuity(ContinuityKind.GAP, 2 * 3_600 + 3 * 60))
        assertEquals("2 h 3 min missing (all-day channel)", rows(listOf(gap))["Last sync continuity"])
    }

    @Test
    fun aFirstSyncAndASyncWithNoRecordsSaySo() {
        val first = Continuity(ContinuityKind.FIRST_SYNC, null)
        val none = Continuity(ContinuityKind.NO_RECORDS, null)
        assertEquals("First sync", rows(listOf(entry(sleep = first, allDay = first)))["Last sync continuity"])
        assertEquals("No records in the last sync", rows(listOf(entry(sleep = none, allDay = none)))["Last sync continuity"])
    }

    @Test
    fun theCapacityVerdictIsWorded() {
        fun capacity(v: CapacityVerdict) = rows(listOf(entry(capacity = v)))["Ring capacity"]
        assertEquals("at least 3 d 2 h", capacity(CapacityVerdict(CapacityKind.LOWER_BOUND, Duration.ofHours(74), null)))
        assertEquals("about 6 d 0 h — overwrites its oldest records when full", capacity(CapacityVerdict(CapacityKind.OVERWRITES_OLDEST, Duration.ofDays(6), null)))
        assertEquals("about 5 d 4 h — stops recording when full", capacity(CapacityVerdict(CapacityKind.STOPS_WHEN_FULL, Duration.ofHours(124), null)))
        assertEquals("inconclusive — the ring was off or not worn", capacity(CapacityVerdict(CapacityKind.INCONCLUSIVE, Duration.ofDays(2), InconclusiveReason.SPARSE)))
    }

    /** Every reason a verdict is inconclusive has its own words: checked against the enum, not a typed list. */
    @Test
    fun everyInconclusiveReasonIsWorded() {
        val words = InconclusiveReason.entries.map { reason ->
            rows(listOf(entry(capacity = CapacityVerdict(CapacityKind.INCONCLUSIVE, null, reason))))["Ring capacity"].orEmpty()
        }
        words.forEach { assertTrue(it.startsWith("inconclusive — ") && it.length > "inconclusive — ".length, it) }
        assertEquals(words.size, words.toSet().size, "each reason reads differently")
    }

    @Test
    fun theLogListsEachSyncNewestFirstWithWhatEachChannelSaw() {
        val older = entry(finishedAt = t0.minus(Duration.ofHours(5)))
        val ui = ConnectionDetailsPresenter.present(DetailsInput(syncLog = listOf(older, entry())))
        val log = ui.syncLog
        assertEquals("2026-10-08T09:00:00Z · COMPLETE · 24 stored · 0 held back · 12.5 s", log[0])
        assertEquals(
            "  sleep · 0x82 82000082 · fallback no · 4c×2 47×0 4d×0 · 12 records · 2026-06-13T22:28:59Z → 2026-06-13T22:56:29Z · " +
                "countdown 6 → 0 · exit endMarker · end yes · rounds 1 · 6.1 s · gap p50 2.6 s max 2.6 s · ack p50 12 ms p95 31 ms · " +
                "re-offers 0 · status replies 0 · contiguous",
            log[1],
        )
        assertTrue(log[2].startsWith("  all-day · ") && log[2].endsWith(" · overlap"), log[2])
        assertTrue(log[3].startsWith("  capacity at least 3 d 2 h"), log[3])
        assertEquals("2026-10-08T04:00:00Z · COMPLETE · 24 stored · 0 held back · 12.5 s", log[4])
        // The Copy text carries the log too.
        assertTrue(ui.copyText.contains("Sync log\n" + log[0]))
    }

    @Test
    fun heldBackRecordsAndTheirChannelAreInTheLog() {
        val held = entry().copy(outcome = "PARTIAL", heldBack = 12, heldBackBy = listOf("all-day"))
        assertEquals(
            "2026-10-08T09:00:00Z · PARTIAL · 24 stored · 12 held back by all-day · 12.5 s",
            ConnectionDetailsPresenter.present(DetailsInput(syncLog = listOf(held))).syncLog[0],
        )
    }

    @Test
    fun aSyncWithNoMeasurementsReadsAsNoneRatherThanNull() {
        val bare = SyncLogEntry(startedAt = t0, finishedAt = t0, outcome = "NOT_CONNECTED", channels = listOf(SyncLogChannel(label = "sleep", channel = 0)))
        val log = ConnectionDetailsPresenter.present(DetailsInput(syncLog = listOf(bare))).syncLog
        assertTrue(log.size >= 2, log.joinToString("\n"))
        assertTrue(log.none { "null" in it }, log.joinToString("\n"))
    }
}
