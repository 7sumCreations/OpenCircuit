package io.github.opencircuit.ble

import io.github.opencircuit.ringkit.Transport
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.util.Locale

/**
 * The ring scanner over any [ScanPort] (upstream `ios/OpenCircuit/BLE/RingScanner.swift:264-431`,
 * `:907-940` @ b1c2fdd).
 *
 * Each collection of [scan] starts exactly one scan and stops it when the collection ends, by
 * any route (Android throttles an app that starts more than five scans in 30 s). An
 * advertisement is a ring when its name starts with a ring prefix (`Transport.matchesRingName`)
 * or it advertises the ring's data service, and its address is six pairs of ASCII hex digits.
 * Each new ring is reported at once ([ScanUpdate.Found]); [QUIET_WINDOW] after the latest NEW ring
 * (a ring advertising again does not count) one ring is [ScanUpdate.Selected] and the scan ends,
 * while several are offered with [ScanUpdate.Choose] and the scan goes on, so a ring that appears
 * later is found and the list offered again. A scan that found nothing ends after [SCAN_TIMEOUT]
 * with [ScanUpdate.NoRingFound]; a scan error ends it with [ScanUpdate.Failed].
 *
 * Every matched advertisement is also kept in [lastMatch] for a connection-details screen.
 */
internal class RingScannerCore(private val port: ScanPort) : RingScanner, ScanDiagnostics {

    private val lastMatchFlow = MutableStateFlow<ScanDiagnostic?>(null)
    override val lastMatch: StateFlow<ScanDiagnostic?> = lastMatchFlow.asStateFlow()

    /** What the scan's loop takes, one at a time: the port's reports and its own two timers. */
    private sealed interface Input {
        class Port(val event: ScanEvent) : Input
        class QuietElapsed(val generation: Int) : Input
        data object TimedOut : Input
    }

    override fun scan(): Flow<ScanUpdate> = channelFlow {
        val inbox = Channel<Input>(Channel.UNLIMITED)
        val running = port.start { inbox.trySend(Input.Port(it)) }
        if (running == null) {
            send(ScanUpdate.Failed(START_REFUSED))
            return@channelFlow
        }
        try {
            val rings = LinkedHashMap<String, RememberedRing>()
            var quiet: Job? = null
            var generation = 0

            /** A ring's advertisement: a new ring is reported and re-arms the quiet window. */
            suspend fun onAdvertisement(event: ScanEvent.Result) {
                val ring = ringOf(event) ?: return
                lastMatchFlow.value = ScanDiagnostic(ring.addressType, event.rawScanRecord, event.rssi)
                val known = rings[ring.address]
                if (known == null) {
                    rings[ring.address] = ring
                    send(ScanUpdate.Found(rings.values.toList()))
                    quiet?.cancel()
                    val armed = ++generation
                    quiet = launch {
                        delay(QUIET_WINDOW.toMillis())
                        inbox.trySend(Input.QuietElapsed(armed))
                    }
                } else if (ring.name != null && ring.name != known.name) {
                    // A name in a later advertisement is kept; a frame without one keeps the old.
                    rings[ring.address] = known.copy(name = ring.name)
                    send(ScanUpdate.Found(rings.values.toList()))
                }
            }

            launch {
                delay(SCAN_TIMEOUT.toMillis())
                inbox.trySend(Input.TimedOut)
            }
            loop@ for (input in inbox) {
                when (input) {
                    is Input.Port -> when (val event = input.event) {
                        is ScanEvent.Error -> {
                            send(ScanUpdate.Failed(event.errorCode))
                            break@loop
                        }
                        is ScanEvent.Result -> onAdvertisement(event)
                    }
                    is Input.QuietElapsed -> {
                        if (input.generation != generation) continue@loop // a newer ring re-armed it
                        if (rings.size == 1) {
                            send(ScanUpdate.Selected(rings.values.single()))
                            break@loop
                        }
                        send(ScanUpdate.Choose(rings.values.toList()))
                    }
                    Input.TimedOut -> if (rings.isEmpty()) {
                        send(ScanUpdate.NoRingFound)
                        break@loop
                    }
                }
            }
        } finally {
            // Timers first (they only ever trySend, so one racing the close cannot throw), then
            // the scan itself, whichever way the collection ended.
            coroutineContext.cancelChildren()
            running.stop()
            inbox.close()
        }
    }

    /** The ring an advertisement names, or null when it is not a ring this app can use. */
    private fun ringOf(result: ScanEvent.Result): RememberedRing? {
        val matches = Transport.matchesRingName(result.name ?: "") ||
            result.serviceUuids.any { it.lowercase(Locale.ROOT) == Transport.DATA_SERVICE_UUID }
        if (!matches) return null
        val mac = macFromAddress(result.address) ?: return null
        return RememberedRing(formatMac(mac), result.reportedAddressType ?: addressTypeGuess(mac), result.name)
    }

    companion object {
        /** Quiet time after the latest new ring before the scan decides (upstream `selectionQuietWindow`, `:293`). */
        val QUIET_WINDOW: Duration = Duration.ofMillis(2_500)

        /** A scan that finds no ring gives up after this (upstream `scanTimeout`, `:299`). */
        val SCAN_TIMEOUT: Duration = Duration.ofSeconds(15)

        /** [ScanUpdate.Failed.errorCode] when Android refused to start the scan at all. */
        const val START_REFUSED: Int = -1
    }
}

/**
 * The address type when Android does not report one (before Android 15): a random static address
 * has the top two bits of its first byte set; anything else is taken as public, Android's own
 * default for a device built from an address alone. Once the ring is bonded, Android's bond record
 * supplies the true type at connect time.
 */
internal fun addressTypeGuess(mac: ByteArray): AddressType =
    if ((mac[0].toInt() and 0xC0) == 0xC0) AddressType.RANDOM else AddressType.PUBLIC
