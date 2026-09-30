package io.github.opencircuit.ringkit

// Whether a finished history drain may stage and persist the night. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/HistoryCommitGate.swift:26-83 (@ b1c2fdd).
//
// THE TWO RULES IT ENCODES
//
//  1. CONSERVATIVE STAGING. A partial / PPG-only / no-ack sleep drain must NOT overwrite a fuller
//     stored night, so staging requires `outcome == COMPLETE` (see
//     [HistoryChannelOutcome.allowsSleepCommit]) AND some fresh records to stage from.
//
//  2. THE ADOPTED-NIGHT RESCUE. `outcome` describes what THIS drain pulled off the wire and says
//     nothing about records the app ACKed earlier with no drain open (see [UnattributedPageBuffer]).
//     Upstream saw a tester's ring stream a whole 7.8 h night to a closed gate, after which the
//     drain's own sleep channel came back `EMPTY`. So an adopted set earns a RE-STAGE FROM THE
//     ARCHIVE UNION — deliberately the weaker action, because it reads the merged union and goes
//     through a merge that can only GROW a stored night, never shrink the fuller one rule 1 protects.

import java.time.Duration
import java.time.Instant

object HistoryCommitGate {

    enum class Decision(val rawValue: String) {
        /** Stage from this drain's night slice and persist (the full commit). */
        STAGE("stage"),

        /** Do not stage this slice, but re-stage from the persisted archive union (the rescue path). */
        RESTAGE_FROM_ARCHIVE("restageFromArchive"),

        /** Neither. Raw records are still kept; only staging is withheld. */
        SKIP("skip"),
    }

    /**
     * @param outcome this drain's SLEEP-channel outcome (null when the channel never ran).
     * @param recordsAdded records this drain pulled off the wire itself.
     * @param adoptedRecordCount records adopted from the unattributed buffer — already ACKed, already
     *   merged into the archive, simply older than this drain's own trace.
     * @param nightRecordsOnOtherChannels sleep-vitals-layout records THIS drain pulled on a channel
     *   other than sleep (🟡 a Gen 3 FR05.011 ring handed its night to the all-day channel in 3 of 4
     *   morning drains). They take the grow-only [Decision.RESTAGE_FROM_ARCHIVE] path, never [Decision.STAGE].
     */
    fun decide(
        outcome: HistoryChannelOutcome?,
        recordsAdded: Int,
        adoptedRecordCount: Int,
        nightRecordsOnOtherChannels: Int = 0,
    ): Decision {
        val hasFreshRecords = recordsAdded > 0 || adoptedRecordCount > 0
        if (outcome?.allowsSleepCommit == true && hasFreshRecords) return Decision.STAGE
        // A drain that never ran its sleep channel (`outcome == null`) has no breadcrumb to reason
        // from, but adopted records are self-evidently real — rescue them either way.
        if (adoptedRecordCount > 0 || nightRecordsOnOtherChannels > 0) return Decision.RESTAGE_FROM_ARCHIVE
        return Decision.SKIP
    }

    /**
     * Slack after the night window's end within which an off-channel sleep-vitals record still
     * counts as night: generous enough for a late wake, short enough to exclude the all-day
     * channel's routine ~10-min daytime SpO₂ epochs (same layout). 2 h.
     *
     * iOS-tuned — re-check in E9/E11. Ported verbatim from upstream (`:64`).
     */
    val OFF_CHANNEL_NIGHT_LATE_MARGIN: Duration = Duration.ofHours(2)

    private val ONE_DAY: Duration = Duration.ofSeconds(86_400)

    /**
     * Does an off-channel sleep-vitals record at [date] belong to a NIGHT (and so may earn a
     * restage)? [window] is the app's cached night window, which may be last night's or tonight's,
     * so both it and the same window one day earlier are tried, each extended by [lateMargin].
     * Without a window nothing can be ruled out: `true`.
     *
     * Both ends are INCLUSIVE, as upstream's explicit `>=` / `<=` are: a record exactly on the
     * window's start, or exactly on its end plus [lateMargin], counts as night.
     */
    fun isNightRecord(date: Instant, window: DateInterval?, lateMargin: Duration = OFF_CHANNEL_NIGHT_LATE_MARGIN): Boolean {
        if (window == null) return true
        return listOf(Duration.ZERO, ONE_DAY.negated()).any { shift ->
            !date.isBefore(window.start.plus(shift)) && !date.isAfter(window.end.plus(shift).plus(lateMargin))
        }
    }
}
