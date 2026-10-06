package io.github.opencircuit.ble

import io.github.opencircuit.ble.FakeScanPort.Companion.RING_SERVICE
import io.github.opencircuit.ble.FakeScanPort.Companion.address
import io.github.opencircuit.ble.FakeScanPort.Companion.advertisementOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The scanner's record of the last matched advertisement, for a connection-details screen:
 * the raw advertisement, the address type and the signal strength, never the address.
 */
class ScanDiagnosticsTest {

    private fun TestScope.scanning(scanner: RingScanner): MutableList<ScanUpdate> {
        val updates = mutableListOf<ScanUpdate>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { scanner.scan().toList(updates) }
        runCurrent()
        return updates
    }

    @Test
    fun theScannerOffersItsDiagnosticsThroughACast() {
        val scanner: RingScanner = RingScannerCore(FakeScanPort())
        val diagnostics = scanner as? ScanDiagnostics
        assertTrue(diagnostics != null)
        assertNull(diagnostics.lastMatch.value, "nothing matched yet")
    }

    @Test
    fun aMatchedAdvertisementIsRecordedWithItsBytesTypeAndSignal() = runTest {
        val port = FakeScanPort()
        val scanner = RingScannerCore(port)
        scanning(scanner)
        val record = advertisementOf("RingConn Gen2-0001")

        port.advertise(address(0xC0, 0x01), name = "RingConn Gen2-0001", record = record, rssi = -67)
        runCurrent()

        assertEquals(ScanDiagnostic(AddressType.RANDOM, record, -67), scanner.lastMatch.value)
        // The guessed type when Android reports none, the reported one when it does.
        port.advertise(address(0xC0, 0x02), name = "RingConn Gen2-0002", reportedAddressType = AddressType.PUBLIC, rssi = -50)
        runCurrent()
        assertEquals(AddressType.PUBLIC, scanner.lastMatch.value?.addressType)
        assertEquals(-50, scanner.lastMatch.value?.rssi)
    }

    @Test
    fun anAdvertisementThatDoesNotMatchLeavesTheRecordAlone() = runTest {
        val port = FakeScanPort()
        val scanner = RingScannerCore(port)
        scanning(scanner)
        val record = advertisementOf(null)
        port.advertise(address(0xC0, 0x01), name = null, serviceUuids = listOf(RING_SERVICE), record = record, rssi = -70)
        runCurrent()

        port.advertise(address(0xC0, 0x02), name = "Pixel Buds", rssi = -40)
        port.advertise(address(0xC0, 0x03).replace(':', '-'), name = "RingConn Gen2-0003", rssi = -41) // never offered
        runCurrent()

        assertEquals(ScanDiagnostic(AddressType.RANDOM, record, -70), scanner.lastMatch.value)
    }

    @Test
    fun theRecordOutlivesItsScanAndTheNextScanReplacesIt() = runTest {
        val port = FakeScanPort()
        val scanner = RingScannerCore(port)
        scanning(scanner)
        port.advertise(address(0xC0, 0x01), name = "RingConn Gen2-0001", rssi = -60)
        advance(2_500) // selected: the scan ended
        assertEquals(0, port.runningScans)
        assertEquals(-60, scanner.lastMatch.value?.rssi)

        scanning(scanner)
        assertEquals(-60, scanner.lastMatch.value?.rssi, "kept until something new matches")
        port.advertise(address(0xC0, 0x01), name = "RingConn Gen2-0001", rssi = -45)
        runCurrent()
        assertEquals(-45, scanner.lastMatch.value?.rssi)
    }

    @Test
    fun theBytesAreCopiedInAndOut() {
        val source = advertisementOf("RingConn Gen2-0001")
        val expected = source.copyOf()
        val diagnostic = ScanDiagnostic(AddressType.RANDOM, source, -60)

        source[0] = 0x7F
        assertContentEquals(expected, diagnostic.rawScanRecord, "changing the caller's array changed the record")
        diagnostic.rawScanRecord[1] = 0x7F
        assertContentEquals(expected, diagnostic.rawScanRecord, "changing a read copy changed the record")
    }

    @Test
    fun theScannerKeepsItsOwnCopyOfWhatThePortDelivered() = runTest {
        val port = FakeScanPort()
        val scanner = RingScannerCore(port)
        // Collected on the test's queued dispatcher, so a report waits until the next runCurrent().
        backgroundScope.launch { scanner.scan().collect {} }
        runCurrent()
        val record = advertisementOf("RingConn Gen2-0001")
        val expected = record.copyOf()

        port.advertise(address(0xC0, 0x01), name = "RingConn Gen2-0001", record = record)
        record[0] = 0x7F // the caller's array changes before the scanner gets to the report
        runCurrent()

        assertContentEquals(expected, scanner.lastMatch.value?.rawScanRecord)
    }

    @Test
    fun twoRecordsAreEqualByContent() {
        val a = ScanDiagnostic(AddressType.RANDOM, advertisementOf("RingConn Gen2-0001"), -60)
        val same = ScanDiagnostic(AddressType.RANDOM, advertisementOf("RingConn Gen2-0001"), -60)
        assertEquals(a, same)
        assertEquals(a.hashCode(), same.hashCode())
        assertNotEquals(a, ScanDiagnostic(AddressType.PUBLIC, advertisementOf("RingConn Gen2-0001"), -60))
        assertNotEquals(a, ScanDiagnostic(AddressType.RANDOM, advertisementOf("RingConn Gen2-0002"), -60))
        assertNotEquals(a, ScanDiagnostic(AddressType.RANDOM, advertisementOf("RingConn Gen2-0001"), -61))
    }

    @Test
    fun nothingInTheRecordHoldsTheAddress() = runTest {
        val port = FakeScanPort()
        val scanner = RingScannerCore(port)
        scanning(scanner)
        val ringAddress = address(0xC0, 0xAD)
        port.advertise(ringAddress, name = "RingConn Gen2-00AD", reportedAddressType = AddressType.RANDOM, rssi = -60)
        runCurrent()
        val diagnostic = scanner.lastMatch.value!!

        // Its only values are the type, the advertisement's own bytes and the signal.
        val stringFields = ScanDiagnostic::class.java.declaredFields.filter { it.type == String::class.java }
        assertEquals(emptyList(), stringFields.map { it.name })
        assertContentEquals(advertisementOf("RingConn Gen2-00AD"), diagnostic.rawScanRecord)
        // Its text form shows neither the address nor the record's bytes (which carry the name).
        val text = diagnostic.toString()
        assertFalse(ringAddress in text, text)
        assertFalse(ringAddress.replace(":", "") in text.uppercase(), text)
        assertFalse("RingConn" in text, text)
        assertEquals("ScanDiagnostic(addressType=RANDOM, rawScanRecord=23 bytes, rssi=-60)", text)
    }
}
