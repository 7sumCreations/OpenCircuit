package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Slice-end tracer for the workout engines, in the order the app calls them.
 *
 * Detected workout: the ring's buffered `0x4d` sport pages, drained in two passes with one page sent
 * twice → `HistoricalSportFrame.decode` → `AutomaticWorkoutInbox.rebuild` (which runs
 * `AutomaticWorkoutDetector.detect`) → `AutomaticWorkoutAnnouncement.shouldAnnounce` → the wearer
 * confirms → `AutomaticWorkoutConfirmation.prepare` → a `WorkoutSummary` whose zones and energy come
 * from the held-zone classifier and `Calories.workoutActiveKcal` → the confirmed bout's span keeps it
 * from coming back, even after the two-day prune moves its first record.
 *
 * Live workout cut short: polled live-HR locks → `WorkoutHRGate.shouldRecord` →
 * `WorkoutSessionAggregator` → a heartbeat `WorkoutSessionSnapshot` → the process dies →
 * `WorkoutSessionRecovery.decide` hours later → the offered workout ends at the last heartbeat with the
 * energy the session showed there.
 *
 * The pages are upstream's own fixture (the official-app drain of 2026-07-09, copied verbatim), read on
 * the raw byte path. The live workout is synthetic.
 */
class WorkoutTracerTest {

    private val profile = UserProfile(age = 35, weightKg = 70.0, heightCm = 175.0, sex = BiologicalSex.MALE)

    private fun fixtureFrames(): List<ByteArray> {
        val stream = assertNotNull(javaClass.classLoader.getResourceAsStream("automatic-workout/automatic_workout_20260709.hex"))
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return text.split("\n").filter { it.isNotEmpty() && !it.startsWith("#") }.map { line -> hex(line.trim().replace(" ", "")) }
    }

    private fun decodeAll(frames: List<ByteArray>): List<HistoricalSportFrame.Sample> = frames.flatMap { assertNotNull(HistoricalSportFrame.decode(it)) }

    @Test
    fun aDrainedSportPageBecomesAConfirmedWorkoutSummary() {
        val frames = fixtureFrames()
        assertEquals(17, frames.size)
        val lastEnd = decodeAll(frames).maxOf { it.endDate }
        val now = lastEnd.plusSeconds(2 * 3600) // the inbox is rebuilt two hours after the bout

        // Two drains: pages 0–8, then 8–16 (page 8 retransmitted). Duplicates collapse to one record each.
        val first = AutomaticWorkoutInbox.rebuild(existing = emptyList(), incoming = decodeAll(frames.subList(0, 9)), resolvedSpans = emptyList(), now = now)
        val inbox = AutomaticWorkoutInbox.rebuild(existing = first.samples, incoming = decodeAll(frames.subList(8, 17)), resolvedSpans = emptyList(), now = now)
        assertEquals(153, inbox.samples.size)
        assertEquals(inbox.samples.map { it.cursor }.distinct().size, inbox.samples.size)
        assertEquals(AutomaticWorkoutDetector.detect(inbox.samples), inbox.candidates)

        val candidate = inbox.candidates.single()
        assertEquals(126, candidate.samples.size)
        assertEquals(0x0c421255L, candidate.id)
        assertEquals(1_262.0, candidate.duration)
        assertEquals(null, candidate.suggestedKind) // stationary data: the wearer picks the type
        assertTrue(AutomaticWorkoutAnnouncement.shouldAnnounce(candidate, announcedSpans = emptyList(), now = now))
        assertFalse(AutomaticWorkoutAnnouncement.shouldAnnounce(candidate, announcedSpans = listOf(candidate.cursorSpan), now = now))

        // The wearer confirms it as "other".
        val prepared = AutomaticWorkoutConfirmation.prepare(candidate, sport = WorkoutSportType.OTHER, profile = profile)
        val summary = prepared.summary
        assertEquals(WorkoutSportType.OTHER, summary.sport)
        assertEquals(candidate.start, summary.startDate)
        assertEquals(candidate.end, summary.endDate)
        assertEquals(1_262.0, summary.durationSeconds)
        assertEquals(126, summary.hrSampleCount)
        assertEquals(81, summary.avgHR)
        assertEquals(120, summary.maxHR)
        assertEquals(443L, summary.steps)
        // The HR samples behind it are the bout's own 10-second records, each ending at its record's end.
        assertEquals(candidate.samples.mapNotNull { it.heartRate }, prepared.heartRateSamples.map { it.bpm })
        assertEquals(candidate.samples.map { it.endDate }, prepared.heartRateSamples.map { it.end })
        // Zones: the held classifier over those samples at max HR 220 − 35 = 185, to the bout's end.
        val zones = HRZoneClassifier.timeInZonesHeld(hrSamples = prepared.heartRateSamples, maxHR = 185, sessionEnd = candidate.end)
        assertEquals(zones, summary.zoneBreakdown)
        assertTrue(summary.zoneBreakdown.totalZoneSeconds > 0 && summary.zoneBreakdown.totalZoneSeconds <= summary.durationSeconds)
        // Energy: Keytel over the true duration at the integer average, through the energy module.
        val kcal = Calories.workoutActiveKcal(avgHR = 81, durationSeconds = 1_262.0, profile = profile)
        assertTrue(kcal > 0)
        assertEquals(kcal, summary.estimatedActiveKcal)

        // Once saved, the bout's span is the bookkeeping: it never comes back, not even after the two-day
        // prune eats its head and its first record (the old identity) changes.
        val resolved = listOf(candidate.cursorSpan)
        assertEquals(emptyList(), AutomaticWorkoutInbox.rebuild(inbox.samples, emptyList(), resolvedSpans = resolved, now = now).candidates)
        val later = candidate.start.plusSeconds(AutomaticWorkoutInbox.RETENTION.toLong() + 300)
        val unresolved = AutomaticWorkoutInbox.rebuild(inbox.samples, emptyList(), resolvedSpans = emptyList(), now = later)
        val moved = unresolved.candidates.single()
        assertNotEquals(candidate.id, moved.id, "the prune moved the bout's first record")
        val kept = resolved.prunedToRelevant(now = later)
        assertEquals(resolved, kept)
        assertEquals(emptyList(), AutomaticWorkoutInbox.rebuild(inbox.samples, emptyList(), resolvedSpans = kept, now = later).candidates)
    }

    @Test
    fun aLiveWorkoutCutShortByACrashIsOfferedUpToItsLastHeartbeat() {
        val start = Instant.parse("2026-07-09T18:00:00Z")
        val aggregator = WorkoutSessionAggregator(startDate = start, userAge = profile.age)
        var lastRecordedAt: Instant? = null
        var snapshot: WorkoutSessionSnapshot? = null

        // The ring locks a fresh reading every 4 s; the app polls every 2 s, so every other poll re-reads a
        // held latch. The first poll still holds a resting lock taken 20 s before the workout. HR rises
        // steadily from 100, so the live energy estimate only ever grows.
        val pollsUntilCrash = 600 / 2 // the process dies 10 minutes in
        for (k in 1..pollsUntilCrash) {
            val now = start.plusSeconds(2L * k)
            val lockedAt = if (k == 1) start.minusSeconds(20) else start.plusSeconds(4L * (k / 2))
            val bpm = 100 + (k / 2) / 10
            if (WorkoutHRGate.shouldRecord(liveHRAt = lockedAt, sessionStart = start, lastRecordedAt = lastRecordedAt, now = now)) {
                aggregator.add(HRSample(bpm = bpm, start = lockedAt))
                lastRecordedAt = lockedAt
            }
            // The heartbeat writes the snapshot every 30 s (the last at 9 min 30 s).
            if ((2L * k) % 30 == 0L && k < pollsUntilCrash) {
                val samples = aggregator.collectedSamples
                snapshot = WorkoutSessionSnapshot(
                    sport = WorkoutSportType.STRENGTH_TRAINING, startDate = start, lastAliveAt = now,
                    hrSampleCount = samples.size, activeKcal = aggregator.liveActiveKcal(profile, asOf = now),
                    avgHR = aggregator.currentAvgHR, maxHR = samples.maxOfOrNull { it.bpm },
                )
            }
        }
        val lastBeat = start.plusSeconds(570)
        val heartbeat = assertNotNull(snapshot)
        assertEquals(lastBeat, heartbeat.lastAliveAt)
        // One reading per fresh lock up to the heartbeat — the carried-in resting lock and every held re-read
        // were refused (locks at 4, 8, …, 568 s).
        assertEquals(142, heartbeat.hrSampleCount)
        assertEquals(150, aggregator.collectedSamples.size) // the live session went on to 600 s before dying

        // Nine hours later the app relaunches with only the snapshot.
        val decision = WorkoutSessionRecovery.decide(heartbeat, now = start.plusSeconds(9 * 3600))
        val offered = assertIs<WorkoutRecoveryDecision.Offer>(decision).workout
        assertEquals(lastBeat, offered.end)
        assertEquals(570.0, offered.durationSeconds)
        assertEquals(WorkoutSportType.STRENGTH_TRAINING, offered.sport)
        assertEquals(142, offered.hrSampleCount)
        // The energy offered is the energy the session showed at its last heartbeat, which — for an indoor
        // workout with a rising HR — is what finalizing the session there would have given.
        val atHeartbeat = WorkoutSessionAggregator(startDate = start, userAge = profile.age)
        aggregator.collectedSamples.filter { !it.start.isAfter(lastBeat) }.forEach(atHeartbeat::add)
        val finalized = atHeartbeat.finalize(WorkoutSportType.STRENGTH_TRAINING, endDate = lastBeat, distanceMeters = null, hasRoute = false, profile = profile)
        val kcal = assertNotNull(offered.activeKcal)
        assertTrue(kcal > 0)
        assertEquals(finalized.estimatedActiveKcal, kcal)
        assertEquals(finalized.avgHR, offered.avgHR)
        assertEquals(finalized.maxHR, offered.maxHR)
    }
}
