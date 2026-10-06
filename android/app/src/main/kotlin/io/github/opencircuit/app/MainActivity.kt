package io.github.opencircuit.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.opencircuit.app.onboarding.Destination
import io.github.opencircuit.app.onboarding.LaunchFlow
import io.github.opencircuit.app.onboarding.OnboardingScreen
import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.ring.RingScreen
import io.github.opencircuit.app.ring.RingViewModel
import io.github.opencircuit.app.ui.OpenCircuitTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The single activity. */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as OpenCircuitApp).container
        // Read synchronously, before the first frame, so onboarding never flashes.
        val launch = LaunchFlow(container.appPrefs, container.log)
        setContent {
            OpenCircuitTheme {
                var destination by rememberSaveable { mutableStateOf(launch.start) }
                when (destination) {
                    Destination.Onboarding -> OnboardingScreen(onDone = { destination = launch.finishOnboarding() })
                    Destination.Ring -> {
                        val ringViewModel = viewModel {
                            RingViewModel(
                                controller = container.ringSession,
                                title = container.ringTitle,
                                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                            )
                        }
                        val state by ringViewModel.uiState.collectAsStateWithLifecycle()
                        RingScreen(
                            state = state,
                            onAction = { action ->
                                if (action is RingAction.Link && action.action.opensSystemSettings) {
                                    openBluetoothSettings()
                                } else {
                                    ringViewModel.onAction(action)
                                }
                            },
                            pulse = !reducedMotion(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Opens Android's Bluetooth settings: where a ring that forgot this phone is forgotten in turn,
 * and where Bluetooth is turned on. Both are the user's own tap, never automatic.
 */
private fun ComponentActivity.openBluetoothSettings() {
    startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
}

/**
 * True when the system asks for less motion: "Remove animations" sets the animator duration scale
 * to 0 (the same switch Android's own animations follow).
 */
private fun ComponentActivity.reducedMotion(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
