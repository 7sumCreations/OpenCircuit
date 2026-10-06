package io.github.opencircuit.app

import io.github.opencircuit.app.connect.ConnectFlowController
import io.github.opencircuit.app.connect.PermissionSnapshot
import io.github.opencircuit.app.data.PrefsAppPrefs
import io.github.opencircuit.app.data.PrefsRememberedRingStore
import io.github.opencircuit.app.ring.RingViewModel
import io.github.opencircuit.ble.AdapterState
import io.github.opencircuit.ble.AddressType
import io.github.opencircuit.ble.RememberedRing
import io.github.opencircuit.ble.RingScanner
import io.github.opencircuit.ble.ScanUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope

/**
 * A [RingScanner] whose every collection runs [script], counting the scans started, the
 * collections still running and the ones cancelled. `:ble` ships no fake scanner, so the tests
 * implement the interface directly, keeping its contract: Selected / NoRingFound / Failed end the
 * flow; after Choose it goes on until the collection is cancelled.
 */
internal class ScriptedScanner(private val script: suspend FlowCollector<ScanUpdate>.() -> Unit) : RingScanner {
    var scans = 0
        private set
    var running = 0
        private set
    var cancelled = 0
        private set

    override fun scan(): Flow<ScanUpdate> = flow {
        scans++
        running++
        try {
            script()
        } catch (e: CancellationException) {
            cancelled++
            throw e
        } finally {
            running--
        }
    }
}

/** A ring nearby, by its advertised name; addresses are synthetic. */
internal fun nearby(name: String?, last: Int) = RememberedRing("C0:FF:EE:00:00:%02X".format(java.util.Locale.ROOT, last), AddressType.RANDOM, name)

internal val granted = PermissionSnapshot(scanGranted = true, connectGranted = true, rationale = false, restricted = false)
internal val nothingGranted = PermissionSnapshot(scanGranted = false, connectGranted = false, rationale = false, restricted = false)

/** The connect flow and the Ring screen's view model over it, on this test's virtual time. */
internal class ConnectScreen(
    val scanner: ScriptedScanner,
    val values: InMemoryKeyValues,
    val adapter: MutableStateFlow<AdapterState?>,
    val connected: MutableList<RememberedRing>,
    val logLines: MutableList<String>,
    val flow: ConnectFlowController,
    val viewModel: RingViewModel,
) {
    val link get() = viewModel.uiState.value.card.link
}

internal fun TestScope.connectScreen(
    scanner: ScriptedScanner,
    values: InMemoryKeyValues = InMemoryKeyValues(),
    adapter: AdapterState? = AdapterState.ON,
): ConnectScreen {
    val adapterFlow = MutableStateFlow(adapter)
    val connected = mutableListOf<RememberedRing>()
    val logLines = mutableListOf<String>()
    val flow = ConnectFlowController(
        prefs = PrefsAppPrefs(values),
        rings = PrefsRememberedRingStore(values),
        scanners = { scanner },
        connector = { connected += it },
        adapterState = adapterFlow,
        scope = backgroundScope,
        log = { logLines += it },
    )
    val viewModel = RingViewModel(controller = null, title = "Ring", scope = backgroundScope, connectFlow = flow, adapterState = adapterFlow)
    testScheduler.runCurrent()
    return ConnectScreen(scanner, values, adapterFlow, connected, logLines, flow, viewModel)
}
