package io.github.opencircuit.store.codec

import io.github.opencircuit.ringkit.HistoricalSportFrame
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The stored form of the buffered 10-second sport records.
 *
 * Kotlin-only. Upstream's `HistoricalSportFrame.Sample` is synthesized Codable
 * (ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/AutomaticWorkoutDetection.swift:18 @ b1c2fdd),
 * read as a list with `try?` (ios/OpenCircuit/BLE/RingSession.swift:924): `cursor` a `UInt32`,
 * `heartRate` optional (missing and `null` are no reading), `steps` a 64-bit `Int`, `auxiliary` a
 * `[UInt8]`, all others required. The Kotlin type holds `heartRate` and `steps` as 32-bit `Int`s,
 * so one past 32 bits makes the whole list unreadable, as one bad sample does.
 */
class SportSampleListCodecTest {

    private val samples = listOf(
        HistoricalSportFrame.Sample(cursor = 123, heartRate = 72, steps = 5, auxiliary = byteArrayOf(1, 0x80.toByte(), 0xff.toByte(), 0, 7)),
        HistoricalSportFrame.Sample(cursor = 0xFFFF_FFFFL, heartRate = null, steps = 0),
    )

    @Test
    fun theStoredFormLeavesOutAMissingHeartRate() {
        assertEquals(
            """[{"auxiliary":[1,128,255,0,7],"cursor":123,"heartRate":72,"steps":5},{"auxiliary":[],"cursor":4294967295,"steps":0}]""",
            SportSampleListCodec.encode(samples),
        )
    }

    @Test
    fun samplesRoundTripWithTheirAuxiliaryBytes() {
        val read = readable(SportSampleListCodec.decode(SportSampleListCodec.encode(samples)))
        assertEquals(samples, read)
        assertContentEquals(byteArrayOf(1, 0x80.toByte(), 0xff.toByte(), 0, 7), read[0].auxiliary)
        assertEquals(emptyList(), readable(SportSampleListCodec.decode("[]")))
    }

    @Test
    fun aMissingOrNullHeartRateIsNoReadingAndAnOutOfBandOneIsKeptAsStored() {
        val read = readable(
            SportSampleListCodec.decode(
                """[{"auxiliary":[],"cursor":1,"steps":2},{"auxiliary":[],"cursor":2,"heartRate":null,"steps":3},""" +
                    """{"auxiliary":[],"cursor":3,"heartRate":250,"steps":0}]""",
            ),
        )
        assertEquals(listOf(null, null, 250), read.map { it.heartRate })
    }

    @Test
    fun aValuePast32BitsOrOneBadSampleMakesTheWholeListUnreadable() {
        for (raw in listOf(
            """[{"auxiliary":[],"cursor":1,"heartRate":72,"steps":2147483648}]""",
            """[{"auxiliary":[],"cursor":1,"heartRate":5000000000,"steps":1}]""",
            """[{"auxiliary":[],"cursor":4294967296,"steps":1}]""",
            """[{"auxiliary":[],"cursor":-1,"steps":1}]""",
            """[{"auxiliary":[256],"cursor":1,"steps":1}]""",
            """[{"auxiliary":[-1],"cursor":1,"steps":1}]""",
            """[{"cursor":1,"steps":1}]""",
            """[{"auxiliary":[],"cursor":1,"steps":1},{"auxiliary":[],"cursor":2}]""",
            """[{"auxiliary":[],"steps":1}]""",
            """[{"auxiliary":[],"cursor":1,"heartRate":"72","steps":1}]""",
            """[{"auxiliary":[],"cursor":1,"heartRate":72.5,"steps":1}]""",
            """{"auxiliary":[],"cursor":1,"steps":1}""",
        )) {
            assertUnreadable(SportSampleListCodec.decode(raw), raw)
        }
    }
}
