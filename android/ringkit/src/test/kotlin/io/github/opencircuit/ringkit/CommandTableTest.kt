package io.github.opencircuit.ringkit

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Every production TX command (incl. `vibrate`, added in E1 slice 3) equals upstream's literal bytes, carries no XOR
 * trailer, and the legacy auth API (`status1`, `liveHRStart`, `authNonce`/`knownAuthNonces`)
 * does not exist. Checked with plain Java reflection, no kotlin-reflect.
 *
 * Every expected value below is typed by hand from upstream
 * ios/OpenCircuitKit/Sources/OpenCircuitKit/Opcodes.swift (@ b1c2fdd), line cited per row —
 * never copied from Opcodes.kt. A table mirroring the code under test asserts nothing.
 */
class CommandTableTest {
    private class Row(val name: String, val expected: ByteArray, val upstreamLine: Int)

    // Opcodes.swift literal table — zero-arg `Command` members only (builders are tested below).
    private val table = listOf(
        Row("status0", bytes(0x01, 0x00, 0x00), 33),
        Row("syncAll", bytes(0x02, 0x00, 0xFF, 0xFF, 0xFF, 0xFF, 0x00, 0x01, 0x00), 40),
        Row("statusQuery", bytes(0xD0, 0x00, 0x00), 41),
        Row("liveHRMode", bytes(0x06, 0x01, 0x00), 42),
        Row("liveSpO2Mode", bytes(0x06, 0x02, 0x00), 43),
        Row("fetch", bytes(0x07, 0x00, 0x00), 44),
        Row("poll", bytes(0x95, 0x00, 0x00), 45),
        Row("pageAck47", bytes(0xC7, 0x00, 0x00), 46),
        Row("pageAck4C", bytes(0xCC, 0x00, 0x00), 47),
        Row("pageAck4D", bytes(0xCD, 0x00, 0x00), 49),
        Row("heartbeatAck", bytes(0x91, 0x00, 0x00), 55),
        Row("sportStop", bytes(0x06, 0x00, 0x00), 62),
        Row("sportStreamAck", bytes(0xCE, 0x00, 0x00), 64),
        Row("findRingLight", bytes(0x24, 0x01, 0x00), 74),
        Row("findRingLightOff", bytes(0x24, 0x00, 0x00), 77),
        Row("airplaneModeOn", bytes(0x08, 0x04, 0x00), 80),
        Row("osaAssessmentStart", bytes(0x05, 0x22, 0x01), 103),
        Row("osaAssessmentStop", bytes(0x05, 0x22, 0x02), 104),
    )

    // Parameterised `Command.vibrate(pattern)` (Opcodes.swift:95-97). The expected bytes are typed
    // from the 🟢 capture list in S/RingVibration.swift:12-15, the frames a Gen 3 ring buzzed on —
    // not from Opcodes.swift. A function, so the getter-completeness set above is unchanged.
    private class VibrateRow(val pattern: VibrationPattern, val expected: ByteArray, val upstreamLine: Int)

    private val vibrateRows = listOf(
        VibrateRow(VibrationPattern.NOTIFICATION, bytes(0x0B, 0x03, 0x01, 0x64, 0x00), 12),
        VibrateRow(VibrationPattern.LONG, bytes(0x0B, 0x03, 0x02, 0x64, 0x00), 15),
    )

    private val commandClass = Command::class.java

    /** JVM getter name → Kotlin property name: `getPageAck4C` → `pageAck4C`. */
    private fun propertyName(getter: Method): String = getter.name.removePrefix("get").replaceFirstChar { it.lowercaseChar() }

    /** Every public, zero-arg, ByteArray-returning getter on `Command` — i.e. every command literal. */
    private fun commandGetters(cls: Class<*>): Map<String, Method> =
        cls.declaredMethods
            .filter {
                Modifier.isPublic(it.modifiers) && it.parameterCount == 0 &&
                    it.returnType == ByteArray::class.java && it.name.startsWith("get")
            }
            .associateBy(::propertyName)

    private fun read(name: String): ByteArray {
        val getter = commandClass.getMethod("get" + name.replaceFirstChar { it.uppercaseChar() })
        return getter.invoke(Command) as ByteArray
    }

    private val legacyAuthNames = listOf("status1", "liveHRStart", "authNonce", "knownAuthNonces")

    /** Declared members of [cls] whose name (JVM getter prefix stripped) is a legacy-auth name. */
    private fun legacyAuthMembers(cls: Class<*>): List<String> {
        val names = cls.declaredMethods.map { it.name } + cls.declaredFields.map { it.name }
        return names.filter { raw ->
            val n = raw.removePrefix("get").replaceFirstChar { it.lowercaseChar() }
            // `-`/`$` suffixes: Kotlin name mangling and synthetic `$default` bridges.
            legacyAuthNames.any { n == it || n.startsWith("$it-") || n.startsWith("$it$") }
        }
    }

    @Test
    fun everyCommandMatchesUpstreamLiteral() {
        for (row in table) {
            assertContentEquals(
                row.expected, read(row.name),
                "Command.${row.name} vs Opcodes.swift:${row.upstreamLine} — got ${read(row.name).toHex()}",
            )
        }
    }

    @Test
    fun tableIsComplete() {
        val getters = commandGetters(commandClass).keys
        val rows = table.map { it.name }.toSet()
        assertEquals(table.size, rows.size, "duplicate table rows")
        assertEquals(
            rows.sorted(), getters.sorted(),
            "Command getters without a table row: ${getters - rows}; table rows without a getter: ${rows - getters}",
        )
    }

    @Test
    fun legacyAuthApiIsAbsent() {
        assertEquals(emptyList(), legacyAuthMembers(commandClass), "legacy auth API must not be ported (PORTING.md D-1)")
    }

    /** The absence check must be able to fail: a class that HAS the members is flagged. */
    @Suppress("unused")
    private object LegacyCommandStub {
        val status1: ByteArray get() = bytes(0x01, 0x01, 0x31, 0x82, 0x67, 0x00)
        val liveHRStart: List<ByteArray> get() = emptyList()
        fun authNonce(challenge: Int): ByteArray? = null
        val knownAuthNonces: Map<Int, ByteArray> = emptyMap()
    }

    @Test
    fun legacyAuthCheckFlagsAClassThatHasTheMembers() {
        val found = legacyAuthMembers(LegacyCommandStub::class.java)
            .map { it.removePrefix("get").replaceFirstChar { c -> c.lowercaseChar() } }.toSet()
        assertEquals(setOf("status1", "liveHRStart", "authNonce", "knownAuthNonces"), found)
    }

    @Test
    fun vibrateMatchesTheConfirmedCapture() {
        // Every pattern has a row (a third VibrationPattern without a capture fails here).
        assertEquals(VibrationPattern.entries.toSet(), vibrateRows.map { it.pattern }.toSet(), "vibrate rows vs VibrationPattern")
        assertEquals(vibrateRows.size, VibrationPattern.entries.size, "duplicate vibrate rows")
        for (row in vibrateRows) {
            val got = Command.vibrate(row.pattern)
            assertContentEquals(
                row.expected, got,
                "Command.vibrate(${row.pattern}) vs RingVibration.swift:${row.upstreamLine} — got ${got.toHex()}",
            )
        }
    }

    @Test
    fun noTxCommandCarriesAnXorTrailer() {
        // FrameTests.swift:33 generalised. NOT "ends in 0x00": osaAssessmentStart/Stop end in 01/02.
        // Oracle is a local fold, independent of Frame.xorTrailer. Covers the fixed table plus the
        // vibrate frames (their XORs are 0x6d / 0x6e vs a literal 0x00 terminator). Builders with an
        // arbitrary byte parameter (sportStart, syncSince) are excluded: some parameter values make a
        // literal frame coincide with its XOR (sportStart(0x01) → `06 03 01 04 00`, XOR 0x00), which
        // says nothing about checksumming.
        val frames = table.map { "Command.${it.name}" to read(it.name) } +
            vibrateRows.map { "Command.vibrate(${it.pattern})" to Command.vibrate(it.pattern) }
        for ((name, cmd) in frames) {
            val xorOfPreceding = cmd.dropLast(1).fold(0) { acc, b -> acc xor (b.toInt() and 0xFF) }
            assertNotEquals(
                xorOfPreceding, cmd.last().toInt() and 0xFF,
                "$name (${cmd.toHex()}) ends in the XOR of its preceding bytes — TX is never checksummed",
            )
        }
    }

    @Test
    fun gettersReturnFreshArrays() {
        // Getters return fresh arrays: mutating one caller's copy must not change the next read.
        for (row in table) {
            val first = read(row.name)
            first[0] = (first[0].toInt() xor 0xFF).toByte()
            val second = read(row.name)
            assertNotSame(first, second, "Command.${row.name} returned the same array twice")
            assertContentEquals(row.expected, second, "Command.${row.name} changed after a caller mutated its copy")
        }
        val poll = Command.poll
        poll[0] = 0x00
        assertEquals(0x95, Command.poll[0].toInt() and 0xFF)
    }

    @Test
    fun sportStartBuildsTypeAtByte2() {
        // Opcodes.swift:60 — `06 03 <type> 04 00`.
        assertContentEquals(bytes(0x06, 0x03, 0x02, 0x04, 0x00), Command.sportStart(0x02))
        assertContentEquals(bytes(0x06, 0x03, 0x07, 0x04, 0x00), Command.sportStart(0x07))
    }

    @Test
    fun automaticSportRecognitionToggle() {
        // Opcodes.swift:110-112.
        assertContentEquals(bytes(0x05, 0x23, 0x01, 0x00), Command.automaticSportRecognition(enabled = true))
        assertContentEquals(bytes(0x05, 0x23, 0x00, 0x00), Command.automaticSportRecognition(enabled = false))
    }

    @Test
    fun outOfRangeByteParametersAreRejectedNotTruncated() {
        // Swift took UInt8 here; an Int outside 0..255 must fail loudly, never wrap (0x100 → 0x00).
        assertFailsWith<IllegalArgumentException> { Command.sportStart(0x100) }
        assertFailsWith<IllegalArgumentException> { Command.sportStart(-1) }
        assertFailsWith<IllegalArgumentException> { Command.syncSince(Command.SYNC_EPOCH, channel = 0x100) }
        assertFailsWith<IllegalArgumentException> { Command.syncSince(Command.SYNC_EPOCH, channel = -1) }
        // Boundary values are accepted.
        assertEquals(0xFF, Command.sportStart(0xFF)[2].toInt() and 0xFF)
        assertEquals(0xFF, Command.syncSince(Command.SYNC_EPOCH, channel = 0xFF)[6].toInt() and 0xFF)
    }

    private fun cursor(unixSeconds: Long): String = Command.syncSince(unixSeconds).copyOfRange(2, 6).toHex()

    @Test
    fun syncSinceClampEdges() {
        // UInt32(clamping:) — Opcodes.swift:145. Upstream asserts only the happy path + the negative floor.
        val e = Command.SYNC_EPOCH
        assertEquals("00 00 00 00", cursor(e - 1), "one second before the epoch floors to 0")
        assertEquals("00 00 00 00", cursor(e), "the epoch itself is cursor 0")
        assertEquals("00 00 00 01", cursor(e + 1))
        assertEquals("ff ff ff fe", cursor(e + 0xFFFF_FFFEL))
        assertEquals("ff ff ff ff", cursor(e + 0xFFFF_FFFFL), "largest representable cursor")
        assertEquals("ff ff ff ff", cursor(e + 0x1_0000_0000L), "overflow clamps, never wraps to 00 00 00 00")
        assertEquals("ff ff ff ff", cursor(Long.MAX_VALUE))
        assertEquals("00 00 00 00", cursor(Long.MIN_VALUE), "far past floors to 0 — the subtraction must not overflow")
        assertEquals("00 00 00 00", cursor(0))
        // Via the Instant entry point, far past / far future.
        assertEquals("00 00 00 00", Command.syncUpToNow(now = Instant.MIN).copyOfRange(2, 6).toHex())
        assertEquals("ff ff ff ff", Command.syncUpToNow(now = Instant.MAX).copyOfRange(2, 6).toHex())
    }

    @Test
    fun syncConstantsMatchUpstream() {
        assertEquals(1_577_793_600L, Command.SYNC_EPOCH)          // Opcodes.swift:124
        assertEquals(0x00, Command.SYNC_CHANNEL_SLEEP)            // :132
        assertEquals(0x02, Command.SYNC_CHANNEL_SPORT)            // :134
        assertEquals(0x03, Command.SYNC_CHANNEL_ALL_DAY)          // :135
    }

    @Test
    fun opcodeConstantsMatchUpstream() {
        // Opcodes.swift:6-26.
        assertEquals(0x95, Opcode.POLL)
        assertEquals(0x07, Opcode.FETCH_RECORD)
        assertEquals(0xC7, Opcode.PAGE_47)
        assertEquals(0xCC, Opcode.PAGE_4C)
        assertEquals(0x01, Opcode.SESSION_SETUP)
        assertEquals(0x02, Opcode.SYNC_OPEN)
        assertEquals(0x06, Opcode.LIVE_HR_MODE)
        assertEquals(0xD0, Opcode.STATUS_QUERY)
        assertEquals(0x48, Opcode.OSA_PPG)
    }

    @Test
    fun transportConstantsMatchUpstream() {
        // Opcodes.swift:193-211.
        assertEquals(0x0804, Transport.NOTIFY_HANDLE)
        assertEquals(0x0802, Transport.WRITE_HANDLE)
        assertEquals(0x0805, Transport.NOTIFY_CCCD)
        assertEquals("8327ad99-2d87-4a22-a8ce-6dd7971c0437", Transport.DATA_SERVICE_UUID)
        assertEquals("8327ad97-2d87-4a22-a8ce-6dd7971c0437", Transport.NOTIFY_CHAR_UUID)
        assertEquals("8327ad98-2d87-4a22-a8ce-6dd7971c0437", Transport.WRITE_CHAR_UUID)
        assertEquals(listOf("RingConn", "Ring"), Transport.namePrefixes)
    }

    @Test
    fun matchesRingNameIsACaseSensitivePrefixMatch() {
        assertTrue(Transport.matchesRingName("RingConn Gen2-03AD"), "observed advertised name")
        assertTrue(Transport.matchesRingName("Ring"), "bare prefix")
        assertFalse(Transport.matchesRingName("Oura"))
        assertFalse(Transport.matchesRingName(""), "empty name")
        assertFalse(Transport.matchesRingName("ringconn gen2"), "Swift hasPrefix is case-sensitive")
        assertFalse(Transport.matchesRingName("My RingConn"), "prefix, not substring")
    }
}
