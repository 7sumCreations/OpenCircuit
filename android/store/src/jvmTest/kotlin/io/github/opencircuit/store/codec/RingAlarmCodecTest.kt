package io.github.opencircuit.store.codec

import io.github.opencircuit.ringkit.RingAlarm
import io.github.opencircuit.ringkit.VibrationPattern
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The ring alarm's stored form beyond upstream's round trip.
 *
 * Kotlin-only. Upstream's alarm is synthesized Codable, measured on Swift 6.3.2: every key is
 * required; `pattern` is a `UInt8` raw value (`3` unreadable, `"long"` unreadable); weekdays are
 * a `Set`, so `[9,9,0]` reads as `{0, 9}` and an out-of-range day is kept (it never fires); an hour
 * of -1 or a minute of 99 is kept; NaN cannot be encoded. Foundation writes the set in a
 * different order from run to run; here the days are written in ascending order, so the same
 * alarm always has the same stored text. Hour, minute, burst count and each day are 64-bit Swift
 * `Int`s held as Kotlin `Int`, so one past 32 bits makes the alarm unreadable.
 */
class RingAlarmCodecTest {

    private val alarm = RingAlarm(
        isEnabled = true, hour = 6, minute = 45, weekdays = setOf(5, 3, 2, 4, 6),
        pattern = VibrationPattern.LONG, burstCount = 4, burstSpacing = 6.5, backupNotification = false,
    )

    private fun with(key: String, value: String?): String {
        val m = LinkedHashMap(Json.parseToJsonElement(assertNotNull(RingAlarmCodec.encode(alarm))).jsonObject)
        if (value == null) m.remove(key) else m[key] = Json.parseToJsonElement(value)
        return JsonObject(m).toString()
    }

    @Test
    fun theStoredFormWritesTheDaysInAscendingOrder() {
        assertEquals(
            """{"backupNotification":false,"burstCount":4,"burstSpacing":6.5,"hour":6,"isEnabled":true,""" +
                """"minute":45,"pattern":2,"weekdays":[2,3,4,5,6]}""",
            RingAlarmCodec.encode(alarm),
        )
    }

    @Test
    fun theDefaultAlarmRoundTrips() {
        assertEquals(RingAlarm(), readable(RingAlarmCodec.decode(assertNotNull(RingAlarmCodec.encode(RingAlarm())))))
    }

    @Test
    fun repeatedAndOutOfRangeValuesAreKeptAsStored() {
        assertEquals(setOf(0, 9), readable(RingAlarmCodec.decode(with("weekdays", "[9,9,0]"))).weekdays)
        val odd = readable(
            RingAlarmCodec.decode(
                """{"backupNotification":true,"burstCount":3,"burstSpacing":4,"hour":-1,"isEnabled":false,""" +
                    """"minute":99,"pattern":1,"weekdays":[]}""",
            ),
        )
        assertEquals(-1, odd.hour)
        assertEquals(99, odd.minute)
        assertEquals(4.0, odd.burstSpacing)
    }

    @Test
    fun anUnknownPatternOrAPatternByNameIsUnreadable() {
        for (raw in listOf(with("pattern", "3"), with("pattern", "\"long\""), with("pattern", "0"))) {
            assertUnreadable(RingAlarmCodec.decode(raw), raw)
        }
    }

    @Test
    fun eachMissingKeyIsUnreadable() {
        for (key in listOf("backupNotification", "burstCount", "burstSpacing", "hour", "isEnabled", "minute", "pattern", "weekdays")) {
            val raw = with(key, null)
            assertUnreadable(RingAlarmCodec.decode(raw), raw, key)
        }
    }

    @Test
    fun aValuePast32BitsIsUnreadable() {
        for ((key, v) in listOf("hour" to "5000000000", "minute" to "2147483648", "burstCount" to "5000000000", "weekdays" to "[2,4294967298]")) {
            val raw = with(key, v)
            assertUnreadable(RingAlarmCodec.decode(raw), raw, key)
        }
    }

    @Test
    fun aNonFiniteBurstSpacingIsNotEncodedAndIsNeverRead() {
        assertNull(RingAlarmCodec.encode(alarm.copy(burstSpacing = Double.NaN)))
        assertNull(RingAlarmCodec.encode(alarm.copy(burstSpacing = Double.POSITIVE_INFINITY)))
        for (raw in listOf(with("burstSpacing", "\"NaN\""), with("burstSpacing", "1e400"))) {
            assertUnreadable(RingAlarmCodec.decode(raw), raw)
        }
    }
}
