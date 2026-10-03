package io.github.opencircuit.store.codec

import io.github.opencircuit.ringkit.HistoryChannelExitReason
import io.github.opencircuit.ringkit.HistoryChannelOutcome
import io.github.opencircuit.ringkit.HistoryChannelTrace
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The history-channel trace's stored form beyond upstream's two legacy tests.
 *
 * Kotlin-only. Upstream's trace is synthesized Codable (`HistorySyncAssessment.swift:81-139`
 * @ b1c2fdd): ten non-optional keys (`label, channel, startedAt, sawSyncAck,
 * sawEmptyHistorySignal, page4CCount, page47Count, endMarkerCount, recordsAtStart, recordsAtEnd`),
 * each missing → keyNotFound; ten optional keys, each missing → nil; `channel` / `syncAckFlag` /
 * opcodes are `UInt8`. Measured on Swift 6.3.2. The Kotlin counters are `Int` where Swift's are
 * 64-bit, so a counter past 32 bits makes the trace unreadable.
 */
class HistoryChannelTraceCodecTest {

    private val required = listOf(
        "label", "channel", "startedAt", "sawSyncAck", "sawEmptyHistorySignal",
        "page4CCount", "page47Count", "endMarkerCount", "recordsAtStart", "recordsAtEnd",
    )

    private val optionalKeys = listOf(
        "finishedAt", "syncAckFlag", "openWriteFailed", "fetchNudges", "reopenRound",
        "page4DCount", "sportSampleCount", "firstOpcode", "lastOpcode", "exitReason",
    )

    /** Every field set, none to its default. */
    private fun full(): HistoryChannelTrace {
        val t = HistoryChannelTrace(label = "all-day", channel = 3, startedAt = Instant.ofEpochMilli(1_759_000_000_000L))
        t.finishedAt = Instant.ofEpochMilli(1_759_000_042_500L)
        t.sawSyncAck = true
        t.syncAckFlag = 0xff
        t.sawEmptyHistorySignal = true
        t.openWriteFailed = false
        t.fetchNudges = 2
        t.reopenRound = 1
        t.page4CCount = 40
        t.page47Count = 7
        t.page4DCount = 3
        t.sportSampleCount = 90
        t.endMarkerCount = 1
        t.recordsAtStart = 100
        t.recordsAtEnd = 140
        t.firstOpcode = 0x82
        t.lastOpcode = 0x50
        t.exitReason = HistoryChannelExitReason.END_MARKER
        return t
    }

    private fun fullJson(): JsonObject = Json.parseToJsonElement(HistoryChannelTraceCodec.encode(full())).jsonObject

    private fun with(key: String, value: JsonElement?): String {
        val m = LinkedHashMap(fullJson())
        if (value == null) m.remove(key) else m[key] = value
        return JsonObject(m).toString()
    }

    @Test
    fun aFullTraceAndAFreshTraceRoundTrip() {
        assertEquals(full(), readable(HistoryChannelTraceCodec.decode(HistoryChannelTraceCodec.encode(full()))))
        val fresh = HistoryChannelTrace(label = "sport", channel = 2, startedAt = Instant.ofEpochMilli(0))
        assertEquals(fresh, readable(HistoryChannelTraceCodec.decode(HistoryChannelTraceCodec.encode(fresh))))
    }

    @Test
    fun everyOutcomeAndEveryExitReasonSurviveTheStoredForm() {
        fun trace(setUp: HistoryChannelTrace.() -> Unit) =
            HistoryChannelTrace(label = "all-day", channel = 3, startedAt = Instant.ofEpochMilli(0)).apply(setUp)
        val byOutcome = listOf(
            trace { page4CCount = 1; endMarkerCount = 1 },
            trace { page4CCount = 1 },
            trace { page47Count = 1 },
            trace { page4DCount = 1 },
            trace { sawSyncAck = true },
            trace { openWriteFailed = true },
            trace { exitReason = HistoryChannelExitReason.CANCELLED },
            trace { },
        )
        assertEquals(HistoryChannelOutcome.entries.toSet(), byOutcome.map { it.outcome }.toSet())
        for (t in byOutcome) {
            val back = readable(HistoryChannelTraceCodec.decode(HistoryChannelTraceCodec.encode(t)))
            assertEquals(t, back)
            assertEquals(t.outcome, back.outcome)
        }
        for (reason in HistoryChannelExitReason.entries) {
            val t = trace { exitReason = reason }
            assertEquals(reason, readable(HistoryChannelTraceCodec.decode(HistoryChannelTraceCodec.encode(t))).exitReason)
        }
    }

    @Test
    fun anAbsentOptionalIsNotWritten() {
        val fresh = HistoryChannelTrace(label = "sport", channel = 2, startedAt = Instant.ofEpochMilli(0))
        val keys = Json.parseToJsonElement(HistoryChannelTraceCodec.encode(fresh)).jsonObject.keys
        // A fresh trace counts sport pages from zero, so those two counters are present.
        assertEquals((required + "page4DCount" + "sportSampleCount").toSet(), keys)
    }

    @Test
    fun eachMissingOptionalKeyFromAnEarlierBuildReadsAsNull() {
        for (key in listOf("openWriteFailed", "fetchNudges", "reopenRound", "page4DCount", "sportSampleCount")) {
            val trace = readable(HistoryChannelTraceCodec.decode(with(key, null)))
            val expected = full()
            when (key) {
                "openWriteFailed" -> expected.openWriteFailed = null
                "fetchNudges" -> expected.fetchNudges = null
                "reopenRound" -> expected.reopenRound = null
                "page4DCount" -> expected.page4DCount = null
                "sportSampleCount" -> expected.sportSampleCount = null
            }
            assertEquals(expected, trace, key)
        }
    }

    @Test
    fun everyOptionalKeyMissingAtOnceReadsAsNull() {
        val m = LinkedHashMap(fullJson())
        optionalKeys.forEach { m.remove(it) }
        val trace = readable(HistoryChannelTraceCodec.decode(JsonObject(m).toString()))
        assertNull(trace.finishedAt)
        assertNull(trace.syncAckFlag)
        assertNull(trace.openWriteFailed)
        assertNull(trace.fetchNudges)
        assertNull(trace.reopenRound)
        assertNull(trace.page4DCount)
        assertNull(trace.sportSampleCount)
        assertNull(trace.firstOpcode)
        assertNull(trace.lastOpcode)
        assertNull(trace.exitReason)
    }

    @Test
    fun anExplicitNullOptionalReadsAsNull() {
        assertNull(readable(HistoryChannelTraceCodec.decode(with("page4DCount", JsonNull))).page4DCount)
    }

    @Test
    fun eachMissingRequiredKeyMakesTheTraceUnreadable() {
        for (key in required) {
            val raw = with(key, null)
            assertUnreadable(HistoryChannelTraceCodec.decode(raw), raw, key)
        }
    }

    @Test
    fun aCounterPast32BitsMakesTheTraceUnreadable() {
        val counters = listOf(
            "page4CCount", "page47Count", "endMarkerCount", "recordsAtStart", "recordsAtEnd",
            "fetchNudges", "reopenRound", "page4DCount", "sportSampleCount",
        )
        for (key in counters) {
            val raw = with(key, JsonPrimitive(5_000_000_000L))
            assertUnreadable(HistoryChannelTraceCodec.decode(raw), raw, key)
        }
        assertEquals(Int.MAX_VALUE, readable(HistoryChannelTraceCodec.decode(with("recordsAtEnd", JsonPrimitive(Int.MAX_VALUE)))).recordsAtEnd)
    }

    @Test
    fun aByteFieldOutOfRangeMakesTheTraceUnreadable() {
        for ((key, v) in listOf("channel" to 256, "syncAckFlag" to 256, "firstOpcode" to -1, "lastOpcode" to 256)) {
            val raw = with(key, JsonPrimitive(v))
            assertUnreadable(HistoryChannelTraceCodec.decode(raw), raw, key)
        }
    }

    @Test
    fun anUnknownExitReasonOrAValueOfTheWrongTypeMakesTheTraceUnreadable() {
        for (raw in listOf(
            with("exitReason", JsonPrimitive("gaveUp")),
            with("openWriteFailed", JsonPrimitive("yes")),
            with("label", JsonPrimitive(3)),
            with("startedAt", JsonPrimitive("2001-01-01T00:00:00Z")),
            with("sawSyncAck", JsonPrimitive(1)),
        )) {
            assertUnreadable(HistoryChannelTraceCodec.decode(raw), raw)
        }
    }
}
