package io.github.opencircuit.store.codec

import io.github.opencircuit.ringkit.ExportEngine
import io.github.opencircuit.ringkit.ExportEngine.HistorySyncEvidenceRow
import io.github.opencircuit.ringkit.HistoryChannelExitReason
import io.github.opencircuit.ringkit.HistoryChannelTrace
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The decode clause of one export test: a trace stored before the sport counters existed exports
 * without them.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/ExportEngineTests.swift
 * (@ b1c2fdd) `testLegacyChannelSummaryOmitsTheSportCountersEntirely` (`:265`), its decode at
 * `:274`; the export half is in `:ringkit`'s `ExportEngineTest`. The stored JSON is upstream's,
 * translated instant for instant (`"startedAt":0`, seconds since 2001-01-01, is 978307200000 epoch
 * milliseconds). The decoded trace must equal the one `:ringkit`'s
 * `ringkit/src/test/kotlin/io/github/opencircuit/ringkit/ExportEngineTest.kt:338` builds field by
 * field; that construction is copied here (its `FoundationDate.reference(0.0)` is the same
 * instant) rather than shared, because test classes do not cross modules.
 */
class ExportEngineStoredFormTest {

    private val t0 = Instant.ofEpochSecond(1_700_000_000L) // 2023-11-14T22:13:20Z, as the export test

    @Test
    fun legacyChannelSummaryOmitsTheSportCountersEntirely() {
        val legacy = """{"label":"sport","channel":2,"startedAt":978307200000,"sawSyncAck":true,""" +
            """"sawEmptyHistorySignal":false,"page4CCount":0,"page47Count":0,""" +
            """"endMarkerCount":1,"recordsAtStart":0,"recordsAtEnd":0,"exitReason":"endMarker"}"""
        val trace = readable(HistoryChannelTraceCodec.decode(legacy))

        // Copied from ExportEngineTest.kt:338-349.
        val built = HistoryChannelTrace(label = "sport", channel = 2, startedAt = Instant.ofEpochMilli(978_307_200_000L))
        built.sawSyncAck = true
        built.sawEmptyHistorySignal = false
        built.page4CCount = 0
        built.page47Count = 0
        built.page4DCount = null
        built.sportSampleCount = null
        built.endMarkerCount = 1
        built.recordsAtStart = 0
        built.recordsAtEnd = 0
        built.exitReason = HistoryChannelExitReason.END_MARKER
        assertEquals(built, trace)

        val row = HistorySyncEvidenceRow(
            capturedAt = t0, ringID = "ring-1", trigger = "manual",
            sleepCommitted = false, stagedSleepSegments = 0,
            mergedRecordCount = 0, historySampleCount = 0,
            rawRecordBlobBase64 = "", channels = listOf(trace),
        )
        val csv = ExportEngine.historySyncEvidenceCSV(listOf(row))
        assertTrue(csv.contains("sport:empty:4c=0:47=0:50=1:added=0"))
        assertFalse(csv.contains("4d="))
        assertFalse(csv.contains(":sport="))
    }
}
