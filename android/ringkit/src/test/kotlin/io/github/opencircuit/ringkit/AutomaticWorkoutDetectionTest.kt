package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/AutomaticWorkoutDetectionTests.swift
 * (@ b1c2fdd), 12 of 12, each test named after upstream's with its line. Cursors (Swift `UInt32`) are
 * `Long`; every page is built on the raw byte path. The official-app drain replay (`:82`) reads upstream's
 * own fixture, copied verbatim to `src/test/resources/automatic-workout/automatic_workout_20260709.hex`,
 * and decodes every one of its 17 frames.
 */
class AutomaticWorkoutDetectionTest {

    private val epoch = Command.SYNC_EPOCH
    private fun epochPlus(seconds: Long): Double = (epoch + seconds).toDouble()
    private fun seconds1970(t: Instant): Double = secondsBetween(Instant.EPOCH, t)

    /** Real channel-0x02 frame from the 2026-07-11 official-app capture. The header countdown is
     *  0x12 even though this page contains nine records, proving it is not a record-count byte. */
    @Test
    fun testDecodesRealHistoricalSportPage() { // :7
        val hex = "4d 00 12 0c 47 47 fb 57 00 03 6a c7 08 00 0c 47 48 05 59 00 02 1e c7 09 00 0c 47 48 0f 58 00 03 1d c7 06 00 0c 47 48 19 55 00 00 79 c6 04 00 0c 47 48 23 58 00 02 55 bf 04 00 0c 47 48 2d 55 00 01 07 bf 03 00 0c 47 48 37 59 00 01 c4 c7 05 00 0c 47 48 41 59 00 02 9d c7 00 00 0c 47 48 4b 58 00 02 b7 c7 00 00 b5"
        val frame = hex(hex.replace(" ", ""))
        val samples = HistoricalSportFrame.decode(frame)

        assertEquals(9, samples?.size)
        assertEquals(0x0c4747fbL, samples?.first()?.cursor)
        assertEquals(87, samples?.first()?.heartRate)
        assertEquals(0, samples?.first()?.steps)
        assertEquals(0x0c47484bL, samples?.last()?.cursor)
    }

    @Test
    fun testRejectsCorruptOrPartialPages() { // :19
        assertNull(HistoricalSportFrame.decode(bytes(0x4d, 0x00, 0x00, 0x00)))

        val valid = makePage(listOf(sample(cursor = 100, bpm = 80, steps = 12)))
        valid[valid.size - 1] = (valid[valid.size - 1].toInt() xor 0xff).toByte()
        assertNull(HistoricalSportFrame.decode(valid))
    }

    @Test
    fun testReconstructsTenMinuteRetroactivePeriod() { // :27
        val samples = (0 until 60).map {
            sample(cursor = 1_000L + (it + 1) * 10, bpm = 90 + it % 5, steps = 17)
        }
        val candidates = AutomaticWorkoutDetector.detect(samples = samples)

        assertEquals(1, candidates.size)
        assertEquals(600.0, candidates[0].duration, 0.001)
        assertEquals(epochPlus(1_000), seconds1970(candidates[0].start), 0.001)
        assertEquals(AutomaticWorkoutDetector.SuggestedKind.WALKING, candidates[0].suggestedKind)
        assertEquals(1_020L, candidates[0].steps)
        assertEquals(94, candidates[0].maximumHeartRate)
    }

    @Test
    fun testRejectsShortAndSparseFalsePeriods() { // :42
        val short = (0 until 59).map {
            sample(cursor = 2_000L + (it + 1) * 10, bpm = 100, steps = 25)
        }
        assertTrue(AutomaticWorkoutDetector.detect(samples = short).isEmpty())

        val sparse = listOf(sample(cursor = 3_010, bpm = 100, steps = 20), sample(cursor = 3_600, bpm = 105, steps = 20))
        assertTrue(AutomaticWorkoutDetector.detect(samples = sparse, maximumGap = 600.0).isEmpty())
    }

    @Test
    fun testSplitsBoutsAndDeduplicatesRetransmits() { // :55
        val first = (0 until 60).map {
            sample(cursor = 4_000L + (it + 1) * 10, bpm = 95, steps = 25)
        }
        val second = (0 until 60).map {
            sample(cursor = 5_000L + (it + 1) * 10, bpm = 150, steps = 28)
        }
        val retransmit = HistoricalSportFrame.Sample(cursor = first[10].cursor, heartRate = null, steps = first[10].steps)

        val candidates = AutomaticWorkoutDetector.detect(samples = first + listOf(retransmit) + second)
        assertEquals(2, candidates.size)
        assertEquals(60, candidates[0].samples.size)
        assertEquals(AutomaticWorkoutDetector.SuggestedKind.RUNNING, candidates[1].suggestedKind)
    }

    @Test
    fun testHistoricalCommandConstants() { // :71
        assertEquals(0x02, Command.SYNC_CHANNEL_SPORT)
        assertContentEquals(bytes(0xcd, 0x00, 0x00), Command.pageAck4D)
        assertContentEquals(bytes(0x05, 0x23, 0x01, 0x00), Command.automaticSportRecognition(enabled = true))
        assertContentEquals(bytes(0x05, 0x23, 0x00, 0x00), Command.automaticSportRecognition(enabled = false))
    }

    /** Replay every channel-0x02 page from a single official-app drain. The capture contains a
     *  continuous 21:02 stationary workout followed 10:12 later by a 4:30 fragment. This proves the
     *  retroactive start/end reconstruction and minimum-duration rejection against real firmware
     *  bytes rather than the synthetic records used by the boundary-focused tests above. */
    @Test
    fun testReplaysCompleteOfficialAppDrainIntoOneRetroactiveCandidate() { // :82
        val stream = assertNotNull(javaClass.classLoader.getResourceAsStream("automatic-workout/automatic_workout_20260709.hex"))
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val frames = text.split("\n")
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line -> hex(line.trim().replace(" ", "")) }

        assertEquals(17, frames.size)
        val samples = frames.flatMap { frame -> assertNotNull(HistoricalSportFrame.decode(frame)) }
        assertEquals(153, samples.size)

        val candidates = AutomaticWorkoutDetector.detect(samples = samples)
        val candidate = assertNotNull(candidates.firstOrNull())
        assertEquals(1, candidates.size) // the real 4:30 tail stays below the 10-min floor
        assertEquals(126, candidate.samples.size)
        assertEquals(0x0c421255L, candidate.id)
        assertEquals(0x0c421739L, candidate.samples.lastOrNull()?.cursor)
        assertEquals(1_262.0, candidate.duration, 0.001)
        assertEquals(443L, candidate.steps)
        assertEquals(81.6746, candidate.averageHeartRate ?: 0.0, 0.001)
        assertEquals(120, candidate.maximumHeartRate)
        assertNull(candidate.suggestedKind) // stationary/yoga-like data must not become a walk
        assertEquals(epochPlus(0x0c421255L - 10), seconds1970(candidate.start), 0.001)
        assertEquals(epochPlus(0x0c421739L), seconds1970(candidate.end), 0.001)

        val prepared = AutomaticWorkoutConfirmation.prepare(
            candidate = candidate,
            sport = WorkoutSportType.YOGA,
            profile = UserProfile(age = 35, weightKg = 70.0, heightCm = 175.0, sex = BiologicalSex.MALE),
        )
        assertEquals(WorkoutSportType.YOGA, prepared.summary.sport)
        assertEquals(candidate.start, prepared.summary.startDate)
        assertEquals(candidate.end, prepared.summary.endDate)
        assertEquals(126, prepared.summary.hrSampleCount)
        assertEquals(81, prepared.summary.avgHR)
        assertEquals(120, prepared.summary.maxHR)
        assertEquals(443L, prepared.summary.steps)
        assertNull(prepared.summary.distanceMeters)
        assertFalse(prepared.summary.hasRoute)
        assertEquals(candidate.start, prepared.heartRateSamples.firstOrNull()?.start)
        assertEquals(candidate.end, prepared.heartRateSamples.lastOrNull()?.end)
    }

    @Test
    fun testInboxPrunesDeduplicatesAndKeepsResolvedBoutSuppressedWhenItExtends() { // :131
        val nowCursor = 1_000_000L
        val now = Instant.ofEpochSecond(epoch + nowCursor)
        val firstCursor = nowCursor - 1_000
        val bout = (0 until 60).map {
            sample(cursor = firstCursor + it * 10L, bpm = 90, steps = 12)
        }
        val expired = sample(
            cursor = nowCursor - AutomaticWorkoutInbox.RETENTION.toLong() - 1,
            bpm = 70,
            steps = 0,
        )
        val invalidDuplicate = HistoricalSportFrame.Sample(
            cursor = firstCursor,
            heartRate = null,
            steps = 12,
        )

        val initial = AutomaticWorkoutInbox.rebuild(
            existing = listOf(expired, invalidDuplicate),
            incoming = bout,
            resolvedSpans = emptyList(),
            now = now,
        )
        assertEquals(60, initial.samples.size)
        assertEquals(90, initial.samples.firstOrNull()?.heartRate) // valid retransmission wins
        assertEquals(listOf(firstCursor), initial.candidates.map { it.id })

        // Later pages extend the same bout. The extended span still overlaps the resolved span, so
        // it remains gone after a reconnect instead of appearing as a duplicate.
        val resolvedSpan = assertNotNull(initial.candidates.firstOrNull()).cursorSpan
        val extensionSamples = (60 until 66).map {
            sample(cursor = firstCursor + it * 10L, bpm = 95, steps = 13)
        }
        val restored = AutomaticWorkoutInbox.rebuild(
            existing = initial.samples,
            incoming = extensionSamples,
            resolvedSpans = listOf(resolvedSpan),
            now = now,
        )
        assertEquals(66, restored.samples.size)
        assertTrue(restored.candidates.isEmpty())
    }

    /** Field failure 2026-08-17: a 19.7 h stuck bout was dismissed 4× and notified 10× because the
     *  48 h retention prune moved its FIRST cursor on every rebuild, and both the resolved filter
     *  and the notify dedup keyed on that cursor. A dismissed bout must stay dismissed after the
     *  prune eats its head. */
    @Test
    fun testPrunedHeadDoesNotResurrectResolvedBout() { // :179
        val retention = AutomaticWorkoutInbox.RETENTION.toLong()
        val firstCursor = 1_000_000L
        val bout = (0 until 120).map {
            sample(cursor = firstCursor + it * 10L, bpm = 75, steps = 1)
        }
        val dismissedAt = Instant.ofEpochSecond(epoch + firstCursor + 2_000)

        val initial = AutomaticWorkoutInbox.rebuild(existing = emptyList(), incoming = bout, resolvedSpans = emptyList(), now = dismissedAt)
        val dismissedSpan = assertNotNull(initial.candidates.firstOrNull()).cursorSpan
        assertEquals(firstCursor, dismissedSpan.start)

        // Two days later the prune cutoff sits INSIDE the bout: its head cursor has moved forward,
        // but the surviving tail still overlaps the dismissed span → no zombie candidate.
        val later = Instant.ofEpochSecond(epoch + firstCursor + retention + 600)
        // Control: absent the resolved filter the pruned tail DOES still form a candidate, so the
        // suppression assertions below cannot pass vacuously (e.g. if minimumDuration changes).
        val control = AutomaticWorkoutInbox.rebuild(existing = initial.samples, incoming = emptyList(), resolvedSpans = emptyList(), now = later)
        assertFalse(control.samples.isEmpty()) // tail survives the prune…
        assertNotEquals(firstCursor, control.samples.firstOrNull()?.cursor) // …with a MOVED head…
        assertEquals(1, control.candidates.size) // …and would resurrect unsuppressed

        val rebuilt = AutomaticWorkoutInbox.rebuild(existing = initial.samples, incoming = emptyList(), resolvedSpans = listOf(dismissedSpan), now = later)
        assertTrue(rebuilt.candidates.isEmpty()) // full span keeps it dismissed
    }

    /** v1→v2 migration replay of the field failure: every v1 cursor on the affected device was a
     *  PAST prune cutoff, i.e. strictly BEFORE the bout span surviving the next rebuild. A
     *  degenerate point span misses that bout entirely; the retention-widened migration must not. */
    @Test
    fun testLegacyCursorMigrationStillSuppressesAfterHeadPrune() { // :214
        val retention = AutomaticWorkoutInbox.RETENTION.toLong()
        val firstCursor = 2_000_000L
        val bout = (0 until 120).map {
            sample(cursor = firstCursor + it * 10L, bpm = 75, steps = 1)
        }
        // The v1 build recorded the head as it stood back then — the original first cursor.
        val legacyHeadCursor = firstCursor

        // Rebuild once the cutoff has eaten past that recorded head.
        val later = Instant.ofEpochSecond(epoch + firstCursor + retention + 600)
        val survivingSpan = assertNotNull(
            AutomaticWorkoutInbox.rebuild(existing = bout, incoming = emptyList(), resolvedSpans = emptyList(), now = later).candidates.firstOrNull(),
        ).cursorSpan

        // The degenerate point provably fails (the review's MAJOR)…
        assertFalse(CursorSpan(point = legacyHeadCursor).overlaps(survivingSpan))
        // …the widened migration covers it…
        val migrated = CursorSpan.migratedFromLegacyCursor(legacyHeadCursor)
        assertTrue(migrated.overlaps(survivingSpan))
        // …and end-to-end the bout stays dismissed through the inbox and the announcement gate.
        assertTrue(AutomaticWorkoutInbox.rebuild(existing = bout, incoming = emptyList(), resolvedSpans = listOf(migrated), now = later).candidates.isEmpty())
        val candidate = AutomaticWorkoutInbox.rebuild(existing = bout, incoming = emptyList(), resolvedSpans = emptyList(), now = later).candidates[0]
        assertFalse(AutomaticWorkoutAnnouncement.shouldAnnounce(candidate, announcedSpans = listOf(migrated), now = candidate.end.plusSeconds(60)))
    }

    @Test
    fun testSpanPruneDropsOnlyDeadSpans() { // :243
        val nowCursor = 3_000_000L
        val now = Instant.ofEpochSecond(epoch + nowCursor)
        val retention = AutomaticWorkoutInbox.RETENTION.toLong()
        val dead = CursorSpan(start = nowCursor - retention - 1_000, end = nowCursor - retention - 1)
        val live = CursorSpan(start = nowCursor - retention - 1_000, end = nowCursor - retention + 1)
        val fresh = CursorSpan(start = nowCursor - 600, end = nowCursor - 100)
        assertEquals(listOf(live, fresh), listOf(dead, live, fresh).prunedToRelevant(now = now))
    }

    @Test
    fun testAnnouncementGateSuppressesMovedHeadsStaleBoutsAndPassesFreshOnes() { // :254
        val firstCursor = 500_000L
        val bout = (0 until 90).map {
            sample(cursor = firstCursor + it * 10L, bpm = 80, steps = 5)
        }
        val candidate = AutomaticWorkoutDetector.detect(samples = bout)[0]
        val justEnded = candidate.end.plusSeconds(60)

        // Fresh, never announced → announce once.
        assertTrue(AutomaticWorkoutAnnouncement.shouldAnnounce(candidate, announcedSpans = emptyList(), now = justEnded))

        // Same bout with a pruned head (drop the first 30 samples): overlaps the announced span,
        // so it is NOT a new workout.
        val announced = candidate.cursorSpan
        val pruned = AutomaticWorkoutDetector.detect(samples = bout.drop(30))[0]
        assertNotEquals(announced.start, pruned.cursorSpan.start)
        assertFalse(AutomaticWorkoutAnnouncement.shouldAnnounce(pruned, announcedSpans = listOf(announced), now = justEnded))

        // A v1-migrated degenerate point span inside the bout also suppresses it.
        assertFalse(AutomaticWorkoutAnnouncement.shouldAnnounce(pruned, announcedSpans = listOf(CursorSpan(point = firstCursor + 600)), now = justEnded))

        // A bout that ENDED longer than maxAge ago never rates a push, announced or not.
        val stale = addingSeconds(candidate.end, AutomaticWorkoutAnnouncement.MAX_AGE + 1)!!
        assertFalse(AutomaticWorkoutAnnouncement.shouldAnnounce(candidate, announcedSpans = emptyList(), now = stale))

        // A disjoint later bout is genuinely new.
        val secondStart = firstCursor + 10_000
        val second = AutomaticWorkoutDetector.detect(samples = (0 until 90).map {
            sample(cursor = secondStart + it * 10L, bpm = 110, steps = 20)
        })[0]
        assertTrue(AutomaticWorkoutAnnouncement.shouldAnnounce(second, announcedSpans = listOf(announced), now = second.end.plusSeconds(60)))
    }

    private fun sample(cursor: Long, bpm: Int, steps: Int): HistoricalSportFrame.Sample =
        HistoricalSportFrame.Sample(cursor = cursor, heartRate = if (bpm in LiveHR.VALID_BPM) bpm else null, steps = steps)

    /** A raw `0x4d` page built byte by byte (never through the decoder), XOR trailer last. */
    private fun makePage(samples: List<HistoricalSportFrame.Sample>): ByteArray {
        val out = mutableListOf(0x4d, 0x00, 0x00)
        for (s in samples) {
            out += listOf(
                ((s.cursor shr 24) and 0xff).toInt(), ((s.cursor shr 16) and 0xff).toInt(),
                ((s.cursor shr 8) and 0xff).toInt(), (s.cursor and 0xff).toInt(),
                (s.heartRate ?: 0) and 0xff, s.steps and 0xff, 0, 0, 0, 0, 0,
            )
        }
        out += out.fold(0) { x, b -> x xor b }
        return ByteArray(out.size) { out[it].toByte() }
    }
}
