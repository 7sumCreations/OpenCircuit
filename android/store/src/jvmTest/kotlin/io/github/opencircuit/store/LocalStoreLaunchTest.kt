package io.github.opencircuit.store

import io.github.opencircuit.ringkit.MetricKind
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Port of upstream ios/OpenCircuitTests/LocalStoreLaunchTests.swift (@ b1c2fdd). Upstream reads
 * the last known heart rate for the launch screen through `LaunchSnapshot.load`, which is
 * `latestSample(kind: .heartRate)`; the rows are inserted directly, as upstream does, not through
 * the ingest path (their 1970 times would not pass its plausibility check).
 */
class LocalStoreLaunchTest {

    /** Upstream `testLaunchSnapshotReadsLastKnownHeartRate` (`:7`). */
    @Test
    fun launchSnapshotReadsLastKnownHeartRate() = runBlocking {
        withInMemoryStore { db ->
            db.sampleDao().insertSamples(
                listOf(
                    StoredSampleEntity(kindRaw = "heartRate", start = Instant.ofEpochSecond(100), end = Instant.ofEpochSecond(100), value = 71.0),
                    StoredSampleEntity(kindRaw = "heartRate", start = Instant.ofEpochSecond(200), end = Instant.ofEpochSecond(200), value = 74.0),
                ),
            )

            val lastHeartRate = LocalStore(db).latestSample(MetricKind.HEART_RATE)

            assertEquals(74.0, lastHeartRate?.value)
        }
    }
}
