package io.github.opencircuit.store.codec

import io.github.opencircuit.ringkit.WorkoutRecoveryDecision
import io.github.opencircuit.ringkit.WorkoutSessionRecovery
import io.github.opencircuit.ringkit.WorkoutSessionSnapshot
import io.github.opencircuit.ringkit.WorkoutSportType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The live-workout snapshot survives its stored form, and a blob this build cannot read is no
 * snapshot at all.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/WorkoutSessionRecoveryTests.swift
 * (@ b1c2fdd) `testSnapshotRoundTripsThroughItsPersistedForm` (`:109`) and
 * `testUnreadableBlobIsTreatedAsAbsent` (`:117`); the file's other tests are in `:ringkit`'s
 * `WorkoutSessionRecoveryTest`. Upstream's `encoded()` / `decoded(from: Data?)` are the codec's
 * `encode` / `decodeOrNull(String?)`; the three bytes 00 01 02 are the same three characters.
 */
class WorkoutSessionRecoveryStoredFormTest {

    private val t0 = Instant.ofEpochSecond(1_756_400_000L)

    private fun snapshot(start: Instant, alive: Instant, hrSampleCount: Int = 12, kcal: Double? = 210.0) =
        WorkoutSessionSnapshot(
            sport = WorkoutSportType.WALKING_OUTDOOR, startDate = start, lastAliveAt = alive,
            hrSampleCount = hrSampleCount, activeKcal = kcal, avgHR = 104, maxHR = 131,
        )

    @Test
    fun snapshotRoundTripsThroughItsPersistedForm() {
        val original = snapshot(start = t0, alive = t0.plusSeconds(1234))
        val restored = WorkoutSessionSnapshotCodec.decodeOrNull(assertNotNull(WorkoutSessionSnapshotCodec.encode(original)))
        assertEquals(original, restored)
    }

    @Test
    fun unreadableBlobIsTreatedAsAbsent() {
        assertNull(WorkoutSessionSnapshotCodec.decodeOrNull(null))
        assertNull(WorkoutSessionSnapshotCodec.decodeOrNull("\u0000\u0001\u0002"))
        assertEquals(
            WorkoutRecoveryDecision.NothingToRecover,
            WorkoutSessionRecovery.decide(snapshot = WorkoutSessionSnapshotCodec.decodeOrNull("{}"), now = t0),
        )
    }
}
