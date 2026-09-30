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
 * Port of the E1 subset of upstream ios/OpenCircuitKit/Sources/RingKitVerify/main.swift
 * (@ b1c2fdd): `realFrames` :36-40, framing & commands :44-60, `responseID` :62-68, parse :70-71,
 * live HR :72-83, SpO₂ :85-92, descriptor steps :94-101, skin temperature :103-110, battery +
 * zero-temp :112-117, metric models :155-158, SM3/auth :264-276. Each upstream `check` becomes
 * one assertion; each area is one named test. The rest of the file (epoch sync, analytics,
 * sleep detection …) belongs to E2–E5.
 *
 * Upstream builds the :104/:113/:116 fixtures with an embedded space removed
 * (`"…1019 02ffaf".replacingOccurrences(of: " ", with: "")`); the strings below are the result.
 * Every fixture is a raw hex literal (PL-2026-09-30-m), never built through production code.
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
