package io.github.opencircuit.app

import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.opencircuit.app.connect.AndroidPermissionReader
import io.github.opencircuit.app.connect.ConnectFlowController
import io.github.opencircuit.app.connect.IntentSenderSheet
import io.github.opencircuit.app.connect.PairingSheet
import io.github.opencircuit.app.connect.ScanStep
import io.github.opencircuit.app.onboarding.Destination
import io.github.opencircuit.app.onboarding.LaunchFlow
import io.github.opencircuit.app.onboarding.OnboardingScreen
import io.github.opencircuit.app.ring.LinkAction
import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.ring.RingScreen
import io.github.opencircuit.app.ring.RingViewModel
import io.github.opencircuit.app.ui.OpenCircuitTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The single activity. It owns what only an activity can do: the Nearby-devices dialog, Android's
 * turn-Bluetooth-on dialog, and opening Settings, each only behind the user's own tap. It reads
 * the permission afresh on every resume and hands it to the view model.
 */
class MainActivity : ComponentActivity() {

    private val container: AppContainer get() = (application as OpenCircuitApp).container

    private val permissionReader by lazy { AndroidPermissionReader(this) }

    private val ringViewModel: RingViewModel by viewModels {
        viewModelFactory {
            initializer {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                val connectFlow = ConnectFlowController(
                    prefs = container.appPrefs,
                    rings = container.rememberedRings,
                    scanners = container.ringScannerFactory,
                    connector = container.ringSessions,
                    adapterState = container.adapterStates.state,
                    scope = scope,
                    log = container.log,
                    pairing = container.companionPairing,
                    monotonicMillis = container.monotonicMillis,
                )
                RingViewModel(
                    container.ringSessions,
                    container.ringTitle,
                    scope,
                    connectFlow,
                    container.adapterStates.state,
                    container.detailsSources,
                    prefs = container.appPrefs,
                    wallClock = { java.time.Instant.ofEpochMilli(container.clock.nowMillis()) },
                )
            }
        }
    }

    // Registered before the activity starts, as the result API requires.
    private val nearbyDevicesRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        ringViewModel.onPermissionResult(permissionReader.read())
    }

    private val bluetoothEnableRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) ringViewModel.onBluetoothEnabled(permissionReader.read())
    }

    // Android's companion-device sheet; its result code is the pairing's answer.
    private val pairingSheetRequest = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        ringViewModel.onPairingSheetResult(result.resultCode)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Read synchronously, before the first frame, so onboarding never flashes.
        val launch = LaunchFlow(container.appPrefs, container.log)
        setContent {
            OpenCircuitTheme {
                var destination by rememberSaveable { mutableStateOf(launch.start) }
                when (destination) {
                    Destination.Onboarding -> OnboardingScreen(onDone = { destination = launch.finishOnboarding() })
                    Destination.Ring -> {
                        // A grant or refusal made in Settings has no callback: read it on every resume.
                        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { ringViewModel.onResume(permissionReader.read()) }
                        val state by ringViewModel.uiState.collectAsStateWithLifecycle()
                        val sheet by ringViewModel.pairingSheet.collectAsStateWithLifecycle()
                        LaunchedEffect(sheet) { sheet?.let(::showPairingSheet) }
                        RingScreen(state = state, onAction = ::onRingAction, pulse = !reducedMotion())
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        container.adapterStates.start()
    }

    override fun onStop() {
        container.adapterStates.stop()
        super.onStop()
    }

    private fun onRingAction(action: RingAction) {
        if (action == RingAction.CopyDetails) {
            copyConnectionDetails()
            return
        }
        if (action !is RingAction.Link) {
            ringViewModel.onAction(action)
            return
        }
        when (action.action) {
            LinkAction.SCAN_AND_CONNECT, LinkAction.SEARCH_AGAIN, LinkAction.ALLOW_NEARBY ->
                if (ringViewModel.requestScan(permissionReader.read()) == ScanStep.ASK_PERMISSION) askForNearbyDevices()
            LinkAction.ASK_AGAIN -> askForNearbyDevices()
            LinkAction.TURN_ON_BLUETOOTH -> askToTurnOnBluetooth()
            LinkAction.BLUETOOTH_SETTINGS -> openBluetoothSettings()
            LinkAction.OPEN_APP_SETTINGS -> openAppSettings()
            LinkAction.CANCEL_SCAN, LinkAction.CONTINUE_PAIRING, LinkAction.TRY_AGAIN, LinkAction.CANCEL,
            LinkAction.STOP_RECONNECTING, LinkAction.DISCONNECT, LinkAction.PAIR_WITHOUT_SHEET,
            -> ringViewModel.onAction(action)
        }
    }

    /**
     * Shows Android's companion-device sheet ("Allow OpenCircuit to access …?"). If it cannot be
     * shown, the pairing goes on without it (Android's own pairing prompt follows).
     */
    private fun showPairingSheet(sheet: PairingSheet) {
        val sender = (sheet as? IntentSenderSheet)?.intentSender
        if (sender == null) {
            ringViewModel.onPairingSheetFailed()
            return
        }
        try {
            pairingSheetRequest.launch(IntentSenderRequest.Builder(sender).build())
            ringViewModel.onPairingSheetShown()
        } catch (_: ActivityNotFoundException) {
            ringViewModel.onPairingSheetFailed()
        }
    }

    /**
     * Puts Connection details on the clipboard, at the user's tap only. The text already has the
     * ring's address and name masked; nothing leaves the phone unless the user pastes it.
     */
    private fun copyConnectionDetails() {
        val text = ringViewModel.uiState.value.details.copyText
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("OpenCircuit connection details", text))
    }

    /** Android's "Nearby devices" dialog for both permissions; it answers at once if refused for good. */
    private fun askForNearbyDevices() {
        nearbyDevicesRequest.launch(AndroidPermissionReader.NEARBY_DEVICES)
    }

    /**
     * Android's own "turn on Bluetooth?" dialog (apps targeting API 33+ cannot turn it on
     * themselves). If no screen handles the request, or Android refuses it, Bluetooth settings
     * open instead.
     */
    private fun askToTurnOnBluetooth() {
        try {
            bluetoothEnableRequest.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (_: ActivityNotFoundException) {
            openBluetoothSettings()
        } catch (_: SecurityException) {
            openBluetoothSettings()
        }
    }

    /** Android's Bluetooth settings: where a ring that forgot this phone is forgotten in turn. */
    private fun openBluetoothSettings() {
        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    /** This app's page in Android's settings, where a permission refused for good can be granted. */
    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }
}

/**
 * True when the system asks for less motion: "Remove animations" sets the animator duration scale
 * to 0 (the same switch Android's own animations follow).
 */
private fun ComponentActivity.reducedMotion(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
