package io.github.opencircuit.store.codec

import io.github.opencircuit.ringkit.BatteryTTE
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The battery history's stored form beyond upstream's round trip.
 *
 * Kotlin-only. Measured on Swift 6.3.2: a stored percent of 150 or -3 decodes as stored (the
 * estimator skips unreadable percents itself), 80.5 or one past 64 bits fails the whole array, and
 * one bad element fails the whole array. `percent` is a 64-bit Swift `Int` held as Kotlin `Int`,
 * so one past 32 bits makes the history unreadable.
 */
class BatterySamplesCodecTest {

    @Test
    fun theStoredFormIsPercentAndMillisecondInstant() {
        val h = listOf(BatteryTTE.Sample(80, Instant.EPOCH), BatteryTTE.Sample(78, Instant.ofEpochMilli(10_800_000)))
        assertEquals("""[{"at":0,"percent":80},{"at":10800000,"percent":78}]""", BatterySamplesCodec.encode(h))
        assertEquals("[]", BatterySamplesCodec.encode(emptyList()))
        assertEquals(emptyList(), readable(BatterySamplesCodec.decode("[]")))
    }

    @Test
    fun aPercentOutsideZeroToHundredIsReadAsStored() {
        val h = readable(BatterySamplesCodec.decode("""[{"at":0,"percent":150},{"at":1,"percent":-3}]"""))
        assertEquals(listOf(150, -3), h.map { it.percent })
    }

    @Test
    fun aBadElementOrAPercentPast32BitsMakesTheHistoryUnreadable() {
        for (raw in listOf(
            """[{"at":0,"percent":80.5}]""",
            """[{"at":0,"percent":2147483648}]""",
            """[{"at":0,"percent":80},"x"]""",
            """[{"percent":80}]""",
            """[{"at":0}]""",
            """[{"at":"1970-01-01T00:00:00Z","percent":80}]""",
            """{"at":0,"percent":80}""",
        )) {
            assertUnreadable(BatterySamplesCodec.decode(raw), raw)
        }
    }
}
