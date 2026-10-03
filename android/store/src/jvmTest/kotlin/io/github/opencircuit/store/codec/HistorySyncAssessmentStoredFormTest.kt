package io.github.opencircuit.store.codec

import io.github.opencircuit.ringkit.HistoryChannelOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A history-channel trace stored by an earlier build still reads back.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/HistorySyncAssessmentTests.swift
 * (@ b1c2fdd), the two stored-form tests `:137` and `:152`; the file's other tests are in
 * `:ringkit`'s `HistorySyncAssessmentTest`. The fixtures are upstream's JSON translated instant for
 * instant: same keys and values, with `"startedAt":0` (seconds since 2001-01-01, Foundation's
 * date form) written as the same instant in epoch milliseconds, 978307200000 — the store's form.
 */
class HistorySyncAssessmentStoredFormTest {

    @Test
    fun legacyTraceJsonWithoutTheNewFieldStillDecodes() {
        val legacy = """{"label":"all-day","channel":3,"startedAt":978307200000,"sawSyncAck":false,""" +
            """"sawEmptyHistorySignal":false,"page4CCount":0,"page47Count":0,""" +
            """"endMarkerCount":0,"recordsAtStart":0,"recordsAtEnd":0}"""
        val trace = readable(HistoryChannelTraceCodec.decode(legacy))
        assertNull(trace.openWriteFailed)
        assertEquals(HistoryChannelOutcome.NO_ACK, trace.outcome)
    }

    @Test
    fun legacyTraceJsonWithoutTheSportCountersStillDecodes() {
        val legacy = """{"label":"sport","channel":2,"startedAt":978307200000,"sawSyncAck":true,""" +
            """"sawEmptyHistorySignal":false,"page4CCount":0,"page47Count":0,""" +
            """"endMarkerCount":1,"recordsAtStart":0,"recordsAtEnd":0,"exitReason":"endMarker"}"""
        val trace = readable(HistoryChannelTraceCodec.decode(legacy))
        assertNull(trace.page4DCount)
        assertNull(trace.sportSampleCount)
        // …and null must classify exactly as the code did before the counters existed.
        assertEquals(HistoryChannelOutcome.EMPTY, trace.outcome)
    }
}
