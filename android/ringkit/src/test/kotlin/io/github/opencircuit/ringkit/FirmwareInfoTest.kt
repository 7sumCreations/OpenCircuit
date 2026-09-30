package io.github.opencircuit.ringkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for `FirmwareInfo` / `RingGeneration` — port of upstream
 * ios/OpenCircuitKit/Tests/OpenCircuitKitTests/FirmwareInfoTests.swift (@ b1c2fdd), all 14 tests,
 * upstream line cited per test. The last two tests are Kotlin-only additions (A3): the
 * user-facing `rawValue` strings and the prefix match's case-sensitivity / anchoring.
 */
class FirmwareInfoTest {

    // MARK: - Generation detection (FirmwareInfoTests.swift:8-41)

    @Test
    fun gen1Prefix() { // :8-12
        val info = FirmwareInfo()
        info.version = "FR01.010"
        assertEquals(RingGeneration.GEN1, info.generation)
    }

    @Test
    fun gen2Prefix() { // :14-18
        val info = FirmwareInfo()
        info.version = "FR02.018"
        assertEquals(RingGeneration.GEN2, info.generation)
    }

    @Test
    fun gen2AirPrefix() { // :20-24
        val info = FirmwareInfo()
        info.version = "FR04.003"
        assertEquals(RingGeneration.GEN2_AIR, info.generation)
    }

    @Test
    fun gen3Prefix() { // :26-30
        val info = FirmwareInfo()
        info.version = "FR05.008" // RingConn Gen3-C384
        assertEquals(RingGeneration.GEN3, info.generation)
    }

    @Test
    fun unknownPrefix() { // :32-36
        val info = FirmwareInfo()
        info.version = "FR99.001"
        assertEquals(RingGeneration.UNKNOWN, info.generation)
    }

    @Test
    fun emptyVersionIsUnknown() { // :38-41
        val info = FirmwareInfo()
        assertEquals(RingGeneration.UNKNOWN, info.generation)
    }

    // MARK: - hasFirmwareMismatch (:45-73)

    @Test
    fun exactPinnedVersionNoMismatch() { // :45-49
        val info = FirmwareInfo()
        info.version = FirmwareInfo.PINNED_VERSION // "FR02.018"
        assertFalse(info.hasFirmwareMismatch)
    }

    @Test
    fun versionStartingWithPinnedNoMismatch() { // :51-55
        val info = FirmwareInfo()
        info.version = "FR02.018.extra"
        assertFalse(info.hasFirmwareMismatch)
    }

    @Test
    fun differentVersionMismatch() { // :57-61
        val info = FirmwareInfo()
        info.version = "FR02.020"
        assertTrue(info.hasFirmwareMismatch)
    }

    @Test
    fun gen1VersionMismatch() { // :63-67
        val info = FirmwareInfo()
        info.version = "FR01.010"
        assertTrue(info.hasFirmwareMismatch)
    }

    @Test
    fun emptyVersionNoMismatch() { // :69-73
        val info = FirmwareInfo()
        // Empty version → we haven't read DIS yet; must not report mismatch.
        assertFalse(info.hasFirmwareMismatch)
    }

    // MARK: - MAC formatting (:77-87)

    @Test
    fun macStoredAndReadBack() { // :77-82
        val mac = "AA:BB:CC:DD:EE:FF"
        val info = FirmwareInfo()
        info.mac = mac
        assertEquals(mac, info.mac)
    }

    @Test
    fun nilMacByDefault() { // :84-87
        val info = FirmwareInfo()
        assertNull(info.mac)
    }

    // MARK: - Equatable (:91-99)

    @Test
    fun equalInfos() { // :91-99
        val a = FirmwareInfo(
            version = "FR02.018", modelName = "RingConn",
            manufacturer = "RingConn", hardwareRevision = "1.0",
            mac = "AA:BB:CC:DD:EE:FF",
        )
        val b = FirmwareInfo(
            version = "FR02.018", modelName = "RingConn",
            manufacturer = "RingConn", hardwareRevision = "1.0",
            mac = "AA:BB:CC:DD:EE:FF",
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        // Kotlin-only (PL-2026-09-30-n): equality must be able to fail — one differing field breaks it.
        assertNotEquals(a, b.copy(hardwareRevision = null))
        assertNotEquals(a, b.copy(mac = "AA:BB:CC:DD:EE:00"))
    }

    // MARK: - Kotlin-only additions

    @Test
    fun generationRawValuesAndPinnedVersionMatchUpstream() {
        // FirmwareInfo.swift:25-29 — the strings the device-info screen shows; :46 — the pin.
        assertEquals("Gen 1", RingGeneration.GEN1.rawValue)
        assertEquals("Gen 2", RingGeneration.GEN2.rawValue)
        assertEquals("Gen 2 Air", RingGeneration.GEN2_AIR.rawValue)
        assertEquals("Gen 3", RingGeneration.GEN3.rawValue)
        assertEquals("Unknown", RingGeneration.UNKNOWN.rawValue)
        assertEquals(5, RingGeneration.entries.size)
        assertEquals("FR02.018", FirmwareInfo.PINNED_VERSION)
    }

    @Test
    fun prefixMatchIsCaseSensitiveAndAnchored() {
        // Swift `hasPrefix` is case-sensitive and anchored at index 0 (A3 — degraded DIS strings).
        fun gen(v: String) = FirmwareInfo(version = v).generation
        assertEquals(RingGeneration.UNKNOWN, gen("fr02.018"), "lower-case prefix")
        assertEquals(RingGeneration.UNKNOWN, gen(" FR02.018"), "leading space")
        assertEquals(RingGeneration.UNKNOWN, gen("XFR05.011"), "prefix, not substring")
        assertEquals(RingGeneration.UNKNOWN, gen("FR0"), "shorter than a prefix")
        assertEquals(RingGeneration.GEN2, gen("FR02"), "bare prefix")
        // A non-empty unknown version is a mismatch; a case-variant of the pin is too.
        assertTrue(FirmwareInfo(version = "fr02.018").hasFirmwareMismatch)
        assertTrue(FirmwareInfo(version = "FR05.011").hasFirmwareMismatch)
    }
}
