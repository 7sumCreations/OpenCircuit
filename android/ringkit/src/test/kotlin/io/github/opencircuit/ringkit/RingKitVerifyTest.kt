package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Codec self-checks against REAL frames from the FR02.018 capture.
 *
 * Port of upstream ios/OpenCircuitKit/Sources/RingKitVerify/main.swift (@ b1c2fdd), in parts.
 * E1: `realFrames` :36-40, framing & commands :44-60, `responseID` :62-68, parse :70-71, live HR
 * :72-83, SpO₂ :85-92, descriptor steps :94-101, skin temperature :103-110, battery + zero-temp
 * :112-117, metric models :155-158, SM3/auth :264-276. E2: helpers `makeFrame` :22-25 and
 * `makeEpochRecord` :26-34, epoch sync Layer A :119-152, `SyncCursor` :160-185, cumulative
 * counters :187-204, bulk `0x4c` decode :305-343. Each upstream `check` becomes one assertion
 * carrying its message; each area is one named test. The rest of the file (analytics, sleep
 * detection …) moves with the epic that ports the code it checks.
 *
 * Upstream builds the :104/:113/:116 fixtures with an embedded space removed
 * (`"…1019 02ffaf".replacingOccurrences(of: " ", with: "")`); the strings below are the result.
 * Every fixture is a raw hex literal, never built through production code.
 */
class RingKitVerifyTest {

    // :36-40 — real validated notify frames from desktop/captures/btsnoop_hci.log.
    private val realFrames = listOf(
        "8100b031", "82000082", "860086", "1500080ab0a7",
        "874e0400000000fd00fd00000000100c0b9e44",
        "104e0100000000fd00fd00000000100c0bffb7",
    )

    // :104 / :113 — morning descriptor, space removed.
    private val morningDescriptor = "104e030000000163016500000000101902ffaf"

    // :116 — same descriptor with both temperature channels zeroed, space removed.
    private val zeroTempDescriptor = "104e030000000000000000000000101902ffaf"

    // :22-25 — opcode + body + XOR trailer. The trailer is computed here, never by the production
    // `Frame.xorTrailer`, so a fault in it cannot make these fixtures agree with it.
    private fun makeFrame(opcode: Int, body: ByteArray): ByteArray {
        val withoutTrailer = bytes(opcode) + body
        return withoutTrailer + bytes(withoutTrailer.fold(0) { acc, b -> acc xor (b.toInt() and 0xFF) })
    }

    // :26-34 — marker, 3-byte big-endian counter, then `fill` up to `size`.
    private fun makeEpochRecord(size: Int, counter: Int, fill: Int = 0x01): ByteArray {
        val head = bytes(0x0C, (counter shr 16) and 0xFF, (counter shr 8) and 0xFF, counter and 0xFF)
        return head + ByteArray(size - head.size) { fill.toByte() }
    }

    // :160-162 — shared by the SyncCursor and cumulative-counter areas, as upstream.
    private val t0 = Instant.ofEpochSecond(1000)
    private val t1 = Instant.ofEpochSecond(2000)
    private val t2 = Instant.ofEpochSecond(3000)

    @Test
    fun framingAndCommands() { // :44-60
        assertEquals(0x31, Frame.xorTrailer(bytes(0x81, 0x00, 0xB0)), "response XOR trailer 81 00 b0 -> 31")
        assertContentEquals(bytes(0x95, 0x00, 0x00), Command.poll, "poll command is verbatim 95 00 00 (not XOR'd)")
        assertContentEquals(
            bytes(0xFF, 0xFF, 0xFF, 0xFF), Command.syncAll.copyOfRange(2, 6), "syncAll cursor = 0xFFFFFFFF",
        )
        assertContentEquals(
            bytes(0x02, 0x00, 0x0c, 0x22, 0x98, 0xc3, 0x00, 0x01, 0x00),
            Command.syncSince(unixSeconds = Command.SYNC_EPOCH + 0x0c2298c3),
            "syncSince builds 02 00 <cursor BE4> 00 01 00 (epoch 1577793600)",
        )
        val nowOpen = Command.syncUpToNow(now = Instant.ofEpochSecond(1_750_000_000))
        assertContentEquals(Command.syncSince(unixSeconds = 1_750_000_000), nowOpen, "syncUpToNow = syncSince(now)")
        assertFalse(nowOpen.contentEquals(Command.syncAll), "syncUpToNow is NOT syncAll (0xFFFFFFFF)")
        for (f in realFrames) assertTrue(Frame.isValid(hex(f)), "real frame validates: $f")

        val bad = hex("8100b031")
        bad[1] = (bad[1].toInt() xor 0xFF).toByte()
        assertFalse(Frame.isValid(bad), "corrupted frame rejected")
        assertFalse(Frame.isValid(ByteArray(0)), "empty rejected")
        assertFalse(Frame.isValid(bytes(0x81)), "single byte rejected")
    }

    @Test
    fun responseIdPairs() { // :62-68
        val pairs = listOf(
            0x01 to 0x81, 0x02 to 0x82, 0x06 to 0x86, 0x07 to 0x87,
            0x95 to 0x15, 0xC7 to 0x47, 0xCC to 0x4C, 0xD0 to 0x50,
        )
        for ((cmd, resp) in pairs) {
            assertEquals(resp, Frame.responseId(cmd), "responseID 0x%x -> 0x%x".format(cmd, resp))
        }
    }

    @Test
    fun parseSplitsOpcodeBodyTrailer() { // :70-71
        assertEquals(
            Frame.Parsed(opcode = 0x81, body = bytes(0x00, 0xB0), trailer = 0x31),
            Frame.parse(hex("8100b031")),
            "parse splits opcode/body/trailer",
        )
    }

    @Test
    fun liveHeartRate() { // :72-83
        assertEquals(91, LiveHR.decode(hex("15005b0ab0f4")), "live HR 0x15 byte[2] -> 91 bpm")
        assertEquals(61, LiveHR.decode(hex("15003d0ab092")), "live HR resting -> 61 bpm")
        assertNull(LiveHR.decodeLocked(hex("1500080ab0a7")), "warm-up sentinel (8) filtered by decodeLocked")
        assertNull(LiveHR.decode(ByteArray(0)), "empty HR -> nil")

        val realHRFrames = listOf(
            "1500080ab0a7", "1500520ab0fd", "1500540ab0fb", "1500580ab0f7",
            "15005a0ab0f5", "15005b0ab0f4", "1500420ab0ed", "15003d0ab092",
        )
        assertEquals(
            listOf(null, 82, 84, 88, 90, 91, 66, 61),
            realHRFrames.map { LiveHR.decodeLocked(hex(it)) },
            "real HR poll stream -> bpm sequence",
        )
    }

    @Test
    fun liveSpO2FromRealFrames() { // :85-92
        val realSpO2Frames = listOf(
            "15010000207afb00000024a1c800600098",
            "150100001615ac0000001a2c8f00600062",
            "15010000102e8000000010be5c00610039",
        )
        assertTrue(realSpO2Frames.all { LiveHR.decode(hex(it)) == null }, "long 15 01 frames yield NO HR (byte[2]=0)")
        assertEquals(listOf(96, 96, 97), realSpO2Frames.map { LiveHR.decodeSpO2(hex(it)) }, "real SpO2 frames -> byte[14] %")
        assertNull(LiveHR.decodeSpO2(hex("15005b0ab0f4")), "short HR frame yields no SpO2")
    }

    @Test
    fun descriptorSteps() { // :94-101
        assertEquals(81, DeviceStatus.steps(hex("105402000051011f012500000000105400ff96")), "descriptor [4:6] -> 81 steps")
        assertEquals(0, DeviceStatus.steps(hex("8754020000000157015700000000105800ff66")), "descriptor with no steps -> 0")
        assertEquals(81, DeviceStatus.steps(hex("8754030000510138013a00000000105402ff3a")), "step count also in 0x87 descriptor")
        assertNull(DeviceStatus.steps(hex("15005b0ab0f4")), "non-descriptor frame -> nil steps")
    }

    @Test
    fun descriptorSkinTemperature() { // :103-110
        val temp = DeviceStatus.skinTemperature(hex(morningDescriptor))
        assertNotNull(temp, "morning descriptor decodes a temperature")
        assertEquals(35.5, temp.channelA, "descriptor [6:8] -> 35.5 °C")
        assertEquals(35.7, temp.channelB, "descriptor [8:10] -> 35.7 °C")
        assertTrue(abs(temp.fahrenheit - 96.08) < 0.01, "skin temp -> 96.08 °F mean, got ${temp.fahrenheit}")
        assertEquals(
            28.7,
            DeviceStatus.skinTemperature(hex("105402000051011f012500000000105400ff96"))?.channelA,
            "just-donned ring reads ~28.7 °C (warming curve)",
        )
        assertNull(DeviceStatus.skinTemperature(hex("15005b0ab0f4")), "non-descriptor frame -> nil temp")
    }

    @Test
    fun descriptorBatteryAndZeroTemperature() { // :112-117
        assertEquals(78, DeviceStatus.battery(hex(morningDescriptor)), "descriptor [1] 0x4e -> 78% battery")
        assertNull(DeviceStatus.battery(hex("15005b0ab0f4")), "non-descriptor frame -> nil battery")
        assertNull(DeviceStatus.skinTemperature(hex(zeroTempDescriptor)), "zero-temp descriptor -> nil (out of band)")
    }

    @Test
    fun epochSyncLayerA() { // :119-152
        val ppgA = makeEpochRecord(size = EpochRecord.PPG_RECORD_SIZE, counter = 0x000100)
        for (i in 9 until 47) ppgA[i] = 0xAA.toByte()
        val ppgB = makeEpochRecord(size = EpochRecord.PPG_RECORD_SIZE, counter = 0x000484)
        val ppgFrame = makeFrame(EpochRecord.PPG_OPCODE, bytes(0x00, 0x03) + ppgA + ppgB)
        val ppgRecords = EpochRecord.parsePPGPage(ppgFrame, streamHighByte = 0x0c)
        assertEquals(2, ppgRecords.size, "0x47 page splits 47-byte records")
        assertEquals(
            Instant.ofEpochSecond(Command.SYNC_EPOCH + 0x0c000100),
            ppgRecords.firstOrNull()?.timestamp,
            "record counter maps to sync epoch Date",
        )
        assertContentEquals(
            ByteArray(38) { 0xAA.toByte() }, ppgRecords.firstOrNull()?.rawPayload, "0x47 exposes 38-byte raw PPG payload",
        )

        val activity = makeEpochRecord(size = EpochRecord.ACTIVITY_RECORD_SIZE, counter = 0x2298c3, fill = 0x00)
        activity[8] = 0x12
        for (i in 0 until 7) activity[15 + i] = (i + 1).toByte()
        val activityFrame = makeFrame(EpochRecord.ACTIVITY_OPCODE, bytes(0x00, 0x00) + activity)
        val activityRecords = EpochRecord.parseActivityPage(activityFrame, streamHighByte = 0x0c)
        assertEquals(1, activityRecords.size, "0x4c routes to final activity page")
        assertEquals(0x12, activityRecords.firstOrNull()?.subtype, "0x4c exposes subtype byte[8]")
        assertContentEquals(
            bytes(1, 2, 3, 4, 5, 6, 7), activityRecords.firstOrNull()?.rawPayload, "0x4c exposes 7-byte raw metric payload",
        )

        val cursorReport = bytes(0x50, 0x00, 0x00, 0x12, 0x0c, 0x22, 0xaa, 0xe4, 0x0c, 0x22, 0xac, 0xb5)
        val report = EpochRecord.parseEndOfHistory(cursorReport)
        assertNotNull(report, "0x50 routes to cursor report")
        assertEquals(0x0c22acb5L, report.cursorTo, "0x50 cursor report decodes no-XOR end cursor")

        val epochSession = EpochSyncSession()
        epochSession.appendActivityPage(activityFrame)
        epochSession.complete(cursorReport)
        assertEquals(
            1,
            epochSession.placeholderQuantitySamples().size,
            "epoch metric decoder emits gated zero-value HR placeholders for worn records",
        )
    }

    @Test
    fun syncCursorIsForwardOnly() { // :160-185
        val cursor = SyncCursor()
        assertNull(cursor.last(MetricKind.HEART_RATE), "fresh cursor: never synced")
        assertTrue(cursor.isNew(MetricKind.HEART_RATE, t0), "any date is new before first sync")

        val batch = listOf(
            QuantitySample(kind = MetricKind.HEART_RATE, start = t1, value = 60.0),
            QuantitySample(kind = MetricKind.HEART_RATE, start = t0, value = 58.0), // out of order
            QuantitySample(kind = MetricKind.SPO2, start = t1, value = 0.97),
        )
        val fresh = cursor.selectNew(batch)
        assertEquals(3, fresh.size, "selectNew keeps all 3 first time")
        // Kept as upstream wrote it. The first alternative can never hold (the batch has one t0),
        // so the check is effectively "the first kept sample is the oldest".
        assertTrue(
            fresh.map { it.start } == listOf(t0, t0, t1) || fresh.firstOrNull()?.start == t0,
            "selectNew sorts by start",
        )
        assertEquals(t1, cursor.last(MetricKind.HEART_RATE), "cursor advanced HR to newest (t1)")
        assertEquals(t1, cursor.last(MetricKind.SPO2), "cursor tracks spo2 independently")

        val resync = cursor.selectNew(
            listOf(
                QuantitySample(kind = MetricKind.HEART_RATE, start = t1, value = 61.0), // equal -> not new
                QuantitySample(kind = MetricKind.HEART_RATE, start = t2, value = 62.0), // newer -> new
            ),
        )
        assertTrue(resync.size == 1 && resync.firstOrNull()?.start == t2, "re-sync drops <= cursor, keeps newer")
        assertEquals(t2, cursor.last(MetricKind.HEART_RATE), "cursor advanced to t2; never backward")
        cursor.advance(MetricKind.HEART_RATE, to = t0)
        assertEquals(t2, cursor.last(MetricKind.HEART_RATE), "advance() never moves cursor backward")
    }

    @Test
    fun cumulativeCounters() { // :187-204
        assertTrue(MetricKind.STEPS.isCumulativeCounter, "steps are treated as cumulative counters")
        assertTrue(MetricKind.ACTIVE_ENERGY.isCumulativeCounter, "active energy is treated as cumulative counter")
        val stepsFirst = CumulativeMetricAccumulator.accumulate(
            QuantitySample(kind = MetricKind.STEPS, start = t0, value = 100.0),
            CumulativeMetricState(),
        )
        val stepsSecond = CumulativeMetricAccumulator.accumulate(
            QuantitySample(kind = MetricKind.STEPS, start = t1, value = 140.0),
            CumulativeMetricState(previousRawValue = stepsFirst.rawValue, dailyTotal = stepsFirst.dailyTotal),
        )
        assertTrue(
            stepsFirst.deltaValue == 100.0 && stepsFirst.dailyTotal == 100.0, "first cumulative sample uses raw as delta",
        )
        assertTrue(
            stepsSecond.deltaValue == 40.0 && stepsSecond.dailyTotal == 140.0,
            "cumulative sample stores delta and running total",
        )
        val rolledSteps = CumulativeMetricAccumulator.accumulate(
            QuantitySample(kind = MetricKind.STEPS, start = t2, value = 12.0),
            CumulativeMetricState(previousRawValue = 250.0, dailyTotal = 250.0),
        )
        assertTrue(
            rolledSteps.deltaValue == 12.0 && rolledSteps.dailyTotal == 262.0, "counter rollover uses raw value as delta",
        )
    }

    // :306-310 — a real, XOR-valid 0x4c page from the 2026-06-13 overnight sync: 6 × 23-byte records.
    private val realPage = "4c00260c22a16b55210a7d120a010101010100000402400400000c22a20155000300" +
        "120a010101010100003c00000d01200c22a297540001005f0a010101010100001101b00f" +
        "00440c22a32d6027077b120a010101010100402501c02235a00c22a3c351260577120b01" +
        "0101010108a01000000401300c22a459502d0378120a01010101010160200000040ff0cc"

    @Test
    fun bulkPageSplitsAndKeysLayout() { // :305-315
        val pageRecs = BulkSleep.recordsFromPage(hex(realPage))
        assertEquals(6, pageRecs.size, "0x4c page splits into 6 × 23-byte records")
        assertTrue(
            pageRecs.getOrNull(2)?.layout == BulkRecord.Layout.SLEEP_VITALS &&
                pageRecs.getOrNull(0)?.layout == BulkRecord.Layout.ACTIVITY,
            "record [8] keys layout: sleep-vitals vs activity",
        )
    }

    @Test
    fun deepSleepEpochVitals() { // :316-326 — confirmed against the app: HR 68 / HRV 77 / SpO2 98.
        val dsr = assertNotNull(BulkRecord.of(hex("0c22d5bf444d057a620a01010101012aa0000090000004")))
        assertEquals(68, dsr.heartRate, "sleep-vitals [4] -> HR 68 bpm (🟢 app-confirmed)")
        assertEquals(77, dsr.hrvRMSSD, "sleep-vitals [5] -> HRV 77 ms (🟢)")
        assertEquals(98, dsr.spo2Percent, "sleep-vitals [8] -> SpO2 98% (🟢)")
        assertEquals(15.25, dsr.respiratoryRate, "sleep-vitals [7] 0x7a/8 -> RR 15.25 brpm (🟢, app avg 15.1)")
        assertEquals(0x0c22d5bfL, dsr.counter, "record [0:4] -> BE counter")
        val dsSamples = BulkSleep.samples(listOf(dsr))
        assertEquals(4, dsSamples.size, "sleep-vitals -> HR + HRV + SpO2 + RR samples")
        assertEquals(0.98, dsSamples.firstOrNull { it.kind == MetricKind.SPO2 }?.value, "SpO2 emitted as 0…1 fraction")
        assertEquals(
            15.25, dsSamples.firstOrNull { it.kind == MetricKind.RESPIRATORY_RATE }?.value, "RR sample emitted",
        )
    }

    @Test
    fun idleTemplateYieldsNoSamples() { // :327-329
        val idleRec = assertNotNull(BulkRecord.of(hex("0c099dbf05000c00120a01010101010000000000000000")))
        assertTrue(
            idleRec.layout == BulkRecord.Layout.IDLE && BulkSleep.samples(listOf(idleRec)).isEmpty(),
            "idle template -> no samples",
        )
    }

    /**
     * :330-345 — HRV and RR ARE carried on `0x12` activity epochs (upstream issue #185). HRV is
     * admitted only when the ring's own `[15:20]` intensity tail says the epoch was QUIET; RR is
     * motion-insensitive. Asserted through the public `samples` surface, plus the strict
     * accessors, which must be untouched on both, and `sleepVitalTimeline`, which the recovered
     * HRV must never seed.
     */
    @Test
    fun activityEpochHrvAndRrRecovery() {
        val quietAct = assertNotNull(BulkRecord.of(hex("0c22a16b55210a7d120a01010101010000000000040000")))
        val movingAct = assertNotNull(BulkRecord.of(hex("0c22a16b55210a7d120a01010101010000040240040000")))
        val qs = BulkSleep.samples(listOf(quietAct)).map { it.kind }.toSet()
        val ms = BulkSleep.samples(listOf(movingAct)).map { it.kind }.toSet()
        assertEquals(
            setOf(MetricKind.HEART_RATE, MetricKind.HRV_SDNN, MetricKind.RESPIRATORY_RATE),
            qs,
            "#185: QUIET activity epoch -> HR + HRV + RR",
        )
        assertEquals(
            setOf(MetricKind.HEART_RATE, MetricKind.RESPIRATORY_RATE), ms, "#185: MOVING activity epoch -> HR + RR, HRV suppressed",
        )
        assertTrue(
            quietAct.hrvRMSSD == null && movingAct.hrvRMSSD == null, "#185: the strict sleep-vitals accessor is UNCHANGED on both",
        )
        assertTrue(
            BulkSleep.sleepVitalTimeline(listOf(quietAct, movingAct)).isEmpty(), "#185: recovered HRV never seeds sleep detection",
        )
    }

    @Test
    fun metricKindUnitsAndQuantitySampleEndDefaultsToStart() { // :155-158
        assertEquals("fraction", MetricKind.SPO2.unit, "spo2 unit is fraction (HealthKit 0…1)")
        assertEquals("count/min", MetricKind.HEART_RATE.unit, "heartRate unit count/min")
        val inst = QuantitySample(kind = MetricKind.HEART_RATE, start = Instant.ofEpochSecond(100), value = 72.0)
        assertEquals(inst.start, inst.end, "instantaneous sample: end defaults to start")
    }

    @Test
    fun sm3AndRingAuth() { // :264-276
        assertEquals(
            "66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0",
            SM3.hash("abc".toByteArray(Charsets.UTF_8)).toHex().replace(" ", ""),
            "SM3('abc') KAT",
        )
        val authMac = bytes(0xf8, 0x79, 0x99, 0xf7, 0x03, 0xad) // F8:79:99:F7:03:AD -> V = 0x59
        assertEquals(0x59, RingAuth.macTailXor(authMac), "RingAuth V = 0x59")
        for ((ch, exp) in listOf(0x0f to "4bcce6", 0xb0 to "318267", 0xe5 to "520be1", 0xf9 to "3609b2", 0x52 to "277d7f")) {
            assertEquals(exp, RingAuth.response(ch, authMac).toHex().replace(" ", ""), "RingAuth f(%02x)=%s".format(ch, exp))
        }
        assertContentEquals(
            bytes(0x01, 0x01, 0x31, 0x82, 0x67, 0x00),
            RingAuth.authCommand(challenge = 0xb0, mac = authMac),
            "RingAuth.authCommand = 01 01 31 82 67 00 for challenge 0xb0",
        )
        assertContentEquals(
            authMac,
            RingAuth.macFromSystemID(bytes(0xf8, 0x79, 0x99, 0xff, 0xfe, 0xf7, 0x03, 0xad)),
            "macFromSystemID parses EUI-64",
        )
    }
}
