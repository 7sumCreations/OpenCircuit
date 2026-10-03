package io.github.opencircuit.store.codec

import io.github.opencircuit.ringkit.WorkoutRecoveryDecision
import io.github.opencircuit.ringkit.WorkoutSessionRecovery
import io.github.opencircuit.ringkit.WorkoutSessionSnapshot
import io.github.opencircuit.ringkit.WorkoutSportType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The workout snapshot's stored form beyond upstream's two tests.
 *
 * Kotlin-only. Measured on Swift 6.3.2 (`WorkoutSessionRecovery.swift:76-84` @ b1c2fdd):
 * `encoded()` of a NaN energy is nil (nothing written); an unknown sport, `avgHR:"x"` or
 * `activeKcal:"NaN"` decodes as nil; an extra key is ignored; `avgHR:null` is a nil field; -0.0
 * kcal encodes as `-0` and keeps its sign. `hrSampleCount` is a 64-bit Swift `Int` (5e9 decodes
 * there) held as Kotlin `Int`: a stored count past 32 bits is no snapshot here.
 */
class WorkoutSessionSnapshotCodecTest {

    private val t0 = Instant.ofEpochMilli(1_756_400_000_000L)

    private val snap = WorkoutSessionSnapshot(
        sport = WorkoutSportType.RUNNING_OUTDOOR, startDate = t0, lastAliveAt = t0.plusSeconds(1_800),
        hrSampleCount = 120, activeKcal = 88.5, avgHR = 140, maxHR = 171,
    )

    private fun with(key: String, value: String?): String {
        val m = LinkedHashMap(Json.parseToJsonElement(assertNotNull(WorkoutSessionSnapshotCodec.encode(snap))).jsonObject)
        if (value == null) m.remove(key) else m[key] = Json.parseToJsonElement(value)
        return JsonObject(m).toString()
    }

    @Test
    fun theStoredFormLeavesOutAbsentValues() {
        assertEquals(
            """{"activeKcal":88.5,"avgHR":140,"hrSampleCount":120,"lastAliveAt":1756401800000,"maxHR":171,""" +
                """"sport":"runningOutdoor","startDate":1756400000000}""",
            WorkoutSessionSnapshotCodec.encode(snap),
        )
        val bare = snap.copy(activeKcal = null, avgHR = null, maxHR = null)
        assertEquals(
            """{"hrSampleCount":120,"lastAliveAt":1756401800000,"sport":"runningOutdoor","startDate":1756400000000}""",
            WorkoutSessionSnapshotCodec.encode(bare),
        )
        assertEquals(bare, WorkoutSessionSnapshotCodec.decodeOrNull(assertNotNull(WorkoutSessionSnapshotCodec.encode(bare))))
    }

    @Test
    fun aReadingCountPast32BitsIsNoSnapshot() {
        val raw = with("hrSampleCount", "5000000000")
        assertUnreadable(WorkoutSessionSnapshotCodec.decode(raw), raw)
        assertNull(WorkoutSessionSnapshotCodec.decodeOrNull(raw))
        assertEquals(WorkoutRecoveryDecision.NothingToRecover, WorkoutSessionRecovery.decide(WorkoutSessionSnapshotCodec.decodeOrNull(raw), t0))
        for (other in listOf(with("avgHR", "2147483648"), with("maxHR", "-2147483649"))) {
            assertNull(WorkoutSessionSnapshotCodec.decodeOrNull(other), other)
        }
    }

    @Test
    fun negativeZeroKcalKeepsItsSign() {
        val text = assertNotNull(WorkoutSessionSnapshotCodec.encode(snap.copy(activeKcal = -0.0)))
        val kcal = assertNotNull(assertNotNull(WorkoutSessionSnapshotCodec.decodeOrNull(text)).activeKcal)
        assertTrue(kcal == 0.0 && 1.0 / kcal < 0, "the stored -0 kcal must read back as -0.0, not 0.0: $kcal")
    }

    @Test
    fun aNonFiniteEnergyIsNotEncoded() {
        assertNull(WorkoutSessionSnapshotCodec.encode(snap.copy(activeKcal = Double.NaN)))
        assertNull(WorkoutSessionSnapshotCodec.encode(snap.copy(activeKcal = Double.NEGATIVE_INFINITY)))
    }

    @Test
    fun anUnknownSportOrAValueOfTheWrongTypeIsNoSnapshot() {
        for (raw in listOf(
            with("sport", "\"swimming\""), with("avgHR", "\"x\""), with("activeKcal", "\"NaN\""),
            with("startDate", null), with("lastAliveAt", null), with("sport", null), with("hrSampleCount", null),
        )) {
            assertUnreadable(WorkoutSessionSnapshotCodec.decode(raw), raw)
        }
    }

    @Test
    fun anExtraKeyIsIgnoredAndANullOptionalIsAbsent() {
        assertEquals(snap, WorkoutSessionSnapshotCodec.decodeOrNull(with("laterField", "[1,2]")))
        assertNull(assertNotNull(WorkoutSessionSnapshotCodec.decodeOrNull(with("avgHR", "null"))).avgHR)
    }
}
