package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `0x4c` bulk activity/sleep decode: page split, the two record layouts, the measured-vitals
 * accessors and the HealthKit-bound sample path (../docs/PROTOCOL.md §5.3).
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/BulkSleepTests.swift (@ b1c2fdd)
 * — the 19 decode/sample tests. The motion-timeline test (`:215`) ports with the motion half of
 * `BulkSleep`; the eight sleep-detection/staging tests port with the night half.
 *
 * Fixtures are REAL `0x4c` frames/records from the 2026-06-13 overnight sync (FW FR02.018), typed
 * as the hex literals upstream uses. They are full of bytes ≥ 0x80, so a signed byte read breaks
 * layout, counter and HR — which is why they are kept verbatim.
 */
class BulkSleepTest {

    // :18-22 — a real, XOR-valid 0x4c page: header 4c 00 26, then 6 × 23-byte records, then XOR.
    private val realPage = "4c00260c22a16b55210a7d120a010101010100000402400400000c22a20155000300" +
        "120a010101010100003c00000d01200c22a297540001005f0a010101010100001101b00f" +
        "00440c22a32d6027077b120a010101010100402501c02235a00c22a3c351260577120b01" +
        "0101010108a01000000401300c22a459502d0378120a01010101010160200000040ff0cc"

    // :24-25 — a single hand-verified deep-sleep record (counter 0c22d5bf): HR 68, HRV 77, SpO2 98.
    private val deepSleepRec = "0c22d5bf444d057a620a01010101012aa0000090000004"

    // :62, :154 — idle/unworn template (§5.3): [4:8]=05 00 0c 00, [9]=0a, motion 01×5, [15:22]=00×7.
    private val idleRec = "0c0000000500" + "0c0001" + "0a" + "0101010101" + "00000000000000" + "00"

    // :94, :113 — a real activity epoch; the second copy has its [15:20] intensity tail zeroed.
    private val movingActivityRec = "0c22a16b55210a7d120a01010101010000040240040000"
    private val quietActivityRec = "0c22a16b55210a7d120a01010101010000000000040000"

    private fun record(h: String): BulkRecord = assertNotNull(BulkRecord.of(hex(h)))

    private fun record(b: ByteArray): BulkRecord = assertNotNull(BulkRecord.of(b))

    /** Wall-clock time of a counter, spelled out here rather than through the production epoch. */
    private fun wallClock(counter: Long): Instant = Instant.ofEpochSecond(counter + 1_577_793_600L)

    private fun List<QuantitySample>.kinds(): Set<MetricKind> = map { it.kind }.toSet()

    @Test
    fun pageSplitsIntoSixRecords() { // :28-31
        val recs = BulkSleep.recordsFromPage(hex(realPage))
        assertEquals(6, recs.size, "page body is 138 B = 6 × 23-byte records")
    }

    @Test
    fun invalidPageRejected() { // :33-37
        val bad = hex(realPage)
        bad[bad.size - 1] = (bad.u8(bad.size - 1) xor 0xFF).toByte() // break XOR trailer
        assertTrue(BulkSleep.recordsFromPage(bad).isEmpty(), "bad XOR -> no records")
        assertTrue(BulkSleep.recordsFromPage(hex("8100b031")).isEmpty(), "wrong opcode -> none")
    }

    @Test
    fun sleepVitalsRecordDecode() { // :39-48
        // Deep-sleep epoch confirmed against the app: HR 68 / HRV 77 / SpO2 98.
        val r = record(deepSleepRec)
        assertEquals(BulkRecord.Layout.SLEEP_VITALS, r.layout)
        assertEquals(0x0c22d5bfL, r.counter)
        assertEquals(68, r.heartRate)
        assertEquals(77, r.hrvRMSSD)
        assertEquals(98, r.spo2Percent)
        assertContentEquals(bytes(1, 1, 1, 1, 1), r.motion)
    }

    @Test
    fun confidenceAndFullActivityCounts() { // :50-57
        // deepSleepRec bytes: [6]=0x05 (confidence), [10:20]=01 01 01 01 01 2a a0 00 00 90.
        val r = record(deepSleepRec)
        assertEquals(5, r.confidence)
        assertContentEquals(bytes(1, 1, 1, 1, 1, 0x2a, 0xa0, 0x00, 0x00, 0x90), r.activityCounts)
        // activityCounts' first 5 bytes always equal `motion` — same underlying bytes.
        assertContentEquals(r.motion, r.activityCounts.copyOfRange(0, 5))
    }

    @Test
    fun confidenceNilOnIdleEpoch() { // :59-65
        val r = record(idleRec)
        assertEquals(BulkRecord.Layout.IDLE, r.layout)
        assertNull(r.confidence)
    }

    @Test
    fun counterToWallClock() { // :67-72
        // counter = seconds since the sync epoch (PROTOCOL.md §5.6).
        val r = record(deepSleepRec)
        assertEquals(wallClock(0x0c22d5bfL), r.date())
    }

    @Test
    fun activityVsSleepLayout() { // :74-88
        val recs = BulkSleep.recordsFromPage(hex(realPage))
        // Records [0],[1] are activity epochs ([8]=0x12); record [2] is sleep-vitals ([8]=0x5f).
        assertEquals(BulkRecord.Layout.ACTIVITY, recs[0].layout)
        assertEquals(BulkRecord.Layout.SLEEP_VITALS, recs[2].layout)
        assertEquals(0x5f, recs[2].spo2Percent) // 95 %
        assertEquals(0x54, recs[2].heartRate) // 84 bpm (waking/active in-bed)
        assertNull(recs[2].hrvRMSSD, "HRV byte is 0 here -> no sample")
        // ALL-DAY HR: an activity epoch ALSO carries HR at byte[4] (0x55 = 85 bpm here).
        assertEquals(85, recs[0].heartRate, "activity epoch exposes all-day HR at [4]")
        assertNull(recs[0].hrvRMSSD, "the STRICT accessor stays sleep-vitals-only")
        assertNull(recs[0].spo2Percent, "no SpO2 on an activity epoch ([8] is the activity tag)")
    }

    // HRV/RR recovered from activity epochs, sample path ONLY

    @Test
    fun samplesFromMovingActivityEpochEmitsHRAndRRButNotHRV() { // :92-106
        // An activity epoch emits HR (byte[4]) AND respiratory rate (byte[7]/8), but its HRV
        // (byte[5]=33) is suppressed because [15:20] is non-zero — the ring says this epoch MOVED.
        val r = record(movingActivityRec)
        assertEquals(BulkRecord.Layout.ACTIVITY, r.layout)
        assertFalse(r.motionIntensityTailIsZero, "fixture is a MOVING activity epoch")
        val s = BulkSleep.samples(listOf(r))
        assertEquals(setOf(MetricKind.HEART_RATE, MetricKind.RESPIRATORY_RATE), s.kinds())
        assertEquals(85.0, s.first { it.kind == MetricKind.HEART_RATE }.value)
        assertEquals(15.625, s.first { it.kind == MetricKind.RESPIRATORY_RATE }.value)
        assertNull(r.hrvRMSSD, "the strict accessor is unchanged")
        assertNull(r.measuredHRVRMSSD, "moving activity epoch -> HRV suppressed")
        assertNull(r.spo2Percent, "byte[8] is the activity tag — never SpO2")
    }

    @Test
    fun quietActivityEpochRecoversHRVAndRR() { // :110-120
        // Same epoch with a ZEROED [15:20] tail — the ring's own "nothing moved" verdict.
        val r = record(quietActivityRec)
        assertEquals(BulkRecord.Layout.ACTIVITY, r.layout, "must NOT be the idle template ([4]=0x55, not 0x05)")
        assertTrue(r.motionIntensityTailIsZero)
        assertNull(r.hrvRMSSD, "strict accessor unchanged — detection/staging see nothing new")
        assertNull(r.respiratoryRate, "strict accessor unchanged")
        assertEquals(33, r.measuredHRVRMSSD)
        assertEquals(15.625, r.measuredRespiratoryRate)
        assertEquals(
            setOf(MetricKind.HEART_RATE, MetricKind.HRV_SDNN, MetricKind.RESPIRATORY_RATE),
            BulkSleep.samples(listOf(r)).kinds(),
        )
    }

    @Test
    fun measuredVitalGuardBoundaries() { // :134-148
        fun quiet(hrv: Int, rr: Int): BulkRecord {
            val b = hex(quietActivityRec)
            b[5] = hrv.toByte()
            b[7] = rr.toByte()
            return record(b)
        }
        assertEquals(200, quiet(hrv = 200, rr = 125).measuredHRVRMSSD, "200 ms is the corpus max — kept")
        assertNull(quiet(hrv = 201, rr = 125).measuredHRVRMSSD, "201 ms is byte garbage — dropped")
        assertNull(quiet(hrv = 0, rr = 125).measuredHRVRMSSD, "HRV byte 0 = no measurement")
        assertEquals(4.0, quiet(hrv = 33, rr = 32).measuredRespiratoryRate, "4.0 brpm floor — kept")
        assertNull(quiet(hrv = 33, rr = 31).measuredRespiratoryRate, "3.875 brpm — dropped")
        assertEquals(30.0, quiet(hrv = 33, rr = 240).measuredRespiratoryRate, "30.0 brpm ceiling — kept")
        assertNull(quiet(hrv = 33, rr = 241).measuredRespiratoryRate, "30.125 brpm — dropped")
        assertNull(quiet(hrv = 33, rr = 0).measuredRespiratoryRate, "RR byte 0 = no measurement")
    }

    @Test
    fun idleTemplateYieldsNoMeasuredVitals() { // :152-159
        val r = record(idleRec)
        assertEquals(BulkRecord.Layout.IDLE, r.layout)
        assertNull(r.measuredHRVRMSSD)
        assertNull(r.measuredRespiratoryRate)
        assertTrue(BulkSleep.samples(listOf(r)).isEmpty(), "idle epoch emits nothing (unchanged)")
    }

    @Test
    fun sleepVitalsBandDivergenceIsDeliberate() { // :164-170
        val b = hex(deepSleepRec)
        b[5] = 255.toByte()
        val r = record(b)
        assertEquals(BulkRecord.Layout.SLEEP_VITALS, r.layout)
        assertEquals(255, r.hrvRMSSD, "staging still sees it — its input is provably unchanged")
        assertNull(r.measuredHRVRMSSD, "but 255 ms never becomes a HealthKit sample")
    }

    @Test
    fun movingSleepVitalsEpochStillEmitsHRV() { // :174-181
        val b = hex(deepSleepRec)
        b[15] = 0x40 // non-zero intensity tail on a SLEEP epoch
        val r = record(b)
        assertEquals(BulkRecord.Layout.SLEEP_VITALS, r.layout)
        assertFalse(r.motionIntensityTailIsZero)
        assertEquals(77, r.measuredHRVRMSSD, "quiet gate must NOT apply to sleep-vitals epochs")
    }

    @Test
    fun samplesFromSleepVitals() { // :183-194
        val r = record(deepSleepRec)
        val s = BulkSleep.samples(listOf(r))
        assertEquals(4, s.size, "HR + HRV + SpO2 + respiratory rate")
        val byKind = s.groupBy { it.kind }
        assertEquals(68.0, byKind[MetricKind.HEART_RATE]?.first()?.value)
        assertEquals(77.0, byKind[MetricKind.HRV_SDNN]?.first()?.value)
        assertEquals(0.98, byKind[MetricKind.SPO2]?.first()?.value, "SpO2 emitted as 0…1 fraction")
        assertEquals(15.25, byKind[MetricKind.RESPIRATORY_RATE]?.first()?.value, "RR = byte[7] 0x7a / 8 (🟢)")
        assertEquals(wallClock(0x0c22d5bfL), s.first().start)
    }

    @Test
    fun idleAndStreamSplit() { // :293-301
        // Idle template record: motion 01×5 + zero payload -> .idle, no samples.
        val idle = record("0c099dbf05000c00120a01010101010000000000000000")
        assertEquals(BulkRecord.Layout.IDLE, idle.layout)
        assertTrue(BulkSleep.samples(listOf(idle)).isEmpty())
        // Stream split drops a trailing partial chunk.
        assertEquals(1, BulkSleep.recordsFromStream(hex(deepSleepRec) + bytes(0xff, 0xff)).size)
    }

    // Desaturations keep their vitals (layout no longer gated on the SpO2 value)

    @Test
    fun lowSpO2EpochKeepsVitals() { // :307-315
        val r = record("0c22d5bf444d057a500a01010101012aa0000090000004")
        assertEquals(BulkRecord.Layout.SLEEP_VITALS, r.layout)
        assertEquals(68, r.heartRate)
        assertEquals(77, r.hrvRMSSD)
        assertEquals(80, r.spo2Percent, "80 % is a plausible desaturation (≥70) — emitted")
        val kinds = BulkSleep.samples(listOf(r)).kinds()
        assertTrue(
            kinds.containsAll(setOf(MetricKind.HEART_RATE, MetricKind.HRV_SDNN, MetricKind.SPO2)),
            "all vitals emitted",
        )
    }

    @Test
    fun implausibleSpO2GuardedButHRKept() { // :319-326
        val r = record("0c22d5bf444d057a300a01010101012aa0000090000004")
        assertEquals(BulkRecord.Layout.SLEEP_VITALS, r.layout)
        assertNull(r.spo2Percent, "48 % is implausible → dropped")
        assertEquals(68, r.heartRate, "but HR survives")
        assertEquals(77, r.hrvRMSSD)
    }

    // HR physiological band (the "Resting HR 4 bpm" bug)

    @Test
    fun heartRateRejectsSubPhysiologicalValue() { // :334-344
        val b = hex(deepSleepRec)
        b[4] = 4
        val r = record(b)
        assertEquals(BulkRecord.Layout.SLEEP_VITALS, r.layout)
        assertNull(r.heartRate, "4 bpm is below the 30 bpm physiological floor")
        val samples = BulkSleep.samples(listOf(r))
        assertTrue(samples.none { it.kind == MetricKind.HEART_RATE }, "sub-floor HR must not become a persisted sample")
        assertTrue(
            samples.kinds().containsAll(setOf(MetricKind.HRV_SDNN, MetricKind.SPO2)),
            "HRV/SpO2 of the same epoch still decode",
        )
    }

    @Test
    fun heartRateBandBoundaries() { // :346-355
        val hi = hex(deepSleepRec).also { it[4] = 240.toByte() } // > 220 ceiling → dropped
        assertNull(record(hi).heartRate)
        val below = hex(deepSleepRec).also { it[4] = 29 } // just below the 30 floor → dropped
        assertNull(record(below).heartRate)
        val loEdge = hex(deepSleepRec).also { it[4] = 30 } // 30 floor inclusive
        assertEquals(30, record(loEdge).heartRate)
        val hiEdge = hex(deepSleepRec).also { it[4] = 220.toByte() } // 220 ceiling inclusive
        assertEquals(220, record(hiEdge).heartRate)
    }
}
