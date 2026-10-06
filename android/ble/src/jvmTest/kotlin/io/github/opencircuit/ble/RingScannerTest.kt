package io.github.opencircuit.ble

import io.github.opencircuit.ble.FakeScanPort.Companion.RING_SERVICE
import io.github.opencircuit.ble.FakeScanPort.Companion.address
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The foreground ring scan (upstream `A/BLE/RingScanner.swift` @ b1c2fdd, `A/` = `ios/OpenCircuit/`):
 * a ring matches by its advertised name (`Transport.matchesRingName`, upstream `:913-914`) or by
 * advertising the ring's data service; 2.5 s after the latest NEW ring (`selectionQuietWindow`,
 * `:293`; re-armed only for a new ring, `:937-938`) one ring is selected and several are offered
 * to choose from; a scan that found nothing gives up after 15 s (`scanTimeout`, `:299`,
 * `:418-430`). Times are virtual; every address is synthetic, built at run time.
 */
class RingScannerTest {

    private val ringA = address(0xC0, 0x01)
    private val ringB = address(0xC0, 0x02)
    private val ringC = address(0xC0, 0x03)

    private class Collection(val updates: List<ScanUpdate>, val job: Job)

    private fun TestScope.collect(scanner: RingScanner): Collection {
        val updates = mutableListOf<ScanUpdate>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { scanner.scan().toList(updates) }
        runCurrent()
        return Collection(updates, job)
    }

    private fun ring(address: String, name: String?, type: AddressType = AddressType.RANDOM) = RememberedRing(address, type, name)

    private fun ringName(n: Int) = String.format(Locale.ROOT, "RingConn Gen2-%04d", n)

    @Test
    fun aRingMatchedByNameIsFoundAndSelectedAfterExactly2500MillisecondsOfQuiet() = runTest {
        val port = FakeScanPort()
        val scan = collect(RingScannerCore(port))

        port.advertise(ringA, name = "RingConn Gen2-0001")
        runCurrent()
        val a = ring(ringA, "RingConn Gen2-0001")
        assertEquals(listOf<ScanUpdate>(ScanUpdate.Found(listOf(a))), scan.updates)

        advance(2_499)
        assertEquals(1, scan.updates.size, "decided before 2.5 s of quiet: ${scan.updates}")
        advance(1)
        assertEquals(listOf(ScanUpdate.Found(listOf(a)), ScanUpdate.Selected(a)), scan.updates)
        assertTrue(scan.job.isCompleted, "the scan ends with its selection")
        assertEquals(0, port.runningScans)
        assertEquals(1, port.stops)
    }

    @Test
    fun aRingThatAdvertisesOnlyTheDataServiceIsFound() = runTest {
        val port = FakeScanPort()
        val scan = collect(RingScannerCore(port))

        port.advertise(ringA, name = null, serviceUuids = listOf("0000180f-0000-1000-8000-00805f9b34fb", RING_SERVICE))
        port.advertise(ringB, name = null, serviceUuids = listOf(RING_SERVICE.uppercase(Locale.ROOT)))
        runCurrent()

        val a = ring(ringA, null)
        val b = ring(ringB, null)
        assertEquals(listOf<ScanUpdate>(ScanUpdate.Found(listOf(a)), ScanUpdate.Found(listOf(a, b))), scan.updates)
    }

    @Test
    fun anAdvertisementThatMatchesNeitherWayIsIgnored() = runTest {
        val port = FakeScanPort()
        val scanner = RingScannerCore(port)
        val scan = collect(scanner)

        port.advertise(address(0xC0, 0x10), name = "Pixel Buds", serviceUuids = listOf("0000180d-0000-1000-8000-00805f9b34fb"))
        port.advertise(address(0xC0, 0x11), name = "ringconn Gen2-0011") // the prefixes are case-sensitive, as upstream
        port.advertise(address(0xC0, 0x12), name = "")
        port.advertise(address(0xC0, 0x13), name = null)
        port.advertise(address(0xC0, 0x14), name = "My RingConn") // a prefix, not anywhere in the name
        // The ring's notify characteristic, not its service:
        port.advertise(address(0xC0, 0x15), name = null, serviceUuids = listOf("8327ad97-2d87-4a22-a8ce-6dd7971c0437"))
        runCurrent()

        assertEquals(emptyList(), scan.updates)
        assertEquals(null, scanner.lastMatch.value)
        advance(15_000)
        assertEquals(listOf<ScanUpdate>(ScanUpdate.NoRingFound), scan.updates)
    }

    @Test
    fun theShorterPrefixRingAlsoMatches() = runTest {
        val port = FakeScanPort()
        val scan = collect(RingScannerCore(port))

        port.advertise(ringA, name = "Ring-0001")
        runCurrent()

        assertEquals(listOf<ScanUpdate>(ScanUpdate.Found(listOf(ring(ringA, "Ring-0001")))), scan.updates)
    }

    @Test
    fun twoRingsAreOfferedToChooseFromAndTheScanGoesOnForMore() = runTest {
        val port = FakeScanPort()
        val scan = collect(RingScannerCore(port))
        val a = ring(ringA, "RingConn Gen2-0001")
        val b = ring(ringB, "RingConn Gen2-0002")
        val c = ring(ringC, "RingConn Gen2-0003")

        port.advertise(ringA, name = "RingConn Gen2-0001")
        advance(2_000)
        port.advertise(ringB, name = "RingConn Gen2-0002")
        runCurrent()
        assertEquals(listOf<ScanUpdate>(ScanUpdate.Found(listOf(a)), ScanUpdate.Found(listOf(a, b))), scan.updates)

        // The second ring re-armed the quiet window: nothing at 2.5 s, the list at 4.5 s.
        advance(2_499)
        assertEquals(2, scan.updates.size, "decided before 2.5 s after the second ring: ${scan.updates}")
        advance(1)
        assertEquals(ScanUpdate.Choose(listOf(a, b)), scan.updates.last())
        assertFalse(scan.job.isCompleted, "the list stays live until the user picks")
        assertEquals(0, port.stops)

        // A third ring later: found, and the list offered again 2.5 s after it. Never a timeout.
        advance(1_500)
        port.advertise(ringC, name = "RingConn Gen2-0003")
        advance(2_500)
        assertEquals(
            listOf(
                ScanUpdate.Found(listOf(a)),
                ScanUpdate.Found(listOf(a, b)),
                ScanUpdate.Choose(listOf(a, b)),
                ScanUpdate.Found(listOf(a, b, c)),
                ScanUpdate.Choose(listOf(a, b, c)),
            ),
            scan.updates,
        )
        advance(60_000)
        assertEquals(5, scan.updates.size)
        assertEquals(1, port.starts)
    }

    @Test
    fun aRingAdvertisingAgainDoesNotPushTheDecisionOut() = runTest {
        val port = FakeScanPort()
        val scan = collect(RingScannerCore(port))

        port.advertise(ringA, name = "RingConn Gen2-0001", rssi = -70)
        advance(2_000)
        port.advertise(ringA, name = "RingConn Gen2-0001", rssi = -60)
        advance(400)
        port.advertise(ringA, name = "RingConn Gen2-0001", rssi = -55)
        advance(100)

        val a = ring(ringA, "RingConn Gen2-0001")
        assertEquals(listOf(ScanUpdate.Found(listOf(a)), ScanUpdate.Selected(a)), scan.updates)
    }

    @Test
    fun aNameSeenAfterAServiceOnlyAdvertisementIsKeptWithoutRestartingTheQuietWindow() = runTest {
        val port = FakeScanPort()
        val scan = collect(RingScannerCore(port))

        port.advertise(ringA, name = null, serviceUuids = listOf(RING_SERVICE))
        advance(1_000)
        port.advertise(ringA, name = "RingConn Gen2-0001")
        advance(1_000)
        port.advertise(ringA, name = null, serviceUuids = listOf(RING_SERVICE)) // a frame without the name keeps it
        advance(500)

        val named = ring(ringA, "RingConn Gen2-0001")
        assertEquals(
            listOf(ScanUpdate.Found(listOf(ring(ringA, null))), ScanUpdate.Found(listOf(named)), ScanUpdate.Selected(named)),
            scan.updates,
        )
    }

    @Test
    fun noRingWithin15SecondsEndsTheScanWithNoRingFound() = runTest {
        val port = FakeScanPort()
        val scan = collect(RingScannerCore(port))

        advance(14_999)
        assertEquals(emptyList(), scan.updates)
        assertEquals(1, port.runningScans)
        advance(1)
        assertEquals(listOf<ScanUpdate>(ScanUpdate.NoRingFound), scan.updates)
        assertTrue(scan.job.isCompleted)
        assertEquals(0, port.runningScans)
    }

    @Test
    fun aRingFoundJustBeforeTheTimeoutIsStillSelected() = runTest {
        val port = FakeScanPort()
        val scan = collect(RingScannerCore(port))

        advance(14_000)
        port.advertise(ringA, name = "RingConn Gen2-0001")
        advance(1_000) // 15 s: the timeout passes, a ring was found
        assertEquals(1, scan.updates.size)
        advance(1_500)

        val a = ring(ringA, "RingConn Gen2-0001")
        assertEquals(listOf(ScanUpdate.Found(listOf(a)), ScanUpdate.Selected(a)), scan.updates)
    }

    @Test
    fun aScanErrorEndsTheScanWithItsCode() = runTest {
        val port = FakeScanPort()
        val scan = collect(RingScannerCore(port))
        port.advertise(ringA, name = "RingConn Gen2-0001")

        port.fail(6) // ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY
        runCurrent()

        assertEquals(ScanUpdate.Failed(6), scan.updates.last())
        assertTrue(scan.job.isCompleted)
        assertEquals(0, port.runningScans)
        advance(60_000)
        assertEquals(2, scan.updates.size, "nothing after the failure: ${scan.updates}")
    }

    @Test
    fun aScanAndroidRefusesToStartFailsAtOnceWithMinusOne() = runTest {
        val port = FakeScanPort().apply { refuseStarts = true }
        val scan = collect(RingScannerCore(port))

        assertEquals(listOf<ScanUpdate>(ScanUpdate.Failed(-1)), scan.updates)
        assertTrue(scan.job.isCompleted)
        assertEquals(1, port.starts)
        assertEquals(0, port.runningScans)
    }

    @Test
    fun eachCollectionStartsExactlyOneScanAndCancellingItStopsTheScan() = runTest {
        val port = FakeScanPort()
        val scanner = RingScannerCore(port)
        val flow = scanner.scan()
        runCurrent()
        assertEquals(0, port.starts, "a scan starts only when collected")

        val first = collect(scanner)
        repeat(20) { port.advertise(address(0xC0, it), name = ringName(it)) }
        advance(1_000)
        assertEquals(1, port.starts)
        assertEquals(1, port.runningScans)

        first.job.cancel()
        runCurrent()
        assertEquals(1, port.stops)
        assertEquals(0, port.runningScans)

        // The same flow collected again starts one more scan, from an empty list.
        val updates = mutableListOf<ScanUpdate>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.toList(updates) }
        runCurrent()
        assertEquals(2, port.starts)
        port.advertise(ringA, name = "RingConn Gen2-0001")
        runCurrent()
        assertEquals(listOf<ScanUpdate>(ScanUpdate.Found(listOf(ring(ringA, "RingConn Gen2-0001")))), updates)
    }

    @Test
    fun theAddressTypeAndroidReportsIsKept() = runTest {
        val port = FakeScanPort()
        val scan = collect(RingScannerCore(port))

        // Reported types win over what the address's top bits would suggest, both ways.
        port.advertise(address(0x40, 0x01), name = "RingConn Gen2-0001", reportedAddressType = AddressType.RANDOM)
        port.advertise(address(0xC0, 0x02), name = "RingConn Gen2-0002", reportedAddressType = AddressType.PUBLIC)
        runCurrent()

        assertEquals(
            ScanUpdate.Found(
                listOf(
                    RememberedRing(address(0x40, 0x01), AddressType.RANDOM, "RingConn Gen2-0001"),
                    RememberedRing(address(0xC0, 0x02), AddressType.PUBLIC, "RingConn Gen2-0002"),
                ),
            ),
            scan.updates.last(),
        )
    }

    @Test
    fun withoutAReportedTypeTheTopTwoBitsOfTheFirstByteDecide() = runTest {
        val port = FakeScanPort()
        val scan = collect(RingScannerCore(port))
        // 0b11 → random static; 0b10, 0b01, 0b00 → public (the guess cannot tell a resolvable or
        // non-resolvable random address from a public one, and Android's own default is public).
        val firstBytes = listOf(0xC0, 0xFF, 0xBF, 0x80, 0x7F, 0x40, 0x00)
        firstBytes.forEachIndexed { i, first -> port.advertise(address(first, i), name = ringName(i)) }
        runCurrent()

        val found = (scan.updates.last() as ScanUpdate.Found).rings.map { it.addressType }
        val r = AddressType.RANDOM
        val p = AddressType.PUBLIC
        assertEquals(listOf(r, r, p, p, p, p, p), found)
    }

    @Test
    fun addressesAreKeptUpperCaseAndTheSameRingInEitherCaseIsOneRing() = runTest {
        val port = FakeScanPort()
        val scan = collect(RingScannerCore(port))
        val upper = address(0xCA, 0xFE)

        port.advertise(upper.lowercase(Locale.ROOT), name = "RingConn Gen2-cafe")
        port.advertise(upper, name = "RingConn Gen2-cafe")
        runCurrent()

        assertEquals(listOf<ScanUpdate>(ScanUpdate.Found(listOf(ring(upper, "RingConn Gen2-cafe")))), scan.updates)
    }

    @Test
    fun anAddressThatIsNotSixPairsOfAsciiHexDigitsIsNeverOffered() = runTest {
        val port = FakeScanPort()
        val scan = collect(RingScannerCore(port))
        val good = address(0xC0, 0x01)

        port.advertise(good.replace('0', '０'), name = "RingConn Gen2-0001") // fullwidth zeros
        port.advertise(good.replace('0', '٠'), name = "RingConn Gen2-0001") // Arabic-Indic zeros
        port.advertise(good.replace(':', '-'), name = "RingConn Gen2-0001")
        port.advertise(good.substringAfter(':'), name = "RingConn Gen2-0001")
        port.advertise("", name = "RingConn Gen2-0001")
        runCurrent()

        assertEquals(emptyList(), scan.updates)
        advance(2_500)
        assertEquals(emptyList(), scan.updates)
    }
}
